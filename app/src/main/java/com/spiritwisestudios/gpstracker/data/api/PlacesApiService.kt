package com.spiritwisestudios.gpstracker.data.api

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindCurrentPlaceRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.resume

/**
 * Service to interact with Google Places API
 */
class PlacesApiService(private val placesClient: PlacesClient) {

    companion object {
        private const val MAX_RETRY_COUNT = 3
        private const val TAG = "PlacesApiService"
        
        // Common API status codes
        private const val STATUS_NETWORK_ERROR = 7
        private const val STATUS_INTERNAL_ERROR = 8
        private const val STATUS_DEVELOPER_ERROR = 10
        private const val STATUS_API_NOT_CONNECTED = 1
        private const val STATUS_RESOLUTION_REQUIRED = 6
        private const val STATUS_INVALID_ACCOUNT = 5
        private const val STATUS_SIGN_IN_REQUIRED = 4
        private const val STATUS_SERVICE_DISABLED = 3
        private const val STATUS_TIMEOUT = 15
    }

    /**
     * Get nearby points of interest based on current location
     */
    suspend fun getNearbyPlaces(radius: Int = 500): List<PointOfInterest> = suspendCancellableCoroutine { continuation ->
        // Define the place fields we want to get
        val placeFields = listOf(
            Place.Field.ID,
            Place.Field.NAME,
            Place.Field.LAT_LNG,
            Place.Field.ADDRESS,
            Place.Field.TYPES,
            Place.Field.RATING,
            Place.Field.PHOTO_METADATAS
        )

        val request = FindCurrentPlaceRequest.newInstance(placeFields)

        try {
            placesClient.findCurrentPlace(request).addOnSuccessListener { response ->
                val places = response.placeLikelihoods.map { placeLikelihood ->
                    val place = placeLikelihood.place
                    PointOfInterest(
                        name = place.name ?: "Unknown Place",
                        latLng = place.latLng ?: LatLng(0.0, 0.0),
                        address = place.address ?: "",
                        category = place.types?.firstOrNull()?.toString() ?: "UNKNOWN",
                        rating = place.rating,
                        placeId = place.id,
                        photoUrl = place.photoMetadatas?.firstOrNull()?.attributions
                    )
                }
                continuation.resume(places)
            }.addOnFailureListener { exception ->
                handleApiException(exception, "Error fetching nearby places", continuation)
            }
        } catch (e: SecurityException) {
            // Handle permission issues
            Timber.e(e, "Permission denied for finding current place")
            continuation.resumeWithException(
                SecurityException("Location permission denied. Please grant permission and try again.")
            )
        } catch (e: Exception) {
            handleGenericException(e, "Error fetching nearby places", continuation)
        }
    }

    /**
     * Get detailed information about a specific place
     */
    suspend fun getPlaceDetails(placeId: String): PointOfInterest = suspendCancellableCoroutine { continuation ->
        // Define the place fields we want to get for the details
        val placeFields = listOf(
            Place.Field.ID,
            Place.Field.NAME,
            Place.Field.LAT_LNG,
            Place.Field.ADDRESS,
            Place.Field.TYPES,
            Place.Field.RATING,
            Place.Field.PHOTO_METADATAS,
            Place.Field.PHONE_NUMBER,
            Place.Field.WEBSITE_URI,
            Place.Field.BUSINESS_STATUS,
            Place.Field.PRICE_LEVEL,
            Place.Field.OPENING_HOURS,
            Place.Field.USER_RATINGS_TOTAL
        )

        // Create the request
        val request = FetchPlaceRequest.newInstance(placeId, placeFields)

        try {
            placesClient.fetchPlace(request).addOnSuccessListener { response ->
                val place = response.place
                val description = buildDescription(place)
                
                val pointOfInterest = PointOfInterest(
                    name = place.name ?: "Unknown Place",
                    latLng = place.latLng ?: LatLng(0.0, 0.0),
                    address = place.address ?: "",
                    category = place.types?.firstOrNull()?.toString() ?: "UNKNOWN",
                    rating = place.rating,
                    description = description,
                    photoUrl = place.photoMetadatas?.firstOrNull()?.attributions,
                    placeId = place.id
                )
                
                continuation.resume(pointOfInterest)
            }.addOnFailureListener { exception ->
                handleApiException(exception, "Error fetching details for place ID: $placeId", continuation)
            }
        } catch (e: Exception) {
            handleGenericException(e, "Exception when fetching details for place ID: $placeId", continuation)
        }
    }
    
    /**
     * Build a description for a place based on available information
     */
    private fun buildDescription(place: Place): String {
        val descriptionParts = mutableListOf<String>()
        
        // Add business status if available
        place.businessStatus?.let {
            val status = when (it) {
                Place.BusinessStatus.OPERATIONAL -> "Open"
                Place.BusinessStatus.CLOSED_TEMPORARILY -> "Temporarily Closed"
                Place.BusinessStatus.CLOSED_PERMANENTLY -> "Permanently Closed"
                else -> null
            }
            status?.let { status -> descriptionParts.add(status) }
        }
        
        // Add price level if available
        place.priceLevel?.let {
            val priceDescription = when (it) {
                0 -> "Free"
                1 -> "Inexpensive"
                2 -> "Moderate"
                3 -> "Expensive"
                4 -> "Very Expensive"
                else -> null
            }
            priceDescription?.let { price -> descriptionParts.add(price) }
        }
        
        // Add user ratings if available
        if (place.rating != null && place.userRatingsTotal != null) {
            descriptionParts.add("${place.rating} stars (${place.userRatingsTotal} reviews)")
        }
        
        // Add phone number if available
        place.phoneNumber?.let { 
            descriptionParts.add("Phone: $it") 
        }
        
        // Add website if available
        place.websiteUri?.let { 
            descriptionParts.add("Website: $it") 
        }
        
        return descriptionParts.joinToString(" · ")
    }
    
    /**
     * Handle API-specific exceptions with appropriate error messages
     */
    private fun <T> handleApiException(
        exception: Exception,
        logMessage: String,
        continuation: CancellableContinuation<T>
    ) {
        when (exception) {
            is ApiException -> {
                val statusCode = exception.statusCode
                val errorMessage = getErrorMessageForStatusCode(statusCode)
                val statusObj = exception.status
                
                // Log detailed API exception information
                Timber.e(exception, "$logMessage (Status Code: $statusCode, Status: ${statusObj.statusMessage})")
                
                // Check specifically for authorization issues
                if (statusObj.statusMessage?.contains("API key") == true || 
                    statusObj.statusMessage?.contains("not authorized") == true ||
                    statusCode == STATUS_DEVELOPER_ERROR) {
                    
                    // Log specific authorization error details
                    Timber.e("Places API authorization error: ${statusObj.statusMessage}. " +
                             "Make sure the API key is correctly configured and has Places API enabled.")
                    
                    continuation.resumeWithException(SecurityException(
                        "Places API authorization error. Please check API key configuration."
                    ))
                } else {
                    continuation.resumeWithException(exception)
                }
            }
            is SecurityException -> {
                Timber.e(exception, "$logMessage: Security error")
                continuation.resumeWithException(
                    SecurityException("Permission denied: ${exception.message}")
                )
            }
            is IOException -> {
                Timber.e(exception, "$logMessage: Network error")
                continuation.resumeWithException(
                    IOException("Network error. Please check your connection and try again.")
                )
            }
            is TimeoutException -> {
                Timber.e(exception, "$logMessage: Request timed out")
                continuation.resumeWithException(
                    TimeoutException("Request timed out. Please try again.")
                )
            }
            else -> {
                Timber.e(exception, "$logMessage: Unexpected error: ${exception.javaClass.simpleName} - ${exception.message}")
                continuation.resumeWithException(exception)
            }
        }
    }
    
    /**
     * Handle generic exceptions with appropriate logging and error messages
     */
    private fun <T> handleGenericException(
        exception: Exception,
        logMessage: String,
        continuation: CancellableContinuation<T>
    ) {
        Timber.e(exception, logMessage)
        continuation.resumeWithException(exception)
    }
    
    /**
     * Get a user-friendly error message based on the API status code
     */
    private fun getErrorMessageForStatusCode(statusCode: Int): String {
        return when (statusCode) {
            STATUS_NETWORK_ERROR -> "Network error. Please check your connection."
            STATUS_INTERNAL_ERROR -> "An internal error occurred. Please try again later."
            STATUS_DEVELOPER_ERROR -> "Developer error. Please report this issue."
            STATUS_API_NOT_CONNECTED -> "API not connected. Please try again."
            STATUS_RESOLUTION_REQUIRED -> "Resolution required. Please update Google Play Services."
            STATUS_INVALID_ACCOUNT -> "Invalid account. Please check your Google account."
            STATUS_SIGN_IN_REQUIRED -> "Sign-in required. Please sign in to your Google account."
            STATUS_SERVICE_DISABLED -> "Service disabled. Please enable Google Play Services."
            STATUS_TIMEOUT -> "Request timed out. Please try again."
            else -> "An unexpected error occurred. Please try again later."
        }
    }
} 