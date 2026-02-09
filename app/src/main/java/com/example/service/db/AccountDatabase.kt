package com.example.service.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [AccountEntity::class], version = 2, exportSchema = false)
abstract class AccountDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao

    companion object {
        @Volatile
        private var INSTANCE: AccountDatabase? = null

        fun getDatabase(context: Context): AccountDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AccountDatabase::class.java,
                    "account_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
