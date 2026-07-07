package com.spiritwisestudios.gpstracker.util

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

/**
 * Maps failures to short, actionable user-facing messages. Raw exception
 * text belongs in the log; the user gets told what happened and what to
 * do about it.
 */
object ErrorMessages {

    /**
     * @param whatFailed the action that failed, in plain words the user
     *   chose to do ("load nearby places", "save your notes")
     */
    fun friendlyMessage(error: Throwable, whatFailed: String): String {
        return when {
            error is SecurityException && mentionsApiKey(error) ->
                "Couldn't $whatFailed — the Google API key isn't authorized. " +
                    "Check the key's restrictions in Google Cloud Console."
            error is SecurityException ->
                "Couldn't $whatFailed — location permission is missing. " +
                    "Grant it in Settings → Apps → GPSTracker → Permissions."
            error is SocketTimeoutException || error is TimeoutException ->
                "Couldn't $whatFailed — the request timed out. Try again in a moment."
            error is IOException ->
                "Couldn't $whatFailed — no connection. Check your internet and try again."
            else ->
                "Couldn't $whatFailed. Please try again."
        }
    }

    private fun mentionsApiKey(error: Throwable): Boolean {
        val message = error.message ?: return false
        return listOf("API key", "authorization", "not authorized").any {
            message.contains(it, ignoreCase = true)
        }
    }
}
