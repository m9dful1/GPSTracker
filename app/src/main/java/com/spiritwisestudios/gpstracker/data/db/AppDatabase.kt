package com.spiritwisestudios.gpstracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.spiritwisestudios.gpstracker.data.db.converters.LatLngConverter
import com.spiritwisestudios.gpstracker.data.db.dao.PointOfInterestDao
import com.spiritwisestudios.gpstracker.data.db.dao.TourContentDao
import com.spiritwisestudios.gpstracker.data.db.entity.PointOfInterestEntity
import com.spiritwisestudios.gpstracker.data.db.entity.TourContentEntity

@Database(
    entities = [PointOfInterestEntity::class, TourContentEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(LatLngConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun pointOfInterestDao(): PointOfInterestDao

    abstract fun tourContentDao(): TourContentDao

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
