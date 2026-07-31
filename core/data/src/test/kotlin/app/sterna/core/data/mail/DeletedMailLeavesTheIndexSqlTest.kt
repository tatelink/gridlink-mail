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
 * Other cases guard the LINE the un-indexing must not cross, and they matter as much as the deletion
 * itself: the retention window evicts messages that are still in their folder and must leave their
 * index rows alone, and an index that cannot be written must not stop a message from being deleted.
 *
 * The line is drawn on the FOLDER, not on the action. An action that moved nothing spares the index
 * row in a folder a search looks at and takes it in Trash/Junk/Spam, where the index covers nothing
 * at either end and a spared row is an orphan — the same rule the delete paths follow when they
 * un-index what already sat in the Trash. And a statement refused on its own must leave the two rows
 * in step, which is what says the pair runs in one transaction with its recovery outside rather than
 * with a swallowed failure inside.
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
    private fun deletePath(function: String, values: Map<String, Any>, busy: Busy? = null) {
        val path = DaoQuerySource.emailDaoPath(function)
        check(path.main.isNotEmpty()) { "EmailDao.$function composes no statement" }
        val outcome = runCatching { replayPart(path.main, path.atomic, values, busy) }
        if (outcome.isSuccess) return
        // The `catch` the shipped body carries, if it carries one; without it the failure escapes
        // to the caller exactly as it would in the app.
        if (path.fallback.isEmpty()) throw outcome.exceptionOrNull()!!
        replayPart(path.fallback, atomic = false, values, busy)
    }

    private fun replayPart(
        statements: List<DaoQuerySource.DaoStatement>,
        atomic: Boolean,
        values: Map<String, Any>,
        busy: Busy?,
    ) {
        if (!atomic) {
            statements.forEach { replay(it, values, busy) }
            return
        }
        db.autoCommit = false
        try {
            statements.forEach { replay(it, values, busy) }
            db.commit()
        } catch (e: Exception) {
            db.rollback() // what @Transaction does with an exception it did not catch
            throw e
        } finally {
            db.autoCommit = true
        }
    }

    /** One replayed statement, failing the way the shipped body lets it fail. */
    private fun replay(statement: DaoQuerySource.DaoStatement, values: Map<String, Any>, busy: Busy?) {
        val run = {
            if (busy?.refuses(statement.sql) == true) throw java.sql.SQLException("database is locked (SQLITE_BUSY)")
            exec(statement.sql, values)
        }
        if (statement.guarded) runCatching { run() } else run()
    }

    /**
     * A lock SQLite refuses ONE statement over, leaving the transaction around it standing —
     * `SQLITE_BUSY`, the push service writing while the screen deletes. SQLite documents that for
     * `SQLITE_FULL`, `SQLITE_IOERR`, `SQLITE_BUSY` and `SQLITE_NOMEM` it MAY abort only the statement
     * in progress and leave the transaction running (`sqlite3_get_autocommit()` is what tells the two
     * apart), which is the case that matters here: the code decides what happens next, and a
     * swallowed error means it commits the half that landed. Modelled by refusing the statement
     * without executing it, as the engine does — and the lock clears on its own, hence [times].
     */
    private class Busy(private val onSql: String, private var times: Int) {
        var fired = 0
            private set

        fun refuses(sql: String): Boolean {
            if (onSql !in sql || times == 0) return false
            times--
            fired++
            return true
        }
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

        deletePath(noOpEviction("archive"), mapOf("accountId" to "acc", "ids" to listOf("already")))

        assertEquals(listOf("other"), cached())
        assertEquals(listOf("already", "other"), indexed())
        assertEquals(listOf("already", "other"), search())
    }

    @Test fun aNoOpInAFolderSearchNeverLooksAtTakesTheIndexRowWithIt() {
        // The other side of the line the case above draws, and the reason it cannot be drawn on
        // "did anything move?" alone: the same two no-op branches are ALSO how a message is filed
        // into Junk (report-spam onto a message already there) and how the folder picker files one
        // into the Trash it is already in. There the index row is not true-but-idle, it is dead
        // weight: search covers neither folder, at either end, so nothing is being preserved — and
        // the row can never be cleaned up either, the re-seed only rewriting rows whose message is
        // still cached. One such gesture, one permanent orphan.
        folder("mb-junk", role = "junk")
        message("reported", "mb-junk")
        message("other", "mb-junk")

        deletePath(noOpEviction("junk"), mapOf("accountId" to "acc", "ids" to listOf("reported")))

        assertEquals(listOf("other"), cached())
        assertEquals("an index row was left for a message in a folder search never looks at", listOf("other"), indexed())
    }

    @Test fun everyNoOpBranchGoesThroughTheEvictionThatWeighsTheFolder() {
        // The wiring the two cases above cannot see: which repository paths hand their empty actions
        // to the one eviction that asks what folder the message stayed in. Each of these has a branch
        // where the destination IS the source; a new one that reaches for deleteById instead has to
        // fail here. The delete paths are deliberately absent, and the case below is what holds them
        // out.
        val missing = listOf("archive", "moveToMailbox", "archiveAll", "moveAllToMailbox")
            .filterNot { "$NO_OP_EVICTION(" in DaoQuerySource.mailFunctionBody("MailRepository", it) }
        assertEquals(
            "these MailRepository paths no longer call $NO_OP_EVICTION(): an action that moves " +
                "nothing must evict the cached row WITHOUT un-indexing it",
            emptyList<String>(), missing,
        )
    }

    @Test fun aTrashDeleteTakesTheIndexRowOneByOneAsInBulk() {
        // The two delete paths must state it the same way: deleting a message that is ALREADY in the
        // Trash moves nothing on the server either, but the Trash is excluded from search at both
        // ends — the query's folder filter, the crawl and the re-seed — so an index row surviving
        // there preserves NO coverage; it is only an orphan, and the re-seed rewrites rows whose
        // message is still cached, so nothing ever clears it. Not, as this comment used to say,
        // because the row carries the folder the message sat in BEFORE: an IMAP id encodes its
        // folder, so an in-Trash id's index row says Trash. That is what keeps it invisible, and
        // only while the folder cache still holds that folder's role. The bulk path (deleteAll)
        // always un-indexed; the single one (delete) was toggled to the index-sparing eviction and
        // the two then contradicted each other, one swipe apart.
        val paths = listOf("delete", "deleteAll")
        val sparing = paths.filter { "$NO_OP_EVICTION(" in DaoQuerySource.mailFunctionBody("MailRepository", it) }
        assertEquals(
            "the delete paths must NOT evict through $NO_OP_EVICTION: they know their destination " +
                "is the Trash, which search covers at neither end, so the index row they would " +
                "spare is an orphan no re-seed can clear",
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
                "index alone — a message deleted from the Trash would keep an index row the folder " +
                "cache alone hides, and a renamed or forgotten folder would give it back",
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
     * that moved nothing — evicts a folder with this [role] with, read out of its own body like
     * [retentionEviction].
     *
     * The wrapper is chosen by the SHIPPED rule ([noOpKeepsIndexRow]), so these cases follow whatever
     * the app decides rather than restating it; what they insist on is that both branches exist. A
     * no-op eviction with nowhere to send a Trash/Junk/Spam folder has only one behaviour, and it is
     * the wrong one for half its call sites.
     */
    private fun noOpEviction(role: String?): String {
        val keepsIndexRow = noOpKeepsIndexRow("mb-any", role)
        val body = DaoQuerySource.mailFunctionBody("MailRepository", NO_OP_EVICTION)
        check(keepsIndexRow || "$INDEXING_REMOVAL(" in body) {
            "MailRepository.$NO_OP_EVICTION has no branch that un-indexes: an action that moves " +
                "nothing in a folder search never looks at (role '$role') leaves an index row for a " +
                "message the index covers at neither end — an orphan no re-seed can clear, since " +
                "the re-seed only rewrites rows whose message is still cached"
        }
        return daoRemovalIn(if (keepsIndexRow) NO_OP_EVICTION else INDEXING_REMOVAL)
    }

    @Test fun theNoOpEvictionAsksWhetherTheFolderIsSearchedAtAll() {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", NO_OP_EVICTION)
        assertEquals(
            "MailRepository.$NO_OP_EVICTION must decide through the shipped rule (noOpKeepsIndexRow): " +
                "whether the index row may stay is not 'did anything move?' but 'is this folder " +
                "searched at all?'",
            true, "noOpKeepsIndexRow(" in body,
        )
        assertEquals(
            "MailRepository.$NO_OP_EVICTION restates the roles a search skips instead of asking the " +
                "single source (NOT_SEARCHED_ROLES, through excludedSearchFolderIds): a second copy " +
                "is what lets the index and the search disagree about what a folder is",
            emptyList<String>(), NOT_SEARCHED_ROLES.filter { "\"$it\"" in body },
        )
    }

    @Test fun theDeletePathIsOneTransaction() {
        // Restored after being dropped on the theory that only a transaction could let a broken
        // index block a delete. It cannot — the fallback below is what keeps the delete landing.
        // Without the transaction, a statement refused on its own (a transient SQLITE_BUSY: the push
        // service writing while the screen deletes) took the un-index away and left the cache delete
        // standing: an orphan index row that no re-seed ever clears, so the deleted message came back
        // in search for good.
        listOf("deleteById", "deleteByIds").forEach { function ->
            assertEquals(
                "the statements EmailDao.$function issues must run as ONE transaction — its own " +
                    "@Transaction or that of the pair it delegates to — so a refused un-index cannot " +
                    "leave the cache delete committed on its own",
                true, DaoQuerySource.emailDaoPath(function).atomic,
            )
        }
    }

    @Test fun theTransactionDoesNotSwallowTheIndexFailureInsideItself() {
        // The form this replaced: both statements in the transaction, the un-index wrapped in a
        // runCatching INSIDE it. It reads as "the delete lands whatever the index does", and it does
        // — but for the failure that motivated the transaction it is the wrong shape. SQLite aborts
        // a BUSY statement and CONTINUES the transaction, so the swallowed error let the block return
        // normally, the transaction committed, and the cache row left without its index row: the very
        // orphan the transaction is there to prevent. A `runCatching` also swallows the
        // CancellationException of a screen closed mid-delete, with the same result.
        listOf("deleteById", "deleteByIds").forEach { function ->
            assertEquals(
                "EmailDao.$function guards a statement INSIDE its transaction: a failure swallowed " +
                    "there commits the half that succeeded. The recovery belongs outside — let the " +
                    "transaction fail whole, then replay the cache delete on its own",
                emptyList<String>(),
                DaoQuerySource.emailDaoPath(function).main.filter { it.guarded }.map { it.function },
            )
        }
    }

    @Test fun aRefusedIndexStatementTakesNeitherHalfWithIt() {
        // The case the shape rules above are about, played out. The index delete is refused once —
        // a lock, not a corruption — and the two halves must end up in step: either both rows go or
        // neither does, and the message must not be left cached-out but still searchable.
        folder("mb-inbox", role = "inbox")
        message("thrown", "mb-inbox")
        message("kept", "mb-inbox")
        val busy = Busy(onSql = "DELETE FROM email_fts", times = 1)

        deletePath("deleteById", mapOf("accountId" to "acc", "id" to "thrown"), busy)

        assertEquals("the index refusal was never exercised", 1, busy.fired)
        assertEquals(listOf("kept"), cached())
        assertEquals(
            "a refused un-index left an index row whose cached message is gone — the orphan no " +
                "re-seed clears, and the deleted message comes back in search for good",
            listOf("kept"), indexed(),
        )
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

        /** The wrapper that takes the index row with the cached one — where the no-op eviction
         *  sends a folder no search looks at. */
        const val INDEXING_REMOVAL = "deleteFromCacheAndIndex"

        /** `MailRepository`'s own removal wrappers: a path's cache removal is either a direct
         *  `emailDao.` call or one of these, and each resolves to the DAO function it evicts with. */
        val REMOVAL_WRAPPERS = listOf(INDEXING_REMOVAL, NO_OP_EVICTION)
    }
}
