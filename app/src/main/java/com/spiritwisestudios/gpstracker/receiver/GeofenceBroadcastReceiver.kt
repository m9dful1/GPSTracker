package com.spiritwisestudios.gpstracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.spiritwisestudios.gpstracker.service.TourModeService
import com.spiritwisestudios.gpstracker.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * BroadcastReceiver to handle geofence transition events.
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    override fun onReceive(context: Context, intent: Intent) {
        Timber.d("Geofence event received")
        
        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: run {
            Timber.e("GeofencingEvent could not be created from intent")
            return
        }
        
        if (geofencingEvent.hasError()) {
            val errorMessage = GeofenceStatusCodes.getStatusCodeString(geofencingEvent.errorCode)
            Timber.e("Geofencing error: $errorMessage")
            return
        }
        
        // Get the transition type
        val geofenceTransition = geofencingEvent.geofenceTransition
        
        // Get the geofences that were triggered
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: run {
            Timber.e("No triggering geofences found")
            return
        }
        
        // Process each geofence transition
        processGeofenceTransition(context, geofenceTransition, triggeringGeofences)
    }
    
    /**
     * Process geofence transition events.
     */
    private fun processGeofenceTransition(context: Context, geofenceTransition: Int, triggeringGeofences: List<Geofence>) {
        // Create a description of the transition
        val transitionString = when (geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "entered"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "exited"
            Geofence.GEOFENCE_TRANSITION_DWELL -> "dwelling in"
            else -> "unknown transition"
        }
        
        // Get the IDs of each geofence that was triggered
        val triggeringGeofenceIds = triggeringGeofences.map { it.requestId }
        
        // Log the transition details
        Timber.d("Geofence transition: $transitionString for geofences: $triggeringGeofenceIds")
        
        // Handle the transition in a coroutine to avoid blocking the broadcast receiver
        scope.launch {
            when (geofenceTransition) {
                Geofence.GEOFENCE_TRANSITION_ENTER -> {
                    // Notify the tour guide service about entering a POI zone
                    notifyTourGuideService(context, "enter", triggeringGeofenceIds)
                }
                Geofence.GEOFENCE_TRANSITION_DWELL -> {
                    // Notify the tour guide service about dwelling in a POI zone
                    notifyTourGuideService(context, "dwell", triggeringGeofenceIds)
                }
                Geofence.GEOFENCE_TRANSITION_EXIT -> {
                    // Notify the tour guide service about exiting a POI zone
                    notifyTourGuideService(context, "exit", triggeringGeofenceIds)
                }
            }
        }
    }
    
    /**
     * Notify the tour guide service about a geofence transition.
     */
    private fun notifyTourGuideService(context: Context, action: String, geofenceIds: List<String>) {
        // Create intent for the TourModeService
        val serviceIntent = Intent(context, TourModeService::class.java).apply {
            this.action = AppConstants.ACTION_PROCESS_GEOFENCE
            putExtra("action", action)
            putStringArrayListExtra("geofence_ids", ArrayList(geofenceIds))
        }
        
        Timber.d("Starting TourModeService with action: $action for geofences: $geofenceIds")
        
        // Use regular startService instead of startForegroundService to avoid requiring
        // immediate startForeground() call in the service
        context.startService(serviceIntent)
    }
} 