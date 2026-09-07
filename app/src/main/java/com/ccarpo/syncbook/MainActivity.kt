package com.ccarpo.syncbook

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import uniffi.syncbook.SyncDoc
import uniffi.syncbook.SyncDocObserver

class MainActivity : ComponentActivity() {
    private val session by lazy { SessionStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var token by remember { mutableStateOf(session.token) }
                var baseUrl by remember { mutableStateOf(session.baseUrl) }
                if (token == null) {
                    LoginScreen(
                        baseUrl = baseUrl,
                        onBaseUrlChanged = {
                            session.baseUrl = it
                            baseUrl = session.baseUrl
                        },
                        onAuthenticated = {
                            session.token = it
                            token = it
                        },
                    )
                } else {
                    NotesScreen(
                        client = httpClient,
                        token = token!!,
                        baseUrl = baseUrl,
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

    private companion object {
        val httpClient = OkHttpClient()
    }
}

private class LocalEditGuard {
    var applying = false
}

@Composable
private fun NotesScreen(
    client: OkHttpClient,
    token: String,
    baseUrl: String,
    context: Context,
    onLogout: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val api = remember(baseUrl) { ApiClient(client, baseUrl) }
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

    BackHandler(enabled = selected != null) { selected = null }
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
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn {
            items(
                notes.filter {
                    search.isBlank() ||
                        it.title.contains(search, ignoreCase = true) ||
                        it.excerpt.contains(search, ignoreCase = true) ||
                        it.tags.any { tag -> tag.contains(search, ignoreCase = true) }
                },
                key = { it.id },
            ) { note ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { selected = note },
                    ) {
                        Column {
                            Text(note.title.ifBlank { "Untitled note" })
                            if (note.tags.isNotEmpty()) {
                                Text(
                                    note.tags.joinToString(" ") { "#$it" },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
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
    val valueState = remember(note.id) {
        mutableStateOf(TextFieldValue(doc.markdown()))
    }
    val localEdit = remember(note.id) { LocalEditGuard() }
    val api = remember(baseUrl) { ApiClient(client, baseUrl) }
    val scope = rememberCoroutineScope()
    var historyVisible by remember { mutableStateOf(false) }
    var snapshots by remember { mutableStateOf<List<Snapshot>>(emptyList()) }
    var preview by remember { mutableStateOf<String?>(null) }
    var tags by remember(note.id) { mutableStateOf(note.tags.joinToString(", ")) }
    var error by remember { mutableStateOf<String?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val renderObserver = remember(doc) {
        object : SyncDocObserver {
            override fun changed() {
                if (localEdit.applying) return
                mainHandler.post {
                    val md = doc.markdown()
                    val currentValue = valueState.value
                    if (md != currentValue.text) {
                        valueState.value = currentValue.copy(
                            text = md,
                            selection = TextRange(
                                currentValue.selection.start.coerceAtMost(md.length),
                                currentValue.selection.end.coerceAtMost(md.length),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun applyMarkdown(newValue: TextFieldValue) {
        valueState.value = newValue
        if (newValue.text != doc.markdown()) {
            localEdit.applying = true
            try {
                doc.setMarkdown(newValue.text)
                val md = doc.markdown()
                if (md != newValue.text) {
                    valueState.value = newValue.copy(
                        text = md,
                        selection = TextRange(
                            newValue.selection.start.coerceAtMost(md.length),
                            newValue.selection.end.coerceAtMost(md.length),
                        ),
                    )
                }
            } finally {
                localEdit.applying = false
            }
        }
    }

    fun toggleChecklist() {
        val value = valueState.value
        val text = value.text
        val cursor = value.selection.start.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)) + 1
        val line = lineAt(text, cursor)
        val prefixLength = when {
            line.startsWith("- [ ] ") || line.startsWith("- [x] ") || line.startsWith("- [X] ") -> 6
            line == "- [ ]" || line == "- [x]" || line == "- [X]" -> 5
            else -> 0
        }
        if (prefixLength > 0) {
            applyMarkdown(
                value.copy(
                    text = text.removeRange(lineStart, lineStart + prefixLength),
                    selection = TextRange(
                        (value.selection.start - prefixLength).coerceAtLeast(0),
                        (value.selection.end - prefixLength).coerceAtLeast(0),
                    ),
                ),
            )
        } else {
            applyMarkdown(
                value.copy(
                    text = text.substring(0, lineStart) + "- [ ] " + text.substring(lineStart),
                    selection = TextRange(
                        value.selection.start + 6,
                        value.selection.end + 6,
                    ),
                ),
            )
        }
    }

    fun toggleChecked() {
        val value = valueState.value
        toggleCheckedLine(value.text, value.selection.start)?.let { text ->
            applyMarkdown(value.copy(text = text))
        }
    }

    fun handleTap(position: Offset) {
        val layout = textLayout ?: return
        val value = valueState.value
        val transform = transformChecklistText(value.text)
        val transformedOffset = layout.getOffsetForPosition(position)
        val glyph = checkboxAtTransformedOffset(transform, transformedOffset)
        if (glyph != null) {
            val originalOffset = transform.offsetMapping.transformedToOriginal(transformedOffset)
            toggleCheckedLine(value.text, originalOffset)?.let { text ->
                applyMarkdown(value.copy(text = text))
            }
        }
    }

    DisposableEffect(note.id) {
        doc.observe(renderObserver)
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
            doc.clearObservers()
            persistence.save(note.id, doc)
            doc.close()
        }
    }
    BackHandler {
        if (historyVisible) {
            historyVisible = false
            preview = null
        } else {
            onBack()
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
            OutlinedButton(onClick = ::toggleChecklist) { Text("Checklist") }
            OutlinedButton(onClick = ::toggleChecked) {
                val current = valueState.value
                val cursor = current.selection.start
                Text(if (isCheckedLine(lineAt(current.text, cursor))) "Undone" else "Done")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = tags,
                onValueChange = { tags = it },
                label = { Text("Tags (comma separated)") },
                singleLine = true,
            )
            OutlinedButton(onClick = {
                scope.launch {
                    runCatching {
                        api.setTags(
                            token,
                            note.id,
                            tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        )
                    }
                        .onSuccess { tags = it.joinToString(", ") }
                        .onFailure { error = it.message }
                }
            }) { Text("Save tags") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (historyVisible) {
            LaunchedEffect(Unit) {
                runCatching { api.history(token, note.id) }
                    .onSuccess { snapshots = it }
                    .onFailure { error = it.message }
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
                                        preview = snapshotDoc.markdown()
                                        snapshotDoc.close()
                                    }
                                    .onFailure { error = it.message }
                            }
                        }) { Text("Preview") }
                        Button(onClick = {
                            scope.launch {
                                runCatching {
                                    api.restoreSnapshot(token, note.id, snapshot.id)
                                }.onSuccess {
                                    historyVisible = false
                                }.onFailure { error = it.message }
                            }
                        }) { Text("Restore") }
                    }
                }
            }
            preview?.let {
                Text("Read-only preview", style = MaterialTheme.typography.titleMedium)
                Text(
                    ChecklistVisualTransformation().filter(
                        AnnotatedString(it),
                    ).text,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            var downPosition: Offset? = null
                            var downTime = 0L
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                val change = event.changes.firstOrNull() ?: continue
                                if (change.pressed && !change.previousPressed) {
                                    downPosition = change.position
                                    downTime = change.uptimeMillis
                                } else if (!change.pressed && change.previousPressed) {
                                    val down = downPosition
                                    if (
                                        down != null &&
                                            (change.position - down).getDistance() <=
                                            viewConfiguration.touchSlop &&
                                            change.uptimeMillis - downTime <
                                            viewConfiguration.longPressTimeoutMillis
                                    ) {
                                        handleTap(change.position)
                                    }
                                    break
                                }
                            }
                        }
                    },
            ) {
                BasicTextField(
                    modifier = Modifier
                        .fillMaxSize(),
                    value = valueState.value,
                    onValueChange = ::applyMarkdown,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    visualTransformation = ChecklistVisualTransformation(),
                    onTextLayout = { textLayout = it },
                    decorationBox = { innerTextField ->
                        Box {
                            if (valueState.value.text.isEmpty()) {
                                Text(
                                    "Start writing…",
                                    style = TextStyle(color = Color.Gray),
                                )
                            }
                            innerTextField()
                        }
                    },
                )
            }
        }
    }
}
