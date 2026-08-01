package app.sterna.core.data.storage

import app.sterna.core.data.mail.DaoQuerySource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The orphan sweep of [StorageRepository.purgeOrphanedAccounts] (#121), replayed against in-memory
 * SQLite with the statements the DAOs ship — read out by [DaoQuerySource], never retyped.
 *
 * The sweep has three ways of going wrong, and each is a separate half of this file:
 *
 *  - WHAT IT LOOKS AT. Taking the inventory from `emails` alone misses the residue that is by far
 *    the likeliest: `refresh()` persists the folder list before it fetches any mail, so an
 *    interrupted or failed first sync leaves an account with folders and not one message. That
 *    account is gone from the store, nothing syncs it, and an emails-only sweep walks straight past
 *    it — for good. Hence the union of every table the sweep deletes from.
 *  - WHAT IT DELETES. An empty list of known accounts must sweep NOTHING. It is not only the
 *    signed-out user: `AccountStore.accounts()` also returns an empty list when the stored JSON
 *    fails to decode, and treating that as "everything is an orphan" would wipe a healthy install's
 *    entire cache on one bad read. The decision stays in [OrphanedAccountCache] — outside SQL —
 *    precisely so it cannot degrade into `DELETE … WHERE accountId NOT IN (…)`. And every delete it
 *    then runs must be scoped to one account: an unscoped `deleteAll()` in that loop empties the
 *    cache of every account there is, which is the single most expensive mistake this file exists
 *    to catch.
 *  - IN WHAT ORDER. The inventory is read first and the account list second. The other way round,
 *    an account created between the two reads is missing from the list, present in the inventory,
 *    and its brand-new cache is swept.
 *
 * ⛔ SO NOTHING HERE IS RETYPED FROM THE REPOSITORY. The statements replayed below, the order they
 * run in, and which tables they touch are all READ OUT of `purgeOrphanedAccounts` (see
 * [SweepShape]). A test that replays its own idea of the sweep proves things about itself; this one
 * goes red when the shipped function changes shape, which is the only reason it is worth running.
 */
class OrphanedAccountSweepSqlTest {

    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate("CREATE TABLE emails(id TEXT, accountId TEXT, mailboxId TEXT, PRIMARY KEY(accountId, id))")
            st.executeUpdate("CREATE TABLE mailboxes(id TEXT, accountId TEXT, name TEXT, PRIMARY KEY(accountId, id))")
            st.executeUpdate("CREATE TABLE email_fts(emailId TEXT, accountId TEXT, subject TEXT)")
            st.executeUpdate("CREATE TABLE email_bodies(id TEXT, accountId TEXT, html TEXT, PRIMARY KEY(accountId, id))")
        }
    }

    @After fun tearDown() = db.close()

    // ---- the shipped sweep, as this file replays it -----------------------------------------------

    /** The account ids the shipped inventory statements report, unioned as the repository unions them. */
    private fun cachedAccountIds(): List<String> = SweepShape.inventory.flatMap { call ->
        val sql = call.sql()
        assertEquals(
            "an inventory statement must ask for the whole table, not for one account: it is what " +
                "tells the sweep which accounts exist in the cache at all. `${call.text}` binds " +
                "${DaoQuerySource.bindOrder(sql).second}, in:\n$sql",
            emptyList<String>(), DaoQuerySource.bindOrder(sql).second,
        )
        db.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                generateSequence { if (rs.next()) rs.getString("accountId") else null }.toList()
            }
        }
    }

    /** Runs one of the shipped deletes for [accountId], after checking it is scoped to an account. */
    private fun runDelete(call: SweepShape.Call, accountId: String) {
        val sql = call.sql()
        val (statement, order) = DaoQuerySource.bindOrder(sql)
        assertEquals(
            "⛔ every delete in the sweep must be scoped to ONE account. `${call.text}` runs:\n$sql\n" +
                "which binds $order — an unscoped delete here empties the cached mail of EVERY " +
                "account (the sweep runs on every cold start), not just the orphan's. This is the " +
                "one mistake in #121 that costs the user her mail.",
            listOf("accountId"), order,
        )
        assertEquals(
            "`${call.text}` must be passed the orphan id the loop is on, and nothing else",
            "accountId", call.args.trim(),
        )
        db.prepareStatement(statement).use { it.setString(1, accountId); it.executeUpdate() }
    }

    /**
     * The whole sweep — inventory → account list → decision → the per-account deletes — in the
     * order the shipped function does them, with [afterFirstRead] standing in for whatever the rest
     * of the app does while the sweep is between its two reads. Returns the ids swept.
     */
    private fun sweep(knownAccountIds: () -> Collection<String>, afterFirstRead: () -> Unit = {}): List<String> {
        val cached: List<String>
        val known: Collection<String>
        if (SweepShape.inventoryIsReadFirst) {
            cached = cachedAccountIds()
            afterFirstRead()
            known = knownAccountIds()
        } else {
            known = knownAccountIds()
            afterFirstRead()
            cached = cachedAccountIds()
        }
        val orphans = OrphanedAccountCache.orphans(known, cached)
        orphans.forEach { accountId -> SweepShape.deletes.forEach { runDelete(it, accountId) } }
        return orphans
    }

    private fun sweep(knownAccountIds: Collection<String>): List<String> = sweep({ knownAccountIds })

    // ---- fixtures --------------------------------------------------------------------------------

    private fun insertEmail(accountId: String, id: String = "m1") =
        exec("INSERT INTO emails VALUES(?, ?, 'inbox')", id, accountId)

    private fun insertMailbox(accountId: String, id: String = "inbox") =
        exec("INSERT INTO mailboxes VALUES(?, ?, 'Inbox')", id, accountId)

    private fun insertIndexRow(accountId: String, id: String = "m1") =
        exec("INSERT INTO email_fts VALUES(?, ?, 'subject')", id, accountId)

    private fun insertBody(accountId: String, id: String = "m1") =
        exec("INSERT INTO email_bodies VALUES(?, ?, '<p>hi</p>')", id, accountId)

    private fun exec(sql: String, vararg args: String) =
        db.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setString(i + 1, a) }
            ps.executeUpdate()
        }

    private fun rowCount(table: String, accountId: String): Int =
        db.prepareStatement("SELECT COUNT(*) FROM $table WHERE accountId = ?").use { ps ->
            ps.setString(1, accountId)
            ps.executeQuery().use { it.next(); it.getInt(1) }
        }

    // ---- what the sweep must see -----------------------------------------------------------------

    @Test fun `an orphan with only folders is swept`() {
        insertMailbox("gone")
        insertEmail("kept"); insertMailbox("kept")

        val swept = sweep(listOf("kept"))

        assertEquals(
            "an account left with folders and no message is the residue a failed first sync leaves, " +
                "and an inventory taken from `emails` alone never sees it: it reports no cached " +
                "account at all for `gone`, so nothing is ever swept and the rows stay for good",
            listOf("gone"), swept,
        )
        assertEquals("the orphan's folders are still there", 0, rowCount("mailboxes", "gone"))
        assertEquals("the live account lost its folders", 1, rowCount("mailboxes", "kept"))
        assertEquals("the live account lost its mail", 1, rowCount("emails", "kept"))
    }

    @Test fun `an orphan known only to the index or to the body cache is swept too`() {
        insertIndexRow("indexed-only")
        insertBody("body-only")
        insertEmail("kept")

        val swept = sweep(listOf("kept"))

        assertEquals(
            "every table the sweep deletes from must also be a table it looks in, or a residue " +
                "living in only one of them is invisible to it",
            listOf("body-only", "indexed-only"), swept.sorted(),
        )
        assertEquals(0, rowCount("email_fts", "indexed-only"))
        assertEquals(0, rowCount("email_bodies", "body-only"))
    }

    @Test fun `an orphan with mail is swept out of all four tables`() {
        insertEmail("gone"); insertMailbox("gone"); insertIndexRow("gone"); insertBody("gone")
        insertEmail("kept"); insertMailbox("kept"); insertIndexRow("kept"); insertBody("kept")

        assertEquals(listOf("gone"), sweep(listOf("kept")))

        listOf("emails", "mailboxes", "email_fts", "email_bodies").forEach { table ->
            assertEquals("$table still holds the orphan's rows", 0, rowCount(table, "gone"))
            assertEquals("$table lost the live account's rows", 1, rowCount(table, "kept"))
        }
    }

    @Test fun `an account listed by several tables is swept once`() {
        insertEmail("gone"); insertMailbox("gone"); insertIndexRow("gone"); insertBody("gone")
        assertEquals(
            "the union must collapse to one id — the deletes are idempotent, but a repeated id " +
                "would be reported to the caller as several accounts swept",
            listOf("gone"), sweep(listOf("kept")),
        )
    }

    // ---- ⛔ the guard --------------------------------------------------------------------------

    @Test fun `an empty list of known accounts sweeps nothing`() {
        insertEmail("acct"); insertMailbox("acct"); insertIndexRow("acct"); insertBody("acct")

        assertEquals(
            "no known account means the account list could not be read — never that every cached " +
                "row is an orphan. This is the one place in the sweep where a mistake costs the " +
                "user her mail.",
            emptyList<String>(), sweep(emptyList()),
        )
        listOf("emails", "mailboxes", "email_fts", "email_bodies").forEach {
            assertEquals("$it was swept with an empty list of known accounts", 1, rowCount(it, "acct"))
        }
    }

    // ---- ⛔ the deletes, as the repository names them ---------------------------------------------

    /**
     * The replay above runs whatever `purgeOrphanedAccounts` calls, so an unscoped delete would
     * already show up as a live account losing its rows. This states the rule on its own, so the
     * failure names it: nothing in that loop may run a statement that is not per-account.
     */
    @Test fun `every delete the sweep runs is scoped to one account`() {
        assertTrue(
            "no delete found in purgeOrphanedAccounts — the loop that removes the orphans' rows is " +
                "what this whole file guards. Body was:\n${SweepShape.body}",
            SweepShape.deletes.isNotEmpty(),
        )
        // Running them on an empty database is enough: runDelete refuses anything unscoped.
        SweepShape.deletes.forEach { runDelete(it, "some-orphan") }
    }

    @Test fun `the sweep looks in exactly the tables it deletes from`() {
        assertEquals(
            "a table swept but not inventoried leaves an orphan nothing can ever see again; a table " +
                "inventoried but not swept reports the same orphan on every start, for ever. " +
                "Inventory: ${SweepShape.inventory.map { it.text }}, deletes: " +
                "${SweepShape.deletes.map { it.text }}",
            SweepShape.deletes.map { it.dao }.toSortedSet(),
            SweepShape.inventory.map { it.dao }.toSortedSet(),
        )
    }

    // ---- ⛔ the order of the two reads ------------------------------------------------------------

    /**
     * The account created while the sweep runs. The trigger used to hand the sweep an already-read
     * list (`purgeOrphanedAccounts(store.accounts().map { it.id })`), and the inventory that follows
     * is typically the first database access of the process: it pays for opening the file and for
     * any migration, while the first unified refresh is discovering shared mailboxes and creating
     * their sub-accounts (#31). An account minted in that gap was absent from the list and present
     * in the inventory — an orphan by both readings, swept the moment its first page landed.
     */
    @Test fun `an account created between the two reads is not swept`() {
        insertEmail("kept"); insertMailbox("kept")
        // The account store, as it changes under the sweep: the new account is added to it and
        // starts caching at the same moment, which is what "created" means here.
        val store = mutableListOf("kept")

        val swept = sweep(
            knownAccountIds = { store.toList() },
            afterFirstRead = { store.add("fresh"); insertMailbox("fresh"); insertEmail("fresh") },
        )

        assertEquals(
            "an account that appeared while the sweep was between its two reads was swept as an " +
                "orphan. Read the inventory FIRST: rows written after it belong to an account the " +
                "list read afterwards is guaranteed to contain.",
            emptyList<String>(), swept,
        )
        assertEquals("the new account lost the mail it had just cached", 1, rowCount("emails", "fresh"))
        assertEquals("the new account lost its folders", 1, rowCount("mailboxes", "fresh"))
    }

    @Test fun `the repository reads its inventory before it reads the account list`() {
        assertTrue(
            "purgeOrphanedAccounts must take the account list as a function and call it AFTER the " +
                "inventory — a list evaluated by the caller is read before the sweep even starts, " +
                "which is the window above. Body was:\n${SweepShape.body}",
            SweepShape.inventoryIsReadFirst,
        )
    }

    // ---- the wiring, read out of the shipped source ----------------------------------------------

    @Test fun `the repository takes its inventory from all four tables`() {
        val missing = listOf(
            "emailDao.countsByAccount()",
            "mailboxDao.accountIds()",
            "emailFtsDao.accountIds()",
            "emailBodyDao.accountIds()",
        ).filterNot { it in SweepShape.body }
        assertEquals(
            "purgeOrphanedAccounts must inventory every table it deletes from. Dropping one back " +
                "out leaves a residue that lives only there unreachable forever (#121). Body was:\n" +
                SweepShape.body,
            emptyList<String>(), missing,
        )
        assertTrue(
            "and the decision must stay OrphanedAccountCache's — a `NOT IN (:known)` in SQL would " +
                "delete the whole cache the day the account list fails to decode. Body was:\n" +
                SweepShape.body,
            "OrphanedAccountCache.orphans(" in SweepShape.body,
        )
        assertTrue(
            "no `NOT IN` may appear in the sweep: the day the account list is empty, that statement " +
                "deletes everything. Body was:\n${SweepShape.body}",
            !SweepShape.body.contains("NOT IN", ignoreCase = true),
        )
    }

    /**
     * The sweep's two callers, in the app module — the cold start and the sign-out. Neither can be
     * exercised from this module (an Application and an AndroidViewModel), and neither was covered
     * by anything at all, so what is cheap to hold is held: they must hand the sweep a READER of
     * the account store, so the order pinned above is the order they actually get.
     */
    @Test fun `both callers hand the sweep a reader of the account store`() {
        val callers = mapOf(
            "app/src/main/kotlin/app/sterna/SternaApplication.kt" to "accountStore",
            "app/src/main/kotlin/app/sterna/ui/settings/AccountsViewModel.kt" to "store",
        )
        callers.forEach { (path, storeName) ->
            val source = locate(path).readText()
            val at = source.indexOf("purgeOrphanedAccounts")
            val call = if (at < 0) null else {
                source.substring(at, minOf(source.length, at + 120)).replace(Regex("""\s+"""), " ")
            }
            assertTrue(
                "$path no longer calls purgeOrphanedAccounts — it is one of only two places the " +
                    "sweep ever runs from, so deleting the call silently retires the fix (#121). " +
                    "Move this rule rather than dropping it.",
                call != null,
            )
            assertTrue(
                "$path must call purgeOrphanedAccounts { $storeName.accounts()… } — passing an " +
                    "already-read list restores the window where an account created during the " +
                    "sweep loses its cache. Found:\n$call",
                call!!.startsWith("purgeOrphanedAccounts {") && "$storeName.accounts()" in call,
            )
        }
    }

    /**
     * The shape of the shipped sweep, parsed once from `StorageRepository.kt`: which DAO calls take
     * the inventory, which ones delete, and whether the account list is read before or after the
     * inventory. Braces are counted raw — no Kotlin parser here — which reads too far or stops
     * early rather than lying, and every failure above prints the body it read.
     */
    private object SweepShape {

        /** One `someDao.someCall(args)` in the sweep: [text] as written, [dao] as its class. */
        data class Call(val text: String, val dao: String, val function: String, val args: String) {
            fun sql(): String = runCatching {
                if (dao == "EmailDao") DaoQuerySource.emailDaoQuery(function)
                else DaoQuerySource.daoQuery(dao, function)
            }.getOrElse { error("the sweep calls $text, which has no readable @Query: ${it.message}") }
        }

        /** The text of `purgeOrphanedAccounts`, declaration to closing brace. */
        val body: String by lazy {
            val source = locate("core/data/src/main/kotlin/app/sterna/core/data/storage/StorageRepository.kt").readText()
            val start = source.indexOf("suspend fun purgeOrphanedAccounts(")
            check(start >= 0) { "purgeOrphanedAccounts is gone from StorageRepository — rename it here too" }
            val open = source.indexOf('{', start)
            var depth = 0
            for (i in open until source.length) {
                when (source[i]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) return@lazy source.substring(start, i + 1)
                }
            }
            error("Unbalanced braces in purgeOrphanedAccounts")
        }

        /** Where the orphans' rows are removed: from the loop over them to the end of the body. */
        private val deleteLoopAt: Int get() = body.indexOf("orphans.forEach")

        /** Where the decision is taken: everything before it is the sweep making up its mind. */
        private val decisionAt: Int get() = body.indexOf("OrphanedAccountCache.orphans(")

        val inventory: List<Call> by lazy { calls(body.substring(0, decisionAt.coerceAtLeast(0))) }

        val deletes: List<Call> by lazy {
            if (deleteLoopAt < 0) emptyList() else calls(body.substring(deleteLoopAt))
        }

        /**
         * Whether the account list is read after the inventory. A list evaluated by the caller
         * leaves no call to read here at all, which is exactly "read first" — the shape this file
         * refuses.
         */
        val inventoryIsReadFirst: Boolean by lazy {
            val listAt = body.indexOf("knownAccountIds()")
            val lastInventoryAt = inventory.lastOrNull()?.let { body.indexOf(it.text) } ?: -1
            listAt >= 0 && lastInventoryAt >= 0 && listAt > lastInventoryAt && listAt < decisionAt
        }

        private fun calls(segment: String): List<Call> =
            DAO_CALL.findAll(segment)
                .map {
                    Call(
                        text = it.value,
                        dao = it.groupValues[1].replaceFirstChar(Char::uppercase),
                        function = it.groupValues[2],
                        args = it.groupValues[3],
                    )
                }
                .distinct()
                .toList()

        /** `emailFtsDao.clearAccount(accountId)` and friends: the property, the call, its arguments. */
        private val DAO_CALL = Regex("""\b(\w*[dD]ao)\.(\w+)\(([^)]*)\)""")
    }

    private companion object {
        /** [relative] resolved from the module's working directory, walking up to the repo root. */
        fun locate(relative: String): File {
            val fromModule = relative.substringAfter("core/data/")
            val cwd = System.getProperty("user.dir").orEmpty()
            var dir: File? = File(cwd).absoluteFile
            while (dir != null) {
                File(dir, relative).takeIf { it.isFile }?.let { return it }
                File(dir, fromModule).takeIf { it.isFile }?.let { return it }
                dir = dir.parentFile
            }
            error("Cannot find $relative from $cwd")
        }
    }
}
