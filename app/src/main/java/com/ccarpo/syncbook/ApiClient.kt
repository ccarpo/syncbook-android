package com.ccarpo.syncbook

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Note(
    val id: String,
    val title: String,
    val excerpt: String,
    val deleted: Boolean,
)

class ApiClient(
    private val client: OkHttpClient,
    baseUrl: String,
) {
    private val baseUrl = baseUrl.trimEnd('/')
    private val jsonMediaType = "application/json".toMediaType()

    suspend fun login(email: String, password: String): String =
        post("/api/auth/login", JSONObject().put("email", email).put("password", password))
            .getString("token")

    suspend fun register(email: String, password: String): String =
        post("/api/auth/register", JSONObject().put("email", email).put("password", password))
            .getString("token")

    suspend fun notes(token: String, trash: Boolean = false): List<Note> {
        val suffix = if (trash) "?trash=true" else ""
        val body = request("GET", "/api/notes$suffix", token)
        val array = JSONArray(body)
        return (0 until array.length()).map { index ->
            val note = array.getJSONObject(index)
            Note(
                id = note.getString("id"),
                title = note.optString("title"),
                excerpt = note.optString("excerpt"),
                deleted = note.optBoolean("deleted"),
            )
        }
    }

    suspend fun createNote(token: String): Note {
        val note = post("/api/notes", JSONObject(), token)
        return note.toNote()
    }

    suspend fun deleteNote(token: String, id: String) {
        request("DELETE", "/api/notes/$id", token)
    }

    suspend fun restoreNote(token: String, id: String) {
        request("POST", "/api/notes/$id/restore", token)
    }

    private suspend fun post(path: String, payload: JSONObject, token: String? = null): JSONObject {
        return JSONObject(request("POST", path, token, payload.toString()))
    }

    private suspend fun request(
        method: String,
        path: String,
        token: String? = null,
        body: String? = null,
    ): String = suspendCancellableCoroutine { continuation ->
        val url = "$baseUrl$path".toHttpUrl()
        val requestBody = body?.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(url)
            .method(method, requestBody)
            .apply {
                if (token != null) {
                    header("Authorization", "Bearer $token")
                }
            }
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    if (it.isSuccessful) {
                        continuation.resume(text)
                    } else {
                        continuation.resumeWithException(
                            ApiException(it.code, text),
                        )
                    }
                }
            }
        })
    }
}

class ApiException(val statusCode: Int, message: String) : Exception(message)

private fun JSONObject.toNote() = Note(
    id = getString("id"),
    title = optString("title"),
    excerpt = optString("excerpt"),
    deleted = optBoolean("deleted"),
)
