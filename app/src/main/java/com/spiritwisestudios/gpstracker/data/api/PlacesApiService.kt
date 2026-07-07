package com.spiritwisestudios.gpstracker.data.api

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Service to interact with the Google Places API.
 *
 * Nearby discovery uses the Places Nearby Search web service so we can search
 * around any point (e.g., sampled points along a navigation route), not just
 * the device's current position. Place details use the Places SDK.
 */
class PlacesApiService(
    private val placesClient: PlacesClient,
    private val httpClient: OkHttpClient,
    private val apiKey: String
) {

    companion object {
        private const val NEARBY_SEARCH_URL = "https://maps.googleapis.com/maps/api/place/nearbysearch/json"
        private const val PHOTO_URL = "https://maps.googleapis.com/maps/api/place/photo"

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

        /**
         * Map Google Place type strings to a stable domain category label.
         */
        internal fun mapPlaceTypesToCategory(types: List<String>): String {
            return when {
                types.any { it in setOf("museum", "art_gallery", "zoo", "aquarium", "library", "university") } -> "CULTURAL"
                types.any { it in setOf("church", "place_of_worship", "synagogue", "mosque", "hindu_temple", "cemetery", "city_hall") } -> "HISTORICAL"
                types.any { it in setOf("park", "campground", "natural_feature") } -> "NATURAL"
                types.any { it in setOf("tourist_attraction", "amusement_park", "movie_theater", "night_club", "stadium", "casino", "bowling_alley") } -> "ENTERTAINMENT"
                types.any { it in setOf("restaurant", "cafe", "bar", "bakery", "meal_takeaway") } -> "DINING"
                types.any { it in setOf("shopping_mall", "department_store", "book_store", "clothing_store") } -> "SHOPPING"
                else -> "OTHER"
            }
        }

        /**
         * Build a fetchable photo URL from a Nearby Search photo reference.
         */
        internal fun photoUrlFor(photoReference: String, apiKey: String): String {
            return "$PHOTO_URL?maxwidth=800&photo_reference=$photoReference&key=$apiKey"
        }

        /**
         * Parse a Nearby Search JSON response into domain POIs, keeping only
         * places whose types map to a tour-worthy category. Throws
         * [IOException] for non-OK API statuses (other than ZERO_RESULTS).
         */
        internal fun parseNearbySearchResponse(json: String, apiKey: String): List<PointOfInterest> {
            val root = JSONObject(json)
            when (val searchStatus = root.getString("status")) {
                "OK" -> Unit
                "ZERO_RESULTS" -> return emptyList()
                else -> {
                    val message = root.optString("error_message", "no details")
                    throw IOException("Nearby Search failed: $searchStatus ($message)")
                }
            }

            val results = root.getJSONArray("results")
            val pois = mutableListOf<PointOfInterest>()

            for (i in 0 until results.length()) {
                val result = results.getJSONObject(i)
                val placeId = result.optString("place_id", "")
                if (placeId.isEmpty()) continue

                val types = result.optJSONArray("types")?.let { array ->
                    (0 until array.length()).map { array.getString(it) }
                } ?: emptyList()

                // Skip places with nothing to narrate (gas stations, offices, ...)
                val category = mapPlaceTypesToCategory(types)
                if (category == "OTHER") continue

                val location = result.getJSONObject("geometry").getJSONObject("location")
                val photoReference = result.optJSONArray("photos")
                    ?.optJSONObject(0)
                    ?.optString("photo_reference", "")
                    ?.takeIf { it.isNotEmpty() }

                pois.add(
                    PointOfInterest(
                        id = placeId,
                        name = result.optString("name", "Unknown Place"),
                        latLng = LatLng(location.getDouble("lat"), location.getDouble("lng")),
                        address = result.optString("vicinity", ""),
                        category = category,
                        rating = if (result.has("rating")) result.getDouble("rating") else null,
                        description = null,
                        photoUrl = photoReference?.let { photoUrlFor(it, apiKey) },
                        placeId = placeId
                    )
                )
            }

            return pois
        }
    }

    /**
     * Find tour-worthy points of interest around a location.
     */
    suspend fun getNearbyPlaces(center: LatLng, radius: Int): List<PointOfInterest> = withContext(Dispatchers.IO) {
        val url = "$NEARBY_SEARCH_URL?location=${center.latitude},${center.longitude}" +
                "&radius=$radius&key=$apiKey"

        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Nearby Search HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw IOException("Empty Nearby Search response")
            parseNearbySearchResponse(body, apiKey)
        }
    }

    /**
     * Get detailed information about a specific place.
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
                    id = place.id ?: UUID.randomUUID().toString(),
                    name = place.name ?: "Unknown Place",
                    latLng = place.latLng ?: LatLng(0.0, 0.0),
                    address = place.address ?: "",
                    category = mapPlaceTypesToCategory(place.types?.map { it.name.lowercase() } ?: emptyList()),
                    rating = place.rating,
                    description = description,
                    // The SDK only exposes photos as bitmaps, not URLs; nearby
                    // search results carry the photo URL instead.
                    photoUrl = null,
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
                val statusObj = exception.status

                // Log detailed API exception information
                Timber.e(exception, "$logMessage (Status Code: $statusCode, Status: ${statusObj.statusMessage})")

                // Check specifically for authorization issues
                if (statusObj.statusMessage?.contains("API key") == true ||
                    statusObj.statusMessage?.contains("not authorized") == true ||
                    statusCode == STATUS_DEVELOPER_ERROR) {

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
