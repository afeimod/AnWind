package com.anwind

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.anwind.data.db.AppDatabase
import com.anwind.data.prefs.SettingsStore
import com.anwind.core.theme.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    }

    companion object {
        lateinit var instance: AnWindApp
            private set

        fun get(): AnWindApp = instance

        fun get(context: Context): AnWindApp =
            context.applicationContext as AnWindApp
    }
}
