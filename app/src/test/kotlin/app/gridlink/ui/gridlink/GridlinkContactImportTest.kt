package app.gridlink.ui.gridlink

import org.junit.Assert.assertEquals
import org.junit.Test

class GridlinkContactImportTest {

    private fun card(name: String, email: String? = null, phone: String? = null) = buildString {
        append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
        append("FN:$name\r\nN:${name.substringAfter(' ')};${name.substringBefore(' ')};;;\r\n")
        if (email != null) append("EMAIL:$email\r\n")
        if (phone != null) append("TEL:$phone\r\n")
        append("END:VCARD\r\n")
    }

    private fun plan(raw: String, emails: Set<String> = emptySet(), names: Set<String> = emptySet()) =
        planContactImport(raw, emails, names)

    @Test
    fun `reads every card in the file`() {
        val result = plan(card("Ada Lovelace", "ada@example.com") + card("Alan Turing", phone = "+441234"))
        assertEquals(listOf("Ada", "Alan"), result.edits.map { it.given })
        assertEquals(listOf("+441234"), result.edits[1].phones)
    }

    @Test
    fun `importing the same file twice adds nothing`() {
        val raw = card("Ada Lovelace", "ADA@example.com") + card("Alan Turing")
        val result = plan(raw, emails = setOf("ada@example.com"), names = setOf("Alan Turing"))
        assertEquals(0, result.edits.size)
        assertEquals(2, result.duplicates)
    }

    @Test
    fun `a repeat inside the file counts once`() {
        val result = plan(card("Ada Lovelace", "ada@example.com") + card("Ada L", "ada@example.com"))
        assertEquals(1, result.edits.size)
        assertEquals(1, result.duplicates)
    }

    @Test
    fun `a card with nothing on it is reported rather than saved`() {
        val result = plan("BEGIN:VCARD\r\nVERSION:3.0\r\nEND:VCARD\r\n" + card("Ada Lovelace"))
        assertEquals(1, result.edits.size)
        assertEquals(1, result.unreadable)
    }

    @Test
    fun `a phone's vCard 2_1 export keeps non-ASCII names`() {
        val raw = "BEGIN:VCARD\r\nVERSION:2.1\r\n" +
            "N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:M=C3=BCller;J=C3=B6=\r\nrg;;;\r\n" +
            "EMAIL;HOME:jorg@example.com\r\nEND:VCARD\r\n"
        val edit = plan(raw).edits.single()
        assertEquals("M\u00fcller", edit.family)
        assertEquals("J\u00f6rg", edit.given)
    }

    @Test
    fun `the summary leaves out what did not happen`() {
        val none = ContactImportPlan(emptyList(), 0, 0)
        assertEquals("Imported 3.", contactImportSummary(3, none, 0, null))
        val some = ContactImportPlan(emptyList(), 2, 1)
        assertEquals(
            "Imported 3. 2 already in contacts. 1 unreadable. 4 not saved: no address book",
            contactImportSummary(3, some, 4, "no address book"),
        )
    }
}
