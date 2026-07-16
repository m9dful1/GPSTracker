package com.spiritwisestudios.gpstracker.data.api

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.domain.service.NavigationService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleRoutingApiServiceTest {

    // Classic polyline5 test vector decoding to three points:
    // (38.5, -120.2), (40.7, -120.95), (43.252, -126.453)
    private val encodedPolyline = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"

    @Test
    fun `request json carries origin, destination, and waypoints`() {
        val body = GoogleRoutingApiService.buildRequestJson(
            origin = LatLng(1.0, 2.0),
            destination = LatLng(3.0, 4.0),
            waypoints = listOf(LatLng(5.0, 6.0))
        )

        assertEquals("DRIVE", body.getString("travelMode"))
        assertEquals("TRAFFIC_AWARE", body.getString("routingPreference"))
        assertEquals(
            1.0,
            body.getJSONObject("origin").getJSONObject("location")
                .getJSONObject("latLng").getDouble("latitude"),
            1e-9
        )
        assertEquals(
            4.0,
            body.getJSONObject("destination").getJSONObject("location")
                .getJSONObject("latLng").getDouble("longitude"),
            1e-9
        )
        assertEquals(1, body.getJSONArray("intermediates").length())
    }

    @Test
    fun `no waypoints means no intermediates in the request`() {
        val body = GoogleRoutingApiService.buildRequestJson(
            LatLng(1.0, 2.0), LatLng(3.0, 4.0), emptyList()
        )
        assertTrue(!body.has("intermediates"))
    }

    @Test
    fun `parses distance, duration, shape, and instructions`() {
        val json = """
            {"routes":[{
              "distanceMeters": 12000,
              "duration": "1234s",
              "polyline": {"encodedPolyline": "$encodedPolyline"},
              "legs": [{
                "steps": [
                  {"distanceMeters": 500,
                   "startLocation": {"latLng": {"latitude": 38.5, "longitude": -120.2}},
                   "navigationInstruction": {"maneuver": "TURN_LEFT",
                                             "instructions": "Turn left onto\n   Main St"}},
                  {"distanceMeters": 300,
                   "startLocation": {"latLng": {"latitude": 40.7, "longitude": -120.95}}}
                ]
              }]
            }]}
        """.trimIndent()

        val route = GoogleRoutingApiService.parseRouteResponse(json)!!

        assertEquals(12000f, route.distanceMeters)
        assertEquals(1_234_000L, route.durationMillis)
        assertEquals(3, route.points.size)
        assertEquals(38.5, route.points.first().latitude, 1e-5)
        assertEquals(-126.453, route.points.last().longitude, 1e-5)

        // The instruction-less continuation step is skipped
        assertEquals(1, route.instructions.size)
        val instruction = route.instructions.first()
        assertEquals(NavigationService.InstructionType.TURN_LEFT, instruction.type)
        assertEquals("Turn left onto Main St", instruction.description) // newline collapsed
        assertEquals(500f, instruction.distance)
        assertEquals(38.5, instruction.maneuverPoint.latitude, 1e-9)
    }

    @Test
    fun `responses without a usable route parse to null`() {
        assertNull(GoogleRoutingApiService.parseRouteResponse("""{"routes":[]}"""))
        assertNull(GoogleRoutingApiService.parseRouteResponse("""{}"""))
        // A route with no polyline can't be drawn or followed
        assertNull(
            GoogleRoutingApiService.parseRouteResponse(
                """{"routes":[{"distanceMeters":1,"duration":"1s","legs":[]}]}"""
            )
        )
    }

    @Test
    fun `maneuver strings bucket onto domain instruction types`() {
        assertEquals(
            NavigationService.InstructionType.TURN_RIGHT,
            GoogleRoutingApiService.mapManeuverType("TURN_RIGHT")
        )
        assertEquals(
            NavigationService.InstructionType.TURN_SLIGHT_LEFT,
            GoogleRoutingApiService.mapManeuverType("FORK_LEFT")
        )
        assertEquals(
            NavigationService.InstructionType.ROUNDABOUT,
            GoogleRoutingApiService.mapManeuverType("ROUNDABOUT_RIGHT")
        )
        assertEquals(
            NavigationService.InstructionType.HIGHWAY_EXIT,
            GoogleRoutingApiService.mapManeuverType("RAMP_RIGHT")
        )
        assertEquals(
            NavigationService.InstructionType.OTHER,
            GoogleRoutingApiService.mapManeuverType("FERRY")
        )
        assertEquals(
            NavigationService.InstructionType.STRAIGHT,
            GoogleRoutingApiService.mapManeuverType("")
        )
    }
}
