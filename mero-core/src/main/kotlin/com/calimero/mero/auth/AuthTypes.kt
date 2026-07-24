package com.calimero.mero.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Auth-API wire types. Field names are snake_case on the wire (core), carried by explicit
 * [SerialName] annotations while Kotlin properties stay camelCase. Ported 1:1 from mero-js
 * `auth-types.ts`.
 */

@Serializable
data class TokenRequest(
    @SerialName("auth_method") val authMethod: String,
    @SerialName("public_key") val publicKey: String,
    @SerialName("client_name") val clientName: String,
    val permissions: List<String>? = null,
    val timestamp: Long,
    @SerialName("provider_data") val providerData: JsonObject,
)

@Serializable
data class TokenPayload(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    val error: String? = null,
)

@Serializable
data class TokenResponse(
    val data: TokenPayload,
    val error: String? = null,
)

@Serializable
data class RefreshTokenRequest(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
)

/** Core revokes by `client_id` (the request is `{ client_id }`). */
@Serializable
data class RevokeTokenRequest(
    @SerialName("client_id") val clientId: String,
)

@Serializable
data class RevokeTokenResponse(
    val success: Boolean,
    val message: String,
)

@Serializable
data class HealthResponse(
    val status: String,
    val storage: Boolean,
    @SerialName("uptime_seconds") val uptimeSeconds: Long,
)

@Serializable
data class IdentityResponse(
    val service: String,
    val version: String,
    @SerialName("authentication_mode") val authenticationMode: String,
    val providers: List<String> = emptyList(),
)

@Serializable
data class Provider(
    val name: String,
    val type: String,
    val description: String = "",
    val configured: Boolean = false,
    val config: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class ProvidersResponse(
    val providers: List<Provider> = emptyList(),
    val count: Int = 0,
)

@Serializable
data class RootKey(
    @SerialName("key_id") val keyId: String,
    @SerialName("public_key") val publicKey: String,
    @SerialName("auth_method") val authMethod: String,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("revoked_at") val revokedAt: Long? = null,
    val permissions: List<String> = emptyList(),
)

@Serializable
data class ClientKey(
    @SerialName("client_id") val clientId: String,
    @SerialName("root_key_id") val rootKeyId: String,
    val name: String = "",
    val permissions: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("revoked_at") val revokedAt: Long? = null,
    @SerialName("is_valid") val isValid: Boolean = true,
)

/** Mock-token request (CI/testing). Core's shape is snake_case. */
@Serializable
data class MockTokenRequest(
    @SerialName("client_name") val clientName: String,
    val permissions: List<String>? = null,
    @SerialName("node_url") val nodeUrl: String? = null,
    @SerialName("access_token_expiry") val accessTokenExpiry: Long? = null,
    @SerialName("refresh_token_expiry") val refreshTokenExpiry: Long? = null,
)

@Serializable
data class CreateKeyRequest(
    @SerialName("public_key") val publicKey: String,
    @SerialName("auth_method") val authMethod: String,
    @SerialName("provider_data") val providerData: JsonObject = JsonObject(emptyMap()),
    @SerialName("target_node_url") val targetNodeUrl: String? = null,
)

@Serializable
data class CreateKeyResponse(
    val status: Boolean = false,
    val message: String = "",
)

@Serializable
data class DeleteKeyResponse(
    val success: Boolean = false,
    val message: String = "",
)

@Serializable
data class GenerateClientKeyRequest(
    @SerialName("context_id") val contextId: String? = null,
    @SerialName("context_identity") val contextIdentity: String? = null,
    val permissions: List<String>? = null,
    @SerialName("target_node_url") val targetNodeUrl: String? = null,
)

@Serializable
data class DeleteClientResponse(
    val success: Boolean = false,
    val message: String = "",
)

/**
 * Core applies an `{ add, remove }` delta (remove first, then add) — NOT a full-set replacement.
 * A `permissions` field is ignored by core.
 */
@Serializable
data class UpdateKeyPermissionsRequest(
    val add: List<String>? = null,
    val remove: List<String>? = null,
)

@Serializable
data class PermissionPayload(
    val permissions: List<String> = emptyList(),
)

@Serializable
data class PermissionResponse(
    val data: PermissionPayload,
    val error: String? = null,
)

/** Result of validating a token via `HEAD /auth/validate`. */
data class ValidationResult(
    val valid: Boolean,
    val status: Int,
    val headers: Map<String, String>,
)

/** Envelope core wraps most admin/auth payloads in: `{ data, error }`. */
@Serializable
data class ApiEnvelope<T>(
    val data: T? = null,
    val error: String? = null,
)
