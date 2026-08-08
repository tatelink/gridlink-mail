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
 * The two PUTs and their preconditions. `If-None-Match: *` and `If-Match: <etag>` are the whole
 * difference between "add" and "edit" at the HTTP level, and the 412 each turns into is a distinct
 * situation the repository above resolves differently — so both headers and both 412s are pinned.
 */
class DavClientWriteTest {
    private lateinit var server: MockWebServer
    private val client = DavClient(OkHttpClient())
    private val credentials = DavCredentials("brandon", "secret")

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun collection() = server.url("/dav/card/b/").toString()

    @Test fun create_putsWithIfNoneMatchStar() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"e1\""))

        val written = client.create(collection(), "u-1.vcf", credentials, DavKind.ADDRESS_BOOK, VCF)

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/dav/card/b/u-1.vcf", request.path)
        // The guard that turns a UID collision into a 412 instead of a silent overwrite.
        assertEquals("*", request.getHeader("If-None-Match"))
        assertEquals(credentials.authorizationHeader(), request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("text/vcard"))
        assertEquals(VCF, request.body.readUtf8())

        assertEquals("/dav/card/b/u-1.vcf", written.href)
        assertEquals("e1", written.etag) // unquoted, as stored
    }

    @Test fun create_reportsACollisionAs412() {
        server.enqueue(MockResponse().setResponseCode(412))
        try {
            runBlocking { client.create(collection(), "u-1.vcf", credentials, DavKind.ADDRESS_BOOK, VCF) }
            throw AssertionError("expected DavException")
        } catch (e: DavException) {
            assertEquals(412, e.code)
        }
    }

    @Test fun update_putsWithTheStoredEtagQuoted() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"e2\""))

        val written = client.update(
            collection(), "/dav/card/b/u-1.vcf", credentials, DavKind.ADDRESS_BOOK, VCF, etag = "e1",
        )

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/dav/card/b/u-1.vcf", request.path)
        // Stored unquoted, sent in the RFC 7232 quoted form. This is what makes the PUT an edit
        // of the version this device saw, not a blind overwrite of whatever is there now.
        assertEquals("\"e1\"", request.getHeader("If-Match"))
        assertNull(request.getHeader("If-None-Match"))
        assertEquals("e2", written.etag)
    }

    @Test fun update_goesUnconditionalOnlyWhenNoEtagWasEverStored() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        client.update(collection(), "/dav/card/b/u-1.vcf", credentials, DavKind.ADDRESS_BOOK, VCF, etag = null)

        assertNull(server.takeRequest().getHeader("If-Match"))
    }

    @Test fun update_reportsSomebodyElsesEditAs412() {
        server.enqueue(MockResponse().setResponseCode(412))
        try {
            runBlocking {
                client.update(collection(), "/dav/card/b/u-1.vcf", credentials, DavKind.ADDRESS_BOOK, VCF, "e1")
            }
            throw AssertionError("expected DavException")
        } catch (e: DavException) {
            assertEquals(412, e.code)
            // The repository shows this message's meaning to the user (resync, try again), so the
            // exception must keep saying "changed on the server" and not a generic write failure.
            assertTrue(e.message!!.contains("changed on the server"))
        }
    }

    @Test fun update_omittedEtagInTheResponseStaysNull() = runBlocking {
        // RFC 4791 §5.3.4: a server that rewrote what it stored may omit the ETag. Null is the
        // honest answer; inventing one would make the NEXT update's If-Match a lie.
        server.enqueue(MockResponse().setResponseCode(204))
        val written = client.update(collection(), "/dav/card/b/u-1.vcf", credentials, DavKind.ADDRESS_BOOK, VCF, "e1")
        assertNull(written.etag)
    }

    private companion object {
        val VCF = listOf(
            "BEGIN:VCARD",
            "VERSION:4.0",
            "UID:u-1",
            "FN:Karen Caldwell",
            "END:VCARD",
        ).joinToString("\r\n", postfix = "\r\n")
    }
}
