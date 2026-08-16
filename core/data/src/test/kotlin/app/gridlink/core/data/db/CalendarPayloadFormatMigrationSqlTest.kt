package app.gridlink.core.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs the exact 24→25 statement ([CALENDAR_PAYLOAD_FORMAT_SQL]) against a real SQLite engine.
 *
 * The point is the DEFAULT. Every event already cached was written by the CalDAV path and holds
 * iCalendar text, and there is no sync that goes back and labels them; they have to come out of the
 * migration already saying so, or the first month view after an update reads every one of them with
 * the JSON parser and shows an empty calendar.
 */
class CalendarPayloadFormatMigrationSqlTest {
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
        }
    }

    @After fun tearDown() = db.close()

    @Test fun existingRowsBecomeIcalendarNotNullAndNotEmpty() {
        db.createStatement().use {
            it.executeUpdate(
                "INSERT INTO `calendar_events` VALUES ('a', '/dav/x.ics', 'u-1', 'BEGIN:VCALENDAR')",
            )
        }

        db.createStatement().use { it.executeUpdate(CALENDAR_PAYLOAD_FORMAT_SQL) }

        db.createStatement().use { st ->
            st.executeQuery("SELECT `payloadFormat`, `raw` FROM `calendar_events`").use { rs ->
                assertTrue(rs.next())
                assertEquals(CalendarEventEntity.FORMAT_ICALENDAR, rs.getString(1))
                // The payload itself is untouched: this migration labels rows, it does not convert
                // them. A migration that rewrote payloads would be unrecoverable if it were wrong.
                assertEquals("BEGIN:VCALENDAR", rs.getString(2))
            }
        }
    }

    @Test fun theColumnRefusesANullFormat() {
        db.createStatement().use { it.executeUpdate(CALENDAR_PAYLOAD_FORMAT_SQL) }

        val failed = runCatching {
            db.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO `calendar_events` VALUES ('a', '/dav/y.ics', 'u-2', 'X', NULL)",
                )
            }
        }.isFailure

        // Three states (icalendar, jscalendar, "the server did not say") would make every reader
        // guess. NOT NULL is what keeps the discriminator to two answers.
        assertTrue("a null payloadFormat should be rejected", failed)
    }

    @Test fun aJscalendarRowKeepsItsOwnFormat() {
        db.createStatement().use { st ->
            st.executeUpdate(CALENDAR_PAYLOAD_FORMAT_SQL)
            st.executeUpdate(
                "INSERT INTO `calendar_events` VALUES ('a', 'jmap:event/e1', 'u-3', '{}', 'jscalendar')",
            )
        }

        db.createStatement().use { st ->
            st.executeQuery(
                "SELECT `payloadFormat` FROM `calendar_events` WHERE `href` = 'jmap:event/e1'",
            ).use { rs ->
                assertTrue(rs.next())
                assertEquals(CalendarEventEntity.FORMAT_JSCALENDAR, rs.getString(1))
            }
        }
    }
}
