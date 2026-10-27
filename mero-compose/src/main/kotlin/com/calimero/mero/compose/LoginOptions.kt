package com.calimero.mero.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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

/**
 * The words and mark [LoginView] shows: every user-visible string on the sign-in screen, so an app
 * can rebrand it completely. Every field has a default, the Calimero copy.
 * (== the Swift SDK's `LoginBranding`, plus the rest of the screen's strings.)
 */
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
    /** The logo tile's side. */
    val logoTileSize: Dp = 76.dp,
    val logoTileCornerRadius: Dp = 22.dp,
    /** The tile's fill; the theme's `primary` when null. */
    val logoTileBackground: Color? = null,
    /** The elevation of the tile's coloured shadow; `0.dp` for none. */
    val logoTileShadow: Dp = 8.dp,
    /** Under the Cloud button ([AccountSignInPanel]): what pressing it does; null hides it. */
    val cloudHint: String? = "Opens the Calimero wallet to approve this device",
    /** Shown when the person declined in the wallet. */
    val cloudCancelled: String = "Sign-in was cancelled in the wallet.",
    /** Shown when the client enrols at a wallet other than the hosted one; given that wallet's URL. */
    val customWalletNote: (walletUrl: String) -> String = { "Enrolling at $it, not the hosted wallet." },
    /** The link from the Cloud sign-in to the node sign-in ([LoginMode.CLOUD_AND_NODE]). */
    val manualNodeLink: String = "Manually enter node URL",
    /** The link from the node sign-in back to the Cloud sign-in. */
    val useCloudLink: String = "Use Calimero Cloud instead",
    /** While local nodes are being discovered. */
    val searchingNodes: String = "Searching for local nodes…",
    /** The tag beside a discovered node. */
    val localNodeTag: String = "local",
    /** The option for typing a URL when nodes were discovered. */
    val manualNodeOption: String = "Enter node URL manually",
    val nodeUrlLabel: String = "Node URL",
    val nodeUrlPlaceholder: String = "http://localhost:4001",
    /** The username and password fields, for [NodeSignInMethod.PASSWORD]. */
    val usernameLabel: String = "Username",
    val usernamePlaceholder: String = "dev",
    val passwordLabel: String = "Password",
    val passwordPlaceholder: String = "Admin password",
    /** The node panel's button. */
    val nodeButton: String = "Connect to node",
    /** The link that searches for local nodes again. */
    val rescanNodes: String = "Rescan local nodes",
)
