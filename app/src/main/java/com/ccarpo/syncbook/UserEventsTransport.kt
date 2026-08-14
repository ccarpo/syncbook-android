package com.ccarpo.syncbook

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class UserEventsTransport(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val token: String,
    private val onChanged: () -> Unit,
    private val onUnauthorized: () -> Unit,
) : AutoCloseable {
    private var socket: WebSocket? = null

    fun connect() {
        val url = baseUrl.toHttpUrl().newBuilder()
            .scheme(if (baseUrl.startsWith("https://")) "wss" else "ws")
            .addPathSegments("ws/user")
            .addQueryParameter("token", token)
            .build()
        socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("\"notes-changed\"")) onChanged()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (response?.code == 401) onUnauthorized()
                }
            },
        )
    }

    override fun close() {
        socket?.close(1000, "closed")
        socket = null
    }
}
