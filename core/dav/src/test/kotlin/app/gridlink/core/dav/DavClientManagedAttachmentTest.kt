package app.gridlink.core.dav

import kotlinx.coroutines.runBlocking
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
 * RFC 8607 managed attachments, client half.
 *
 * The three things worth pinning are the three that cannot be seen by looking at the app: that the
 * capability is READ and not assumed, that the request carries the exact query string and header
 * shape the spec names, and that a file name from a picker cannot become an injected header. The
 * last one is the reason this file exists at all — the rest of the round trip is one POST.
 */
class DavClientManagedAttachmentTest {
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

    private fun event() = server.url("/dav/cal/b/default/e-1.ics").toString()

    @Test fun capability_isReadFromTheDavHeader() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("DAV", "1, 2, 3, access-control, calendar-access, calendar-managed-attachments"),
        )

        assertTrue(client.managedAttachmentsSupported(event(), credentials))
        assertEquals("OPTIONS", server.takeRequest().method)
    }

    /** Stalwart's real answer, measured 2026-08-16. The feature must stay dark against it. */
    @Test fun capability_isFalseWhenTheServerDoesNotAdvertiseIt() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader(
                "DAV",
                "1, 2, 3, access-control, extended-mkcol, calendar-access, " +
                    "calendar-auto-schedule, calendar-no-timezone, addressbook",
            ),
        )

        assertFalse(client.managedAttachmentsSupported(event(), credentials))
    }

    /** A server free to split its tokens across repeated headers must not read as "unsupported". */
    @Test fun capability_readsEveryDavHeaderNotJustTheFirst() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("DAV", "1, 2, 3")
                .addHeader("DAV", "calendar-access, calendar-managed-attachments"),
        )

        assertTrue(client.managedAttachmentsSupported(event(), credentials))
    }

    @Test fun capability_isFalseWhenTheServerRefusesTheRequest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        assertFalse(client.managedAttachmentsSupported(event(), credentials))
    }

    @Test fun add_postsTheSpecShape() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setHeader("Cal-Managed-ID", "m-42")
                .setHeader("Location", "https://cal.example/attach/m-42")
                .setHeader("ETag", "\"e2\""),
        )

        val added = client.addManagedAttachment(
            objectUrl = event(),
            credentials = credentials,
            fileName = "agenda.pdf",
            contentType = "application/pdf",
            bytes = BYTES,
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/dav/cal/b/default/e-1.ics?action=attachment-add", request.path)
        assertEquals("attachment;filename=\"agenda.pdf\"", request.getHeader("Content-Disposition"))
        assertEquals(credentials.authorizationHeader(), request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/pdf"))
        assertEquals(BYTES.size.toLong(), request.bodySize)

        assertEquals("m-42", added.managedId)
        assertEquals("https://cal.example/attach/m-42", added.url)
        assertEquals("e2", added.etag) // unquoted, as stored
    }

    @Test fun add_attachesToOneInstanceWhenGivenARecurrenceId() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Cal-Managed-ID", "m-7"))

        client.addManagedAttachment(
            objectUrl = event(),
            credentials = credentials,
            fileName = "notes.txt",
            contentType = "text/plain",
            bytes = BYTES,
            recurrenceId = "20260818T140000Z",
        )

        assertEquals(
            "/dav/cal/b/default/e-1.ics?action=attachment-add&rid=20260818T140000Z",
            server.takeRequest().path,
        )
    }

    /**
     * 🔴 A 2xx without a managed id is a write this client cannot describe: the attachment exists
     * and can never be named again. Failing loudly beats recording a blank handle.
     */
    @Test fun add_failsWhenTheServerReturnsNoManagedId() {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"e2\""))
        try {
            runBlocking {
                client.addManagedAttachment(event(), credentials, "a.txt", "text/plain", BYTES)
            }
            throw AssertionError("expected DavException")
        } catch (e: DavException) {
            assertTrue(e.message!!.contains("Cal-Managed-ID"))
        }
    }

    @Test fun add_reportsAServerRefusal() {
        server.enqueue(MockResponse().setResponseCode(403))
        try {
            runBlocking {
                client.addManagedAttachment(event(), credentials, "a.txt", "text/plain", BYTES)
            }
            throw AssertionError("expected DavException")
        } catch (e: DavException) {
            assertEquals(403, e.code)
        }
    }

    @Test fun remove_postsTheManagedIdAndReturnsTheNewEtag() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"e3\""))

        val etag = client.removeManagedAttachment(event(), credentials, "m-42")

        assertEquals(
            "/dav/cal/b/default/e-1.ics?action=attachment-remove&managed-id=m-42",
            server.takeRequest().path,
        )
        assertEquals("e3", etag)
    }

    /** 204 with nothing to say about the version is legal, and the next sync settles it. */
    @Test fun remove_toleratesAServerWithNoEtagToOffer() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        assertNull(client.removeManagedAttachment(event(), credentials, "m-42"))
    }

    @Test fun fileName_dropsWhatWouldInjectAHeader() {
        // The attack: close the quoted value, then start a header of the attacker's choosing.
        // One underscore per dropped character, so CR and LF leave two: the name is deliberately
        // not prettied up afterwards, because every extra pass over it is somewhere to be clever
        // and wrong. What matters is that nothing structural survives.
        assertEquals(
            "evil.pdf_ __X-Evil_ yes",
            DavClient.safeFileName("evil.pdf\" \r\nX-Evil: yes"),
        )
        // Path separators go too. The remains of the traversal are still there as text, which is
        // fine and is the point: what matters is that no separator survives for a server to walk.
        assertEquals("etc_passwd", DavClient.safeFileName("../../etc/passwd"))
        // Nothing survivable left is still a valid attachment, just a blandly named one.
        assertEquals(DavClient.FALLBACK_ATTACHMENT_NAME, DavClient.safeFileName("\"\"\""))
        assertEquals(DavClient.FALLBACK_ATTACHMENT_NAME, DavClient.safeFileName("   "))
        // The ordinary case is left alone, which is what stops this being a nuisance.
        assertEquals("Q3 report (final).pdf", DavClient.safeFileName("Q3 report (final).pdf"))
    }

    private companion object {
        val BYTES = "hello".toByteArray()
    }
}
