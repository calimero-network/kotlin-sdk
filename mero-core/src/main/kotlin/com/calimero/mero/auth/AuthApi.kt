package com.calimero.mero.auth

import com.calimero.mero.http.HttpClient
import com.calimero.mero.http.deleteJson
import com.calimero.mero.http.getJson
import com.calimero.mero.http.postJson
import com.calimero.mero.http.putJson

/**
 * Thin, typed wrapper over the node's `/auth/…` and `/admin/…` (key-management) endpoints.
 * Ported 1:1 from mero-js `auth-client.ts`. Every method is a suspend function dispatched on IO by
 * the underlying [HttpClient].
 */
class AuthApi(
    private val http: HttpClient,
) {
    // ---- Health & status -------------------------------------------------------------------

    suspend fun getHealth(): HealthResponse =
        http.getJson<ApiEnvelope<HealthResponse>>("/auth/health").data
            ?: error("Health response data is null")

    suspend fun getIdentity(): IdentityResponse =
        http.getJson<ApiEnvelope<IdentityResponse>>("/admin/identity").data
            ?: error("Identity response data is null")

    suspend fun getProviders(): ProvidersResponse =
        http.getJson<ApiEnvelope<ProvidersResponse>>("/auth/providers").data
            ?: error("Providers response data is null")

    // ---- Authentication --------------------------------------------------------------------

    suspend fun generateTokens(request: TokenRequest): TokenResponse =
        http.postJson("/auth/token", request)

    suspend fun refreshToken(request: RefreshTokenRequest): TokenResponse =
        http.postJson("/auth/refresh", request)

    /** Mint a token pair without a provider handshake (CI/testing only). */
    suspend fun generateMockTokens(request: MockTokenRequest): TokenResponse =
        http.postJson("/auth/mock-token", request)

    /**
     * Validate a specific access token via `HEAD /auth/validate`. Returns the raw status/headers so
     * callers can inspect `x-auth-error`. Never throws on a 401 — an invalid token is a normal result.
     */
    suspend fun validateToken(token: String): ValidationResult {
        val res =
            http.execute(
                method = "HEAD",
                path = "/auth/validate",
                headers = mapOf("Authorization" to "Bearer $token"),
            )
        return ValidationResult(valid = res.status == 200, status = res.status, headers = res.headers)
    }

    // ---- Token management ------------------------------------------------------------------

    /** Revoke a client's tokens by `client_id`. Used by logout when a client id is known. */
    suspend fun revokeTokens(request: RevokeTokenRequest): RevokeTokenResponse =
        http
            .postJson<RevokeTokenRequest, ApiEnvelope<RevokeTokenResponse>>("/admin/revoke", request)
            .data ?: error("Revoke tokens response data is null")

    // ---- Key management --------------------------------------------------------------------

    suspend fun listRootKeys(): List<RootKey> =
        http.getJson<ApiEnvelope<List<RootKey>>>("/admin/keys").data ?: emptyList()

    suspend fun createRootKey(request: CreateKeyRequest): CreateKeyResponse =
        http.postJson<CreateKeyRequest, ApiEnvelope<CreateKeyResponse>>("/admin/keys", request).data
            ?: error("Create root key response data is null")

    suspend fun deleteRootKey(keyId: String): DeleteKeyResponse =
        http.deleteJson("/admin/keys/$keyId")

    // ---- Client management -----------------------------------------------------------------

    suspend fun listClientKeys(): List<ClientKey> =
        http.getJson<ApiEnvelope<List<ClientKey>>>("/admin/keys/clients").data ?: emptyList()

    suspend fun generateClientKey(request: GenerateClientKeyRequest): TokenResponse =
        http.postJson("/admin/client-key", request)

    suspend fun deleteClientKey(
        keyId: String,
        clientId: String,
    ): DeleteClientResponse =
        http.deleteJson("/admin/keys/$keyId/clients/$clientId")

    // ---- Permissions -----------------------------------------------------------------------

    suspend fun getKeyPermissions(keyId: String): PermissionResponse =
        http.getJson("/admin/keys/$keyId/permissions")

    /** Core expects an `{ add, remove }` delta, not a `{ permissions }` replacement. */
    suspend fun updateKeyPermissions(
        keyId: String,
        changes: UpdateKeyPermissionsRequest,
    ): PermissionResponse =
        http.putJson("/admin/keys/$keyId/permissions", changes)
}
