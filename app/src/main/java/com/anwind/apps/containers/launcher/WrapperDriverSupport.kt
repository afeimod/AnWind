package com.anwind.apps.containers.launcher

import com.anwind.apps.containers.ContainerManager
import com.anwind.apps.containers.GuanliSupport
import java.io.File
import java.util.zip.ZipInputStream

/**
 * v3.3.16（完全重写，逐语义对齐 WinNative DriverPackages.java）：
 * Wrapper / Turnip 用户驱动接入 bionic rootfs。
 *
 * ## v3.3.15 旧实现的问题（用户实测反馈："和 turnip 冲突，不能用，
 * 因为一直在使用 turnip 作为驱动"）
 *
 * 旧版把驱动按 rootfs 布局【覆盖落位】rootfs/usr/lib/ —— 用户驱动包
 * 里的 libvulkan_freedreno.so 与 rootfs 自带 Mesa Turnip 同名直接互踩：
 * 装了 wrapper，Mesa turnip 的 so 被顶掉；卸载时恢复，来回读写；
 * 同一时刻只能存在一个版本，"wrapper 和 turnip 老打架"。
 *
 * ## 新模型（WinNative DriverPackages 同款：独立目录、多驱动共存、
 * ICD 显式选择 —— turnip 与 wrapper 从"覆盖竞争"变为"并列选项"）
 *
 *  * 每个驱动一个【独立目录】，rootfs 任何文件都不被覆盖：
 *      usr/share/anwind-wrapper/drivers/<slot>/
 *        ├── libvulkan_*.so + 附属 .so（libc++_shared.so 等）
 *        ├── meta.json   （name / libraryName / installedAt / sourceFile）
 *        └── icd.json    （自生成 Vulkan ICD，library_path=宿主绝对路径）
 *    WinNative 对应物：contents/linux-drivers/<name>/ + meta.json +
 *    driver_icd.json 三件套，语义完全一致。
 *  * 【turnip（Mesa 内置）= BUNDLED 档】：rootfs 自带
 *    usr/share/vulkan/icd.d/freedreno_icd.aarch64.json（libvulkan_freedreno.so
 *    在 usr/lib），作为驱动类型之一出现在选择列表，不可卸载 ——
 *    WinNative BUNDLED 同语义。
 *  * 【多版本共存】：drivers/ 下任意多个 wrapper / turnip 驱动并存，
 *    每个有自己的 ICD；切换 = 换一个 VK_ICD_FILENAMES 指向，零文件
 *    读写（WinNative selectLinux 只写 SharedPreferences 同理）。
 *  * 【激活】（RootfsLauncher 6a / anwind-wine apply_wrapper_driver）：
 *      VK_ICD_FILENAMES=<drivers/<slot>/icd.json>   ← Vulkan 侧
 *      LD_LIBRARY_PATH 前插 drivers/<slot>/          ← 附属 .so 解析
 *      GALLIUM_DRIVER=zink（auto 档联动）             ← GL over Vulkan
 *    （WinNative XServerDisplayActivity：icd != null 时
 *    guest.add("VK_ICD_FILENAMES=" + icd.getPath()) 同款）
 *  * 【yuzu 式安装】：UI 点 ➕ 选 zip/tar.gz/tar.xz/tar.bz2 →
 *    解析 → 落位独立目录 → 立即出现在版本列表（WinNative
 *    promptInstallDriverFromFile → DriverPackages.install → 自动选中）。
 *
 * ## 支持的包形态（全部归一到独立目录）
 *
 *  1. rootfs 布局包（用户主要形态）：usr/lib 下全部 .so（libvulkan 开头的
 *     驱动本体 + 附属 so）+ usr/share/…（share 侧内容一并收进 slot 目录，
 *     ICD 自生成，不信任包内 json 的 library_path）；
 *  2. WinNative meta.json 包：根级 meta.json 的 libraryName 指明驱动；
 *  3. yuzu 裸包：根级 libvulkan*.so（+ 同目录附属 .so）。
 *
 * ## 旧版迁移
 *
 * v3.3.15 及以前的"覆盖式"安装会在 usr/share/anwind-wrapper/ 留下
 * <slot>.manifest + backup/<slot>/（被覆盖 Mesa 文件的备份）——
 * [migrateLegacy] 在首次访问时按清单恢复 rootfs 原文件并清掉旧痕迹，
 * 旧 slot 之后按新模型重新安装即可。
 */
object WrapperDriverSupport {

    /** 驱动安装根目录（rootfs 内；WinNative 对应 contents/linux-drivers/）。 */
    const val DRIVER_ROOT_REL = "usr/share/anwind-wrapper/drivers"

    /** 每个驱动 slot 目录内的 ICD 清单文件名（WinNative DRIVER_ICD 同位）。 */
    const val ICD_NAME = "icd.json"

    /** 每个驱动 slot 目录内的元数据文件名（WinNative meta.json 同名）。 */
    const val META_NAME = "meta.json"

    private val RFS_ROOT get() = ContainerManager.ROOTFS_ROOT

    private fun rfs(rel: String) = File(RFS_ROOT, rel)

    /** 驱动安装根目录。 */
    val driverRoot: File get() = rfs(DRIVER_ROOT_REL)

    /** 已安装驱动的元数据快照（UI 版本列表 / 激活探测共用）。 */
    data class DriverEntry(
        /** slot 目录名（稳定 id）。 */
        val slot: String,
        /** 驱动库名（libvulkan_*.so）。 */
        val libraryName: String,
        /** 原包文件名（显示用）。 */
        val sourceFile: String,
        /** slot 目录。 */
        val dir: File,
        /** slot 的 ICD 清单（存在且 library 指向真实文件才非空）。 */
        val icd: File?
    )

    /** slot（包显示名）→ 安全目录名（WinNative safeName 同语义）。 */
    fun safeSlotName(display: String): String =
        display.trim()
            .replace(Regex("\\.(zip|tar|tar\\.(gz|xz|bz2))$"), "")
            .replace(Regex("[^A-Za-z0-9._+-]"), "-")
            .trim('-', '.')
            .ifEmpty { "driver" }
            .take(64)

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 列出已安装驱动（slot 目录含 meta.json + 可用 ICD 才入列；按名排序）。 */
    fun listDrivers(): List<DriverEntry> {
        migrateLegacy()
        val root = driverRoot
        if (!root.isDirectory) return emptyList()
        return root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            .orEmpty()
            .mapNotNull { dir ->
                val meta = File(dir, META_NAME)
                if (!meta.isFile) return@mapNotNull null
                val lib = runCatching {
                    Regex("\"libraryName\"\\s*:\\s*\"([^\"]+)\"")
                        .find(meta.readText())?.groupValues?.get(1)
                }.getOrNull() ?: return@mapNotNull null
                val src = runCatching {
                    Regex("\"sourceFile\"\\s*:\\s*\"([^\"]+)\"")
                        .find(meta.readText())?.groupValues?.get(1)
                }.getOrNull() ?: dir.name
                DriverEntry(
                    slot = dir.name,
                    libraryName = lib,
                    sourceFile = src,
                    dir = dir,
                    icd = icdFor(dir.name)
                )
            }
            .sortedBy { it.slot.lowercase() }
    }

    /**
     * slot 的 ICD 清单（存在且 library_path 指向真实 .so 才算数）。
     * RootfsLauncher / anwind-wine 注入 VK_ICD_FILENAMES 的探测入口。
     */
    fun icdFor(slot: String): File? {
        val json = File(File(driverRoot, safeSlotName(slot)), ICD_NAME)
        if (!json.isFile) return null
        val lib = parseIcdLibraryPath(json) ?: return null
        // library_path 为宿主绝对路径（自生成保证）；相对路径按 slot 目录解析
        val libFile = if (lib.startsWith("/")) File(lib) else File(json.parentFile, lib)
        return if (libFile.isFile) json else null
    }

    /**
     * 【turnip BUNDLED 档】rootfs 自带 Mesa Turnip 的 ICD
     * （不存在时返回 null —— 表示"不注入 VK_ICD_FILENAMES，走 loader
     * 默认搜索"，rootfs 的 vulkan-icd-loader 配置了标准路径）。
     */
    fun bundledTurnipIcd(): File? {
        val icd = rfs("usr/share/vulkan/icd.d/freedreno_icd.aarch64.json")
        return if (icd.isFile) icd else null
    }

    /**
     * wrapper/用户 turnip 激活时 GL 侧是否应联动 zink：
     * 容器 gallium 为 auto/空/zink 时 true（GL over Vulkan 吃驱动加速），
     * 用户显式选 freedreno/softpipe 等 GL 驱动时 false（尊重选择）。
     */
    fun shouldForceZink(gallium: String): Boolean {
        val g = gallium.trim().lowercase()
        return g.isBlank() || g == "auto" || g == "zink"
    }

    // ------------------------------------------------------------------
    // 安装（yuzu 式：zip / tar 包 → 独立目录）
    // ------------------------------------------------------------------

    /**
     * 安装一个驱动包到独立 slot 目录（WinNative DriverPackages.install
     * 同语义：staging 解包 → 校验 → 落位 → 生成 ICD + meta）。
     *
     * @param srcPath 包文件路径（.zip / .tar / .tar.gz / .tar.xz / .tar.bz2）
     * @param log 进度输出
     * @return null 成功；否则用户可读的错误信息
     */
    fun install(srcPath: String, log: (String) -> Unit = {}): String? {
        migrateLegacy()
        val src = File(srcPath)
        if (!src.isFile) return "文件不存在：$srcPath"
        val slot = safeSlotName(src.name)

        // staging 解包（rootfs tmp 下，与安装目标同文件系统，rename 原子）
        val staging = File(rfs("usr/tmp"), "anwind-driver-install-$slot")
        runCatching {
            if (staging.exists()) staging.deleteRecursively()
            staging.mkdirs()
        }.onFailure { return "staging 准备失败：${it.message}" }

        val err = when {
            src.name.endsWith(".zip", true) -> unzipTo(src, staging)
            else -> GuanliSupport.extractTarTo(src.absolutePath, staging) { log(it) }
        }
        if (err != null) {
            runCatching { staging.deleteRecursively() }
            return err
        }

        // 归一化解析包形态 → 待收集文件列表 + 驱动 so 名
        val plan = planInstall(staging)
            ?: run {
                runCatching { staging.deleteRecursively() }
                return "包内未找到 libvulkan*.so（支持 rootfs 布局 / meta.json / yuzu 裸包三种形态）"
            }

        // 目标目录（已存在 = 同名重装：先删旧目录，driver 独立目录无共享
        // 文件，直接删除不影响其他 slot 与 Mesa）
        val dest = File(driverRoot, slot)
        runCatching {
            if (dest.exists()) dest.deleteRecursively()
            dest.mkdirs()
        }.onFailure {
            runCatching { staging.deleteRecursively() }
            return "目标目录创建失败：${it.message}"
        }

        // 落位：驱动 + 附属 .so（及 share 侧文件）平铺进 slot 目录
        var installed = 0
        runCatching {
            plan.forEach { (file, name) ->
                val out = File(dest, name)
                file.copyTo(out, overwrite = true)
                installed++
            }
        }.onFailure {
            runCatching { dest.deleteRecursively(); staging.deleteRecursively() }
            return "驱动落位失败：${it.message}"
        }
        runCatching { staging.deleteRecursively() }

        // 自生成 ICD（WinNative 同款：宿主绝对路径，不信任包内 json）
        val libAbs = File(dest, plan.driverSoName).absolutePath
        val icdJson = buildString {
            appendLine("{")
            appendLine("    \"file_format_version\": \"1.0.0\",")
            appendLine("    \"ICD\": {")
            appendLine("        \"api_version\": \"1.4.0\",")
            appendLine("        \"library_path\": \"$libAbs\"")
            appendLine("    }")
            appendLine("}")
        }
        // meta.json（WinNative 同款字段）
        val metaJson = buildString {
            appendLine("{")
            appendLine("    \"name\": \"$slot\",")
            appendLine("    \"libraryName\": \"${plan.driverSoName}\",")
            appendLine("    \"sourceFile\": \"${src.name}\",")
            appendLine("    \"installedAt\": ${System.currentTimeMillis()}")
            appendLine("}")
        }
        return runCatching {
            File(dest, ICD_NAME).writeText(icdJson)
            File(dest, META_NAME).writeText(metaJson)
            log("✅ 驱动 $slot 已安装：$installed 个文件 → ${dest.absolutePath}")
            log("Vulkan ICD → ${File(dest, ICD_NAME).absolutePath}（容器选它即生效）")
            null
        }.getOrElse { "ICD/meta 写入失败：${it.message}" }
    }

    /** 包形态解析结果：files = staging 文件 → slot 内文件名；driverSoName = 驱动 so。 */
    private data class InstallPlan(val files: List<Pair<File, String>>, val driverSoName: String)

    /**
     * 解析三种包形态（对 root / 剥壳后 base 各做一次 —— rootfs 布局包
     * 的顶层目录就是 usr，不能被"剥壳"逻辑剥掉）：
     *  A. rootfs 布局：usr/lib 的 .so 们收进 slot（usr/share 的
     *     vulkan/icd.d json 也收着作参考，其余 share 内容跳过）；
     *  B. WinNative meta.json：libraryName 指定的 so + 同目录附属 .so；
     *  C. yuzu 裸包：根级 libvulkan*.so + 同目录附属 .so。
     */
    private fun planInstall(root: File): InstallPlan? {
        planRootfsLayout(root)?.let { return it }

        // 剥壳：唯一顶层目录且根级无文件 → 下钻一层再判（usr 是布局本体不剥）
        val dirs = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty()
        val rootFiles = root.listFiles { f -> f.isFile }.orEmpty()
        if (dirs.size == 1 && rootFiles.isEmpty() && dirs[0].name != "usr") {
            val base = dirs[0]
            planRootfsLayout(base)?.let { return it }
            return planFlatLayout(base)
        }
        return planFlatLayout(root)
    }

    /** A) rootfs 布局包：usr/lib 的全部 .so + usr/share/vulkan 的 json。 */
    private fun planRootfsLayout(base: File): InstallPlan? {
        val usrDir = listOf(File(base, "usr"), File(base, "usr/local"))
            .firstOrNull { it.isDirectory } ?: return null
        val libDir = listOf(File(usrDir, "lib"), File(usrDir, "lib64"))
            .firstOrNull { it.isDirectory } ?: return null
        val files = mutableListOf<Pair<File, String>>()
        var driverSo: String? = null
        libDir.listFiles { f -> f.isFile && f.name.endsWith(".so") }
            .orEmpty().forEach { f ->
                files.add(f to f.name)
                if (driverSo == null && f.name.startsWith("libvulkan") && f.name.endsWith(".so")) {
                    driverSo = f.name
                }
            }
        if (driverSo == null) return null
        // usr/share/vulkan/icd.d/*.json 一并收进 slot（仅参考，激活用自生成 ICD）
        File(usrDir, "share/vulkan/icd.d").takeIf { it.isDirectory }
            ?.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.forEach { files.add(it to it.name) }
        return InstallPlan(files, driverSo!!)
    }

    /** B/C) 扁平包：WinNative meta.json（libraryName）或 yuzu 裸包（根级 libvulkan*.so）。 */
    private fun planFlatLayout(base: File): InstallPlan? {
        // B) WinNative meta.json：libraryName
        runCatching {
            val meta = File(base, META_NAME)
            if (meta.isFile) {
                val m = Regex("\"libraryName\"\\s*:\\s*\"([^\"]+)\"")
                    .find(meta.readText())?.groupValues?.get(1)
                if (!m.isNullOrBlank()) {
                    val so = File(base, m)
                    if (so.isFile) {
                        val files = mutableListOf(so to so.name)
                        base.listFiles { f -> f.isFile && f.name.endsWith(".so") }
                            .orEmpty().forEach { f -> if (f != so) files.add(f to f.name) }
                        return InstallPlan(files, so.name)
                    }
                }
            }
        }
        // C) yuzu 裸包：根级 libvulkan*.so + 附属 .so
        val rootSo = base.listFiles { f -> f.isFile && f.name.startsWith("libvulkan") && f.name.endsWith(".so") }
            ?.firstOrNull() ?: return null
        val files = mutableListOf(rootSo to rootSo.name)
        base.listFiles { f -> f.isFile && f.name.endsWith(".so") }
            .orEmpty().forEach { f -> if (f != rootSo) files.add(f to f.name) }
        return InstallPlan(files, rootSo.name)
    }

    // ------------------------------------------------------------------
    // 卸载（独立目录整体删除，不碰其他 slot 与 Mesa）
    // ------------------------------------------------------------------

    /** 卸载一个 slot。@return null 成功。 */
    fun uninstall(slot: String, log: (String) -> Unit = {}): String? {
        val dir = File(driverRoot, safeSlotName(slot))
        if (!dir.isDirectory) return "驱动未安装：$slot"
        // 路径越界防护（dir 必须直接位于 driverRoot 之下）
        if (dir.parentFile?.canonicalPath != driverRoot.canonicalPath)
            return "拒绝卸载：路径越界"
        return runCatching {
            if (!dir.deleteRecursively()) return "删除未完成（部分文件被占用，可重试）"
            log("驱动 $slot 已卸载（${dir.absolutePath}）")
            null
        }.getOrElse { "卸载异常：${it.message}" }
    }

    // ------------------------------------------------------------------
    // 旧版（v3.3.15 覆盖式安装）迁移
    // ------------------------------------------------------------------

    /**
     * v3.3.15 及以前的覆盖式安装会在 usr/share/anwind-wrapper/ 留下
     * <slot>.manifest + backup/<slot>/（被覆盖 Mesa 文件的备份）。
     * 首次访问时按清单恢复 rootfs 原文件并清掉旧痕迹（一次性，幂等）。
     */
    private fun migrateLegacy() {
        runCatching {
            val legacyRoot = rfs("usr/share/anwind-wrapper")
            if (!legacyRoot.isDirectory) return
            val manifests = legacyRoot.listFiles { f -> f.isFile && f.name.endsWith(".manifest") }
                .orEmpty()
            manifests.forEach { mf ->
                val slot = mf.name.removeSuffix(".manifest")
                val lines = mf.readLines().filter { it.isNotBlank() }
                val backedUp = lines
                    .filter { it.startsWith("backup\t") }
                    .map { it.removePrefix("backup\t").trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                lines.forEach { line ->
                    val kind = line.substringBefore('\t')
                    val rel = line.substringAfter('\t', "").trim()
                    if (rel.isBlank()) return@forEach
                    when (kind) {
                        "file" -> if (rel !in backedUp) rfs(rel).delete()
                        "backup" -> {
                            val bak = File(File(legacyRoot, "backup/$slot"), rel)
                            if (bak.isFile) {
                                val dest = rfs(rel)
                                dest.parentFile?.mkdirs()
                                bak.copyTo(dest, overwrite = true)
                            }
                        }
                    }
                }
                mf.delete()
            }
            // 旧 state / backup / ICD 残留清理
            File(legacyRoot, "state").delete()
            File(legacyRoot, "backup").takeIf { it.isDirectory }?.deleteRecursively()
            legacyRoot.listFiles { f -> f.isFile && f.name.startsWith("anwind-wrapper-") && f.name.endsWith(".json") }
                ?.forEach { it.delete() }
            // ICD 目录里旧的自生成清单
            rfs("usr/share/vulkan/icd.d").takeIf { it.isDirectory }
                ?.listFiles { f -> f.isFile && f.name.startsWith("anwind-wrapper-") }
                ?.forEach { it.delete() }
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

    /** zip 解压（yuzu 驱动包是 zip 格式；路径安全校验防 zip-slip）。 */
    private fun unzipTo(zip: File, dest: File): String? = runCatching {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            val canonicalDest = dest.canonicalPath + File.separator
            while (entry != null) {
                val out = File(dest, entry.name)
                if (!out.canonicalPath.startsWith(canonicalDest)) {
                    return@runCatching "zip 内路径越界：${entry.name}"
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        null
    }.getOrElse { "zip 解压失败：${it.message}" }
}
