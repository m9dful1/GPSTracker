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
    version = AppDatabase.VERSION,
    exportSchema = true
)
@TypeConverters(LatLngConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun pointOfInterestDao(): PointOfInterestDao

    abstract fun tourContentDao(): TourContentDao

    companion object {
        /**
         * Bumping this requires a matching migration in [AppMigrations] — the
         * journal is the user's own history, and `AppMigrationsTest` fails the
         * build rather than let a schema change quietly discard it.
         */
        const val VERSION = 2

        const val NAME = "gpstracker_database"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    NAME
                )
                    .addMigrations(*AppMigrations.ALL)
                    // Version 1 predates schema export, so there is no record
                    // of its shape to migrate from — it is the only version
                    // allowed to be rebuilt. Every later one must migrate, and
                    // a missing migration now fails loudly instead of silently
                    // deleting the journal.
                    //
                    // dropAllTables = true is the whole point of the newer
                    // overload, and the right answer here for the same reason
                    // version 1 is rebuilt at all: with no exported schema
                    // there is no record of what that version contained, so a
                    // rebuild that dropped only the tables Room knows about
                    // today could leave something from then behind. Rebuilt
                    // means rebuilt.
                    .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
                    // One file, no write-ahead log: this database is backed up
                    // and restored (see res/xml/data_extraction_rules.xml), and
                    // a .db copied without its -wal loses whatever the log
                    // still held. The app writes a handful of rows per drive,
                    // so WAL's concurrency buys it nothing.
                    .setJournalMode(JournalMode.TRUNCATE)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
