package app.gridlink.core.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs the exact 26→27 statements against a real SQLite engine.
 *
 * 🔴 The opposite requirement to its two predecessors: these columns must come out NULL and must NOT
 * be backfilled. Every row that exists when this runs arrived over DAV, and null is what says so.
 * A default of any kind here would claim every cached contact and event was JMAP-backed, and the
 * sync would then delete the lot the first time a listing came back without their invented ids.
 */
class RemoteIdMigrationSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE `calendar_events`(
                  `accountId` TEXT NOT NULL, `href` TEXT NOT NULL, `uid` TEXT NOT NULL,
                  `raw` TEXT NOT NULL, PRIMARY KEY(`accountId`, `href`))
                """.trimIndent(),
            )
            st.executeUpdate(
                """
                CREATE TABLE `address_book_contacts`(
                  `accountId` TEXT NOT NULL, `href` TEXT NOT NULL, `uid` TEXT NOT NULL,
                  `raw` TEXT NOT NULL, PRIMARY KEY(`accountId`, `href`))
                """.trimIndent(),
            )
            st.executeUpdate(
                """
                CREATE TABLE `dav_collections`(
                  `accountId` TEXT NOT NULL, `url` TEXT NOT NULL, `kind` TEXT NOT NULL,
                  PRIMARY KEY(`accountId`, `url`))
                """.trimIndent(),
            )
        }
    }

    @After fun tearDown() = db.close()

    @Test fun everyExistingRowComesOutSayingItIsNotJmapBacked() {
        db.createStatement().use { st ->
            st.executeUpdate("INSERT INTO `calendar_events` VALUES ('a', '/dav/x.ics', 'u-1', 'BEGIN:VCALENDAR')")
            st.executeUpdate("INSERT INTO `address_book_contacts` VALUES ('a', '/dav/x.vcf', 'u-2', 'BEGIN:VCARD')")
            st.executeUpdate("INSERT INTO `dav_collections` VALUES ('a', '/dav/cal/personal/', 'calendar')")
            st.executeUpdate(EVENT_REMOTE_ID_SQL)
            st.executeUpdate(CONTACT_REMOTE_ID_SQL)
            st.executeUpdate(COLLECTION_REMOTE_ID_SQL)
        }

        listOf("calendar_events", "address_book_contacts", "dav_collections").forEach { table ->
            db.createStatement().use { st ->
                st.executeQuery("SELECT `remoteId` FROM `$table`").use { rs ->
                    assertTrue(rs.next())
                    assertNull("$table.remoteId should migrate to NULL, not a default", rs.getString(1))
                }
            }
        }
    }

    @Test fun theKeysThemselvesAreLeftAloneWhichIsTheWholePoint() {
        db.createStatement().use { st ->
            st.executeUpdate("INSERT INTO `calendar_events` VALUES ('a', '/dav/x.ics', 'u-1', 'BEGIN:VCALENDAR')")
            st.executeUpdate("INSERT INTO `address_book_contacts` VALUES ('a', '/dav/x.vcf', 'u-2', 'BEGIN:VCARD')")
            st.executeUpdate(EVENT_REMOTE_ID_SQL)
            st.executeUpdate(CONTACT_REMOTE_ID_SQL)
        }

        // The href is what both system mirrors derive the provider's row id from. This migration
        // exists so those keys can stay put, so it had better not be the thing that moves them.
        db.createStatement().use { st ->
            st.executeQuery("SELECT `href` FROM `calendar_events`").use { rs ->
                assertTrue(rs.next())
                assertEquals("/dav/x.ics", rs.getString(1))
            }
        }
        db.createStatement().use { st ->
            st.executeQuery("SELECT `href` FROM `address_book_contacts`").use { rs ->
                assertTrue(rs.next())
                assertEquals("/dav/x.vcf", rs.getString(1))
            }
        }
    }

    @Test fun aRowMayCarryAServerIdAfterwards() {
        db.createStatement().use { st ->
            st.executeUpdate(EVENT_REMOTE_ID_SQL)
            st.executeUpdate(
                "INSERT INTO `calendar_events` VALUES ('a', '/dav/x.ics', 'u-1', 'BEGIN:VCALENDAR', 'E1')",
            )
        }

        // The adopted shape: a CalDAV href and a JMAP id on the same row, which is the state this
        // whole change exists to make representable.
        db.createStatement().use { st ->
            st.executeQuery("SELECT `href`, `remoteId` FROM `calendar_events`").use { rs ->
                assertTrue(rs.next())
                assertEquals("/dav/x.ics", rs.getString(1))
                assertEquals("E1", rs.getString(2))
            }
        }
    }
}
