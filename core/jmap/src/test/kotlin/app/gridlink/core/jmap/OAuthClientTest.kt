package app.gridlink.core.jmap

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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

        val device = client.startDeviceAuthorization(metadata(), "gridlink", "scope-a offline_access")

        assertEquals("DC", device.deviceCode)
        assertEquals("WDJB-MJHT", device.userCode)
        assertEquals(5, device.interval)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("client_id=gridlink"))
        assertTrue(body.contains("scope=scope-a"))
    }

    @Test fun pollDeviceToken_pendingThenSuccess() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"authorization_pending"}"""))
        assertEquals(DeviceTokenResult.Pending, client.pollDeviceToken(metadata(), "DC", "gridlink"))

        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"AT","refresh_token":"RT","expires_in":3600,"token_type":"Bearer"}""",
            ),
        )
        val result = client.pollDeviceToken(metadata(), "DC", "gridlink")
        assertTrue(result is DeviceTokenResult.Success)
        assertEquals("AT", (result as DeviceTokenResult.Success).tokens.accessToken)
        assertEquals("RT", result.tokens.refreshToken)
    }

    @Test fun pollDeviceToken_slowDownAndDenied() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"slow_down"}"""))
        assertEquals(DeviceTokenResult.SlowDown, client.pollDeviceToken(metadata(), "DC", "gridlink"))

        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"access_denied"}"""))
        val denied = client.pollDeviceToken(metadata(), "DC", "gridlink")
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
        val result = client.pollDeviceToken(metadata(), "DC", "gridlink")
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
        val tokens = client.refresh(server.url("/auth/token").toString(), "RT", "gridlink")
        assertEquals("AT2", tokens.accessToken)
        assertEquals(7200, tokens.expiresIn)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("grant_type=refresh_token"))
        assertTrue(body.contains("refresh_token=RT"))
    }
}
