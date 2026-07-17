package com.spiritwisestudios.gpstracker.domain.model

/**
 * The user's account level, which decides what pays for the tour guide.
 * STANDARD rides free with ads and Wikipedia-parsed narration; PREMIUM
 * removes the ads and has Gemini write the narration scripts (when a key
 * is configured). Defaults to STANDARD; release builds flip it when an
 * upgrade purchase lands, debug builds via the settings toggle.
 */
enum class AccountTier {
    STANDARD,
    PREMIUM;

    companion object {
        /** Stored names from any app version fall back to STANDARD. */
        fun fromStorage(name: String?): AccountTier =
            entries.firstOrNull { it.name == name } ?: STANDARD
    }
}
