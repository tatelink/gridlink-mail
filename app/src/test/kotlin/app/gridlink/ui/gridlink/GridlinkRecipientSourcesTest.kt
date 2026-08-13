package app.gridlink.ui.gridlink

import app.gridlink.contacts.ContactSuggestion
import app.gridlink.ui.gridlink.GridlinkSampleContacts.GridlinkContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the extra recipient sources promise. The two rules worth pinning are the id prefix, which is
 * the only thing keeping a real person from being refused as demo data on send, and the dedupe,
 * which is what stops the same address appearing twice under two different names.
 */
class GridlinkRecipientSourcesTest {

    private fun book(vararg emails: String) = emails.mapIndexed { i, email ->
        GridlinkContact(id = "book$i", given = "Book", family = "Person$i", role = "", email = email)
    }

    @Test fun `a suggestion with a name shows the name`() {
        val contact = gridlinkSuggestedContact(ContactSuggestion("p.ashby@gridlink.me", "Paloma Ashby"))
        assertEquals("Paloma Ashby", contact.displayName)
        assertEquals("p.ashby@gridlink.me", contact.email)
    }

    @Test fun `a suggestion with no name shows the address`() {
        val contact = gridlinkSuggestedContact(ContactSuggestion("nobody@gridlink.me", null))
        assertEquals("nobody@gridlink.me", contact.displayName)
    }

    @Test fun `a blank name is treated as no name`() {
        val contact = gridlinkSuggestedContact(ContactSuggestion("nobody@gridlink.me", "   "))
        assertEquals("nobody@gridlink.me", contact.displayName)
    }

    @Test fun `a suggested contact is never mistaken for sample data`() {
        // 🔴 The send guard refuses sample contacts by id. A real person arriving from the device
        // book or from recents must not collide with one, or sending to them is refused outright.
        val real = GridlinkSampleContacts.all.first()
        val contact = gridlinkSuggestedContact(ContactSuggestion(real.email, real.displayName))
        assertFalse(GridlinkSampleContacts.isSample(contact))
    }

    @Test fun `the same address from two sources is one row`() {
        val merged = gridlinkRecipientCandidates(
            book = emptyList(),
            suggested = listOf(
                ContactSuggestion("p.ashby@gridlink.me", "Paloma Ashby"),
                ContactSuggestion("P.Ashby@Gridlink.me", null),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals("Paloma Ashby", merged.single().displayName)
    }

    @Test fun `the address book wins over a suggestion for the same person`() {
        val merged = gridlinkRecipientCandidates(
            book = book("p.ashby@gridlink.me"),
            suggested = listOf(ContactSuggestion("P.ASHBY@gridlink.me", "Someone Else")),
        )
        assertEquals(1, merged.size)
        assertEquals("book0", merged.single().id)
    }

    @Test fun `suggestions that are not in the book are appended after it`() {
        val merged = gridlinkRecipientCandidates(
            book = book("m.bexley@gridlink.me"),
            suggested = listOf(ContactSuggestion("new@gridlink.me", "New Person")),
        )
        assertEquals(listOf("m.bexley@gridlink.me", "new@gridlink.me"), merged.map { it.email })
    }

    @Test fun `no suggestions leaves the book exactly as it was`() {
        val original = book("m.bexley@gridlink.me")
        assertTrue(gridlinkRecipientCandidates(original, emptyList()) === original)
    }
}
