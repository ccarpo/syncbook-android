package com.ccarpo.syncbook

import android.os.Bundle
import android.content.Context
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import uniffi.syncbook.BlockKind
import uniffi.syncbook.SyncDoc

class MainActivity : ComponentActivity() {
    private val httpClient = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = SessionStore(this)
        setContent {
            var token by remember { mutableStateOf(session.token) }
            var baseUrl by remember { mutableStateOf(session.baseUrl) }
            MaterialTheme {
                if (token == null) {
                    LoginScreen(baseUrl) { newToken ->
                        session.token = newToken
                        token = newToken
                    }
                } else {
                    NotesScreen(
                        client = httpClient,
                        token = token!!,
                        baseUrl = baseUrl,
                        onBaseUrlChanged = {
                            session.baseUrl = it
                            baseUrl = session.baseUrl
                        },
                        context = applicationContext,
                        onLogout = {
                            session.token = null
                            token = null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(baseUrl: String, onAuthenticated: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var registering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Syncbook", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(email, { email = it }, label = { Text("Email") })
        OutlinedTextField(password, { password = it }, label = { Text("Password") })
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    runCatching {
                        val api = ApiClient(OkHttpClient(), baseUrl)
                        if (registering) api.register(email, password) else api.login(email, password)
                    }.onSuccess(onAuthenticated).onFailure { error = it.message }
                }
            },
        ) {
            Text(if (registering) "Register" else "Log in")
        }
        OutlinedButton(onClick = { registering = !registering }) {
            Text(if (registering) "Use existing account" else "Create account")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun NotesScreen(
    client: OkHttpClient,
    token: String,
    baseUrl: String,
    onBaseUrlChanged: (String) -> Unit,
    context: Context,
    onLogout: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val api = remember(baseUrl) { ApiClient(client, baseUrl) }
    var editableBaseUrl by remember(baseUrl) { mutableStateOf(baseUrl) }
    var notes by remember { mutableStateOf<List<Note>>(emptyList()) }
    var selected by remember { mutableStateOf<Note?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            runCatching { api.notes(token) }
                .onSuccess { notes = it }
                .onFailure { error = it.message }
        }
    }

    LaunchedEffect(token) { refresh() }
    DisposableEffect(token) {
        val events = UserEventsTransport(client, baseUrl, token, ::refresh, onLogout)
        events.connect()
        onDispose { events.close() }
    }

    selected?.let { note ->
        EditorScreen(client, token, baseUrl, context, note, onLogout) { selected = null }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    runCatching { api.createNote(token) }
                        .onSuccess { note ->
                            notes = notes + note
                            selected = note
                        }
                        .onFailure { error = it.message }
                }
            }) {
                Text("+")
            }
            OutlinedButton(onClick = onLogout) { Text("Log out") }
        }
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = editableBaseUrl,
            onValueChange = { editableBaseUrl = it },
            label = { Text("Server base URL") },
            singleLine = true,
        )
        OutlinedButton(onClick = { onBaseUrlChanged(editableBaseUrl) }) {
            Text("Save server URL")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn {
            items(notes, key = { it.id }) { note ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { selected = note },
                    ) {
                        Text(note.title.ifBlank { "Untitled note" })
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            runCatching { api.deleteNote(token, note.id) }
                                .onSuccess { refresh() }
                                .onFailure { error = it.message }
                        }
                    }) {
                        Text("Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorScreen(
    client: OkHttpClient,
    token: String,
    baseUrl: String,
    context: Context,
    note: Note,
    onUnauthorized: () -> Unit,
    onBack: () -> Unit,
) {
    val persistence = remember { NotePersistence(context) }
    val doc = remember(note.id) {
        SyncDoc().also { persistence.load(note.id, it) }
    }
    var blocks by remember(note.id) {
        if (doc.blocks().isEmpty()) {
            doc.insertText("", 0u, "")
        }
        mutableStateOf(doc.blocks())
    }
    val api = remember(baseUrl) { ApiClient(client, baseUrl) }
    val scope = rememberCoroutineScope()
    var historyVisible by remember { mutableStateOf(false) }
    var snapshots by remember { mutableStateOf<List<Snapshot>>(emptyList()) }
    var preview by remember { mutableStateOf<List<uniffi.syncbook.Block>?>(null) }
    DisposableEffect(note.id) {
        val transport = SyncTransport(
            client = client,
            baseUrl = baseUrl,
            noteId = note.id,
            token = token,
            doc = doc,
            onUnauthorized = onUnauthorized,
            onStatus = {},
        )
        transport.connect()
        onDispose {
            transport.close()
            persistence.save(note.id, doc)
            doc.close()
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBack) { Text("Back") }
            OutlinedButton(onClick = {
                historyVisible = !historyVisible
                if (!historyVisible) preview = null
            }) { Text(if (historyVisible) "Editor" else "History") }
        }
        if (historyVisible) {
            LaunchedEffect(Unit) {
                runCatching { api.history(token, note.id) }
                    .onSuccess { snapshots = it }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(snapshots, key = { it.id }) { snapshot ->
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = snapshot.excerpt.ifBlank { "Untitled snapshot" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(snapshot.createdAt) },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                runCatching { api.snapshot(token, note.id, snapshot.id) }
                                    .onSuccess { detail ->
                                        val snapshotDoc = SyncDoc()
                                        snapshotDoc.applyUpdate(
                                            Base64.decode(detail.state, Base64.DEFAULT)
                                                .map { it.toUByte() },
                                        )
                                        preview = snapshotDoc.blocks()
                                        snapshotDoc.close()
                                    }
                            }
                        }) { Text("Preview") }
                        Button(onClick = {
                            scope.launch {
                                runCatching {
                                    api.restoreSnapshot(token, note.id, snapshot.id)
                                }.onSuccess {
                                    historyVisible = false
                                }
                            }
                        }) { Text("Restore") }
                    }
                }
            }
            preview?.let { previewBlocks ->
                Text("Read-only preview", style = MaterialTheme.typography.titleMedium)
                previewBlocks.forEach { block ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        if (block.kind == BlockKind.TASK_ITEM) {
                            Checkbox(checked = block.checked, onCheckedChange = null)
                        }
                        Text(block.text, modifier = Modifier.padding(8.dp))
                    }
                }
            }
        } else {
            blocks.forEach { block ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    if (block.kind == BlockKind.TASK_ITEM) {
                        Checkbox(
                            checked = block.checked,
                            onCheckedChange = {
                                doc.setChecked(block.id, it)
                                blocks = doc.blocks()
                            },
                        )
                    }
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = block.text,
                        onValueChange = { value ->
                            applyTextEdit(doc, block.id, block.text, value)
                            blocks = doc.blocks()
                        },
                    )
                }
            }
        }
    }
}

private fun applyTextEdit(doc: SyncDoc, blockId: String, oldText: String, newText: String) {
    var prefix = 0
    while (
        prefix < oldText.length &&
        prefix < newText.length &&
        oldText[prefix] == newText[prefix]
    ) {
        prefix++
    }
    var oldSuffix = oldText.length
    var newSuffix = newText.length
    while (
        oldSuffix > prefix &&
        newSuffix > prefix &&
        oldText[oldSuffix - 1] == newText[newSuffix - 1]
    ) {
        oldSuffix--
        newSuffix--
    }
    val deleteLength = oldSuffix - prefix
    if (deleteLength > 0) {
        doc.deleteText(blockId, prefix.toUInt(), deleteLength.toUInt())
    }
    val inserted = newText.substring(prefix, newSuffix)
    if (inserted.isNotEmpty()) {
        doc.insertText(blockId, prefix.toUInt(), inserted)
    }
}
