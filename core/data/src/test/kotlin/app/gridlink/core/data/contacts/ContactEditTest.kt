package app.gridlink.core.data.contacts

import app.gridlink.core.jmap.model.ContactCardGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContactEdit.touchedSince] decides what an update sends, so its false positives are wire churn
 * (every casual look at a card rewrites it on every synced device) and its false negatives are
 * lost edits. The no-op cases here use real card shapes from the live account, because the
 * org-promoted card is exactly where a naive display-field diff went wrong on paper.
 */
class ContactEditTest {

    @Test
    fun `an opened untouched saved form is the empty set`() {
        val card = VCard.parse(
            """
            BEGIN:VCARD
            VERSION:3.0
            FN:Kenna Chadwick
            N:Chadwick;Kenna;;;
            TEL;TYPE=CELL:+1 717-555-0142
            END:VCARD
            """.trimIndent(),
        )!!
        val seed = ContactEdit.from(card)
        assertTrue(seed.touchedSince(seed).isEmpty())
    }

    @Test
    fun `an org-promoted card round-trips as a no-op`() {
        // The trap case: ORG == FN, fake N components. ContactEdit.from puts the company in
        // `family`, and diffing that against the same derivation must be silent — diffing it
        // against the parsed N would report a name change on a card nobody touched.
        val card = VCard.parse(
            """
            BEGIN:VCARD
            VERSION:3.0
            FN:Redoak Foodservice
            N:Foodservice;Redoak;;;
            ORG:Redoak Foodservice;
            UID:hl-135
            TITLE:Customer Service
            END:VCARD
            """.trimIndent(),
        )!!
        val seed = ContactEdit.from(card)
        assertTrue(seed.touchedSince(seed).isEmpty())
        // And the seed still spells an organization, so a save would write ORG == FN again.
        assertTrue(seed.isOrganization)
        assertEquals("Redoak Foodservice", seed.organizationValue)
    }

    @Test
    fun `whitespace and blank list entries do not count as edits`() {
        val original = ContactEdit(given = "Kenna", family = "Chadwick", emails = listOf("k@acme.example"))
        val fromForm = ContactEdit(
            given = " Kenna ",
            family = "Chadwick",
            // The form keeps a blank trailing row for grow-on-type; it must not read as an edit.
            emails = listOf(" k@acme.example ", ""),
            phones = listOf(""),
        )
        assertTrue(fromForm.touchedSince(original).isEmpty())
    }

    @Test
    fun `each field lands in its own group`() {
        val base = ContactEdit(given = "Kenna", family = "Chadwick")

        assertEquals(setOf(ContactCardGroup.NAME), base.copy(given = "Kate").touchedSince(base))
        assertEquals(setOf(ContactCardGroup.ORGANIZATION), base.copy(company = "Acme").touchedSince(base))
        assertEquals(setOf(ContactCardGroup.TITLE), base.copy(title = "Dispatcher").touchedSince(base))
        assertEquals(setOf(ContactCardGroup.EMAILS), base.copy(emails = listOf("k@a.example")).touchedSince(base))
        assertEquals(setOf(ContactCardGroup.PHONES), base.copy(phones = listOf("555")).touchedSince(base))
        assertEquals(setOf(ContactCardGroup.NOTE), base.copy(note = "Hi").touchedSince(base))
    }

    @Test
    fun `clearing the company field of a company-named card is a wire no-op`() {
        // Both edits spell the same card: FN "Acme Corp", ORG "Acme Corp". One says it via the
        // company field, the other via the organization rule (family IS the company). The diff
        // works on what would be written, not on which form field carried it, so nothing travels.
        val viaCompanyField = ContactEdit(given = "", family = "Acme Corp", company = "Acme Corp")
        val viaOrgRule = ContactEdit(given = "", family = "Acme Corp", company = "")
        assertEquals(emptySet<ContactCardGroup>(), viaOrgRule.touchedSince(viaCompanyField))
    }

    @Test
    fun `toCardWrite strips name components off an organization card`() {
        val write = ContactEdit(family = "Redoak Foodservice").toCardWrite("u-1")
        assertEquals("Redoak Foodservice", write.fullName)
        assertEquals("", write.given)
        assertEquals("", write.family)
        assertEquals("Redoak Foodservice", write.organization)
    }

    @Test
    fun `toCardWrite sends trimmed values and drops blank list rows`() {
        val write = ContactEdit(
            given = " Kenna ",
            family = "Chadwick",
            title = " ",
            emails = listOf("k@acme.example", " "),
            note = "",
        ).toCardWrite("u-2")

        assertEquals("Kenna Chadwick", write.fullName)
        assertEquals("Kenna", write.given)
        assertNull(write.title)
        assertNull(write.note)
        assertEquals(listOf("k@acme.example"), write.emails)
    }
}
