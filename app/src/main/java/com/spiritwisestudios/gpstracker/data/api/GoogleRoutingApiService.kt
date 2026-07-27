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
 * Fetches turn-by-turn routes from the Google Routes API (computeRoutes —
 * the legacy Directions API is unavailable to new Cloud projects). Needs
 * "Routes API" enabled on the Google Cloud project that owns the
 * MAPS_API_KEY; active only when the map provider setting is Google.
 */
class GoogleRoutingApiService(
    private val httpClient: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL
) : RoutingApi {

    /** False when no MAPS_API_KEY is configured; callers should fall back. */
    val isConfigured: Boolean
        get() = apiKey.isNotBlank()

    companion object {
        private const val DEFAULT_BASE_URL = "https://routes.googleapis.com"

        // The field mask is required; request only what the parser reads
        internal const val FIELD_MASK =
            "routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline," +
                "routes.legs.steps.distanceMeters,routes.legs.steps.startLocation," +
                "routes.legs.steps.navigationInstruction"

        /** Build a computeRoutes request body. */
        internal fun buildRequestJson(
            origin: LatLng,
            destination: LatLng,
            waypoints: List<LatLng>
        ): JSONObject {
            fun waypointJson(point: LatLng) = JSONObject().put(
                "location", JSONObject().put(
                    "latLng", JSONObject()
                        .put("latitude", point.latitude)
                        .put("longitude", point.longitude)
                )
            )

            return JSONObject().apply {
                put("origin", waypointJson(origin))
                put("destination", waypointJson(destination))
                if (waypoints.isNotEmpty()) {
                    put("intermediates", JSONArray(waypoints.map { waypointJson(it) }))
                }
                put("travelMode", "DRIVE")
                put("routingPreference", "TRAFFIC_AWARE")
                put("units", "IMPERIAL")
            }
        }

        /**
         * Parse a computeRoutes response. Throws JSONException on malformed
         * payloads; returns null when the response carries no usable route.
         */
        internal fun parseRouteResponse(json: String): Route? {
            val routes = JSONObject(json).optJSONArray("routes")
            if (routes == null || routes.length() == 0) {
                Timber.e("No routes in Routes API response")
                return null
            }

            val route = routes.getJSONObject(0)
            val distanceMeters = route.optInt("distanceMeters").toFloat()
            // Durations serialize as seconds with an "s" suffix, e.g. "1234s"
            val durationMillis =
                (route.optString("duration", "0s").removeSuffix("s").toDouble() * 1000).toLong()

            val instructions = mutableListOf<NavigationService.NavigationInstruction>()
            val legs = route.optJSONArray("legs") ?: JSONArray()
            for (i in 0 until legs.length()) {
                val steps = legs.getJSONObject(i).optJSONArray("steps") ?: continue
                for (j in 0 until steps.length()) {
                    val step = steps.getJSONObject(j)

                    // Steps without an instruction (plain continuations) are skipped
                    val navigationInstruction = step.optJSONObject("navigationInstruction") ?: continue
                    val description = navigationInstruction.optString("instructions")
                        .replace("\\s+".toRegex(), " ") // instructions can span multiple lines
                        .trim()
                    if (description.isEmpty()) continue

                    val startLatLng = step.getJSONObject("startLocation").getJSONObject("latLng")

                    instructions.add(
                        NavigationService.NavigationInstruction(
                            type = mapManeuverType(navigationInstruction.optString("maneuver")),
                            distance = step.optInt("distanceMeters").toFloat(),
                            description = description,
                            maneuverPoint = LatLng(
                                startLatLng.getDouble("latitude"),
                                startLatLng.getDouble("longitude")
                            )
                        )
                    )
                }
            }

            // Routes API encodes shapes with 5 decimal digits (polyline5)
            val encodedPolyline = route.optJSONObject("polyline")
                ?.optString("encodedPolyline")?.takeIf { it.isNotEmpty() }
            if (encodedPolyline == null) {
                Timber.e("Routes API route has no polyline")
                return null
            }
            val points = Polyline.decode(encodedPolyline)
            if (points.isEmpty()) {
                Timber.e("Routes API polyline decoded to no points")
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
         * [parseRouteResponse], with a body we can't read treated as no route
         * — the same guard [RoutingApiService] carries, for the same reason:
         * the last-resort catch logged it as "unexpected error", and the log
         * is all anyone has when a route silently doesn't appear.
         */
        internal fun parseRouteResponseOrNull(json: String): Route? =
            try {
                parseRouteResponse(json)
            } catch (e: JSONException) {
                Timber.e(e, "Routes API route response could not be parsed")
                null
            }

        /**
         * Map Routes API maneuver strings onto the domain instruction types,
         * mirroring how the Valhalla type codes are bucketed.
         */
        internal fun mapManeuverType(maneuver: String): NavigationService.InstructionType {
            return when (maneuver) {
                "TURN_LEFT" -> NavigationService.InstructionType.TURN_LEFT
                "TURN_RIGHT" -> NavigationService.InstructionType.TURN_RIGHT
                "TURN_SLIGHT_LEFT", "FORK_LEFT" -> NavigationService.InstructionType.TURN_SLIGHT_LEFT
                "TURN_SLIGHT_RIGHT", "FORK_RIGHT" -> NavigationService.InstructionType.TURN_SLIGHT_RIGHT
                "TURN_SHARP_LEFT", "UTURN_LEFT" -> NavigationService.InstructionType.TURN_SHARP_LEFT
                "TURN_SHARP_RIGHT", "UTURN_RIGHT" -> NavigationService.InstructionType.TURN_SHARP_RIGHT
                "ROUNDABOUT_LEFT", "ROUNDABOUT_RIGHT" -> NavigationService.InstructionType.ROUNDABOUT
                "MERGE" -> NavigationService.InstructionType.MERGE
                "RAMP_LEFT", "RAMP_RIGHT" -> NavigationService.InstructionType.HIGHWAY_EXIT
                "DEPART" -> NavigationService.InstructionType.DEPART
                "ARRIVE" -> NavigationService.InstructionType.ARRIVE
                "FERRY", "FERRY_TRAIN" -> NavigationService.InstructionType.OTHER
                else -> NavigationService.InstructionType.STRAIGHT
            }
        }
    }

    override suspend fun getRoute(
        origin: LatLng,
        destination: LatLng,
        waypoints: List<LatLng>
    ): Route? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/directions/v2:computeRoutes")
            .header("X-Goog-Api-Key", apiKey)
            .header("X-Goog-FieldMask", FIELD_MASK)
            .post(
                buildRequestJson(origin, destination, waypoints).toString()
                    .toRequestBody("application/json".toMediaType())
            )
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val responseData = response.body?.string()

                if (!response.isSuccessful) {
                    val errorMessage = responseData?.let {
                        try {
                            JSONObject(it).optJSONObject("error")?.optString("message")
                        } catch (e: JSONException) {
                            null
                        }
                    }
                    Timber.e("Routes API request failed: ${response.code} - ${errorMessage ?: response.message}")
                    return@withContext null
                }

                if (responseData.isNullOrEmpty()) {
                    Timber.e("Empty response from Routes API")
                    return@withContext null
                }

                parseRouteResponseOrNull(responseData)
            }
        } catch (e: IOException) {
            Timber.e(e, "Network error when fetching route")
            null
        } catch (e: Exception) {
            // Still a net: the duration field is parsed with toDouble, which
            // throws NumberFormatException rather than JSONException
            Timber.e(e, "Unexpected error getting route")
            null
        }
    }
}
