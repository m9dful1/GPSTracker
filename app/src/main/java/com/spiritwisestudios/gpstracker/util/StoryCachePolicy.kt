package com.spiritwisestudios.gpstracker.util

/**
 * How long the guide keeps the stories it has fetched.
 *
 * The cache exists so a place the user drives past twice doesn't cost two
 * requests, and so a prefetched story is ready before the geofence fires.
 * Neither purpose needs it kept forever: text from months ago may no longer
 * match what the source says, and a cache nothing ever removes from is a leak
 * with a lookup table attached.
 *
 * This is only about the story cache. The Tour Journal — the places narrated,
 * when, and the user's own notes — is history rather than cache, and nothing
 * here touches it.
 */
object StoryCachePolicy {

    /** Stories cached longer ago than this are dropped. */
    const val MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000

    /** However recent they are, keep no more than this many. */
    const val MAX_ENTRIES = 500

    /** Cached before this instant means too old to keep. */
    fun cutoffMillis(nowMillis: Long): Long = nowMillis - MAX_AGE_MS

    /** Whether the cache has outgrown [MAX_ENTRIES] and needs trimming. */
    fun shouldTrim(entryCount: Int): Boolean = entryCount > MAX_ENTRIES
}
