package com.spiritwisestudios.gpstracker.data.api

import com.spiritwisestudios.gpstracker.data.api.RoutingApi.Route
import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.domain.service.NavigationService
import com.spiritwisestudios.gpstracker.util.Polyline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException

/**
 * Fetches turn-by-turn routes from a Valhalla routing server (free, no API
 * key). The default instance is the public FOSSGIS server built from
 * OpenStreetMap data; point [baseUrl] at a self-hosted or commercial Valhalla
 * instance to change providers.
 */
class RoutingApiService(
    private val httpClient: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL
) : RoutingApi {

    companion object {
        private const val DEFAULT_BASE_URL = "https://valhalla1.openstreetmap.de"
        private const val USER_AGENT = "GPSTracker-TourGuide/1.0 (educational project)"

        /**
         * Build a Valhalla /route request body. Waypoints go in as "through"
         * locations so the route passes them without splitting into legs.
         */
        internal fun buildRequestJson(
            origin: LatLng,
            destination: LatLng,
            waypoints: List<LatLng>
        ): JSONObject {
            fun locationJson(point: LatLng, type: String) = JSONObject()
                .put("lat", point.latitude)
                .put("lon", point.longitude)
                .put("type", type)

            val locations = JSONArray().apply {
                put(locationJson(origin, "break"))
                waypoints.forEach { put(locationJson(it, "through")) }
                put(locationJson(destination, "break"))
            }

            return JSONObject().apply {
                put("locations", locations)
                put("costing", "auto")
                // Maneuver lengths come back in these units; parsing converts
                // to meters, display formatting stays with DistanceFormatter.
                put("units", "kilometers")
                put("language", "en-US")
            }
        }

        /**
         * Parse a Valhalla /route response. Throws JSONException on malformed
         * payloads; returns null when the response carries no usable trip.
         */
        internal fun parseRouteResponse(json: String): Route? {
            val trip = JSONObject(json).optJSONObject("trip") ?: run {
                Timber.e("No trip in Valhalla response")
                return null
            }

            val summary = trip.getJSONObject("summary")
            val distanceMeters = (summary.getDouble("length") * 1000).toFloat()
            val durationMillis = (summary.getDouble("time") * 1000).toLong()

            val points = mutableListOf<LatLng>()
            val instructions = mutableListOf<NavigationService.NavigationInstruction>()

            val legs = trip.getJSONArray("legs")
            for (i in 0 until legs.length()) {
                val leg = legs.getJSONObject(i)
                // Valhalla encodes shapes with 6 decimal digits (polyline6)
                val shape = Polyline.decode(leg.getString("shape"), Polyline.PRECISION_6)

                val maneuvers = leg.optJSONArray("maneuvers") ?: JSONArray()
                for (j in 0 until maneuvers.length()) {
                    val maneuver = maneuvers.getJSONObject(j)
                    val description = maneuver.optString("instruction").trim()
                    if (description.isEmpty()) continue

                    val shapeIndex = maneuver.optInt("begin_shape_index")
                        .coerceIn(0, shape.size - 1)

                    instructions.add(
                        NavigationService.NavigationInstruction(
                            type = mapManeuverType(maneuver.optInt("type")),
                            distance = (maneuver.optDouble("length", 0.0) * 1000).toFloat(),
                            description = description,
                            maneuverPoint = shape[shapeIndex]
                        )
                    )
                }

                points.addAll(shape)
            }

            if (points.isEmpty()) {
                Timber.e("Valhalla trip has no shape points")
                return null
            }

            return Route(
                points = points,
                distanceMeters = distanceMeters,
                durationMillis = durationMillis,
                instructions = instructions
            )
        }

        /**
         * [parseRouteResponse], with a body we can't read treated as no
         * route.
         *
         * [getRoute]'s last-resort catch already turned this into null, but it
         * logged it as "unexpected error", which is the wrong word for the
         * ordinary behaviour of a free public server under load — and the log
         * is all anyone has when a route silently doesn't appear.
         */
        internal fun parseRouteResponseOrNull(json: String): Route? =
            try {
                parseRouteResponse(json)
            } catch (e: JSONException) {
                Timber.e(e, "Valhalla route response could not be parsed")
                null
            }

        /**
         * Map Valhalla maneuver type codes onto the domain instruction types,
         * mirroring how the Google maneuver strings used to be bucketed.
         */
        internal fun mapManeuverType(type: Int): NavigationService.InstructionType {
            return when (type) {
                1, 2, 3 -> NavigationService.InstructionType.DEPART
                4, 5, 6 -> NavigationService.InstructionType.ARRIVE
                9, 23 -> NavigationService.InstructionType.TURN_SLIGHT_RIGHT
                10 -> NavigationService.InstructionType.TURN_RIGHT
                11, 12 -> NavigationService.InstructionType.TURN_SHARP_RIGHT
                13, 14 -> NavigationService.InstructionType.TURN_SHARP_LEFT
                15 -> NavigationService.InstructionType.TURN_LEFT
                16, 24 -> NavigationService.InstructionType.TURN_SLIGHT_LEFT
                17, 18, 19, 20, 21 -> NavigationService.InstructionType.HIGHWAY_EXIT
                25, 37, 38 -> NavigationService.InstructionType.MERGE
                26, 27 -> NavigationService.InstructionType.ROUNDABOUT
                28, 29 -> NavigationService.InstructionType.OTHER // ferries
                else -> NavigationService.InstructionType.STRAIGHT
            }
        }
    }

    /**
     * Compute a route, or null when the server is unreachable or returns
     * nothing usable. Waypoints are routed through in the given order.
     */
    override suspend fun getRoute(
        origin: LatLng,
        destination: LatLng,
        waypoints: List<LatLng>
    ): Route? = withContext(Dispatchers.IO) {
        val requestJson = buildRequestJson(origin, destination, waypoints)

        val request = Request.Builder()
            .url("$baseUrl/route")
            .header("User-Agent", USER_AGENT)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val responseData = response.body?.string()

                if (!response.isSuccessful) {
                    // Valhalla errors carry error/error_code fields
                    val errorMessage = responseData?.let {
                        try {
                            JSONObject(it).optString("error")
                        } catch (e: JSONException) {
                            null
                        }
                    }
                    Timber.e("Routing request failed: ${response.code} - ${errorMessage ?: response.message}")
                    return@withContext null
                }

                if (responseData.isNullOrEmpty()) {
                    Timber.e("Empty response from routing server")
                    return@withContext null
                }

                parseRouteResponseOrNull(responseData)
            }
        } catch (e: IOException) {
            Timber.e(e, "Network error when fetching route")
            null
        } catch (e: Exception) {
            // Still a net: the parse can also trip over a numeric field that
            // isn't a number, which is not a JSONException
            Timber.e(e, "Unexpected error getting route")
            null
        }
    }
}
