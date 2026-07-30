package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

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
 * These tests pin the shipped policy, [purgeWithoutUidPlus] =
 * [PurgeWithoutUidPlus.EXPUNGE_WHEN_ONLY_OURS]: ask the server what is flagged, erase only when
 * the answer holds nothing but our own. Flipping that one line is meant to flip assertions here
 * with it — a purge policy is not something to change without also stating, in a test, what it
 * now does.
 */
class ExpungeTest {

    /**
     * Enough UIDs to span several `UID STORE` chunks. That is the whole point: what happens when
     * the server has no UIDPLUS is decided once for the operation, not once per chunk.
     */
    private val manyUids = (1L..450L).toList()

    /** What each scripted server still holds flagged `\Deleted`, read after the session closed. */
    private val flaggedPerServer = mutableMapOf<FakeImapServer, () -> List<Long>>()

    /**
     * A server that either implements UIDPLUS or does not, and — crucially — really keeps track
     * of what is flagged `\Deleted` instead of reciting a canned answer: `UID STORE +FLAGS` adds
     * to the set, `UID SEARCH DELETED` reports it, a bare `EXPUNGE` empties it. Anything the test
     * puts in [flaggedByOthers] is in the folder before this session ever connects, exactly as a
     * message flagged from Thunderbird would be.
     *
     * [advertiseAtLogin] puts the capability list in the LOGIN completion's `[CAPABILITY …]`
     * response code (what Dovecot, Gmail and Stalwart all do) instead of waiting for a
     * `CAPABILITY` command. [forgetsFlagsFor] drops ids from what the server reports as flagged,
     * standing in for a message that has meanwhile gone or had its flag cleared elsewhere.
     */
    private fun scriptedServer(
        uidPlus: Boolean,
        advertiseAtLogin: Boolean = false,
        flaggedByOthers: List<Long> = emptyList(),
        forgetsFlagsFor: Set<Long> = emptySet(),
    ): FakeImapServer {
        val flaggedOnServer: MutableSet<Long> =
            Collections.synchronizedSet(sortedSetOf<Long>().apply { addAll(flaggedByOthers) })
        val server = FakeImapServer { tag, line ->
            val uidPlusName = if (uidPlus) " UIDPLUS" else ""
            when {
                advertiseAtLogin && line.startsWith("LOGIN") ->
                    "$tag OK [CAPABILITY IMAP4rev1 LITERAL+$uidPlusName] logged in\r\n"
                line.startsWith("CAPABILITY") ->
                    "* CAPABILITY IMAP4rev1 LITERAL+$uidPlusName\r\n$tag OK capability completed\r\n"
                line.startsWith("SELECT") -> selectResponse(tag, exists = manyUids.size)
                line.startsWith("UID STORE") -> {
                    val set = line.removePrefix("UID STORE ").substringBefore(' ')
                    if (line.contains("+FLAGS (\\Deleted)")) flaggedOnServer.addAll(expandUidSet(set))
                    ok(tag)
                }
                line.startsWith("UID SEARCH DELETED") ->
                    searchResponse(tag, flaggedOnServer.toList() - forgetsFlagsFor)
                // A server without UIDPLUS does not merely dislike this command — it has never
                // heard of it, which is exactly how it answers.
                line.startsWith("UID EXPUNGE") -> if (uidPlus) ok(tag) else "$tag BAD unknown command\r\n"
                line == "EXPUNGE" -> { flaggedOnServer.clear(); ok(tag) }
                // No MOVE extension either: the copy fallback is the path under test.
                line.startsWith("UID MOVE") -> "$tag NO [CANNOT] MOVE unsupported\r\n"
                line.startsWith("UID COPY") -> {
                    val set = line.removePrefix("UID COPY ").substringBefore(' ')
                    "$tag OK [COPYUID 1 $set $set] copy completed\r\n"
                }
                else -> ok(tag)
            }
        }
        flaggedPerServer[server] = { flaggedOnServer.toList() }
        return server
    }

    private fun FakeImapServer.stillFlagged(): List<Long> = flaggedPerServer.getValue(this)()

    private fun List<String>.setsOf(verb: String): List<String> =
        filter { it.startsWith("$verb ") }.map { it.removePrefix("$verb ").substringBefore(' ') }

    private fun List<String>.uidsFlagged(): List<Long> =
        setsOf("UID STORE").flatMap { expandUidSet(it) }.sorted()

    private fun List<String>.bareExpunges(): Int = count { it == "EXPUNGE" }

    // ---- delete(): the conditional purge ----

    /**
     * The defect itself. Somebody else has a message flagged in this Trash; the purge must leave
     * it alone, which on a server with no `UID EXPUNGE` means not erasing anything at all — and
     * not once per chunk either, which is what used to happen.
     */
    @Test
    fun `a foreign flagged message stops the purge, over every chunk`() {
        scriptedServer(uidPlus = false, flaggedByOthers = listOf(9_000L)).use { server ->
            server.session().use { session ->
                session.select("Trash")
                session.delete(manyUids)
            }

            val issued = server.issued()
            assertEquals("a bare EXPUNGE went out: $issued", 0, issued.bareExpunges())
            // Not attempted-then-recovered either: with the capability known, the command that
            // does not exist on this server is never sent.
            assertTrue("UID EXPUNGE was attempted: $issued", issued.none { it.startsWith("UID EXPUNGE") })
            // Somebody else's message is still there, and so is everything we flagged.
            assertEquals(manyUids + 9_000L, server.stillFlagged())
        }
    }

    /**
     * The other half of the decision: nothing foreign is flagged, so the folder can be emptied
     * for real — with ONE `EXPUNGE`, not one per chunk.
     */
    @Test
    fun `a folder flagged by nobody else is emptied with exactly one EXPUNGE`() {
        scriptedServer(uidPlus = false).use { server ->
            server.session().use { session ->
                session.select("Trash")
                session.delete(manyUids)
            }

            val issued = server.issued()
            assertTrue("several chunks were expected: $issued", issued.setsOf("UID STORE").size > 1)
            assertEquals("not exactly one EXPUNGE: $issued", 1, issued.bareExpunges())
            assertEquals(emptyList<Long>(), server.stillFlagged())
            // And the EXPUNGE came last, after every chunk had been flagged.
            assertEquals("EXPUNGE", issued.filterNot { it == "LOGOUT" }.last())
        }
    }

    /**
     * The question belongs to the operation, not to the chunk: one `UID SEARCH DELETED` and one
     * `CAPABILITY` for a purge that takes several `UID STORE`s. This is what stops a folder-wide
     * command — and now a folder-wide question — from being repeated fifty times.
     */
    @Test
    fun `the server is questioned once for a purge spanning several chunks`() {
        scriptedServer(uidPlus = false).use { server ->
            server.session().use { session ->
                session.select("Trash")
                session.delete(manyUids)
            }

            val issued = server.issued()
            assertTrue("only one chunk: $issued", issued.setsOf("UID STORE").size > 1)
            assertEquals("the capability was asked per chunk: $issued", 1, issued.count { it.startsWith("CAPABILITY") })
            assertEquals("DELETED was searched per chunk: $issued", 1, issued.count { it.startsWith("UID SEARCH") })
        }
    }

    /**
     * A STRICT SUBSET is a yes. The server reports fewer flagged messages than we flagged — one
     * had already gone, or another client cleared its flag — so every id an `EXPUNGE` could
     * reach is still one of ours. Refusing here would leave the Trash un-emptied for no gain.
     */
    @Test
    fun `a subset of our own flags still allows the purge`() {
        scriptedServer(uidPlus = false, forgetsFlagsFor = setOf(7L, 42L)).use { server ->
            server.session().use { session ->
                session.select("Trash")
                session.delete(manyUids)
            }

            assertEquals("not exactly one EXPUNGE: ${server.issued()}", 1, server.issued().bareExpunges())
        }
    }

    /**
     * An empty answer is a no — not because it is unsafe, but because there is nothing left to
     * erase and the command would be pure noise on the wire.
     */
    @Test
    fun `a folder the server reports as holding nothing flagged is left alone`() {
        scriptedServer(uidPlus = false, forgetsFlagsFor = manyUids.toSet()).use { server ->
            server.session().use { session ->
                session.select("Trash")
                session.delete(manyUids)
            }

            assertEquals("an EXPUNGE went out for nothing: ${server.issued()}", 0, server.issued().bareExpunges())
        }
    }

    /**
     * The cap on the answer. A folder holding far more flagged messages than we flagged must be
     * a refusal, and must not be read into memory whole to find that out: the search is capped at
     * one more than our own count, and a truncated answer can never fit inside a set our size.
     */
    @Test
    fun `a folder full of foreign flags refuses the purge`() {
        scriptedServer(uidPlus = false, flaggedByOthers = (10_000L..20_000L).toList()).use { server ->
            server.session().use { session ->
                session.select("Trash")
                session.delete(listOf(1L, 2L, 3L))
            }

            val issued = server.issued()
            assertEquals("a bare EXPUNGE went out: $issued", 0, issued.bareExpunges())
            assertEquals(listOf("UID SEARCH DELETED"), issued.filter { it.startsWith("UID SEARCH") })
            // Ours are flagged and everybody else's are untouched — 10 001 + 3 still there.
            assertEquals(10_004, server.stillFlagged().size)
        }
    }

    /**
     * Stopping short of the erase does not mean stopping short of the flagging: every message the
     * caller named is flagged `\Deleted`, and no other. Under a refusal that flag is all the
     * server is left with, so it had better cover exactly the right messages.
     */
    @Test
    fun `every named message is flagged deleted, and only those`() {
        scriptedServer(uidPlus = false, flaggedByOthers = listOf(9_000L)).use { server ->
            server.session().use { session ->
                session.select("Trash")
                session.delete(manyUids)
            }

            assertEquals(manyUids, server.issued().uidsFlagged())
        }
    }

    /** The normal path, unchanged: a server with UIDPLUS erases by UID and asks nothing extra. */
    @Test
    fun `a server advertising UIDPLUS erases exactly the named uids`() {
        scriptedServer(uidPlus = true, flaggedByOthers = listOf(9_000L)).use { server ->
            server.session().use { session ->
                session.select("Trash")
                session.delete(manyUids)
            }

            val issued = server.issued()
            assertEquals(listOf("""SELECT "Trash"""", "CAPABILITY"), issued.take(2))
            assertEquals("a bare EXPUNGE went out: $issued", 0, issued.bareExpunges())
            // No question to ask: UID EXPUNGE already names its victims.
            assertTrue("DELETED was searched: $issued", issued.none { it.startsWith("UID SEARCH") })
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

            assertEquals("a bare EXPUNGE went out: ${server.issued()}", 0, server.issued().bareExpunges())
        }
    }

    /** Nothing to destroy: no flagging, no question, no command at all. */
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
     * path expunge the whole source folder, once per chunk. With somebody else's message flagged
     * in that folder, it must now erase nothing.
     */
    @Test
    fun `a move falling back to copy spares a folder flagged by somebody else`() {
        scriptedServer(uidPlus = false, flaggedByOthers = listOf(9_000L)).use { server ->
            val mapping = server.session().use { session ->
                session.select("INBOX")
                session.move(manyUids, "Archive")
            }

            val issued = server.issued()
            assertEquals("a bare EXPUNGE went out: $issued", 0, issued.bareExpunges())
            assertTrue("UID EXPUNGE was attempted: $issued", issued.none { it.startsWith("UID EXPUNGE") })
            // The move itself still happened, in several chunks, and still reports its COPYUIDs.
            assertTrue("only one chunk: $issued", issued.setsOf("UID COPY").size > 1)
            assertEquals(manyUids, issued.uidsFlagged())
            assertEquals(manyUids.associateWith { it }, mapping)
        }
    }

    /** Nothing foreign flagged: the copy fallback clears the originals with one `EXPUNGE`. */
    @Test
    fun `a move falling back to copy purges once when the folder is only ours`() {
        scriptedServer(uidPlus = false).use { server ->
            server.session().use { session ->
                session.select("INBOX")
                session.move(manyUids, "Archive")
            }

            val issued = server.issued()
            assertEquals("not exactly one EXPUNGE: $issued", 1, issued.bareExpunges())
            assertEquals("DELETED was searched per chunk: $issued", 1, issued.count { it.startsWith("UID SEARCH") })
        }
    }

    /** With UIDPLUS the copy fallback erases only what it copied — one `UID EXPUNGE` per chunk. */
    @Test
    fun `a move falling back to copy erases by uid when the server can`() {
        scriptedServer(uidPlus = true, flaggedByOthers = listOf(9_000L)).use { server ->
            server.session().use { session ->
                session.select("INBOX")
                session.move(manyUids, "Archive")
            }

            val issued = server.issued()
            assertEquals("a bare EXPUNGE went out: $issued", 0, issued.bareExpunges())
            assertTrue("DELETED was searched: $issued", issued.none { it.startsWith("UID SEARCH") })
            assertEquals(issued.setsOf("UID COPY"), issued.setsOf("UID EXPUNGE"))
        }
    }

    /** A move the server performs itself flags nothing, so there is nothing to purge or ask. */
    @Test
    fun `a move the server performs itself asks and purges nothing`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID MOVE") -> "$tag OK [COPYUID 1 1:3 7:9] moved\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                session.select("INBOX")
                session.move(listOf(1L, 2L, 3L), "Archive")
            }

            val issued = server.issued()
            assertTrue("a search went out: $issued", issued.none { it.startsWith("UID SEARCH") })
            assertTrue("the capability was asked for nothing: $issued", issued.none { it.startsWith("CAPABILITY") })
            assertEquals(0, issued.bareExpunges())
        }
    }
}
