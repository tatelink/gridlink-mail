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
        val line = ContentLines.parse("ORGANIZER;CN=Dara:mailto:d.loxwell@gridlink.me")!!
        assertEquals("mailto:d.loxwell@gridlink.me", line.value)
        assertEquals("Dara", line.param("CN"))
    }

    @Test
    fun `folded lines rejoin without the continuation space`() {
        val raw = "UID:040000008200E00074C5B7101A82E008000000\r\n 00D3ADF1CC0600DD01\r\nSUMMARY:Kirkwood\r\n"
        val lines = ContentLines.parseAll(raw)

        assertEquals(2, lines.size)
        assertEquals("04000000820" + "0E00074C5B7101A82E00800000000D3ADF1CC0600DD01", lines[0].value)
        assertEquals("Kirkwood", lines[1].value)
    }

    @Test
    fun `param does not split on a comma but paramValues does`() {
        val line = ContentLines.parse("TEL;TYPE=CELL,VOICE:+1 717-555-0142")!!

        // 🔴 The whole-text reading is what protects `CN=Loxwell, Dara` from becoming "Loxwell".
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
            listOf("Foodservice", "Redoak", "", "", ""),
            ContentLines.splitStructured("Foodservice;Redoak;;;"),
        )
        // An escaped semicolon is part of the surname, not a component break.
        assertEquals(listOf("""O\;Brien""", "Sean"), ContentLines.splitStructured("""O\;Brien;Sean"""))
    }

    @Test
    fun `unescapes the escapes a real exporter emits`() {
        assertEquals(
            "2140 Windmere Rd, Ste 200 Fairhaven PA 17033-2841",
            ContentLines.unescapeText("""2140 Windmere Rd\, Ste 200 Fairhaven PA 17033-2841"""),
        )
        assertEquals("\n", ContentLines.unescapeText("""\n"""))
    }

    @Test
    fun `a line with no colon is not a content line`() {
        assertNull(ContentLines.parse("BEGIN"))
        assertNull(ContentLines.parse("   "))
    }
}
