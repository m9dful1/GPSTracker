package com.spiritwisestudios.gpstracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.spiritwisestudios.gpstracker.data.db.converters.LatLngConverter
import com.spiritwisestudios.gpstracker.data.db.dao.PointOfInterestDao
import com.spiritwisestudios.gpstracker.data.db.entity.PointOfInterestEntity

@Database(
    entities = [PointOfInterestEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(LatLngConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun pointOfInterestDao(): PointOfInterestDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gpstracker_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
} 