package com.spiritwisestudios.gpstracker.util

/**
 * Pure navigation-camera decisions, extracted for unit testing.
 */
object CameraLogic {

    /** Close-up when stopped or walking. */
    const val MAX_ZOOM = 18f

    /** Widest view at highway speed (100 km/h and beyond). */
    const val MIN_ZOOM = 15f

    /** Driving-view tilt, steep enough to look down the road ahead. */
    const val DRIVING_TILT = 60.0

    /** Heading-up driving view engages here — faster than a brisk walk. */
    const val DRIVING_SPEED_MPS = 3f

    /** Slow fixes tolerated before the driving view lets go (~20 s at 1 fix/s). */
    const val STILL_FIXES_TO_RELEASE = 20

    /** How long the driving camera's ease runs between fixes. */
    const val CAMERA_EASE_MS = 1000

    /** Never lead the camera further than this, however fast the GPS claims. */
    const val MAX_CAMERA_LEAD_METERS = 40f

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

    /**
     * How far ahead of the last fix to aim the driving camera. A fix shows
     * where the user *was*, and by the time the ease finishes they have
     * moved another second down the road — so the camera trails reality by
     * roughly one ease. Leading the target by one ease-worth of travel
     * makes the view land where the user actually is.
     */
    fun cameraLeadMeters(speedMetersPerSecond: Float): Float {
        return (speedMetersPerSecond.coerceAtLeast(0f) * CAMERA_EASE_MS / 1000f)
            .coerceAtMost(MAX_CAMERA_LEAD_METERS)
    }

    /**
     * Decides when the heading-up driving view is engaged outside of
     * turn-by-turn guidance. It engages the moment the user moves at
     * driving speed, but releases only after a sustained stop, so a short
     * red light doesn't flatten the view at every block. Walking never
     * engages it.
     */
    class DrivingCameraGate {

        var isDriving = false
            private set

        private var slowFixes = 0

        /** Feed every location fix; returns whether the driving view is engaged. */
        fun onSpeed(speedMetersPerSecond: Float): Boolean {
            if (speedMetersPerSecond >= DRIVING_SPEED_MPS) {
                isDriving = true
                slowFixes = 0
            } else if (isDriving && ++slowFixes >= STILL_FIXES_TO_RELEASE) {
                reset()
            }
            return isDriving
        }

        fun reset() {
            isDriving = false
            slowFixes = 0
        }
    }
}
