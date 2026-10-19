package com.calimero.mero.sample

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.calimero.mero.sample.mock.MockWallet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The CI mock test: Calimero Cloud sign-in end to end with no network. [MainActivity] runs with
 * `mock = true`, so an in-app FakeNode plays the cloud manager and the relay, and the wallet is
 * [MockWallet]: tapping "Continue with Calimero" records the wallet URL instead of opening a
 * browser.
 *
 * The wallet's answer is delivered the way a real one arrives — as a VIEW intent on the
 * `mero-sample://enrol#…` callback, which the singleTop activity receives in `onNewIntent` — built by [MockWallet.approve] /
 * [MockWallet.decline] from the URL the app asked to open.
 */
@RunWith(AndroidJUnit4::class)
class LoginFlowTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private fun launchMock(): ActivityScenario<MainActivity> {
        MockWallet.pendingUrl = null
        val intent =
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .putExtra("mock", true)
        return ActivityScenario.launch(intent)
    }

    private fun waitForTag(
        tag: String,
        timeoutMs: Long = 15_000,
    ) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Tap Continue, wait for the app to ask the (mock) wallet, and deliver its redirect. */
    private fun signIn(
        scenario: ActivityScenario<MainActivity>,
        answer: (String) -> String,
    ) {
        waitForTag("loginTitle")
        composeRule.onNodeWithTag("cloudLoginButton").performClick()
        composeRule.waitUntil(10_000) { MockWallet.pendingUrl != null }
        val callback = answer(MockWallet.pendingUrl!!)
        // Through the real path: the singleTop activity receives it in onNewIntent.
        scenario.onActivity { activity ->
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(callback)).setPackage(activity.packageName))
        }
    }

    @Test
    fun signInOpenChatAndSignOut() {
        val scenario = launchMock()
        signIn(scenario) { MockWallet.approve(it) }

        waitForTag("homeTitle")
        composeRule.onNodeWithTag("homeAccount").assertIsDisplayed()

        // "Try it" → Chat switches to the Chat tab; a Cloud session needs no install, so the
        // spaces list (and its + menu) is there at once.
        composeRule.onNodeWithTag("openChat").performClick()
        waitForTag("chatAdd")

        composeRule.onNodeWithTag("tabHome").performClick()
        waitForTag("signOutButton")
        composeRule.onNodeWithTag("signOutButton").performScrollTo().performClick()
        waitForTag("loginTitle")
    }

    @Test
    fun declinedAtTheWalletStaysSignedOut() {
        val scenario = launchMock()
        signIn(scenario) { MockWallet.decline(it) }

        waitForTag("cloudLoginCancelled")
        composeRule.onNodeWithTag("loginTitle").assertIsDisplayed()
    }

    /** SDK tab → Login component: mero-compose's LoginView, switching between Cloud and a node. */
    @Test
    fun loginComponentSwitchesBetweenCloudAndNode() {
        val scenario = launchMock()
        signIn(scenario) { MockWallet.approve(it) }
        waitForTag("homeTitle")

        composeRule.onNodeWithTag("tabSdk").performClick()
        waitForTag("componentsEntry")
        composeRule.onNodeWithTag("componentsEntry").performClick()

        waitForTag("cloudSignInButton")
        composeRule.onNodeWithText("Welcome to Calimero").assertIsDisplayed()
        // On a small screen (CI's default AVD) the node card pushes these links below the fold.
        composeRule.onNodeWithTag("manualNode").performScrollTo().performClick()

        waitForTag("nodeConnectButton")
        composeRule.onNodeWithText("Connect to a node").assertIsDisplayed()
        // The preview starts on username + password.
        composeRule.onNodeWithTag("nodeUserInput").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("nodePasswordInput").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("useCloud").performScrollTo().performClick()
        waitForTag("cloudSignInButton")
    }
}
