package com.calimero.mero.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * A credential login form bound to the ambient [MeroClient]. Kotlin analogue of mero-react's
 * `LoginModal` (credential path). Shows inline loading + error and calls back on success.
 *
 * @param showBootstrapSecret surface the first-login setup code field (core#3221).
 */
@Composable
fun LoginSheet(
    modifier: Modifier = Modifier,
    client: MeroClient = useMero(),
    showBootstrapSecret: Boolean = false,
    onAuthenticated: () -> Unit = {},
) {
    val state by client.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var bootstrapSecret by remember { mutableStateOf("") }

    if (state.isAuthenticated) {
        onAuthenticated()
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Sign in", style = MaterialTheme.typography.titleLarge)
        state.nodeUrl?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        if (showBootstrapSecret) {
            OutlinedTextField(
                value = bootstrapSecret,
                onValueChange = { bootstrapSecret = it },
                label = { Text("Setup code (first login only)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                scope.launch {
                    client.login(username, password, bootstrapSecret.ifBlank { null })
                }
            },
            enabled = !state.isLoading && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            }
            Text("Log in")
        }
    }
}
