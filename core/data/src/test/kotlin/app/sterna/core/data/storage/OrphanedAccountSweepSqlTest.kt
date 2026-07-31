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
 * The sweep has two halves and each has its own way of going wrong:
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
 *    precisely so it cannot degrade into `DELETE … WHERE accountId NOT IN (…)`.
 *
 * What this cannot see is that the shipped repository still composes those two halves the same way,
 * so the last test reads that composition out of the source, as the source lints of this bench do.
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

    // ---- the shipped statements ------------------------------------------------------------------

    private val inventory = mapOf(
        "emails" to DaoQuerySource.emailDaoQuery("countsByAccount"),
        "mailboxes" to DaoQuerySource.daoQuery("MailboxDao", "accountIds"),
        "email_fts" to DaoQuerySource.daoQuery("EmailFtsDao", "accountIds"),
        "email_bodies" to DaoQuerySource.daoQuery("EmailBodyDao", "accountIds"),
    )

    private val deletes = listOf(
        DaoQuerySource.emailDaoQuery("deleteForAccount"),
        DaoQuerySource.daoQuery("MailboxDao", "deleteForAccount"),
        DaoQuerySource.daoQuery("EmailFtsDao", "clearAccount"),
        DaoQuerySource.daoQuery("EmailBodyDao", "deleteForAccount"),
    )

    /** The account ids the four inventory statements report, as the repository unions them. */
    private fun cachedAccountIds(): List<String> = inventory.values.flatMap { sql ->
        db.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                generateSequence { if (rs.next()) rs.getString("accountId") else null }.toList()
            }
        }
    }

    /** The whole sweep: inventory → decision → the per-account deletes. Returns the ids swept. */
    private fun sweep(knownAccountIds: Collection<String>): List<String> {
        val orphans = OrphanedAccountCache.orphans(knownAccountIds, cachedAccountIds())
        orphans.forEach { accountId ->
            deletes.forEach { sql ->
                val (statement, order) = DaoQuerySource.bindOrder(sql)
                check(order == listOf("accountId")) { "unexpected parameters $order in: $sql" }
                db.prepareStatement(statement).use { it.setString(1, accountId); it.executeUpdate() }
            }
        }
        return orphans
    }

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

    // ---- the wiring, read out of the shipped source ----------------------------------------------

    @Test fun `the repository takes its inventory from all four tables`() {
        val body = purgeOrphanedAccountsSource()
        val missing = listOf(
            "emailDao.countsByAccount()",
            "mailboxDao.accountIds()",
            "emailFtsDao.accountIds()",
            "emailBodyDao.accountIds()",
        ).filterNot { it in body }
        assertEquals(
            "purgeOrphanedAccounts must inventory every table it deletes from. Dropping one back " +
                "out leaves a residue that lives only there unreachable forever (#121). Body was:\n$body",
            emptyList<String>(), missing,
        )
        assertTrue(
            "and the decision must stay OrphanedAccountCache's — a `NOT IN (:known)` in SQL would " +
                "delete the whole cache the day the account list fails to decode. Body was:\n$body",
            "OrphanedAccountCache.orphans(knownAccountIds," in body,
        )
    }

    /** The text of `purgeOrphanedAccounts`, from its declaration to the end of its expression body. */
    private fun purgeOrphanedAccountsSource(): String {
        val source = locate("core/data/src/main/kotlin/app/sterna/core/data/storage/StorageRepository.kt").readText()
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

    /** [relative] resolved from the module's working directory, walking up to the repo root. */
    private fun locate(relative: String): File {
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
