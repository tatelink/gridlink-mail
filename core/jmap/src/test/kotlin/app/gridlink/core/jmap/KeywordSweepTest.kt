package app.gridlink.core.jmap

import app.gridlink.core.jmap.model.JmapSession
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
 * The keyword sweep: JMAP's answer to "what tags exist in this account", assembled the only way the
 * protocol allows.
 *
 * ## Why there is a sweep to test
 * RFC 8621 keeps keywords as keys on each Email and gives no way to enumerate them. `Email/query`
 * with `hasKeyword` needs the name before it can ask for it, so nothing can list the vocabulary and
 * the client has to read the mail. IMAP, by contrast, is simply told (see the imap module's
 * `PermanentFlagsTest`). That asymmetry is the reason this method is expensive, bounded, and honest
 * about stopping.
 *
 * ## What is actually being held still
 * Two things, and the second is the one worth the file. First that the paging terminates on a short
 * page rather than running to the ceiling. Second that [KeywordSweep.complete] tells the truth in
 * both directions: false when the sweep stopped at its own limit with mail left unread, true only
 * when the mailbox ran out. A sweep that reported complete after hitting the cap would turn "we did
 * not look at the rest" into "there is nothing else", which is precisely the false negative the
 * feature exists to remove.
 */
class KeywordSweepTest {
    private lateinit var server: MockWebServer
    private val client = JmapClient()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    /** One page of [ids], each carrying [keywords] as its keyword map. */
    private fun page(ids: List<String>, keywords: List<List<String>>): String {
        val list = ids.mapIndexed { index, id ->
            val flags = keywords[index].joinToString(",") { Q + it + Q + ":true" }
            "{" + Q + "id" + Q + ":" + Q + id + Q +
                "," + Q + "keywords" + Q + ":{" + flags + "}}"
        }
        val idList = ids.joinToString(",") { Q + it + Q }
        return """
            {"methodResponses":[
              ["Email/query",{"accountId":"acc1","ids":[$idList]},"q0"],
              ["Email/get",{"accountId":"acc1","list":[${list.joinToString(",")}]},"g0"]
            ]}
        """.trimIndent()
    }

    private fun sweep(maxMessages: Int, pageSize: Int) = runBlocking {
        client.sweepKeywords(
            session = JmapSession(apiUrl = server.url("/jmap/api/").toString()),
            accountId = "acc1",
            auth = BasicAuth("u", "p"),
            maxMessages = maxMessages,
            pageSize = pageSize,
        )
    }

    @Test fun `a short page ends the sweep and it is complete`() {
        server.enqueue(
            MockResponse().setBody(
                page(listOf("e1", "e2"), listOf(listOf("holiday", D + "seen"), listOf("receipts"))),
            ),
        )

        val found = sweep(maxMessages = 100, pageSize = 10)

        assertEquals(listOf("holiday", "receipts"), found.keywords)
        assertTrue("two of ten is the end of the mailbox", found.complete)
        assertEquals("the sweep should stop, not ask again", 1, server.requestCount)
    }

    /** Full pages keep it going; the union is across all of them, sorted and de-duplicated. */
    @Test fun `it pages until the mailbox runs out`() {
        server.enqueue(MockResponse().setBody(page(listOf("e1", "e2"), listOf(listOf("work"), listOf("holiday")))))
        server.enqueue(MockResponse().setBody(page(listOf("e3", "e4"), listOf(listOf("work"), listOf("admin")))))
        server.enqueue(MockResponse().setBody(page(listOf("e5"), listOf(listOf("holiday")))))

        val found = sweep(maxMessages = 100, pageSize = 2)

        assertEquals(listOf("admin", "holiday", "work"), found.keywords)
        assertTrue(found.complete)
        assertEquals(3, server.requestCount)
    }

    /** 🔴 The whole point: stopping at the ceiling is reported as an incomplete answer. */
    @Test fun `hitting the ceiling is not complete`() {
        server.enqueue(MockResponse().setBody(page(listOf("e1", "e2"), listOf(listOf("work"), listOf("holiday")))))

        val found = sweep(maxMessages = 2, pageSize = 2)

        assertEquals(listOf("holiday", "work"), found.keywords)
        assertFalse("the mailbox may hold tags this sweep never reached", found.complete)
        assertEquals("the cap has to stop the paging", 1, server.requestCount)
    }

    /** System keywords are message state under another name, not tags anyone would name. */
    @Test fun `dollar-prefixed system keywords are dropped`() {
        server.enqueue(
            MockResponse().setBody(
                page(
                    listOf("e1"),
                    listOf(listOf(D + "seen", D + "flagged", D + "answered", "holiday")),
                ),
            ),
        )

        assertEquals(listOf("holiday"), sweep(maxMessages = 10, pageSize = 10).keywords)
    }

    /** An empty account: no keywords, and complete, because there was nothing left to read. */
    @Test fun `an empty mailbox is a complete answer`() {
        server.enqueue(MockResponse().setBody(page(emptyList(), emptyList())))

        val found = sweep(maxMessages = 10, pageSize = 10)

        assertEquals(emptyList<String>(), found.keywords)
        assertTrue(found.complete)
    }

    /** Ids and keywords only. A sweep that pulled envelopes would be unusable on a real mailbox. */
    @Test fun `it asks for nothing but ids and keywords`() {
        server.enqueue(MockResponse().setBody(page(listOf("e1"), listOf(listOf("holiday")))))

        sweep(maxMessages = 10, pageSize = 10)

        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("Email/query"))
        assertTrue(sent.contains("keywords"))
        assertFalse("an envelope fetch would defeat the point", sent.contains("bodyValues"))
        assertFalse(sent.contains("subject"))
    }
}

/** A literal double quote, so the canned JSON below can be built without escaping every field. */
private const val Q = "\""

/** A literal `$`, which the system keywords start with and a Kotlin string would otherwise read as interpolation. */
private const val D = "${'$'}"
