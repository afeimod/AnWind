package com.anwind.apps.containers

import android.content.Context
import android.content.Intent
import android.os.Build
import com.anwind.apps.containers.launcher.NativeShellExecutor
import com.anwind.apps.containers.launcher.WineEnvironment
import com.anwind.apps.containers.launcher.WineRuntime
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * v3.2.18（wineguanli.py 完全重制）：wineguanli.py 的"高级设置"数据与
 * 操作层移植（Qt 桌面端是全局 /opt/conf 目录下的 .conf 文件，AnWind 原生链路
 * 对应物是容器级 container.conf + rootfs /opt 生态目录）。
 *
 * 移植对应关系（逐条对应 wineguanli.py 类/方法）：
 *  - DynarecEditor.presets        → [DY_PRESETS]（兼容/性能/平衡三预设，
 *    9 个 BOX64_DYNAREC_* 变量逐字照搬）+ 容器 env= 合并落地
 *  - load_driver_versions 等      → [OptEcosystem.scan]（/opt/tz、/opt/zink、
 *    /opt/dxvk1、/opt/vkord3d、/opt/box、/opt/box86 的 tar 扫描）+
 *    [OptEcosystem.extract]（tar 解压进 rootfs）
 *  - install_from_file / url      → [WineOps.installFromTar] /
 *    [WineOps.download] + installFromTar
 *  - delete_wine_version          → [WineOps.deleteWine]
 *  - refresh_box_info             → [OptEcosystem.boxVersion]
 *  - force_d3d9/11 + apply_cnc    → [AppDefaults.forceD3d] / [AppDefaults.cncDdraw]
 *    （wineguanli 复制 DLL 到 exe 目录；AnWind 用 wine 原生
 *    HKCU\AppDefaults\<exe>\DllOverrides 注册表机制实现同一效果，
 *    不需要在用户文件里铺文件）
 *  - MangohudConfigDialog         → [MangoHud.buildConfig]
 *  - ShortcutDialog               → [Shortcuts.pinRunContainer]
 */
object GuanliSupport {

    // ------------------------------------------------------------------
    // DY 配置（wineguanli.py DynarecEditor.presets 逐字移植）
    // ------------------------------------------------------------------

    /** 一个 DY 预设 = 一组 BOX64_* 环境变量（顺序保持 wineguanli 原序）。 */
    val DY_PRESETS: LinkedHashMap<String, LinkedHashMap<String, String>> = linkedMapOf(
        "🔒 兼容模式" to linkedMapOf(
            "BOX64_DYNAREC_SAFEFLAGS" to "2",
            "BOX64_DYNAREC_BIGBLOCK" to "0",
            "BOX64_DYNAREC_CALLRET" to "0",
            "BOX64_DYNAREC_WAIT" to "1",
            "BOX64_DYNAREC_FASTNAN" to "1",
            "BOX64_DYNAREC_X87DOUBLE" to "1",
            "BOX64_DYNAREC_WEAKBARRIER" to "0",
            "BOX64_AVX" to "0",
            "BOX64_DYNAREC_FORWARD" to "256"
        ),
        "🚀 性能模式" to linkedMapOf(
            "BOX64_DYNAREC_SAFEFLAGS" to "0",
            "BOX64_DYNAREC_BIGBLOCK" to "3",
            "BOX64_DYNAREC_CALLRET" to "1",
            "BOX64_DYNAREC_WAIT" to "1",
            "BOX64_DYNAREC_FASTNAN" to "1",
            "BOX64_DYNAREC_X87DOUBLE" to "0",
            "BOX64_DYNAREC_WEAKBARRIER" to "1",
            "BOX64_AVX" to "2",
            "BOX64_DYNAREC_FORWARD" to "1024"
        ),
        "⚖️ 平衡模式" to linkedMapOf(
            "BOX64_DYNAREC_SAFEFLAGS" to "1",
            "BOX64_DYNAREC_BIGBLOCK" to "1",
            "BOX64_DYNAREC_CALLRET" to "0",
            "BOX64_DYNAREC_WAIT" to "1",
            "BOX64_DYNAREC_FASTNAN" to "1",
            "BOX64_DYNAREC_X87DOUBLE" to "0",
            "BOX64_DYNAREC_WEAKBARRIER" to "1",
            "BOX64_AVX" to "0",
            "BOX64_DYNAREC_FORWARD" to "512"
        )
    )

    // ------------------------------------------------------------------
    // 容器 env= 的 K=V 列表化读写（DY 配置 / 环境变量两视图共用同一存储）
    // ------------------------------------------------------------------

    /**
     * env=（空格分隔 K=V）→ 有序列表。
     * 值带空格时用引号包裹（与 anwind-wine 的解析规则一致：K="V V"）。
     */
    fun parseEnv(env: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val re = Regex("([A-Za-z_][A-Za-z0-9_]*)=(\"[^\"]*\"|\\S+)")
        re.findAll(env).forEach { m ->
            val v = m.groupValues[2].removeSurrounding("\"")
            out.add(m.groupValues[1] to v)
        }
        return out
    }

    /** 列表 → env= 串（值含空白时加引号）。 */
    fun serializeEnv(pairs: List<Pair<String, String>>): String =
        pairs.joinToString(" ") { (k, v) ->
            if (v.contains(' ')) "$k=\"$v\"" else "$k=$v"
        }

    /**
     * 把一组变量合并进容器 env=（同名替换、其余保留、顺序稳定）。
     * wineguanli 的 DY/环境变量配置写 /opt/conf/env.conf（全局）；
     * AnWind 的等价物是容器级 env=（applyExtraEnv 在启动链最后叠加，
     * 可覆盖一切预设）。
     */
    fun mergeEnv(current: String, vars: Map<String, String>): String {
        val list = parseEnv(current).toMutableList()
        vars.forEach { (k, v) ->
            val idx = list.indexOfFirst { it.first == k }
            if (idx >= 0) list[idx] = k to v else list.add(k to v)
        }
        return serializeEnv(list)
    }

    /** 从容器 env= 读单个变量（无则 null）。 */
    fun envValue(env: String, key: String): String? =
        parseEnv(env).firstOrNull { it.first == key }?.second

    // ------------------------------------------------------------------
    // /opt 生态（wineguanli.py load_driver_versions / load_box_versions）
    // ------------------------------------------------------------------

    /** rootfs 内 /opt（wineguanli 的 /opt 在 rootfs 里）。 */
    val OPT_ROOT = "${ContainerManager.ROOTFS_ROOT}/opt"

    /** 驱动/box tar 包条目（display = 去掉 tar 后缀的文件名）。 */
    data class OptTar(val display: String, val fileName: String, val path: String)

    /**
     * 扫描 /opt/<sub> 下的 .tar.gz / .tar.xz / .tar.bz2（wineguanli
     * load_driver_versions 同规则：显示名去后缀，按名排序）。
     * 目录不存在返回空表（UI 显示"默认/不使用"两项 + 提示）。
     */
    fun scanOpt(sub: String): List<OptTar> {
        val dir = File(OPT_ROOT, sub)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f ->
            f.isFile && (f.name.endsWith(".tar.gz") || f.name.endsWith(".tar.xz") || f.name.endsWith(".tar.bz2"))
        }.orEmpty()
            .map { f ->
                val disp = f.name.replace(Regex("\\.tar\\.(gz|xz|bz2)$"), "")
                OptTar(disp, f.name, f.absolutePath)
            }
            .sortedBy { it.display }
    }

    /**
     * 解压 /opt 内 tar 包进 rootfs（wineguanli extract_package /
     * extract_box_file 同一动作：tar -xf <包> -C <目标> --overwrite）。
     * 驱动类（turnip/zink/dxvk/wined3d）→ rootfs /usr/local/lib 落点由
     * 包内布局决定；box 类 → /usr/local/bin。统一解到 /usr/local（与
     * wineguanli 的 destDir 一致）。
     * @return null 成功；否则错误信息。
     */
    fun extractIntoRootfsLocal(tarPath: String, log: (String) -> Unit = {}): String? {
        val f = File(tarPath)
        if (!f.isFile) return "包不存在：$tarPath"
        val tarBin = listOf(
            File("${ContainerManager.ROOTFS_ROOT}/usr/bin/tar"),
            File("${ContainerManager.APP_PREFIX}/bin/tar")
        ).firstOrNull { it.canExecute() } ?: return "rootfs 内未找到 tar（rootfs 未导入？）"
        val dest = File("${ContainerManager.ROOTFS_ROOT}/usr/local")
        return try {
            val p = ProcessBuilder(tarBin.absolutePath, "-xf", f.absolutePath, "-C", dest.absolutePath, "--overwrite")
                .redirectErrorStream(true)
                .start()
            val ok = p.waitFor(120, TimeUnit.SECONDS)
            val out = runCatching { p.inputStream.readBytes().decodeToString() }.getOrDefault("")
            if (!ok) { p.destroyForcibly(); return "解压超时（120s）" }
            if (p.exitValue() != 0) "解压失败：${out.take(300)}"
            else { log("已解压 ${f.name} → rootfs/usr/local"); null }
        } catch (e: Exception) {
            "解压异常：${e.message}"
        }
    }

    /** box64/box86 版本探活（wineguanli refresh_box_info：box64 -v 首行）。 */
    fun boxVersion(exeName: String): String {
        val bin = listOf(
            File("${ContainerManager.ROOTFS_ROOT}/usr/local/bin/$exeName"),
            File("${ContainerManager.ROOTFS_ROOT}/usr/bin/$exeName")
        ).firstOrNull { it.isFile && it.canExecute() }
            ?: return "$exeName: 未检测到"
        return try {
            val p = ProcessBuilder(bin.absolutePath, "-v").redirectErrorStream(true).start()
            val ok = p.waitFor(5, TimeUnit.SECONDS)
            val out = runCatching { p.inputStream.readBytes().decodeToString() }.getOrDefault("")
            runCatching { p.destroyForcibly() }
            if (ok) "$exeName: ${out.lineSequence().firstOrNull { it.isNotBlank() } ?: "（无输出）"}"
            else "$exeName: 获取版本超时"
        } catch (e: Exception) {
            "$exeName: 检测时出错（${e.message}）"
        }
    }

    // ------------------------------------------------------------------
    // Wine 安装/删除（wineguanli.py install_from_file / install_from_url /
    // delete_wine_version；AnWind 落点 /usr/opt/wine-<名> 槽位）
    // ------------------------------------------------------------------

    object WineOps {

        /** 从本地 tar 包安装：解压到 /usr/opt/<槽位名> + 写 .anwind-wine-info。 */
        fun installFromTar(tarPath: String, slotName: String, arch: String): String? {
            val f = File(tarPath)
            if (!f.isFile) return "文件不存在：$tarPath"
            val name = slotName.trim().ifEmpty {
                f.nameWithoutExtension.let { n ->
                    n.removeSuffix(".tar").removePrefix("wine-").ifEmpty { "wine" }.let { "wine-$it" }
                }
            }.replace(Regex("[^A-Za-z0-9._-]"), "-")
            val tarBin = listOf(
                File("${ContainerManager.ROOTFS_ROOT}/usr/bin/tar"),
                File("${ContainerManager.APP_PREFIX}/bin/tar")
            ).firstOrNull { it.canExecute() } ?: return "rootfs 内未找到 tar（rootfs 未导入？）"
            val dest = File(ContainerManager.WINE_OPT_DIR, name)
            if (dest.exists()) return "槽位已存在：$name（先删除旧版本）"
            return try {
                dest.mkdirs()
                val p = ProcessBuilder(tarBin.absolutePath, "-xf", f.absolutePath, "-C", dest.absolutePath, "--strip-components=1")
                    .redirectErrorStream(true)
                    .start()
                val ok = p.waitFor(300, TimeUnit.SECONDS)
                val out = runCatching { p.inputStream.readBytes().decodeToString() }.getOrDefault("")
                if (!ok) {
                    p.destroyForcibly(); com.anwind.util.SafeFileOps.deleteTree(dest)
                    return "解压超时（300s），已回滚"
                }
                if (p.exitValue() != 0) {
                    com.anwind.util.SafeFileOps.deleteTree(dest)
                    "安装失败（tar 退出码 ${p.exitValue()}：${out.take(200)}）—— 请确认包是 wine 目录树（bin/wine 在顶层）"
                } else if (!File(dest, "bin/wine").isFile && !File(dest, "bin/wine64").isFile) {
                    com.anwind.util.SafeFileOps.deleteTree(dest)
                    "包布局不对：解压后未见 bin/wine（应选 wine 目录树的 tar）"
                } else {
                    File(dest, ".anwind-wine-info").writeText(
                        buildString {
                            appendLine("name=$name")
                            appendLine("arch=$arch")
                            appendLine("installed=${System.currentTimeMillis()}")
                        }
                    )
                    null
                }
            } catch (e: Exception) {
                com.anwind.util.SafeFileOps.deleteTree(dest)
                "安装异常：${e.message}"
            }
        }

        /** 下载 URL 到缓存文件（wineguanli install_from_url）。@return 文件或错误。 */
        fun download(url: String, log: (String) -> Unit = {}): File {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            try {
                if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                val dir = File("/data/data/com.anwind/files/anwind-download").apply { mkdirs() }
                val file = File(
                    dir,
                    "wine_${System.currentTimeMillis()}_${url.substringAfterLast('/').take(60).ifEmpty { "download.tar.gz" }}"
                )
                file.outputStream().use { out ->
                    conn.inputStream.use { ins ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            if (total % (1024 * 1024) < buf.size) log("已下载 ${total / 1024 / 1024} MB…")
                        }
                    }
                }
                return file
            } finally {
                conn.disconnect()
            }
        }

        /** 删除 wine 槽位。@return null 成功。 */
        fun deleteWine(slotName: String): String? {
            val dir = File(ContainerManager.WINE_OPT_DIR, slotName)
            if (!dir.isDirectory) return "槽位不存在：$slotName"
            if (dir.parentFile?.canonicalPath != File(ContainerManager.WINE_OPT_DIR).canonicalPath)
                return "拒绝删除：路径越界"
            return try {
                if (!com.anwind.util.SafeFileOps.deleteTree(dir))
                    return "删除未完成（部分文件被占用，可重试）"
                null
            } catch (e: Exception) {
                "删除失败：${e.message}"
            }
        }

        /** 设置全局默认 wine 槽位（default_wine=）。@return null 成功。 */
        fun setDefaultWine(slotName: String): String? {
            val f = File(ContainerManager.GLOBAL_CONF)
            return try {
                f.parentFile?.mkdirs()
                val others = if (f.isFile) f.readLines().filter {
                    val t = it.trim()
                    t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("default_wine=")
                } else emptyList()
                f.writeText(
                    buildString {
                        appendLine("# AnWind Wine 容器全局配置（anwind-container / App 端共用）")
                        (others + "default_wine=$slotName").forEach { appendLine(it) }
                    }
                )
                null
            } catch (e: Exception) {
                "写入失败：${e.message}"
            }
        }
    }

    // ------------------------------------------------------------------
    // Direct3D / CNC-DDraw（wineguanli.py force_d3d9/11、apply_cnc_ddraw）
    // ------------------------------------------------------------------

    object AppDefaults {

        /**
         * 对指定 exe 写 AppDefaults DllOverrides（wine 原生的"按程序覆盖
         * DLL 加载序"机制 —— wineguanli 用复制 DLL 实现，AnWind 用注册表
         * 实现同一效果，不动用户文件）。
         * @param dlls 例如 {"d3d9" to "native,builtin"}；空表 = 清除该 exe 的全部覆盖
         * @return null 成功。
         */
        fun applyDllOverrides(container: ContainerData, exeName: String, dlls: Map<String, String>): String? {
            val prefix = File(container.prefixPath)
            if (!File(prefix, "system.reg").isFile) return "容器前缀尚未构建（先运行 wineboot 或启动一次容器）"
            if (exeName.isBlank() || !exeName.contains('.')) return "请填写完整的 exe 文件名（如 game.exe）"
            val key = "AppDefaults\\\\${exeName.replace("\\", "\\\\")}\\\\DllOverrides"
            val reg = File(prefix, "drive_c/anwind").apply { mkdirs() }.let { File(it, "appdefaults.reg") }
            reg.writeText(
                buildString {
                    appendLine("REGEDIT4")
                    appendLine()
                    appendLine("[HKEY_CURRENT_USER\\Software\\Wine\\$key]")
                    if (dlls.isEmpty()) {
                        appendLine("\"d3d9\"=\"-\"")
                        appendLine("\"d3d11\"=\"-\"")
                        appendLine("\"dxgi\"=\"-\"")
                        appendLine("\"ddraw\"=\"-\"")
                    } else {
                        dlls.forEach { (dll, order) -> appendLine("\"$dll\"=\"$order\"") }
                    }
                }
            )
            val resolved = runCatching { WineRuntime.resolve(container) }.getOrNull()
                ?: return "wine 运行时解析失败（wine 未安装？）"
            val env = WineEnvironment.buildBaseEnv(container, resolved.wineDir).toMutableMap()
            env["WINEPREFIX"] = container.prefixPath
            return try {
                val cmd = NativeShellExecutor.create()
                    .setCommand(*WineRuntime.wineCommand(resolved).toTypedArray(), "regedit", "/s", "C:\\anwind\\appdefaults.reg")
                    .putEnv(env)
                    .setWorkingDir(WineEnvironment.HOME_DIR)
                    .exec()
                val ok = cmd.waitFor(60, TimeUnit.SECONDS)
                if (ok && cmd.waitFor() == 0) null else "注册表导入失败（详见会话日志）"
            } catch (e: Exception) {
                "注册表导入异常：${e.message}"
            }
        }

        /** 强制 D3D9（wineguanli force_d3d9）。 */
        fun forceD3d(container: ContainerData, exeName: String) =
            applyDllOverrides(container, exeName, mapOf("d3d9" to "native,builtin"))

        /** 强制 D3D11（wineguanli force_d3d11）。 */
        fun forceD3d11(container: ContainerData, exeName: String) =
            applyDllOverrides(container, exeName, mapOf("d3d11" to "native,builtin"))

        /** CNC DDraw（wineguanli apply_cnc_ddraw：ddraw 原生优先 + 关联项）。 */
        fun cncDdraw(container: ContainerData, exeName: String) =
            applyDllOverrides(
                container, exeName, mapOf(
                    "ddraw" to "native,builtin",
                    "d3d9" to "native,builtin",
                    "dxgi" to "native,builtin"
                )
            )

        /** 清除某 exe 的全部覆盖（wineguanli clear_d3d_config / clear_cnc_config）。 */
        fun clear(container: ContainerData, exeName: String) =
            applyDllOverrides(container, exeName, emptyMap())
    }

    // ------------------------------------------------------------------
    // MangoHud 配置串（wineguanli.py MangohudConfigDialog.get_config_string）
    // ------------------------------------------------------------------

    object MangoHud {

        /** wineguanli 的全部可勾选项（key → 中文标签）。 */
        val TOGGLES: List<Pair<String, String>> = listOf(
            "fps" to "FPS", "frametime" to "帧时间",
            "cpu_stats" to "CPU使用率", "gpu_stats" to "GPU使用率",
            "ram" to "内存使用", "vram" to "显存使用",
            "cpu_temp" to "CPU温度", "gpu_temp" to "GPU温度",
            "horizontal" to "横屏显示", "no_display" to "无显示模式"
        )

        val POSITIONS = listOf("top-left", "top-right", "bottom-left", "bottom-right")

        /**
         * 组配置串（wineguanli get_config_string 逐字段等价）：
         * 勾选项 + horizontal + font_size=<n> + position=<p> +
         * background_alpha=<0.xx> + no_display。
         */
        fun buildConfig(
            enabled: Set<String>,
            fontSize: Int,
            position: String,
            backgroundAlpha: Int,
            noDisplay: Boolean
        ): String = buildList {
            TOGGLES.forEach { (k, _) -> if (k in enabled && k != "horizontal" && k != "no_display") add(k) }
            if ("horizontal" in enabled) add("horizontal")
            add("font_size=$fontSize")
            add("position=$position")
            add("background_alpha=%.2f".format(backgroundAlpha / 100.0))
            if (noDisplay || "no_display" in enabled) add("no_display")
        }.joinToString(",")

        /** 从配置串恢复勾选态（MangoHudConfigDialog.load_current_config 等价）。 */
        fun parseConfig(config: String): Triple<Set<String>, Boolean, Boolean> {
            val set = mutableSetOf<String>()
            config.split(',').map { it.trim() }.forEach {
                if (it.isNotEmpty() && !it.contains('=')) set.add(it)
            }
            val noDisplay = "no_display" in set
            if (noDisplay) set.remove("no_display")
            return Triple(set, noDisplay, set.isNotEmpty() || config.isNotBlank())
        }
    }

    // ------------------------------------------------------------------
    // 桌面快捷方式（wineguanli.py ShortcutDialog 的 Android 等价物）
    // ------------------------------------------------------------------

    object Shortcuts {

        /** Android 主界面启动器固定的 extra：打开容器窗口并运行指定容器。 */
        const val EXTRA_RUN_CONTAINER = "anwind_run_container"

        /**
         * 请求把"运行容器"快捷方式固定到 Android 启动器
         * （ShortcutManager.requestPinShortcut，系统会弹确认框；
         * 部分启动器不支持固定，返回 false）。
         */
        fun pinRunContainer(context: Context, container: ContainerData, exePath: String): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            return try {
                val mgr = context.getSystemService(android.content.pm.ShortcutManager::class.java) ?: return false
                if (!mgr.isRequestPinShortcutSupported) return false
                val intent = Intent(context, Class.forName("com.anwind.MainActivity")).apply {
                    action = Intent.ACTION_MAIN
                    putExtra(EXTRA_RUN_CONTAINER, container.name)
                    if (exePath.isNotBlank()) putExtra("anwind_run_exe", exePath)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val info = android.content.pm.ShortcutInfo.Builder(context, "anwind-container-${container.name}")
                    .setShortLabel(container.name.take(10))
                    .setLongLabel("运行 ${container.name}")
                    .setIcon(
                        android.graphics.drawable.Icon.createWithResource(context, context.applicationInfo.icon)
                    )
                    .setIntent(intent)
                    .build()
                mgr.requestPinShortcut(info, null)
            } catch (e: Exception) {
                false
            }
        }
    }
}
