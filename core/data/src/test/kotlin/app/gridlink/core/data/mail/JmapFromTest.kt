package app.gridlink.core.data.mail

import app.gridlink.core.jmap.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A JMAP send must carry the identity's display name, as an IMAP/SMTP send does. A freshly added
 * JMAP account has no account name, so the composer passes the address with no name; the From then
 * has to borrow the name of the server identity that owns that address, not go out as `<address>`.
 */
class JmapFromTest {

    private val serverIdentity = EmailAddress(name = "Avery Chen", email = "avery@gridlink.me")
    private val fallback = serverIdentity

    @Test fun chosenAddressWithoutANameTakesTheOwningIdentitysName() {
        val from = jmapFrom(null, "avery@gridlink.me", fallback, listOf(serverIdentity))
        assertEquals(EmailAddress(name = "Avery Chen", email = "avery@gridlink.me"), from)
    }

    @Test fun addressMatchIgnoresCase() {
        val from = jmapFrom("  ", "Avery@Gridlink.ME", fallback, listOf(serverIdentity))
        assertEquals("Avery Chen", from.name)
        assertEquals("Avery@Gridlink.ME", from.email)
    }

    @Test fun anExplicitNameWins() {
        val from = jmapFrom("Avery IMAP", "avery@gridlink.me", fallback, listOf(serverIdentity))
        assertEquals("Avery IMAP", from.name)
    }

    @Test fun noChosenAddressSendsAsTheFallbackIdentity() {
        val from = jmapFrom(null, null, fallback, emptyList())
        assertEquals(EmailAddress(name = "Avery Chen", email = "avery@gridlink.me"), from)
    }

    @Test fun anAliasNeverBorrowsAnotherIdentitysName() {
        val alias = EmailAddress(name = "", email = "sales@gridlink.me")
        val from = jmapFrom(null, "sales@gridlink.me", fallback, listOf(serverIdentity, alias))
        assertEquals("sales@gridlink.me", from.email)
        assertNull(from.name)
    }

    @Test fun anAliasWithItsOwnNameUsesIt() {
        val alias = EmailAddress(name = "Gridlink Sales", email = "sales@gridlink.me")
        val from = jmapFrom(null, "sales@gridlink.me", fallback, listOf(serverIdentity, alias))
        assertEquals("Gridlink Sales", from.name)
    }

    @Test fun aBlankNameIsNeverSent() {
        val nameless = EmailAddress(name = " ", email = "avery@gridlink.me")
        val from = jmapFrom("", "avery@gridlink.me", nameless, listOf(nameless))
        assertNull(from.name)
    }
}
