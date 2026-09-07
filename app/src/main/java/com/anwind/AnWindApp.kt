package com.anwind

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.room.Room
import com.anwind.data.db.AppDatabase
import com.anwind.data.prefs.SettingsStore
import com.anwind.core.theme.ThemeManager
import com.anwind.apps.x11.X11Desktop
import com.termux.x11.CmdEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口：初始化 Room DB、ThemeManager、SettingsStore 等单例。
 *
 * 内置应用在 [registerApps] 中注册到 AppRegistry，由各 App 文件实现。
 */
class AnWindApp : Application() {

    lateinit var database: AppDatabase
    lateinit var themeManager: ThemeManager
    lateinit var settingsStore: SettingsStore

    /**
     * v2.17 应用级协程作用域：生命周期与应用进程一致，不随任何窗口/组合销毁。
     *
     * 修复“选择图片自定义桌面壁纸不生效”：旧版在窗口内用
     * rememberCoroutineScope 启动 DataStore 写入后立即关窗，窗口组合销毁时
     * 协程被取消，写入随机丢失。涉及持久化的操作（设壁纸/锁屏壁纸等）
     * 一律改用本作用域。
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * v2.22.2 内置 X11 桌面：终端侧 `anwind-x11` 客户端（app_process 进程）
     * 每秒广播一次 ACTION_START（附带 X 连接 fd 的 binder）直到被取用。
     * 此处去抖后拉起全屏 X11 桌面 Activity；binder 由桌面 Activity 自己的
     * 动态接收器从重播中取出并完成连接（见 X11Desktop 注释）。
     */
    private val x11LaunchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == CmdEntryPoint.ACTION_START) {
                X11Desktop.openFromBroadcast(context)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = Room.databaseBuilder(this, AppDatabase::class.java, AppDatabase.NAME)
            .fallbackToDestructiveMigration()
            .build()

        themeManager = ThemeManager(this)
        settingsStore = SettingsStore(this)

        // 注册所有内置应用
        com.anwind.apps.AppBootstrap.registerAll()

        // 内置 X11 桌面：监听终端侧 X server 的连接广播
        registerReceiver(
            x11LaunchReceiver,
            IntentFilter(CmdEntryPoint.ACTION_START)
        )

        // v2.22.2 X11 客户端宿主定位文件自愈：APK 升级后安装路径变化，
        // 每次启动在后台线程刷新 etc/anwind-x11.env（未装 bootstrap 时静默）
        applicationScope.launch(Dispatchers.IO) {
            com.anwind.apps.terminal.termux.TermuxBootstrapInstaller
                .refreshX11Env(this@AnWindApp)
        }
    }

    companion object {
        lateinit var instance: AnWindApp
            private set

        fun get(): AnWindApp = instance

        fun get(context: Context): AnWindApp =
            context.applicationContext as AnWindApp
    }
}
