package com.ccarpo.syncbook

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import uniffi.syncbook.BlockKind
import uniffi.syncbook.SyncDoc
import uniffi.syncbook.SyncDocObserver

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
    var trash by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Note?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            runCatching { api.notes(token, trash) }
                .onSuccess { notes = it }
                .onFailure { error = it.message }
        }
    }

    LaunchedEffect(token, trash) { refresh() }
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
            OutlinedButton(onClick = { trash = !trash }) {
                Text(if (trash) "Notes" else "Trash")
            }
        }
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = search,
            onValueChange = { search = it },
            label = { Text("Search") },
            singleLine = true,
        )
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
            items(
                notes.filter {
                    search.isBlank() ||
                        it.title.contains(search, ignoreCase = true) ||
                        it.excerpt.contains(search, ignoreCase = true)
                },
                key = { it.id },
            ) { note ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { selected = note },
                    ) {
                        Text(note.title.ifBlank { "Untitled note" })
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            runCatching {
                                if (trash) api.restoreNote(token, note.id)
                                else api.deleteNote(token, note.id)
                            }
                                .onSuccess { refresh() }
                                .onFailure { error = it.message }
                        }
                    }) {
                        Text(if (trash) "Restore" else "Delete")
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
    var blocks by remember(note.id) { mutableStateOf(doc.blocks()) }
    val api = remember(baseUrl) { ApiClient(client, baseUrl) }
    val scope = rememberCoroutineScope()
    var historyVisible by remember { mutableStateOf(false) }
    var snapshots by remember { mutableStateOf<List<Snapshot>>(emptyList()) }
    var preview by remember { mutableStateOf<List<uniffi.syncbook.Block>?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val renderObserver = remember(doc) {
        object : SyncDocObserver {
            override fun changed() {
                mainHandler.post { blocks = doc.blocks() }
            }
        }
    }
    DisposableEffect(doc) {
        doc.observe(renderObserver)
        onDispose { doc.clearObservers() }
    }
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
                                            Base64.decode(detail.state, Base64.DEFAULT),
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
            if (blocks.isEmpty()) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = "",
                    onValueChange = { value ->
                        if (value.isNotEmpty()) {
                            doc.insertText("", 0u, value)
                            blocks = doc.blocks()
                        }
                    },
                    label = { Text("Start writing") },
                )
            }
            blocks.forEachIndexed { index, block ->
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
                    if (block.kind == BlockKind.PARAGRAPH) {
                        OutlinedButton(onClick = {
                            doc.toggleTaskList(block.id)
                            blocks = doc.blocks()
                        }) { Text("Checklist") }
                    }
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = block.text,
                        singleLine = false,
                        onValueChange = { value ->
                            val newline = value.indexOf('\n')
                            if (newline >= 0) {
                                val before = value.substring(0, newline)
                                val after = value.substring(newline + 1)
                                applyTextEdit(doc, block.id, block.text, before)
                                doc.splitBlock(block.id, before.utf16Length().toUInt())
                                if (after.isNotEmpty()) {
                                    val next = doc.blocks().getOrNull(index + 1)
                                    if (next != null) {
                                        doc.insertText(next.id, 0u, after)
                                    }
                                }
                            } else {
                                applyTextEdit(doc, block.id, block.text, value)
                            }
                            blocks = doc.blocks()
                        },
                    )
                }
            }
        }
    }
}

private fun String.utf16Length(): Int = length

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
