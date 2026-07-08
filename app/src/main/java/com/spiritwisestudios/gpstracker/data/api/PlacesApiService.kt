package com.spiritwisestudios.gpstracker.data.api

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.model.CircularBounds
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.libraries.places.api.net.SearchNearbyRequest
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.io.IOException
import java.util.UUID

/**
 * Service to interact with the Google Places API (New) through the Places SDK.
 *
 * Nearby discovery uses Nearby Search (New), which can search around any point
 * (e.g., sampled points along a navigation route), not just the device's
 * current position. Place details use Place Details (New). Both require
 * "Places API (New)" to be enabled in Google Cloud Console.
 */
class PlacesApiService(
    private val placesClient: PlacesClient
) {

    companion object {
        private const val MAX_NEARBY_RESULTS = 20
        private const val STATUS_DEVELOPER_ERROR = 10

        /**
         * Map Google Place type strings to a stable domain category label.
         * Covers both legacy type names (stored in the local POI cache) and
         * the expanded type set returned by the new Places API.
         */
        internal fun mapPlaceTypesToCategory(types: List<String>): String {
            return when {
                types.any { it in setOf("museum", "art_gallery", "zoo", "aquarium", "library", "university", "cultural_center", "performing_arts_theater") } -> "CULTURAL"
                types.any { it in setOf("church", "place_of_worship", "synagogue", "mosque", "hindu_temple", "cemetery", "city_hall", "historical_landmark", "historical_place", "monument") } -> "HISTORICAL"
                types.any { it in setOf("park", "campground", "natural_feature", "national_park", "state_park", "botanical_garden", "garden", "beach") } -> "NATURAL"
                types.any { it in setOf("tourist_attraction", "amusement_park", "movie_theater", "night_club", "stadium", "casino", "bowling_alley") } -> "ENTERTAINMENT"
                types.any { it in setOf("restaurant", "cafe", "bar", "bakery", "meal_takeaway") } -> "DINING"
                types.any { it in setOf("shopping_mall", "department_store", "book_store", "clothing_store") } -> "SHOPPING"
                else -> "OTHER"
            }
        }
    }

    /**
     * Find tour-worthy points of interest around a location.
     */
    suspend fun getNearbyPlaces(center: LatLng, radius: Int): List<PointOfInterest> {
        val placeFields = listOf(
            Place.Field.ID,
            Place.Field.DISPLAY_NAME,
            Place.Field.LOCATION,
            Place.Field.FORMATTED_ADDRESS,
            Place.Field.TYPES,
            Place.Field.RATING
        )
        val bounds = CircularBounds.newInstance(center, radius.toDouble())
        val request = SearchNearbyRequest.builder(bounds, placeFields)
            .setMaxResultCount(MAX_NEARBY_RESULTS)
            .build()

        val response = try {
            placesClient.searchNearby(request).await()
        } catch (e: ApiException) {
            Timber.e(e, "Nearby Search failed (status ${e.statusCode})")
            throw translateApiException(e, "Nearby Search failed")
        }

        return response.places.mapNotNull { placeToPoi(it) }
    }

    /**
     * Get detailed information about a specific place.
     */
    suspend fun getPlaceDetails(placeId: String): PointOfInterest {
        val placeFields = listOf(
            Place.Field.ID,
            Place.Field.DISPLAY_NAME,
            Place.Field.LOCATION,
            Place.Field.FORMATTED_ADDRESS,
            Place.Field.TYPES,
            Place.Field.RATING,
            Place.Field.INTERNATIONAL_PHONE_NUMBER,
            Place.Field.WEBSITE_URI,
            Place.Field.BUSINESS_STATUS,
            Place.Field.PRICE_LEVEL,
            Place.Field.OPENING_HOURS,
            Place.Field.USER_RATING_COUNT
        )
        val request = FetchPlaceRequest.newInstance(placeId, placeFields)

        val place = try {
            placesClient.fetchPlace(request).await().place
        } catch (e: ApiException) {
            Timber.e(e, "Error fetching details for place ID: $placeId (status ${e.statusCode})")
            throw translateApiException(e, "Error fetching place details")
        }

        return PointOfInterest(
            id = place.id ?: UUID.randomUUID().toString(),
            name = place.displayName ?: "Unknown Place",
            latLng = place.location ?: LatLng(0.0, 0.0),
            address = place.formattedAddress ?: "",
            category = mapPlaceTypesToCategory(place.placeTypes ?: emptyList()),
            rating = place.rating,
            description = buildDescription(place),
            photoUrl = null,
            placeId = place.id
        )
    }

    /**
     * Convert a Nearby Search result into a domain POI, or null when the place
     * has nothing to narrate (gas stations, offices, ...).
     */
    private fun placeToPoi(place: Place): PointOfInterest? {
        val placeId = place.id ?: return null
        val location = place.location ?: return null

        val category = mapPlaceTypesToCategory(place.placeTypes ?: emptyList())
        if (category == "OTHER") return null

        return PointOfInterest(
            id = placeId,
            name = place.displayName ?: "Unknown Place",
            latLng = location,
            address = place.formattedAddress ?: "",
            category = category,
            rating = place.rating,
            description = null,
            // Photos (New) needs a separate billable resolve call per place and
            // nothing in the UI renders POI photos yet, so skip fetching them.
            photoUrl = null,
            placeId = placeId
        )
    }

    /**
     * Build a description for a place based on available information
     */
    private fun buildDescription(place: Place): String {
        val descriptionParts = mutableListOf<String>()

        place.businessStatus?.let {
            val status = when (it) {
                Place.BusinessStatus.OPERATIONAL -> "Open"
                Place.BusinessStatus.CLOSED_TEMPORARILY -> "Temporarily Closed"
                Place.BusinessStatus.CLOSED_PERMANENTLY -> "Permanently Closed"
                else -> null
            }
            status?.let { status -> descriptionParts.add(status) }
        }

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

        if (place.rating != null && place.userRatingCount != null) {
            descriptionParts.add("${place.rating} stars (${place.userRatingCount} reviews)")
        }

        place.internationalPhoneNumber?.let {
            descriptionParts.add("Phone: $it")
        }

        place.websiteUri?.let {
            descriptionParts.add("Website: $it")
        }

        return descriptionParts.joinToString(" · ")
    }

    /**
     * Surface authorization problems distinctly so callers can show a useful
     * message; wrap everything else as a network-style failure.
     */
    private fun translateApiException(e: ApiException, context: String): Exception {
        val statusMessage = e.status.statusMessage ?: ""
        return if (statusMessage.contains("API key") ||
            statusMessage.contains("not authorized") ||
            e.statusCode == STATUS_DEVELOPER_ERROR
        ) {
            SecurityException("Places API authorization error. Please check API key configuration.")
        } else {
            IOException("$context: ${e.statusCode} ($statusMessage)", e)
        }
    }
}
