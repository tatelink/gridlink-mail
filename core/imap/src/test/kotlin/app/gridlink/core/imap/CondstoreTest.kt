package app.gridlink.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CONDSTORE delta on the wire (RFC 7162).
 *
 * Stated at this level because the failure it guards against is silent: a sync that stops seeing
 * flag changes throws nothing, shows nothing and looks exactly like a mailbox where nothing
 * happened. The only durable evidence is which command lines left the client, so that is what
 * every case here asserts.
 */
class CondstoreTest {

    /** [selectResponse] with a `[HIGHESTMODSEQ n]` line, i.e. what a CONDSTORE server really sends. */
    private fun selectWithModSeq(tag: String, modSeq: Long, exists: Int = 4, uidValidity: Long = 1L) =
        "* $exists EXISTS\r\n* OK [UIDVALIDITY $uidValidity] ok\r\n* OK [UIDNEXT ${exists + 1}] ok\r\n" +
            "* OK [HIGHESTMODSEQ $modSeq] ok\r\n$tag OK [READ-WRITE] selected\r\n"

    private fun server(
        capability: String = "IMAP4rev1 CONDSTORE",
        select: (String) -> String = { selectWithModSeq(it, 90L) },
        fetch: (String) -> String = { ok(it) },
    ) = FakeImapServer { tag, line ->
        when {
            line.startsWith("CAPABILITY") -> "* CAPABILITY $capability\r\n$tag OK done\r\n"
            line.startsWith("SELECT") -> select(tag)
            line.startsWith("UID FETCH") -> fetch(tag)
            else -> ok(tag)
        }
    }

    @Test fun `asking for a modseq sends the CONDSTORE select parameter`() {
        server().use { s ->
            val status = s.session().use { it.select("INBOX", withModSeq = true) }

            assertEquals(listOf("SELECT \"INBOX\" (CONDSTORE)"), s.issued().filter { it.startsWith("SELECT") })
            assertEquals(90L, status.highestModSeq)
        }
    }

    /**
     * The parameter is a BAD on a server without the capability, and a BAD on SELECT is an
     * unopenable folder. So the gate lives in [ImapClient.select] and the caller may always ask.
     */
    @Test fun `a server without CONDSTORE gets a plain select`() {
        server(capability = "IMAP4rev1 UIDPLUS", select = { selectResponse(it) }).use { s ->
            val status = s.session().use { it.select("INBOX", withModSeq = true) }

            assertEquals(listOf("SELECT \"INBOX\""), s.issued().filter { it.startsWith("SELECT") })
            assertEquals(0L, status.highestModSeq)
        }
    }

    /** A caller that did not ask never gets the parameter, capability or not. */
    @Test fun `a caller that does not ask never sends it`() {
        server().use { s ->
            s.session().use { it.select("INBOX") }

            assertEquals(listOf("SELECT \"INBOX\""), s.issued().filter { it.startsWith("SELECT") })
        }
    }

    /**
     * RFC 7162 §3.1.2.2: one folder may answer `NOMODSEQ` on a server that supports CONDSTORE.
     * It must read as "no watermark", never as watermark zero, or the folder would be declared
     * unchanged forever.
     */
    @Test fun `a NOMODSEQ folder reports no watermark`() {
        server(
            select = {
                "* 4 EXISTS\r\n* OK [UIDVALIDITY 1] ok\r\n* OK [UIDNEXT 5] ok\r\n" +
                    "* OK [NOMODSEQ] no modsequences\r\n$it OK [READ-WRITE] selected\r\n"
            },
        ).use { s ->
            val status = s.session().use { it.select("INBOX", withModSeq = true) }

            assertEquals(0L, status.highestModSeq)
        }
    }

    /**
     * The case the plan asked for by name: a flag changed on the server, does the client see it.
     * Both directions — the star that went on, and the one that went off — because a parser that
     * only ever reads "has this flag" makes CLEARING one invisible.
     */
    @Test fun `a flag changed on the server arrives as a change`() {
        server(
            fetch = {
                "* 2 FETCH (UID 12 FLAGS (\\Seen \\Flagged) MODSEQ (101))\r\n" +
                    "* 3 FETCH (UID 13 FLAGS () MODSEQ (102))\r\n$it OK fetch completed\r\n"
            },
        ).use { s ->
            val changes = s.session().use { session ->
                session.select("INBOX", withModSeq = true)
                session.fetchFlagsChangedSince(90L)
            }

            assertEquals(
                listOf("UID FETCH 1:* (FLAGS) (CHANGEDSINCE 90)"),
                s.issued().filter { it.startsWith("UID FETCH") },
            )
            assertEquals(2, changes.size)
            assertEquals(ImapFlagChange(uid = 12L, seen = true, flagged = true, answered = false, deleted = false), changes[0])
            assertEquals(ImapFlagChange(uid = 13L, seen = false, flagged = false, answered = false, deleted = false), changes[1])
        }
    }

    /**
     * A `\Deleted` message stays IN the delta, unlike every envelope fetch, which filters it out.
     * The flag going on is the change the cache has to act on; dropping it would leave a message
     * the user deleted elsewhere on screen until a full re-read.
     */
    @Test fun `a deleted flag is reported rather than filtered out`() {
        server(fetch = { "* 1 FETCH (UID 7 FLAGS (\\Deleted \\Seen) MODSEQ (99))\r\n$it OK done\r\n" }).use { s ->
            val changes = s.session().use { session ->
                session.select("INBOX", withModSeq = true)
                session.fetchFlagsChangedSince(90L)
            }

            assertEquals(1, changes.size)
            assertTrue(changes.single().deleted)
        }
    }

    /** No watermark, no delta: a fetch of `CHANGEDSINCE 0` would ask for the whole folder. */
    @Test fun `no watermark issues no fetch at all`() {
        server().use { s ->
            val changes = s.session().use { session ->
                session.select("INBOX", withModSeq = true)
                session.fetchFlagsChangedSince(0L)
            }

            assertEquals(emptyList<String>(), s.issued().filter { it.startsWith("UID FETCH") })
            assertEquals(emptyList<ImapFlagChange>(), changes)
        }
    }

    /**
     * A FETCH line with no UID is dropped rather than turned into a change for message zero. The
     * server that sends one is out of spec, but a `uid = 0` change would be applied to an id no
     * row has, which is a silent no-op that looks like a working sync.
     */
    @Test fun `a line without a uid is dropped`() {
        server(fetch = { "* 1 FETCH (FLAGS (\\Seen) MODSEQ (99))\r\n$it OK done\r\n" }).use { s ->
            val changes = s.session().use { session ->
                session.select("INBOX", withModSeq = true)
                session.fetchFlagsChangedSince(90L)
            }

            assertEquals(emptyList<ImapFlagChange>(), changes)
            assertNull(changes.firstOrNull())
        }
    }
}
