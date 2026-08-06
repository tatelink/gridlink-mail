package app.sterna.core.data.mail

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * `EmailDao.reconcileMailbox` RUN, against in-memory SQLite, with the arguments the shipped body
 * passes — not with arguments this test chose.
 *
 * ⛔ Why it has to exist. Since the streaming volet, this function is a DELETE that is callable on
 * its own, from both protocols, with nothing before it to make the deletion safe. What it deletes
 * is decided by ONE call — `reconcileEvictions(cachedIds = …, keepIds = …, spareIds = …)` — and
 * substituting one of those arguments for another compiles (they are all id collections) while
 * turning every full re-query into "empty the folder except the handful of recently mutated rows".
 * A statement-list check does not see it: the statements are the same. A `@Transaction` check does
 * not see it. A test with its own reconcile does not see it, because it never calls this one.
 *
 * ⛔ So the argument EXPRESSIONS are read out of the shipped body ([shippedEvictionArguments]) and
 * evaluated, rather than restated here. `keepIds = keepIds` and `keepIds = spareIds.toHashSet()`
 * are then two different runs producing two different sets of rows in a real table. What this test
 * must never do is decide for itself which set to keep — that is the copy this repository has been
 * caught making three times.
 *
 * The eviction statement is the shipped SQL too, so swapping `evictFromCacheKeepingIndex` for
 * `deleteByIds` shows up as index rows disappearing — an irreversible loss on IMAP, where nothing
 * re-indexes a message the cache no longer holds.
 */
class ReconcileMailboxSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(
                "CREATE TABLE emails(" +
                    "id TEXT, accountId TEXT, mailboxId TEXT, threadId TEXT, subject TEXT, " +
                    "preview TEXT, receivedAt TEXT, fromName TEXT, fromEmail TEXT, seen INTEGER, " +
                    "flagged INTEGER, hasAttachment INTEGER, sortKey INTEGER, recipientsJson TEXT, " +
                    "PRIMARY KEY(accountId, id))",
            )
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
        }
    }

    @After fun tearDown() = db.close()

    // ---- the folder, as the cache holds it ------------------------------------------------------

    private fun cache(id: String, mailboxId: String = "mb-inbox", accountId: String = "acc") {
        db.prepareStatement(
            "INSERT INTO emails VALUES(?, ?, ?, NULL, 'subject', 'preview', '', 'Alex', " +
                "'alex@example.org', 0, 0, 0, 100, NULL)",
        ).use {
            it.setString(1, id); it.setString(2, accountId); it.setString(3, mailboxId); it.executeUpdate()
        }
        db.prepareStatement(
            "INSERT INTO email_fts(emailId, accountId, mailboxId, threadId, subject, sender, body, " +
                "preview, receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, sortKey) " +
                "VALUES(?, ?, ?, NULL, 'subject', 'Alex alex@example.org', '', 'preview', '', " +
                "'Alex', 'alex@example.org', 0, 0, 0, 100)",
        ).use {
            it.setString(1, id); it.setString(2, accountId); it.setString(3, mailboxId); it.executeUpdate()
        }
    }

    private fun cached(mailboxId: String = "mb-inbox", accountId: String = "acc"): List<String> =
        db.prepareStatement("SELECT id FROM emails WHERE accountId = ? AND mailboxId = ? ORDER BY id").use { ps ->
            ps.setString(1, accountId); ps.setString(2, mailboxId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    private fun allCached(): List<String> =
        db.prepareStatement("SELECT id FROM emails ORDER BY id").use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    private fun indexed(): List<String> =
        db.prepareStatement("SELECT emailId FROM email_fts ORDER BY emailId").use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    // ---- the shipped body, read and run ---------------------------------------------------------

    /**
     * The named arguments of the ONE `reconcileEvictions(…)` call in the shipped reconcile, as
     * source text. Positional arguments are refused rather than guessed at: the whole point is that
     * this test does not decide which id set plays which role.
     */
    private fun shippedEvictionArguments(): Map<String, String> {
        val body = DaoQuerySource.daoFunctionBody("EmailDao", RECONCILE_BODY)
        val call = body.indexOf("reconcileEvictions(")
        check(call >= 0) {
            "EmailDao.$RECONCILE_BODY no longer calls reconcileEvictions. That call IS the decision; " +
                "if it moved, this test has to follow it rather than keep passing."
        }
        var depth = 0
        var i = body.indexOf('(', call)
        val open = i
        do {
            when (body[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        } while (depth > 0)
        return body.substring(open + 1, i - 1)
            .split(Regex(",(?![^(]*\\))"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .associate { argument ->
                val eq = argument.indexOf('=')
                check(eq > 0) {
                    "EmailDao.$RECONCILE_BODY passes '$argument' to reconcileEvictions positionally. " +
                        "Name it: this test reads which of its own parameters goes into which role, " +
                        "and a positional argument would have it guess."
                }
                argument.take(eq).trim() to argument.drop(eq + 1).trim()
            }
    }

    /**
     * One shipped argument expression, evaluated. Deliberately tiny and deliberately loud: anything
     * it does not recognise fails the test rather than being silently approximated, because an
     * approximation here is a test that stops watching the decision it exists for.
     */
    private fun idSet(expression: String, keepIds: Set<String>, spareIds: List<String>): Set<String> =
        when (expression) {
            "keepIds" -> keepIds
            "keepIds.toHashSet()", "keepIds.toSet()" -> keepIds.toHashSet()
            "spareIds" -> spareIds.toHashSet()
            "spareIds.toHashSet()", "spareIds.toSet()" -> spareIds.toHashSet()
            "emptySet()" -> emptySet()
            else -> error(
                "EmailDao.$RECONCILE_BODY now hands reconcileEvictions the expression '$expression', " +
                    "which this test cannot evaluate. Teach it, or explain why the reconcile needs " +
                    "an id set that is neither of its two parameters.",
            )
        }

    /** `idsForMailbox`'s shipped SQL, run for real. */
    private fun cachedIdsOf(expression: String, accountId: String, mailboxId: String): List<String> {
        check(expression == "idsForMailbox(accountId, mailboxId)") {
            "EmailDao.$RECONCILE_BODY no longer reads the folder with idsForMailbox(accountId, " +
                "mailboxId) — it reads '$expression'. An unscoped read here would reconcile one " +
                "account's folder against another's (#31/#121)."
        }
        val sql = DaoQuerySource.emailDaoQuery("idsForMailbox")
        val (statement, order) = DaoQuerySource.bindOrder(sql)
        return db.prepareStatement(statement).use { ps ->
            order.forEachIndexed { i, name ->
                ps.setString(i + 1, if (name == "accountId") accountId else mailboxId)
            }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    /** The chunk the shipped call asks for, or [MAX_CHANGES] when it names none. */
    private fun chunkOf(arguments: Map<String, String>): Int {
        val expression = arguments["chunk"] ?: return MAX_CHANGES
        return expression.replace("_", "").toIntOrNull()
            ?: if (expression == "MAX_CHANGES") MAX_CHANGES else error(
                "EmailDao.$RECONCILE_BODY asks reconcileEvictions for a chunk of '$expression', which " +
                    "this test cannot evaluate.",
            )
    }

    /**
     * `EmailDao.reconcileMailbox`, replayed: the shipped read, the shipped decision with the
     * shipped arguments, the shipped eviction statement. Returns the batches, so a caller can
     * assert on the size no statement may exceed.
     */
    private fun reconcileMailboxRows(
        accountId: String,
        mailboxId: String,
        keepIds: Set<String>,
        spareIds: List<String> = emptyList(),
    ): List<List<String>> {
        val arguments = shippedEvictionArguments()
        assertEquals(
            "EmailDao.$RECONCILE_BODY no longer names exactly these three things to reconcileEvictions",
            setOf("cachedIds", "keepIds", "spareIds"),
            arguments.keys - "chunk",
        )
        val batches = reconcileEvictions(
            cachedIds = cachedIdsOf(arguments.getValue("cachedIds"), accountId, mailboxId),
            keepIds = idSet(arguments.getValue("keepIds"), keepIds, spareIds),
            spareIds = idSet(arguments.getValue("spareIds"), keepIds, spareIds),
            chunk = chunkOf(arguments),
        )
        val evict = DaoQuerySource.emailDaoStatements(RECONCILE_BODY)
            .single { it.sql.trimStart().startsWith("DELETE", ignoreCase = true) }
        batches.forEach { batch ->
            val (statement, order) = DaoQuerySource.bindOrder(evict.sql, mapOf("ids" to batch.size))
            var next = 0
            db.prepareStatement(statement).use { ps ->
                order.forEachIndexed { i, name ->
                    ps.setString(i + 1, if (name == "accountId") accountId else batch[next++])
                }
                ps.executeUpdate()
            }
        }
        return batches
    }

    // ---- the cases -------------------------------------------------------------------------------

    @Test fun `it keeps what the walk named and deletes the rest`() {
        // ⭐ The load-bearing case: `keepIds` is the walk's answer and it must reach the decision as
        // the KEEP set. Substituting the spare set for it compiles, keeps the statements identical,
        // and empties the folder — which is exactly what these rows say.
        listOf("w1", "w2", "w3", "stale1", "stale2").forEach { cache(it) }

        reconcileMailboxRows("acc", "mb-inbox", keepIds = setOf("w1", "w2", "w3"))

        assertEquals(listOf("w1", "w2", "w3"), cached())
    }

    @Test fun `it spares a recently mutated row the walk did not name`() {
        // The optimistic Undo the server has not caught up on: outside the page, and it must stay.
        listOf("w1", "undone", "stale").forEach { cache(it) }

        reconcileMailboxRows("acc", "mb-inbox", keepIds = setOf("w1"), spareIds = listOf("undone"))

        assertEquals(listOf("undone", "w1"), cached())
    }

    @Test fun `the same fixture without the spare loses the row — the witness`() {
        listOf("w1", "undone", "stale").forEach { cache(it) }

        reconcileMailboxRows("acc", "mb-inbox", keepIds = setOf("w1"))

        assertEquals(listOf("w1"), cached())
    }

    @Test fun `the eviction leaves the search index alone`() {
        // ⛔ `deleteByIds` here is an IRREVERSIBLE loss on IMAP: these messages are still in their
        // folder on the server, and nothing re-indexes a row the cache no longer holds.
        listOf("w1", "evicted").forEach { cache(it) }

        reconcileMailboxRows("acc", "mb-inbox", keepIds = setOf("w1"))

        assertEquals(listOf("w1"), cached())
        assertEquals(
            "the reconcile un-indexed a message that is still sitting in its folder on the server",
            listOf("evicted", "w1"), indexed(),
        )
    }

    @Test fun `it stays inside its account and its folder`() {
        // #31 / #121: ids collide between sibling accounts on the same server, and a folder id can
        // collide too. An eviction that reached either would delete another account's mail.
        cache("shared", accountId = "acc")
        cache("shared", accountId = "other")
        cache("elsewhere", mailboxId = "mb-archive")

        reconcileMailboxRows("acc", "mb-inbox", keepIds = emptySet())

        assertEquals(listOf("elsewhere", "shared"), allCached())
        assertEquals(listOf("elsewhere"), cached("mb-archive"))
    }

    @Test fun `an empty keep set really does empty the folder — which is why null exists upstream`() {
        // Stated here so nobody has to take it on trust: this is what an empty set does, and it is
        // the reason a walk that learned nothing must answer null instead
        // ([EmptyWindowIsNotAnEmptyFolderTest]).
        listOf("a", "b", "c").forEach { cache(it) }

        reconcileMailboxRows("acc", "mb-inbox", keepIds = emptySet())

        assertEquals(emptyList<String>(), cached())
    }

    @Test fun `no single statement is handed more ids than SQLite will bind`() {
        // ⛔ Codeberg #29. Below Android 12 SQLite binds at most 999 variables; past that the
        // statement throws, and it threw BEFORE the sync cursor was stored, so the folder
        // re-queried whole for ever. The bound is the batch size and nothing else — sqlite-jdbc
        // here binds far more than an Android 11 device would, so the engine cannot be the witness
        // and the batch size has to be asserted directly.
        (1..450).forEach { cache("m$it") }

        val batches = reconcileMailboxRows("acc", "mb-inbox", keepIds = emptySet())

        assertEquals(450, batches.sumOf { it.size })
        assertTrue(
            "the reconcile now issues a statement binding ${batches.maxOf { it.size } + 1} variables; " +
                "on Android 11 SQLite refuses past 999 and the folder stops syncing for ever (#29)",
            batches.all { it.size <= MAX_CHANGES },
        )
        assertEquals(emptyList<String>(), cached())
    }

    private companion object {
        /**
         * The `EmailDao` function whose body carries the reconcile decision. Named once, here, so
         * that splitting or renaming it fails this test loudly instead of leaving it reading a
         * function the app no longer runs.
         */
        const val RECONCILE_BODY = "reconcileMailboxRows"
    }
}
