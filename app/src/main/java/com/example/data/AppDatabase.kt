package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SavedAccountEntity::class,
        MetaAccountEntity::class,
        OtpHistoryEntity::class,
        AutomationStateEntity::class,
        ExecutionLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun savedAccountDao(): SavedAccountDao
    abstract fun metaAccountDao(): MetaAccountDao
    abstract fun otpHistoryDao(): OtpHistoryDao
    abstract fun automationStateDao(): AutomationStateDao
    abstract fun executionLogDao(): ExecutionLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "trustpoint_automation.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
