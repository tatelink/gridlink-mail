package app.gridlink.core.dav

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Discovery across the `/.well-known` redirect.
 *
 * The well-known path exists to 301 to the host the collections really live on, and that hop
 * crosses hosts in the normal deployment (a domain redirecting to its provider's server).
 * okhttp follows such a redirect but drops the `Authorization` header on the way, so the
 * provider's server answers an anonymous PROPFIND with 401 and the user is told their password
 * is wrong. This pins the client's own following: the re-issued PROPFIND must still be a
 * PROPFIND and must still carry the password, and the hrefs in the answer must be resolved
 * against the host that answered, not the host that redirected.
 *
 * Two servers stand in for the two hosts; the second is addressed as 127.0.0.1 rather than
 * localhost so the host names really differ, which is the condition the header drop depends on.
 */
class DavClientRedirectTest {
    private lateinit var domain: MockWebServer
    private lateinit var provider: MockWebServer
    private val client = DavClient()
    private val credentials = DavCredentials("mmchugh@silverapple.eu", "secret")

    @Before fun setUp() {
        domain = MockWebServer()
        domain.start()
        provider = MockWebServer()
        provider.start()
    }

    @After fun tearDown() {
        domain.shutdown()
        provider.shutdown()
    }

    private fun providerUrl(path: String): String =
        provider.url(path).toString().replace("localhost", "127.0.0.1")

    private fun multiStatus(body: String) = MockResponse().setResponseCode(207)
        .setHeader("Content-Type", "application/xml; charset=utf-8")
        .setBody(body)

    @Test fun discoverySurvivesACrossHostRedirect() = runBlocking {
        domain.enqueue(
            MockResponse().setResponseCode(301)
                .setHeader("Location", providerUrl("/webdav/")),
        )
        provider.enqueue(
            multiStatus(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:carddav">
                  <D:response>
                    <D:href>/webdav/</D:href>
                    <D:propstat><D:prop>
                      <D:current-user-principal><D:href>/webdav/principals/mmchugh/</D:href></D:current-user-principal>
                      <C:addressbook-home-set><D:href>/webdav/addressbooks/</D:href></C:addressbook-home-set>
                    </D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                  </D:response>
                </D:multistatus>
                """.trimIndent(),
            ),
        )
        provider.enqueue(
            multiStatus(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:carddav">
                  <D:response>
                    <D:href>/webdav/addressbooks/</D:href>
                    <D:propstat><D:prop>
                      <D:resourcetype><D:collection/></D:resourcetype>
                    </D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                  </D:response>
                  <D:response>
                    <D:href>/webdav/addressbooks/silverapple.eu/mmchugh/7617/</D:href>
                    <D:propstat><D:prop>
                      <D:displayname>Contacts</D:displayname>
                      <D:resourcetype><C:addressbook/></D:resourcetype>
                    </D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                  </D:response>
                </D:multistatus>
                """.trimIndent(),
            ),
        )

        val found = client.discover(domain.url("/").toString(), credentials, DavKind.ADDRESS_BOOK)

        assertEquals(1, found.size)
        assertEquals("Contacts", found[0].displayName)
        assertEquals(
            providerUrl("/webdav/addressbooks/silverapple.eu/mmchugh/7617/"),
            found[0].url,
        )

        val asked = domain.takeRequest()
        assertEquals("PROPFIND", asked.method)
        assertEquals("/.well-known/carddav", asked.path)
        assertEquals(credentials.authorizationHeader(), asked.getHeader("Authorization"))

        // The re-issued request on the other host: same method, password still attached.
        val answered = provider.takeRequest()
        assertEquals("PROPFIND", answered.method)
        assertEquals(credentials.authorizationHeader(), answered.getHeader("Authorization"))

        // The home set was listed on the answering host, not back on the redirecting domain.
        val listing = provider.takeRequest()
        assertEquals("PROPFIND", listing.method)
        assertEquals("/webdav/addressbooks/", listing.path)
        assertEquals(1, domain.requestCount)
    }
}
