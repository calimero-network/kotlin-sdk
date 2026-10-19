package com.calimero.mero.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector

/** Which ways to sign in [LoginView] offers. (== the Swift SDK's `LoginMode`.) */
enum class LoginMode {
    /** Calimero Cloud, with a "Manually enter node URL" link under it. */
    CLOUD_AND_NODE,

    /** Calimero Cloud alone. */
    CLOUD_ONLY,

    /** A node URL alone (a node the user runs). */
    NODE_ONLY,
    ;

    /** The [LoginModalTab]s this mode offers, first shown first. */
    val tabs: List<LoginModalTab>
        get() =
            when (this) {
                CLOUD_AND_NODE -> listOf(LoginModalTab.CLOUD, LoginModalTab.NODE)
                CLOUD_ONLY -> listOf(LoginModalTab.CLOUD)
                NODE_ONLY -> listOf(LoginModalTab.NODE)
            }

    companion object {
        /** The mode that offers [tabs] (the older `tabs =` parameter); no tabs means Cloud. */
        fun from(tabs: List<LoginModalTab>): LoginMode {
            val hasCloud = LoginModalTab.CLOUD in tabs || tabs.isEmpty()
            val hasNode = LoginModalTab.NODE in tabs
            return when {
                hasCloud && hasNode -> CLOUD_AND_NODE
                hasNode -> NODE_ONLY
                else -> CLOUD_ONLY
            }
        }
    }
}

/** How a node sign-in authenticates once its URL is chosen. (== the Swift SDK's `NodeSignInMethod`.) */
enum class NodeSignInMethod {
    /** Open the node's own login page (a Custom Tab); the app never sees a password. The default. */
    LOGIN_PAGE,

    /** Admin username and password typed into the app ([MeroClient.login]) — for development nodes (a local `merod`). */
    PASSWORD,
}

/** The words and mark [LoginView] shows. Every field has a default. (== the Swift SDK's `LoginBranding`.) */
@Immutable
data class LoginBranding(
    /** Over the Cloud sign-in. */
    val cloudTitle: String = "Welcome to Calimero",
    val cloudSubtitle: String = "Private, peer-to-peer apps. Sign in with your passkey; your account key never leaves the wallet.",
    /** Over the node sign-in. */
    val nodeTitle: String = "Connect to a node",
    val nodeSubtitle: String = "Sign in to a Calimero node you run.",
    /** The Cloud button's label. */
    val cloudButton: String = "Continue with Calimero Cloud",
    /** The mark in the tile at the top; the Calimero logo when null. */
    val logo: ImageVector? = null,
)
