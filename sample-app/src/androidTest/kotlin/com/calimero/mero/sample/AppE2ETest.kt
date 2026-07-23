package com.calimero.mero.sample

import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full-feature end-to-end test against a LIVE node + registry — the Android analog of the Swift
 * `AppE2ETests`. Excluded from the mock CI (run via `-Pandroid.testInstrumentationRunnerArguments`
 * with `notClass`). Reads `nodeUrl`, `user`, `pass` from the instrumentation runner arguments.
 */
@RunWith(AndroidJUnit4::class)
class AppE2ETest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val args = InstrumentationRegistry.getArguments()
    private val nodeUrl = args.getString("nodeUrl") ?: "http://10.0.2.2:2528"
    private val user = args.getString("user") ?: "dev"
    private val pass = args.getString("pass") ?: "dev-password"

    private fun launchExplorer() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra("mock", false)
            .putExtra("nodeUrl", nodeUrl)
        ActivityScenario.launch<MainActivity>(intent)
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 20_000) =
        composeRule.waitUntil(timeoutMs) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String, timeoutMs: Long = 20_000) =
        composeRule.waitUntil(timeoutMs) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun login() {
        waitForTag("loginTitle")
        composeRule.onNodeWithTag("usernameField").performTextInput(user)
        composeRule.onNodeWithTag("passwordField").performTextInput(pass)
        composeRule.onNodeWithTag("loginButton").performClick()
        waitForText("Open Chat")
    }

    @Test
    fun explorerRunsLiveMethod() {
        launchExplorer()
        login()
        composeRule.onNodeWithTag("opList").performScrollToNode(hasText("getContexts"))
        composeRule.onNodeWithText("getContexts").performClick()
        composeRule.onNodeWithText("Run").performClick()
        waitForText("RESPONSE")
    }

    @Test
    fun chatEndToEnd() {
        launchExplorer()
        login()
        composeRule.onNodeWithText("Open Chat").performClick()
        waitForTag("installChat")
        composeRule.onNodeWithTag("installChat").performClick()
        waitForTag("chatAdd", timeoutMs = 90_000)

        // create space
        composeRule.onNodeWithTag("chatAdd").performClick()
        composeRule.onNodeWithText("New space").performClick()
        composeRule.onNodeWithTag("createField").performTextInput("e2e-space")
        composeRule.onNodeWithText("Create").performClick()
        waitForText("e2e-space")
        composeRule.onNodeWithText("e2e-space").performClick()

        // create channel
        waitForTag("channelAdd")
        composeRule.onNodeWithTag("channelAdd").performClick()
        composeRule.onNodeWithText("New channel").performClick()
        composeRule.onNodeWithTag("createField").performTextInput("general")
        composeRule.onNodeWithText("Create").performClick()
        waitForText("general", timeoutMs = 30_000)
        composeRule.onNodeWithText("general").performClick()

        // send + read a message
        waitForTag("messageField")
        composeRule.onNodeWithTag("messageField").performTextInput("e2e hello")
        composeRule.onNodeWithTag("sendMessage").performClick()
        waitForText("e2e hello")
    }
}
