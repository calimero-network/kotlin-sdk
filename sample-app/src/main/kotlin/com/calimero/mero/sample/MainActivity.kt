package com.calimero.mero.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calimero.mero.compose.ConnectButton
import com.calimero.mero.compose.LoginSheet
import com.calimero.mero.compose.MeroClient
import com.calimero.mero.compose.MeroProvider

/**
 * Minimal Compose sample: enter a node URL, log in with credentials, then the authed screen shows a
 * [ConnectButton] (logout). Also consumes an SSO deep-link callback if the app was opened by one.
 */
class MainActivity : ComponentActivity() {

    private var pendingCallbackUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingCallbackUrl = intent?.data?.toString()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SampleApp(initialCallbackUrl = pendingCallbackUrl)
                }
            }
        }
    }
}

@Composable
private fun SampleApp(initialCallbackUrl: String?) {
    val context = LocalContext.current
    var nodeUrl by remember { mutableStateOf("http://10.0.2.2:2528") }
    var client by remember { mutableStateOf<MeroClient?>(null) }

    val activeClient = client
    if (activeClient == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Connect to a Calimero node", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = nodeUrl,
                onValueChange = { nodeUrl = it },
                label = { Text("Node URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val created = MeroClient.create(context, nodeUrl.trim())
                    initialCallbackUrl?.let { created.handleAuthCallback(it) }
                    client = created
                },
                enabled = nodeUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Continue") }
        }
        return
    }

    MeroProvider(activeClient) {
        val state by activeClient.state.collectAsStateWithLifecycle()
        if (state.isAuthenticated) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Authenticated", style = MaterialTheme.typography.titleLarge)
                state.nodeUrl?.let { Text("Node: $it") }
                state.applicationId?.let { Text("App: $it") }
                ConnectButton()
            }
        } else {
            LoginSheet(showBootstrapSecret = true)
        }
    }
}
