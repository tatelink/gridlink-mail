package app.sterna.core.data.mail

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Verifies, against in-memory SQLite, the OTHER half of "a deleted message must not come back in
 * search results": [app.sterna.core.data.db.EmailDao.deleteById] / `deleteByIds` take the search
 * index row away with the cached message.
 *
 * [LocalSearchFolderFilterSqlTest] pins the query-side filter, which drops a hit whose index row is
 * LABELLED Trash/Junk/Spam. That filter cannot close the reported defect on its own: nothing in the
 * app ever rewrites `email_fts.mailboxId`, so a message indexed while it sat in the Inbox still
 * carries the Inbox after being thrown away — the filter looks up the Inbox's role, finds it
 * perfectly searchable, and hands the message back with subject and preview. The two defences are
 * disjoint: the filter covers rows that are still there but mislabelled, this covers messages that
 * are gone. Both tests must stay.
 *
 * The statements executed here are read out of the shipped DAO by [DaoQuerySource], never retyped —
 * including WHICH statements the delete path is made of, taken from the composed body itself, so
 * unhooking the un-indexing in the DAO turns these red instead of leaving them checking a path the
 * app no longer runs.
 *
 * Two cases guard the LINE the un-indexing must not cross, and they matter as much as the deletion
 * itself: the retention window evicts messages that are still in their folder and must leave their
 * index rows alone, and an index that cannot be written must not stop a message from being deleted.
 * A third guards the exception on that line — deleting what already sits in the Trash un-indexes,
 * one message at a time exactly as in bulk, because the Trash is out of search at both ends.
 */
class DeletedMailLeavesTheIndexSqlTest {
    private lateinit var db: Connection

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
                CREATE TABLE emails(
                    id TEXT, accountId TEXT, mailboxId TEXT, threadId TEXT, subject TEXT,
                    preview TEXT, receivedAt TEXT, fromName TEXT, fromEmail TEXT, seen INTEGER,
                    flagged INTEGER, hasAttachment INTEGER, sortKey INTEGER, recipientsJson TEXT,
                    PRIMARY KEY(accountId, id)
                )
                """.trimIndent(),
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

    // ---- the state the app would be in -------------------------------------------------------

    private fun folder(id: String, role: String?, accountId: String = "acc") {
        db.prepareStatement("INSERT INTO mailboxes VALUES(?, ?, 'Folder', ?, NULL, 0, 0, 0)").use {
            it.setString(1, accountId); it.setString(2, id); it.setString(3, role); it.executeUpdate()
        }
    }

    /** A message that is both cached for the list and present in the search index. */
    private fun message(emailId: String, mailboxId: String, accountId: String = "acc") {
        cache(emailId, mailboxId, accountId)
        index(emailId, mailboxId, accountId)
    }

    private fun cache(emailId: String, mailboxId: String, accountId: String = "acc") {
        db.prepareStatement(
            "INSERT INTO emails VALUES(?, ?, ?, NULL, 'quarterly token report', 'preview', " +
                "'', 'Alex Rivera', 'alex@example.org', 0, 0, 0, 100, NULL)",
        ).use {
            it.setString(1, emailId); it.setString(2, accountId); it.setString(3, mailboxId)
            it.executeUpdate()
        }
    }

    private fun index(emailId: String, mailboxId: String, accountId: String = "acc") {
        db.prepareStatement(
            "INSERT INTO email_fts(emailId, accountId, mailboxId, threadId, subject, sender, " +
                "body, preview, receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, " +
                "sortKey) VALUES(?, ?, ?, NULL, 'quarterly token report', " +
                "'Alex Rivera alex@example.org', '', 'preview', '', 'Alex Rivera', " +
                "'alex@example.org', 0, 0, 0, 100)",
        ).use {
            it.setString(1, emailId); it.setString(2, accountId); it.setString(3, mailboxId)
            it.executeUpdate()
        }
    }

    // ---- the shipped statements ---------------------------------------------------------------

    /**
     * Replay one of `EmailDao`'s composed delete functions: the statements ITS OWN BODY calls, in
     * the order it calls them, with the two properties of the shipped body that decide what a
     * failure does — whether it is wrapped in `@Transaction`, and whether the call is guarded by a
     * `runCatching`.
     *
     * A JVM SQL test cannot invoke the Kotlin body, so the alternative would be to assume which
     * halves it composes — and an assumption would keep passing after someone deletes the
     * un-indexing call, or puts the two statements back under one all-or-nothing transaction.
     * Reading the body means the replay stops issuing the un-index the moment the DAO stops issuing
     * it, and rolls the cache delete back exactly when the DAO would.
     */
    private fun deletePath(function: String, values: Map<String, Any>) {
        val statements = DaoQuerySource.emailDaoStatements(function)
        check(statements.isNotEmpty()) { "EmailDao.$function composes no statement" }
        val atomic = DaoQuerySource.isTransactional("EmailDao", function)
        if (!atomic) {
            statements.forEach { replay(it, values) }
            return
        }
        db.autoCommit = false
        try {
            statements.forEach { replay(it, values) }
            db.commit()
        } catch (e: Exception) {
            db.rollback() // what @Transaction does with an exception it did not catch
            throw e
        } finally {
            db.autoCommit = true
        }
    }

    /** One replayed statement, failing the way the shipped body lets it fail. */
    private fun replay(statement: DaoQuerySource.DaoStatement, values: Map<String, Any>) {
        if (statement.guarded) runCatching { exec(statement.sql, values) } else exec(statement.sql, values)
    }

    /** Run a shipped `@Query` statement, binding [values] by Room's parameter names. */
    private fun exec(sql: String, values: Map<String, Any>) {
        val (statement, order) = DaoQuerySource.bindOrder(sql, listSizes(values))
        db.prepareStatement(statement).use { ps -> bind(ps, order, values); ps.executeUpdate() }
    }

    private fun listSizes(values: Map<String, Any>): Map<String, Int> =
        values.mapNotNull { (name, v) -> (v as? List<*>)?.let { name to it.size } }.toMap()

    private fun bind(ps: java.sql.PreparedStatement, order: List<String>, values: Map<String, Any>) {
        val consumed = mutableMapOf<String, Int>()
        order.forEachIndexed { i, name ->
            when (val v = values[name] ?: error("no value bound for ':$name'")) {
                is List<*> -> {
                    val n = consumed.getOrDefault(name, 0)
                    ps.setString(i + 1, v[n] as String)
                    consumed[name] = n + 1
                }
                is Int -> ps.setInt(i + 1, v)
                else -> ps.setString(i + 1, v.toString())
            }
        }
    }

    /** The ids the shipped `EmailFtsDao.search` returns — the local half of a user's search. */
    private fun search(match: String = "token*", limit: Int = 50): List<String> {
        val roles = NOT_SEARCHED_ROLES.toList()
        val sql = DaoQuerySource.daoQuery("EmailFtsDao", "search")
        val (statement, order) = DaoQuerySource.bindOrder(sql, mapOf("excludedRoles" to roles.size))
        var role = 0
        return db.prepareStatement(statement).use { ps ->
            order.forEachIndexed { i, name ->
                when (name) {
                    "match" -> ps.setString(i + 1, match)
                    "excludedRoles" -> ps.setString(i + 1, roles[role++])
                    "limit" -> ps.setInt(i + 1, limit)
                    else -> error("Unexpected parameter ':$name' in EmailFtsDao.search")
                }
            }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("emailId")) } }
        }
    }

    /** What the INDEX itself holds, whatever the search query chooses to show. */
    private fun indexed(): List<String> =
        db.prepareStatement("SELECT emailId FROM email_fts ORDER BY emailId").use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("emailId")) } }
        }

    /** What the display CACHE itself holds. */
    private fun cached(): List<String> =
        db.prepareStatement("SELECT id FROM emails ORDER BY id").use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }

    // ---- the cases ----------------------------------------------------------------------------

    @Test fun aRowLabelledTrashIsHiddenByTheQueryAndStaysInTheIndex() {
        // The witness of the query-side filter, restated here so removing it is visibly a
        // regression of a DIFFERENT defence: this row was written already carrying the Trash
        // mailbox (unsynced folder roles, a late role, MIGRATION_14_15), nothing deleted it.
        folder("mb-trash", role = "trash")
        message("labelled", "mb-trash")

        assertEquals(emptyList<String>(), search())
        assertEquals(listOf("labelled"), indexed())
    }

    @Test fun aMessageDeletedFromTheInboxLosesItsIndexRowToo() {
        // The reported case, exactly: indexed while it sat in the Inbox, then thrown away. Its
        // index row still says "inbox", so the query-side filter is blind to it — what keeps it
        // out of the results is that the delete path took the index row with it.
        folder("mb-inbox", role = "inbox")
        message("thrown", "mb-inbox")
        assertEquals(listOf("thrown"), search())

        deletePath("deleteById", mapOf("accountId" to "acc", "id" to "thrown"))

        assertEquals(emptyList<String>(), cached())
        assertEquals(emptyList<String>(), indexed())
        assertEquals(emptyList<String>(), search())
    }

    @Test fun aBulkDeleteTakesEveryIndexRowWithIt() {
        folder("mb-inbox", role = "inbox")
        message("one", "mb-inbox")
        message("two", "mb-inbox")
        message("kept", "mb-inbox")

        deletePath("deleteByIds", mapOf("accountId" to "acc", "ids" to listOf("one", "two")))

        assertEquals(listOf("kept"), cached())
        assertEquals(listOf("kept"), indexed())
        assertEquals(listOf("kept"), search())
    }

    @Test fun deletingInOneAccountLeavesTheSiblingAccountsIndexAlone() {
        // Two accounts of one login (issue #31) share mailbox AND message ids: the delete must be
        // account-scoped on the index for the same reason it is on the cache.
        folder("mb-7", role = "inbox", accountId = "accA")
        folder("mb-7", role = "inbox", accountId = "accB")
        message("e-9", "mb-7", accountId = "accA")
        message("e-9", "mb-7", accountId = "accB")

        deletePath("deleteById", mapOf("accountId" to "accA", "id" to "e-9"))

        assertEquals(listOf("e-9"), cached())
        assertEquals(listOf("e-9"), indexed())
        assertEquals(listOf("e-9"), search())
        assertEquals(
            listOf("accB"),
            db.prepareStatement("SELECT accountId FROM email_fts").use { ps ->
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            },
        )
    }

    @Test fun anActionThatMovedNothingKeepsTheIndexRowOfWhatItDidNotMove() {
        // Archiving a message that is already in the Archive, moving one to the folder it is in:
        // nothing leaves anything, the message stays in a SEARCHABLE folder, and the row only goes
        // because the action was empty. Sending those through the delete path un-indexed messages
        // sitting untouched on the server — and it is the SWIPE paths, the most used of all. Deep in
        // a folder, past the sync window, that was permanent: the IMAP index crawl does not exist
        // and the re-seed only rewrites rows whose message is still cached.
        //
        // Deleting a message that is already in the Trash is NOT one of these — see
        // [aTrashDeleteTakesTheIndexRowOneByOneAsInBulk].
        folder("mb-archive", role = "archive")
        message("already", "mb-archive")
        message("other", "mb-archive")

        deletePath(noOpEviction(), mapOf("accountId" to "acc", "ids" to listOf("already")))

        assertEquals(listOf("other"), cached())
        assertEquals(listOf("already", "other"), indexed())
        assertEquals(listOf("already", "other"), search())
    }

    @Test fun everyNoOpBranchGoesThroughTheEvictionThatSparesTheIndex() {
        // The wiring the case above cannot see: which repository paths hand their empty actions to
        // it. Each of these has a branch where the destination IS the source; a new one that reaches
        // for deleteById instead has to fail here. The delete paths are deliberately absent, and
        // the case below is what holds them out.
        val missing = listOf("archive", "moveToMailbox", "archiveAll", "moveAllToMailbox")
            .filterNot { "$NO_OP_EVICTION(" in DaoQuerySource.mailFunctionBody("MailRepository", it) }
        assertEquals(
            "these MailRepository paths no longer call $NO_OP_EVICTION(): an action that moves " +
                "nothing must evict the cached row WITHOUT un-indexing it",
            emptyList<String>(), missing,
        )
    }

    @Test fun aTrashDeleteTakesTheIndexRowOneByOneAsInBulk() {
        // The exception to the rule above, and the two paths must state it the same way: deleting a
        // message that is ALREADY in the Trash moves nothing on the server either, but the Trash is
        // excluded from search at both ends — the query's folder filter and the re-seed — so an
        // index row surviving there carries the folder the message sat in BEFORE it was thrown
        // away. That is exactly the stale row that hands a deleted message back with its subject and
        // preview, so taking it is right, not merely harmless. The bulk path (deleteAll) always said
        // so; the single one (delete) was toggled to the index-sparing eviction and the two then
        // contradicted each other, one swipe apart.
        val paths = listOf("delete", "deleteAll")
        val sparing = paths.filter { "$NO_OP_EVICTION(" in DaoQuerySource.mailFunctionBody("MailRepository", it) }
        assertEquals(
            "the delete paths must NOT evict through $NO_OP_EVICTION: it spares the search-index " +
                "row, and a row left in the index for a message in the Trash carries its previous " +
                "folder — the stale hit that gives a deleted message back",
            emptyList<String>(), sparing,
        )
        val removals = paths.associateWith { cacheRemovalsIn(it) }
        assertEquals(
            "these delete paths no longer remove any cached row through EmailDao — has the delete " +
                "stopped emptying the local list, or does it go through a wrapper this rule does " +
                "not know about ($REMOVAL_WRAPPERS)?",
            emptyList<String>(), removals.filterValues { it.isEmpty() }.keys.toList(),
        )
        assertEquals(
            "these delete paths empty the cache with an EmailDao function that leaves the search " +
                "index alone — a message deleted from the Trash would keep an index row labelled " +
                "with the folder it was in before, and come back in the results",
            emptyList<String>(),
            removals.flatMap { (path, functions) ->
                functions.filterNot(::unindexes).map { "MailRepository.$path → EmailDao.$it" }
            },
        )

        // And the effect itself, replayed on the statements each path actually issues.
        folder("mb-trash", role = "trash")
        message("one-by-one", "mb-trash")
        message("in-bulk", "mb-trash")
        removals.forEach { (path, functions) ->
            val id = if (path == "delete") "one-by-one" else "in-bulk"
            functions.forEach { function ->
                deletePath(function, mapOf("accountId" to "acc", "id" to id, "ids" to listOf(id)))
            }
        }
        assertEquals(emptyList<String>(), cached())
        assertEquals(emptyList<String>(), indexed())
    }

    /**
     * The `EmailDao` functions `MailRepository.[path]` empties the display cache through: its own
     * `emailDao.x(` calls plus those of the removal wrappers it delegates to ([REMOVAL_WRAPPERS]),
     * keeping the ones whose statements DELETE from `emails`.
     */
    private fun cacheRemovalsIn(path: String): Set<String> {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", path)
        val direct = Regex("""emailDao\.(\w+)\(""").findAll(body).map { it.groupValues[1] }.toList()
        val delegated = REMOVAL_WRAPPERS.filter { "$it(" in body }.map { daoRemovalIn(it) }
        return (direct + delegated).filter(::deletesCache).toSet()
    }

    private fun deletesCache(function: String): Boolean =
        DaoQuerySource.emailDaoStatements(function).any { it.sql.contains("DELETE FROM emails", ignoreCase = true) }

    private fun unindexes(function: String): Boolean =
        DaoQuerySource.emailDaoStatements(function).any { it.sql.contains("DELETE FROM email_fts", ignoreCase = true) }

    /**
     * The `EmailDao` function the `MailRepository` wrapper [repositoryFunction] evicts with, read
     * out of that wrapper's own body — so a case follows whatever the shipped path calls instead of
     * asserting against a hand-picked function it may no longer use. The eviction is the DAO call
     * whose statements DELETE (the others read the rows to decide on).
     */
    private fun daoRemovalIn(repositoryFunction: String): String =
        Regex("""emailDao\.(\w+)\(""")
            .findAll(DaoQuerySource.mailFunctionBody("MailRepository", repositoryFunction))
            .map { it.groupValues[1] }
            .distinct()
            .firstOrNull { name ->
                DaoQuerySource.emailDaoStatements(name).any { it.sql.trimStart().startsWith("DELETE", ignoreCase = true) }
            }
            ?: error("MailRepository.$repositoryFunction no longer evicts anything through EmailDao")

    /**
     * The `EmailDao` function `MailRepository.evictAlreadyThere` — the shared cleanup of an action
     * that moved nothing — evicts with, read out of its own body like [retentionEviction].
     */
    private fun noOpEviction(): String = daoRemovalIn(NO_OP_EVICTION)

    @Test fun theDeletePathIsOneTransaction() {
        // Restored after being dropped on the theory that only a transaction could let a broken
        // index block a delete. It cannot: SQLite auto-rolls-back on SQLITE_FULL / IOERR / BUSY /
        // NOMEM / INTERRUPT, and SQLITE_CORRUPT — the damaged-FTS case of issue #71 — is not among
        // them, so the swallowed failure below commits either way. Without the transaction, the
        // errors that ARE auto-rolled-back (a transient SQLITE_BUSY: the push service writing while
        // the screen deletes) undid the un-index alone and left an orphan index row that no re-seed
        // ever clears — the deleted message came back in search for good.
        listOf("deleteById", "deleteByIds").forEach { function ->
            assertEquals(
                "EmailDao.$function must stay @Transaction: the runCatching inside it already keeps " +
                    "a sick index from blocking the delete, and dropping the transaction is what lets " +
                    "a rolled-back un-index leave an index row whose message is gone",
                true, DaoQuerySource.isTransactional("EmailDao", function),
            )
        }
    }

    @Test fun anIndexTooBrokenToWriteStillLetsTheMailBeDeleted() {
        // Issue #71's ground: a search table that cannot be written (damaged, or locked). Deleting
        // is a folder action, not a search feature — it must land whatever the index says. It could
        // not while both statements were one @Transaction: the un-index threw, the cache delete was
        // rolled back with it, and the message came back into a list the server had already emptied
        // it from (these paths are network-first, the move is done before this runs).
        folder("mb-inbox", role = "inbox")
        message("thrown", "mb-inbox")
        message("kept", "mb-inbox")
        db.createStatement().use { it.executeUpdate("DROP TABLE email_fts") }
        // Stated before the delete, so "the cache row is gone" below is the delete's doing and not
        // a row that was never written: without it the case passes on an empty database.
        assertEquals(listOf("kept", "thrown"), cached())

        val outcome = runCatching { deletePath("deleteById", mapOf("accountId" to "acc", "id" to "thrown")) }

        assertEquals("the cache delete did not stand", listOf("kept"), cached())
        assertEquals("the delete path let an index failure escape", null, outcome.exceptionOrNull()?.message)
    }

    @Test fun aRetentionEvictionKeepsTheIndexRowsOfTheMailItDropped() {
        // The sync window is not a removal: these messages are still in their folder on the server,
        // they merely fell outside what the account keeps offline (`retentionEvictions`). Routing
        // that through the delete path un-indexed them, so offline search stopped covering anything
        // older than the window on every refresh — and on IMAP for good, nothing re-indexing a row
        // the cache no longer holds. The function is read out of `pruneRetention` itself.
        folder("mb-inbox", role = "inbox")
        message("old", "mb-inbox")
        message("recent", "mb-inbox")

        deletePath(retentionEviction(), mapOf("accountId" to "acc", "ids" to listOf("old")))

        assertEquals(listOf("recent"), cached())
        assertEquals(listOf("old", "recent"), indexed())
        assertEquals(listOf("old", "recent"), search())
    }

    /**
     * The `EmailDao` function `MailRepository.pruneRetention` evicts with — read out of that
     * function's own body, so the case above follows whatever the retention path calls instead of
     * asserting against a hand-picked function it may no longer use. The eviction is the DAO call
     * whose statements DELETE (the other one reads the rows to decide on).
     */
    private fun retentionEviction(): String =
        Regex("""emailDao\.(\w+)\(""")
            .findAll(DaoQuerySource.mailFunctionBody("MailRepository", "pruneRetention"))
            .map { it.groupValues[1] }
            .distinct()
            .firstOrNull { name ->
                DaoQuerySource.emailDaoStatements(name).any { it.sql.trimStart().startsWith("DELETE", ignoreCase = true) }
            }
            ?: error("MailRepository.pruneRetention no longer evicts anything through EmailDao")

    @Test fun pruningTheCachedPageDoesNotUnindexAnything() {
        // deleteNotIn is window eviction: the message is still in its folder, it just fell out of
        // the page the list caches. Wiring it to the index would empty search as the user scrolls.
        folder("mb-inbox", role = "inbox")
        message("page1", "mb-inbox")
        message("page2", "mb-inbox")

        exec(
            DaoQuerySource.emailDaoQuery("deleteNotIn"),
            mapOf("accountId" to "acc", "mailboxId" to "mb-inbox", "keepIds" to listOf("page1")),
        )

        assertEquals(listOf("page1"), cached())
        assertEquals(listOf("page1", "page2"), indexed())
        assertEquals(listOf("page1", "page2"), search())
    }

    private companion object {
        /** The `MailRepository` function every "the destination is where it already is" branch
         *  hands its ids to — named once, so the two rules above cannot drift apart. The delete
         *  paths are the exception, and [REMOVAL_WRAPPERS] is how the rule sees what they use. */
        const val NO_OP_EVICTION = "evictAlreadyThere"

        /** `MailRepository`'s own removal wrappers: a path's cache removal is either a direct
         *  `emailDao.` call or one of these, and each resolves to the DAO function it evicts with. */
        val REMOVAL_WRAPPERS = listOf("deleteFromCacheAndIndex", NO_OP_EVICTION)
    }
}
