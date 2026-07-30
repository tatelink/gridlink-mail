package app.sterna.core.data.mail

import app.sterna.core.data.settings.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs the real conversation-grouping SQL ([conversationSql]) against an in-memory
 * SQLite engine, so the thread collapse / count / unread / snooze logic is verified
 * without an Android device.
 */
class ConversationSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE emails(
                    id TEXT, accountId TEXT, mailboxId TEXT, threadId TEXT,
                    subject TEXT, preview TEXT, receivedAt TEXT, fromName TEXT, fromEmail TEXT,
                    seen INTEGER, flagged INTEGER, hasAttachment INTEGER, sortKey INTEGER,
                    PRIMARY KEY(accountId, id)
                )
                """.trimIndent(),
            )
            st.executeUpdate("CREATE TABLE snoozed(emailId TEXT, accountId TEXT, until INTEGER, PRIMARY KEY(accountId, emailId))")
        }
    }

    @After fun tearDown() = db.close()

    private fun insert(
        id: String, threadId: String?, seen: Int, flagged: Int, sortKey: Long,
        mailbox: String = "inbox", accountId: String = "acc",
        // Only the sort-order cases care about these; every other case leaves them equal so
        // the subject/sender orders tie and the discriminating column is the one under test.
        subject: String = "subj", fromName: String = "N",
    ) {
        db.prepareStatement(
            "INSERT INTO emails VALUES(?, ?, ?, ?, ?, 'prev', '', ?, 'e', ?, ?, 0, ?)",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, accountId); ps.setString(3, mailbox); ps.setString(4, threadId)
            ps.setString(5, subject); ps.setString(6, fromName)
            ps.setInt(7, seen); ps.setInt(8, flagged); ps.setLong(9, sortKey)
            ps.executeUpdate()
        }
    }

    private fun snooze(id: String, untilMillis: Long, accountId: String = "acc") {
        db.prepareStatement("INSERT INTO snoozed VALUES(?, ?, ?)").use {
            it.setString(1, id); it.setString(2, accountId); it.setLong(3, untilMillis); it.executeUpdate()
        }
    }

    /** Run the grouping SQL for a single "inbox" mailbox; returns rows as maps. */
    private fun run(sort: SortOrder = SortOrder.DATE_DESC, unreadOnly: Boolean = false): List<Map<String, Any?>> {
        val sql = conversationSql(mailboxCount = 1, sort = sort, unreadOnly = unreadOnly)
        return db.prepareStatement(sql).use { ps ->
            // in-view sub-query, in-view count sub-query, outer WHERE — each binds "inbox".
            ps.setString(1, "inbox"); ps.setString(2, "inbox"); ps.setString(3, "inbox")
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            mapOf(
                                "id" to rs.getString("id"),
                                "threadCount" to rs.getInt("threadCount"),
                                "threadTotal" to rs.getInt("threadTotal"),
                                "threadUnread" to rs.getInt("threadUnread"),
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test fun collapsesThreadToLatestMessageWithCountAndUnread() {
        // A 2-message thread (m2 newest, m1 read / m2 unread) + a standalone read message.
        insert("m1", threadId = "T1", seen = 1, flagged = 0, sortKey = 100)
        insert("m2", threadId = "T1", seen = 0, flagged = 0, sortKey = 200)
        insert("s1", threadId = null, seen = 1, flagged = 0, sortKey = 150)

        val rows = run()
        assertEquals(2, rows.size)
        // Newest-first: the thread's representative (m2) then the standalone.
        assertEquals("m2", rows[0]["id"])
        assertEquals(2, rows[0]["threadCount"])
        assertEquals(0, rows[0]["threadUnread"]) // 0 = has an unread message
        assertEquals("s1", rows[1]["id"])
        assertEquals(1, rows[1]["threadCount"])
        assertEquals(1, rows[1]["threadUnread"]) // 1 = all read
    }

    @Test fun unreadOnlyKeepsThreadsWithAnyUnread() {
        insert("m1", threadId = "T1", seen = 1, flagged = 0, sortKey = 100)
        insert("m2", threadId = "T1", seen = 0, flagged = 0, sortKey = 200) // unread → thread kept
        insert("s1", threadId = null, seen = 1, flagged = 0, sortKey = 150) // read → dropped

        val rows = run(unreadOnly = true)
        assertEquals(1, rows.size)
        assertEquals("m2", rows[0]["id"])
    }

    @Test fun snoozedMessagesAreExcluded() {
        insert("s1", threadId = null, seen = 0, flagged = 0, sortKey = 100)
        insert("s2", threadId = null, seen = 0, flagged = 0, sortKey = 300) // would be first…
        snooze("s2", untilMillis = Long.MAX_VALUE) // …but snoozed into the future

        val rows = run()
        assertEquals(1, rows.size)
        assertEquals("s1", rows[0]["id"])
    }

    @Test fun accountScopeExcludesOtherAccountSharingAMailboxId() {
        // Two accounts whose inbox shares the same server-assigned mailbox id ("inbox")
        // — the case that made a single-account folder view show a mix of both accounts.
        insert("a1", threadId = null, seen = 1, flagged = 0, sortKey = 100, accountId = "accA")
        insert("b1", threadId = null, seen = 1, flagged = 0, sortKey = 200, accountId = "accB")

        val sql = conversationSql(mailboxCount = 1, sort = SortOrder.DATE_DESC, unreadOnly = false, hasAccountId = true)
        val rows = db.prepareStatement(sql).use { ps ->
            // in-view (mailbox, accountId), in-view count (mailbox, accountId), outer (mailbox, accountId).
            ps.setString(1, "inbox"); ps.setString(2, "accA")
            ps.setString(3, "inbox"); ps.setString(4, "accA")
            ps.setString(5, "inbox"); ps.setString(6, "accA")
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }
        assertEquals(listOf("a1"), rows) // only account A's mail; b1 is excluded
    }

    /** Single-account bound run for [accountId]'s "inbox"; returns the row ids. */
    private fun runScoped(accountId: String): List<String> {
        val sql = conversationSql(mailboxCount = 1, sort = SortOrder.DATE_DESC, unreadOnly = false, hasAccountId = true)
        return db.prepareStatement(sql).use { ps ->
            ps.setString(1, "inbox"); ps.setString(2, accountId)
            ps.setString(3, "inbox"); ps.setString(4, accountId)
            ps.setString(5, "inbox"); ps.setString(6, accountId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }
    }

    @Test fun sameEmailIdAcrossAccountsStaysScoped() {
        // Two sub-accounts of one login (issue #31) whose server minted the SAME email id
        // in the SAME-named mailbox — the composite (accountId, id) key case. Each scoped
        // view must show exactly its own row.
        insert("e1", threadId = null, seen = 0, flagged = 0, sortKey = 100, accountId = "accA")
        insert("e1", threadId = null, seen = 1, flagged = 0, sortKey = 200, accountId = "accB")

        assertEquals(listOf("e1"), runScoped("accA"))
        assertEquals(listOf("e1"), runScoped("accB"))
    }

    @Test fun snoozeIsScopedToItsAccount() {
        // Account A snoozes its "e1"; account B's message that happens to share the id
        // (same-server sub-accounts, issue #31) must stay visible.
        insert("e1", threadId = null, seen = 0, flagged = 0, sortKey = 100, accountId = "accA")
        insert("e1", threadId = null, seen = 0, flagged = 0, sortKey = 200, accountId = "accB")
        snooze("e1", untilMillis = Long.MAX_VALUE, accountId = "accA")

        assertEquals(emptyList<String>(), runScoped("accA")) // snoozed away
        assertEquals(listOf("e1"), runScoped("accB")) // untouched by A's snooze
    }

    /** Run the SQL as bound in production for the Inbox of account "acc" with a Sent folder:
     *  `g` and the outer WHERE scope to the Inbox; the chip sub-query `c` also takes the
     *  account-pinned ("acc", "sent") pair. */
    private fun runWithSent(): List<Map<String, Any?>> {
        val sql = conversationSql(
            mailboxCount = 1, sort = SortOrder.DATE_DESC, unreadOnly = false,
            hasAccountId = true, sentMailboxCount = 1,
        )
        return db.prepareStatement(sql).use { ps ->
            ps.setString(1, "inbox"); ps.setString(2, "acc") // in-view sub-query g
            ps.setString(3, "inbox"); ps.setString(4, "acc"); ps.setString(5, "sent"); ps.setString(6, "acc") // chip c
            ps.setString(7, "inbox"); ps.setString(8, "acc") // outer WHERE
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            mapOf(
                                "id" to rs.getString("id"),
                                "threadCount" to rs.getInt("threadCount"),
                                "threadTotal" to rs.getInt("threadTotal"),
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test fun chipCountsFolderPlusSentMembersMatchingTheUnfoldedConversation() {
        // A thread with one message in the Inbox and its reply filed in Sent: the unfolded
        // conversation shows both, so the chip says 2 — while the representative stays the
        // in-view (Inbox) message.
        insert("in1", threadId = "T1", seen = 0, flagged = 0, sortKey = 100, mailbox = "inbox")
        insert("sent1", threadId = "T1", seen = 1, flagged = 0, sortKey = 200, mailbox = "sent")

        val rows = runWithSent()
        assertEquals(1, rows.size)
        assertEquals("in1", rows[0]["id"])      // representative is the in-view (Inbox) message…
        assertEquals(2, rows[0]["threadCount"]) // …the chip counts Inbox + Sent members…
        assertEquals(2, rows[0]["threadTotal"]) // …and the account-wide total gates expandability.
    }

    @Test fun chipIgnoresTrashMembersEvenWithSentBound() {
        // Folder member + Sent reply + trashed member: the unfolded conversation shows 2
        // (Inbox + Sent), so the chip says 2; the trashed member neither counts nor elects
        // the representative, but keeps threadTotal at 3.
        insert("in1", threadId = "T1", seen = 1, flagged = 0, sortKey = 100, mailbox = "inbox")
        insert("sent1", threadId = "T1", seen = 1, flagged = 0, sortKey = 200, mailbox = "sent")
        insert("tr1", threadId = "T1", seen = 1, flagged = 0, sortKey = 300, mailbox = "trash")

        val rows = runWithSent()
        assertEquals(1, rows.size)
        assertEquals("in1", rows[0]["id"]) // representative = newest IN-VIEW member, not tr1/sent1
        assertEquals(2, rows[0]["threadCount"])
        assertEquals(3, rows[0]["threadTotal"])
    }

    @Test fun threadWithOnlySentMembersSurfacesNoRow() {
        // Row presence stays strictly folder-scoped: Sent members widen the chip of an
        // in-folder thread but never conjure a row of their own into the Inbox.
        insert("sent1", threadId = "T1", seen = 1, flagged = 0, sortKey = 100, mailbox = "sent")

        assertEquals(0, runWithSent().size)
    }

    @Test fun threadCountIgnoresMembersOutsideTheViewedFolder() {
        // No Sent folder bound: two members in the viewed folder, one filed elsewhere —
        // chip 2, total 3.
        insert("in1", threadId = "T1", seen = 1, flagged = 0, sortKey = 100, mailbox = "inbox")
        insert("in2", threadId = "T1", seen = 0, flagged = 0, sortKey = 200, mailbox = "inbox")
        insert("tr1", threadId = "T1", seen = 1, flagged = 0, sortKey = 300, mailbox = "trash")

        val rows = run()
        assertEquals(1, rows.size)
        assertEquals("in2", rows[0]["id"]) // representative = newest IN-VIEW member, not tr1
        assertEquals(2, rows[0]["threadCount"])
        assertEquals(3, rows[0]["threadTotal"])
    }

    @Test fun countsDoNotMixAccountsSharingMailboxAndThreadIds() {
        // Unified view (no account bind): two accounts whose server assigned the same
        // mailbox AND thread ids — one row EACH (grouping is per (account, thread)), and
        // the counts stay per the representative's account.
        insert("a1", threadId = "T1", seen = 0, flagged = 0, sortKey = 200, accountId = "accA")
        insert("b1", threadId = "T1", seen = 1, flagged = 0, sortKey = 100, accountId = "accB")

        val rows = run()
        assertEquals(2, rows.size)
        assertEquals("a1", rows[0]["id"])
        assertEquals(1, rows[0]["threadCount"]) // accB's b1 must not inflate the chip
        assertEquals(1, rows[0]["threadTotal"])
        assertEquals("b1", rows[1]["id"])
        assertEquals(1, rows[1]["threadCount"])
        assertEquals(1, rows[1]["threadTotal"])
    }

    @Test fun unifiedViewKeepsBothAccountsConversationsWhenThreadIdsCollide() {
        // The data-loss case: two accounts of the same server carry the SAME thread id, each
        // with a 2-message conversation. Grouped on the thread key alone, the two collapsed
        // into a single row and only the newest account's conversation survived — the other
        // account's mail was simply absent from the unified list.
        insert("a1", threadId = "T1", seen = 1, flagged = 0, sortKey = 100, accountId = "accA")
        insert("a2", threadId = "T1", seen = 0, flagged = 0, sortKey = 400, accountId = "accA")
        insert("b1", threadId = "T1", seen = 1, flagged = 0, sortKey = 200, accountId = "accB")
        insert("b2", threadId = "T1", seen = 1, flagged = 0, sortKey = 300, accountId = "accB")

        val rows = run()
        assertEquals(2, rows.size)
        // One representative per (account, thread), each with its own count and unread state.
        assertEquals("a2", rows[0]["id"])
        assertEquals(2, rows[0]["threadCount"])
        assertEquals(0, rows[0]["threadUnread"]) // accA's conversation has an unread member
        assertEquals("b2", rows[1]["id"])
        assertEquals(2, rows[1]["threadCount"])
        assertEquals(1, rows[1]["threadUnread"]) // accB's is fully read
    }

    @Test fun unreadOnlyKeepsEachAccountsThreadIndependently() {
        // Colliding thread ids again, filtered: accB's conversation is unread and must show
        // even though accA's — grouped under the same thread key — is fully read.
        insert("a1", threadId = "T1", seen = 1, flagged = 0, sortKey = 400, accountId = "accA")
        insert("b1", threadId = "T1", seen = 0, flagged = 0, sortKey = 100, accountId = "accB")

        val rows = run(unreadOnly = true)
        assertEquals(listOf("b1"), rows.map { it["id"] })
    }

    @Test fun sameEmailIdAndNoThreadAcrossAccountsKeepsBothRows() {
        // Thread-less messages group on their own id, so a colliding EMAIL id is the same
        // collapse hazard: both accounts' messages must still be listed.
        insert("e1", threadId = null, seen = 0, flagged = 0, sortKey = 200, accountId = "accA")
        insert("e1", threadId = null, seen = 0, flagged = 0, sortKey = 100, accountId = "accB")

        assertEquals(2, run().size)
    }

    @Test fun sentPairDoesNotLeakACollidingMailboxIdAcrossAccounts() {
        // Unified view: account B's Sent folder id ("X") collides with a plain folder of
        // account A. A's chip must not count its "X"-folder member (X is not A's Sent) —
        // the pair binding pins the Sent widening to account B — while B's chip still
        // counts its Sent reply.
        insert("a1", threadId = "T1", seen = 0, flagged = 0, sortKey = 200, accountId = "accA")
        insert("ax", threadId = "T1", seen = 1, flagged = 0, sortKey = 100, mailbox = "X", accountId = "accA")
        insert("b1", threadId = "T2", seen = 0, flagged = 0, sortKey = 300, accountId = "accB")
        insert("bx", threadId = "T2", seen = 1, flagged = 0, sortKey = 250, mailbox = "X", accountId = "accB")

        val sql = conversationSql(
            mailboxCount = 1, sort = SortOrder.DATE_DESC, unreadOnly = false,
            hasAccountId = false, sentMailboxCount = 1,
        )
        val rows = db.prepareStatement(sql).use { ps ->
            ps.setString(1, "inbox") // in-view sub-query g
            ps.setString(2, "inbox"); ps.setString(3, "accB"); ps.setString(4, "X") // chip c: view + accB's Sent pair
            ps.setString(5, "inbox") // outer WHERE
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString("id") to rs.getInt("threadCount")) }
            }
        }
        assertEquals(listOf("b1" to 2, "a1" to 1), rows)
    }

    // -- sort orders and the favourite pin (issue #111) -----------------------------------
    //
    // Until 1.4.5 every ORDER BY was prefixed with `flagged DESC`, so each of the five menu
    // entries silently meant "…, except the starred ones". Pinning is now its own entry,
    // SortOrder.FLAGGED_FIRST, and the other five must order on their own criterion alone.
    //
    // One fixture serves all six orders, arranged so that the starred rows LOSE under every
    // non-pinning order — otherwise the assertion would pass whether or not the prefix is
    // there. `p` is unread, mid-dated, first alphabetically by subject AND sender; `s2`/`s`
    // are starred, read, and sort behind it on every criterion but the pin.
    private fun insertSortFixture() {
        insert("p", threadId = null, seen = 0, flagged = 0, sortKey = 200, subject = "aaa", fromName = "Anna")
        insert("s", threadId = null, seen = 1, flagged = 1, sortKey = 100, subject = "zzz", fromName = "Zoe")
        insert("s2", threadId = null, seen = 1, flagged = 1, sortKey = 300, subject = "mmm", fromName = "Mona")
    }

    private fun ids(rows: List<Map<String, Any?>>) = rows.map { it["id"] }

    @Test fun conversationOrdersDoNotPinFavourites() {
        insertSortFixture()

        assertEquals(listOf("s2", "p", "s"), ids(run(SortOrder.DATE_DESC)))
        assertEquals(listOf("s", "p", "s2"), ids(run(SortOrder.DATE_ASC)))
        assertEquals(listOf("p", "s2", "s"), ids(run(SortOrder.SUBJECT)))
        assertEquals(listOf("p", "s2", "s"), ids(run(SortOrder.SENDER)))
        // The starkest case: "Unread first" used to put a starred READ message above an
        // unread one, which is the one thing the entry's name promises it will not do.
        assertEquals(listOf("p", "s2", "s"), ids(run(SortOrder.UNREAD_FIRST)))
    }

    @Test fun conversationFavouritesFirstPinsWhenChosen() {
        insertSortFixture()

        // The pre-1.4.5 behaviour, now reachable on purpose: starred at the top, newest-first
        // inside each group.
        assertEquals(listOf("s2", "s", "p"), ids(run(SortOrder.FLAGGED_FIRST)))
    }

    @Test fun favouritesFirstFollowsAStarOnAnyMessageOfTheThread() {
        // A thread whose OLD message is starred and whose latest is not. The old pin read
        // e.flagged — the representative (latest) row's flag — so this thread did not move;
        // FLAGGED_FIRST reads the thread's own state (g.threadFlagged) and pins it.
        insert("t1", threadId = "T", seen = 1, flagged = 1, sortKey = 100) // starred, older
        insert("t2", threadId = "T", seen = 1, flagged = 0, sortKey = 200) // representative, not starred
        insert("plain", threadId = null, seen = 1, flagged = 0, sortKey = 300)

        val rows = run(SortOrder.FLAGGED_FIRST)
        assertEquals(listOf("t2", "plain"), ids(rows)) // the thread pins, shown by its latest message
        assertEquals(2, rows[0]["threadCount"])
    }

    // -- flat (uncollapsed) mode, colliding mailbox ids -----------------------------------
    //
    // Conversation mode is exercised above; the same views also run FLAT, through a different
    // query ([pagingSql]), and the unified inbox binds its mailbox ids there with NO account
    // filter. Stalwart numbers mailboxes per account from "a", so on one server EVERY account's
    // Inbox is "a", its Trash "b", its Junk "c" — the ids collide wholesale, which is what makes
    // this worth pinning rather than assuming (Codeberg #107 probe).

    /** Flat list over [mailboxes]; [accountId] non-null pins it to one account (folder view),
     *  null leaves it unpinned (the unified inbox). Bind order: mailboxes, then account. */
    private fun runFlat(
        vararg mailboxes: String,
        accountId: String? = null,
        sort: SortOrder = SortOrder.DATE_DESC,
    ): List<String> {
        val sql = pagingSql(
            mailboxCount = mailboxes.size,
            sort = sort,
            unreadOnly = false,
            hasAccountId = accountId != null,
        )
        return db.prepareStatement(sql).use { ps ->
            mailboxes.forEachIndexed { i, m -> ps.setString(i + 1, m) }
            accountId?.let { ps.setString(mailboxes.size + 1, it) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }
    }

    @Test fun flatUnifiedSpansAccountsThatShareTheirInboxId() {
        // The intended case, and the reason the unified bind is unpinned: ten Stalwart accounts
        // all call their Inbox "a", so ONE bound id must surface all ten accounts' inboxes.
        insert("a1", threadId = null, seen = 0, flagged = 0, sortKey = 100, mailbox = "a", accountId = "accA")
        insert("b1", threadId = null, seen = 0, flagged = 0, sortKey = 200, mailbox = "a", accountId = "accB")

        assertEquals(listOf("b1", "a1"), runFlat("a"))
    }

    @Test fun flatFolderViewStaysPinnedToItsOwnAccount() {
        // Same rows, single-folder view: the account bind is what keeps a sibling account's
        // identically-numbered Inbox out (issues #31/#92).
        insert("a1", threadId = null, seen = 0, flagged = 0, sortKey = 100, mailbox = "a", accountId = "accA")
        insert("b1", threadId = null, seen = 0, flagged = 0, sortKey = 200, mailbox = "a", accountId = "accB")

        assertEquals(listOf("a1"), runFlat("a", accountId = "accA"))
        assertEquals(listOf("b1"), runFlat("a", accountId = "accB"))
    }

    @Test fun flatUnifiedSelectsOnTheMailboxIdStringAloneAcrossRoles() {
        // The structural cost of the unpinned bind, stated plainly: the unified inbox matches the
        // id STRING, not "this account's Inbox". A row filed under ANOTHER account's folder that
        // carries the bound id is listed as if it were inbox mail.
        //
        // This is a characterisation test — it asserts what the shipped query does today, and it
        // passes on the pre-probe tree. What it also shows is the bound on the defect: it needs a
        // CROSS-ROLE collision, and Stalwart cannot produce one, because it creates the Inbox
        // first in every account, so "a" is the Inbox everywhere and a Trash is never "a". The
        // leak is real in the query and unreachable on this reporter's server.
        insert("inbox1", threadId = null, seen = 0, flagged = 0, sortKey = 100, mailbox = "a", accountId = "accA")
        insert("trash1", threadId = null, seen = 0, flagged = 0, sortKey = 200, mailbox = "a", accountId = "accB")

        assertEquals(listOf("trash1", "inbox1"), runFlat("a"))
    }

    @Test fun flatUnifiedKeepsBothRowsWhenTheEmailIdsCollideToo() {
        // Same-server accounts can be handed the same EMAIL id as well (issue #31, composite
        // (accountId, id) key). The flat list must show one row per account, not one row.
        insert("e1", threadId = null, seen = 0, flagged = 0, sortKey = 100, mailbox = "a", accountId = "accA")
        insert("e1", threadId = null, seen = 0, flagged = 0, sortKey = 200, mailbox = "a", accountId = "accB")

        assertEquals(listOf("e1", "e1"), runFlat("a"))
        assertEquals(listOf("e1"), runFlat("a", accountId = "accA"))
    }

    @Test fun flatSnoozeStaysScopedToItsAccountAcrossACollidingId() {
        // The snooze correlate is account-qualified in the flat filter too: account A snoozing
        // "e1" must not hide account B's same-id inbox message from the unified list.
        insert("e1", threadId = null, seen = 0, flagged = 0, sortKey = 100, mailbox = "a", accountId = "accA")
        insert("e1", threadId = null, seen = 0, flagged = 0, sortKey = 200, mailbox = "a", accountId = "accB")
        snooze("e1", untilMillis = Long.MAX_VALUE, accountId = "accA")

        assertEquals(listOf("e1"), runFlat("a"))                     // only accB's survives…
        assertEquals(listOf("e1"), runFlat("a", accountId = "accB")) // …and it is accB's
        assertEquals(emptyList<String>(), runFlat("a", accountId = "accA"))
    }

    // -- flat mode: sort orders and the favourite pin (issue #111) -------------------------
    //
    // The flat query had NO favourite-pin coverage at all before this — every case above
    // inserts flagged = 0, so the prefix could have been removed or kept and nothing here
    // would have noticed. The flat list is what most readers actually see (conversation view
    // is off by default), so it gets the same fixture as the collapsed one.

    @Test fun flatOrdersDoNotPinFavourites() {
        insertSortFixture()

        assertEquals(listOf("s2", "p", "s"), runFlat("inbox", sort = SortOrder.DATE_DESC))
        assertEquals(listOf("s", "p", "s2"), runFlat("inbox", sort = SortOrder.DATE_ASC))
        assertEquals(listOf("p", "s2", "s"), runFlat("inbox", sort = SortOrder.SUBJECT))
        assertEquals(listOf("p", "s2", "s"), runFlat("inbox", sort = SortOrder.SENDER))
        assertEquals(listOf("p", "s2", "s"), runFlat("inbox", sort = SortOrder.UNREAD_FIRST))
    }

    @Test fun flatFavouritesFirstPinsWhenChosen() {
        insertSortFixture()

        assertEquals(listOf("s2", "s", "p"), runFlat("inbox", sort = SortOrder.FLAGGED_FIRST))
    }
}
