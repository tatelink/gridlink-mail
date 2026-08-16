package app.gridlink.core.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs the exact 25→26 statement ([CONTACT_PAYLOAD_FORMAT_SQL]) against a real SQLite engine.
 *
 * Same point as its calendar twin: the DEFAULT. Every contact already cached came down CardDAV and
 * holds vCard text, and nothing goes back to label them afterwards. They have to come out of the
 * migration already saying so, or the first Contacts tab after an update reads every card with the
 * JSON parser and shows an address book of blank names.
 */
class ContactPayloadFormatMigrationSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE `address_book_contacts`(
                  `accountId` TEXT NOT NULL, `href` TEXT NOT NULL, `uid` TEXT NOT NULL,
                  `raw` TEXT NOT NULL, PRIMARY KEY(`accountId`, `href`))
                """.trimIndent(),
            )
        }
    }

    @After fun tearDown() = db.close()

    @Test fun existingRowsBecomeVcardNotNullAndNotEmpty() {
        db.createStatement().use {
            it.executeUpdate(
                "INSERT INTO `address_book_contacts` VALUES ('a', '/dav/x.vcf', 'u-1', 'BEGIN:VCARD')",
            )
        }

        db.createStatement().use { it.executeUpdate(CONTACT_PAYLOAD_FORMAT_SQL) }

        db.createStatement().use { st ->
            st.executeQuery("SELECT `payloadFormat`, `raw` FROM `address_book_contacts`").use { rs ->
                assertTrue(rs.next())
                assertEquals(AddressBookContactEntity.FORMAT_VCARD, rs.getString(1))
                // The payload is untouched: this migration labels rows, it does not convert them.
                assertEquals("BEGIN:VCARD", rs.getString(2))
            }
        }
    }

    @Test fun theColumnRefusesANullFormat() {
        db.createStatement().use { it.executeUpdate(CONTACT_PAYLOAD_FORMAT_SQL) }

        val failed = runCatching {
            db.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO `address_book_contacts` VALUES ('a', '/dav/y.vcf', 'u-2', 'X', NULL)",
                )
            }
        }.isFailure

        // Two answers, never three: a row that does not say which parser to use is a row every
        // reader has to guess about.
        assertTrue("a null payloadFormat should be rejected", failed)
    }

    @Test fun aJscontactRowKeepsItsOwnFormat() {
        db.createStatement().use { st ->
            st.executeUpdate(CONTACT_PAYLOAD_FORMAT_SQL)
            st.executeUpdate(
                "INSERT INTO `address_book_contacts` VALUES ('a', 'jmap:card/c1', 'u-3', '{}', 'jscontact')",
            )
        }

        db.createStatement().use { st ->
            st.executeQuery(
                "SELECT `payloadFormat` FROM `address_book_contacts` WHERE `href` = 'jmap:card/c1'",
            ).use { rs ->
                assertTrue(rs.next())
                assertEquals(AddressBookContactEntity.FORMAT_JSCONTACT, rs.getString(1))
            }
        }
    }
}
