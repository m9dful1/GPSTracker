package com.spiritwisestudios.gpstracker.util

/**
 * Pure policy for how often the tour service should ask for location
 * fixes: faster the faster the user moves (so narration triggers before
 * a POI is already behind them), floored by battery level so a draining
 * phone backs off. Kept free of Android classes for JVM unit testing.
 */
object LocationCadence {

    /** Not going anywhere — a relaxed cadence still notices a walk starting. */
    const val INTERVAL_STATIONARY_MS = 10_000L

    /** Walking pace: ~7 m of travel between fixes. */
    const val INTERVAL_WALKING_MS = 5_000L

    /** City driving: ~20 m between fixes. */
    const val INTERVAL_DRIVING_MS = 2_500L

    /** Fast driving: keeps distance-per-fix in the tens of meters. */
    const val INTERVAL_FAST_MS = 1_250L

    /** Speed thresholds in m/s. */
    const val SPEED_STATIONARY_MPS = 0.5f
    const val SPEED_WALKING_MPS = 2.0f
    const val SPEED_DRIVING_MPS = 8.0f

    /** Battery thresholds in percent. */
    const val BATTERY_LOW_PERCENT = 15
    const val BATTERY_MEDIUM_PERCENT = 50

    /** Slowest cadence, used as the low-battery floor. */
    const val FLOOR_LOW_BATTERY_MS = 30_000L

    /** Medium-battery floor. */
    const val FLOOR_MEDIUM_BATTERY_MS = 10_000L

    /** Cadence the current speed calls for, ignoring battery. */
    fun speedIntervalMs(speedMetersPerSecond: Float): Long {
        return when {
            speedMetersPerSecond < SPEED_STATIONARY_MPS -> INTERVAL_STATIONARY_MS
            speedMetersPerSecond < SPEED_WALKING_MPS -> INTERVAL_WALKING_MS
            speedMetersPerSecond < SPEED_DRIVING_MPS -> INTERVAL_DRIVING_MS
            else -> INTERVAL_FAST_MS
        }
    }

    /** Slowest interval the battery level will tolerate (0 = no floor). */
    fun batteryFloorMs(batteryPercent: Int): Long {
        return when {
            batteryPercent <= BATTERY_LOW_PERCENT -> FLOOR_LOW_BATTERY_MS
            batteryPercent <= BATTERY_MEDIUM_PERCENT -> FLOOR_MEDIUM_BATTERY_MS
            else -> 0L
        }
    }

    /** The interval to actually request: speed's ask, floored by battery. */
    fun intervalMs(speedMetersPerSecond: Float, batteryPercent: Int): Long {
        return maxOf(speedIntervalMs(speedMetersPerSecond), batteryFloorMs(batteryPercent))
    }
}
