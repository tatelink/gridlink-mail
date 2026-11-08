package app.gridlink.core.dav

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The calendar-level requests: which calendar the server calls the default (found during discovery),
 * moving an event between calendars, and renaming or recolouring a calendar.
 *
 * The answers mirror Stalwart's (probed live 2026-11-07): the principal 404s
 * `schedule-default-calendar-URL` but names the scheduling inbox, and the inbox answers it.
 */
class DavClientCalendarAdminTest {
    private lateinit var server: MockWebServer
    private val client = DavClient(OkHttpClient())
    private val credentials = DavCredentials("avery", "secret")

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun multiStatus(body: String) = MockResponse().setResponseCode(207)
        .setHeader("Content-Type", "application/xml; charset=utf-8").setBody(body.trimIndent())

    private fun enqueueDiscoveryUpToListing() {
        server.enqueue(
            multiStatus(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <D:response><D:href>/.well-known/caldav</D:href>
                    <D:propstat><D:prop>
                      <D:current-user-principal><D:href>/dav/pal/avery/</D:href></D:current-user-principal>
                      <C:calendar-home-set><D:href>/dav/cal/avery/</D:href></C:calendar-home-set>
                    </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                  </D:response>
                </D:multistatus>
                """,
            ),
        )
        server.enqueue(
            multiStatus(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <D:response><D:href>/dav/cal/avery/</D:href>
                    <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                  </D:response>
                  <D:response><D:href>/dav/cal/avery/shared/</D:href>
                    <D:propstat><D:prop><D:displayname>Team</D:displayname>
                      <D:resourcetype><D:collection/><C:calendar/></D:resourcetype></D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                  </D:response>
                  <D:response><D:href>/dav/cal/avery/default/</D:href>
                    <D:propstat><D:prop><D:displayname>Mine</D:displayname>
                      <D:resourcetype><D:collection/><C:calendar/></D:resourcetype></D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                  </D:response>
                </D:multistatus>
                """,
            ),
        )
    }

    @Test fun discoveryMarksTheCalendarTheSchedulingInboxNamesAsDefault() = runBlocking {
        enqueueDiscoveryUpToListing()
        server.enqueue(
            multiStatus(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <D:response><D:href>/dav/pal/avery/</D:href>
                    <D:propstat><D:prop><C:schedule-inbox-URL><D:href>/dav/itip/avery/inbox/</D:href></C:schedule-inbox-URL></D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                    <D:propstat><D:prop><C:schedule-default-calendar-URL/></D:prop>
                    <D:status>HTTP/1.1 404 Not Found</D:status></D:propstat>
                  </D:response>
                </D:multistatus>
                """,
            ),
        )
        server.enqueue(
            multiStatus(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <D:response><D:href>/dav/itip/avery/inbox/</D:href>
                    <D:propstat><D:prop><C:schedule-default-calendar-URL><D:href>/dav/cal/avery/default</D:href></C:schedule-default-calendar-URL></D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                  </D:response>
                </D:multistatus>
                """,
            ),
        )

        val found = client.discover(server.url("/").toString(), credentials, DavKind.CALENDAR)

        assertEquals(listOf("Team", "Mine"), found.map { it.displayName })
        // The inbox's href has no trailing slash; the listing's does. Still the same calendar.
        assertEquals(listOf(false, true), found.map { it.isDefault })
        server.takeRequest()
        server.takeRequest()
        assertEquals("/dav/pal/avery/", server.takeRequest().path)
        assertEquals("/dav/itip/avery/inbox/", server.takeRequest().path)
    }

    @Test fun aServerWithoutSchedulingStillDiscoversWithNoDefault() = runBlocking {
        enqueueDiscoveryUpToListing()
        server.enqueue(MockResponse().setResponseCode(404))

        val found = client.discover(server.url("/").toString(), credentials, DavKind.CALENDAR)

        assertEquals(2, found.size)
        assertFalse(found.any { it.isDefault })
    }

    @Test fun moveSendsDestinationNoOverwriteAndTheStoredEtag() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"e9\""))

        val moved = client.move(
            collectionUrl = server.url("/dav/cal/avery/default/").toString(),
            href = "/dav/cal/avery/default/ev-1.ics",
            targetCollectionUrl = server.url("/dav/cal/avery/work/").toString(),
            credentials = credentials,
            etag = "e1",
        )

        val request = server.takeRequest()
        assertEquals("MOVE", request.method)
        assertEquals("/dav/cal/avery/default/ev-1.ics", request.path)
        assertEquals(server.url("/dav/cal/avery/work/ev-1.ics").toString(), request.getHeader("Destination"))
        assertEquals("F", request.getHeader("Overwrite"))
        assertEquals("\"e1\"", request.getHeader("If-Match"))
        assertEquals("/dav/cal/avery/work/ev-1.ics", moved.href)
        assertEquals("e9", moved.etag)
    }

    @Test fun aMoveOfAChangedItemIs412AndAnUnsupportedMoveIsRecognised() {
        server.enqueue(MockResponse().setResponseCode(412))
        server.enqueue(MockResponse().setResponseCode(502))
        val codes = (1..2).map {
            try {
                runBlocking {
                    client.move(
                        server.url("/c/a/").toString(),
                        "/c/a/x.ics",
                        server.url("/c/b/").toString(),
                        credentials,
                        "e1",
                    )
                }
                null
            } catch (e: DavException) {
                e.code
            }
        }
        assertEquals(listOf(412, 502), codes)
        assertFalse(DavClient.moveUnsupported(412))
        assertTrue(DavClient.moveUnsupported(502))
        assertTrue(DavClient.moveUnsupported(405))
    }

    @Test fun renameProppatchesNameAndColourEscaped() = runBlocking {
        server.enqueue(
            multiStatus(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:"><D:response><D:href>/dav/cal/avery/work/</D:href>
                  <D:propstat><D:prop><D:displayname/></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                </D:response></D:multistatus>
                """,
            ),
        )

        client.updateCollection(server.url("/dav/cal/avery/work/").toString(), credentials, "R&D <team>", "#2A9D8FFF")

        val request = server.takeRequest()
        assertEquals("PROPPATCH", request.method)
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("<D:displayname>R&amp;D &lt;team&gt;</D:displayname>"))
        assertTrue(body, body.contains("<A:calendar-color>#2A9D8FFF</A:calendar-color>"))
    }

    @Test fun aRefusedPropertyInsideA207IsARefusal() {
        server.enqueue(
            multiStatus(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:"><D:response><D:href>/c/</D:href>
                  <D:propstat><D:prop><D:displayname/></D:prop><D:status>HTTP/1.1 403 Forbidden</D:status></D:propstat>
                </D:response></D:multistatus>
                """,
            ),
        )
        val code = try {
            runBlocking { client.updateCollection(server.url("/c/").toString(), credentials, "New", null) }
            null
        } catch (e: DavException) {
            e.code
        }
        assertEquals(403, code)
    }
}
