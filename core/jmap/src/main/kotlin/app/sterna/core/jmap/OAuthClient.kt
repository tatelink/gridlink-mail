package app.sterna.core.jmap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
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
    /** The scopes actually granted (may differ from those requested). */
    @SerialName("scope") val scope: String? = null,
)

/** A parsed OAuth error response (RFC 6749 §5.2). [aadstsCode] is the first AADSTS code
 *  found in [description], when present (Microsoft), e.g. "AADSTS650051". */
data class OAuthError(
    val error: String,
    val description: String = "",
    val aadstsCode: String? = null,
)

/** One device-token poll outcome (RFC 8628 §3.5). */
sealed interface DeviceTokenResult {
    data class Success(val tokens: OAuthTokens) : DeviceTokenResult
    /** `authorization_pending` — the user hasn't approved yet; keep polling. */
    data object Pending : DeviceTokenResult
    /** `slow_down` — poll less often (increase the interval by 5s, per RFC). */
    data object SlowDown : DeviceTokenResult
    /** Terminal failure: `expired_token`, `access_denied`, or an HTTP/transport error.
     *  [description] and [aadstsCode] carry the server's `error_description` detail when present. */
    data class Failed(
        val error: String,
        val description: String = "",
        val aadstsCode: String? = null,
    ) : DeviceTokenResult
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
            decodeGuarded(body, "device authorization", DeviceAuthorization.serializer())
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
                    DeviceTokenResult.Success(decodeGuarded(body, "token", OAuthTokens.serializer()))
                } else {
                    val parsed = parseError(body)
                    when (parsed?.error) {
                        "authorization_pending" -> DeviceTokenResult.Pending
                        "slow_down" -> DeviceTokenResult.SlowDown
                        else -> DeviceTokenResult.Failed(
                            error = parsed?.error ?: "http_${response.code}",
                            description = parsed?.description.orEmpty(),
                            aadstsCode = parsed?.aadstsCode,
                        )
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
            decodeGuarded(body, "token", OAuthTokens.serializer())
        }
    }

    /**
     * Decode an OAuth response WITHOUT letting the payload into the failure.
     *
     * kotlinx.serialization puts a slice of the offending JSON in its message — and on these
     * endpoints that JSON is the access and refresh tokens themselves. The message does not stay
     * in logcat: `refresh` is called from the token refresher, under
     * `MailRepository.jmapAuth`/`refresh`, whose `t.message` the mail list now DISPLAYS. A
     * truncated body, a proxy's HTML error page served as JSON, a number where a string belongs —
     * any of them is enough, and the reader would be shown their own bearer token.
     *
     * So: no excerpt and no cause, exactly like `JmapClient.decodeList` and `postJmap`. [what] is
     * enough to place the failure, and the raw body is available to whoever can attach a debugger.
     */
    private fun <T> decodeGuarded(body: String, what: String, serializer: KSerializer<T>): T =
        try {
            json.decodeFromString(serializer, body)
        } catch (_: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException, and so is what
            // decodeFromString raises on a structurally wrong document.
            throw JmapException("Could not decode the $what response")
        }

    internal fun parseError(body: String): OAuthError? = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        val error = obj["error"]?.jsonPrimitive?.content ?: return@runCatching null
        val description = obj["error_description"]?.jsonPrimitive?.content.orEmpty()
        OAuthError(error, description, AADSTS_REGEX.find(description)?.value)
    }.getOrNull()

    private companion object {
        private val AADSTS_REGEX = Regex("AADSTS\\d+")
    }
}
