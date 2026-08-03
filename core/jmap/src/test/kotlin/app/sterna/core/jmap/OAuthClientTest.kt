package app.sterna.core.jmap

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OAuthClientTest {
    private lateinit var server: MockWebServer
    private val client = OAuthClient()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun metadata() = OAuthMetadata(
        issuer = server.url("/").toString(),
        tokenEndpoint = server.url("/auth/token").toString(),
        deviceAuthorizationEndpoint = server.url("/auth/device").toString(),
    )

    @Test fun startDeviceAuthorization_parsesAndSendsClientAndScope() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"device_code":"DC","user_code":"WDJB-MJHT",
                   "verification_uri":"https://srv/device",
                   "verification_uri_complete":"https://srv/device?code=WDJB-MJHT",
                   "expires_in":1800,"interval":5}""".trimIndent(),
            ),
        )

        val device = client.startDeviceAuthorization(metadata(), "sterna", "scope-a offline_access")

        assertEquals("DC", device.deviceCode)
        assertEquals("WDJB-MJHT", device.userCode)
        assertEquals(5, device.interval)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("client_id=sterna"))
        assertTrue(body.contains("scope=scope-a"))
    }

    @Test fun pollDeviceToken_pendingThenSuccess() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"authorization_pending"}"""))
        assertEquals(DeviceTokenResult.Pending, client.pollDeviceToken(metadata(), "DC", "sterna"))

        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"AT","refresh_token":"RT","expires_in":3600,"token_type":"Bearer"}""",
            ),
        )
        val result = client.pollDeviceToken(metadata(), "DC", "sterna")
        assertTrue(result is DeviceTokenResult.Success)
        assertEquals("AT", (result as DeviceTokenResult.Success).tokens.accessToken)
        assertEquals("RT", result.tokens.refreshToken)
    }

    @Test fun pollDeviceToken_slowDownAndDenied() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"slow_down"}"""))
        assertEquals(DeviceTokenResult.SlowDown, client.pollDeviceToken(metadata(), "DC", "sterna"))

        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"access_denied"}"""))
        val denied = client.pollDeviceToken(metadata(), "DC", "sterna")
        assertTrue(denied is DeviceTokenResult.Failed)
        assertEquals("access_denied", (denied as DeviceTokenResult.Failed).error)
    }

    @Test fun parseError_extractsErrorDescriptionAndAadsts() {
        val parsed = client.parseError(
            """{"error":"invalid_grant","error_description":"AADSTS90094: The grant requires admin permission."}""",
        )
        assertEquals("invalid_grant", parsed?.error)
        assertTrue(parsed?.description?.contains("admin permission") == true)
        assertEquals("AADSTS90094", parsed?.aadstsCode)
    }

    @Test fun parseError_noAadstsWhenAbsent() {
        val parsed = client.parseError(
            """{"error":"expired_token","error_description":"The code expired."}""",
        )
        assertEquals("expired_token", parsed?.error)
        assertNull(parsed?.aadstsCode)
        assertTrue(parsed?.description?.isNotEmpty() == true)
    }

    @Test fun parseError_returnsNullForNonError() {
        assertNull(client.parseError("""{"foo":"bar"}"""))
        assertNull(client.parseError("not json"))
    }

    @Test fun pollDeviceToken_failedCarriesDescriptionAndAadsts() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":"authorization_declined","error_description":"AADSTS650051: app not approved."}""",
            ),
        )
        val result = client.pollDeviceToken(metadata(), "DC", "sterna")
        assertTrue(result is DeviceTokenResult.Failed)
        result as DeviceTokenResult.Failed
        assertEquals("authorization_declined", result.error)
        assertEquals("AADSTS650051", result.aadstsCode)
        assertTrue(result.description.contains("not approved"))
    }

    @Test fun refresh_exchangesRefreshTokenForAccessToken() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"access_token":"AT2","refresh_token":"RT2","expires_in":7200}"""),
        )
        val tokens = client.refresh(server.url("/auth/token").toString(), "RT", "sterna")
        assertEquals("AT2", tokens.accessToken)
        assertEquals(7200, tokens.expiresIn)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("grant_type=refresh_token"))
        assertTrue(body.contains("refresh_token=RT"))
    }

    // ---- A malformed token response must not carry the tokens into the error ----
    //
    // kotlinx.serialization puts a SLICE OF THE OFFENDING JSON in its message, and for a token
    // endpoint that JSON is the access and refresh tokens themselves. That message travels:
    // refresh() -> OAuthTokenRefresher -> jmapAuth -> MailRepository.refresh ->
    // InboxViewModel's `t.message` -> the list's error banner.
    //
    // ⚠ The trigger is a response the parser cannot finish, NOT the `"expires_in": "3600"` a
    // lenient-looking provider sends: this Json coerces that one to the property default without
    // complaining (verified — the test that assumed it threw stayed green while the tokens were
    // never at risk). A truncated body, a proxy's HTML error page with a JSON content type, a
    // number where a string belongs: those throw, and those carry the payload.

    @Test fun refresh_doesNotPutTheTokensInTheErrorOfAMalformedResponse() {
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"SECRET-AT","refresh_token":"SECRET-RT","expires_in":36""",
            ),
        )
        try {
            runBlocking { client.refresh(server.url("/auth/token").toString(), "RT", "sterna") }
            throw AssertionError("expected the malformed response to fail")
        } catch (e: Exception) {
            val text = generateSequence<Throwable>(e) { it.cause }.joinToString(" ") { "${it.javaClass} ${it.message}" }
            assertFalse("the error carried the access token: $text", text.contains("SECRET-AT"))
            assertFalse("the error carried the refresh token: $text", text.contains("SECRET-RT"))
        }
    }

    @Test fun pollDeviceToken_doesNotPutTheTokensInTheErrorOfAMalformedResponse() = runBlocking {
        // Same payload on the device-flow path, whose failure text reaches the connect screen.
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"SECRET-AT","refresh_token":"SECRET-RT","expires_in":36""",
            ),
        )
        val result = client.pollDeviceToken(metadata(), "DC", "sterna")
        assertTrue(result is DeviceTokenResult.Failed)
        val text = (result as DeviceTokenResult.Failed).error + " " + result.description
        assertFalse("the failure carried the access token: $text", text.contains("SECRET-AT"))
        assertFalse("the failure carried the refresh token: $text", text.contains("SECRET-RT"))
    }

    @Test fun startDeviceAuthorization_doesNotPutTheResponseInItsError() {
        server.enqueue(
            MockResponse().setBody(
                """{"device_code":"SECRET-DC","user_code":"UC","verification_uri":"https://s/d","interval":""",
            ),
        )
        try {
            runBlocking { client.startDeviceAuthorization(metadata(), "sterna", "scope-a") }
            throw AssertionError("expected the malformed response to fail")
        } catch (e: Exception) {
            val text = generateSequence<Throwable>(e) { it.cause }.joinToString(" ") { "${it.javaClass} ${it.message}" }
            assertFalse("the error carried the payload: $text", text.contains("SECRET-DC"))
        }
    }
}
