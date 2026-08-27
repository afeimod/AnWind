package com.anwind

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.anwind.data.db.AppDatabase
import com.anwind.data.prefs.SettingsStore
import com.anwind.core.theme.ThemeManager

/**
 * 应用入口：初始化 Room DB、ThemeManager、SettingsStore 等单例。
 *
 * 内置应用在 [registerApps] 中注册到 AppRegistry，由各 App 文件实现。
 */
class AnWindApp : Application() {

    lateinit var database: AppDatabase
    lateinit var themeManager: ThemeManager
    lateinit var settingsStore: SettingsStore

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
