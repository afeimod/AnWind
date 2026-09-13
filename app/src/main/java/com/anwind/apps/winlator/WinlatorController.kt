package com.anwind.apps.winlator

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.anwind.AnWindApp
import com.anwind.apps.x11.X11ResolutionLink
import com.anwind.apps.x11.X11WindowController
import com.anwind.data.db.entity.ShortcutEntity
import com.anwind.data.model.DesktopItemType
import com.termux.x11.X11InputHub
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.session.WinlatorSession
import com.winlator.cmod.x11.X11SocketFinder
import com.winlator.cmod.xenvironment.ImageFs
import com.winlator.cmod.xenvironment.ImageFsInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Winlator 容器控制器（v2.23 集成）—— :app 模块侧的桥。
 *
 * - 把 [WinlatorSession]（引擎编排）的 State 包装成 Compose 可观察的
 *   [state]；会话状态变化经会话监听器回灌；
 * - 实现 [WinlatorSession.Host]：lorie X server 拉起（app_process 复刻
 *   anwind-x11）、分辨率握手（X11ResolutionLink）、X11 浮动窗口开窗
 *   （X11WindowController）、WinHandler 单例（X11InputHub）；
 * - 容器 CRUD 与 exe 桌面快捷方式（DesktopItemType.SHORTCUT_WINLATOR，type=4）。
 */
object WinlatorController {

    // ---------------- 会话状态（Compose 可观察） ----------------

    data class SessionUiState(
        val state: WinlatorSession.State = WinlatorSession.State.IDLE,
        val message: String = "",
        /** 引擎资产（imagefs）是否已就绪（决定"新建容器"是否可用）。 */
        val imageFsReady: Boolean = false,
        val installingPercent: Int = 0,
        val installingMessage: String = ""
    )

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 容器列表修订号（增删改后自增，驱动 UI 刷新）。 */
    var containersRevision by mutableStateOf(0)
        private set

    fun refreshContainers() { containersRevision++ }

    private var appContext: Context? = null
    private var containerManager: ContainerManager? = null

    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        containerManager = ContainerManager(ctx)

        WinlatorSession.get().setStateListener { st, msg ->
            _state.value = _state.value.copy(state = st, message = msg)
        }

        // 引擎资产就绪检查（后台）
        scope.launch(Dispatchers.IO) {
            val imageFs = ImageFs.find(ctx)
            val ready = imageFs.isValid() && imageFs.version >= ImageFsInstaller.LATEST_VERSION
            _state.value = _state.value.copy(imageFsReady = ready)
        }
    }

    // ---------------- 容器管理 ----------------

    fun containers(): List<Container> {
        val ctx = appContext ?: return emptyList()
        return try {
            containerManager?.containers ?: ContainerManager(ctx).containers
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun containerById(id: Int): Container? = containers().firstOrNull { it.id == id }

    /**
     * 新建容器（data 的键与 Winlator Container.loadData 兼容）。
     * 注意：createContainerAsync 内部 new Handler() 要求主线程调用
     * （回调经主 looper 投递），重活在其内部单线程执行器完成。
     */
    fun createContainer(data: JSONObject, onDone: (Container?) -> Unit = {}) {
        val ctx = appContext ?: return
        val manager = containerManager ?: ContainerManager(ctx)
        manager.createContainerAsync(data, null) { c ->
            refreshContainers()
            onDone(c)
        }
    }

    fun saveContainer(container: Container) {
        scope.launch(Dispatchers.IO) {
            container.saveData()
            refreshContainers()
        }
    }

    fun duplicateContainer(container: Container) {
        // createContainerAsync 系列内部 new Handler() 要求主线程调用
        containerManager?.duplicateContainerAsync(container) { refreshContainers() }
    }

    fun deleteContainer(container: Container) {
        containerManager?.removeContainerAsync(container) { refreshContainers() }
    }

    // ---------------- 引擎资产安装 ----------------

    /** 安装/重装引擎资产（首次运行或升级后）。 */
    fun installImageFs(context: Context) {
        val ctx = context.applicationContext
        scope.launch(Dispatchers.IO) {
            WinlatorSession.get().ensureImageFs(
                ctx,
                sessionHost(ctx),
                { percent, msg ->
                    _state.value = _state.value.copy(
                        installingPercent = percent,
                        installingMessage = msg ?: ""
                    )
                }
            ) { ok ->
                _state.value = _state.value.copy(
                    imageFsReady = ok,
                    installingPercent = 0,
                    installingMessage = if (ok) "" else "引擎资产安装失败"
                )
            }
        }
    }

    // ---------------- 运行 / 停止 ----------------

    /** 运行容器内的 exe（游戏画面显示在 AnWind 的 X11 浮动窗口）。 */
    fun runExe(context: Context, container: Container, exePath: String) {
        val ctx = context.applicationContext
        WinlatorSession.get().runExe(ctx, sessionHost(ctx), container, exePath, null)
    }

    fun stopSession() = WinlatorSession.get().stop()

    // ---------------- exe 桌面快捷方式 ----------------

    /**
     * 生成 AnWind 桌面快捷方式（DesktopItemType.SHORTCUT_WINLATOR，type=4）：
     * target = 容器 id，launchArgs = {"exe": "<路径>"}。
     */
    fun createDesktopShortcut(context: Context, container: Container, exePath: String, label: String) {
        val ctx = context.applicationContext
        scope.launch(Dispatchers.IO) {
            val dao = (ctx as? AnWindApp)?.database?.shortcutDao() ?: return@launch
            dao.insert(
                ShortcutEntity(
                    label = label.ifEmpty { exePath.substringAfterLast('/') }.substringBeforeLast('.'),
                    iconAsset = "emoji:🎮",
                    type = 4, // SHORTCUT_WINLATOR
                    target = container.id.toString(),
                    launchArgs = "{\"exe\":\"${exePath.replace("\\", "\\\\").replace("\"", "\\\"")}\"}"
                )
            )
        }
    }

    // ---------------- 会话宿主实现 ----------------

    private fun sessionHost(context: Context): WinlatorSession.Host {
        val ctx = context.applicationContext
        // 会话在后台线程编排 —— 涉及桌面 UI/Compose 状态的回调统一切主线程
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        return object : WinlatorSession.Host {
            override fun ensureX11Session(): Boolean = X11SessionStarter.ensureRunning(ctx)

            override fun applyContainerResolution(screenSize: String) {
                // 容器分辨率 → AnWind 分辨率握手（X 屏幕切换 + 显示层拉伸铺满）
                mainHandler.post {
                    try {
                        if (screenSize.isNotBlank() && screenSize != "native") {
                            X11ResolutionLink.apply(screenSize)
                        }
                        // "native" 交由 X11FitClient 自适应贴合，不强制握手
                    } catch (_: Exception) {}
                }
            }

            override fun openDisplayWindow() {
                mainHandler.post { X11WindowController.openWindow(ctx) }
            }

            override fun getWinHandler(): com.winlator.cmod.winhandler.WinHandler {
                return X11InputHub.get(ctx).getWinHandler()
            }

            override fun onSessionMessage(message: String) {
                scope.launch {
                    try {
                        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                }
            }
        }
    }
}

/**
 * AnWind X server（lorie）app 侧启动器。
 *
 * 与终端脚本 `bin/termux-x11`（ANWIND_X11_CLIENT）完全同源：
 * app_process + 宿主 APK CLASSPATH + CmdEntryPoint，广播
 * ACTION_START → X11WindowController 自动弹出 X11 浮动窗口。
 */
object X11SessionStarter {

    /** socket 已存在 → true；否则拉起 CmdEntryPoint 并等待 socket（最长 8s）。 */
    fun ensureRunning(context: Context): Boolean {
        if (X11SocketFinder.findSocket(context) != null) return true

        val prefix = X11SocketFinder.getPrefixPath(context)
        val apkPath = context.applicationInfo.sourceDir ?: return false
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: ""

        // XKB 键盘数据（X server 初始化必需；随 bootstrap 部署）
        val xkbDir = File("$prefix/etc/anwind/x11/xkb")
        val xkbShare = File("$prefix/share/X11/xkb")
        val xkbRoot = when {
            xkbDir.resolve("rules").isDirectory -> xkbDir.path
            xkbShare.resolve("rules").isDirectory -> xkbShare.path
            else -> return false // 未部署：请先打开一次 AnWind 主界面
        }

        return try {
            val pb = ProcessBuilder(
                "/system/bin/app_process", "-Xnoimage-dex2oat", "/",
                "--nice-name=anwindx11 :1",
                "com.termux.x11.CmdEntryPoint", ":1"
            )
            val env = pb.environment()
            env["CLASSPATH"] = apkPath
            env["TERMUX_X11_OVERRIDE_PACKAGE"] = context.packageName
            env["ANWIND_X11_NATIVE_DIR"] = nativeDir
            env["XKB_CONFIG_ROOT"] = xkbRoot
            env["XDG_RUNTIME_DIR"] = "$prefix/tmp"
            env.remove("LD_LIBRARY_PATH")
            env.remove("LD_PRELOAD")
            pb.start() // 分离进程：X server 随后自行广播

            // 等待 X socket 就位（app_process 冷启动较慢，与脚本一致最多 8s）
            repeat(16) {
                Thread.sleep(500)
                if (X11SocketFinder.findSocket(context) != null) return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }
}
