package com.spiritwisestudios.gpstracker.util

/**
 * Decides the one moment a drive counts as arrived.
 *
 * Fed the distance to the destination on every location fix, it answers true
 * exactly once per drive. Two things make that less obvious than a `< 50 m`
 * check:
 *
 * - a parked car keeps producing fixes inside the radius, so arrival has to
 *   be latched rather than re-derived;
 * - a Take a Tour loop drive *starts* at its own destination, so arrival only
 *   counts once the car has actually been away from it.
 *
 * The cost of the departure rule is that a destination closer than the radius
 * is never "arrived at" — silence, for a drive short enough that guidance had
 * nothing to say anyway.
 */
class ArrivalLatch(private val radiusMeters: Float = DEFAULT_RADIUS_METERS) {

    private var departed = false
    private var arrived = false

    /**
     * @return true on the single fix that turns the drive into an arrival.
     */
    fun onDistanceToDestination(meters: Float): Boolean {
        if (meters >= radiusMeters) {
            departed = true
            return false
        }
        if (!departed || arrived) return false

        arrived = true
        return true
    }

    /** Whether arrival has already been reported for this drive. */
    val hasArrived: Boolean
        get() = arrived

    /** A new drive begins: nowhere has been reached yet. */
    fun reset() {
        departed = false
        arrived = false
    }

    companion object {
        const val DEFAULT_RADIUS_METERS = 50f
    }
}
