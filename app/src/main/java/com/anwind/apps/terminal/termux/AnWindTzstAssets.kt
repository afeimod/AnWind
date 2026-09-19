package com.anwind.apps.terminal.termux

import android.content.Context
import android.system.Os
import com.termux.x11.controller.core.TarCompressorUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * AnWind 内置 tzst 资产自解压（v2.26 · assets 自解压引擎）。
 *
 * ------------------------------------------------------------------
 * 资产契约（APK 内 assets/anwind/ 目录，全部为 tzst 格式 = tar + zstd，
 * 同时兼容 .tar.zst 后缀；CI 构建产物改名后放入即可）：
 *
 *   assets/anwind/rootfs-*.tzst   bionic rootfs（data/data 布局或 usr/ 布局均可）
 *   assets/anwind/mesa-*.tzst     Mesa GL（libGL/EGL/gbm + dri/ + freedreno/turnip ICD）
 *   assets/anwind/box64-*.tzst    box64（usr/local/bin/box64 或 bin/box64 布局）
 *   assets/anwind/turnip-*.tzst   Turnip Vulkan 驱动（usr/lib + share/vulkan/icd.d）
 *   assets/anwind/dxvk-*.tzst     DXVK（顶层 system32/ + syswow64/ 布局；
 *                                 兼容上游 x64/ x32|x86/ 布局自动重映射）
 *
 * 解压目标（与 rootfs 侧 anwind-container / 终端侧 glibc-runner 双端契约一致）：
 *   rootfs 根 : /data/data/com.anwind/files/rootfs
 *     - rootfs / mesa / box64 / turnip 四类资产按成员路径重映射后落位；
 *     - CI 发行包的 data/data/com.anwind/files/rootfs 前缀成员自动剥壳；
 *     - usr/、etc/、home/ 等相对布局直接落入 rootfs 根；
 *     - com.termux 旧路径成员自动改写为 com.anwind（等长重写语义）。
 *
 *   wine 前缀  : <WINEPREFIX>/drive_c/windows
 *     - DXVK 资产【前缀构建后】才解压（system.reg + drive_c/windows 存在
 *       判定前缀已构建；未构建时等待下次调用，绝不预创建）；
 *     - 用户资产为顶层 system32/ + syswow64/ 布局：直接解压进
 *       drive_c/windows，DLL 各就各位（不是 x32/x64 目录样式）；
 *     - 上游 DXVK 发布包（x64/ + x32|x86/）自动重映射：
 *       x64 → system32，x32/x86 → syswow64；
 *     - 裸 *.dll 成员（无目录布局）一律按 64 位落入 system32。
 *
 * 幂等标记（fingerprint = 资产名 + 字节长度 + CRC；资产不变不重复解压）：
 *   rootfs 侧 : <rootfs>/usr/etc/anwind/.tzst-assets-fingerprint
 *               （随 rootfs 重建自动失效 → 换 rootfs 后资产自动重铺）
 *   dxvk 侧   : <prefix>/anwindmeta/.anwind-dxvk-applied
 *               （与 glibc-runner [F5] 消费同一标记名，双端互认）
 *
 * 解压引擎复用 termux-x11 模块的 TarCompressorUtils
 * （Apache Commons Compress + zstd-jni，纯 Java 实现，不依赖设备 tar/zstd）。
 */
object AnWindTzstAssets {

    private const val TAG = "AnWindTzstAssets"

    /** APK 内资产目录。 */
    const val ASSET_DIR = "anwind"

    /** bionic rootfs 根（与 ContainerManager.ROOTFS_ROOT 逐字一致）。 */
    const val ROOTFS_ROOT = "/data/data/com.anwind/files/rootfs"

    /** glibc 前缀根（glibc-runner 侧 WINEPREFIX 默认值所在）。 */
    private const val GLIBC_PREFIX = "/data/data/com.anwind/files/usr/glibc"

    /** 资产指纹文件（rootfs 侧）。 */
    private fun fingerprintFile(): File =
        File("$ROOTFS_ROOT/usr/etc/anwind/.tzst-assets-fingerprint")

    /** dxvk 待应用状态（Compose/界面可订阅；true = 本次调用已全部就位）。 */
    private val _dxvkReady = MutableStateFlow(false)
    val dxvkReady: StateFlow<Boolean> = _dxvkReady

    // ------------------------------------------------------------------
    // 资产扫描
    // ------------------------------------------------------------------

    /** tzst 资产分类（按文件名关键词识别，容错 .tzst / .tar.zst 两种后缀）。 */
    private enum class Kind(val keyword: String) {
        ROOTFS("rootfs"), MESA("mesa"), BOX64("box64"),
        TURNIP("turnip"), DXVK("dxvk");

        companion object {
            // 关键词命中顺序 = 特异性优先（dxvk/turnip 等具体件名先于
            // rootfs/mesa 等泛名；"anwind-mesa-turnip" 类罕见命名归 turnip）
            private val ORDER = listOf(DXVK, TURNIP, BOX64, MESA, ROOTFS)

            /**
             * 命中规则：文件名含关键词即归类。同时兼容
             *  - 简短命名: rootfs-xxx.tzst / dxvk-2.4.tar.zst
             *  - CI 产物原名: anwind-bionic-rootfs-20260919-aarch64.tzst、
             *    anwind-mesa-25.1-aarch64-bionic.tzst、anwind-box64-v0.2.9.tzst
             */
            fun of(name: String): Kind? {
                val lower = name.lowercase()
                return ORDER.firstOrNull { lower.contains(it.keyword) }
            }
        }
    }

    private fun isTzst(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".tzst") || lower.endsWith(".tar.zst")
    }

    /** 扫描 assets/anwind/ 下的 tzst 资产 → 分类列表（稳定排序）。 */
    private fun scanAssets(context: Context): Map<Kind, List<String>> {
        val result = mutableMapOf<Kind, MutableList<String>>()
        try {
            val names = context.assets.list(ASSET_DIR) ?: return result
            for (name in names) {
                if (!isTzst(name)) continue
                val kind = Kind.of(name) ?: continue
                result.getOrPut(kind) { mutableListOf() }.add(name)
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "扫描 ${ASSET_DIR}/ 资产失败: ${e.message}")
        }
        return result
    }

    private fun assetPath(name: String): String = "$ASSET_DIR/$name"

    /** 资产指纹（名+长度+CRC32），资产更新后自动重铺。 */
    private fun assetFingerprint(context: Context, name: String): String {
        var len = 0L
        var crc = 0L
        val buf = ByteArray(64 * 1024)
        try {
            context.assets.open(assetPath(name)).use { input ->
                val crc32 = java.util.zip.CRC32()
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    crc32.update(buf, 0, n)
                    len += n
                }
                crc = crc32.value
            }
        } catch (e: Exception) {
            return "err:${name}:${e.message}"
        }
        return "$name:$len:$crc"
    }

    // ------------------------------------------------------------------
    // 成员路径重映射（CI data/data 布局 → rootfs 根）
    // ------------------------------------------------------------------

    private val rootfsMembersPrefix = "data/data/com.anwind/files/rootfs/"
    private val legacyMembersPrefix = "data/data/com.termux/files/rootfs/"

    /**
     * tar 成员路径 → rootfs 根内真实落点。
     *  - data/data/com.anwind|com.termux/files/rootfs/xxx → rootfs/xxx
     *  - 其余（usr/ etc/ home/ bin/ lib/ ...）→ rootfs/<原路径>
     * 返回 null 表示目标越界（../ 逃逸防护），由调用方跳过。
     */
    private fun remapToRootfs(entryName: String): File? {
        val name = entryName.trimStart('.', '/')
        val relative = when {
            name.startsWith(rootfsMembersPrefix) -> name.removePrefix(rootfsMembersPrefix)
            name.startsWith(legacyMembersPrefix) -> name.removePrefix(legacyMembersPrefix)
            name == "data/data/com.anwind/files/rootfs" ||
                name == "data/data/com.termux/files/rootfs" -> ""
            else -> name
        }
        if (relative.isEmpty()) return null            // 根目录条目自身：跳过
        if (relative.contains("..")) return null       // 路径逃逸防护
        val root = File(ROOTFS_ROOT)
        val f = File(root, relative)
        // 双重保险：规范化后必须仍在 rootfs 根内
        val canonicalRoot = root.canonicalPath + File.separator
        if (!f.canonicalPath.startsWith(canonicalRoot) && f.canonicalPath != root.canonicalPath) {
            return null
        }
        return f
    }

    // ------------------------------------------------------------------
    // bionic 资产铺装（rootfs / mesa / box64 / turnip）
    // ------------------------------------------------------------------

    /**
     * 扫描并解压全部 bionic 侧 tzst 资产到 rootfs。
     * 幂等：指纹未变化的资产跳过；rootfs 被重建（指纹文件消失）后自动重铺。
     * 耗时操作（rootfs 可达数百 MB），必须在后台线程调用。
     *
     * @return 实际执行了解压的资产数（0 = 全部已就位）
     */
    fun installBionicAssetsIfNeeded(context: Context): Int {
        val assets = scanAssets(context)
        val bionic = listOf(Kind.ROOTFS, Kind.MESA, Kind.BOX64, Kind.TURNIP)
        if (bionic.none { assets[it]?.isNotEmpty() == true }) return 0

        val fpFile = fingerprintFile()
        val oldFps = try {
            if (fpFile.isFile) fpFile.readText().lines().filter { it.isNotBlank() }.toSet()
            else emptySet()
        } catch (_: Exception) { emptySet() }

        val newFps = mutableSetOf<String>()
        var applied = 0
        for (kind in bionic) {
            for (name in assets[kind].orEmpty().sorted()) {
                val fp = assetFingerprint(context, name)
                newFps.add(fp)
                if (fp in oldFps) continue   // 指纹一致：已铺装，跳过
                val ok = extractToRootfs(context, name, kind)
                if (ok) {
                    applied++
                    android.util.Log.i(TAG, "tzst 资产已铺装: $name → $ROOTFS_ROOT")
                } else {
                    android.util.Log.e(TAG, "tzst 资产解压失败: $name（保留指纹空缺，下次启动重试）")
                    newFps.remove(fp)
                }
            }
        }
        if (applied > 0 || (oldFps != newFps && newFps.isNotEmpty())) {
            try {
                fpFile.parentFile?.mkdirs()
                fpFile.writeText(newFps.sorted().joinToString("\n") + "\n")
                Os.chmod(fpFile.absolutePath, 420)   // 0644
            } catch (e: Exception) {
                android.util.Log.w(TAG, "指纹写入失败: ${e.message}")
            }
        }
        return applied
    }

    private fun extractToRootfs(context: Context, name: String, kind: Kind): Boolean {
        val root = File(ROOTFS_ROOT)
        root.mkdirs()
        return try {
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD, context, assetPath(name), root
            ) { destination, _ ->
                // TarCompressorUtils 传入 File(destinationDir, entryName)；
                // 成员路径重映射：剥 CI 前缀 / 旧包名改写，越界成员跳过
                val entryRel = destination.path.removePrefix(root.path + "/")
                remapToRootfs(entryRel)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "解压异常: $name: ${e.message}")
            false
        }
    }

    // ------------------------------------------------------------------
    // DXVK —— 前缀构建后的解压（核心需求）
    // ------------------------------------------------------------------

    /** 前缀已构建判定：system.reg 存在（wineboot 产物，与 glibc-runner 同判据）。 */
    private fun prefixBuilt(prefix: File): Boolean =
        File(prefix, "system.reg").isFile && File(prefix, "drive_c").isDirectory

    /**
     * 向所有已构建的 wine 前缀应用内置 DXVK 资产。
     *
     * "前缀构建后的解压"：本函数只处理 system.reg + drive_c 已就绪的前缀
     * （bionic 容器前缀 + glibc 默认前缀）；未构建的前缀跳过，等下次调用
     * （App 启动 / 进入容器界面）自动补齐。绝不预创建前缀或 drive_c。
     *
     * 落位规则（用户资产 = 顶层 system32/ + syswow64/ 布局，直接解压；
     * 非 x32/x64 目录样式）：
     *   system32 目录 → drive_c/windows/system32/   （64 位 DLL）
     *   syswow64 目录 → drive_c/windows/syswow64/   （32 位 DLL）
     * 兼容上游发布布局自动重映射：
     *   x64 目录      → system32
     *   x32、x86 目录 → syswow64
     *   裸 *.dll      → system32（视为 64 位）
     * ⚠ Kotlin 注释支持嵌套：注释文本里禁止出现 "斜杠+星号" 序列
     *   （会被 lexer 当作子注释开启，导致整段注释永不闭合）。
     *
     * 幂等标记: <prefix>/anwindmeta/.anwind-dxvk-applied（glibc-runner [F5]
     * 同名互认）；标记存在且指纹未变 → 跳过。资产更新（指纹变化）→ 重铺。
     *
     * @return 本次实际应用了 DXVK 的前缀数
     */
    fun applyDxvkWhenPrefixReady(context: Context): Int {
        val dxvkAssets = scanAssets(context)[Kind.DXVK].orEmpty().sorted()
        if (dxvkAssets.isEmpty()) {
            _dxvkReady.value = false
            return 0
        }

        // 指纹比对：全部 dxvk 资产的指纹串（单资产场景零开销）
        val combinedFp = dxvkAssets.joinToString("|") { assetFingerprint(context, it) }

        var appliedCount = 0
        for (prefix in knownPrefixes()) {
            if (!prefixBuilt(prefix)) continue          // ★ 前缀未构建：等待下次
            val meta = File(prefix, "anwindmeta")
            val mark = File(meta, ".anwind-dxvk-applied")
            if (mark.isFile) {
                val prev = try { mark.readText().trim() } catch (_: Exception) { "" }
                if (prev == combinedFp) continue        // 已应用且资产未变
            }
            val windows = File(prefix, "drive_c/windows")
            var ok = true
            for (name in dxvkAssets) {
                if (!extractDxvkToWindows(context, name, windows)) ok = false
            }
            if (ok) {
                try {
                    meta.mkdirs()
                    mark.writeText(combinedFp)
                    Os.chmod(mark.absolutePath, 420)
                    stampContainerDxvk(prefix, dxvkAssets.last())
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "dxvk 标记写入失败: ${e.message}")
                }
                appliedCount++
                android.util.Log.i(TAG, "DXVK 已解压到前缀: ${prefix.path} → drive_c/windows")
            }
        }
        _dxvkReady.value = appliedCount == 0
        return appliedCount
    }

    /** 已知的 wine 前缀集合（bionic 容器前缀 + glibc 默认前缀）。 */
    private fun knownPrefixes(): List<File> {
        val out = mutableListOf<File>()
        // bionic 容器前缀: rootfs/home/.anwind/containers/<name>/prefix
        val contRoot = File("$ROOTFS_ROOT/home/.anwind/containers")
        contRoot.listFiles()?.forEach { c ->
            val p = File(c, "prefix")
            if (p.isDirectory) out.add(p)
        }
        // glibc 默认前缀（glibc-runner WINEPREFIX 缺省值）
        out.add(File("$GLIBC_PREFIX/opt/prefix"))
        return out
    }

    /**
     * DXVK tzst → drive_c/windows。
     * 两段式：先解到 anwindmeta/.dxvk-stage 暂存目录，按顶层布局归位后清理。
     * （tar 成员布局在解压前不可知，暂存 + 归位可同时支持
     * system32/syswow64 直解与 x64/x32 重映射，且不污染 windows 目录。）
     */
    private fun extractDxvkToWindows(context: Context, name: String, windows: File): Boolean {
        val prefix = windows.parentFile?.parentFile ?: return false
        val stage = File(prefix, "anwindmeta/.dxvk-stage")
        try {
            stage.deleteRecursively()
            stage.mkdirs()
            val extracted = TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD, context, assetPath(name), stage
            )
            if (!extracted) {
                android.util.Log.e(TAG, "DXVK 解压失败: $name")
                return false
            }
            return installStagedDxvk(stage, windows)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "DXVK 解压异常: $name: ${e.message}")
            return false
        } finally {
            stage.deleteRecursively()
        }
    }

    /** 暂存目录 → drive_c/windows 的布局归位。 */
    private fun installStagedDxvk(stage: File, windows: File): Boolean {
        val system32 = File(windows, "system32")
        val syswow64 = File(windows, "syswow64")

        // 布局根：上游发布包常有单根目录包装（如 dxvk-2.4/x64），
        // 沿"仅含单个子目录且无散文件"的链路下钻到真实布局层
        val root = layoutRoot(stage)

        // 顶层布局探测（忽略深层嵌套的 repack 包根目录）
        val topDirs = root.listFiles()?.filter { it.isDirectory }?.map { it.name.lowercase() } ?: emptyList()
        val hasNativeLayout = topDirs.contains("system32") || topDirs.contains("syswow64")
        val hasUpstreamLayout = topDirs.contains("x64") || topDirs.contains("x32") || topDirs.contains("x86")

        return try {
            when {
                // 用户样式：顶层 system32/ + syswow64/ → 直接归位
                hasNativeLayout -> {
                    File(root, "system32").takeIf { it.isDirectory }
                        ?.let { copyTree(it, system32) }
                    File(root, "syswow64").takeIf { it.isDirectory }
                        ?.let { copyTree(it, syswow64) }
                    // 根级散置 dll（如有）→ system32
                    root.listFiles()?.filter { it.isFile && it.name.lowercase().endsWith(".dll") }
                        ?.forEach { copyFileTo(it, system32) }
                    true
                }
                // 上游样式：x64/ + x32|x86/ → 重映射到 system32 / syswow64
                hasUpstreamLayout -> {
                    listOf("x64").forEach {
                        File(root, it).takeIf { d -> d.isDirectory }?.let { d -> copyTree(d, system32) }
                    }
                    listOf("x32", "x86").forEach {
                        File(root, it).takeIf { d -> d.isDirectory }?.let { d -> copyTree(d, syswow64) }
                    }
                    true
                }
                // 无目录布局：裸 dll 全集 → system32（按 64 位处理）
                else -> {
                    var any = false
                    root.walkTopDown().filter { it.isFile && it.name.lowercase().endsWith(".dll") }
                        .forEach { copyFileTo(it, system32); any = true }
                    any
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "DXVK 归位失败: ${e.message}")
            false
        }
    }

    /**
     * 布局根下钻：目录仅含"一个子目录、零散文件"时进入该子目录，
     * 直到出现文件或多个子目录（system32+syswow64 / x64+x32 即停）。
     */
    private fun layoutRoot(start: File): File {
        var dir = start
        while (true) {
            val children = dir.listFiles() ?: return dir
            val dirs = children.filter { it.isDirectory }
            val hasFiles = children.any { it.isFile }
            if (hasFiles || dirs.size != 1) return dir
            dir = dirs[0]
        }
    }

    /** 容器配置回写 dxvk 版本戳（对齐 anwind-container install-dxvk 的记账）。 */
    private fun stampContainerDxvk(prefix: File, assetName: String) {
        try {
            // dxvk-2.4.tzst / dxvk-2.4-20260919.tar.zst → "2.4"
            val m = Regex("dxvk-([0-9]+(?:\\.[0-9]+)*)").find(assetName) ?: return
            val ver = m.groupValues[1]
            val contDir = prefix.parentFile ?: return
            val conf = File(contDir, "container.conf")
            if (!conf.isFile) return   // glibc 前缀无 container.conf：跳过
            val old = conf.readText()
            val line = "dxvk=$ver"
            val new = if (old.contains(Regex("^dxvk=.*$", RegexOption.MULTILINE))) {
                old.replace(Regex("^dxvk=.*$", RegexOption.MULTILINE), line)
            } else {
                old.trimEnd() + "\n" + line + "\n"
            }
            if (new != old) conf.writeText(new)
            // DLL 覆盖（anwind-wine 启动时导出为 WINEDLLOVERRIDES）
            val ovr = "dxoverrides=d3d8,d3d9,d3d10core,d3d11,dxgi=native,builtin"
            val newOvr = if (old.contains(Regex("^dxoverrides=.*$", RegexOption.MULTILINE))) {
                new.replace(Regex("^dxoverrides=.*$", RegexOption.MULTILINE), ovr)
            } else {
                new.trimEnd() + "\n" + ovr + "\n"
            }
            if (newOvr != new) conf.writeText(newOvr)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // 文件归位工具（保留权限语义：目录 0771，dll 0644 可被 wine 映射）
    // ------------------------------------------------------------------

    private fun copyFileTo(src: File, dstDir: File) {
        dstDir.mkdirs()
        val dst = File(dstDir, src.name)
        src.copyTo(dst, overwrite = true)
        try { Os.chmod(dst.absolutePath, 436) } catch (_: Exception) {}   // 0664
    }

    private fun copyTree(srcDir: File, dstDir: File) {
        dstDir.mkdirs()
        srcDir.listFiles()?.forEach { f ->
            if (f.isDirectory) copyTree(f, File(dstDir, f.name))
            else copyFileTo(f, dstDir)
        }
    }

    // ------------------------------------------------------------------
    // 一键入口
    // ------------------------------------------------------------------

    /**
     * 全量自解压（App 启动 / bootstrap 安装完成后调用，后台线程）：
     * ① rootfs / mesa / box64 / turnip → rootfs（指纹幂等）；
     * ② dxvk → 已构建前缀（未构建的前缀自动等待下次）。
     */
    fun installAllIfNeeded(context: Context) {
        try {
            installBionicAssetsIfNeeded(context)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "bionic tzst 资产铺装失败: ${e.message}")
        }
        try {
            applyDxvkWhenPrefixReady(context)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "dxvk tzst 应用失败: ${e.message}")
        }
    }
}
