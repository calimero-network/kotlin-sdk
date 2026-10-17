package com.calimero.mero.compose

import com.calimero.mero.account.AccountApp
import com.calimero.mero.account.ApplicationIds
import com.calimero.mero.cloud.CloudClient

/**
 * What an app asks a node for at login, and so what its token may do — mero-react `AppMode`.
 * [wire] is the `mode` the node's login page is sent.
 */
enum class AppMode(
    val wire: String,
) {
    /**
     * The auth flow no longer selects a context for the app (mero-react 2.1.0, removed in
     * 3.0.0). Use [MULTI_CONTEXT] and let the app pick its own context.
     */
    @Deprecated("The auth flow no longer selects a context. Use MULTI_CONTEXT and pick the context in the app.")
    SINGLE_CONTEXT("single-context"),

    /** The app manages its own contexts, namespaces, groups and blobs. */
    MULTI_CONTEXT("multi-context"),

    /** Full administrative access. */
    ADMIN("admin"),
}

/**
 * The permission grants requested for the client token at login — mero-react
 * `getPermissionsForMode`.
 *
 * Cores since 0.11.0-rc.9 refuse admin routes a token holds no scope for. `context:subscribe` is
 * what `/sse`, `/sse/subscription` and `/ws` require: without it every event stream answers
 * `403 permission_denied`. `context:delete` is what `DELETE /admin-api/contexts/{id}` requires.
 * `context:list` is in every mode because an app reads `identities-owned` right after login.
 *
 * Public so whatever mints a token for an app on its behalf tests against this, not a hand copy.
 */
@Suppress("DEPRECATION")
fun permissionsFor(mode: AppMode): List<String> =
    when (mode) {
        AppMode.SINGLE_CONTEXT ->
            listOf("context:execute", "context:list", "context:subscribe", "application:list", "blob", "context:alias")
        AppMode.MULTI_CONTEXT ->
            listOf(
                "context:create",
                "context:delete",
                "context:list",
                "context:execute",
                "context:subscribe",
                "application:list",
                "namespace",
                "group",
                "blob",
                "context:alias",
            )
        AppMode.ADMIN -> listOf("admin")
    }

/**
 * What a connection may do, so an app asks *what is allowed* instead of which transport it runs
 * on — mero-react `MeroCapabilities`. A capability that later becomes available to accounts
 * turns true here with no app change.
 */
data class MeroCapabilities(
    /** Found a namespace. */
    val createNamespace: Boolean,
    /** Create a context inside a namespace this connection belongs to. */
    val createContext: Boolean,
    /** Mint an invitation to a namespace. */
    val invite: Boolean,
    /** Upgrade a group's application. A node's only: a relay carries just the first choice. */
    val upgrade: Boolean,
) {
    companion object {
        /** A node login can do everything. */
        val NODE = MeroCapabilities(createNamespace = true, createContext = true, invite = true, upgrade = true)

        /** Nothing, before any connection. */
        val NONE = MeroCapabilities(createNamespace = false, createContext = false, invite = false, upgrade = false)

        /**
         * An account on a relay: it founds a namespace only when the app names its registry
         * package (the founding names the application), signs its own invitations, and cannot
         * upgrade (only a group's first application choice goes through a relay).
         */
        fun account(hasPackage: Boolean): MeroCapabilities =
            MeroCapabilities(createNamespace = hasPackage, createContext = true, invite = true, upgrade = false)
    }
}

/**
 * How a [MeroClient] is configured — mero-react `MeroProviderConfig`.
 *
 * @property allowedNodeUrls origins an SSO callback may authenticate against besides the node the
 *   login was started with ([resolveTrustedNodeUrl]).
 * @property cloudBaseUrl the cloud an account asks which relay serves a namespace; the hosted one by default.
 */
data class MeroAppConfig(
    val mode: AppMode = AppMode.MULTI_CONTEXT,
    /** The app's registry package: an account founds namespaces for it, and its application id is derived from it. */
    val packageName: String? = null,
    /** The version to pin; the registry's latest when null. */
    val packageVersion: String? = null,
    /** The registry the package is published on; [ApplicationIds.DEFAULT_REGISTRY_URL] when null. */
    val registryUrl: String? = null,
    val cloudBaseUrl: String = CloudClient.DEFAULT_BASE_URL,
    val allowedNodeUrls: List<String> = emptyList(),
    /** Request timeout for node clients. */
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    /** The app's registry identity, as the admins take it (mero-react `app`). */
    val app: AccountApp
        get() = AccountApp(packageName, packageVersion, registryUrl ?: ApplicationIds.DEFAULT_REGISTRY_URL)

    companion object {
        /** mero-react's `timeoutMs` default. */
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}
