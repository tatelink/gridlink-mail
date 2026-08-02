package app.sterna.core.data.unsubscribe

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the one-click unsubscribe puts on the wire, and — more importantly — what it refuses to do.
 *
 * The tests run against a local [MockWebServer], which speaks plain http, through
 * [UnsubscribeClient.post]; the https-only gate is exercised separately through the public
 * [UnsubscribeClient.oneClick]. The client under test is built by
 * [defaultUnsubscribeHttpClient] — the production one, redirect policy included — so the refusal
 * proved here is the shipped behaviour and not a setting the test invented.
 */
class UnsubscribeClientTest {
    private lateinit var server: MockWebServer
    private val client = UnsubscribeClient()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `a 2xx answer is a successful unsubscribe`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        assertEquals(UnsubscribeResult.Sent, client.post(server.url("/u/abc").toString()))
    }

    @Test fun `the request is a form post carrying RFC 8058's body and nothing else`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        client.post(server.url("/u/abc").toString())

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/u/abc", request.path)
        assertEquals("List-Unsubscribe=One-Click", request.body.readUtf8())
        assertTrue(
            "Content-Type was ${request.getHeader("Content-Type")}",
            request.getHeader("Content-Type")?.startsWith("application/x-www-form-urlencoded") == true,
        )
    }

    /**
     * ⛔ The URL is used VERBATIM — path and query string, byte for byte (decision D6).
     *
     * The subscriber token lives in the query on most large senders, and `LinkCleaner`, which
     * strips tracking parameters everywhere else in the app, is deliberately not applied here:
     * the parameter that looks like tracking IS the thing that identifies which subscription to
     * end. Strip it and the request either fails or, far more often, answers 200 and unsubscribes
     * nobody — while the reader is told it went through. This is the one invariant of the feature
     * that fails silently in the right direction, so it is asserted on the bytes the server saw
     * rather than stated in a comment.
     */
    @Test fun `the whole url is sent, query string included`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        client.post(server.url("/u/abc?token=xyz&list=42").toString())

        val request = server.takeRequest()
        assertEquals("/u/abc?token=xyz&list=42", request.path)
        assertEquals("List-Unsubscribe=One-Click", request.body.readUtf8())
    }

    /**
     * ⛔ The invariant this whole class exists for. The POST goes to a domain named by the sender
     * of an email; carrying the account's credentials there would hand them to a stranger. There
     * is no global interceptor in the project, so this holds as long as nobody routes the call
     * through `JmapClient.postWithRetry`, which demands a token.
     */
    @Test fun `no Authorization header is ever sent`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        client.post(server.url("/u/abc").toString())

        val request = server.takeRequest()
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("Cookie"))
    }

    /**
     * ⛔ A 3xx is a FAILURE, and the decisive assertion is the second one: OkHttp follows a
     * 301/302/303 by re-issuing the call as a GET, which would turn the one request that loads
     * nothing into exactly the page fetch — with its IP address, its User-Agent and its pixel —
     * that this feature exists to avoid (decision D3). Nothing may leave after the redirect.
     */
    @Test fun `a redirect is refused and no second request leaves`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/somewhere-else"))
        server.enqueue(MockResponse().setResponseCode(200))

        val result = client.post(server.url("/u/abc").toString())

        assertEquals(UnsubscribeResult.Failed(UnsubscribeFailure.REDIRECT), result)
        assertEquals("the redirect must not be followed", 1, server.requestCount)
    }

    @Test fun `every 3xx is refused, including the permanent ones`() = runBlocking {
        listOf(301, 302, 303, 307, 308).forEach { code ->
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", "/elsewhere"))
            assertEquals(
                "HTTP $code",
                UnsubscribeResult.Failed(UnsubscribeFailure.REDIRECT),
                client.post(server.url("/u/$code").toString()),
            )
        }
        assertEquals("one request per code, no follow-ups", 5, server.requestCount)
    }

    @Test fun `a server refusal is reported as a refusal`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(
            UnsubscribeResult.Failed(UnsubscribeFailure.REFUSED),
            client.post(server.url("/u/abc").toString()),
        )
    }

    // ---- the https-only gate (decision D4), through the public entry point ----

    @Test fun `an http url is refused before any request leaves`() = runBlocking {
        // The mock server speaks plain http, so its own URL is the cleartext case.
        val url = server.url("/u/abc").toString()
        assertTrue("the mock server must be plain http here", url.startsWith("http://"))

        val result = client.oneClick(url)

        assertEquals(UnsubscribeResult.Failed(UnsubscribeFailure.REFUSED), result)
        assertEquals("nothing may be sent over cleartext", 0, server.requestCount)
    }

    @Test fun `only https may be posted to`() {
        assertTrue(isPostableUnsubscribeUrl("https://list.example.com/u"))
        assertTrue(isPostableUnsubscribeUrl("HTTPS://list.example.com/u"))
        listOf(
            "http://list.example.com/u",
            "ftp://list.example.com/u",
            "mailto:leave@list.example.com",
            "list.example.com/u",
            "",
        ).forEach { url ->
            assertTrue("\"$url\" must not be POSTable", !isPostableUnsubscribeUrl(url))
        }
    }

    /**
     * The witness that [UnsubscribeClient.oneClick] does not short-circuit its https branch: an
     * https URL nothing is listening on comes back as OFFLINE, which only a real connection
     * attempt — through the real client, mapped by [oneClickFailure] — can produce. A version
     * that answered REFUSED without trying would pass every other test in this file.
     */
    @Test fun `an https url that cannot be reached is reported as no network`() = runBlocking {
        // Port 1 is privileged and unbound: the connection is refused immediately.
        val result = client.oneClick("https://127.0.0.1:1/u/abc")

        assertEquals(UnsubscribeResult.Failed(UnsubscribeFailure.OFFLINE), result)
    }

    // ---- the two pure mappings, each pinned on its own ----

    @Test fun `status codes map to what the reader is told`() {
        assertEquals(UnsubscribeResult.Sent, oneClickOutcome(200))
        assertEquals(UnsubscribeResult.Sent, oneClickOutcome(202))
        assertEquals(UnsubscribeResult.Sent, oneClickOutcome(299))
        assertEquals(UnsubscribeResult.Failed(UnsubscribeFailure.REDIRECT), oneClickOutcome(301))
        assertEquals(UnsubscribeResult.Failed(UnsubscribeFailure.REDIRECT), oneClickOutcome(399))
        assertEquals(UnsubscribeResult.Failed(UnsubscribeFailure.REFUSED), oneClickOutcome(400))
        assertEquals(UnsubscribeResult.Failed(UnsubscribeFailure.REFUSED), oneClickOutcome(500))
        assertEquals(UnsubscribeResult.Failed(UnsubscribeFailure.REFUSED), oneClickOutcome(199))
    }

    /** The transport dying is "no network"; anything else keeps the neutral refusal. */
    @Test fun `transport failures are told apart from server refusals`() {
        assertEquals(
            UnsubscribeResult.Failed(UnsubscribeFailure.OFFLINE),
            oneClickFailure(java.net.UnknownHostException("list.example.com")),
        )
        assertEquals(
            UnsubscribeResult.Failed(UnsubscribeFailure.OFFLINE),
            oneClickFailure(java.net.SocketTimeoutException("timeout")),
        )
        assertEquals(
            UnsubscribeResult.Failed(UnsubscribeFailure.OFFLINE),
            oneClickFailure(java.net.ConnectException("refused")),
        )
        // Wrapped, as OkHttp hands them over.
        assertEquals(
            UnsubscribeResult.Failed(UnsubscribeFailure.OFFLINE),
            oneClickFailure(java.io.IOException("wrapped", java.net.SocketException("down"))),
        )
        assertEquals(
            UnsubscribeResult.Failed(UnsubscribeFailure.REFUSED),
            oneClickFailure(javax.net.ssl.SSLException("bad certificate")),
        )
        assertEquals(
            UnsubscribeResult.Failed(UnsubscribeFailure.REFUSED),
            oneClickFailure(IllegalStateException("boom")),
        )
    }
}
