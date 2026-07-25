package com.spiritwisestudios.gpstracker.data.db

import androidx.room.migration.Migration

/**
 * Every schema migration, in order.
 *
 * The database holds the Tour Journal — the places the guide has narrated,
 * when, and whatever the user wrote about them. That is their own history, not
 * a cache, so a schema change has to carry it forward rather than start again.
 *
 * **To change the schema:**
 * 1. Edit the entities and bump [AppDatabase.VERSION].
 * 2. Add a `Migration(previous, new)` here and list it in [ALL].
 * 3. Commit the JSON schema the build writes to `app/schemas` — it is the
 *    record of what the old shape actually was, and the only way a migration
 *    can be checked afterwards.
 *
 * `AppMigrationsTest` fails if the chain doesn't reach [AppDatabase.VERSION],
 * so a forgotten migration is a red build rather than a wiped journal.
 *
 * Version 1 is the exception: it predates schema export, so there is no record
 * of its shape to migrate from. [AppDatabase] lets that one — and only that
 * one — be rebuilt from scratch.
 */
object AppMigrations {

    /**
     * The first migratable version. Anything older has no exported schema and
     * cannot be carried forward.
     */
    const val FIRST_MIGRATABLE_VERSION = 2

    val ALL: Array<Migration> = arrayOf(
        // Nothing yet: version 2 is current. The next schema change adds its
        // Migration(2, 3) here.
    )
}
