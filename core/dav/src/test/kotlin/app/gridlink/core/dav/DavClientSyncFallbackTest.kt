package app.gridlink.core.dav

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
 * Syncing a collection from a server with no sync-collection REPORT.
 *
 * RFC 6578 is optional, and a server that does not implement it refuses the REPORT itself
 * (SmarterMail answers 400). The client must still be able to read the collection: PROPFIND
 * to list it, GET for each member, reported as a full resync with no token, which is the
 * shape the repository already knows how to apply. This pins the fallback's trigger (a 400
 * on the first REPORT), the request sequence it produces, and that the collection's own
 * entry in the listing is not fetched as if it were a contact.
 */
class DavClientSyncFallbackTest {
    private lateinit var server: MockWebServer
    private val client = DavClient()
    private val credentials = DavCredentials("mmchugh@silverapple.eu", "secret")

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun collection() = server.url("/dav/addressbooks/me/").toString()

    private fun multiStatus(body: String) = MockResponse().setResponseCode(207)
        .setHeader("Content-Type", "application/xml; charset=utf-8")
        .setBody(body)

    @Test fun aRefusedSyncReportFallsBackToListingAndGet() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(
            multiStatus(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:">
                  <D:response>
                    <D:href>/dav/addressbooks/me/</D:href>
                    <D:propstat><D:prop>
                      <D:resourcetype><D:collection/></D:resourcetype>
                    </D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                  </D:response>
                  <D:response>
                    <D:href>/dav/addressbooks/me/alice.vcf</D:href>
                    <D:propstat><D:prop><D:getetag>"e1"</D:getetag></D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                  </D:response>
                  <D:response>
                    <D:href>/dav/addressbooks/me/bob.vcf</D:href>
                    <D:propstat><D:prop><D:getetag>"e2"</D:getetag></D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                  </D:response>
                </D:multistatus>
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("ETag", "\"e1\"")
                .setBody("BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Alice\r\nEND:VCARD\r\n"),
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("ETag", "\"e2\"")
                .setBody("BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Bob\r\nEND:VCARD\r\n"),
        )

        val result = client.sync(collection(), credentials, DavKind.ADDRESS_BOOK, token = null)

        assertTrue(result.fullResync)
        assertNull(result.token)
        assertTrue(result.removed.isEmpty())
        assertEquals(2, result.changed.size)
        assertEquals("/dav/addressbooks/me/alice.vcf", result.changed[0].href)
        assertEquals("e1", result.changed[0].etag)
        assertTrue(result.changed[0].data!!.contains("FN:Alice"))
        assertEquals("/dav/addressbooks/me/bob.vcf", result.changed[1].href)
        assertTrue(result.changed[1].data!!.contains("FN:Bob"))

        assertEquals("REPORT", server.takeRequest().method)
        val listing = server.takeRequest()
        assertEquals("PROPFIND", listing.method)
        assertEquals("1", listing.getHeader("Depth"))
        assertEquals("GET", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
        assertEquals(4, server.requestCount)
    }
}
