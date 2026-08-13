package app.gridlink.core.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tag definition model: its codec, its palette, and the fallbacks that keep it total. */
class MailTagTest {

    @Test fun `definitions round-trip in the order they were created`() {
        // Order is the reader's (new tags append), which is why this is a JSON list and not a
        // stringSet: a set would reshuffle the tag manager on every write.
        val tags = listOf(
            MailTag(keyword = "work", label = "Work", color = TagColor.BLUE.name),
            MailTag(keyword = "tax-2026", label = "Tax 2026", color = TagColor.AMBER.name),
        )
        assertEquals(tags, MailTagCodec.decode(MailTagCodec.encode(tags)))
    }

    @Test fun `a missing or corrupt store means no tags, never a crash`() {
        assertEquals(emptyList<MailTag>(), MailTagCodec.decode(null))
        assertEquals(emptyList<MailTag>(), MailTagCodec.decode(""))
        assertEquals(emptyList<MailTag>(), MailTagCodec.decode("{not json"))
    }

    @Test fun `a colour name this build does not know falls back instead of throwing`() {
        // A backup from a newer build naming a colour added since must still import.
        assertEquals(TagColor.BLUE, MailTag(keyword = "k", label = "K", color = "FUCHSIA").tagColor)
        assertEquals(TagColor.BLUE, TagColor.byName(null))
        // And the names we do ship resolve regardless of casing.
        assertEquals(TagColor.GREEN, TagColor.byName("green"))
    }

    @Test fun `an undefined tag gets a stable colour rather than a random one`() {
        // The same tag must be the same colour on every screen and after a restart, or the list
        // flickers as it re-composes.
        assertEquals(TagColor.forUnknown("receipts"), TagColor.forUnknown("receipts"))
    }

    @Test fun `the palette has no purple`() {
        // 🔴 House style, stated more than once. Asserted rather than trusted to review: this is
        // the kind of thing a well-meaning "let's add one more colour" quietly undoes.
        TagColor.entries.forEach { color ->
            val r = ((color.argb shr 16) and 0xFF).toInt()
            val g = ((color.argb shr 8) and 0xFF).toInt()
            val b = (color.argb and 0xFF).toInt()
            // Purple/magenta is the region where red and blue both dominate green.
            val purple = b > g + 24 && r > g + 24
            assertTrue("${color.name} reads as purple", !purple)
        }
    }

    @Test fun `every palette entry is opaque and distinct`() {
        TagColor.entries.forEach {
            assertEquals("${it.name} must be fully opaque", 0xFFL, (it.argb shr 24) and 0xFF)
        }
        assertEquals(TagColor.entries.size, TagColor.entries.map { it.argb }.toSet().size)
        assertNotEquals(0, TagColor.entries.size)
    }
}
