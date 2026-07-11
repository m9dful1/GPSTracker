package com.spiritwisestudios.gpstracker.data.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiApiServiceTest {

    // --- request building ---

    @Test
    fun `request carries the place, the notes, and the guide persona`() {
        val body = JSONObject(
            GeminiApiService.buildRequestBody(
                placeName = "Fort Point",
                category = "HISTORICAL",
                referenceNotes = "A Civil War era fort at the Golden Gate.",
                extraDetails = "Marine Drive, San Francisco"
            )
        )

        val system = body.getJSONObject("system_instruction")
            .getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(system.contains("tour guide"))

        val user = body.getJSONArray("contents").getJSONObject(0)
        assertEquals("user", user.getString("role"))
        val prompt = user.getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(prompt.contains("Fort Point"))
        assertTrue(prompt.contains("Civil War era fort"))
        assertTrue(prompt.contains("Marine Drive"))
    }

    @Test
    fun `request disables thinking for fast cheap scripts`() {
        val body = JSONObject(
            GeminiApiService.buildRequestBody("X", "OTHER", "notes", "")
        )
        val budget = body.getJSONObject("generationConfig")
            .getJSONObject("thinkingConfig").getInt("thinkingBudget")
        assertEquals(0, budget)
    }

    @Test
    fun `overlong reference notes are truncated`() {
        val body = GeminiApiService.buildRequestBody(
            "X", "OTHER", "a".repeat(GeminiApiService.MAX_NOTES_CHARS * 2), ""
        )
        // The whole request stays bounded even with a runaway article
        assertTrue(body.length < GeminiApiService.MAX_NOTES_CHARS * 2)
    }

    // --- response parsing ---

    @Test
    fun `parses the first candidate's text`() {
        val json = """
            {"candidates":[{"content":{"parts":[
                {"text":"Built in 1861, "},{"text":"it never fired a shot."}
            ],"role":"model"},"finishReason":"STOP"}]}
        """.trimIndent()
        assertEquals(
            "Built in 1861, it never fired a shot.",
            GeminiApiService.parseScript(json)
        )
    }

    @Test
    fun `empty or missing candidates parse to null`() {
        assertNull(GeminiApiService.parseScript("""{"candidates":[]}"""))
        assertNull(GeminiApiService.parseScript("""{"promptFeedback":{}}"""))
    }

    // --- output polishing ---

    @Test
    fun `markdown and stray whitespace are stripped`() {
        assertEquals(
            "This fort never fired a single shot in battle.",
            GeminiApiService.polishScript(
                "  **This fort** never fired a  single shot\n\nin battle. "
            )
        )
    }

    @Test
    fun `scripts containing urls are rejected`() {
        assertNull(
            GeminiApiService.polishScript(
                "See more at https://example.com about this interesting place."
            )
        )
    }

    @Test
    fun `degenerate outputs are rejected`() {
        assertNull(GeminiApiService.polishScript(null))
        assertNull(GeminiApiService.polishScript("Ok."))
        assertNull(
            GeminiApiService.polishScript("word ".repeat(GeminiApiService.MAX_SCRIPT_CHARS))
        )
    }

    @Test
    fun `an unconfigured service refuses without a network call`() {
        val service = GeminiApiService(okhttp3.OkHttpClient(), apiKey = "")
        assertFalse(service.isConfigured)
        // writeTourScript short-circuits on isConfigured, so a null return
        // here proves no request was attempted (no server exists to answer)
        kotlinx.coroutines.runBlocking {
            assertNull(service.writeTourScript("X", "OTHER", "notes"))
        }
    }
}
