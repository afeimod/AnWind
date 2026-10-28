package com.anwind.apps.containers.launcher

import com.anwind.apps.containers.ContainerManager
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * PulseAudio 音频管理器 —— 原生音频链路。
 *
 * 移植 anwind-wine 脚本 audio_setup 的语义，但由 App 端以
 * [NativeShellExecutor] 原生拉起 rootfs 自带的 pulseaudio
 * （bionic 构建，default.pa 内置 module-sles-sink 直连 Android
 * OpenSLES），并与 WinFusion PulseAudioServer 的健壮性设计对齐：
 *
 *  1. **陈旧 pid 文件必须由我们清**：daemon 模式的 pulseaudio 按 pid
 *     文件判断"是否已有守护在跑"。App 进程死亡留下的孤儿 pulse 或
 *     pid 被内核回收后的陈旧文件，会让下一次 `--start` 打印
 *     "Daemon already running." 后秒退，socket 永不出现 —— 表现为
 *     随机的"音频无声"。启动前先校验 pid 真伪，死了就清 pid 与
 *     socket 残留（WinFusion 同款教训）。
 *  2. unix socket 就绪采用短轮询（6 × 500ms，与脚本一致），
 *     失败再尝试 TCP 4713 兜底（rootfs 自己的实例 + 匿名鉴权），
 *     最终仍失败则给出诊断提示但**不阻断容器启动**（音频只是降级）。
 *
 * 守护进程以 `--exit-idle-time=-1` 常驻（对齐脚本行为：跨会话存活，
 * 终端用户与 App 共享同一实例），生命周期不挂在单个 wine 会话上。
 */
object PulseAudioManager {

    private val RFS_ROOT = ContainerManager.ROOTFS_ROOT
    private val RFS_USR = "$RFS_ROOT/usr"

    /** rootfs 自带 pulseaudio 二进制。 */
    private val PULSE_BIN = File("$RFS_USR/bin/pulseaudio")

    /** unix socket（XDG_RUNTIME_DIR/pulse/native）。 */
    private val PULSE_SOCK = File("$RFS_USR/tmp/pulse/native")

    /** pid 文件（daemon 模式）。 */
    private val PULSE_PID = File("$RFS_USR/tmp/pulse/pid")

    /** 该 pulse 进程所需的最小环境。 */
    private fun pulseEnv(): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        env["HOME"] = "$RFS_ROOT/home"
        env["TMPDIR"] = "$RFS_USR/tmp"
        env["XDG_RUNTIME_DIR"] = "$RFS_USR/tmp"
        env["XDG_CONFIG_HOME"] = "$RFS_ROOT/home/.config"
        env["XDG_CACHE_HOME"] = "$RFS_ROOT/home/.cache"
        env["LD_LIBRARY_PATH"] = "$RFS_USR/lib"
        env["PATH"] = "$RFS_USR/bin:/system/bin"
        env["PULSE_RUNTIME_PATH"] = "$RFS_USR/tmp/pulse"
        env["PULSE_STATE_PATH"] = "$RFS_USR/tmp/pulse"
        return env
    }

    /**
     * 确保音频守护就绪并返回 PULSE_SERVER 值。
     *
     * @param log 决策行输出（写入会话日志）
     * @return PULSE_SERVER 值（unix:… 或 TCP）；从未就绪时返回
     *         unix socket 路径（对齐脚本兜底：宁可给 wine 一个
     *         连不上的端点，也不静默切换到错误的实例）。
     */
    @Synchronized
    fun ensureServer(log: (String) -> Unit = {}): String {
        // 守护已在跑：直接复用（跨会话常驻设计）
        if (PULSE_SOCK.exists()) return "unix:${PULSE_SOCK.absolutePath}"
        if (!PULSE_BIN.isFile) {
            // 老 rootfs 未内置 pulseaudio —— 最后借道 App 侧 TCP 4713
            // （与脚本终态兜底一致）
            log("rootfs 未内置 pulseaudio，音频走 App 侧 TCP 4713 兜底")
            return "127.0.0.1:4713"
        }

        // 陈旧 pid 清理：pid 死了但文件残留 → 清掉，否则 --start 秒退
        discardStaleRuntime(log)

        return try {
            // 首次拉起（daemon 模式，--start 由 pulse 自行 fork 后台，
            // 与终端侧 anwind-wine 的调用参数逐字一致）
            NativeShellExecutor.create()
                .setCommand(
                    PULSE_BIN.absolutePath,
                    "--start", "--exit-idle-time=-1", "--disallow-exit=false"
                )
                .putEnv(pulseEnv())
                .setWorkingDir(File("$RFS_ROOT/home"))
                .exec()
                .waitFor(8, java.util.concurrent.TimeUnit.SECONDS)

            // 等待默认 unix socket 就位（default.pa 自带
            // module-native-protocol-unix → $XDG_RUNTIME_DIR/pulse/native）
            if (awaitSocket(6, 500)) {
                "unix:${PULSE_SOCK.absolutePath}"
            } else {
                tryTcpFallback(log)
            }
        } catch (e: Exception) {
            log("PulseAudio 启动异常：${e.message}（音频可能无声）")
            "unix:${PULSE_SOCK.absolutePath}"
        }
    }

    /** TCP 4713 兜底（rootfs 自己的实例 + 匿名鉴权，与脚本一致）。 */
    private fun tryTcpFallback(log: (String) -> Unit): String {
        return try {
            NativeShellExecutor.create()
                .setCommand(
                    PULSE_BIN.absolutePath,
                    "--start", "--exit-idle-time=-1",
                    "--load=module-native-protocol-tcp port=4713 " +
                        "auth-ip-acl=127.0.0.1 auth-anonymous=1"
                )
                .putEnv(pulseEnv())
                .setWorkingDir(File("$RFS_ROOT/home"))
                .exec()
                .waitFor(5, java.util.concurrent.TimeUnit.SECONDS)

            if (tcpReachable(600)) {
                log("PulseAudio unix socket 未就绪，已切换 TCP 4713")
                "127.0.0.1:4713"
            } else {
                log("警告: rootfs PulseAudio 未就绪（音频可能无声；可查看会话日志排障）")
                "unix:${PULSE_SOCK.absolutePath}"
            }
        } catch (_: Exception) {
            "unix:${PULSE_SOCK.absolutePath}"
        }
    }

    /**
     * 校验并清理陈旧运行时残留（pid 文件 / socket）。
     *
     * pid 文件存在但对应进程已死 → 删除 pid 与 socket 残留。
     * （App 与 rootfs 同 UID，`/proc/<pid>` 存在且 cmdline 含
     * pulseaudio 视为活进程。）
     */
    private fun discardStaleRuntime(log: (String) -> Unit) {
        runCatching {
            if (PULSE_PID.isFile) {
                val pid = PULSE_PID.readText().trim().toIntOrNull()
                val alive = pid != null && pid > 0 &&
                    File("/proc/$pid/cmdline").let { f ->
                        f.isFile && f.readText().contains("pulseaudio")
                    }
                if (!alive) {
                    PULSE_PID.delete()
                    PULSE_SOCK.delete()
                    log("已清理陈旧 PulseAudio pid/socket 残留（pid=${pid ?: "?"}）")
                }
            }
            // 无 pid 文件但 socket 残留（进程已死）同样清掉，
            // 避免客户端反复连一个死端点
            if (!PULSE_PID.isFile && PULSE_SOCK.exists()) {
                val anyAlive = scanPulseAlive()
                if (!anyAlive) PULSE_SOCK.delete()
            }
        }
    }

    /** 扫描 /proc 是否还有本 UID 的 pulseaudio 存活。 */
    private fun scanPulseAlive(): Boolean = try {
        File("/proc").listFiles { f ->
            f.name.toIntOrNull() != null
        }.orEmpty().any { proc ->
            runCatching {
                val cmdline = File(proc, "cmdline")
                cmdline.isFile && cmdline.readText().contains("pulseaudio")
            }.getOrDefault(false)
        }
    } catch (_: Exception) {
        false
    }

    /** 轮询等待 unix socket 就位。 */
    private fun awaitSocket(rounds: Int, intervalMs: Long): Boolean {
        repeat(rounds) {
            if (PULSE_SOCK.exists()) return true
            runCatching { Thread.sleep(intervalMs) }
        }
        return PULSE_SOCK.exists()
    }

    /** TCP 端口连通性探测。 */
    private fun tcpReachable(timeoutMs: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", 4713), timeoutMs)
            true
        }
    } catch (_: Exception) {
        false
    }

    /**
     * 停止音频守护（`pulseaudio -k`，经原生执行器）。
     * 常规会话停止**不**调用（守护跨会话存活是设计行为）；供
     * 重置/诊断类操作使用。
     */
    @Synchronized
    fun stop() {
        runCatching {
            if (!PULSE_BIN.isFile) return
            NativeShellExecutor.create()
                .setCommand(PULSE_BIN.absolutePath, "-k")
                .putEnv(pulseEnv())
                .setWorkingDir(File("$RFS_ROOT/home"))
                .exec()
                .waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            PULSE_PID.delete()
            PULSE_SOCK.delete()
        }
    }
}
