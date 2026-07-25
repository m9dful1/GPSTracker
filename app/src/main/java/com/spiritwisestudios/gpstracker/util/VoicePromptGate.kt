package com.spiritwisestudios.gpstracker.util

/**
 * Decides which navigation prompts are actually worth saying out loud.
 *
 * Status updates arrive every few seconds while driving, and each one carries
 * the next maneuver, so the same turn would be announced over and over. Two
 * rules keep the guide sounding human:
 *
 * - the same maneuver announced the same way is spoken once;
 * - arrival is spoken once per drive, however many times it is reported.
 *
 * Arrival needs its own rule because a parked car keeps producing fixes
 * inside the arrival radius, and each one is a slightly different position —
 * which used to make a new key and another "you have arrived".
 */
class VoicePromptGate {

    private var lastKey: String? = null
    private var arrivalSpoken = false

    /**
     * @param key identifies the maneuver and the way it is being announced,
     *   so the same turn can still be announced twice at different distances
     *   ("in half a mile, turn left" then "turn left now").
     * @param isArrival whether this prompt ends the drive.
     * @return whether to speak it.
     */
    fun shouldSpeak(key: String, isArrival: Boolean = false): Boolean {
        if (isArrival && arrivalSpoken) return false
        if (key == lastKey) return false

        lastKey = key
        if (isArrival) arrivalSpoken = true
        return true
    }

    /** A new drive begins: everything is worth saying again. */
    fun reset() {
        lastKey = null
        arrivalSpoken = false
    }
}
