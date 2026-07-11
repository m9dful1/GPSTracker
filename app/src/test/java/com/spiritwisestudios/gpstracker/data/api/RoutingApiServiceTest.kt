package com.spiritwisestudios.gpstracker.data.api

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.domain.service.NavigationService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RoutingApiServiceTest {

    // --- buildRequestJson ---

    @Test
    fun `builds request with breaks at ends and through waypoints`() {
        val json = RoutingApiService.buildRequestJson(
            origin = LatLng(37.7, -122.4),
            destination = LatLng(37.8, -122.5),
            waypoints = listOf(LatLng(37.75, -122.45))
        )

        assertEquals("auto", json.getString("costing"))
        assertEquals("kilometers", json.getString("units"))

        val locations = json.getJSONArray("locations")
        assertEquals(3, locations.length())
        assertEquals("break", locations.getJSONObject(0).getString("type"))
        assertEquals(37.7, locations.getJSONObject(0).getDouble("lat"), 1e-9)
        assertEquals(-122.4, locations.getJSONObject(0).getDouble("lon"), 1e-9)
        assertEquals("through", locations.getJSONObject(1).getString("type"))
        assertEquals("break", locations.getJSONObject(2).getString("type"))
    }

    // --- parseRouteResponse ---

    // The polyline decodes to 3 points; at Valhalla's 1e6 precision these are
    // (3.85, -12.02), (4.07, -12.095), (4.3252, -12.6453).
    private val validResponse = """
        {
          "trip": {
            "status": 0,
            "units": "kilometers",
            "summary": { "time": 120.5, "length": 1.5 },
            "legs": [
              {
                "summary": { "time": 120.5, "length": 1.5 },
                "shape": "_p~iF~ps|U_ulLnnqC_mqNvxq`@",
                "maneuvers": [
                  { "type": 1, "instruction": "Drive east on Main Street.", "length": 0.5, "time": 30, "begin_shape_index": 0, "end_shape_index": 1 },
                  { "type": 15, "instruction": "Turn left onto Oak Avenue.", "length": 1.0, "time": 60, "begin_shape_index": 1, "end_shape_index": 2 },
                  { "type": 4, "instruction": "You have arrived at your destination.", "length": 0.0, "time": 0, "begin_shape_index": 2, "end_shape_index": 2 }
                ]
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `parses trip summary into meters and milliseconds`() {
        val route = RoutingApiService.parseRouteResponse(validResponse)

        assertNotNull(route)
        assertEquals(1500f, route!!.distanceMeters, 1e-3f)
        assertEquals(120_500L, route.durationMillis)
    }

    @Test
    fun `decodes shape with polyline6 precision`() {
        val route = RoutingApiService.parseRouteResponse(validResponse)!!

        assertEquals(3, route.points.size)
        assertEquals(3.85, route.points[0].latitude, 1e-6)
        assertEquals(-12.02, route.points[0].longitude, 1e-6)
    }

    @Test
    fun `maps maneuvers to typed instructions anchored on the shape`() {
        val route = RoutingApiService.parseRouteResponse(validResponse)!!

        assertEquals(3, route.instructions.size)

        assertEquals(NavigationService.InstructionType.DEPART, route.instructions[0].type)

        val turn = route.instructions[1]
        assertEquals(NavigationService.InstructionType.TURN_LEFT, turn.type)
        assertEquals("Turn left onto Oak Avenue.", turn.description)
        assertEquals(1000f, turn.distance, 1e-3f)
        assertEquals(route.points[1], turn.maneuverPoint)

        assertEquals(NavigationService.InstructionType.ARRIVE, route.instructions[2].type)
    }

    @Test
    fun `response without trip yields null`() {
        assertNull(RoutingApiService.parseRouteResponse("""{"error":"No path could be found"}"""))
    }

    // --- mapManeuverType ---

    @Test
    fun `maps valhalla maneuver codes onto instruction types`() {
        assertEquals(NavigationService.InstructionType.TURN_RIGHT, RoutingApiService.mapManeuverType(10))
        assertEquals(NavigationService.InstructionType.TURN_SHARP_RIGHT, RoutingApiService.mapManeuverType(12)) // right u-turn
        assertEquals(NavigationService.InstructionType.TURN_SLIGHT_LEFT, RoutingApiService.mapManeuverType(16))
        assertEquals(NavigationService.InstructionType.ROUNDABOUT, RoutingApiService.mapManeuverType(26))
        assertEquals(NavigationService.InstructionType.MERGE, RoutingApiService.mapManeuverType(25))
        assertEquals(NavigationService.InstructionType.HIGHWAY_EXIT, RoutingApiService.mapManeuverType(20))
        assertEquals(NavigationService.InstructionType.STRAIGHT, RoutingApiService.mapManeuverType(8)) // continue
        assertEquals(NavigationService.InstructionType.OTHER, RoutingApiService.mapManeuverType(28)) // ferry
    }
}
