package com.spiritwisestudios.gpstracker.data.api

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A geocoder driven over a real socket, rather than by calling its parser.
 *
 * Two things are only checkable here. The **HTTP client itself** — every
 * feature in this app goes through OkHttp, and a major-version upgrade that
 * merely compiles has not been tested. And the **catch blocks**: B4 gave each
 * API service a guarded parse and said openly that the `try` around the
 * request was still unexercised, because there was no test server to fail
 * with. There is one now; it comes with OkHttp.
 */
class GeocodingOverHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var api: GeocodingApiService

    private val bias = LatLng(39.5, -119.8)

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        api = GeocodingApiService(
            OkHttpClient(),
            baseUrl = server.url("/").toString().removeSuffix("/")
        )
    }

    @After
    fun stop() {
        server.close()
    }

    @Test
    fun `a well-formed response becomes results, and the request carries the query`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {"features":[
                      {"geometry":{"coordinates":[-119.81,39.53]},
                       "properties":{"name":"Reno","state":"Nevada","country":"United States"}}
                    ]}
                    """.trimIndent()
                )
                .build()
        )

        val results = api.search("reno", bias, limit = 5)

        assertEquals(1, results.size)
        assertEquals("Reno", results.first().name)
        assertEquals(39.53, results.first().latLng.latitude, 1e-9)

        val request = server.takeRequest()
        assertTrue(request.url.encodedPath.startsWith("/api/"))
        assertEquals("reno", request.url.queryParameter("q"))
        // The bias reaches the server, or nearby places don't rank first
        assertEquals("39.5", request.url.queryParameter("lat"))
        assertTrue(request.headers["User-Agent"]!!.startsWith("GPSTracker-TourGuide"))
    }

    @Test
    fun `an HTML error page served with HTTP 200 is no results, not a crash`() = runTest {
        // What a public geocoder actually returns when it is unhappy, and the
        // case B4's guarded parse exists for. The search sheets have no catch
        // of their own, so anything thrown here reaches the user as a crash.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("<html><head><title>502 Bad Gateway</title></head><body>nginx</body></html>")
                .build()
        )

        assertTrue(api.search("reno", bias, limit = 5).isEmpty())
    }

    @Test
    fun `an error status is no results`() = runTest {
        server.enqueue(MockResponse.Builder().code(503).body("service unavailable").build())

        assertTrue(api.search("reno", bias, limit = 5).isEmpty())
    }

    @Test
    fun `a connection that fails outright is no results`() = runTest {
        // The IOException path: nothing is listening any more
        server.close()

        assertTrue(api.search("reno", bias, limit = 5).isEmpty())
    }

    @Test
    fun `a blank query never reaches the network`() = runTest {
        assertTrue(api.search("   ", bias, limit = 5).isEmpty())
        assertEquals(0, server.requestCount)
    }
}
