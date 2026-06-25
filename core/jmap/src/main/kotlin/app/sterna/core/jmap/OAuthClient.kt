package app.sterna.core.jmap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** OAuth 2.0 authorization-server metadata (RFC 8414), trimmed to what we use. */
@Serializable
data class OAuthMetadata(
    val issuer: String = "",
    @SerialName("authorization_endpoint") val authorizationEndpoint: String? = null,
    @SerialName("token_endpoint") val tokenEndpoint: String = "",
    @SerialName("device_authorization_endpoint") val deviceAuthorizationEndpoint: String? = null,
) {
    /** True when this server supports the Device Authorization Grant (RFC 8628). */
    val supportsDeviceFlow: Boolean
        get() = !deviceAuthorizationEndpoint.isNullOrBlank() && tokenEndpoint.isNotBlank()
}

/** Response to a device-authorization request (RFC 8628 §3.2). */
@Serializable
data class DeviceAuthorization(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Int = 1800,
    val interval: Int = 5,
)

/** A successful token grant (RFC 6749 §5.1). */
@Serializable
data class OAuthTokens(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("token_type") val tokenType: String = "Bearer",
    /** Present when the `openid` scope was requested; carries the signed-in identity. */
    @SerialName("id_token") val idToken: String? = null,
)

/** One device-token poll outcome (RFC 8628 §3.5). */
sealed interface DeviceTokenResult {
    data class Success(val tokens: OAuthTokens) : DeviceTokenResult
    /** `authorization_pending` — the user hasn't approved yet; keep polling. */
    data object Pending : DeviceTokenResult
    /** `slow_down` — poll less often (increase the interval by 5s, per RFC). */
    data object SlowDown : DeviceTokenResult
    /** Terminal failure: `expired_token`, `access_denied`, or an HTTP/transport error. */
    data class Failed(val error: String) : DeviceTokenResult
}

/**
 * Minimal OAuth 2.0 client for the Device Authorization Grant (RFC 8628) plus
 * refresh-token grants — the flow used to sign a mobile app into a JMAP server
 * (e.g. Stalwart) without ever handling the password. Pure JVM, unit-testable
 * with MockWebServer.
 */
class OAuthClient internal constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    /** Public constructor for app code — shares the JMAP default client + JSON. */
    constructor() : this(JmapClient.defaultHttpClient(), JmapClient.DefaultJson)

    /** Fetch the server's OAuth metadata, or null if it advertises none. */
    suspend fun discoverMetadata(host: String): OAuthMetadata? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(Jmap.oauthMetadataUrlFor(host))
            .header("Accept", "application/json")
            .get()
            .build()
        runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                json.decodeFromString<OAuthMetadata>(response.body?.string().orEmpty())
            }
        }.getOrNull()?.takeIf { it.tokenEndpoint.isNotBlank() }
    }

    /** Begin the device flow: ask the server for a user code + verification URL. */
    suspend fun startDeviceAuthorization(
        metadata: OAuthMetadata,
        clientId: String,
        scope: String,
    ): DeviceAuthorization = withContext(Dispatchers.IO) {
        val endpoint = metadata.deviceAuthorizationEndpoint
            ?: throw JmapException("Server has no device-authorization endpoint")
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", scope)
            .build()
        val request = Request.Builder().url(endpoint).post(form).header("Accept", "application/json").build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw JmapException("Device authorization failed: HTTP ${response.code}", httpCode = response.code)
            }
            json.decodeFromString<DeviceAuthorization>(body)
        }
    }

    /** Poll the token endpoint once for the device grant. */
    suspend fun pollDeviceToken(
        metadata: OAuthMetadata,
        deviceCode: String,
        clientId: String,
    ): DeviceTokenResult = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add("device_code", deviceCode)
            .add("client_id", clientId)
            .build()
        val request = Request.Builder().url(metadata.tokenEndpoint).post(form).header("Accept", "application/json").build()
        runCatching {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    DeviceTokenResult.Success(json.decodeFromString<OAuthTokens>(body))
                } else {
                    when (val error = parseError(body)) {
                        "authorization_pending" -> DeviceTokenResult.Pending
                        "slow_down" -> DeviceTokenResult.SlowDown
                        else -> DeviceTokenResult.Failed(error ?: "HTTP ${response.code}")
                    }
                }
            }
        }.getOrElse { DeviceTokenResult.Failed(it.message ?: "network error") }
    }

    /** Exchange a refresh token for a fresh access token (RFC 6749 §6). */
    suspend fun refresh(
        tokenEndpoint: String,
        refreshToken: String,
        clientId: String,
    ): OAuthTokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .build()
        val request = Request.Builder().url(tokenEndpoint).post(form).header("Accept", "application/json").build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw JmapException("Token refresh failed: HTTP ${response.code}", httpCode = response.code)
            }
            json.decodeFromString<OAuthTokens>(body)
        }
    }

    private fun parseError(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
    }.getOrNull()
}
