package app.sterna.core.data.mail

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Verifies, against in-memory SQLite, that the local index half of a search
 * ([app.sterna.core.data.db.EmailFtsDao.search]) hides the folders a search must not surface —
 * Trash/Junk/Spam, the roles of [NOT_SEARCHED_ROLES], which the server half already excludes.
 *
 * The defect this pins: the index is built once and the exclusion used to live only at INDEXING
 * time, so a message indexed while it sat in the Inbox stayed indexed after being thrown away, and
 * the search union put it back in the results — subject, preview and all — while the advanced
 * (server-only) search correctly showed nothing. Every case therefore carries the same witness:
 * **the row is still in `email_fts`**. That is what proves the FILTER did the work; a test that
 * merely deleted the index row would also pass against the old, broken query and would guard
 * nothing.
 *
 * The statement executed here is read out of the shipped DAO by [DaoQuerySource], not retyped, so
 * changing the DAO's SQL changes this test's SQL with it.
 */
class LocalSearchFolderFilterSqlTest {
    private lateinit var db: Connection

    /** The DAO's own statement, with `:match` / `:excludedRoles` / `:limit` turned into `?`. */
    private val searchSql = DaoQuerySource.bindOrder(
        DaoQuerySource.daoQuery("EmailFtsDao", "search"),
        listParams = mapOf("excludedRoles" to NOT_SEARCHED_ROLES.size),
    )

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(
                "CREATE VIRTUAL TABLE email_fts USING fts4(" +
                    "emailId, accountId, mailboxId, threadId, subject, sender, body, preview, " +
                    "receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, sortKey, " +
                    "notindexed=emailId, notindexed=accountId, notindexed=mailboxId, " +
                    "notindexed=threadId, notindexed=preview, notindexed=receivedAt, " +
                    "notindexed=fromName, notindexed=fromEmail, notindexed=seen, " +
                    "notindexed=flagged, notindexed=hasAttachment, notindexed=sortKey, " +
                    "tokenize=unicode61 `remove_diacritics=1`)",
            )
            st.executeUpdate(
                """
                CREATE TABLE mailboxes(
                    accountId TEXT, id TEXT, name TEXT, role TEXT, parentId TEXT,
                    sortOrder INTEGER, totalEmails INTEGER, unreadEmails INTEGER,
                    PRIMARY KEY(accountId, id)
                )
                """.trimIndent(),
            )
        }
    }

    @After fun tearDown() = db.close()

    private fun folder(id: String, role: String?, accountId: String = "acc") {
        db.prepareStatement("INSERT INTO mailboxes VALUES(?, ?, 'Folder', ?, NULL, 0, 0, 0)").use {
            it.setString(1, accountId); it.setString(2, id); it.setString(3, role); it.executeUpdate()
        }
    }

    private fun index(
        emailId: String, mailboxId: String, subject: String = "quarterly token report",
        accountId: String = "acc", sortKey: Long = 100,
    ) {
        db.prepareStatement(
            "INSERT INTO email_fts(emailId, accountId, mailboxId, threadId, subject, sender, " +
                "body, preview, receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, " +
                "sortKey) VALUES(?, ?, ?, NULL, ?, 'Alex Rivera alex@example.org', '', " +
                "'preview', '', 'Alex Rivera', 'alex@example.org', 0, 0, 0, ?)",
        ).use {
            it.setString(1, emailId); it.setString(2, accountId); it.setString(3, mailboxId)
            it.setString(4, subject); it.setLong(5, sortKey)
            it.executeUpdate()
        }
    }

    /** Move an already indexed message: only the mailbox of its INDEX row changes, as a real move
     *  leaves it (nothing on the move path un-indexes it — that is the whole point). */
    private fun moveIndexedTo(emailId: String, mailboxId: String) {
        db.prepareStatement("UPDATE email_fts SET mailboxId = ? WHERE emailId = ?").use {
            it.setString(1, mailboxId); it.setString(2, emailId); it.executeUpdate()
        }
    }

    /** The ids the shipped search query returns for [match]. */
    private fun search(match: String, limit: Int = 50): List<String> {
        val (sql, order) = searchSql
        val roles = NOT_SEARCHED_ROLES.toList()
        var role = 0
        return db.prepareStatement(sql).use { ps ->
            order.forEachIndexed { i, name ->
                when (name) {
                    "match" -> ps.setString(i + 1, match)
                    "excludedRoles" -> ps.setString(i + 1, roles[role++])
                    "limit" -> ps.setInt(i + 1, limit)
                    else -> error("Unexpected parameter ':$name' in EmailFtsDao.search")
                }
            }
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString("emailId")) }
            }
        }
    }

    /** The witness: what the INDEX holds, filter or no filter. */
    private fun indexedIds(match: String): List<String> =
        db.prepareStatement("SELECT emailId FROM email_fts WHERE email_fts MATCH ? ORDER BY emailId").use { ps ->
            ps.setString(1, match)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("emailId")) } }
        }

    @Test fun aMessageInASearchableFolderIsReturned() {
        folder("mb-inbox", role = "inbox")
        index("kept", "mb-inbox")

        assertEquals(listOf("kept"), search("token*"))
    }

    @Test fun theSameMessageThrownAwayDisappearsFromResultsWhileStayingInTheIndex() {
        folder("mb-inbox", role = "inbox")
        folder("mb-trash", role = "trash")
        index("thrown", "mb-inbox")
        assertEquals(listOf("thrown"), search("token*"))

        moveIndexedTo("thrown", "mb-trash")

        assertEquals(emptyList<String>(), search("token*"))
        // The witness: nothing cleaned the index — the QUERY is what hides the message.
        assertEquals(listOf("thrown"), indexedIds("token*"))
    }

    @Test fun theSameMessageMarkedAsJunkDisappearsFromResultsWhileStayingInTheIndex() {
        folder("mb-inbox", role = "inbox")
        folder("mb-junk", role = "junk")
        index("junked", "mb-inbox")
        assertEquals(listOf("junked"), search("token*"))

        moveIndexedTo("junked", "mb-junk")

        assertEquals(emptyList<String>(), search("token*"))
        assertEquals(listOf("junked"), indexedIds("token*"))
    }

    @Test fun aSpamFolderIsHiddenUnderWhateverSpellingTheServerSentItsRole() {
        // Servers spell roles inconsistently (`Trash`, ` Spam `): the same folding the index build
        // applies (LOWER(TRIM(...))) must apply here, or the filter misses the folder it targets.
        folder("mb-inbox", role = "inbox")
        folder("mb-spam", role = " Spam ")
        folder("mb-trash", role = "TRASH")
        index("kept", "mb-inbox")
        index("spam", "mb-spam")
        index("thrown", "mb-trash")

        assertEquals(listOf("kept"), search("token*"))
        assertEquals(listOf("kept", "spam", "thrown"), indexedIds("token*"))
    }

    @Test fun aHitWhoseFolderIsNotCachedYetIsStillReturned() {
        // Index and folder list fill in independently. An unknown folder must not turn "not synced
        // yet" into "no results" while the user is typing: local search stays monotonic.
        index("early", "mb-not-synced-yet")

        assertEquals(listOf("early"), search("token*"))
    }

    @Test fun aSiblingAccountsTrashDoesNotHideThisAccountsMessage() {
        // Two accounts of one login (issue #31) can mint the SAME mailbox id: account A's inbox
        // and account B's trash both being "mb-7" must not make A's message vanish.
        folder("mb-7", role = "inbox", accountId = "accA")
        folder("mb-7", role = "trash", accountId = "accB")
        index("a1", "mb-7", accountId = "accA")
        index("b1", "mb-7", accountId = "accB")

        assertEquals(listOf("a1"), search("token*"))
        assertEquals(listOf("a1", "b1"), indexedIds("token*"))
    }

    @Test fun anEmptyIndexSimplyAnswersNothing() {
        folder("mb-inbox", role = "inbox")

        assertEquals(emptyList<String>(), search("token*"))
    }
}
