package app.gridlink.core.data.contacts

import app.gridlink.core.jmap.model.ContactCardGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one guarantee this file exists to hold: [VCardWrite.patch] rewrites the touched properties
 * and passes every other BYTE through. The cards here carry the things the model does not know —
 * PHOTO, ADR, X-props, `item1.` groups, folded lines — because those are exactly what a
 * regenerate-from-model bug would silently delete on the first casual edit.
 */
class VCardWriteTest {

    private val richCard = listOf(
        "BEGIN:VCARD",
        "VERSION:3.0",
        "PRODID:-//Sabre//Sabre VObject 4.5.7//EN",
        "UID:hl-42",
        "FN:Kenna Chadwick",
        "N:Chadwick;Kenna;;;",
        "ORG:Acme Corp",
        "TITLE:Dispatcher",
        "EMAIL;TYPE=WORK:kenna@acme.example",
        "TEL;TYPE=CELL:+1 717-555-0142",
        "ADR;TYPE=HOME:;;123 Main St;Ashvale;PA;17036;USA",
        "PHOTO;ENCODING=b;TYPE=JPEG:dGhpcyBpcyBub3QgYSByZWFsIHBob3RvIGJ1dCBpdCBpcyBs",
        " b25nIGVub3VnaCB0byBmb2xk",
        "item1.URL:https://acme.example",
        "item1.X-ABLabel:Work",
        "X-CUSTOM-FLAG:kept",
        "NOTE:Prefers morning calls. Long note that the exporter folded across two ph",
        " ysical lines for RFC 6350.",
        "REV;VALUE=DATE-TIME:2026-07-12T19:09:34Z",
        "END:VCARD",
    ).joinToString("\r\n", postfix = "\r\n")

    private val richEdit = ContactEdit(
        given = "Kenna",
        family = "Chadwick",
        company = "Acme Corp",
        title = "Dispatcher",
        emails = listOf("kenna@acme.example"),
        phones = listOf("+1 717-555-0142"),
        note = "Prefers morning calls. Long note that the exporter folded across two physical lines for RFC 6350.",
    )

    @Test
    fun `an empty touched set returns the exact same string instance`() {
        // Not equals: SAME. The no-op save must be a no-op on the wire, and the cheapest proof
        // that nothing was rewritten is that nothing was even copied.
        assertSame(richCard, VCardWrite.patch(richCard, richEdit, emptySet()))
    }

    @Test
    fun `patching one group leaves every unmodelled line byte-for-byte intact`() {
        val patched = VCardWrite.patch(
            richCard,
            richEdit.copy(title = "Senior Dispatcher"),
            setOf(ContactCardGroup.TITLE),
        )

        assertTrue(patched.contains("TITLE:Senior Dispatcher"))
        assertFalse(patched.contains("TITLE:Dispatcher\r\n"))
        // Everything the model does not know survives, byte-for-byte.
        for (line in listOf(
            "PRODID:-//Sabre//Sabre VObject 4.5.7//EN",
            "ADR;TYPE=HOME:;;123 Main St;Ashvale;PA;17036;USA",
            "PHOTO;ENCODING=b;TYPE=JPEG:dGhpcyBpcyBub3QgYSByZWFsIHBob3RvIGJ1dCBpdCBpcyBs\r\n b25nIGVub3VnaCB0byBmb2xk",
            "item1.URL:https://acme.example",
            "item1.X-ABLabel:Work",
            "X-CUSTOM-FLAG:kept",
            "REV;VALUE=DATE-TIME:2026-07-12T19:09:34Z",
            // Untouched groups are unmodelled too, as far as this patch is concerned.
            "EMAIL;TYPE=WORK:kenna@acme.example",
            "TEL;TYPE=CELL:+1 717-555-0142",
            "N:Chadwick;Kenna;;;",
        )) {
            assertTrue("missing after patch: $line", patched.contains(line))
        }
    }

    @Test
    fun `removing a folded property takes its continuation lines with it`() {
        val patched = VCardWrite.patch(
            richCard,
            richEdit.copy(note = "Short now."),
            setOf(ContactCardGroup.NOTE),
        )

        assertTrue(patched.contains("NOTE:Short now."))
        // The folded tail of the old NOTE must not survive as an orphan continuation line.
        assertFalse(patched.contains("ysical lines"))
        // The folded PHOTO, which was NOT touched, keeps its continuation.
        assertTrue(patched.contains(" b25nIGVub3VnaCB0byBmb2xk"))
    }

    @Test
    fun `a patched group's parameters are lost only for the group that was edited`() {
        // Editing emails rewrites EMAIL unparameterised. That is the documented cost of the DAV
        // path; the test pins that TEL's parameters are NOT part of that cost.
        val patched = VCardWrite.patch(
            richCard,
            richEdit.copy(emails = listOf("kchadwick@acme.example")),
            setOf(ContactCardGroup.EMAILS),
        )

        assertTrue(patched.contains("EMAIL:kchadwick@acme.example"))
        assertFalse(patched.contains("kenna@acme.example"))
        assertTrue(patched.contains("TEL;TYPE=CELL:+1 717-555-0142"))
    }

    @Test
    fun `an emptied group means the property is gone which is how a vCard spells cleared`() {
        val patched = VCardWrite.patch(
            richCard,
            richEdit.copy(note = ""),
            setOf(ContactCardGroup.NOTE),
        )

        assertFalse(patched.contains("NOTE"))
        assertTrue(patched.contains("END:VCARD"))
    }

    @Test
    fun `replacements land inside the card not after END`() {
        val patched = VCardWrite.patch(
            richCard,
            richEdit.copy(title = "Lead"),
            setOf(ContactCardGroup.TITLE),
        )
        assertTrue(patched.indexOf("TITLE:Lead") < patched.indexOf("END:VCARD"))
    }

    @Test
    fun `an LF-only source stays LF-only`() {
        val lfCard = richCard.replace("\r\n", "\n")
        val patched = VCardWrite.patch(lfCard, richEdit.copy(title = "Lead"), setOf(ContactCardGroup.TITLE))
        assertFalse(patched.contains('\r'))
    }

    @Test
    fun `patch then parse round-trips to the edit that was saved`() {
        // The repo caches the patched text and re-parses it for the row; a patch VCard.parse
        // cannot read back is a card that renders wrong everywhere.
        val edit = richEdit.copy(given = "Kate", note = "Renamed.")
        val patched = VCardWrite.patch(richCard, edit, setOf(ContactCardGroup.NAME, ContactCardGroup.NOTE))
        val reparsed = VCard.parse(patched)!!

        assertEquals("Kate", reparsed.given)
        assertEquals("Chadwick", reparsed.family)
        assertEquals("Renamed.", reparsed.note)
        // And the no-op guarantee holds across the round trip: same derivation both sides.
        assertTrue(edit.touchedSince(ContactEdit.from(reparsed)).isEmpty())
    }

    @Test
    fun `build writes an organization card as ORG equals FN with no N line`() {
        val vcf = VCardWrite.build(
            ContactEdit(family = "Redoak Foodservice", emails = listOf("cs@redoak.example")),
            uid = "u-1",
        )

        assertTrue(vcf.contains("FN:Redoak Foodservice"))
        assertTrue(vcf.contains("ORG:Redoak Foodservice"))
        assertFalse(vcf.contains("\r\nN:"))
        // And the reader recognises its own on the way back in.
        assertTrue(VCard.parse(vcf)!!.isOrganization)
    }

    @Test
    fun `build escapes text values the RFC way`() {
        val vcf = VCardWrite.build(
            ContactEdit(
                given = "Ana",
                family = "Ruiz, Jr; PhD",
                note = "Line one\nLine two\\end",
            ),
            uid = "u-2",
        )

        assertTrue(vcf.contains("N:Ruiz\\, Jr\\; PhD;Ana;;;"))
        assertTrue(vcf.contains("NOTE:Line one\\nLine two\\\\end"))
        val reparsed = VCard.parse(vcf)!!
        assertEquals("Ruiz, Jr; PhD", reparsed.family)
        assertEquals("Line one\nLine two\\end", reparsed.note)
    }
}
