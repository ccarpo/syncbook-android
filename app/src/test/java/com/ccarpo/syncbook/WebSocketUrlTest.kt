package com.ccarpo.syncbook

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class WebSocketUrlTest {
    @Test
    fun userEventsUrlKeepsHttpsScheme() {
        val url = userEventsUrl("https://example.com", "token")

        assertTrue(url.toString().startsWith("https://example.com/ws/user?token="))
    }

    @Test
    fun userEventsUrlSupportsHttpAndTrailingSlash() {
        assertEquals(
            "http://example.com/ws/user?token=token",
            userEventsUrl("http://example.com/", "token").toString(),
        )
    }

    @Test
    fun noteSyncUrlKeepsHttpsScheme() {
        assertEquals(
            "https://example.com/ws?noteId=note&token=token",
            noteSyncUrl("https://example.com", "note", "token").toString(),
        )
    }

    @Test
    fun noteSyncUrlSupportsHttpAndTrailingSlash() {
        assertEquals(
            "http://example.com/ws?noteId=note&token=token",
            noteSyncUrl("http://example.com/", "note", "token").toString(),
        )
    }
}
