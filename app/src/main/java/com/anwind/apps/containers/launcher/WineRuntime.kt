package com.anwind.apps.containers.launcher

import com.anwind.apps.containers.ContainerBackend
import com.anwind.apps.containers.ContainerData
import com.anwind.apps.containers.ContainerManager
import java.io.File
import java.io.RandomAccessFile

/**
 * Wine 运行时解析 —— 原生启动链的第一步。
 *
 * 职责（完整移植 anwind-wine 脚本的 resolve_wine_dir / detect_wine_arch /
 * pick_backend / auto_backend 四段逻辑，语义逐条对齐）：
 *  1. 依容器 `wine=` → 全局 `default_wine=` → `/usr/opt/wine` 解析 wine 目录；
 *  2. 读 wine 二进制 ELF 头的 e_machine（偏移 18，2 字节小端）判定形态：
 *     aarch64（bionic-arm64ec 构建，new WoW64 原生跑）或 x86_64（经
 *     box64 / FEX / native 跑）；
 *  3. 依容器 backend 配置 + 设备架构 + 组件可用性推导最终后端。
 *
 * App 端从 [ContainerManager.listWines] 同源读取槽位信息，与终端 CLI
 * （anwind-container wine-list）看到的数据完全一致。
 */
object WineRuntime {

    /** wine 二进制 ELF 形态。 */
    enum class Arch(val id: String) {
        AARCH64("aarch64"),
        X86_64("x86_64"),
        UNKNOWN("unknown")
    }

    /**
     * 一次启动所需的全部运行时定位结果。
     *
     * @property wineDir       wine 安装目录（/usr/opt/<槽位>）
     * @property arch          wine 二进制 ELF 形态
     * @property backend       最终选定的执行后端
     * @property wineBinary    wine 主二进制
     * @property wineServerBin wineserver 二进制（同目录）
     * @property wineBootBin   wineboot 二进制（同目录）
     */
    data class Resolved(
        val wineDir: File,
        val arch: Arch,
        val backend: ContainerBackend,
        val wineBinary: File,
        val wineServerBin: File,
        val wineBootBin: File
    )

    /** rootfs 侧路径常量的本地别名（与 ContainerManager 逐字对齐）。 */
    private val RFS_ROOT = ContainerManager.ROOTFS_ROOT
    private val RFS_USR = "$RFS_ROOT/usr"
    private val WINE_OPT = ContainerManager.WINE_OPT_DIR

    /** 旧版 hangover wine 兜底路径（rootfs 打包时内置的 aarch64 构建）。 */
    val HANGOVER_BIN = File("$RFS_USR/opt/bin/wine")

    /** FEX-Emu 解释器路径。 */
    val FEX_BIN = File("$RFS_USR/opt/fex/bin/FEXInterpreter")

    /** box64 候选路径（rootfs local → rootfs bin → App 前缀）。 */
    fun locateBox64(): File? = listOf(
        File("$RFS_USR/local/bin/box64"),
        File("$RFS_USR/bin/box64"),
        File("${ContainerManager.APP_PREFIX}/bin/box64")
    ).firstOrNull { it.isFile && it.canExecute() }

    /**
     * 解析容器对应的 wine 运行时（resolve + detect + pick 一步到位）。
     *
     * @param container 容器配置（wine= / backend= 字段）
     * @return 解析结果；错误时抛出 [IllegalStateException]（调用方转用户提示）。
     */
    fun resolve(container: ContainerData): Resolved {
        // ---- 1) wine 目录（容器 wine= → 全局 default_wine= → /usr/opt/wine） ----
        val wineDir = resolveWineDir(container)
            ?: throw IllegalStateException(
                "未找到 wine：请先安装（App 端 Wine 选择器一键安装，或终端 anwind-container install-wine）"
            )
        val wineBinary = File(wineDir, "bin/wine")
        if (!wineBinary.isFile) {
            throw IllegalStateException("wine 主程序缺失：${wineBinary.absolutePath}")
        }

        // ---- 2) ELF 形态探测 ----
        val arch = detectElfArch(wineBinary)

        // ---- 3) 后端选择 ----
        val backend = pickBackend(container, arch, wineBinary)

        return Resolved(
            wineDir = wineDir,
            arch = arch,
            backend = backend,
            wineBinary = wineBinary,
            wineServerBin = File(wineDir, "bin/wineserver"),
            wineBootBin = File(wineDir, "bin/wineboot")
        )
    }

    /**
     * 解析 wine 安装目录。容器 `wine=` 支持目录名（/usr/opt/<名>）或
     * 绝对路径；空则全局 default_wine，再空则主槽位 /usr/opt/wine。
     */
    private fun resolveWineDir(container: ContainerData): File? {
        val explicit = container.wine.trim()
        val dir: File = when {
            explicit.startsWith("/") -> File(explicit)
            explicit.isNotEmpty() -> File(WINE_OPT, explicit)
            else -> {
                val global = readGlobalConfKey("default_wine")
                if (global.isNotBlank()) File(WINE_OPT, global) else File(WINE_OPT, "wine")
            }
        }
        return dir.takeIf { it.isDirectory }
    }

    /** 读全局 container.conf 的指定键（与 CLI conf_read 同源）。 */
    private fun readGlobalConfKey(key: String): String {
        val f = File(ContainerManager.GLOBAL_CONF)
        if (!f.isFile) return ""
        f.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("$key=")) return line.removePrefix("$key=").trim()
        }
        return ""
    }

    /**
     * 读 ELF 头 e_machine（偏移 18，2 字节小端）。
     * 对齐脚本 detect_wine_arch：b7 00 = aarch64；3e 00 = x86_64。
     */
    fun detectElfArch(file: File): Arch {
        if (!file.isFile) return Arch.UNKNOWN
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 20) return Arch.UNKNOWN
                raf.seek(18)
                val b0 = raf.read()
                val b1 = raf.read()
                when {
                    b0 == 0xB7 && b1 == 0x00 -> Arch.AARCH64
                    b0 == 0x3E && b1 == 0x00 -> Arch.X86_64
                    else -> Arch.UNKNOWN
                }
            }
        } catch (_: Exception) {
            Arch.UNKNOWN
        }
    }

    /** 设备架构（aarch64 / x86_64）。 */
    private val deviceArch: String =
        System.getProperty("os.arch", "aarch64").lowercase()

    /**
     * 后端选择（pick_backend + auto_backend 合并移植）。
     *
     * 显式 backend= 时逐项校验组件就位；auto 时按设备架构 + wine ELF
     * 形态 + 组件可用性推导：aarch64 设备 + aarch64 wine → arm64ec，
     * x86_64 wine → box64（有 box64 时）；兜底 hangover 旧路径。
     */
    private fun pickBackend(
        container: ContainerData,
        arch: Arch,
        wineBinary: File
    ): ContainerBackend {
        var backend = container.backend
        if (backend == ContainerBackend.AUTO) {
            val auto = autoBackend(arch)
            if (auto == null) {
                throw IllegalStateException(
                    "没有可用后端：既无 x86_64 wine + box64，也无 arm64ec/hangover wine。" +
                        "请先安装 wine 与 box64（App 端安装器或 anwind-container doctor）"
                )
            }
            backend = auto
        }
        when (backend) {
            ContainerBackend.BOX64 -> {
                if (locateBox64() == null) {
                    throw IllegalStateException("box64 未安装（anwind-container doctor 查看详情）")
                }
                if (!wineBinary.isFile) {
                    throw IllegalStateException("x86_64 wine 未安装：请先安装 wine")
                }
                if (arch == Arch.AARCH64) {
                    throw IllegalStateException(
                        "所选 wine 是 aarch64/arm64ec 形态，box64 后端不适用——backend 改 arm64ec 或 auto"
                    )
                }
            }
            ContainerBackend.ARM64EC -> {
                if (!wineBinary.isFile) {
                    throw IllegalStateException("arm64ec wine 未安装：请安装 bionic-arm64ec 构建")
                }
                if (arch != Arch.AARCH64) {
                    throw IllegalStateException(
                        "所选 wine 不是 aarch64 形态（当前: ${arch.id}）——arm64ec 后端需要 bionic-arm64ec 构建"
                    )
                }
            }
            ContainerBackend.HANGOVER -> {
                if (!HANGOVER_BIN.isFile) {
                    throw IllegalStateException(
                        "hangover wine 不在 ${HANGOVER_BIN.absolutePath}——" +
                            "建议安装 CI 构建的新版 wine（bionic-arm64ec）"
                    )
                }
            }
            ContainerBackend.FEXCORE -> {
                if (!FEX_BIN.isFile) {
                    throw IllegalStateException("FEXInterpreter 不在 ${FEX_BIN.absolutePath}（FEX-Emu 未安装）")
                }
                if (!wineBinary.isFile) {
                    throw IllegalStateException("x86_64 wine 未安装：请先安装 wine")
                }
            }
            ContainerBackend.NATIVE -> {
                if (!wineBinary.isFile) {
                    throw IllegalStateException("x86_64 wine 未安装")
                }
            }
            ContainerBackend.AUTO -> {
                // 上面 auto 分支已处理，不会走到
            }
        }
        return backend
    }

    private fun autoBackend(arch: Arch): ContainerBackend? {
        return when (deviceArch) {
            "x86_64", "amd64" -> {
                if (File("$WINE_OPT/wine/bin/wine").isFile ||
                    File(WINE_OPT).isDirectory
                ) ContainerBackend.NATIVE else null
            }
            else -> {
                // aarch64 / arm64 设备
                val wineOk = File("$WINE_OPT/wine/bin/wine").isFile || File(WINE_OPT).isDirectory
                if (wineOk) {
                    when (arch) {
                        Arch.AARCH64 -> ContainerBackend.ARM64EC
                        Arch.X86_64 -> if (locateBox64() != null) ContainerBackend.BOX64 else null
                        Arch.UNKNOWN -> null
                    }
                } else if (HANGOVER_BIN.isFile) {
                    ContainerBackend.HANGOVER
                } else null
            }
        }
    }

    // ------------------------------------------------------------------
    // 命令构建（wine_run / run_wine 的 exec 目标矩阵，Kotlin 化）
    // ------------------------------------------------------------------

    /**
     * 按后端包装「宿主二进制」argv 前缀。
     *
     * - box64   → [box64, <binary>, ...]
     * - fexcore → [FEXInterpreter, <binary>, ...]
     * - native / arm64ec / hangover → [<binary>, ...]（hangover 用自己的 aarch64 路径）
     *
     * wineserver / wineboot 是 Unix 宿主程序，不走 wine 主程序包装，
     * 但 x86_64 形态仍需经 box64/FEX 执行 —— 与脚本 run_wine 的特判一致。
     */
    fun hostCommand(resolved: Resolved, binary: File): List<String> {
        return when (resolved.backend) {
            ContainerBackend.BOX64 -> {
                val box64 = locateBox64()
                    ?: throw IllegalStateException("box64 未安装")
                listOf(box64.absolutePath, binary.absolutePath)
            }
            ContainerBackend.FEXCORE -> listOf(FEX_BIN.absolutePath, binary.absolutePath)
            else -> listOf(binary.absolutePath)
        }
    }

    /** 主 wine 命令前缀（run_wine 的一般分支）。 */
    fun wineCommand(resolved: Resolved): List<String> = when (resolved.backend) {
        ContainerBackend.BOX64 -> {
            val box64 = locateBox64()
                ?: throw IllegalStateException("box64 未安装")
            listOf(box64.absolutePath, resolved.wineBinary.absolutePath)
        }
        ContainerBackend.FEXCORE -> listOf(FEX_BIN.absolutePath, resolved.wineBinary.absolutePath)
        ContainerBackend.HANGOVER -> listOf(HANGOVER_BIN.absolutePath)
        else -> listOf(resolved.wineBinary.absolutePath)
    }

    /** wineserver / wineboot 宿主二进制（依后端取对应目录）。 */
    fun hostToolBin(resolved: Resolved, tool: String): File {
        val dir = if (resolved.backend == ContainerBackend.HANGOVER) {
            HANGOVER_BIN.parentFile ?: File("$RFS_USR/opt/bin")
        } else {
            resolved.wineBinary.parentFile ?: File(resolved.wineDir, "bin")
        }
        return File(dir, tool)
    }
}
