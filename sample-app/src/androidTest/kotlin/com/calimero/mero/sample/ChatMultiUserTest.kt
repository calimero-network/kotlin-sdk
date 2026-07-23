package com.calimero.mero.sample

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two-user, two-node chat e2e — the Android analog of the Swift `ChatMultiUserTests`. Cross-emulator
 * clipboard does not exist, so the host logs the invite as `MERO_E2E_INVITE=<token>` (scraped from
 * logcat by the harness) and the guest reads it from the `invite` runner arg. Reads `nodeUrl`,
 * `user`, `pass`, `invite` from the instrumentation runner arguments. Excluded from the mock CI.
 */
@RunWith(AndroidJUnit4::class)
class ChatMultiUserTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val args = InstrumentationRegistry.getArguments()
    private val nodeUrl = args.getString("nodeUrl") ?: "http://10.0.2.2:2528"
    private val user = args.getString("user") ?: "dev"
    private val pass = args.getString("pass") ?: "dev-password"
    private val invite = args.getString("invite") ?: ""

    private fun launch(joinInvite: String? = null) {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra("mock", false)
            .putExtra("nodeUrl", nodeUrl)
        if (joinInvite != null) intent.putExtra("invite", joinInvite)
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

    private fun send(text: String) {
        composeRule.onNodeWithTag("messageField").performTextInput(text)
        composeRule.onNodeWithTag("sendMessage").performClick()
    }

    private fun openChannel(space: String, channel: String, timeoutMs: Long) {
        waitForText(space, timeoutMs)
        composeRule.onNodeWithText(space).performClick()
        waitForText(channel, timeoutMs)
        composeRule.onNodeWithText(channel).performClick()
        waitForTag("messageField")
    }

    private fun readClipboard(): String {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var token = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            token = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        }
        return token
    }

    // 1. Host: create space + channel, copy the invite (logged for the harness), post a message.
    @Test
    fun testHostCreateInviteAndPost() {
        launch()
        login()
        composeRule.onNodeWithText("Open Chat").performClick()
        waitForTag("installChat")
        composeRule.onNodeWithTag("installChat").performClick()
        waitForTag("chatAdd", timeoutMs = 90_000)

        composeRule.onNodeWithTag("chatAdd").performClick()
        composeRule.onNodeWithText("New space").performClick()
        composeRule.onNodeWithTag("createField").performTextInput("shared")
        composeRule.onNodeWithText("Create").performClick()
        waitForText("shared")
        composeRule.onNodeWithText("shared").performClick()

        composeRule.onNodeWithTag("channelAdd").performClick()
        composeRule.onNodeWithText("New channel").performClick()
        composeRule.onNodeWithTag("createField").performTextInput("general")
        composeRule.onNodeWithText("Create").performClick()
        waitForText("general", timeoutMs = 30_000)

        // create + copy invite, then log it for the harness to scrape.
        composeRule.onNodeWithTag("channelAdd").performClick()
        composeRule.onNodeWithText("Invite people").performClick()
        waitForText("Copy")
        composeRule.onNodeWithText("Copy").performClick()
        Log.i("MeroE2E", "MERO_E2E_INVITE=${readClipboard()}")
        composeRule.onNodeWithText("Done").performClick()

        // post a message the guest should see
        composeRule.onNodeWithText("general").performClick()
        send("hi from host")
        waitForText("hi from host")
    }

    // 2. Guest: join via the invite, see the host's message, reply.
    @Test
    fun testGuestJoinAndReply() {
        launch(joinInvite = invite)
        login()
        composeRule.onNodeWithText("Open Chat").performClick()
        waitForTag("installChat")
        composeRule.onNodeWithTag("installChat").performClick()
        waitForTag("chatAdd", timeoutMs = 90_000)

        // join with the invite handed in via the runner arg
        composeRule.onNodeWithTag("chatAdd").performClick()
        composeRule.onNodeWithText("Join with invite").performClick()
        composeRule.onNodeWithTag("joinField").performTextInput(invite)
        composeRule.onNodeWithText("Join space").performClick()

        openChannel(space = "shared", channel = "general", timeoutMs = 90_000)
        waitForText("hi from host", timeoutMs = 60_000)
        send("hi from guest")
        waitForText("hi from guest")
    }

    // 3. Host: the guest's reply should sync back.
    @Test
    fun testHostSeesReply() {
        launch()
        login()
        composeRule.onNodeWithText("Open Chat").performClick()
        openChannel(space = "shared", channel = "general", timeoutMs = 30_000)
        waitForText("hi from guest", timeoutMs = 60_000)
    }
}
