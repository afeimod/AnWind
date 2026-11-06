package com.anwind.apps.containers.launcher

import com.anwind.apps.containers.ContainerData
import com.anwind.apps.containers.ContainerManager
import com.anwind.apps.containers.WindowsVersions
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * v3.2.15（wineguanli.py 重制）：容器「函数库」设置的注册表落地。
 *
 * v3.2.27（用户强烈要求：去掉 APK 自带的 DllOverrides，完全按照
 * rootfs/opt/prefix/ 下的注册表来）：**不再写任何 DllOverrides 键** ——
 * dsound / dshow（DirectShow=quartz）的 DLL 加载序设置随内置表一起移除，
 * 前缀的 DLL 加载序完全由 /opt/prefix 安装包的 user.reg 决定；
 * 本类只保留与 DLL 加载序无关的两件套：
 *  - dpi → HKCU\Control Panel\Desktop 的 LogPixels（dword，十六进制 8 位）；
 *  - winver → HKLM\Software\Microsoft\Windows NT\CurrentVersion 的
 *    CurrentVersion / ProductName / CurrentBuild 三件套（wineguanli.py
 *    版本映射表逐条移植，见 [WindowsVersions]）。
 *
 * v3.3.8（用户问题 4：sound/quartz 的 DLL 配置恢复）：dsound / dshow
 * 三档语义 —— "default"（跟随安装包，不写注册表，v3.2.27 行为）与
 * 显式档（"builtin,native" / "native,builtin" → 写入
 * HKCU\Software\Wine\DllOverrides；winefuanli.py 同款）。默认档零改动，
 * 显式选择才落地。
 *
 * 实现方式：单次 `wine regedit /s` 导入；wineguanli.py 的直接文本改写
 * 依赖其特定前缀编码（UTF-8），而 wine 原生前缀的 .reg 为 UTF-16LE ——
 * regedit 导入对两种形态都正确，且无需关心编码。
 *
 * 幂等：.anwind-library 标记记录已应用的指纹，值变化才重新导入；
 * 失败不阻断启动链（下次自动重试）。
 */
object WineLibrary {

    private fun marker(prefix: File) = File(prefix, ".anwind-library")

    /** 指纹（值变化 → 重新导入；dsound/dshow 已不再写注册表，仅参与指纹）。 */
    private fun fingerprint(c: ContainerData): String =
        "${c.dsound}|${c.dshow}|${c.dpi}|${c.winver}"

    /**
     * 启动链集成点：前缀已构建且指纹变化时导入函数库设置。
     *
     * @return true = 已应用/无需应用；false = 导入失败（下次启动自动重试）
     */
    fun ensureApplied(
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        env: Map<String, String>,
        log: (String) -> Unit = {}
    ): Boolean {
        val prefix = File(container.prefixPath)
        if (!File(prefix, "system.reg").isFile) return true   // 前缀未建：留给 wineboot 后
        val fp = fingerprint(container)
        val mark = marker(prefix)
        if (mark.isFile) {
            val prev = runCatching { mark.readText().trim() }.getOrDefault("")
            if (prev == fp) return true
        }
        log("应用函数库设置（DPI=${container.dpi}/Windows=${WindowsVersions.fromId(container.winver).label}" +
            "；DLL: dsound=${container.dsound}/dshow=${container.dshow}）……")
        return try {
            val anwindDir = File(prefix, "drive_c/anwind")
            anwindDir.mkdirs()
            val regFile = File(anwindDir, "library.reg")
            regFile.writeText(buildReg(container))

            val cmd = NativeShellExecutor.create()
                .setCommand(*WineRuntime.wineCommand(resolved).toTypedArray(),
                            "regedit", "/s", "C:\\anwind\\library.reg")
                .putEnv(env)
                .putEnv("WINEPREFIX", container.prefixPath)
                .setWorkingDir(WineEnvironment.RUNTIME_DIR)
                .exec()
            val finished = runCatching { cmd.waitFor(120, TimeUnit.SECONDS) }.getOrDefault(false)
            val code = if (finished) runCatching { cmd.waitFor() }.getOrDefault(-1) else -1
            if (finished && code == 0) {
                runCatching { mark.writeText(fp) }
                log("函数库设置已写入注册表（DPI=${container.dpi} Windows=${WindowsVersions.fromId(container.winver).label}" +
                    "；DLL: dsound=${container.dsound}/dshow=${container.dshow}）")
                true
            } else {
                log("警告: 函数库设置导入未确认成功（退出码=$code；下次启动自动重试）")
                false
            }
        } catch (e: Exception) {
            log("警告: 函数库设置注入异常：${e.message}（下次启动自动重试）")
            false
        }
    }

    /**
     * 立即应用（UI「函数库」页按钮）：不等下次启动，当场导入。
     * 前缀未构建时返回错误提示（此时 wineboot 后启动链会自动补）。
     */
    fun applyNow(
        container: ContainerData,
        log: (String) -> Unit = {}
    ): String? {
        val prefix = File(container.prefixPath)
        if (!File(prefix, "system.reg").isFile) {
            return "容器前缀尚未构建（先运行 wineboot 或启动一次容器）"
        }
        val resolved = runCatching { WineRuntime.resolve(container) }.getOrNull()
            ?: return "wine 运行时解析失败（wine 未安装？）"
        val env = WineEnvironment.buildBaseEnv(container, resolved.wineDir).toMutableMap()
        env["WINEPREFIX"] = container.prefixPath
        return if (ensureApplied(resolved, container, env, log)) null
        else "函数库设置导入失败（详见日志；下次启动自动重试）"
    }

    /** 生成 regedit 导入文件（REGEDIT4 / ANSI-ASCII，wine regedit 原生支持）。
     *  v3.2.27：不再包含 DllOverrides 键（用户要求完全按 /opt/prefix 注册表）。
     *  v3.3.8：dsound/dshow 显式档（非 default）时写入 DllOverrides
     *  （winefuanli.py 的 sound/quartz 配置同款）；default 档不写。
     *  从显式改回 default 时写入空串（恢复 wine 内置默认加载序）。 */
    private fun buildReg(c: ContainerData): String = buildString {
        val ver = WindowsVersions.fromId(c.winver)
        appendLine("REGEDIT4")
        appendLine()
        appendLine("[HKEY_CURRENT_USER\\Control Panel\\Desktop]")
        appendLine("\"LogPixels\"=dword:${dpiHex(c.dpi)}")
        appendLine()
        appendLine("[HKEY_LOCAL_MACHINE\\Software\\Microsoft\\Windows NT\\CurrentVersion]")
        appendLine("\"CurrentVersion\"=\"${ver.currentVersion}\"")
        appendLine("\"ProductName\"=\"${ver.productName}\"")
        appendLine("\"CurrentBuild\"=\"${ver.currentBuild}\"")
        // v3.3.8（用户问题 4）：显式 DLL 加载序（dsound=DirectSound /
        // dshow=DirectShow 即 quartz）—— 任一键显式时整段写入；
        // 回到 default 档的键写空串清除旧覆盖。
        if (c.dsound != "default" || c.dshow != "default") {
            appendLine()
            appendLine("[HKEY_CURRENT_USER\\Software\\Wine\\DllOverrides]")
            appendLine("\"dsound\"=\"${if (c.dsound != "default") c.dsound else ""}\"")
            appendLine("\"quartz\"=\"${if (c.dshow != "default") c.dshow else ""}\"")
        }
    }

    /** DPI 十六进制 8 位（wineguanli.py：hex(dpi)[2:].zfill(8)，如 96 → 00000060）。 */
    internal fun dpiHex(dpi: String): String {
        val v = dpi.trim().toIntOrNull()?.coerceIn(60, 240) ?: 96
        return v.toString(16).padStart(8, '0')
    }
}
