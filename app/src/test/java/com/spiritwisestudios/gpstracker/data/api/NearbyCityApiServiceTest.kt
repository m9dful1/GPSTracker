package com.spiritwisestudios.gpstracker.data.api

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyCityApiServiceTest {

    private val center = LatLng(36.17, -115.14) // Las Vegas

    @Test
    fun `query asks for named cities and towns around the point`() {
        val query = NearbyCityApiService.buildCitiesQuery(center, 60_000)

        assertTrue(query.contains("around:60000,36.17,-115.14"))
        assertTrue(query.contains("\"place\"=\"city\""))
        assertTrue(query.contains("\"place\"=\"town\""))
        assertTrue(query.contains("[\"name\"]"))
    }

    @Test
    fun `cities parse nearest first with population when tagged`() {
        val json = """
            {"elements":[
              {"type":"node","id":1,"lat":36.0397,"lon":-114.9819,
               "tags":{"place":"city","name":"Henderson","population":"320189"}},
              {"type":"node","id":2,"lat":36.1699,"lon":-115.1398,
               "tags":{"place":"city","name":"Las Vegas","population":"641903"}},
              {"type":"node","id":3,"lat":36.1989,"lon":-115.1175,
               "tags":{"place":"city","name":"North Las Vegas"}}
            ]}
        """.trimIndent()

        val cities = NearbyCityApiService.parseCitiesResponse(json, center)

        assertEquals(3, cities.size)
        assertEquals("Las Vegas", cities.first().name) // nearest to the center
        assertEquals(641_903L, cities.first().population)
        assertEquals(null, cities.first { it.name == "North Las Vegas" }.population)
    }

    @Test
    fun `duplicate place nodes collapse and nameless nodes are dropped`() {
        val json = """
            {"elements":[
              {"type":"node","id":1,"lat":36.17,"lon":-115.14,
               "tags":{"place":"city","name":"Las Vegas"}},
              {"type":"node","id":2,"lat":36.18,"lon":-115.15,
               "tags":{"place":"city","name":"Las Vegas"}},
              {"type":"node","id":3,"lat":36.19,"lon":-115.16,"tags":{"place":"town"}}
            ]}
        """.trimIndent()

        val cities = NearbyCityApiService.parseCitiesResponse(json, center)

        assertEquals(1, cities.size)
        assertEquals("Las Vegas", cities.first().name)
    }

    @Test
    fun `empty responses parse to no cities`() {
        assertTrue(NearbyCityApiService.parseCitiesResponse("""{"elements":[]}""", center).isEmpty())
        assertTrue(NearbyCityApiService.parseCitiesResponse("""{}""", center).isEmpty())
    }
}
