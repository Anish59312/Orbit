package com.example.orbit.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TripEntity::class, DataPointEntity::class], version = 1, exportSchema = false)
abstract class DriveLogDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao

    companion object {
        @Volatile
        private var INSTANCE: DriveLogDatabase? = null

        fun getDatabase(context: Context): DriveLogDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DriveLogDatabase::class.java,
                    "drive_log_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
