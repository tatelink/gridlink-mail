package app.gridlink.core.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentLineTest {

    @Test
    fun `a quoted parameter may contain the colon that would otherwise end the header`() {
        val line = ContentLines.parse("""DTSTART;TZID="Eastern Standard Time":20260610T143000""")!!

        assertEquals("DTSTART", line.name)
        assertEquals("Eastern Standard Time", line.param("TZID"))
        assertEquals("20260610T143000", line.value)
    }

    @Test
    fun `a value may contain colons of its own`() {
        val line = ContentLines.parse("ORGANIZER;CN=Dana:mailto:d.locklear@gridlink.me")!!
        assertEquals("mailto:d.locklear@gridlink.me", line.value)
        assertEquals("Dana", line.param("CN"))
    }

    @Test
    fun `folded lines rejoin without the continuation space`() {
        val raw = "UID:040000008200E00074C5B7101A82E008000000\r\n 00D3ADF1CC0600DD01\r\nSUMMARY:Pineville\r\n"
        val lines = ContentLines.parseAll(raw)

        assertEquals(2, lines.size)
        assertEquals("04000000820" + "0E00074C5B7101A82E00800000000D3ADF1CC0600DD01", lines[0].value)
        assertEquals("Pineville", lines[1].value)
    }

    @Test
    fun `param does not split on a comma but paramValues does`() {
        val line = ContentLines.parse("TEL;TYPE=CELL,VOICE:+1 704-232-8656")!!

        // 🔴 The whole-text reading is what protects `CN=Locklear, Dana` from becoming "Locklear".
        assertEquals("CELL,VOICE", line.param("TYPE"))
        assertEquals(listOf("CELL", "VOICE"), line.paramValues("TYPE"))
    }

    @Test
    fun `a vCard 2 dot 1 shorthand parameter is read as a TYPE`() {
        val line = ContentLines.parse("TEL;WORK;VOICE:+1 704-000-0000")!!
        assertEquals(listOf("WORK", "VOICE"), line.paramValues("TYPE"))
    }

    @Test
    fun `a structured value splits on semicolons but not on escaped ones`() {
        assertEquals(
            listOf("Foodservice", "McLane", "", "", ""),
            ContentLines.splitStructured("Foodservice;McLane;;;"),
        )
        // An escaped semicolon is part of the surname, not a component break.
        assertEquals(listOf("""O\;Brien""", "Sean"), ContentLines.splitStructured("""O\;Brien;Sean"""))
    }

    @Test
    fun `unescapes the escapes a real exporter emits`() {
        assertEquals(
            "5821 Fairview Rd, Ste 200 Charlotte NC 28209-3649",
            ContentLines.unescapeText("""5821 Fairview Rd\, Ste 200 Charlotte NC 28209-3649"""),
        )
        assertEquals("\n", ContentLines.unescapeText("""\n"""))
    }

    @Test
    fun `a line with no colon is not a content line`() {
        assertNull(ContentLines.parse("BEGIN"))
        assertNull(ContentLines.parse("   "))
    }
}
