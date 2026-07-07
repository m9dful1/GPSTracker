package com.spiritwisestudios.gpstracker.data.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import com.spiritwisestudios.gpstracker.domain.service.NavigationService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.roundToInt
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONException
import com.spiritwisestudios.gpstracker.BuildConfig
import com.spiritwisestudios.gpstracker.util.Polyline
import java.io.IOException
import android.graphics.Color

class NavigationServiceImpl @Inject constructor(
    private val context: Context
) : NavigationService {

    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    private val geocoder: Geocoder = Geocoder(context, Locale.getDefault())
    private val navigationState = MutableStateFlow<NavigationState>(NavigationState.Inactive)
    private val httpClient = OkHttpClient()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Add location callback and request
    private var locationCallback: LocationCallback? = null
    private var locationRequest: LocationRequest? = null
    private var lastRecalculationTimestampMs: Long = 0L
    private val offRouteDistanceThresholdMeters: Float = 80f
    private val routeRecalculationCooldownMs: Long = 15_000L
    
    private data class NavigationState(
        val isActive: Boolean = false,
        val destination: LatLng? = null,
        val waypoints: List<LatLng> = emptyList(),
        val currentRoute: List<LatLng> = emptyList(),
        val eta: Long = 0L, // ETA in milliseconds since epoch
        val distanceRemaining: Float = 0f,
        val nextInstruction: NavigationService.NavigationInstruction? = null,
        val allInstructions: List<NavigationService.NavigationInstruction> = emptyList(),
        val currentLocation: LatLng? = null
    ) {
        companion object {
            val Inactive = NavigationState()
        }
    }
    
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun startNavigation(destination: LatLng, waypoints: List<LatLng>): Flow<NavigationService.NavigationStatus> = callbackFlow {
        try {
            // Initial state update
            navigationState.value = NavigationState(
                isActive = true,
                destination = destination,
                waypoints = waypoints
            )
            
            // Get current location
            val location = getCurrentLocation()
            if (location != null) {
                // Calculate initial distance
                val currentLatLng = LatLng(location.latitude, location.longitude)
                val initialDistance = calculateDistance(currentLatLng, destination)
                
                // Update the navigation state with current location
                navigationState.value = navigationState.value.copy(
                    currentLocation = currentLatLng
                )
                
                // Get route from Directions API
                val routeResult = getRouteFromDirectionsApi(currentLatLng, destination, waypoints)
                
                if (routeResult != null) {
                    // Update navigation state with calculated values and route
                    navigationState.value = navigationState.value.copy(
                        currentRoute = routeResult.route,
                        distanceRemaining = routeResult.distance,
                        eta = System.currentTimeMillis() + routeResult.duration,
                        allInstructions = routeResult.instructions,
                        nextInstruction = routeResult.instructions.firstOrNull()
                    )
                    
                    // Determine announcement timing for first instruction
                    val announcementTiming = if (routeResult.instructions.isNotEmpty()) {
                        determineAnnouncementTiming(routeResult.instructions.first().distance)
                    } else {
                        NavigationService.AnnouncementTiming.NONE
                    }
                    
                    // Send initial status
                    trySend(
                        NavigationService.NavigationStatus(
                            isActive = true,
                            currentLocation = currentLatLng,
                            distanceRemaining = navigationState.value.distanceRemaining,
                            timeRemaining = routeResult.duration,
                            nextInstruction = navigationState.value.nextInstruction,
                            announcementTiming = announcementTiming
                        )
                    )
                } else {
                    // Fallback to straight-line calculation if API fails
                    navigationState.value = navigationState.value.copy(
                        currentRoute = listOf(currentLatLng, destination),
                        distanceRemaining = initialDistance,
                        eta = System.currentTimeMillis() + (initialDistance / 13.4f * 3600 * 1000).toLong() // Assuming average speed of 13.4 m/s (30 mph)
                    )
                    
                    // Send initial status
                    trySend(
                        NavigationService.NavigationStatus(
                            isActive = true,
                            currentLocation = currentLatLng,
                            distanceRemaining = initialDistance,
                            timeRemaining = (initialDistance / 13.4f * 3600 * 1000).toLong(),
                            nextInstruction = null
                        )
                    )
                }
                
                // Set up continuous location updates
                setupLocationUpdates { status -> trySend(status) }
            }
            
            awaitClose {
                // Clean up when flow is closed
                stopLocationUpdates()
                stopNavigation()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error starting navigation")
            // Send error status
            trySend(
                NavigationService.NavigationStatus(
                    isActive = false,
                    currentLocation = LatLng(0.0, 0.0),
                    distanceRemaining = 0f,
                    timeRemaining = 0,
                    nextInstruction = null
                )
            )
            close(e)
        }
    }
    
    /**
     * Set up location updates for continuous navigation
     */
    @SuppressLint("MissingPermission")
    private fun setupLocationUpdates(emitStatus: (NavigationService.NavigationStatus) -> Unit) {
        try {
            // Create location request
            locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)  // 5 seconds
                .setMinUpdateDistanceMeters(5f)  // 5 meters
                .setWaitForAccurateLocation(false)
                .build()
            
            // Create location callback
            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let { location ->
                        // Update with new location
                        updateNavigation(LatLng(location.latitude, location.longitude), emitStatus)
                    }
                }
            }
            
            // Request location updates
            fusedLocationClient.requestLocationUpdates(
                locationRequest!!,
                locationCallback!!,
                Looper.getMainLooper()
            )
            
            Timber.d("Started location updates for navigation")
        } catch (e: SecurityException) {
            Timber.e(e, "Location permission denied for navigation updates")
        } catch (e: Exception) {
            Timber.e(e, "Error setting up location updates")
        }
    }
    
    /**
     * Stop location updates
     */
    private fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
            Timber.d("Stopped location updates for navigation")
        }
        locationRequest = null
    }
    
    /**
     * Update navigation state with new location
     */
    private fun updateNavigation(newLocation: LatLng, emitStatus: (NavigationService.NavigationStatus) -> Unit) {
        val currentState = navigationState.value
        
        if (!currentState.isActive || currentState.destination == null) {
            return
        }
        
        // Calculate new distance to destination
        val newDistanceToDestination = calculateDistance(newLocation, currentState.destination)
        
        // Find the closest point on our route to current location
        val closestRoutePointIndex = findClosestPointOnRoute(newLocation, currentState.currentRoute)
        
        // Check if we need to update next instruction based on progress along the route
        val nextInstruction = updateNextInstructionBasedOnRoute(
            newLocation, 
            currentState.allInstructions,
            closestRoutePointIndex,
            currentState.currentRoute
        )
        
        // Determine when to announce the instruction
        val announcementTiming = if (nextInstruction != null) {
            determineAnnouncementTiming(nextInstruction.distance)
        } else {
            NavigationService.AnnouncementTiming.NONE
        }
        
        // Off-route detection and route recalculation (cooldown protected)
        if (closestRoutePointIndex >= 0 && currentState.currentRoute.isNotEmpty()) {
            val closestPoint = currentState.currentRoute[closestRoutePointIndex]
            val distanceToRoute = calculateDistance(newLocation, closestPoint)
            val now = System.currentTimeMillis()
            if (distanceToRoute > offRouteDistanceThresholdMeters && (now - lastRecalculationTimestampMs) > routeRecalculationCooldownMs) {
                lastRecalculationTimestampMs = now
                val dest = currentState.destination
                if (dest != null) {
                    serviceScope.launch {
                        val routeResult = getRouteFromDirectionsApi(newLocation, dest, currentState.waypoints)
                        if (routeResult != null) {
                            navigationState.value = navigationState.value.copy(
                                currentRoute = routeResult.route,
                                distanceRemaining = routeResult.distance,
                                eta = System.currentTimeMillis() + routeResult.duration,
                                allInstructions = routeResult.instructions,
                                nextInstruction = routeResult.instructions.firstOrNull(),
                                currentLocation = newLocation
                            )
                            val timing = routeResult.instructions.firstOrNull()?.let { determineAnnouncementTiming(it.distance) }
                                ?: NavigationService.AnnouncementTiming.NONE
                            emitStatus(
                                NavigationService.NavigationStatus(
                                    isActive = true,
                                    currentLocation = newLocation,
                                    distanceRemaining = routeResult.distance,
                                    timeRemaining = routeResult.duration,
                                    nextInstruction = navigationState.value.nextInstruction,
                                    announcementTiming = timing
                                )
                            )
                            return@launch
                        }
                    }
                }
            }
        }

        // Update arrival time estimate based on remaining route, not just straight line
        val remainingTime = if (closestRoutePointIndex >= 0 && closestRoutePointIndex < currentState.currentRoute.size - 1) {
            // Calculate remaining distance along the route
            var remainingDistance = 0f
            for (i in closestRoutePointIndex until currentState.currentRoute.size - 1) {
                remainingDistance += calculateDistance(
                    currentState.currentRoute[i],
                    currentState.currentRoute[i + 1]
                )
            }
            
            // Estimate time based on average speed
            val avgSpeed = 13.4f // m/s, about 30 mph
            (remainingDistance / avgSpeed * 1000).toLong() // milliseconds
        } else {
            // Fallback to straight-line calculation
            if (newDistanceToDestination > 0) {
                val avgSpeed = 13.4f // m/s, about 30 mph
                (newDistanceToDestination / avgSpeed * 1000).toLong() // milliseconds
            } else 0L
        }
        
        Timber.d("Navigation update: distance=${newDistanceToDestination}m, closest point=$closestRoutePointIndex/${currentState.currentRoute.size}, time=${remainingTime}ms")
        
        // Update navigation state
        navigationState.value = currentState.copy(
            currentLocation = newLocation,
            distanceRemaining = newDistanceToDestination,
            eta = System.currentTimeMillis() + remainingTime,
            nextInstruction = nextInstruction
        )
        
        // Emit updated status
        emitStatus(
            NavigationService.NavigationStatus(
                isActive = true,
                currentLocation = newLocation,
                distanceRemaining = newDistanceToDestination,
                timeRemaining = remainingTime,
                nextInstruction = nextInstruction,
                announcementTiming = announcementTiming
            )
        )
        
        // Check if we've arrived at the destination
        if (newDistanceToDestination < 50) { // Within 50 meters of destination
            Timber.d("Arrived at destination")
            
            // Create arrival instruction
            val arrivalInstruction = NavigationService.NavigationInstruction(
                type = NavigationService.InstructionType.ARRIVE,
                distance = 0f,
                description = "You have arrived at your destination",
                maneuverPoint = newLocation
            )
            
            // Emit arrival status
            emitStatus(
                NavigationService.NavigationStatus(
                    isActive = true,
                    currentLocation = newLocation,
                    distanceRemaining = 0f,
                    timeRemaining = 0,
                    nextInstruction = arrivalInstruction,
                    announcementTiming = NavigationService.AnnouncementTiming.IMMEDIATE
                )
            )
        }
    }
    
    /**
     * Find the index of the closest point on the route to the current location
     */
    private fun findClosestPointOnRoute(location: LatLng, route: List<LatLng>): Int {
        if (route.isEmpty()) return -1
        
        var closestPointIndex = -1
        var minDistance = Float.MAX_VALUE
        
        for (i in route.indices) {
            val distance = calculateDistance(location, route[i])
            if (distance < minDistance) {
                minDistance = distance
                closestPointIndex = i
            }
        }
        
        return closestPointIndex
    }
    
    /**
     * Update the next instruction based on progress along the route
     */
    private fun updateNextInstructionBasedOnRoute(
        location: LatLng,
        instructions: List<NavigationService.NavigationInstruction>,
        closestRoutePointIndex: Int,
        route: List<LatLng>
    ): NavigationService.NavigationInstruction? {
        if (instructions.isEmpty() || closestRoutePointIndex < 0) return null
        
        // First find the next upcoming instruction we haven't passed yet
        var nextInstructionIndex = -1
        var closestDistance = Float.MAX_VALUE
        
        for (i in instructions.indices) {
            val instruction = instructions[i]
            
            // Find the closest point on the route to this instruction
            val instructionRouteIndex = findClosestPointOnRoute(instruction.maneuverPoint, route)
            
            // Only consider instructions ahead of us on the route
            if (instructionRouteIndex > closestRoutePointIndex) {
                val distance = calculateDistance(location, instruction.maneuverPoint)
                
                Timber.d("Instruction ${i}: ${instruction.description} at route point $instructionRouteIndex, distance=${distance}m")
                
                // If this instruction is ahead of us and closer than the current closest
                if (distance < closestDistance) {
                    closestDistance = distance
                    nextInstructionIndex = i
                }
            }
        }
        
        // If we found a next instruction
        if (nextInstructionIndex >= 0) {
            val nextInstruction = instructions[nextInstructionIndex]
            
            // Update the distance in the instruction to reflect actual distance
            val updatedDistance = calculateDistance(location, nextInstruction.maneuverPoint)
            
            Timber.d("Selected instruction $nextInstructionIndex: ${nextInstruction.description}, distance=${updatedDistance}m")
            
            // Return an updated copy of the instruction with the correct distance
            return nextInstruction.copy(distance = updatedDistance)
        }
        
        // Fallback: if we can't find an instruction ahead (maybe we're off route),
        // just return the closest instruction
        return if (instructions.isNotEmpty()) {
            var closestInstruction = instructions[0]
            var minDistance = calculateDistance(location, closestInstruction.maneuverPoint)
            
            for (instruction in instructions) {
                val distance = calculateDistance(location, instruction.maneuverPoint)
                if (distance < minDistance) {
                    minDistance = distance
                    closestInstruction = instruction
                }
            }
            
            // Update distance in the instruction
            closestInstruction.copy(distance = minDistance)
        } else null
    }
    
    /**
     * Get route information from Google Directions API
     */
    private suspend fun getRouteFromDirectionsApi(
        origin: LatLng,
        destination: LatLng,
        waypoints: List<LatLng> = emptyList()
    ): RouteResult? = withContext(Dispatchers.IO) {
        // Check network connectivity first
        if (!isNetworkAvailable()) {
            Timber.e("No network connection available for route calculation")
            return@withContext null
        }
        
        try {
            val apiKey = BuildConfig.MAPS_API_KEY

            // Validate API key (never log the key itself)
            if (!isValidApiKey(apiKey)) {
                Timber.e("Google Maps API key is missing or malformed — check MAPS_API_KEY in local.properties")
                return@withContext null
            }
            
            // Build the waypoints string if there are any
            val waypointsString = if (waypoints.isNotEmpty()) {
                "&waypoints=" + waypoints.joinToString("|") { 
                    "${it.latitude},${it.longitude}" 
                }
            } else ""
            
            // Build the URL
            val url = "https://maps.googleapis.com/maps/api/directions/json?" +
                    "origin=${origin.latitude},${origin.longitude}" +
                    "&destination=${destination.latitude},${destination.longitude}" +
                    waypointsString +
                    "&mode=driving" +
                    "&key=$apiKey"
            
            // Log the URL without the API key
            Timber.d("Making Directions API request: ${url.replace(apiKey, "***")}")
            
            // Make the request
            val request = Request.Builder()
                .url(url)
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                Timber.d("Directions API response code: ${response.code}")
                
                if (!response.isSuccessful) {
                    Timber.e("Directions API request failed: ${response.code} - ${response.message}")
                    return@withContext null
                }
                
                val responseData = response.body?.string()
                if (responseData.isNullOrEmpty()) {
                    Timber.e("Empty response from Directions API")
                    return@withContext null
                }
                
                // Log first part of the response (truncated for brevity)
                Timber.d("Directions API raw response: ${responseData.take(500)}...")
                
                // Parse the response
                val jsonResponse = JSONObject(responseData)
                val status = jsonResponse.getString("status")
                Timber.d("Directions API status: $status")
                
                if (status != "OK") {
                    val errorMessage = if (jsonResponse.has("error_message")) {
                        jsonResponse.getString("error_message")
                    } else {
                        "Unknown error"
                    }
                    Timber.e("Directions API returned error: $status - $errorMessage")
                    return@withContext null
                }
                
                // Get the first route
                val routes = jsonResponse.getJSONArray("routes")
                Timber.d("Directions API returned ${routes.length()} routes")
                
                if (routes.length() == 0) {
                    Timber.e("No routes found in Directions API response")
                    return@withContext null
                }
                
                val route = routes.getJSONObject(0)
                val legs = route.getJSONArray("legs")
                Timber.d("First route has ${legs.length()} leg(s)")
                
                // Calculate total distance and duration
                var totalDistance = 0
                var totalDuration = 0L
                val instructions = mutableListOf<NavigationService.NavigationInstruction>()
                
                // Process each leg of the journey
                for (i in 0 until legs.length()) {
                    val leg = legs.getJSONObject(i)
                    
                    // Get distance and duration
                    val distance = leg.getJSONObject("distance").getInt("value")
                    val duration = leg.getJSONObject("duration").getInt("value") * 1000L // Convert to milliseconds
                    
                    totalDistance += distance
                    totalDuration += duration
                    
                    // Get steps (instructions)
                    val steps = leg.getJSONArray("steps")
                    for (j in 0 until steps.length()) {
                        val step = steps.getJSONObject(j)
                        val stepDistance = step.getJSONObject("distance").getInt("value")
                        val instruction = step.getString("html_instructions")
                            .replace("<[^>]*>".toRegex(), " ") // Remove HTML tags
                            .replace("\\s+".toRegex(), " ") // Replace multiple spaces with a single space
                            .trim()
                            
                        // Get maneuver point
                        val startLocation = step.getJSONObject("start_location")
                        val maneuverPoint = LatLng(
                            startLocation.getDouble("lat"),
                            startLocation.getDouble("lng")
                        )
                        
                        // Determine instruction type
                        val maneuver = if (step.has("maneuver")) step.getString("maneuver") else "straight"
                        val instructionType = when (maneuver) {
                            "turn-left" -> NavigationService.InstructionType.TURN_LEFT
                            "turn-right" -> NavigationService.InstructionType.TURN_RIGHT
                            "turn-slight-left" -> NavigationService.InstructionType.TURN_SLIGHT_LEFT
                            "turn-slight-right" -> NavigationService.InstructionType.TURN_SLIGHT_RIGHT
                            "turn-sharp-left" -> NavigationService.InstructionType.TURN_SHARP_LEFT
                            "turn-sharp-right" -> NavigationService.InstructionType.TURN_SHARP_RIGHT
                            "roundabout-left", "roundabout-right", "rotary" -> NavigationService.InstructionType.ROUNDABOUT
                            "merge" -> NavigationService.InstructionType.MERGE
                            "ramp-left", "ramp-right" -> NavigationService.InstructionType.HIGHWAY_EXIT
                            "ferry", "ferry-train" -> NavigationService.InstructionType.OTHER
                            else -> NavigationService.InstructionType.STRAIGHT
                        }
                        
                        // Add to instructions list
                        instructions.add(
                            NavigationService.NavigationInstruction(
                                type = instructionType,
                                distance = stepDistance.toFloat(),
                                description = instruction,
                                maneuverPoint = maneuverPoint
                            )
                        )
                    }
                }
                
                // Decode the polyline
                val overviewPolyline = route.getJSONObject("overview_polyline").getString("points")
                val polylinePoints = Polyline.decode(overviewPolyline)
                
                // Before returning the route result
                Timber.d("Successfully parsed route with ${polylinePoints.size} points, distance: ${totalDistance}m, duration: ${totalDuration}ms")
                
                return@withContext RouteResult(
                    route = polylinePoints,
                    distance = totalDistance.toFloat(),
                    duration = totalDuration,
                    instructions = instructions
                )
            }
        } catch (e: IOException) {
            Timber.e(e, "Network error when fetching route: ${e.message}")
            return@withContext null
        } catch (e: JSONException) {
            Timber.e(e, "Error parsing Directions API response: ${e.message}")
            return@withContext null
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error getting route: ${e.message}")
            return@withContext null
        }
    }
    
    /**
     * Data class to encapsulate route results
     */
    private data class RouteResult(
        val route: List<LatLng>,
        val distance: Float,
        val duration: Long,
        val instructions: List<NavigationService.NavigationInstruction>
    )

    override suspend fun getCurrentRoute(): List<LatLng> {
        return navigationState.value.currentRoute
    }

    override suspend fun getEstimatedTimeOfArrival(): Long {
        return navigationState.value.eta
    }

    override fun getRemainingDistance(): Float {
        return navigationState.value.distanceRemaining
    }

    override suspend fun getNextInstruction(): NavigationService.NavigationInstruction? {
        return navigationState.value.nextInstruction
    }

    override fun stopNavigation() {
        stopLocationUpdates()
        navigationState.value = NavigationState.Inactive
    }

    override fun isNavigating(): Boolean {
        return navigationState.value.isActive
    }

    @SuppressLint("MissingPermission")
    private suspend fun getCurrentLocation(): Location? = suspendCancellableCoroutine { continuation ->
        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    continuation.resume(location)
                }
                .addOnFailureListener { e ->
                    Timber.e(e, "Error getting current location")
                    continuation.resume(null)
                }
        } catch (e: SecurityException) {
            Timber.e(e, "Location permission denied")
            continuation.resume(null)
        }
    }

    /**
     * Check if network is available
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            return networkInfo != null && networkInfo.isConnected
        }
    }

    /**
     * Geocode an address to coordinates
     */
    override suspend fun geocodeAddress(address: String): LatLng? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                var result: LatLng? = null
                geocoder.getFromLocationName(address, 1) { addresses ->
                    if (addresses.isNotEmpty()) {
                        val latitude = addresses[0].latitude
                        val longitude = addresses[0].longitude
                        result = LatLng(latitude, longitude)
                    }
                }
                result
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(address, 1)
                if (addresses != null && addresses.isNotEmpty()) {
                    val latitude = addresses[0].latitude
                    val longitude = addresses[0].longitude
                    LatLng(latitude, longitude)
                } else {
                    null
                }
            }
        } catch (e: IOException) {
            Timber.e(e, "Network error geocoding address: $address")
            null
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "Invalid address format: $address")
            null
        } catch (e: Exception) {
            Timber.e(e, "Error geocoding address: $address - ${e.message}")
            null
        }
    }
    
    private fun calculateDistance(start: LatLng, end: LatLng): Float {
        val results = FloatArray(1)
        Location.distanceBetween(
            start.latitude, start.longitude,
            end.latitude, end.longitude,
            results
        )
        return results[0]
    }

    /**
     * Check if the API key appears to be valid
     * Note: This is a basic check - the key might still be rejected by Google
     */
    private fun isValidApiKey(apiKey: String): Boolean {
        // Check if the key is empty or default placeholder
        if (apiKey.isBlank() || apiKey == "YOUR_API_KEY_HERE") {
            return false
        }
        
        // Basic format check for Google API keys
        // Most Google API keys are ~39 characters and start with "AIza"
        if (!apiKey.startsWith("AIza") || apiKey.length < 30) {
            return false
        }
        
        return true
    }

    /**
     * Get detailed information for a maneuver.
     */
    override fun getManeuverDetails(instruction: NavigationService.NavigationInstruction): NavigationService.ManeuverDetails {
        // Visual icon based on instruction type
        val visualIcon = when (instruction.type) {
            NavigationService.InstructionType.TURN_LEFT -> "↰"
            NavigationService.InstructionType.TURN_RIGHT -> "↱"
            NavigationService.InstructionType.TURN_SLIGHT_LEFT -> "↖"
            NavigationService.InstructionType.TURN_SLIGHT_RIGHT -> "↗"
            NavigationService.InstructionType.TURN_SHARP_LEFT -> "⬏"
            NavigationService.InstructionType.TURN_SHARP_RIGHT -> "⬎"
            NavigationService.InstructionType.ROUNDABOUT -> "⭮"
            NavigationService.InstructionType.MERGE -> "⥱"
            NavigationService.InstructionType.HIGHWAY_EXIT -> "⛐"
            NavigationService.InstructionType.ARRIVE -> "⭐"
            NavigationService.InstructionType.DEPART -> "⭢"
            else -> "⭢" // Straight or other
        }
        
        // Visual color based on instruction type
        val visualColor = when (instruction.type) {
            NavigationService.InstructionType.ARRIVE -> Color.GREEN
            NavigationService.InstructionType.DEPART -> Color.BLUE
            else -> Color.BLUE
        }
        
        // Sound cue based on instruction type
        val soundCue = when (instruction.type) {
            NavigationService.InstructionType.TURN_LEFT -> "turn_left"
            NavigationService.InstructionType.TURN_RIGHT -> "turn_right"
            NavigationService.InstructionType.TURN_SLIGHT_LEFT -> "turn_slight_left"
            NavigationService.InstructionType.TURN_SLIGHT_RIGHT -> "turn_slight_right"
            NavigationService.InstructionType.TURN_SHARP_LEFT -> "turn_sharp_left"
            NavigationService.InstructionType.TURN_SHARP_RIGHT -> "turn_sharp_right"
            NavigationService.InstructionType.ROUNDABOUT -> "roundabout"
            NavigationService.InstructionType.MERGE -> "merge"
            NavigationService.InstructionType.HIGHWAY_EXIT -> "exit"
            NavigationService.InstructionType.ARRIVE -> "arrive"
            NavigationService.InstructionType.DEPART -> "depart"
            else -> "continue"
        }
        
        // Format distance for human-friendly display
        val distanceText = when {
            instruction.distance >= 1000 -> String.format("%.1f km", instruction.distance / 1000)
            else -> String.format("%d m", instruction.distance.toInt())
        }
        
        // Primary instruction (main directive)
        val primaryInstruction = when (instruction.type) {
            NavigationService.InstructionType.TURN_LEFT -> "Turn left"
            NavigationService.InstructionType.TURN_RIGHT -> "Turn right"
            NavigationService.InstructionType.TURN_SLIGHT_LEFT -> "Turn slight left"
            NavigationService.InstructionType.TURN_SLIGHT_RIGHT -> "Turn slight right"
            NavigationService.InstructionType.TURN_SHARP_LEFT -> "Turn sharp left"
            NavigationService.InstructionType.TURN_SHARP_RIGHT -> "Turn sharp right"
            NavigationService.InstructionType.ROUNDABOUT -> "Enter roundabout"
            NavigationService.InstructionType.MERGE -> "Merge"
            NavigationService.InstructionType.HIGHWAY_EXIT -> "Take exit"
            NavigationService.InstructionType.ARRIVE -> "Arrive at destination"
            NavigationService.InstructionType.DEPART -> "Depart"
            else -> "Continue"
        }
        
        // Secondary instruction (additional context)
        val secondaryInstruction = when (instruction.type) {
            NavigationService.InstructionType.ARRIVE -> "Your destination is on the right"
            else -> "in $distanceText"
        }
        
        return NavigationService.ManeuverDetails(
            visualIcon = visualIcon,
            visualColor = visualColor,
            soundCue = soundCue,
            primaryInstruction = primaryInstruction,
            secondaryInstruction = secondaryInstruction
        )
    }
    
    /**
     * Determine when to announce an instruction based on distance.
     */
    override fun determineAnnouncementTiming(distanceToManeuver: Float): NavigationService.AnnouncementTiming {
        return when {
            distanceToManeuver < 0 -> NavigationService.AnnouncementTiming.PASSED
            distanceToManeuver <= 30 -> NavigationService.AnnouncementTiming.IMMEDIATE
            distanceToManeuver <= 200 -> NavigationService.AnnouncementTiming.APPROACHING
            distanceToManeuver <= 500 -> NavigationService.AnnouncementTiming.ADVANCE
            else -> NavigationService.AnnouncementTiming.NONE
        }
    }
} 