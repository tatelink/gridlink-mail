package app.gridlink.core.dav

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Fetching a file the server pointed at, and above all WHO gets sent the password.
 *
 * A calendar attachment's URL is written by whoever sent the invitation, so [DavClient.trusts] is a
 * security boundary and not a convenience: every case below is a way somebody could try to talk this
 * client into posting the user's mail password somewhere it does not belong.
 */
class DavClientFetchTest {
    private lateinit var server: MockWebServer
    private val client = DavClient(OkHttpClient())
    private val credentials = DavCredentials("tate", "secret")

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun theAccountsOwnServerOverHttpsIsTrustedWithThePassword() {
        assertTrue(client.trusts("https://mail.gridlink.me/f.pdf".toHttpUrl(), "mail.gridlink.me"))
        // The stored server is often a full URL rather than a bare host, and means the same thing.
        assertTrue(client.trusts("https://mail.gridlink.me/f.pdf".toHttpUrl(), "https://mail.gridlink.me"))
    }

    @Test fun someoneElsesServerIsNot() {
        assertFalse(client.trusts("https://evil.example/f.pdf".toHttpUrl(), "mail.gridlink.me"))
    }

    @Test fun aHostThatMerelyEndsWithTheOwnServersNameIsNotTheOwnServer() {
        // The suffix-match bug this is here to prevent: `evil-mail.gridlink.me.attacker.test`
        // contains the real host as a substring and is a completely different machine.
        assertFalse(
            client.trusts("https://mail.gridlink.me.attacker.test/f.pdf".toHttpUrl(), "mail.gridlink.me"),
        )
    }

    @Test fun theOwnServerOverPlainHttpIsNotTrustedEither() {
        assertFalse(client.trusts("http://mail.gridlink.me/f.pdf".toHttpUrl(), "mail.gridlink.me"))
    }

    @Test fun anAccountWithNoStoredServerTrustsNothing() {
        assertFalse(client.trusts("https://mail.gridlink.me/f.pdf".toHttpUrl(), null))
        assertFalse(client.trusts("https://mail.gridlink.me/f.pdf".toHttpUrl(), "  "))
    }

    @Test fun aStrangersUrlIsStillFetchedJustWithoutTheHeader() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/pdf; charset=binary")
                .setBody("agenda"),
        )

        // MockWebServer is plain http, so this is the untrusted case by construction.
        val download = client.fetch(server.url("/f.pdf").toString(), credentials, "mail.gridlink.me")

        assertNull(server.takeRequest().getHeader("Authorization"))
        assertEquals("agenda", String(download.bytes))
        // The parameters are dropped: this is handed to an intent as a MIME type.
        assertEquals("application/pdf", download.contentType)
    }

    @Test fun aServerThatSaysNothingAboutTheTypeLeavesItUnknown() = runBlocking {
        // MockWebServer's own default is text/plain, so the header is cleared explicitly.
        server.enqueue(MockResponse().setResponseCode(200).setBody("x").removeHeader("Content-Type"))

        val download = client.fetch(server.url("/f.bin").toString(), null, null)

        // Null, not a guess: the caller falls back to the type the invitation claimed.
        assertNull(download.contentType)
    }

    @Test fun aFileTooBigToOpenIsRefusedRatherThanBuffered() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("0123456789"))
        try {
            runBlocking { client.fetch(server.url("/big.bin").toString(), null, null, maxBytes = 4) }
            throw AssertionError("expected DavException")
        } catch (e: DavException) {
            assertTrue(e.message!!.contains("too large"))
        }
    }

    @Test fun aMissingFileIsAFailureAndNotAnEmptyDownload() {
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            runBlocking { client.fetch(server.url("/gone.pdf").toString(), null, null) }
            throw AssertionError("expected DavException")
        } catch (e: DavException) {
            assertEquals(404, e.code)
        }
    }

    @Test fun somethingThatIsNotAUrlIsRefusedBeforeAnyRequest() {
        try {
            runBlocking { client.fetch("cid:part1@invite", null, null) }
            throw AssertionError("expected DavException")
        } catch (e: DavException) {
            assertTrue(e.message!!.contains("Bad attachment URL"))
        }
    }
}
