package app.sterna.core.data.mail

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The real snooze predicate ([NOT_SNOOZED_PREDICATE]) run against an in-memory SQLite engine:
 * a pending snooze hides its message, a lapsed one does not, and deleting the `snoozed` row —
 * what "Cancel snooze" does (Codeberg #82) — hands the id straight back to the lists.
 */
class SnoozeFilterSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate("CREATE TABLE emails(id TEXT PRIMARY KEY, mailboxId TEXT)")
            st.executeUpdate("CREATE TABLE snoozed(emailId TEXT PRIMARY KEY, accountId TEXT, until INTEGER)")
            st.executeUpdate("INSERT INTO emails VALUES('a', 'inbox'), ('b', 'inbox'), ('c', 'inbox')")
        }
    }

    @After fun tearDown() = db.close()

    private fun snooze(id: String, until: Long) = db.createStatement().use {
        it.executeUpdate("INSERT INTO snoozed VALUES('$id', 'acc', $until)")
    }

    private fun unsnooze(id: String) = db.createStatement().use {
        it.executeUpdate("DELETE FROM snoozed WHERE emailId = '$id'")
    }

    /** The ids a list query would show, i.e. those surviving the snooze predicate. */
    private fun visibleIds(): List<String> = buildList {
        db.createStatement().use { st ->
            st.executeQuery("SELECT id FROM emails WHERE $NOT_SNOOZED_PREDICATE ORDER BY id").use { rs ->
                while (rs.next()) add(rs.getString(1))
            }
        }
    }

    private val now get() = System.currentTimeMillis()

    @Test fun `a pending snooze hides its message`() {
        snooze("b", now + 3_600_000)
        assertEquals(listOf("a", "c"), visibleIds())
    }

    @Test fun `a lapsed snooze hides nothing`() {
        snooze("b", now - 1_000)
        assertEquals(listOf("a", "b", "c"), visibleIds())
    }

    @Test fun `cancelling a snooze makes the message visible again`() {
        snooze("b", now + 3_600_000)
        assertEquals(listOf("a", "c"), visibleIds())
        unsnooze("b")
        assertEquals(listOf("a", "b", "c"), visibleIds())
    }

    @Test fun `rescheduling to a later deadline keeps it hidden`() {
        snooze("b", now + 3_600_000)
        db.createStatement().use { it.executeUpdate("UPDATE snoozed SET until = ${now + 86_400_000} WHERE emailId = 'b'") }
        assertEquals(listOf("a", "c"), visibleIds())
    }
}
