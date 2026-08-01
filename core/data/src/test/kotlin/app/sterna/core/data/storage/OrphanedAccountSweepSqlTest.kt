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
 *    then runs must be scoped to one account: an unscoped `deleteAll()` **anywhere after the
 *    decision** empties the cache of every account there is, which is the single most expensive
 *    mistake this file exists to catch.
 *  - IN WHAT ORDER. The inventory is read first and the account list second. The other way round,
 *    an account created between the two reads is missing from the list, present in the inventory,
 *    and its brand-new cache is swept.
 *
 * ⛔ SO NOTHING HERE IS RETYPED FROM THE REPOSITORY. The statements replayed below, the order they
 * run in, which tables they touch and **which way round the decision is called** are all READ OUT
 * of `purgeOrphanedAccounts` (see [SweepShape]). A test that replays its own idea of the sweep
 * proves things about itself; this one goes red when the shipped function changes shape, which is
 * the only reason it is worth running.
 *
 * ⚠ TWO THINGS THIS READS AND EARLIER VERSIONS DID NOT, both because a mutation slipped through:
 *
 *  - **The region judged as "the deletes" starts at the DECISION, not at the loop.** It used to
 *    start at `orphans.forEach`, so `if (orphans.isNotEmpty()) emailDao.deleteAll()` on the line
 *    between the two was read by no rule at all and left every test green — while wiping every
 *    account's cached mail on every cold start. Every DAO call standing after the decision is now
 *    judged as a delete: per-account or red.
 *  - **The decision's arguments, in order.** Only the function name was pinned, and the replay
 *    retyped `orphans(known, cached)` by hand, so `orphans(cached, known)` (same types, compiles in
 *    silence, sweep goes inert and #121 comes back) and `orphans(listOf(known.first()), cached)`
 *    (every account but one loses its cache) were both invisible. The argument list is now read
 *    from the source and the replay passes them in the order it read.
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
        assertTrue(
            "an inventory statement must READ. `${call.text}` runs:\n$sql\nwhich is not a SELECT — a " +
                "statement standing before the decision is taken for part of the inventory, so a " +
                "delete written there is judged by none of the rules below. Put it after the " +
                "decision, where every delete is checked for being per-account.",
            sql.trimStart().startsWith("SELECT", ignoreCase = true),
        )
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
            "⛔ every delete the sweep runs after its decision must be scoped to ONE account. " +
                "`${call.text}` runs:\n$sql\nwhich binds $order — an unscoped delete here empties " +
                "the cached mail of EVERY account (the sweep runs on every cold start), not just " +
                "the orphan's. This is the one mistake in #121 that costs the user her mail. It " +
                "counts wherever it stands after the decision, inside the loop over the orphans or " +
                "on the way to it.",
            listOf("accountId"), order,
        )
        assertEquals(
            "`${call.text}` must be passed the orphan the loop is on — the name that loop binds is " +
                "`${SweepShape.loopVariable}` — and nothing else. A delete handed anything wider " +
                "than the id being swept reaches accounts that are not orphans.",
            SweepShape.loopVariable, call.args.trim(),
        )
        db.prepareStatement(statement).use { it.setString(1, accountId); it.executeUpdate() }
    }

    /**
     * The decision, with its two arguments in the order the SHIPPED call passes them — the one
     * thing this replay cannot execute for itself, so it reads it instead. Swapping them in
     * `StorageRepository` swaps them here, and the sweep goes inert in every test below rather than
     * staying green on a hand-typed copy of a call the app no longer makes.
     */
    private fun decide(known: Collection<String>, cached: Collection<String>): List<String> {
        val values = mapOf(SweepShape.knownValue to known, SweepShape.cachedValue to cached)
        val args = SweepShape.decisionArgs
        assertEquals(
            "OrphanedAccountCache.orphans takes the known accounts and the cached ones, in that " +
                "order. The sweep passes ${args.size} argument(s): $args",
            2, args.size,
        )
        assertEquals(
            "the sweep hands the decision something this replay cannot follow. It knows two values: " +
                "the account list (`${SweepShape.knownValue}`) and the inventory " +
                "(`${SweepShape.cachedValue}`). Anything else — `listOf(${SweepShape.knownValue}." +
                "first())`, a filtered copy, a fresh read — is a narrowing of what counts as a known " +
                "account, and every account it drops loses its whole cache on the next cold start. " +
                "Pass the two values as read, or teach this replay the new shape on purpose.",
            emptyList<String>(), args.filterNot { it in values },
        )
        return OrphanedAccountCache.orphans(values.getValue(args[0]), values.getValue(args[1]))
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
        val orphans = decide(known, cached)
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

    /**
     * The whole point of reading the known accounts as a LIST rather than a filter: every account
     * the store lists keeps its cache, not just the first one. A decision handed a narrowed list
     * (`listOf(known.first())`, a `take(1)`, a filtered copy) still sweeps "orphans" — they are
     * simply every other account the user has, and they lose their mail on the next cold start.
     */
    @Test fun `nothing is swept while every cached account is a known one`() {
        val known = listOf("a", "b", "c")
        known.forEach { insertEmail(it); insertMailbox(it); insertIndexRow(it); insertBody(it) }

        assertEquals(
            "an account the store lists is never an orphan, however many of them there are",
            emptyList<String>(), sweep(known),
        )
        known.forEach { account ->
            listOf("emails", "mailboxes", "email_fts", "email_bodies").forEach { table ->
                assertEquals("$table lost $account's rows", 1, rowCount(table, account))
            }
        }
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
     * The replay above runs whatever `purgeOrphanedAccounts` calls after its decision, so an
     * unscoped delete would already show up as a live account losing its rows. This states the rule
     * on its own, so the failure names it: nothing standing after the decision may run a statement
     * that is not per-account — not in the loop, and not on the line before it.
     */
    @Test fun `every delete the sweep runs is scoped to one account`() {
        assertTrue(
            "no delete found after the decision in purgeOrphanedAccounts — the loop that removes " +
                "the orphans' rows is what this whole file guards. Body was:\n${SweepShape.body}",
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

    // ---- ⛔ the decision, as the repository calls it ----------------------------------------------

    /**
     * ⛔ THE CALL ITSELF, ARGUMENTS INCLUDED. Pinning only the function name left two mutations
     * green, and both are cache-wide:
     *
     *  - `orphans(cached, known)` — same types, compiles in silence. Every cached account is now
     *    "known", so nothing is ever swept: the sweep is inert and #121 is back, with a full suite
     *    of green tests over it.
     *  - `orphans(listOf(known.firstOrNull().orEmpty()), cached)` — one known account survives and
     *    every other account of the user is swept as an orphan, on every cold start.
     *
     * Neither is a delete, which is why the rules about deletes read straight past them. What holds
     * them is that the two names the sweep read into are the two things it hands over, in that
     * order — and [decide] then replays the call the way this reads it, so the behaviour tests turn
     * red on the same mutation rather than agreeing with a copy typed here.
     */
    @Test fun `the decision is handed the account list first and the inventory second`() {
        assertEquals(
            "⛔ the sweep must hand OrphanedAccountCache.orphans the account list it read " +
                "(`${SweepShape.knownValue}`) and then the inventory it took " +
                "(`${SweepShape.cachedValue}`), unaltered and in that order. The two arguments have " +
                "the same type, so the compiler accepts them either way round: swapped, the sweep " +
                "stops sweeping anything (#121 returns); narrowed, it sweeps the accounts it " +
                "narrowed away. Body was:\n${SweepShape.body}",
            listOf(SweepShape.knownValue, SweepShape.cachedValue), SweepShape.decisionArgs,
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
        ).filterNot { it in SweepShape.code }
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
            SweepShape.DECISION in SweepShape.code,
        )
        assertTrue(
            "no `NOT IN` may appear in the sweep: the day the account list is empty, that statement " +
                "deletes everything. (Comments are stripped before this looks, so prose may say " +
                "\"not in\" freely — only code counts.) Code was:\n${SweepShape.code}",
            !SweepShape.code.contains("NOT IN", ignoreCase = true),
        )
    }

    /**
     * The sweep's two callers, in the app module — the cold start and the sign-out. Neither can be
     * exercised from this module (an Application and an AndroidViewModel), and neither was covered
     * by anything at all, so what is cheap to hold is held: they must hand the sweep a READER of
     * the account store, so the order pinned above is the order they actually get.
     *
     * Comments are stripped before the file is read, and EVERY call site is judged rather than the
     * first mention found: a KDoc naming `purgeOrphanedAccounts` above the function used to be
     * taken for the call and failed the rule on its own.
     */
    @Test fun `both callers hand the sweep a reader of the account store`() {
        val callers = mapOf(
            "app/src/main/kotlin/app/sterna/SternaApplication.kt" to "accountStore",
            "app/src/main/kotlin/app/sterna/ui/settings/AccountsViewModel.kt" to "store",
        )
        callers.forEach { (path, storeName) ->
            val source = withoutComments(locate(path).readText())
            val callSites = Regex("""\bpurgeOrphanedAccounts\s*[({]""").findAll(source)
                .map { source.substring(it.range.first, minOf(source.length, it.range.first + 120)) }
                .map { it.replace(Regex("""\s+"""), " ") }
                .toList()
            assertTrue(
                "$path no longer calls purgeOrphanedAccounts — it is one of only two places the " +
                    "sweep ever runs from, so deleting the call silently retires the fix (#121). " +
                    "Move this rule rather than dropping it.",
                callSites.isNotEmpty(),
            )
            val wrong = callSites.filterNot {
                it.startsWith("purgeOrphanedAccounts {") && "$storeName.accounts()" in it
            }
            assertEquals(
                "$path must call purgeOrphanedAccounts { $storeName.accounts()… } — passing an " +
                    "already-read list restores the window where an account created during the " +
                    "sweep loses its cache.",
                emptyList<String>(), wrong,
            )
        }
    }

    /**
     * The shape of the shipped sweep, parsed once from `StorageRepository.kt`: which DAO calls take
     * the inventory, which ones delete, which way round the decision is called, and whether the
     * account list is read before or after the inventory. Braces are counted raw — no Kotlin parser
     * here — which reads too far or stops early rather than lying, and every failure above prints
     * the body it read.
     *
     * ⚠ THE SHAPE THIS EXPECTS, and it is a real constraint on the shipped function: the account
     * list and the inventory each go into a local `val` before the decision is taken, and the
     * decision's result into a third. Reading a source file as text is the only instrument
     * available here, and it needs names to follow; inlining any of the three (`orphans(
     * knownAccountIds(), …)`) fails these rules loudly rather than passing unread. The sweep is
     * eight lines long, so that is a cheap thing to ask of it — but it IS asked, deliberately, and
     * a future shape that wants the inlining will have to teach this to follow it.
     */
    private object SweepShape {

        const val DECISION = "OrphanedAccountCache.orphans("

        /** One `someDao.someCall(args)` in the sweep: [text] as written, [dao] as its class. */
        data class Call(val text: String, val dao: String, val function: String, val args: String) {
            fun sql(): String = runCatching {
                if (dao == "EmailDao") DaoQuerySource.emailDaoQuery(function)
                else DaoQuerySource.daoQuery(dao, function)
            }.getOrElse { error("the sweep calls $text, which has no readable @Query: ${it.message}") }
        }

        /** The text of `purgeOrphanedAccounts`, declaration to closing brace — for failure messages. */
        val body: String by lazy { functionText(SOURCE) }

        /**
         * The same, with the comments taken out — what every rule here actually reads.
         *
         * Prose is not code, and reading it as code made two rules fail on files that were
         * perfectly correct: a comment containing the words "not in" tripped the `NOT IN` ban (the
         * comparison ignores case), and a commented-out DAO call would have been parsed as a
         * statement the sweep runs.
         */
        val code: String by lazy { functionText(withoutComments(SOURCE)) }

        private val SOURCE: String by lazy {
            locate("core/data/src/main/kotlin/app/sterna/core/data/storage/StorageRepository.kt").readText()
        }

        /** `purgeOrphanedAccounts`, declaration to closing brace, in [source]. */
        private fun functionText(source: String): String {
            val start = source.indexOf("suspend fun purgeOrphanedAccounts(")
            check(start >= 0) { "purgeOrphanedAccounts is gone from StorageRepository — rename it here too" }
            val open = source.indexOf('{', start)
            var depth = 0
            for (i in open until source.length) {
                when (source[i]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) return source.substring(start, i + 1)
                }
            }
            error("Unbalanced braces in purgeOrphanedAccounts")
        }

        /**
         * Where the sweep makes up its mind. Everything before it is the inventory; everything
         * after it deletes — ⛔ INCLUDING THE GAP between the decision and the loop, which used to
         * belong to no region at all and where an `emailDao.deleteAll()` sat unread by every rule.
         */
        val decisionAt: Int by lazy {
            code.indexOf(DECISION).also {
                check(it >= 0) {
                    "purgeOrphanedAccounts no longer calls $DECISION. That decision is the whole " +
                        "reason the sweep is not a `DELETE … WHERE accountId NOT IN (…)`: it is " +
                        "what refuses to treat an unreadable account list as \"everything is an " +
                        "orphan\". Body was:\n$body"
                }
            }
        }

        val inventory: List<Call> by lazy { calls(code.substring(0, decisionAt)) }

        /** Every DAO call standing after the decision — see [decisionAt]. */
        val deletes: List<Call> by lazy { calls(code.substring(decisionAt)) }

        /** The name of the parameter the account list is read through. */
        val listParameter: String by lazy {
            Regex("""fun\s+purgeOrphanedAccounts\s*\(\s*(\w+)\s*:""").find(code)?.groupValues?.get(1)
                ?: error("cannot read the parameter of purgeOrphanedAccounts. Body was:\n$body")
        }

        /** `val <name> = <listParameter>()`: where the account list lands. */
        val knownValue: String by lazy {
            bindings.firstOrNull { it.initializer.trim() == "$listParameter()" }?.name
                ?: error(
                    "the sweep must read the account list into a local val (`val known = " +
                        "$listParameter()`) before the decision — this file reads the source as " +
                        "text and follows that name into the call. Body was:\n$body",
                )
        }

        /** `val <name> = emailDao.countsByAccount() + …`: where the inventory lands. */
        val cachedValue: String by lazy {
            val first = inventory.firstOrNull()
                ?: error("no DAO call before the decision — the sweep takes no inventory. Body was:\n$body")
            bindings.firstOrNull { first.text in it.initializer }?.name
                ?: error(
                    "the sweep must read its inventory into a local val before the decision — this " +
                        "file follows that name into the call. Body was:\n$body",
                )
        }

        /** `val <name> = OrphanedAccountCache.orphans(…)`: what the loop iterates. */
        val orphansValue: String by lazy {
            Regex("""\bval\s+(\w+)\s*=\s*${Regex.escape(DECISION)}""").find(code)?.groupValues?.get(1)
                ?: error(
                    "the decision's result must go into a local val — the rules below follow that " +
                        "name to the loop that deletes. Body was:\n$body",
                )
        }

        /**
         * The two things the decision is handed, as written. Read with balanced parentheses, so a
         * `listOf(known.first())` is reported whole instead of being cut at its first `)`.
         */
        val decisionArgs: List<String> by lazy {
            argumentsAt(code, decisionAt + DECISION.length - 1)
        }

        /**
         * The name the loop binds each orphan to — `forEach { id ->`, a bare `forEach { … it … }`,
         * or `for (id in orphans)` alike. Read rather than assumed: renaming the loop variable, or
         * writing the loop the other way, is not a defect and must not turn this file red.
         */
        val loopVariable: String by lazy {
            val after = code.substring(decisionAt)
            val name = Regex("""\b$orphansValue\s*\.\s*forEach\s*\{\s*(\w+)\s*->""").find(after)
                ?: Regex("""\bfor\s*\(\s*(\w+)\s+in\s+$orphansValue\b""").find(after)
            when {
                name != null -> name.groupValues[1]
                Regex("""\b$orphansValue\s*\.\s*forEach\s*\{""").containsMatchIn(after) -> "it"
                else -> error(
                    "the deletes must run inside a loop over `$orphansValue`, one account at a " +
                        "time: that loop is what scopes them to the orphans. Body was:\n$body",
                )
            }
        }

        /**
         * Whether the account list is read after the inventory. A list evaluated by the caller
         * leaves no call to read here at all, which is exactly "read first" — the shape this file
         * refuses.
         */
        val inventoryIsReadFirst: Boolean by lazy {
            val listAt = code.indexOf("$listParameter()")
            val lastInventoryAt = inventory.lastOrNull()?.let { code.indexOf(it.text) } ?: -1
            listAt >= 0 && lastInventoryAt >= 0 && listAt > lastInventoryAt && listAt < decisionAt
        }

        /** The local `val`s declared before the decision, each with the text it is assigned. */
        private data class Binding(val name: String, val initializer: String)

        private val bindings: List<Binding> by lazy {
            val region = code.substring(0, decisionAt)
            val declarations = Regex("""\bval\s+(\w+)\s*=""").findAll(region).toList()
            declarations.mapIndexed { i, match ->
                val end = declarations.getOrNull(i + 1)?.range?.first ?: region.length
                Binding(match.groupValues[1], region.substring(match.range.last + 1, end))
            }
        }

        /** The top-level arguments of the call whose `(` sits at [open]. */
        private fun argumentsAt(text: String, open: Int): List<String> {
            val args = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            for (i in open until text.length) {
                val c = text[i]
                when {
                    c == '(' -> { depth++; if (depth > 1) current.append(c) }
                    c == ')' -> {
                        if (--depth == 0) {
                            if (current.isNotBlank()) args += current.toString().trim()
                            return args
                        }
                        current.append(c)
                    }
                    c == ',' && depth == 1 -> { args += current.toString().trim(); current.clear() }
                    else -> current.append(c)
                }
            }
            error("Unbalanced parentheses in the call to $DECISION. Body was:\n$body")
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

        /**
         * [source] with its Kotlin comments removed, string literals left alone.
         *
         * A source lint that reads prose as code fails on files that are correct — a comment saying
         * "not in" is not a `NOT IN`, and a commented-out call is not a call. Strings are stepped
         * over so a `"http://…"` cannot be mistaken for the start of a comment; character literals
         * are not, which would only matter for a `'"'` and none of the files read here has one.
         */
        fun withoutComments(source: String): String {
            val out = StringBuilder(source.length)
            var i = 0
            while (i < source.length) {
                when {
                    source.startsWith("//", i) -> while (i < source.length && source[i] != '\n') i++
                    source.startsWith("/*", i) -> {
                        val end = source.indexOf("*/", i + 2)
                        i = if (end < 0) source.length else end + 2
                        out.append(' ')
                    }
                    source.startsWith("\"\"\"", i) -> {
                        val end = source.indexOf("\"\"\"", i + 3)
                        val stop = if (end < 0) source.length else end + 3
                        out.append(source, i, stop)
                        i = stop
                    }
                    source[i] == '"' -> {
                        out.append(source[i]); i++
                        while (i < source.length && source[i] != '"') {
                            if (source[i] == '\\' && i + 1 < source.length) { out.append(source[i]); i++ }
                            out.append(source[i]); i++
                        }
                        if (i < source.length) { out.append(source[i]); i++ }
                    }
                    else -> { out.append(source[i]); i++ }
                }
            }
            return out.toString()
        }
    }
}
