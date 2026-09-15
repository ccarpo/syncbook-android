package com.ccarpo.syncbook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@Composable
fun LoginScreen(
    baseUrl: String,
    onBaseUrlChanged: (String) -> Unit,
    onAuthenticated: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var serverUrl by remember(baseUrl) { mutableStateOf(baseUrl) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var registering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Syncbook", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server base URL") },
            singleLine = true,
        )
        OutlinedTextField(email, { email = it }, label = { Text("Email") })
        OutlinedTextField(
            password,
            { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            onClick = {
                if (busy) return@Button
                busy = true
                error = null
                val url = serverUrl.trim().trimEnd('/')
                scope.launch {
                    runCatching {
                        val api = ApiClient(OkHttpClient(), url)
                        if (registering) api.register(email, password) else api.login(email, password)
                    }.onSuccess {
                        onBaseUrlChanged(url)
                        onAuthenticated(it)
                    }.onFailure { error = it.message }
                    busy = false
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
