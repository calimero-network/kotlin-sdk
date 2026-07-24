package com.calimero.mero.sample

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The CI mock test — the Android analog of the Swift `LoginFlowUITests`. Launches [MainActivity]
 * with the `mock = true` intent extra so the app runs its in-app FakeNode: no live node needed.
 *
 * Intent-extra passing: because [createEmptyComposeRule] does not launch an activity itself, we
 * launch [MainActivity] via [ActivityScenario] with an explicit [Intent] carrying `mock = true`,
 * and drive the resulting composition through the empty compose rule.
 */
@RunWith(AndroidJUnit4::class)
class LoginFlowTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private fun launchMock() {
        val intent =
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .putExtra("mock", true)
        ActivityScenario.launch<MainActivity>(intent)
    }

    private fun waitForTag(
        tag: String,
        timeoutMs: Long = 10_000,
    ) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun loginRunRpcAndLogout() {
        launchMock()

        waitForTag("loginTitle")
        composeRule.onNodeWithTag("usernameField").performTextInput("dev")
        composeRule.onNodeWithTag("passwordField").performTextInput("dev-password")
        composeRule.onNodeWithTag("loginButton").performClick()

        waitForTag("homeTitle")
        composeRule.onNodeWithTag("homeUser").assertTextContains("dev", substring = true)

        composeRule.onNodeWithTag("runRpcButton").performClick()
        waitForTag("rpcResult")

        composeRule.onNodeWithTag("logoutButton").performClick()
        waitForTag("loginTitle")
    }

    @Test
    fun validationErrorOnEmptyCredentials() {
        launchMock()

        waitForTag("loginTitle")
        composeRule.onNodeWithTag("loginButton").performClick()

        waitForTag("loginError")
        composeRule.onNodeWithTag("loginTitle").assertIsDisplayed()
    }
}
