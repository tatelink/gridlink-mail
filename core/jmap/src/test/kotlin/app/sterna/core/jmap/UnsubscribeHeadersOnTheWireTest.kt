package app.sterna.core.jmap

import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The two unsubscribe headers, from the request bytes to the decoded model — and, just as
 * deliberately, their ABSENCE from the list fetch.
 *
 * The chain has three places to go silently inert, and each of them looks like "this sender does
 * not offer an unsubscribe":
 *  1. the property is not asked for on the wire;
 *  2. it is asked for, but the `@SerialName` is missing — `DefaultJson` is
 *     `ignoreUnknownKeys = true`, so kotlinx drops the server's answer without a word;
 *  3. the server rejects the property and the whole `Email/get` fails, which is much worse than
 *     no banner: no message opens at all.
 *
 * So the assertions start from the call the READER makes and go down to the octets.
 */
class UnsubscribeHeadersOnTheWireTest {
    private lateinit var server: MockWebServer

    /** A fresh client per test: the "this server refuses the properties" memo is per instance,
     *  and the OS can hand a later mock server the same port a previous one used. */
    private lateinit var client: JmapClient
    private val auth = BasicAuth("alex@masto.top", "secret")

    private fun session() = JmapSession(apiUrl = server.url("/jmap/api/").toString())

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = JmapClient()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun emailResponse(extraProperties: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {"methodResponses":[["Email/get",{"accountId":"acc1","state":"s1","list":[
              {"id":"m1","subject":"Weekly digest"$extraProperties}
            ],"notFound":[]},"g0"]]}
            """.trimIndent(),
        )

    @Test fun `opening a message asks the server for both unsubscribe headers`() = runBlocking {
        server.enqueue(emailResponse(""))

        client.getEmail(session(), "acc1", "m1", auth)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"header:List-Unsubscribe:asText\""))
        assertTrue(body, body.contains("\"header:List-Unsubscribe-Post:asText\""))
    }

    @Test fun `the body prefetch asks for them too, so a cached body carries the banner`() = runBlocking {
        server.enqueue(emailResponse(""))

        client.getEmailsWithBody(session(), "acc1", listOf("m1", "m2"), auth)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"header:List-Unsubscribe:asText\""))
        assertTrue(body, body.contains("\"header:List-Unsubscribe-Post:asText\""))
    }

    /**
     * THE witness. A list page fetches dozens of rows every time a folder is scrolled; putting a
     * header property there would make every user pay, on every page, for something only the open
     * message uses. The indicator in the list is a separate, later piece of work with its own
     * cost — it must not arrive by accident here.
     */
    @Test fun `the folder list does NOT ask for them`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"methodResponses":[
                      ["Email/query",{"ids":["m1"],"queryState":"q1"},"q0"],
                      ["Email/get",{"list":[{"id":"m1"}],"state":"s1"},"g0"]
                    ]}
                    """.trimIndent(),
                ),
        )

        client.queryEmailsPage(session(), "acc1", "inbox", 50, auth)

        val body = server.takeRequest().body.readUtf8()
        assertFalse("a list page must not pay for a reader-only header", body.contains("header:List-Unsubscribe"))
    }

    /** Asked for AND decoded: without the `@SerialName` the value is dropped in silence. */
    @Test fun `the returned headers reach the model`() = runBlocking {
        server.enqueue(
            emailResponse(
                ""","header:List-Unsubscribe:asText":"<https://l.example.com/u>",""" +
                    """"header:List-Unsubscribe-Post:asText":"List-Unsubscribe=One-Click"""",
            ),
        )

        val email = client.getEmail(session(), "acc1", "m1", auth)

        assertEquals("<https://l.example.com/u>", email.listUnsubscribe)
        assertEquals("List-Unsubscribe=One-Click", email.listUnsubscribePost)
    }

    /** A message with no such header comes back null, and that is not a failure. */
    @Test fun `a message without the headers opens with them null`() = runBlocking {
        server.enqueue(emailResponse(""))

        val email = client.getEmail(session(), "acc1", "m1", auth)

        assertEquals(null, email.listUnsubscribe)
        assertEquals(null, email.listUnsubscribePost)
    }

    /**
     * The fallback, and the reason it is not optional. Stalwart accepts these properties; Cyrus,
     * Fastmail and James were never measured. A server that rejects an unknown property fails the
     * WHOLE `Email/get`, so without this every message on that account would stop opening — a
     * privacy nicety would have broken reading mail.
     */
    @Test fun `a server that rejects the properties still opens the message`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"methodResponses":[["error",{"type":"invalidArguments"},"g0"]]}"""),
        )
        server.enqueue(emailResponse(""))

        val email = client.getEmail(session(), "acc1", "m1", auth)

        assertEquals("m1", email.id)
        assertEquals(2, server.requestCount)
        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        assertTrue("the first attempt asks", first.contains("header:List-Unsubscribe"))
        assertFalse("the replay must drop them", second.contains("header:List-Unsubscribe"))
        // …and it must still be a real Email/get, not a stripped-down one.
        assertTrue(second, second.contains("\"bodyValues\""))
    }

    /** Learned once: the next message on the same server does not spend a round trip finding out. */
    @Test fun `a refusing server is remembered for the rest of the process`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"methodResponses":[["error",{"type":"invalidArguments"},"g0"]]}"""),
        )
        server.enqueue(emailResponse(""))
        server.enqueue(emailResponse(""))

        client.getEmail(session(), "acc1", "m1", auth)
        client.getEmail(session(), "acc1", "m2", auth)

        assertEquals("the second open must cost ONE request", 3, server.requestCount)
        repeat(2) { server.takeRequest() }
        val third = server.takeRequest().body.readUtf8()
        assertFalse("the properties must not be asked for again", third.contains("header:List-Unsubscribe"))
    }

    /**
     * And the limit of the fallback: a real failure is NOT retried without the properties. Doing
     * so would swallow the error and hand the reader an empty message instead of a diagnosis.
     */
    @Test fun `an unrelated method error is not retried`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"methodResponses":[["error",{"type":"accountNotFound"},"g0"]]}"""),
        )

        val failure = runCatching { client.getEmail(session(), "acc1", "m1", auth) }.exceptionOrNull()

        assertTrue("$failure", failure is JmapException)
        assertEquals("accountNotFound", (failure as JmapException).errorType)
        assertEquals(1, server.requestCount)
    }
}
