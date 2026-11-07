package com.anwind.apps.containers.launcher

import com.anwind.apps.containers.ContainerManager
import com.anwind.apps.containers.GuanliSupport
import java.io.File

/**
 * v3.3.10（用户问题 4）：Wrapper 驱动支持 —— WinNative / yuzu 同型的
 * 预编译 Turnip（Adreno）Vulkan 驱动接入 bionic rootfs。
 *
 * ## 为什么需要（与 rootfs 自带 Mesa Turnip 的关系）
 *
 * rootfs 内的 Mesa 自建 Turnip（usr/lib/libvulkan_freedreno.so +
 * share/vulkan/icd.d/freedreno_icd.aarch64.json）是社区开源实现；
 * yuzu / WinNative 社区分发的 Wrapper 驱动是从高通专有驱动转出的
 * 用户态 .so（常见名 libvulkan_adreno.so / libvulkan_freedreno.so），
 * 在对应 GPU（A6xx/A7xx）上性能显著更高 —— 用户诉求"两种都要"。
 *
 * ## 机制（WinNative DriverPackages 同款 —— ICD 清单切换）
 *
 *  * Wrapper tar 解压到 rootfs 独立目录 usr/lib/anwind-wrapper/<name>/
 *    （不落 usr/lib —— 避免同名 .so 直接覆盖 Mesa 版，两种驱动共存）；
 *  * 生成专属 Vulkan ICD 清单：usr/share/vulkan/icd.d/anwind-wrapper-<name>.json
 *    { "file_format_version": "1.0.0",
 *      "ICD": { "api_version": "1.4.0",
 *               "library_path": "<rootfs>/usr/lib/anwind-wrapper/<name>/libvulkan_*.so" } }
 *  * 启动环境注入 VK_ICD_FILENAMES 指向该 json —— 标准 Vulkan loader
 *    只加载清单指向的驱动，切回 Mesa 只需清掉该变量（loader 默认扫
 *    icd.d 里 Mesa 的 freedreno_icd.aarch64.json）。
 *
 * ## tar 包布局兼容（用户手上的包各式各样，全部归一化）
 *
 *  - 根级 libvulkan_*.so（yuzu zip 重打包的最简形态）；
 *  - 单顶层目录 <dir>/libvulkan_*.so（tar 带壳）→ 剥壳；
 *  - WinNative 布局：meta.json 的 libraryName 指明驱动 .so；
 *  - 自带 ICD json（含 "ICD" 键）→ 复用其 library_path（相对路径按
 *    解压目录重定位）；
 *  - 同目录附属 .so（libc++_shared.so 等）一并入目录。
 *
 * ## 依赖补全（"补全依赖"诉求）
 *
 *  * libc++_shared.so：NDK bionic 链接的 Wrapper 驱动普遍 DT_NEEDED 它；
 *    tar 内没带时从 rootfs/usr/lib（libc++ 配方安装位）复制一份进驱动目录，
 *    dlopen 时驱动目录在 LD_LIBRARY_PATH 前列，稳定解析；
 *  * libc/libm/libdl/liblog/libandroid/libz：bionic 系统库，设备
 *    /system/lib64 与 rootfs 均可解析，无需处理；
 *  * 安装后探测 .so 的 ELF 依赖（readelf 不可用则跳过），缺库时日志
 *    显式提示（不阻断启动 —— 驱动加载失败 wine 会回退 Mesa GL 路径）。
 */
object WrapperDriverSupport {

    /** rootfs 内 wrapper 驱动安装根（与 Mesa 的 usr/lib 隔离，共存可切）。 */
    const val WRAPPER_LIB_DIR = "usr/lib/anwind-wrapper"

    /** rootfs 内 Vulkan ICD 清单目录（vulkan-icd-loader 标准搜索位）。 */
    const val ICD_DIR = "usr/share/vulkan/icd.d"

    private val RFS_ROOT get() = ContainerManager.ROOTFS_ROOT

    /** 安装根绝对路径。 */
    val wrapperRoot: File get() = File(RFS_ROOT, WRAPPER_LIB_DIR)

    /** ICD 清单目录绝对路径。 */
    val icdDir: File get() = File(RFS_ROOT, ICD_DIR)

    /** display 名 → 安全目录名（去空白与路径分隔符）。 */
    fun safeDirName(display: String): String =
        display.trim().replace(Regex("[^A-Za-z0-9._+-]"), "-").ifEmpty { "driver" }

    /**
     * 已安装 wrapper 的 ICD 清单（存在且 library_path 指向真实 .so 才算数）。
     * WineEnvironment 注入 VK_ICD_FILENAMES 前的探测入口。
     */
    fun icdFor(display: String): File? {
        val json = File(icdDir, "anwind-wrapper-${safeDirName(display)}.json")
        if (!json.isFile) return null
        val lib = parseIcdLibraryPath(json) ?: return null
        val libFile = if (lib.startsWith("/")) File(lib) else File(wrapperRoot, lib)
        return if (libFile.isFile) json else null
    }

    /**
     * 安装一个 wrapper 驱动 tar（幂等：重复安装先清旧目录再落新）。
     *
     * @param display 槽位显示名（tar 文件名去后缀，来自 /opt/wrapper 扫描）
     * @param tarPath tar 绝对路径
     * @return 成功返回 ICD json；失败返回 null（log 有细节，不阻断启动）
     */
    fun install(display: String, tarPath: String, log: (String) -> Unit = {}): File? {
        val dirName = safeDirName(display)
        val staging = File(File(RFS_ROOT, "usr/tmp"), "anwind-wrapper-$dirName")
        val dest = File(wrapperRoot, dirName)
        // 干净起步：staging 必清；dest 也清（同版本重装 = 升级覆盖）
        runCatching {
            if (staging.exists()) staging.deleteRecursively()
            if (dest.exists()) dest.deleteRecursively()
            staging.mkdirs()
        }.onFailure {
            log("⚠ Wrapper 驱动目录准备失败：${it.message}")
            return null
        }

        // ① 解压到 staging
        val err = GuanliSupport.extractTarTo(tarPath, staging) { log(it) }
        if (err != null) {
            log("⚠ Wrapper 驱动 ${display} 解压失败：$err（继续启动，Vulkan 走 rootfs 自带 Mesa）")
            runCatching { staging.deleteRecursively() }
            return null
        }

        // ② 归一化：定位驱动 .so（根级 / 单顶层目录 / meta.json 指定 / ICD json 指定）
        val driverSo = locateDriverSo(staging) ?: run {
            log("⚠ Wrapper 驱动 ${display} 内未找到 libvulkan*.so（yuzu/WinNative 型驱动 tar 应含该文件）—— 已跳过")
            runCatching { staging.deleteRecursively() }
            return null
        }
        val sourceDir = driverSo.parentFile ?: staging

        // ③ 落位：驱动 .so + 同目录附属 .so → usr/lib/anwind-wrapper/<name>/
        runCatching {
            dest.mkdirs()
            sourceDir.listFiles { f -> f.isFile && f.name.endsWith(".so") }
                .orEmpty().forEach { f -> f.copyTo(File(dest, f.name), overwrite = true) }
            // meta.json 一并保留（WinNative 型包的元数据，便于人工核查）
            File(sourceDir, "meta.json").takeIf { it.isFile }
                ?.copyTo(File(dest, "meta.json"), overwrite = true)
        }.onFailure {
            log("⚠ Wrapper 驱动 ${display} 落位失败：${it.message}")
            runCatching { staging.deleteRecursively() }
            return null
        }
        runCatching { staging.deleteRecursively() }

        // ④ 依赖补全：libc++_shared.so（NDK bionic 驱动普遍需要）
        completeDeps(dest, log)

        // ⑤ 生成专属 ICD 清单（WinNative DriverPackages 同款结构）
        val libAbs = File(dest, driverSo.name).absolutePath
        val icdJson = File(icdDir, "anwind-wrapper-$dirName.json")
        val json = buildString {
            appendLine("{")
            appendLine("    \"file_format_version\": \"1.0.0\",")
            appendLine("    \"ICD\": {")
            appendLine("        \"api_version\": \"1.4.0\",")
            appendLine("        \"library_path\": \"$libAbs\"")
            appendLine("    }")
            appendLine("}")
        }
        return runCatching {
            icdDir.mkdirs()
            icdJson.writeText(json)
            log("Wrapper 驱动 ${display} 已就位 → ${dest.absolutePath}")
            log("Vulkan ICD 清单 → ${icdJson.absolutePath}（VK_ICD_FILENAMES 注入后生效）")
            icdJson
        }.getOrElse {
            log("⚠ Wrapper 驱动 ICD 清单写入失败：${it.message}")
            null
        }
    }

    /**
     * 定位驱动 .so：
     *  1) meta.json 的 libraryName（WinNative 布局）；
     *  2) 根级 libvulkan*.so；
     *  3) 单顶层目录下的 libvulkan*.so（剥壳）；
     *  4) 自带 ICD json（含 "ICD" 键）的 library_path。
     */
    private fun locateDriverSo(root: File): File? {
        // WinNative meta.json：{ "name": ..., "libraryName": "libvulkan_xxx.so" }
        runCatching {
            val meta = File(root, "meta.json")
            if (meta.isFile) {
                val m = Regex("\"libraryName\"\\s*:\\s*\"([^\"]+)\"").find(meta.readText())?.groupValues?.get(1)
                if (!m.isNullOrBlank()) {
                    val f = File(root, m)
                    if (f.isFile) return f
                }
            }
        }
        // 根级
        root.listFiles { f -> f.isFile && f.name.startsWith("libvulkan") && f.name.endsWith(".so") }
            ?.firstOrNull()?.let { return it }
        // 自带 ICD json 的 library_path
        root.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().forEach { j ->
            parseIcdLibraryPath(j)?.let { p ->
                val f = if (p.startsWith("/")) File(p) else File(root, p)
                if (f.isFile) return f
            }
        }
        // 单顶层目录剥壳
        val dirs = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty()
        if (dirs.size == 1) {
            dirs[0].listFiles { f -> f.isFile && f.name.startsWith("libvulkan") && f.name.endsWith(".so") }
                ?.firstOrNull()?.let { return it }
            // 剥壳目录内的 meta.json / ICD json 再探一层
            runCatching {
                val meta = File(dirs[0], "meta.json")
                if (meta.isFile) {
                    val m = Regex("\"libraryName\"\\s*:\\s*\"([^\"]+)\"").find(meta.readText())?.groupValues?.get(1)
                    if (!m.isNullOrBlank() && File(dirs[0], m).isFile) return File(dirs[0], m)
                }
            }
        }
        return null
    }

    /** 解析 ICD json 的 ICD.library_path（宽容解析，任何失败返回 null）。 */
    private fun parseIcdLibraryPath(json: File): String? = runCatching {
        val text = json.readText()
        if (!text.contains("\"ICD\"")) return@runCatching null
        Regex("\"library_path\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
    }.getOrNull()

    /**
     * 依赖补全：驱动目录缺 libc++_shared.so 时从 rootfs/usr/lib 复制
     * （libc++ 配方安装位）；rootfs 也没有则提示（NDK bionic 驱动的
     * 另一常见来源是 APK 侧，但 rootfs 内 libc++ 配方 v3.3 起必有）。
     */
    private fun completeDeps(dest: File, log: (String) -> Unit) {
        runCatching {
            if (File(dest, "libc++_shared.so").isFile) return
            val src = File("${RFS_ROOT}/usr/lib/libc++_shared.so")
            if (src.isFile) {
                src.copyTo(File(dest, "libc++_shared.so"), overwrite = true)
                log("Wrapper 驱动依赖补全：libc++_shared.so → 驱动目录")
            } else {
                log("⚠ 驱动目录与 rootfs 均无 libc++_shared.so —— 若驱动加载报缺库，请把该文件放入 tar 包")
            }
        }
    }

    /** 诊断输出（容器体检 / 日志用）：已安装的 wrapper 驱动列表。 */
    fun installedDrivers(): List<String> =
        wrapperRoot.takeIf { it.isDirectory }
            ?.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            .orEmpty()
            .map { it.name }
            .sorted()
}
