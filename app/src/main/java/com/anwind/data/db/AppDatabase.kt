package com.anwind.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.anwind.data.db.dao.BookmarkDao
import com.anwind.data.db.dao.HistoryDao
import com.anwind.data.db.dao.ShortcutDao
import com.anwind.data.db.entity.BookmarkEntity
import com.anwind.data.db.entity.HistoryEntity
import com.anwind.data.db.entity.ShortcutEntity

@Database(
    entities = [
        ShortcutEntity::class,
        BookmarkEntity::class,
        HistoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shortcutDao(): ShortcutDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao

    companion object {
        const val NAME = "anwind.db"
    }
}
