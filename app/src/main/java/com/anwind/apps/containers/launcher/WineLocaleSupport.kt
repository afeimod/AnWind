package com.anwind.apps.containers.launcher

import android.content.Context
import com.anwind.apps.containers.ContainerData
import com.anwind.apps.containers.ContainerManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * v3.2.1（用户问题 4）—— wine 中文/本地化支持（wine.mo 翻译 + CJK 字体）。
 *
 * ## 根因（两层）
 *
 * 1. **界面语言（英文环境）**：wine 配方 `--enable-nls` 且 `--prefix=
 *    $prefix/opt/wine` —— wine 的 LOCALEDIR 指向 wine 安装目录内的
 *    `share/locale`。但 wine 包经 `install-wine` 装进**槽位目录**
 *    （/usr/opt/wine-10-18 等，编译期路径是默认槽位 /usr/opt/wine），
 *    槽位换名后编译期 LOCALEDIR 与真实目录脱节；再叠加 CI 镜像缺
 *    msgfmt 时 make install 静默跳过 .mo —— 结果设备上
 *    LANG=zh_CN.UTF-8 也只有英文（wine 自己的 NLS 加载器找不到
 *    zh_CN/wine.mo）。
 *
 *    修复：APK assets 携带已编译的 wine.mo（上游 wine-10.18 的
 *    zh_CN/zh_TW/ja/ko 翻译），启动链幂等安装到**两处**（槽位目录 +
 *    编译期默认路径）—— 无论 wine 按哪条路径解析都命中。已有同名文件
 *    （CI 新包自带）不覆盖。
 *
 * 2. **中文渲染（方块字）**：bionic rootfs 的 wine 前缀没有任何 CJK
 *    字体 —— wine 内置字体只有 Tahoma 等拉丁字面，中文全部渲染为
 *    方块/空白。修复：把 **Android 系统自带的 CJK 字体**
 *    （/system/fonts/NotoSansCJK/MiSans/…，全球可读）复制进前缀
 *    `drive_c/windows/Fonts/`，并写注册表：
 *    - HKLM\…\CurrentVersion\Fonts：注册为 SimSun/NSimSun/SimHei/
 *      Microsoft YaHei（+ ja/ko 容器的本地字体名）；
 *    - HKLM\…\FontSubstitutes：MS Shell Dlg → 中文字体（Windows
 *      中文版的官方替换约定）—— 未指定字体的老程序默认即得中文字形。
 *
 * 全部幂等（前缀内标记文件 + 目标存在检查），每次启动链快速通过。
 */
object WineLocaleSupport {
    private const val TAG = "WineLocaleSupport"

    /** assets 内的 wine.mo（语言目录名 → asset 文件名）。 */
    private val WINE_MO_ASSETS = mapOf(
        "zh_CN" to "anwind/locale/zh_CN.mo",
        "zh_TW" to "anwind/locale/zh_TW.mo",
        "ja" to "anwind/locale/ja.mo",
        "ja_JP" to "anwind/locale/ja.mo",
        "ko" to "anwind/locale/ko.mo",
        "ko_KR" to "anwind/locale/ko.mo"
    )

    /** Android 系统 CJK 字体候选（单字面文件优先 —— .ttc 首字面可能是 JP 变体）。 */
    private val SYS_FONT_CANDIDATES = listOf(
        "/system/fonts/NotoSansSC-Regular.otf",
        "/system/fonts/NotoSansSC-Regular.ttf",
        "/system/fonts/NotoSansCJKsc-Regular.otf",
        "/system/fonts/MiSans-Regular.ttf",
        "/system/fonts/MiSansSC-Regular.ttf",
        "/system/fonts/HarmonyOS_Sans_SC_Regular.ttf",
        "/system/fonts/NotoSansCJK-Regular.ttc",
        "/system/fonts/DroidSansFallback.ttf"
    )

    /** 前缀内“字体已装”标记。 */
    private fun fontMarker(prefix: File) = File(prefix, ".anwind-cjk-font")

    /**
     * 启动链入口：翻译安装（无需前缀）+ CJK 字体（需前缀就绪；未就绪时
     * 静默跳过 —— 前缀建成后的下一次启动自动补装）。
     */
    fun ensure(
        context: Context,
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        env: Map<String, String>,
        log: (String) -> Unit = {}
    ) {
        runCatching { installWineMo(context, resolved, log) }
            .onFailure { log("警告: wine 翻译安装失败（${it.message}）—— wine 界面可能保持英文") }
        runCatching { installCjkFont(resolved, container, env, log) }
            .onFailure { log("警告: CJK 字体安装失败（${it.message}）—— 中文可能显示为方块") }
    }

    // ------------------------------------------------------------------
    // 1) wine.mo 翻译安装
    // ------------------------------------------------------------------

    /**
     * 把 assets 里的 wine.mo 安装到 wine 目录的 share/locale —— 槽位目录
     * 与编译期默认路径（/usr/opt/wine）各一份；已存在（CI 新包自带）不覆盖。
     */
    private fun installWineMo(
        context: Context,
        resolved: WineRuntime.Resolved,
        log: (String) -> Unit
    ) {
        val targets = linkedSetOf(resolved.wineDir)
        // 编译期默认槽位路径（--prefix=$prefix/opt/wine）—— 槽位换名时
        // wine 仍按此路径找翻译；不存在则建目录放一份。
        val defaultSlot = File(ContainerManager.WINE_OPT_DIR, "wine")
        if (defaultSlot.isDirectory && defaultSlot.absolutePath != resolved.wineDir.absolutePath) {
            targets.add(defaultSlot)
        }

        val am = context.assets
        var installed = 0
        for ((langDir, asset) in WINE_MO_ASSETS) {
            val targetDirs = targets.map { File(it, "share/locale/$langDir/LC_MESSAGES") }
            if (targetDirs.all { File(it, "wine.mo").isFile }) continue
            val bytes = runCatching { am.open(asset).use { s -> s.readBytes() } }.getOrNull()
                ?: continue
            for (dir in targetDirs) {
                val f = File(dir, "wine.mo")
                if (f.isFile) continue
                runCatching {
                    dir.mkdirs()
                    FileOutputStream(f).use { it.write(bytes) }
                    installed++
                }.onFailure {
                    log("警告: wine.mo 写入 ${f.absolutePath} 失败（${it.message}）")
                }
            }
        }
        if (installed > 0) {
            log("已安装 wine 界面翻译 wine.mo ×$installed（zh_CN/zh_TW/ja/ko —— 配合容器的 LANG 环境变量，wine 界面即为对应语言）")
        }
    }

    // ------------------------------------------------------------------
    // 2) CJK 字体安装（前缀级）
    // ------------------------------------------------------------------

    /**
     * 复制 Android 系统 CJK 字体进前缀并写注册表。前缀未建成
     * （system.reg 不存在）时跳过 —— 由后续启动补装。
     */
    private fun installCjkFont(
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        env: Map<String, String>,
        log: (String) -> Unit
    ) {
        val prefix = File(container.prefixPath)
        if (!File(prefix, "system.reg").isFile) return   // 前缀未就绪，下次启动再装
        if (fontMarker(prefix).isFile) return

        // 仅中文/日文/韩文容器安装（英文容器无需 CJK 字体）
        val lang = container.lang.trim().lowercase()
        val needJp = lang.startsWith("ja")
        val needKr = lang.startsWith("ko")
        if (!(lang.startsWith("zh") || needJp || needKr)) return

        val src = SYS_FONT_CANDIDATES.firstOrNull { File(it).isFile }
        if (src == null) {
            log("警告: 设备 /system/fonts 未找到 CJK 字体（NotoSansCJK/MiSans/DroidSansFallback 均缺失）——中文将显示为方块")
            return
        }

        val fontsDir = File(prefix, "drive_c/windows/Fonts")
        fontsDir.mkdirs()
        val ext = File(src).extension.ifEmpty { "ttf" }
        val dstName = "anwind-cjk.$ext"
        val dst = File(fontsDir, dstName)
        runCatching {
            File(src).inputStream().use { input ->
                FileOutputStream(dst).use { output -> input.copyTo(output) }
            }
        }.onFailure {
            log("警告: CJK 字体复制失败（${it.message}）")
            return
        }
        log("已安装 CJK 字体: $src → drive_c/windows/Fonts/$dstName")

        // ---- 注册表：Fonts（按中/日/韩注册对应的家族名）----
        val regAdds = ArrayList<Array<String>>()
        fun font(name: String) = regAdds.add(
            arrayOf("reg", "add", "HKLM\\Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts",
                "/v", "$name (TrueType)", "/t", "REG_SZ", "/d", dstName, "/f")
        )
        font("SimSun"); font("NSimSun"); font("SimHei"); font("Microsoft YaHei")
        font("Microsoft YaHei UI"); font("Microsoft JhengHei")     // 繁中
        if (needJp) { font("MS Gothic"); font("MS PGothic"); font("MS UI Gothic") }
        if (needKr) { font("Malgun Gothic"); font("Gulim") }

        // ---- 注册表：FontSubstitutes（默认界面字体 → 中文字形）----
        fun subst(from: String, to: String) = regAdds.add(
            arrayOf("reg", "add", "HKLM\\Software\\Microsoft\\Windows NT\\CurrentVersion\\FontSubstitutes",
                "/v", from, "/t", "REG_SZ", "/d", to, "/f")
        )
        subst("MS Shell Dlg", "Microsoft YaHei")
        subst("MS Shell Dlg 2", "Microsoft YaHei")
        subst("SimSun-ExtB", "SimSun")

        val ok = runRegBatch(resolved, container, env, regAdds)
        if (ok) {
            runCatching { fontMarker(prefix).createNewFile() }
            log("CJK 字体注册完成（SimSun/微软雅黑 等已指向 $dstName；MS Shell Dlg 默认字体替换为微软雅黑）")
        } else {
            log("警告: CJK 字体注册表写入未全部成功（下次启动自动重试）")
            // 不写标记 → 下次启动重试
        }
    }

    /** 逐条执行 wine reg add（任一失败返回 false；单条限时 20 秒）。 */
    private fun runRegBatch(
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        env: Map<String, String>,
        cmds: List<Array<String>>
    ): Boolean {
        var allOk = true
        for (args in cmds) {
            try {
                val executor = NativeShellExecutor.create()
                    .setCommand(*WineRuntime.wineCommand(resolved).toTypedArray(), *args)
                    .putEnv(env)
                    .putEnv("WINEPREFIX", container.prefixPath)
                    .setWorkingDir(WineEnvironment.HOME_DIR)
                    .exec()
                val code = runCatching {
                    if (!executor.waitFor(20, TimeUnit.SECONDS)) {
                        runCatching { executor.stopAndWait(true, 5, TimeUnit.SECONDS) }
                        -1
                    } else executor.waitFor()
                }.getOrDefault(-1)
                if (code != 0) allOk = false
            } catch (_: Exception) {
                allOk = false
            }
        }
        return allOk
    }
}
