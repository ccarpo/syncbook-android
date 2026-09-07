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
    val tags: List<String>,
    val owned: Boolean,
)

data class Snapshot(
    val id: String,
    val createdAt: String,
    val excerpt: String,
)

data class SnapshotDetail(
    val id: String,
    val createdAt: String,
    val excerpt: String,
    val state: String,
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
                tags = note.optJSONArray("tags").toStringList(),
                owned = note.optBoolean("owned", true),
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

    suspend fun setTags(token: String, noteId: String, tags: List<String>): List<String> {
        val response = JSONObject(
            request(
                "PUT",
                "/api/notes/$noteId/tags",
                token,
                JSONObject().put("tags", JSONArray(tags)).toString(),
            ),
        )
        return response.optJSONArray("tags").toStringList()
    }

    suspend fun restoreNote(token: String, id: String) {
        request("POST", "/api/notes/$id/restore", token)
    }

    suspend fun history(token: String, noteId: String): List<Snapshot> {
        val array = JSONArray(request("GET", "/api/notes/$noteId/history", token))
        return (0 until array.length()).map { index ->
            val snapshot = array.getJSONObject(index)
            Snapshot(
                id = snapshot.getString("id"),
                createdAt = snapshot.getString("created_at"),
                excerpt = snapshot.optString("excerpt"),
            )
        }
    }

    suspend fun snapshot(token: String, noteId: String, snapshotId: String): SnapshotDetail {
        val snapshot = JSONObject(request("GET", "/api/notes/$noteId/history/$snapshotId", token))
        return SnapshotDetail(
            id = snapshot.getString("id"),
            createdAt = snapshot.getString("created_at"),
            excerpt = snapshot.optString("excerpt"),
            state = snapshot.getString("state"),
        )
    }

    suspend fun restoreSnapshot(token: String, noteId: String, snapshotId: String) {
        request("POST", "/api/notes/$noteId/history/$snapshotId/restore", token)
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
    tags = optJSONArray("tags").toStringList(),
    owned = optBoolean("owned", true),
)

private fun JSONArray?.toStringList(): List<String> =
    if (this == null) {
        emptyList()
    } else {
        (0 until length()).map { index -> getString(index) }
    }
