package app.gridlink.core.dav

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

/**
 * Syncing a collection from a server with no sync-collection REPORT.
 *
 * RFC 6578 is optional, and a server that does not implement it refuses the REPORT itself
 * (SmarterMail answers 400). The fallback is a listing diff: PROPFIND for etags only, then
 * downloads for just the items that are new or changed, batched through multiget where the
 * server allows it and plain GETs where it does not. These pin the trigger (a 400 on the
 * first REPORT), that an unchanged item is not fetched again, that a cached item missing
 * from the listing is reported removed, and that a refused multiget degrades to GETs
 * instead of failing the sync.
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

    private fun listing() = multiStatus(
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
          <D:response>
            <D:href>/dav/addressbooks/me/carol.vcf</D:href>
            <D:propstat><D:prop><D:getetag>"e3"</D:getetag></D:prop>
            <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          </D:response>
        </D:multistatus>
        """.trimIndent(),
    )

    @Test fun aRefusedSyncReportFallsBackToAnEtagDiffAndMultiget() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(listing())
        server.enqueue(
            multiStatus(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:carddav">
                  <D:response>
                    <D:href>/dav/addressbooks/me/bob.vcf</D:href>
                    <D:propstat><D:prop>
                      <D:getetag>"e2"</D:getetag>
                      <C:address-data>BEGIN:VCARD&#13;&#10;VERSION:4.0&#13;&#10;FN:Bob&#13;&#10;END:VCARD&#13;&#10;</C:address-data>
                    </D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                  </D:response>
                  <D:response>
                    <D:href>/dav/addressbooks/me/carol.vcf</D:href>
                    <D:propstat><D:prop>
                      <D:getetag>"e3"</D:getetag>
                      <C:address-data>BEGIN:VCARD&#13;&#10;VERSION:4.0&#13;&#10;FN:Carol&#13;&#10;END:VCARD&#13;&#10;</C:address-data>
                    </D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                  </D:response>
                </D:multistatus>
                """.trimIndent(),
            ),
        )

        // Alice is cached at the etag the server still reports; Bob's etag moved; Dave is
        // cached but no longer listed.
        val known = mapOf(
            "/dav/addressbooks/me/alice.vcf" to "e1",
            "/dav/addressbooks/me/bob.vcf" to "e1",
            "/dav/addressbooks/me/dave.vcf" to "e9",
        )
        val result = client.sync(collection(), credentials, DavKind.ADDRESS_BOOK, token = null, knownEtags = known)

        assertFalse(result.fullResync)
        assertNull(result.token)
        assertEquals(listOf("/dav/addressbooks/me/dave.vcf"), result.removed)
        assertEquals(
            listOf("/dav/addressbooks/me/bob.vcf", "/dav/addressbooks/me/carol.vcf"),
            result.changed.map { it.href },
        )
        assertTrue(result.changed[0].data!!.contains("FN:Bob"))
        assertTrue(result.changed[1].data!!.contains("FN:Carol"))

        assertEquals("REPORT", server.takeRequest().method)
        val listingRequest = server.takeRequest()
        assertEquals("PROPFIND", listingRequest.method)
        assertEquals("1", listingRequest.getHeader("Depth"))
        val multiget = server.takeRequest()
        assertEquals("REPORT", multiget.method)
        val multigetBody = multiget.body.readUtf8()
        assertTrue(multigetBody.contains("addressbook-multiget"))
        assertTrue(multigetBody.contains("/dav/addressbooks/me/bob.vcf"))
        assertTrue(multigetBody.contains("/dav/addressbooks/me/carol.vcf"))
        assertFalse(multigetBody.contains("alice"))
        assertEquals(3, server.requestCount)
    }

    @Test fun aRefusedMultigetDegradesToPlainGets() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(listing())
        server.enqueue(MockResponse().setResponseCode(400))
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
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("ETag", "\"e3\"")
                .setBody("BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Carol\r\nEND:VCARD\r\n"),
        )

        val result = client.sync(collection(), credentials, DavKind.ADDRESS_BOOK, token = null)

        assertEquals(3, result.changed.size)
        assertTrue(result.changed[0].data!!.contains("FN:Alice"))
        assertTrue(result.changed[2].data!!.contains("FN:Carol"))
        assertTrue(result.removed.isEmpty())

        assertEquals("REPORT", server.takeRequest().method)
        assertEquals("PROPFIND", server.takeRequest().method)
        assertEquals("REPORT", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
        assertEquals(6, server.requestCount)
    }
}
