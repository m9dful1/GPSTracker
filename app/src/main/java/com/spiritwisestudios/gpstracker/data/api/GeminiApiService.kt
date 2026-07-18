package com.spiritwisestudios.gpstracker.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Writes brief, tour-guide-style narration scripts with the Gemini API,
 * turning reference facts (a Wikipedia intro, OSM details) into something
 * that sounds like a guide talking, not an encyclopedia being read aloud.
 *
 * The key is a free Google AI Studio key supplied at build time (see
 * app/docs/ai_narration.md); without one, [writeTourScript] returns null
 * and callers fall back to plain Wikipedia narration. For a Play Store
 * release this direct-key transport should be swapped for Firebase AI
 * Logic — only this class would change.
 */
class GeminiApiService(
    httpClient: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    // Narration is worthless once the place is behind the listener, so
    // give up quickly and let the Wikipedia fallback speak instead
    private val httpClient = httpClient.newBuilder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    /**
     * A short spoken script about a place, written from the given reference
     * notes, or null when the service is unconfigured, the request fails,
     * or the model's output doesn't survive [polishScript].
     */
    suspend fun writeTourScript(
        placeName: String,
        category: String,
        referenceNotes: String,
        extraDetails: String = ""
    ): String? {
        return requestScript(
            buildRequestBody(placeName, category, referenceNotes, extraDetails),
            label = placeName
        )
    }

    /**
     * A short spoken script of regional color — how people live around
     * [regionName] — for the quiet stretches between sights, or null on
     * the same failure modes as [writeTourScript].
     */
    suspend fun writeWayOfLifeScript(regionName: String, referenceNotes: String): String? {
        return requestScript(
            buildWayOfLifeRequestBody(regionName, referenceNotes),
            label = regionName
        )
    }

    private suspend fun requestScript(requestBody: String, label: String): String? {
        if (!isConfigured) return null

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/v1beta/models/$MODEL:generateContent")
                    .header("x-goog-api-key", apiKey)
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.w("Gemini narration request failed: HTTP ${response.code}")
                        return@withContext null
                    }
                    val body = response.body?.string() ?: return@withContext null
                    polishScript(parseScript(body))
                }
            } catch (e: Exception) {
                Timber.w(e, "Gemini narration failed for $label")
                null
            }
        }
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"

        /** Fast, cheap, and inside the free tier — right for 80-word scripts. */
        private const val MODEL = "gemini-2.5-flash"

        /** Reference notes beyond this add cost without adding better facts. */
        internal const val MAX_NOTES_CHARS = 1500

        /** Longest script worth speaking; anything more is a lecture. */
        internal const val MAX_SCRIPT_CHARS = 700

        // The craft rules here are the trained ones: one theme per stop
        // with at most a couple of supporting details (Ham's thematic
        // interpretation), the visible feature first so the listener's eyes
        // lock on, people and emotion over dates and dimensions, folklore
        // labeled as folklore, and a closing line that provokes a look
        // rather than trailing off on a fact (Tilden: provocation, not
        // instruction).
        internal val SYSTEM_PROMPT = """
            You are a charismatic local tour guide speaking over a car's audio
            as the listener passes a place. The listener has just heard an
            announcement like "On your left: Fort Point." — continue naturally
            from it, without repeating the place name in your first sentence
            and without any greeting.

            Write at most four short, conversational sentences (under 85
            words) built on ONE idea: the single most surprising or memorable
            thing in the reference notes, with at most two supporting details.
            When the notes describe what the place looks like, open with the
            feature the listener can spot from the road, then reveal the story
            behind it. Prefer people, drama, and feeling over dates and
            measurements — give a number only when it startles. If a detail is
            folklore rather than fact, say so ("legend has it"). End with a
            short line that gives the listener something to look for or wonder
            about as they pass, not a trailing fact.

            Use only facts from the notes; when they are thin, say less rather
            than inventing. Write for the ear: short sentences, no
            parentheses, and plain spoken text only — no markdown, no lists,
            no URLs, no stage directions.
        """.trimIndent()

        /**
         * The guide's voice for the stretches between sights: no landmark
         * to point at, so the trained fallback is "way of life" commentary —
         * contemporary life, work, and character of the area — rather than
         * site facts without a site.
         */
        internal val WAY_OF_LIFE_PROMPT = """
            You are a charismatic local tour guide speaking over a car's audio
            during a quiet stretch of road, between sights. The listener has
            just heard an announcement like "While the road is quiet, a
            little about Reno." — continue naturally from it, without
            repeating the region's name in your first sentence and without
            any greeting.

            From the reference notes, sketch what life is like here: what the
            area is known for, what people do, what shaped its character —
            the color a local guide shares between sights. Write at most four
            short, conversational sentences (under 85 words), one idea with
            at most two supporting details. Prefer people, work, food, and
            character over dates and statistics. If a detail is folklore
            rather than fact, say so ("legend has it"). End with something
            the listener can watch for out the window as they drive on.

            Use only facts from the notes; when they are thin, say less rather
            than inventing. Write for the ear: short sentences, no
            parentheses, and plain spoken text only — no markdown, no lists,
            no URLs, no stage directions.
        """.trimIndent()

        /**
         * The generateContent request JSON. Thinking is disabled — a
         * four-sentence script doesn't need it, and the listener is moving.
         */
        internal fun buildRequestBody(
            placeName: String,
            category: String,
            referenceNotes: String,
            extraDetails: String
        ): String {
            val prompt = buildString {
                appendLine("Place: $placeName")
                appendLine("Category: ${category.lowercase()}")
                appendLine("Reference notes:")
                appendLine(referenceNotes.take(MAX_NOTES_CHARS))
                if (extraDetails.isNotBlank()) {
                    appendLine("Extra details:")
                    appendLine(extraDetails.take(300))
                }
            }
            return requestJson(SYSTEM_PROMPT, prompt)
        }

        /** The request JSON for a between-sights regional-color script. */
        internal fun buildWayOfLifeRequestBody(
            regionName: String,
            referenceNotes: String
        ): String {
            val prompt = buildString {
                appendLine("Region: $regionName")
                appendLine("Reference notes:")
                appendLine(referenceNotes.take(MAX_NOTES_CHARS))
            }
            return requestJson(WAY_OF_LIFE_PROMPT, prompt)
        }

        private fun requestJson(systemPrompt: String, userPrompt: String): String {
            fun textParts(text: String) =
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))

            return JSONObject()
                .put("system_instruction", textParts(systemPrompt))
                .put(
                    "contents",
                    JSONArray().put(textParts(userPrompt).put("role", "user"))
                )
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", 0.9)
                        .put("maxOutputTokens", 220)
                        .put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                )
                .toString()
        }

        /** The concatenated text of the first candidate, or null. */
        internal fun parseScript(json: String): String? {
            val candidates = JSONObject(json).optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val parts = candidates.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts")
                ?: return null

            val text = buildString {
                for (i in 0 until parts.length()) {
                    append(parts.getJSONObject(i).optString("text", ""))
                }
            }
            return text.takeIf { it.isNotBlank() }
        }

        /**
         * Model output → something safe to hand to a TTS engine, or null
         * when the output is unusable (too short to be a real script, too
         * long to speak in passing, or containing a URL despite the prompt).
         */
        internal fun polishScript(raw: String?): String? {
            if (raw == null) return null
            val cleaned = raw
                .replace(Regex("[*_`#>]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .removeSurrounding("\"")
            return cleaned.takeIf {
                it.length in 20..MAX_SCRIPT_CHARS && !it.contains("http", ignoreCase = true)
            }
        }
    }
}
