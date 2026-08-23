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

    @Test fun `adopting many keywords keeps the colours the reader was already looking at`() {
        // The dots on the message rows are forUnknown's derived colour. Adopting must not repaint
        // them for the sake of tidiness, or the tag the reader just claimed changes under them.
        val keywords = listOf("bills", "receipts")
        val adopted = adoptTags(keywords, existing = emptyList())
        assertEquals(keywords, adopted.map { it.keyword })
        adopted.forEach { assertEquals(TagColor.forUnknown(it.keyword), it.tagColor) }
    }

    @Test fun `adopting never hands back two tags in the same colour while the palette holds out`() {
        // forUnknown is a hash into eight entries, so a ten-tag vocabulary WILL collide. Identical
        // dots are the one outcome that makes the whole feature useless.
        val keywords = listOf(
            "bills", "receipts", "orders-shipping", "accounts-security",
            "health", "infra-alerts", "newsletters", "promotions",
        )
        val adopted = adoptTags(keywords, existing = emptyList())
        assertEquals(keywords.size, adopted.size)
        assertEquals(adopted.size, adopted.map { it.tagColor }.toSet().size)
    }

    @Test fun `adopting past the palette wraps instead of refusing`() {
        val keywords = (1..TagColor.entries.size + 3).map { "tag$it" }
        val adopted = adoptTags(keywords, existing = emptyList())
        assertEquals(keywords.size, adopted.size)
        // Every one is a real palette entry; only distinctness is given up, not the adoption.
        adopted.forEach { assertTrue(it.tagColor in TagColor.entries) }
    }

    @Test fun `adopting skips keywords that already have a definition`() {
        // Additive, always. A second definition for one wire name is the one thing that would make
        // the tag manager show a tag whose colour disagrees with the chip on the message.
        val existing = listOf(MailTag(keyword = "bills", label = "Bills", color = TagColor.RED.name))
        val adopted = adoptTags(listOf("bills", "receipts"), existing)
        assertEquals(listOf("receipts"), adopted.map { it.keyword })
        assertNotEquals(TagColor.RED, adopted.single().tagColor)
    }

    @Test fun `an adopted tag arrives with a readable label, not the raw slug`() {
        val adopted = adoptTags(listOf("orders-shipping"), existing = emptyList())
        assertEquals("Orders shipping", adopted.single().label)
        // 🔴 The KEYWORD is untouched. Adoption defines the tag that is on the mail; deriving a new
        // keyword from the new label would define a lookalike no message carries.
        assertEquals("orders-shipping", adopted.single().keyword)
    }
}
