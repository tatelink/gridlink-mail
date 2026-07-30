package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a permanent purge behaves on a server WITHOUT the UIDPLUS extension, driven against a
 * scripted server on loopback (Codeberg #99).
 *
 * The user's scenario: a Trash that another client — or the user, months ago — has left messages
 * flagged `\Deleted` in. "Empty trash" must not take those with it. `UID EXPUNGE <set>` says
 * "erase exactly these" and is the only command that can promise that; it belongs to UIDPLUS
 * (RFC 4315), which not every server implements. The bare `EXPUNGE` that used to stand in for it
 * erases the folder's whole `\Deleted` set, so it destroyed mail nobody had designated — once per
 * chunk, i.e. up to fifty times over a folder-wide "Empty trash".
 *
 * These tests pin the shipped policy, [purgeWithoutUidPlus] = [PurgeWithoutUidPlus.LEAVE_FLAGGED]:
 * no bare `EXPUNGE`, ever. Flipping that one line is meant to flip the assertions here with it —
 * a purge policy is not something to change without also stating, in a test, what it now does.
 */
class ExpungeTest {

    /**
     * Enough UIDs to span several `UID STORE` chunks. That is the whole point: what happens when
     * the server has no UIDPLUS is decided once for the operation, not once per chunk.
     */
    private val manyUids = (1L..450L).toList()

    /**
     * A server that either implements UIDPLUS or does not, and says so where a real one does.
     * [advertiseAtLogin] puts the list in the LOGIN completion's `[CAPABILITY …]` response code
     * (what Dovecot, Gmail and Stalwart all do) instead of waiting for a `CAPABILITY` command.
     */
    private fun scriptedServer(uidPlus: Boolean, advertiseAtLogin: Boolean = false) = FakeImapServer { tag, line ->
        val uidPlusName = if (uidPlus) " UIDPLUS" else ""
        when {
            advertiseAtLogin && line.startsWith("LOGIN") ->
                "$tag OK [CAPABILITY IMAP4rev1 LITERAL+$uidPlusName] logged in\r\n"
            line.startsWith("CAPABILITY") ->
                "* CAPABILITY IMAP4rev1 LITERAL+$uidPlusName\r\n$tag OK capability completed\r\n"
            line.startsWith("SELECT") -> selectResponse(tag, exists = manyUids.size)
            // A server without UIDPLUS does not merely dislike this command — it has never
            // heard of it, which is exactly how it answers.
            line.startsWith("UID EXPUNGE") -> if (uidPlus) ok(tag) else "$tag BAD unknown command\r\n"
            // No MOVE extension either: the copy fallback is the path under test.
            line.startsWith("UID MOVE") -> "$tag NO [CANNOT] MOVE unsupported\r\n"
            line.startsWith("UID COPY") -> {
                val set = line.removePrefix("UID COPY ").substringBefore(' ')
                "$tag OK [COPYUID 1 $set $set] copy completed\r\n"
            }
            else -> ok(tag)
        }
    }

    private fun List<String>.setsOf(verb: String): List<String> =
        filter { it.startsWith("$verb ") }.map { it.removePrefix("$verb ").substringBefore(' ') }

    private fun List<String>.uidsFlagged(): List<Long> =
        setsOf("UID STORE").flatMap { expandUidSet(it) }.sorted()

    // ---- delete() ----

    /**
     * The defect itself. A server that has no `UID EXPUNGE` must not see a single bare `EXPUNGE`,
     * however many chunks the purge is cut into — one per chunk is what used to be sent.
     */
    @Test
    fun `a purge on a server without UIDPLUS sends no bare EXPUNGE at all`() {
        scriptedServer(uidPlus = false).use { server ->
            server.session().use { session ->
                session.select("Trash")
                session.delete(manyUids)
            }

            val issued = server.issued()
            assertTrue("a bare EXPUNGE went out: $issued", issued.none { it.trim() == "EXPUNGE" })
            // And it is not attempted-then-recovered either: the command is never sent, because
            // the capability was asked instead of the failure being read as an answer.
            assertTrue("UID EXPUNGE was attempted: $issued", issued.none { it.startsWith("UID EXPUNGE") })
        }
    }

    /**
     * The decision belongs to the operation, not to the chunk: one question for a purge that
     * takes several `UID STORE`s. This is what stops a folder-wide command from being applied
     * fifty times.
     */
    @Test
    fun `the capability is asked once for a purge spanning several chunks`() {
        scriptedServer(uidPlus = false).use { server ->
            server.session().use { session ->
                session.select("Trash")
                session.delete(manyUids)
            }

            val issued = server.issued()
            assertTrue("only one chunk: $issued", issued.setsOf("UID STORE").size > 1)
            assertEquals("the capability was asked per chunk: $issued", 1, issued.count { it.startsWith("CAPABILITY") })
        }
    }

    /**
     * Stopping short of the erase does not mean stopping short of the flagging: every message the
     * caller named is flagged `\Deleted`, and no other is. Under
     * [PurgeWithoutUidPlus.LEAVE_FLAGGED] that flag is all the server is left with, so it had
     * better cover exactly the right messages.
     */
    @Test
    fun `every named message is flagged deleted, and only those`() {
        scriptedServer(uidPlus = false).use { server ->
            server.session().use { session ->
                session.select("Trash")
                session.delete(manyUids)
            }

            assertEquals(manyUids, server.issued().uidsFlagged())
        }
    }

    /** The normal path, unchanged: a server with UIDPLUS erases by UID, chunk by chunk. */
    @Test
    fun `a server advertising UIDPLUS erases exactly the named uids`() {
        scriptedServer(uidPlus = true).use { server ->
            server.session().use { session ->
                session.select("Trash")
                session.delete(manyUids)
            }

            val issued = server.issued()
            assertEquals(listOf("""SELECT "Trash"""", "CAPABILITY"), issued.take(2))
            assertTrue("a bare EXPUNGE went out: $issued", issued.none { it.trim() == "EXPUNGE" })
            // Each chunk flagged then erased, by the same set: nothing wider than what was named.
            assertEquals(issued.setsOf("UID STORE"), issued.setsOf("UID EXPUNGE"))
            assertEquals(manyUids, issued.uidsFlagged())
        }
    }

    /**
     * A `UID EXPUNGE` refused by a server that DOES advertise UIDPLUS is a failure, not a cue to
     * reach for the folder-wide command. The destroy worker retries; a repeated destroy costs
     * nothing, whereas the old recovery cost other people's mail.
     */
    @Test
    fun `a refused UID EXPUNGE fails instead of falling back to the folder`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 UIDPLUS\r\n$tag OK done\r\n"
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID EXPUNGE") -> "$tag NO [SERVERBUG] not now\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                session.select("Trash")
                val failure = runCatching { session.delete(listOf(1L, 2L, 3L)) }.exceptionOrNull()
                assertTrue("expected an ImapException, got $failure", failure is ImapException)
            }

            val issued = server.issued()
            assertTrue("a bare EXPUNGE went out: $issued", issued.none { it.trim() == "EXPUNGE" })
        }
    }

    /** Nothing to destroy: no flagging, no capability question, no command at all. */
    @Test
    fun `deleting nothing asks the server nothing`() {
        scriptedServer(uidPlus = false).use { server ->
            server.session().use { it.delete(emptyList()) }

            assertEquals(listOf("LOGOUT"), server.issued())
        }
    }

    // ---- the capability list itself ----

    /** The usual case costs no round trip: the server volunteered its list at login. */
    @Test
    fun `capabilities advertised at login are used without a CAPABILITY command`() {
        scriptedServer(uidPlus = true, advertiseAtLogin = true).use { server ->
            server.session().use { session ->
                session.select("Trash")
                session.delete(listOf(1L, 2L))
            }

            val issued = server.issued()
            assertTrue("a CAPABILITY round trip was made: $issued", issued.none { it.startsWith("CAPABILITY") })
            assertEquals(listOf("1:2"), issued.setsOf("UID EXPUNGE"))
        }
    }

    /** RFC 3501 §6.1.1: capability names are case-insensitive, wherever they are read. */
    @Test
    fun `a lower-case capability list is understood`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("CAPABILITY") -> "* capability imap4rev1 uidplus\r\n$tag OK done\r\n"
                line.startsWith("SELECT") -> selectResponse(tag, exists = 2)
                else -> ok(tag)
            }
        }.use { server ->
            val supported = server.session().use { it.hasCapability("UIDPLUS") }

            assertTrue(supported)
        }
    }

    /**
     * The response-code form, read off the flattened line because `[` and `]` are not IMAP token
     * delimiters — the parser hands back `[CAPABILITY` and `UIDPLUS]` as atoms.
     */
    @Test
    fun `a CAPABILITY response code is read whole`() {
        val tagged = listOf("a1", "OK", "[CAPABILITY", "IMAP4rev1", "LITERAL+", "UIDPLUS]", "logged", "in")

        assertEquals(setOf("IMAP4REV1", "LITERAL+", "UIDPLUS"), capabilitiesInResponseCode(tagged))
    }

    /** A completion without the code answers nothing, so the question stays open for a query. */
    @Test
    fun `a login completion without a CAPABILITY code advertises nothing`() {
        assertNull(capabilitiesInResponseCode(listOf("a1", "OK", "logged", "in")))
    }

    /** A `CAPABILITY` answer with no list at all reads as "no optional extension". */
    @Test
    fun `an empty capability answer denies every extension`() {
        assertFalse(CAP_UIDPLUS in parseCapabilities(listOf(listOf("a1", "OK", "done"))))
    }

    // ---- move()'s copy fallback ----

    /**
     * The same fallback, the same danger: a server with neither MOVE nor UIDPLUS made the copy
     * path expunge the whole source folder, once per chunk. It must now leave the originals
     * flagged instead.
     */
    @Test
    fun `a move falling back to copy never expunges the folder`() {
        scriptedServer(uidPlus = false).use { server ->
            val mapping = server.session().use { session ->
                session.select("INBOX")
                session.move(manyUids, "Archive")
            }

            val issued = server.issued()
            assertTrue("a bare EXPUNGE went out: $issued", issued.none { it.trim() == "EXPUNGE" })
            assertTrue("UID EXPUNGE was attempted: $issued", issued.none { it.startsWith("UID EXPUNGE") })
            // The move itself still happened, in several chunks, and still reports its COPYUIDs.
            assertTrue("only one chunk: $issued", issued.setsOf("UID COPY").size > 1)
            assertEquals(manyUids, issued.uidsFlagged())
            assertEquals(manyUids.associateWith { it }, mapping)
        }
    }

    /** With UIDPLUS the copy fallback erases only what it copied — one `UID EXPUNGE` per chunk. */
    @Test
    fun `a move falling back to copy erases by uid when the server can`() {
        scriptedServer(uidPlus = true).use { server ->
            server.session().use { session ->
                session.select("INBOX")
                session.move(manyUids, "Archive")
            }

            val issued = server.issued()
            assertTrue("a bare EXPUNGE went out: $issued", issued.none { it.trim() == "EXPUNGE" })
            assertEquals(issued.setsOf("UID COPY"), issued.setsOf("UID EXPUNGE"))
        }
    }
}
