package com.anwind.apps.containers.launcher

import com.anwind.apps.containers.ContainerManager
import com.anwind.apps.containers.GuanliSupport
import java.io.File

/**
 * v3.3.15（重写，对齐用户驱动包结构 / WinNative DriverPackages）：
 * Wrapper（预编译 Turnip/Adreno）Vulkan 驱动接入 bionic rootfs。
 *
 * ## v3.3.10 旧实现的问题（用户实测反馈）
 *
 * 旧版把包内容重新组装到自造目录 usr/lib/anwind-wrapper/<name>/ ——
 * 用户手上的 wrapper 驱动压缩包本身就是【rootfs 布局】（usr/lib 下的
 * .so + usr/share/vulkan/icd.d 下的 ICD json），拆散挪到私有目录后：
 *  - 包内按 usr/lib 语义打包的附属库（libc++_shared.so 等）全部脱离
 *    标准搜索路径；
 *  - 自造目录不在任何 loader 缺省搜索序列里，离开 VK_ICD_FILENAMES
 *    完全不可见；
 *  - 与 WinNative 的包结构语义不一致（WinNative 装 rootfs 布局包时
 *    同样按包内路径落位）。
 *
 * ## 新机制（WinNative DriverPackages.java 同款语义）
 *
 *  * 驱动包内容按【rootfs 布局直接落位】：
 *      usr/lib/libvulkan_*.so            → rootfs/usr/lib/（loader 标准
 *                                          搜索位，wine 的 LD_LIBRARY_PATH
 *                                          第一段就是它）
 *      usr/share/vulkan/icd.d/<json>     → rootfs/usr/share/vulkan/icd.d/
 *    裸包（根级 libvulkan*.so / WinNative meta.json 型）的 .so 同样
 *    落位 rootfs/usr/lib/ —— 归一为统一布局。
 *  * 落位前对被覆盖文件做【备份】（rootfs 自带 Mesa Turnip 的
 *    libvulkan_freedreno.so 与 wrapper 驱动常重名）：原文件存
 *    usr/share/anwind-wrapper/backup/<slot>/，清单记录在
 *    usr/share/anwind-wrapper/<slot>.manifest；卸载按清单恢复 ——
 *    wrapper 与 Mesa 真正共存、随容器切换。
 *  * ICD 清单一律【自生成】（WinNative 同款，不信任包内 json 的
 *    library_path —— 它可能是容器内路径 /usr/lib/... 或相对路径，
 *    而本 rootfs 的 wine 在宿主命名空间运行，loader 需要【宿主绝对
 *    路径】/data/data/com.anwind/files/rootfs/usr/lib/xxx.so）：
 *      usr/share/vulkan/icd.d/anwind-wrapper-<slot>.json
 *      { "file_format_version": "1.0.0",
 *        "ICD": { "api_version": "1.4.0",
 *                 "library_path": "<宿主绝对路径>" } }
 *  * 激活（RootfsLauncher 6a / anwind-wine apply_wrapper_driver）：
 *      VK_ICD_FILENAMES=<宿主 icd json 路径>   ← Vulkan 侧
 *      GALLIUM_DRIVER=zink（auto 档联动）        ← GL 侧走 zink over
 *    Vulkan，吃到 wrapper 驱动加速（WinNative XServerDisplayActivity
 *    同款：MESA_LOADER_DRIVER_OVERRIDE=zink + GALLIUM_DRIVER=zink）。
 *    用户显式选了 freedreno/softpipe 时尊重选择，只换 Vulkan 侧。
 *  * 依赖补全：驱动 .so 与 libc++_shared.so 同在 rootfs/usr/lib
 *    （libc++ 配方 v3.3 起必装），NDK bionic 驱动的 DT_NEEDED 在
 *    wine 的 LD_LIBRARY_PATH 下自然解析，无需复制。
 *
 * ## 支持的包形态（全部归一化为 rootfs 布局落位）
 *
 *  1. rootfs 布局包（用户主要形态）：包内含 usr/lib/libvulkan*.so，
 *     可带 usr/share/vulkan/icd.d/<json> 与附属 .so —— 整个 usr/ 树
 *     按相对路径落位（ICD json 落位仅作参考，激活用的清单自生成）；
 *  2. WinNative meta.json 包：根级 meta.json 的 libraryName 指明驱动
 *     .so（同目录附属 .so 一并落位 usr/lib/）；
 *  3. yuzu 裸包：根级 libvulkan*.so（+ 同目录附属 .so）落位 usr/lib/。
 *
 * ## 状态与切换
 *
 *  usr/share/anwind-wrapper/state 记录当前已安装 slot；容器 wrapper
 *  键变化时先卸载旧 slot（按 manifest 删文件/恢复备份）再装新的，
 *  off 即完全恢复 Mesa。
 */
object WrapperDriverSupport {

    /** 驱动 .so 落位目录（rootfs 内，与 Mesa 共存、同名互备）。 */
    const val DRIVER_LIB_DIR = "usr/lib"

    /** rootfs 内 Vulkan ICD 清单目录（vulkan-icd-loader 标准搜索位）。 */
    const val ICD_DIR = "usr/share/vulkan/icd.d"

    /** wrapper 安装元数据根（manifest / state / backup）。 */
    const val META_DIR = "usr/share/anwind-wrapper"

    private val RFS_ROOT get() = ContainerManager.ROOTFS_ROOT

    private fun rfs(rel: String) = File(RFS_ROOT, rel)

    /** 安装元数据目录绝对路径。 */
    val metaDir: File get() = rfs(META_DIR)

    /** ICD 清单目录绝对路径。 */
    val icdDir: File get() = rfs(ICD_DIR)

    /** slot（tar 显示名）→ 安全目录/文件名片段。 */
    fun safeSlotName(display: String): String =
        display.trim().replace(Regex("[^A-Za-z0-9._+-]"), "-").ifEmpty { "driver" }

    private fun manifestFile(slot: String) =
        File(metaDir, "${safeSlotName(slot)}.manifest")

    private fun icdFile(slot: String) =
        File(icdDir, "anwind-wrapper-${safeSlotName(slot)}.json")

    private fun backupDir(slot: String) =
        File(File(metaDir, "backup"), safeSlotName(slot))

    private fun stateFile() = File(metaDir, "state")

    /** 当前已安装的 slot（state 文件内容；未安装返回 null）。 */
    fun installedSlot(): String? =
        stateFile().takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

    // ------------------------------------------------------------------
    // 查询（激活入口）
    // ------------------------------------------------------------------

    /**
     * slot 的 ICD 清单（存在且 library_path 指向真实 .so 才算数）。
     * RootfsLauncher / anwind-wine 注入 VK_ICD_FILENAMES 前的探测入口。
     */
    fun icdFor(display: String): File? {
        val json = icdFile(display)
        if (!json.isFile) return null
        val lib = parseIcdLibraryPath(json) ?: return null
        val libFile = File(lib)   // library_path 为宿主绝对路径（自生成保证）
        return if (libFile.isFile) json else null
    }

    /**
     * wrapper 激活时 GL 侧是否应联动 zink：
     * 容器 gallium 为 auto/空/zink 时 true（GL over Vulkan 吃 wrapper 加速），
     * 用户显式选 freedreno/softpipe 等 GL 驱动时 false（尊重选择）。
     */
    fun shouldForceZink(gallium: String): Boolean {
        val g = gallium.trim().lowercase()
        return g.isBlank() || g == "auto" || g == "zink"
    }

    // ------------------------------------------------------------------
    // 安装（rootfs 布局落位 + 备份 + 自生成 ICD）
    // ------------------------------------------------------------------

    /**
     * 安装一个 wrapper 驱动包（幂等：重复安装先卸载旧 slot 再落新）。
     *
     * @param display 槽位显示名（tar 文件名去后缀，来自 /opt/wrapper 扫描）
     * @param tarPath tar 绝对路径
     * @return 成功返回 ICD json；失败返回 null（log 有细节，不阻断启动）
     */
    fun install(display: String, tarPath: String, log: (String) -> Unit = {}): File? {
        val slot = safeSlotName(display)

        // 同 slot 重装 = 卸载旧文件（含恢复被覆盖的 Mesa）后再装，避免叠加
        if (manifestFile(display).isFile) {
            uninstall(display, log)
        }

        // staging 解压
        val staging = File(rfs("usr/tmp"), "anwind-wrapper-install-$slot")
        runCatching {
            if (staging.exists()) staging.deleteRecursively()
            staging.mkdirs()
        }.onFailure {
            log("⚠ Wrapper 驱动 staging 准备失败：${it.message}")
            return null
        }
        val err = GuanliSupport.extractTarTo(tarPath, staging) { log(it) }
        if (err != null) {
            log("⚠ Wrapper 驱动 ${display} 解压失败：$err（继续启动，Vulkan 走 rootfs 自带 Mesa）")
            runCatching { staging.deleteRecursively() }
            return null
        }

        // 归一化：解析包形态，得到【待落位文件 → rootfs 相对路径】映射 + 驱动 so
        val plan = planInstall(staging)
        if (plan == null || plan.driverSoRel == null) {
            log("⚠ Wrapper 驱动 ${display} 内未找到 libvulkan*.so（支持 rootfs 布局 / meta.json / 根级裸包三种形态）—— 已跳过")
            runCatching { staging.deleteRecursively() }
            return null
        }

        // 落位（带备份）+ manifest
        val manifest = StringBuilder()
        var installed = 0
        var backedUp = 0
        runCatching {
            for ((src, rel) in plan.files) {
                val dest = rfs(rel)
                dest.parentFile?.mkdirs()
                if (dest.exists()) {
                    // 被覆盖（Mesa 同名 so / 之前残留）→ 备份原文件，卸载时恢复
                    val bak = File(backupDir(display), rel)
                    bak.parentFile?.mkdirs()
                    dest.copyTo(bak, overwrite = true)
                    manifest.append("backup\t$rel\n")
                    backedUp++
                }
                src.copyTo(dest, overwrite = true)
                manifest.append("file\t$rel\n")
                installed++
            }
        }.onFailure {
            log("⚠ Wrapper 驱动 ${display} 落位失败：${it.message}")
            runCatching { staging.deleteRecursively() }
            return null
        }
        runCatching { staging.deleteRecursively() }

        // 自生成 ICD 清单（WinNative 同款：宿主绝对路径，不信任包内 json）
        val libAbs = rfs(plan.driverSoRel!!).absolutePath
        val icdJson = icdFile(display)
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
            metaDir.mkdirs()
            icdJson.writeText(json)
            manifestFile(display).writeText(manifest.toString())
            stateFile().writeText(display)
            log("Wrapper 驱动 ${display} 已落位：$installed 个文件 → usr/（备份覆盖 $backedUp 个）")
            log("驱动库 → $libAbs")
            log("Vulkan ICD → ${icdJson.absolutePath}（VK_ICD_FILENAMES 注入后生效）")
            icdJson
        }.getOrElse {
            log("⚠ Wrapper 驱动 ICD 清单写入失败：${it.message}")
            null
        }
    }

    /** 包形态解析结果：files = staging 文件 → rootfs 相对路径；driverSoRel = 驱动 so。 */
    private data class InstallPlan(val files: List<Pair<File, String>>, val driverSoRel: String?)

    /**
     * 解析三种包形态（A 判断对 root / 剥壳后 base 各做一次 —— rootfs 布局
     * 包的顶层目录就是 usr，不能被"剥壳"逻辑剥掉）：
     *  A. rootfs 布局：usr 树全量按相对路径落位（lib + share 一体）；
     *  B. WinNative meta.json：libraryName 指定的 so + 同目录附属 .so → usr/lib/；
     *  C. yuzu 裸包：根级 libvulkan*.so + 同目录附属 .so → usr/lib/。
     */
    private fun planInstall(root: File): InstallPlan? {
        // A) rootfs 布局（顶层即 usr/）
        planRootfsLayout(root)?.let { return it }

        // 剥壳：唯一顶层目录且根级无文件 → 下钻一层再判（usr 是布局本体不剥）
        val dirs = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty()
        val rootFiles = root.listFiles { f -> f.isFile }.orEmpty()
        if (dirs.size == 1 && rootFiles.isEmpty() && dirs[0].name != "usr") {
            val base = dirs[0]
            planRootfsLayout(base)?.let { return it }
            planFlatLayout(base)?.let { return it }
            return null
        }
        return planFlatLayout(root)
    }

    /** A) rootfs 布局包：usr/ 树全量落位，驱动 so = usr/lib 下 libvulkan*.so。 */
    private fun planRootfsLayout(base: File): InstallPlan? {
        val usrDir = listOf(File(base, "usr"), File(base, "usr/local"))
            .firstOrNull { it.isDirectory } ?: return null
        val files = mutableListOf<Pair<File, String>>()
        var driverSo: String? = null
        usrDir.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = "usr/" + f.toRelativeString(usrDir)
            files.add(f to rel)
            if (driverSo == null && f.name.startsWith("libvulkan") && f.name.endsWith(".so")) {
                driverSo = rel
            }
        }
        return if (driverSo != null) InstallPlan(files, driverSo) else null
    }

    /** B/C) 扁平包：WinNative meta.json（libraryName）或 yuzu 裸包（根级 libvulkan*.so）。 */
    private fun planFlatLayout(base: File): InstallPlan? {
        // B) WinNative meta.json：libraryName
        runCatching {
            val meta = File(base, "meta.json")
            if (meta.isFile) {
                val m = Regex("\"libraryName\"\\s*:\\s*\"([^\"]+)\"")
                    .find(meta.readText())?.groupValues?.get(1)
                if (!m.isNullOrBlank()) {
                    val so = File(base, m)
                    if (so.isFile) {
                        // 驱动 so + 同目录附属 .so 全部落位 usr/lib/（meta.json
                        // 仅为包元数据，不进 rootfs）
                        val files = mutableListOf(so to "$DRIVER_LIB_DIR/${so.name}")
                        base.listFiles { f -> f.isFile && f.name.endsWith(".so") }
                            .orEmpty().forEach { f ->
                                if (f != so) files.add(f to "$DRIVER_LIB_DIR/${f.name}")
                            }
                        return InstallPlan(files, "$DRIVER_LIB_DIR/${so.name}")
                    }
                }
            }
        }
        // C) yuzu 裸包：根级 libvulkan*.so + 附属 .so
        val rootSo = base.listFiles { f -> f.isFile && f.name.startsWith("libvulkan") && f.name.endsWith(".so") }
            ?.firstOrNull() ?: return null
        val files = mutableListOf(rootSo to "$DRIVER_LIB_DIR/${rootSo.name}")
        base.listFiles { f -> f.isFile && f.name.endsWith(".so") }
            .orEmpty().forEach { f ->
                if (f != rootSo) files.add(f to "$DRIVER_LIB_DIR/${f.name}")
            }
        return InstallPlan(files, "$DRIVER_LIB_DIR/${rootSo.name}")
    }

    // ------------------------------------------------------------------
    // 卸载（按 manifest 恢复 Mesa）
    // ------------------------------------------------------------------

    /**
     * 卸载一个 slot：删除 manifest 里记录的安装文件、恢复备份的被覆盖
     * 文件、清理 ICD 清单与备份目录。找不到 manifest 时静默成功。
     *
     * 注意：被覆盖的文件在 manifest 里同时有 backup 与 file 两条记录 ——
     * 卸载语义是【恢复】（恢复本身已覆盖 wrapper 版本），file 分支必须
     * 跳过有 backup 记录的 rel，否则先恢复后删除会把刚恢复的 Mesa
     * 原文件又删掉（端到端测试实测抓出）。
     */
    fun uninstall(display: String, log: (String) -> Unit = {}) {
        runCatching {
            val mf = manifestFile(display)
            if (!mf.isFile) return
            val lines = mf.readLines().filter { it.isNotBlank() }
            // 有 backup 记录的 rel 集合（这些文件走恢复，不走删除）
            val backedUpRels = lines
                .filter { it.startsWith("backup\t") }
                .map { it.removePrefix("backup\t").trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            var restored = 0
            var removed = 0
            val backupRoot = backupDir(display)
            lines.forEach { line ->
                val kind = line.substringBefore('\t')
                val rel = line.substringAfter('\t', "").trim()
                if (rel.isBlank()) return@forEach
                when (kind) {
                    "file" -> {
                        if (rel !in backedUpRels && rfs(rel).delete()) removed++
                    }
                    "backup" -> {
                        val bak = File(backupRoot, rel)
                        if (bak.isFile) {
                            val dest = rfs(rel)
                            dest.parentFile?.mkdirs()
                            bak.copyTo(dest, overwrite = true)
                            restored++
                        }
                    }
                }
            }
            // 清理：manifest / ICD / 备份目录
            mf.delete()
            icdFile(display).delete()
            backupRoot.deleteRecursively()
            if (installedSlot() == display) stateFile().delete()
            log("Wrapper 驱动 ${display} 已卸载：移除 $removed 个文件，恢复 $restored 个原文件（Mesa 回归）")
        }.onFailure {
            log("⚠ Wrapper 驱动 ${display} 卸载异常：${it.message}")
        }
    }

    /**
     * 启动时的槽位同步：容器 wrapper 键与已安装 slot 不一致时先卸载旧的。
     * （安装新 slot 由调用方随后执行；wrapper=off 时仅卸载。）
     */
    fun syncSlot(current: String?, log: (String) -> Unit = {}) {
        val installed = installedSlot()
        if (installed != null && installed != current) {
            log("Wrapper 槽位切换：$installed → ${current ?: "off"}，先卸载旧驱动恢复 Mesa")
            uninstall(installed, log)
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 解析 ICD json 的 ICD.library_path（宽容解析，任何失败返回 null）。 */
    private fun parseIcdLibraryPath(json: File): String? = runCatching {
        val text = json.readText()
        if (!text.contains("\"ICD\"")) return@runCatching null
        Regex("\"library_path\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
    }.getOrNull()

    /** 诊断输出（容器体检 / 日志用）：已安装的 wrapper 驱动信息。 */
    fun installedDrivers(): List<String> {
        val installed = installedSlot()?.let { listOf(it) } ?: emptyList()
        val stale = metaDir.takeIf { it.isDirectory }
            ?.listFiles { f -> f.isFile && f.name.endsWith(".manifest") }
            .orEmpty()
            .map { it.name.removeSuffix(".manifest") }
            .filter { it !in installed }
        return (installed + stale).sorted()
    }
}
