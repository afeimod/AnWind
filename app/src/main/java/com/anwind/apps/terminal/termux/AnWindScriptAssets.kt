package com.anwind.apps.terminal.termux

import android.content.Context
import android.system.Os
import java.io.File

/**
 * AnWind 容器脚本资产部署（v2.26 · assets → rootfs 覆盖安装）。
 *
 * ------------------------------------------------------------------
 * 背景（v2.26 隔离版改造）：
 *   anwind-container / anwind-wine 两个容器管理脚本原先由
 *   bionic-rootfs 构建系统（projects/anwind-container）打进 rootfs
 *   发行包 —— 换 rootfs 才能更新脚本，且 rootfs 构建与 App 逻辑耦合。
 *
 *   现改为：脚本直接放 APK 内 assets/anwind/scripts/，App 每次启动
 *   与每次容器会话启动时【覆盖安装】到 rootfs/usr/bin/ —— rootfs
 *   解压完即被覆盖为 APK 内置的最新版本，旧 rootfs 自带的旧脚本
 *   同样被替换，用户无需重导 rootfs。
 *
 * 资产契约（APK 内 assets/anwind/scripts/ 目录，纯文本脚本）：
 *   anwind-container   容器管理 CLI（create/list/set/install-wine/
 *                      wine-list/wine-default/wine-remove/dxvk/vkd3d/doctor）
 *   anwind-wine        容器 Wine 启动器 v2.0（环境隔离版：
 *                      PREFIX/HOME/TMPDIR/LD_LIBRARY_PATH/LANG/音频
 *                      全部指向 rootfs 自身；DISPLAY 立即 :1；
 *                      dmode=off/d/v/f 显示模式）
 *
 * 覆盖安装目标（与 ContainerManager.ROOTFS_ROOT 一致）：
 *   /data/data/com.anwind/files/rootfs/usr/bin/<脚本名>（chmod 0755）
 *
 * 附带兜底（rootfs 未含 anwind-container 项目时补齐）：
 *   usr/etc/anwind/container.conf        全局配置（default_container/
 *                                        default_wine 指针，缺失才创建）
 *   usr/etc/profile.d/anwind-container.sh rootfs bin 并入 PATH
 *   home/.anwind/containers/logs         容器与日志目录
 *
 * 幂等策略：脚本每次启动无条件覆盖（APK 内版本即最新版本，体量仅
 * 数十 KB）；配置文件与目录仅在缺失时创建，绝不覆盖用户数据。
 */
object AnWindScriptAssets {

    private const val TAG = "AnWindScriptAssets"

    /** APK 内脚本资产目录。 */
    const val SCRIPT_ASSET_DIR = "anwind/scripts"

    /** bionic rootfs 根（与 ContainerManager.ROOTFS_ROOT 逐字一致）。 */
    const val ROOTFS_ROOT = "/data/data/com.anwind/files/rootfs"

    /** 需要部署到 rootfs/usr/bin 的脚本清单。 */
    private val SCRIPTS = listOf("anwind-container", "anwind-wine")

    /**
     * 部署容器脚本到 rootfs（覆盖安装）。
     *
     * 调用时机：
     *  1. App 启动（AnWindTzstAssets.installAllIfNeeded 尾部，rootfs
     *     tzst 资产解压完成后 —— 满足"解压完 rootfs 后覆盖过去"）；
     *  2. 每次容器会话启动前（WineSessionLauncher.spawn，保证脚本
     *     与 APK 版本始终一致）。
     *
     * rootfs 未导入时静默跳过（返回 0），bootstrap 安装后自动生效。
     *
     * @return 实际写入的脚本数（0 = rootfs 未就绪或无资产）
     */
    fun deployScriptsIfNeeded(context: Context): Int {
        val binDir = File("$ROOTFS_ROOT/usr/bin")
        // rootfs 未导入（usr/bin 不存在）→ 跳过；rootfs 解压完成后本处即生效
        if (!binDir.isDirectory) return 0

        var written = 0
        val names = try {
            context.assets.list(SCRIPT_ASSET_DIR) ?: emptyArray()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "扫描 $SCRIPT_ASSET_DIR/ 失败: ${e.message}")
            return 0
        }

        for (name in names) {
            // 只部署白名单脚本，避免 assets 里出现意外文件被误装
            if (name !in SCRIPTS) continue
            if (copyScript(context, "$SCRIPT_ASSET_DIR/$name", File(binDir, name))) written++
        }

        ensureSupportFiles()
        if (written > 0) {
            android.util.Log.i(TAG, "容器脚本已覆盖安装: $written 个 → $binDir")
        }
        return written
    }

    /** 从 assets 复制单个脚本并 chmod 0755。 */
    private fun copyScript(context: Context, assetPath: String, target: File): Boolean {
        return try {
            context.assets.open(assetPath).use { input ->
                val tmp = File(target.absolutePath + ".tmp")
                tmp.outputStream().use { output -> input.copyTo(output) }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    // rename 失败（跨文件系统等）退回直接写入
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
            Os.chmod(target.absolutePath, 493)   // 0755
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "脚本部署失败: $assetPath → ${target.path}: ${e.message}")
            false
        }
    }

    /**
     * 兜底补齐脚本运行所需的配置与目录（只在缺失时创建，
     * 不覆盖用户已有配置 —— default_container/default_wine 指针保留）。
     */
    private fun ensureSupportFiles() {
        try {
            // 全局容器配置（容器 CLI 与 App 端 ContainerManager 共用）
            val conf = File("$ROOTFS_ROOT/usr/etc/anwind/container.conf")
            if (!conf.isFile) {
                conf.parentFile?.mkdirs()
                conf.writeText(
                    "# AnWind Wine 容器全局配置（anwind-container / App 端共用）\n" +
                        "# default_container=<容器名>\n" +
                        "# default_wine=<wine 槽位名（/usr/opt/<名>）>\n"
                )
                Os.chmod(conf.absolutePath, 420)   // 0644
            }

            // profile.d：rootfs bin 并入 PATH（终端直接敲 anwind-container）
            val prof = File("$ROOTFS_ROOT/usr/etc/profile.d/anwind-container.sh")
            if (!prof.isFile) {
                prof.parentFile?.mkdirs()
                prof.writeText(
                    "# AnWind Wine 容器管理（由 APK assets 自动部署）\n" +
                        "case \":${'$'}PATH:\" in\n" +
                        "  *\":$ROOTFS_ROOT/usr/bin:\"*) ;;\n" +
                        "  *) export PATH=\"$ROOTFS_ROOT/usr/bin:${'$'}{PATH}\" ;;\n" +
                        "esac\n"
                )
                Os.chmod(prof.absolutePath, 420)
            }

            // 容器存储与日志目录（rootfs home 下）
            File("$ROOTFS_ROOT/home/.anwind/containers/logs").mkdirs()
            File("$ROOTFS_ROOT/usr/tmp").mkdirs()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "容器支撑文件补齐失败: ${e.message}")
        }
    }
}
