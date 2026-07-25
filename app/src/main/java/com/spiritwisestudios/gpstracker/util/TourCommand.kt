package com.spiritwisestudios.gpstracker.util

/**
 * What a start command is asking the tour service to do.
 *
 * Resolving this before entering the foreground matters: a start command can
 * arrive at a service instance that exists *only because that intent created
 * it*, and a notification for a tour that isn't running is worse than no
 * notification at all.
 */
enum class TourCommand {
    /**
     * Pick the tour back up. `START_STICKY` hands a killed service back with
     * a null intent; the tour's settings are persisted, so the guide can
     * carry on from there.
     */
    RESUME,

    /** Begin a tour the user asked for. */
    START,

    /** End the tour and the service with it. */
    STOP,

    /** A geofence fired — revive the tour if needed, then narrate. */
    GEOFENCE,

    /** Pause or resume the narration in progress. */
    PLAY_PAUSE,

    /** Skip to the next queued narration. */
    NEXT,

    /**
     * Nothing to do, and no reason for the service to exist: a control for a
     * tour that has already ended. The service should stop rather than enter
     * the foreground.
     */
    NONE;

    companion object {
        /**
         * @param action the start intent's action, null when the system
         *   revived the service without one.
         * @param isTourRunning whether a tour is under way in this instance.
         */
        fun forAction(action: String?, isTourRunning: Boolean): TourCommand = when (action) {
            null -> RESUME
            AppConstants.ACTION_START_TOUR_MODE -> START
            AppConstants.ACTION_PROCESS_GEOFENCE -> GEOFENCE

            // These three only mean something to a tour in progress. They
            // reach a dead service through the notification's buttons and the
            // fact card's, both of which outlive the tour by a moment.
            AppConstants.ACTION_STOP_TOUR_MODE -> if (isTourRunning) STOP else NONE
            AppConstants.ACTION_PLAY_PAUSE -> if (isTourRunning) PLAY_PAUSE else NONE
            AppConstants.ACTION_NEXT_POI -> if (isTourRunning) NEXT else NONE

            else -> NONE
        }
    }
}
