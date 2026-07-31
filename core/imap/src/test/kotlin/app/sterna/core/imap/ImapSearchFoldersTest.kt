package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The multi-folder walk, driven against a scripted server on loopback: what really leaves the
 * client, folder by folder, and how the result cap behaves once a local filter is in the way.
 */
class ImapSearchFoldersTest {

    private val fullQuery = buildImapSearch(
        ImapSearchCriteria(
            text = "invoice",
            from = "alex.rivera@masto.top",
            recipient = "team@masto.top",
            subject = "facture",
            afterMillis = LocalDate.of(2026, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            beforeMillis = LocalDate.of(2026, 6, 16).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1000,
        ),
    )

    /** The whole point: the command that goes on the wire for a fully-filled advanced search. */
    @Test
    fun `a full query leaves the client as one UID SEARCH per selected folder`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag)
                line.startsWith("UID SEARCH") -> searchResponse(tag, listOf(7L))
                line.startsWith("UID FETCH") -> fetchResponse(tag, uidsOf(line)) { false }
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                session.searchFolders(listOf("INBOX", "Archive"), fullQuery, requireAttachment = false, limit = 50)
            }
            assertEquals(
                listOf(
                    """SELECT "INBOX"""",
                    """UID SEARCH FROM "alex.rivera@masto.top" OR TO "team@masto.top" CC "team@masto.top" """ +
                        """SUBJECT "facture" SINCE 1-Jun-2026 BEFORE 16-Jun-2026 TEXT "invoice"""",
                    """UID FETCH 7 (UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE)""",
                    """SELECT "Archive"""",
                    """UID SEARCH FROM "alex.rivera@masto.top" OR TO "team@masto.top" CC "team@masto.top" """ +
                        """SUBJECT "facture" SINCE 1-Jun-2026 BEFORE 16-Jun-2026 TEXT "invoice"""",
                    """UID FETCH 7 (UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE)""",
                    "LOGOUT",
                ),
                server.issued(),
            )
        }
    }

    /** Every hit must stay attached to the folder it came from: a UID means nothing without one. */
    @Test
    fun `hits keep the folder they were found in`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag)
                // Both folders answer with the SAME uid — a mix-up would be invisible otherwise.
                line.startsWith("UID SEARCH") -> searchResponse(tag, listOf(11L))
                line.startsWith("UID FETCH") -> fetchResponse(tag, uidsOf(line)) { false }
                else -> ok(tag)
            }
        }.use { server ->
            val hits = server.session().use { session ->
                session.searchFolders(listOf("INBOX", "Sent"), fullQuery, requireAttachment = false, limit = 50)
            }
            assertEquals(listOf("INBOX", "Sent"), hits.map { it.mailbox })
            assertTrue(hits.all { it.messages.single().uid == 11L })
        }
    }

    /** A folder with no match contributes nothing and costs no FETCH. */
    @Test
    fun `an empty folder is skipped without a fetch`() {
        // The SEARCH line does not name a folder — the preceding SELECT does, so the scripted
        // server tracks it, exactly like a real one.
        var selected = ""
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> {
                    selected = line.removePrefix("SELECT ").trim('"')
                    selectResponse(tag)
                }
                line.startsWith("UID SEARCH") ->
                    searchResponse(tag, if (selected == "INBOX") listOf(3L) else emptyList())
                line.startsWith("UID FETCH") -> fetchResponse(tag, uidsOf(line)) { false }
                else -> ok(tag)
            }
        }.use { server ->
            val hits = server.session().use { session ->
                session.searchFolders(listOf("INBOX", "Junk"), fullQuery, requireAttachment = false, limit = 50)
            }
            assertEquals(listOf("INBOX"), hits.map { it.mailbox })
            assertEquals(1, server.issued().count { it.startsWith("UID FETCH") })
        }
    }

    /**
     * The cap counts KEPT messages, not examined ones. With one message in ten carrying a file,
     * asking for 5 must walk enough candidates to find 5 — not stop at the first batch and
     * report 5 hits as if that were all there was.
     */
    @Test
    fun `the attachment filter fills the cap instead of truncating the scan`() {
        val matching = (1L..300L).toList()
        var fetched = 0
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 300)
                line.startsWith("UID SEARCH") -> searchResponse(tag, matching)
                line.startsWith("UID FETCH") -> {
                    val uids = uidsOf(line)
                    fetched += uids.size
                    fetchResponse(tag, uids) { uid -> uid % 10 == 0L }
                }
                else -> ok(tag)
            }
        }.use { server ->
            val hits = server.session().use { session ->
                session.searchFolders(listOf("INBOX"), fullQuery, requireAttachment = true, limit = 5)
            }
            val messages = hits.single().messages
            assertEquals(5, messages.size)
            assertTrue(messages.all { it.hasAttachment })
            // Newest (highest UID) first, and only multiples of ten survive the filter.
            assertEquals(listOf(300L, 290L, 280L, 270L, 260L), messages.map { it.uid })
            // It had to look at more than it kept — but in batches, not one message at a time.
            assertTrue("examined $fetched candidates", fetched in 6..100)
            // The cap was filled, so this IS the answer: nothing to warn the counter about.
            assertFalse(hits.single().scanTruncated)
        }
    }

    /**
     * The scan cap is the one case where the answer may be short: the walk stops with candidates
     * left and the result cap unfilled. It has to SAY so, or a count on screen would present a
     * partial scan as a total.
     */
    @Test
    fun `a scan that gives up on its cap says the answer is short`() {
        // Well past the 1000-candidate scan cap, and not one of them carries a file.
        val matching = (1L..1_500L).toList()
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 1_500)
                line.startsWith("UID SEARCH") -> searchResponse(tag, matching)
                line.startsWith("UID FETCH") -> fetchResponse(tag, uidsOf(line)) { false }
                else -> ok(tag)
            }
        }.use { server ->
            val hits = server.session().use { session ->
                session.searchFolders(listOf("INBOX"), fullQuery, requireAttachment = true, limit = 5)
            }
            // The folder is reported even with nothing kept: the truncation is the information.
            assertEquals(listOf("INBOX"), hits.map { it.mailbox })
            assertTrue(hits.single().messages.isEmpty())
            assertTrue(hits.single().scanTruncated)
        }
    }

    /** Without the attachment filter, the newest [limit] UIDs are the answer: exactly one FETCH. */
    @Test
    fun `an unfiltered search fetches the cap once`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 300)
                line.startsWith("UID SEARCH") -> searchResponse(tag, (1L..300L).toList())
                line.startsWith("UID FETCH") -> fetchResponse(tag, uidsOf(line)) { false }
                else -> ok(tag)
            }
        }.use { server ->
            val hits = server.session().use { session ->
                session.searchFolders(listOf("INBOX"), fullQuery, requireAttachment = false, limit = 5)
            }
            assertEquals(5, hits.single().messages.size)
            assertEquals(listOf(300L, 299L, 298L, 297L, 296L), hits.single().messages.map { it.uid })
            val fetches = server.issued().filter { it.startsWith("UID FETCH") }
            assertEquals(1, fetches.size)
            assertEquals(5, uidsOf(fetches.single()).size)
        }
    }

    /**
     * The flagged filter, end to end on a socket: `FLAGGED` really leaves the client as a SEARCH
     * key, so the SERVER decides which UIDs come back.
     *
     * The counts are the point. The 300 unflagged messages in this folder are never fetched: the
     * server names the two flagged UIDs and one FETCH brings them home. Had the filter been built
     * like the attachment one, the client would have pulled envelopes in batches of fifty looking
     * for stars — a scan cap and a truncated count for a question IMAP answers in one word.
     */
    @Test
    fun `flagged is answered by the server, not walked through on the client`() {
        val flaggedUids = listOf(42L, 77L)
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 300)
                // Only a search CARRYING the key gets the short list; anything else gets the
                // whole folder, so a dropped key would blow the fetch count wide open.
                line.startsWith("UID SEARCH") ->
                    searchResponse(tag, if ("FLAGGED" in line) flaggedUids else (1L..300L).toList())
                line.startsWith("UID FETCH") -> fetchResponse(tag, uidsOf(line), flags = { "\\Flagged" }) { false }
                else -> ok(tag)
            }
        }.use { server ->
            val starred = buildImapSearch(ImapSearchCriteria(flagged = true))
            val hits = server.session().use { session ->
                session.searchFolders(listOf("INBOX"), starred, requireAttachment = false, limit = 50)
            }
            assertEquals(listOf("UID SEARCH FLAGGED"), server.issued().filter { it.startsWith("UID SEARCH") })
            assertEquals(listOf(77L, 42L), hits.single().messages.map { it.uid })
            assertTrue(hits.single().messages.all { it.flagged })
            // One FETCH of two UIDs: no batched candidate walk, no scan cap, so the answer is whole.
            val fetches = server.issued().filter { it.startsWith("UID FETCH") }
            assertEquals(1, fetches.size)
            assertEquals(flaggedUids.size, uidsOf(fetches.single()).size)
            assertFalse(hits.single().scanTruncated)
        }
    }

    /** The witness: with the switch off, no FLAGGED key is sent and the folder answers in full. */
    @Test
    fun `an unticked flagged switch sends no FLAGGED key`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag)
                line.startsWith("UID SEARCH") -> searchResponse(tag, listOf(8L))
                line.startsWith("UID FETCH") -> fetchResponse(tag, uidsOf(line)) { false }
                else -> ok(tag)
            }
        }.use { server ->
            val plain = buildImapSearch(ImapSearchCriteria(subject = "facture", flagged = false))
            server.session().use { session ->
                session.searchFolders(listOf("INBOX"), plain, requireAttachment = false, limit = 50)
            }
            assertEquals(
                listOf("""UID SEARCH SUBJECT "facture""""),
                server.issued().filter { it.startsWith("UID SEARCH") },
            )
        }
    }

    /** One unreadable folder must neither sink the search nor disappear in silence. */
    @Test
    fun `a folder that fails is reported and the others still answer`() {
        val failures = mutableListOf<String>()
        FakeImapServer { tag, line ->
            when {
                line.startsWith("""SELECT "Broken"""") -> "$tag NO [NOPERM] permission denied\r\n"
                line.startsWith("SELECT") -> selectResponse(tag)
                line.startsWith("UID SEARCH") -> searchResponse(tag, listOf(4L))
                line.startsWith("UID FETCH") -> fetchResponse(tag, uidsOf(line)) { false }
                else -> ok(tag)
            }
        }.use { server ->
            val hits = server.session().use { session ->
                session.searchFolders(
                    listOf("Broken", "INBOX"),
                    fullQuery,
                    requireAttachment = false,
                    limit = 50,
                ) { mailbox, _ -> failures += mailbox }
            }
            assertEquals(listOf("INBOX"), hits.map { it.mailbox })
            assertEquals(listOf("Broken"), failures)
        }
    }

    /** A caller that has moved on (next keystroke) stops the walk instead of tying up the connection. */
    @Test
    fun `the walk stops between folders once the caller is gone`() {
        var wanted = true
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag)
                line.startsWith("UID SEARCH") -> searchResponse(tag, listOf(2L))
                line.startsWith("UID FETCH") -> {
                    wanted = false // the caller was cancelled while the first folder was in flight
                    fetchResponse(tag, uidsOf(line)) { false }
                }
                else -> ok(tag)
            }
        }.use { server ->
            val hits = server.session().use { session ->
                session.searchFolders(
                    listOf("INBOX", "Archive", "Sent"),
                    fullQuery,
                    requireAttachment = false,
                    limit = 50,
                    stillWanted = { wanted },
                )
            }
            assertEquals(listOf("INBOX"), hits.map { it.mailbox })
            assertEquals(1, server.issued().count { it.startsWith("SELECT") })
        }
    }

    /**
     * A server that only supports US-ASCII answers `NO [BADCHARSET]`; the accented search then
     * retries undeclared rather than coming back empty-handed.
     */
    @Test
    fun `a rejected UTF-8 charset falls back to an undeclared search`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag)
                line.startsWith("UID SEARCH CHARSET") -> "$tag NO [BADCHARSET (US-ASCII)] unsupported\r\n"
                line.startsWith("UID SEARCH") -> searchResponse(tag, listOf(9L))
                line.startsWith("UID FETCH") -> fetchResponse(tag, uidsOf(line)) { false }
                else -> ok(tag)
            }
        }.use { server ->
            val accented = buildImapSearch(ImapSearchCriteria(subject = "école"))
            assertTrue(accented.needsUtf8)
            val hits = server.session().use { session ->
                session.searchFolders(listOf("INBOX"), accented, requireAttachment = false, limit = 50)
            }
            assertEquals(listOf(9L), hits.single().messages.map { it.uid })
            val searches = server.issued().filter { it.startsWith("UID SEARCH") }
            assertEquals(
                listOf("""UID SEARCH CHARSET UTF-8 SUBJECT "école"""", """UID SEARCH SUBJECT "école""""),
                searches,
            )
        }
    }

    /**
     * The cost of covering a whole account: SELECT + UID SEARCH per folder, plus one FETCH only
     * where there were hits. Ten folders with matches in two of them = 22 commands, not 10
     * connections. Printed so the figure in the report comes from a run, not from arithmetic.
     */
    @Test
    fun `covering ten folders costs two commands per folder plus a fetch where it matched`() {
        val folders = listOf("INBOX", "Archive", "Sent", "Drafts", "Trash", "Junk", "a", "b", "c", "d")
        var selected = ""
        val started = System.nanoTime()
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> {
                    selected = line.removePrefix("SELECT ").trim('"')
                    selectResponse(tag)
                }
                line.startsWith("UID SEARCH") ->
                    searchResponse(tag, if (selected == "INBOX" || selected == "Sent") listOf(5L, 6L) else emptyList())
                line.startsWith("UID FETCH") -> fetchResponse(tag, uidsOf(line)) { false }
                else -> ok(tag)
            }
        }.use { server ->
            val hits = server.session().use { session ->
                session.searchFolders(folders, fullQuery, requireAttachment = false, limit = 50)
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val issued = server.issued().filterNot { it == "LOGOUT" }
            assertEquals(listOf("INBOX", "Sent"), hits.map { it.mailbox })
            assertEquals(10, issued.count { it.startsWith("SELECT") })
            assertEquals(10, issued.count { it.startsWith("UID SEARCH") })
            assertEquals(2, issued.count { it.startsWith("UID FETCH") })
            assertEquals(22, issued.size)
            assertFalse(issued.any { it.startsWith("LOGIN") })
            println("[search cost] ${folders.size} folders -> ${issued.size} commands in ${elapsedMs}ms (loopback)")
        }
    }
}
