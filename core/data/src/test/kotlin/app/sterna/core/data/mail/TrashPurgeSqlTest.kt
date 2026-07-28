package app.sterna.core.data.mail

import app.sterna.core.data.db.PURGE_SNAPSHOT_CREATE_SQL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The storage side of the Empty-trash snapshot (Codeberg #99), against real in-memory SQLite:
 * the table `PurgeSnapshotDao` reads and writes, built from the very constant the 17→18
 * migration executes, and the same statements the DAO declares.
 *
 * What it pins down: a purge only ever sees the ids of its own confirmation and its own
 * account; draining it in waves terminates; an undo erases the destroy list; an abandoned
 * snapshot is swept instead of lingering.
 */
class TrashPurgeSqlTest {
    private lateinit var db: Connection

    // Mirrors PurgeSnapshotDao, statement for statement.
    private val waveSql =
        "SELECT emailId FROM purge_snapshot WHERE purgeId = ? AND accountId = ? ORDER BY rowid LIMIT ?"
    private val countSql = "SELECT COUNT(*) FROM purge_snapshot WHERE purgeId = ? AND accountId = ?"
    private val deleteSnapshotSql = "DELETE FROM purge_snapshot WHERE purgeId = ?"
    private val deleteForMailboxSql = "DELETE FROM purge_snapshot WHERE accountId = ? AND mailboxId = ?"
    private val deleteForAccountSql = "DELETE FROM purge_snapshot WHERE accountId = ?"
    private val sweepSql = "DELETE FROM purge_snapshot WHERE createdAt < ?"

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { it.executeUpdate(PURGE_SNAPSHOT_CREATE_SQL) }
    }

    @After fun tearDown() = db.close()

    // --- helpers ---------------------------------------------------------------------------

    private fun snapshot(
        purgeId: String,
        ids: List<String>,
        accountId: String = "accA",
        mailboxId: String = "trash",
        createdAt: Long = 1_000L,
    ) {
        val rows = TrashPurge.snapshotRows(purgeId, accountId, mailboxId, ids, createdAt)
        db.prepareStatement("INSERT OR REPLACE INTO purge_snapshot VALUES(?, ?, ?, ?, ?)").use { ps ->
            rows.forEach { row ->
                ps.setString(1, row.purgeId); ps.setString(2, row.accountId)
                ps.setString(3, row.mailboxId); ps.setString(4, row.emailId)
                ps.setLong(5, row.createdAt)
                ps.executeUpdate()
            }
        }
    }

    private fun wave(purgeId: String, accountId: String = "accA", limit: Int = 500): List<String> =
        db.prepareStatement(waveSql).use { ps ->
            ps.setString(1, purgeId); ps.setString(2, accountId); ps.setInt(3, limit)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }

    private fun deleteIds(purgeId: String, accountId: String, ids: List<String>) {
        val holes = ids.joinToString(", ") { "?" }
        db.prepareStatement(
            "DELETE FROM purge_snapshot WHERE purgeId = ? AND accountId = ? AND emailId IN ($holes)",
        ).use { ps ->
            ps.setString(1, purgeId); ps.setString(2, accountId)
            ids.forEachIndexed { i, id -> ps.setString(3 + i, id) }
            ps.executeUpdate()
        }
    }

    private fun count(purgeId: String, accountId: String = "accA"): Int =
        db.prepareStatement(countSql).use { ps ->
            ps.setString(1, purgeId); ps.setString(2, accountId)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }

    private fun total(): Int = db.createStatement().use { st ->
        st.executeQuery("SELECT COUNT(*) FROM purge_snapshot").use { rs -> rs.next(); rs.getInt(1) }
    }

    /** The purge loop: read a wave, destroy it, delete it, repeat — as [MailRepository.purgeSnapshot] does. */
    private fun drain(purgeId: String, accountId: String = "accA", waveSize: Int = 2): List<String> {
        val destroyed = mutableListOf<String>()
        repeat(TrashPurge.MAX_WAVES) {
            val ids = wave(purgeId, accountId, waveSize)
            if (ids.isEmpty()) return destroyed
            destroyed += ids
            deleteIds(purgeId, accountId, ids)
        }
        return destroyed
    }

    // --- the defect ------------------------------------------------------------------------

    @Test fun aPurgeSeesOnlyItsOwnConfirmation() {
        snapshot("p1", listOf("m1", "m2"))
        // The user files m3 in Trash during the undo window and later empties again: a second,
        // separate confirmation. The pending purge must not inherit it.
        snapshot("p2", listOf("m3"))

        assertEquals(listOf("m1", "m2"), wave("p1"))
        assertEquals(listOf("m3"), wave("p2"))
    }

    @Test fun anIdIsOnlyEverResolvedInsideItsOwnAccount() {
        // Same server, two accounts: mailbox and email ids collide (issue #31).
        snapshot("p1", listOf("m1", "m2"), accountId = "accA")
        snapshot("p1", listOf("m1", "m9"), accountId = "accB")

        assertEquals(listOf("m1", "m2"), wave("p1", "accA"))
        assertEquals(listOf("m1", "m9"), wave("p1", "accB"))
    }

    @Test fun emptyingOneAccountsTrashLeavesTheOthersAlone() {
        snapshot("p1", listOf("m1"), accountId = "accA", mailboxId = "1")
        snapshot("p2", listOf("m1"), accountId = "accB", mailboxId = "1") // same folder id, other account

        drain("p1", "accA")

        assertEquals(0, count("p1", "accA"))
        assertEquals(1, count("p2", "accB"))
    }

    // --- draining --------------------------------------------------------------------------

    @Test fun drainingConsumesEveryIdExactlyOnceAndTerminates() {
        val ids = (1..7).map { "m$it" }
        snapshot("p1", ids)

        val destroyed = drain("p1", waveSize = 2)

        assertEquals(ids, destroyed)
        assertEquals(0, count("p1"))
    }

    @Test fun idsRejectedByTheServerStillLeaveTheSnapshotSoTheLoopEnds() {
        // deleteIds removes the whole wave, accepted or refused: a permanently rejected id
        // cannot spin the loop forever (the old code re-queried and relied on a progress check).
        snapshot("p1", listOf("m1", "m2", "m3"))

        val first = wave("p1", limit = 3)
        deleteIds("p1", "accA", first) // pretend the server refused all three

        assertEquals(emptyList<String>(), wave("p1"))
    }

    @Test fun aResumedPurgePicksUpOnlyWhatIsLeft() {
        snapshot("p1", listOf("m1", "m2", "m3", "m4"))

        // First attempt destroys one wave, then the transport fails and the worker retries.
        val firstWave = wave("p1", limit = 2)
        deleteIds("p1", "accA", firstWave)

        assertEquals(listOf("m1", "m2"), firstWave)
        assertEquals(listOf("m3", "m4"), wave("p1"))
    }

    // --- undo and cleanup -------------------------------------------------------------------

    @Test fun undoErasesTheDestroyListOfThatTrashOnly() {
        snapshot("p1", listOf("m1", "m2"), accountId = "accA", mailboxId = "trash")
        snapshot("p2", listOf("m5"), accountId = "accA", mailboxId = "junk")
        snapshot("p3", listOf("m7"), accountId = "accB", mailboxId = "trash")

        db.prepareStatement(deleteForMailboxSql).use { ps ->
            ps.setString(1, "accA"); ps.setString(2, "trash"); ps.executeUpdate()
        }

        // Nothing left to destroy for the undone purge — the snapshot IS the order.
        assertEquals(emptyList<String>(), wave("p1"))
        assertEquals(listOf("m5"), wave("p2"))
        assertEquals(listOf("m7"), wave("p3", "accB"))
    }

    @Test fun aFinishedOrAbandonedPurgeDropsItsWholeSnapshot() {
        snapshot("p1", listOf("m1", "m2"), accountId = "accA")
        snapshot("p1", listOf("m3"), accountId = "accB")

        db.prepareStatement(deleteSnapshotSql).use { ps -> ps.setString(1, "p1"); ps.executeUpdate() }

        assertEquals(0, total())
    }

    @Test fun anAbandonedSnapshotIsSweptByAge() {
        val now = 10L * TrashPurge.SNAPSHOT_TTL_MS
        snapshot("old", listOf("m1"), createdAt = now - TrashPurge.SNAPSHOT_TTL_MS - 1)
        snapshot("fresh", listOf("m2"), createdAt = now)

        db.prepareStatement(sweepSql).use { ps ->
            ps.setLong(1, now - TrashPurge.SNAPSHOT_TTL_MS); ps.executeUpdate()
        }

        assertEquals(emptyList<String>(), wave("old"))
        assertEquals(listOf("m2"), wave("fresh"))
    }

    @Test fun signingOutTakesThatAccountsDestroyListWithIt() {
        snapshot("p1", listOf("m1"), accountId = "accA")
        snapshot("p2", listOf("m2"), accountId = "accB")

        db.prepareStatement(deleteForAccountSql).use { ps -> ps.setString(1, "accA"); ps.executeUpdate() }

        assertEquals(emptyList<String>(), wave("p1", "accA"))
        assertEquals(listOf("m2"), wave("p2", "accB"))
    }

    @Test fun theSameIdCanBelongToTwoAccountsSnapshotsAtOnce() {
        snapshot("p1", listOf("m1"), accountId = "accA")
        snapshot("p1", listOf("m1"), accountId = "accB")

        // The composite key keeps them apart instead of collapsing them into one row.
        assertEquals(2, total())
    }
}
