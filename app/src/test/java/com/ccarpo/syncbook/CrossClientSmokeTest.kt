package com.ccarpo.syncbook

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.junit.Assume.assumeTrue
import org.junit.Test
import uniffi.syncbook.SyncDoc
import uniffi.syncbook.SyncDocObserver
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossClientSmokeTest {
    @Test
    fun rustClientWritesProseMirrorShapeThroughProxy() {
        val base = System.getenv("CROSS_CLIENT_BASE_URL")
        assumeTrue("Set CROSS_CLIENT_BASE_URL to run the live-server smoke test", !base.isNullOrBlank())
        val client = OkHttpClient()
        val email = "android-${UUID.randomUUID()}@example.com"
        val password = "password123"
        val token = auth(client, base!!, email, password)
        val noteId = createNote(client, base, token)
        val left = SyncDoc()
        val right = SyncDoc()
        left.insertText("", 0u, "")
        connect(client, base, noteId, token, left)
        connect(client, base, noteId, token, right)
        val leftId = left.blocks()[0].id
        left.insertText(leftId, 0u, "Android proxy title")
        assertTrue(waitFor(15_000) { right.blocks().firstOrNull()?.text == "Android proxy title" })
        val notes = Request.Builder()
            .url("$base/api/notes")
            .header("Authorization", "Bearer $token")
            .build()
            .let { request -> client.newCall(request).execute().use { it.body!!.string() } }
        assertTrue(notes.contains("Android proxy title"))
    }

    private fun connect(
        client: OkHttpClient,
        base: String,
        noteId: String,
        token: String,
        doc: SyncDoc,
    ) {
        val url = base.replaceFirst("http", "ws") +
            "/ws?noteId=$noteId&token=$token"
        lateinit var socket: WebSocket
        doc.observe(object : SyncDocObserver {
            override fun changed() {
                val update = doc.encodeStateAsUpdate(null)
                socket.send(
                    ByteString.of(*doc.encodeUpdateMessage(update).map { it.toByte() }.toByteArray()),
                )
            }
        })
        socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(ByteString.of(*doc.syncStep1().map { it.toByte() }.toByteArray()))
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    doc.handleMessage(bytes.toByteArray().map { it.toUByte() }).forEach { reply ->
                        webSocket.send(ByteString.of(*reply.map { it.toByte() }.toByteArray()))
                    }
                }
            },
        )
    }

    private fun auth(client: OkHttpClient, base: String, email: String, password: String): String {
        val request = Request.Builder()
            .url("$base/api/auth/register")
            .post("""{"email":"$email","password":"$password"}"""
                .toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(request).execute().use {
            Regex(""""token":"([^"]+)"""").find(it.body!!.string())!!.groupValues[1]
        }
    }

    private fun createNote(client: OkHttpClient, base: String, token: String): String {
        val request = Request.Builder()
            .url("$base/api/notes")
            .header("Authorization", "Bearer $token")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return client.newCall(request).execute().use {
            Regex(""""id":"([^"]+)"""").find(it.body!!.string())!!.groupValues[1]
        }
    }

    private fun waitFor(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(100)
        }
        return predicate()
    }
}
