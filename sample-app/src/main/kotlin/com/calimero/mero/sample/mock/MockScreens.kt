package com.calimero.mero.sample.mock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.calimero.mero.sample.ui.Cal
import com.calimero.mero.sample.ui.CalPrimaryButton
import com.calimero.mero.sample.ui.CalTextField
import com.calimero.mero.sample.ui.MeroExplorerTheme

/**
 * Root of the deterministic mock flow. Routes login ⇄ home on [MockViewModel] auth state — the
 * Android analog of the Swift sample's `MeroRootView` under `-uitest-mock`.
 */
@Composable
fun MockRoot(initialNodeUrl: String) {
    MeroExplorerTheme {
        val vm: MockViewModel = viewModel()
        LaunchedEffect(initialNodeUrl) { vm.nodeUrl = initialNodeUrl }
        if (vm.isAuthenticated) {
            MockHomeScreen(vm)
        } else {
            MockLoginScreen(vm)
        }
    }
}

/** Credential login form. Test tags mirror the Swift `LoginView` accessibility identifiers. */
@Composable
fun MockLoginScreen(vm: MockViewModel) {
    var node by remember { mutableStateOf(vm.nodeUrl) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Sign in to Calimero",
            style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
            color = Cal.text,
            modifier = Modifier.testTag("loginTitle"),
        )
        CalTextField(node, { node = it; vm.nodeUrl = it }, "Node URL", Modifier.testTag("nodeURLField"))
        CalTextField(username, { username = it }, "Username", Modifier.testTag("usernameField"))
        CalTextField(password, { password = it }, "Password", Modifier.testTag("passwordField"), secure = true)

        vm.errorMessage?.let { error ->
            Text(error, color = Cal.error, modifier = Modifier.testTag("loginError"))
        }

        CalPrimaryButton(
            text = "Log In",
            onClick = { vm.login(node, username, password) },
            enabled = !vm.isLoading,
            modifier = Modifier.fillMaxWidth().testTag("loginButton"),
        )
    }
}

/** Signed-in screen: session info, a demo RPC button, and logout. Mirrors the Swift `HomeView`. */
@Composable
fun MockHomeScreen(vm: MockViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Signed in",
            style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
            color = Cal.text,
            modifier = Modifier.testTag("homeTitle"),
        )
        Text("Node: ${vm.nodeUrl}", color = Cal.textDim, modifier = Modifier.testTag("homeNodeURL"))
        Text("User: ${vm.username}", color = Cal.textDim, modifier = Modifier.testTag("homeUser"))

        CalPrimaryButton(
            text = "Run sample RPC",
            onClick = { vm.runSampleRpc() },
            modifier = Modifier.fillMaxWidth().testTag("runRpcButton"),
        )
        vm.rpcResult?.let { result ->
            Text(result, color = Cal.lime, modifier = Modifier.testTag("rpcResult"))
        }
        vm.errorMessage?.let { error ->
            Text(error, color = Cal.error, textAlign = TextAlign.Start, modifier = Modifier.testTag("homeError"))
        }

        Spacer(Modifier.weight(1f))
        CalPrimaryButton(
            text = "Log Out",
            onClick = { vm.logout() },
            modifier = Modifier.fillMaxWidth().testTag("logoutButton"),
        )
    }
}
