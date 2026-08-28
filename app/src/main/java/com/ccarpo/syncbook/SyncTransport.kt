package com.ccarpo.syncbook

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import uniffi.syncbook.SyncDoc
import uniffi.syncbook.SyncDocObserver

class SyncTransport(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val noteId: String,
    private val token: String,
    private val doc: SyncDoc,
    private val onUnauthorized: () -> Unit,
    private val onStatus: (Status) -> Unit,
) : AutoCloseable {
    enum class Status {
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
    }

    private var socket: WebSocket? = null
    private var sentStateVector = ByteArray(0)
    private var observing = false
    private var applyingRemoteMessage = false

    private val observer = object : SyncDocObserver {
        override fun changed() {
            if (applyingRemoteMessage) return
            val update = doc.encodeStateAsUpdate(sentStateVector)
            if (update.isNotEmpty()) {
                socket?.send(doc.encodeUpdateMessage(update).toByteString())
                sentStateVector = doc.stateVector()
            }
        }
    }

    fun connect() {
        close()
        if (!observing) {
            doc.observe(observer)
            observing = true
        }
        onStatus(Status.CONNECTING)
        val url = baseUrl.toHttpUrl().newBuilder()
            .scheme(if (baseUrl.startsWith("https://")) "wss" else "ws")
            .addPathSegments("ws")
            .addQueryParameter("noteId", noteId)
            .addQueryParameter("token", token)
            .build()
        socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            listener,
        )
    }

    override fun close() {
        socket?.close(1000, "closed")
        socket = null
        onStatus(Status.DISCONNECTED)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            sentStateVector = doc.stateVector()
            webSocket.send(doc.syncStep1().toByteString())
            onStatus(Status.CONNECTED)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            applyingRemoteMessage = true
            try {
                val replies = doc.handleMessage(bytes.toByteArray())
                replies.forEach { reply ->
                    webSocket.send(reply.toByteString())
                }
            } finally {
                applyingRemoteMessage = false
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (response?.code == 401) {
                onUnauthorized()
            }
            onStatus(Status.DISCONNECTED)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onStatus(Status.DISCONNECTED)
        }
    }
}

private fun ByteArray.toByteString(): ByteString = ByteString.of(*this)
