package app.sterna.core.data.mail

import app.sterna.core.data.db.BODY_CACHE_CLEAR_SQL
import app.sterna.core.data.db.EMAILS_CREATE_SQL
import app.sterna.core.data.db.EMAIL_BODIES_CREATE_SQL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.sql.DriverManager

/**
 * The upgrade purge of the cached message bodies: ONCE per version bump, and not one time more.
 *
 * Both halves matter and they pull in opposite directions. Never purging leaves a new reader
 * feature invisible on the mail already in the cache — which, thanks to the prefetch, is the
 * twenty newest messages of the inbox, i.e. the newsletters this lot is about. Purging too often
 * throws away every body on every start and turns each message back into a network round trip.
 */
class BodyCacheUpgradeTest {

    @Test fun `a version bump purges, and records the version it purged for`() {
        assertEquals(167, bodyCachePurgeVersion(purgedForVersion = 166, currentVersion = 167))
    }

    /** ⛔ The witness. Same version, second start: nothing to do. */
    @Test fun `the same version does not purge again`() {
        assertNull(bodyCachePurgeVersion(purgedForVersion = 166, currentVersion = 166))
    }

    /** A fresh install records the version without ever having had anything to drop. */
    @Test fun `a first launch records the version`() {
        assertEquals(166, bodyCachePurgeVersion(purgedForVersion = 0, currentVersion = 166))
    }

    /** A downgrade (a sideloaded older build) purges nothing: its own bodies are its own. */
    @Test fun `an older build does not purge`() {
        assertNull(bodyCachePurgeVersion(purgedForVersion = 167, currentVersion = 166))
    }

    /**
     * ⛔ What the purge is allowed to delete, executed against real in-memory SQLite and built
     * from the very statement the DAO ships (and the very schema the migrations create).
     *
     * The bodies are a cache: dropping them costs one refetch per message read. The message list
     * is not — it is what the app shows when it is offline, and rebuilding it means a full resync
     * of every folder. One clause too few in this statement, run automatically at the first
     * launch after an update, would be indistinguishable from data loss.
     */
    @Test fun `the purge empties the body cache and touches nothing else`() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { st ->
                st.executeUpdate(EMAIL_BODIES_CREATE_SQL)
                st.executeUpdate(EMAILS_CREATE_SQL)
                st.executeUpdate(
                    "INSERT INTO email_bodies VALUES('m1','accA','{\"id\":\"m1\"}','{}',1000)",
                )
                st.executeUpdate(
                    "INSERT INTO email_bodies VALUES('m2','accB','{\"id\":\"m2\"}','{}',2000)",
                )
                st.executeUpdate(
                    "INSERT INTO emails VALUES('m1','accA','inbox',NULL,'Weekly digest',NULL," +
                        "NULL,NULL,NULL,0,0,0,1000)",
                )
            }

            db.createStatement().use { it.executeUpdate(BODY_CACHE_CLEAR_SQL) }

            assertEquals("every account's bodies go, not just one", 0, count(db, "email_bodies"))
            assertEquals("the message list must survive the purge", 1, count(db, "emails"))
        }
    }

    private fun count(db: java.sql.Connection, table: String): Int =
        db.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> rs.next(); rs.getInt(1) }
        }

    /** Two launches of the same build, played out: the second one is a no-op. */
    @Test fun `the purge happens exactly once across restarts`() {
        var recorded = 166
        val purges = mutableListOf<Int>()
        repeat(3) {
            bodyCachePurgeVersion(recorded, 167)?.let { version ->
                purges += version
                recorded = version
            }
        }
        assertEquals(listOf(167), purges)
    }
}
