package com.spiritwisestudios.gpstracker.util

/**
 * Pure navigation-camera decisions, extracted for unit testing.
 */
object CameraLogic {

    /** Close-up when stopped or walking. */
    const val MAX_ZOOM = 18f

    /** Widest view at highway speed (100 km/h and beyond). */
    const val MIN_ZOOM = 15f

    /**
     * Navigation zoom glides with speed: closer when slow (the next turn
     * matters), wider when fast (the road far ahead matters). Continuous
     * rather than stepped so GPS speed jitter can't bounce the camera
     * between zoom bands.
     */
    fun zoomForSpeed(speedMetersPerSecond: Float): Float {
        val speedKmh = speedMetersPerSecond.coerceAtLeast(0f) * 3.6f
        val fractionOfTopSpeed = (speedKmh / 100f).coerceIn(0f, 1f)
        return MAX_ZOOM - (MAX_ZOOM - MIN_ZOOM) * fractionOfTopSpeed
    }
}
