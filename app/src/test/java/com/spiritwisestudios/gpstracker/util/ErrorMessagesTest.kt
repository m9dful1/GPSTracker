package com.spiritwisestudios.gpstracker.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

class ErrorMessagesTest {

    @Test
    fun `api key problems point at cloud console`() {
        val message = ErrorMessages.friendlyMessage(
            SecurityException("Places API authorization error: API key rejected"),
            "load nearby places"
        )
        assertTrue(message.contains("Cloud Console"))
        assertTrue(message.contains("load nearby places"))
    }

    @Test
    fun `permission problems point at app settings`() {
        val message = ErrorMessages.friendlyMessage(
            SecurityException("ACCESS_FINE_LOCATION denied"),
            "load nearby places"
        )
        assertTrue(message.contains("permission"))
        assertTrue(message.contains("Settings"))
    }

    @Test
    fun `network failures suggest checking the connection`() {
        val message = ErrorMessages.friendlyMessage(IOException("unreachable"), "save your notes")
        assertTrue(message.contains("connection", ignoreCase = true))
    }

    @Test
    fun `timeouts suggest retrying, including socket timeouts`() {
        // SocketTimeoutException is an IOException; it must land in the
        // timeout branch, not the no-connection one
        listOf(SocketTimeoutException("read timed out"), TimeoutException("timed out")).forEach {
            val message = ErrorMessages.friendlyMessage(it, "load place details")
            assertTrue("wrong branch for ${it.javaClass.simpleName}", message.contains("timed out"))
        }
    }

    @Test
    fun `unknown failures stay generic but name the action`() {
        val message = ErrorMessages.friendlyMessage(IllegalStateException("boom"), "generate the story")
        assertTrue(message.contains("generate the story"))
        assertTrue(!message.contains("boom"))
    }
}
