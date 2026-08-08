package app.gridlink.core.data.contacts

import app.gridlink.core.jmap.model.ContactCardCustomField
import app.gridlink.core.jmap.model.ContactCardGroup
import app.gridlink.core.jmap.model.ContactCardPhoto
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
            FN:Karen Caldwell
            N:Caldwell;Karen;;;
            TEL;TYPE=CELL:+1 704-232-8656
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
            FN:McLane Foodservice
            N:Foodservice;McLane;;;
            ORG:McLane Foodservice;
            UID:hl-135
            TITLE:Customer Service
            END:VCARD
            """.trimIndent(),
        )!!
        val seed = ContactEdit.from(card)
        assertTrue(seed.touchedSince(seed).isEmpty())
        // And the seed still spells an organization, so a save would write ORG == FN again.
        assertTrue(seed.isOrganization)
        assertEquals("McLane Foodservice", seed.organizationValue)
    }

    @Test
    fun `whitespace and blank list entries do not count as edits`() {
        val original = ContactEdit(given = "Karen", family = "Caldwell", emails = listOf("k@acme.example"))
        val fromForm = ContactEdit(
            given = " Karen ",
            family = "Caldwell",
            // The form keeps a blank trailing row for grow-on-type; it must not read as an edit.
            emails = listOf(" k@acme.example ", ""),
            phones = listOf(""),
        )
        assertTrue(fromForm.touchedSince(original).isEmpty())
    }

    @Test
    fun `each field lands in its own group`() {
        val base = ContactEdit(given = "Karen", family = "Caldwell")

        assertEquals(setOf(ContactCardGroup.NAME), base.copy(given = "Kate").touchedSince(base))
        assertEquals(setOf(ContactCardGroup.ORGANIZATION), base.copy(company = "Acme").touchedSince(base))
        assertEquals(setOf(ContactCardGroup.TITLE), base.copy(title = "Dispatcher").touchedSince(base))
        assertEquals(setOf(ContactCardGroup.EMAILS), base.copy(emails = listOf("k@a.example")).touchedSince(base))
        assertEquals(setOf(ContactCardGroup.PHONES), base.copy(phones = listOf("555")).touchedSince(base))
        assertEquals(setOf(ContactCardGroup.NOTE), base.copy(note = "Hi").touchedSince(base))
        assertEquals(
            setOf(ContactCardGroup.PHOTO),
            base.copy(photo = ContactCardPhoto("image/jpeg", "R0lGODlh")).touchedSince(base),
        )
        assertEquals(
            setOf(ContactCardGroup.CUSTOM),
            base.copy(customFields = listOf(ContactCardCustomField("Office", "B-240"))).touchedSince(base),
        )
    }

    @Test
    fun `a card with a photo and custom fields still round-trips as a no-op`() {
        val card = VCard.parse(
            """
            BEGIN:VCARD
            VERSION:4.0
            FN:Marisol Rivera
            N:Rivera;Marisol;;;
            PHOTO:data:image/jpeg;base64,R0lGODlh
            X-GRIDLINK-FIELD:Office;Ballantyne
            END:VCARD
            """.trimIndent(),
        )!!
        val seed = ContactEdit.from(card)
        assertTrue(seed.touchedSince(seed).isEmpty())
        // And the seed actually carried them — an empty-vs-empty no-op would prove nothing.
        assertEquals("R0lGODlh", seed.photo!!.base64)
        assertEquals(listOf(ContactCardCustomField("Office", "Ballantyne")), seed.customFields)
    }

    @Test
    fun `a fully blank custom row is the grow-on-type spare, not an edit`() {
        val original = ContactEdit(given = "Karen", family = "Caldwell")
        val fromForm = original.copy(customFields = listOf(ContactCardCustomField(" ", "")))
        assertTrue(fromForm.touchedSince(original).isEmpty())
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
        val write = ContactEdit(family = "McLane Foodservice").toCardWrite("u-1")
        assertEquals("McLane Foodservice", write.fullName)
        assertEquals("", write.given)
        assertEquals("", write.family)
        assertEquals("McLane Foodservice", write.organization)
    }

    @Test
    fun `toCardWrite sends trimmed values and drops blank list rows`() {
        val write = ContactEdit(
            given = " Karen ",
            family = "Caldwell",
            title = " ",
            emails = listOf("k@acme.example", " "),
            note = "",
        ).toCardWrite("u-2")

        assertEquals("Karen Caldwell", write.fullName)
        assertEquals("Karen", write.given)
        assertNull(write.title)
        assertNull(write.note)
        assertEquals(listOf("k@acme.example"), write.emails)
    }
}
