package com.spiritwisestudios.gpstracker.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard behind [AppMigrations]: bumping the schema version without writing
 * the migration to go with it fails here, rather than deleting somebody's tour
 * journal on their next update.
 */
class AppMigrationsTest {

    @Test
    fun `migrations reach the current schema version`() {
        val chainEnd = AppMigrations.ALL
            .maxOfOrNull { it.endVersion }
            ?: AppMigrations.FIRST_MIGRATABLE_VERSION

        assertEquals(
            "AppDatabase.VERSION is ${AppDatabase.VERSION} but the migrations stop at " +
                "$chainEnd — add the missing Migration to AppMigrations.ALL",
            AppDatabase.VERSION,
            chainEnd
        )
    }

    @Test
    fun `the chain has no gaps`() {
        val steps = AppMigrations.ALL.sortedBy { it.startVersion }
        var expected = AppMigrations.FIRST_MIGRATABLE_VERSION

        for (step in steps) {
            assertEquals(
                "migration ${step.startVersion} to ${step.endVersion} doesn't follow on",
                expected,
                step.startVersion
            )
            expected = step.endVersion
        }
    }

    @Test
    fun `every migration moves forward`() {
        for (step in AppMigrations.ALL) {
            assertTrue(
                "migration ${step.startVersion} to ${step.endVersion} doesn't move forward",
                step.endVersion > step.startVersion
            )
        }
    }

    @Test
    fun `nothing older than the first migratable version is claimed`() {
        // Version 1 has no exported schema, so no migration can start there —
        // AppDatabase rebuilds that one instead.
        for (step in AppMigrations.ALL) {
            assertTrue(
                "migration from ${step.startVersion} predates the exported schemas",
                step.startVersion >= AppMigrations.FIRST_MIGRATABLE_VERSION
            )
        }
    }
}
