package app.gridlink.core.dav

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Finding an event's CalDAV address by UID (RFC 4791 §7.8.6).
 *
 * This exists for one caller: an event synced over JMAP has no path, and RFC 8607 attachments are
 * POSTs to a path. What is worth pinning is that the request really is a `calendar-query` filtered
 * on UID (a server takes the filter literally, and a malformed one silently matches everything),
 * that a relative href comes back as an absolute URL, and that every flavour of "no" answers null
 * rather than throwing at a caller whose only move is to hide a button.
 */
class DavClientFindEventTest {
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

    private fun collection() = server.url("/dav/cal/b/default/").toString()

    private fun multiStatus(body: String) = MockResponse().setResponseCode(207)
        .setHeader("Content-Type", "application/xml; charset=utf-8")
        .setBody(body)

    @Test fun resolvesARelativeHrefAgainstTheCollection() = runBlocking {
        server.enqueue(
            multiStatus(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:">
                  <D:response>
                    <D:href>/dav/cal/b/default/abc-123.ics</D:href>
                    <D:propstat><D:prop><D:getetag>"e1"</D:getetag></D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                  </D:response>
                </D:multistatus>
                """.trimIndent(),
            ),
        )

        val found = client.findEventUrl(collection(), credentials, "abc-123")

        assertEquals(server.url("/dav/cal/b/default/abc-123.ics").toString(), found)
    }

    @Test fun asksForACalendarQueryFilteredOnTheUid() = runBlocking {
        server.enqueue(multiStatus("""<D:multistatus xmlns:D="DAV:"></D:multistatus>"""))

        client.findEventUrl(collection(), credentials, "abc-123")

        val request = server.takeRequest()
        assertEquals("REPORT", request.method)
        assertEquals("/dav/cal/b/default/", request.path)
        assertEquals(credentials.authorizationHeader(), request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue("is a calendar-query", body.contains("<C:calendar-query"))
        assertTrue("filters VEVENT", body.contains("""<C:comp-filter name="VEVENT">"""))
        assertTrue("filters on UID", body.contains("""<C:prop-filter name="UID">"""))
        assertTrue("carries the uid", body.contains("abc-123"))
        // Asking for the event body would download the whole appointment to learn its address.
        assertTrue("asks only for the etag", body.contains("<D:getetag/>"))
        assertTrue("does not ask for calendar-data", !body.contains("calendar-data"))
    }

    /** A uid with XML in it must not be able to rewrite the filter it travels inside. */
    @Test fun escapesTheUid() = runBlocking {
        server.enqueue(multiStatus("""<D:multistatus xmlns:D="DAV:"></D:multistatus>"""))

        client.findEventUrl(collection(), credentials, """a<b&c"d""")

        val body = server.takeRequest().body.readUtf8()
        assertTrue("escaped", body.contains("a&lt;b&amp;c"))
        assertTrue("no raw open tag from the uid", !body.contains("a<b"))
    }

    @Test fun noMatchIsNull() = runBlocking {
        server.enqueue(multiStatus("""<D:multistatus xmlns:D="DAV:"></D:multistatus>"""))

        assertNull(client.findEventUrl(collection(), credentials, "nothing-here"))
    }

    /**
     * A server that does not implement calendar-query answers an error, not an empty match. The
     * caller's move is identical either way, so it must not have to catch anything.
     */
    @Test fun aServerThatRefusesTheReportIsNull() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400))

        assertNull(client.findEventUrl(collection(), credentials, "abc-123"))
    }

    /** One of this app's own synthetic collection keys is not a URL. No request should go out. */
    @Test fun aSyntheticCollectionKeyIsNullWithoutARequest() = runBlocking {
        assertNull(client.findEventUrl("jmap:calendar/b", credentials, "abc-123"))
        assertEquals(0, server.requestCount)
    }

    @Test fun aBlankUidIsNullWithoutARequest() = runBlocking {
        assertNull(client.findEventUrl(collection(), credentials, "  "))
        assertEquals(0, server.requestCount)
    }

    /**
     * Some servers include the collection itself in a REPORT answer. Attaching a file to a calendar
     * instead of to an appointment is a failure that only shows up on somebody else's device.
     */
    @Test fun skipsTheCollectionsOwnHref() = runBlocking {
        server.enqueue(
            multiStatus(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:">
                  <D:response><D:href>/dav/cal/b/default/</D:href>
                    <D:propstat><D:prop/><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                  </D:response>
                  <D:response><D:href>/dav/cal/b/default/abc-123.ics</D:href>
                    <D:propstat><D:prop><D:getetag>"e1"</D:getetag></D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                  </D:response>
                </D:multistatus>
                """.trimIndent(),
            ),
        )

        assertEquals(
            server.url("/dav/cal/b/default/abc-123.ics").toString(),
            client.findEventUrl(collection(), credentials, "abc-123"),
        )
    }

    /** A 404'd response is the server saying that resource is gone, not an address to POST to. */
    @Test fun skipsRemovedResponses() = runBlocking {
        server.enqueue(
            multiStatus(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:">
                  <D:response><D:href>/dav/cal/b/default/old.ics</D:href>
                    <D:status>HTTP/1.1 404 Not Found</D:status>
                  </D:response>
                  <D:response><D:href>/dav/cal/b/default/abc-123.ics</D:href>
                    <D:propstat><D:prop><D:getetag>"e1"</D:getetag></D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                  </D:response>
                </D:multistatus>
                """.trimIndent(),
            ),
        )

        assertEquals(
            server.url("/dav/cal/b/default/abc-123.ics").toString(),
            client.findEventUrl(collection(), credentials, "abc-123"),
        )
    }
}
