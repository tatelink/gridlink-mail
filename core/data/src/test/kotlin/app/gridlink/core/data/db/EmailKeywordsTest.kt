package app.gridlink.core.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The tag codec: what a typed label becomes on the wire, what the server's keyword set becomes in
 * the column, and that the column can be matched exactly by a `LIKE`.
 *
 * Pure data, so it runs as a plain JVM test — the same reason the swipe config and the outbox
 * helpers are testable without a device.
 */
class EmailKeywordsTest {

    // --- custom(): the ingest filter ------------------------------------------------------------

    @Test fun `system keywords never become tags`() {
        // 🔴 $seen and $flagged are the read state and the star; a removable "seen" tag would be a
        // second control fighting the first. IMAP's backslash flags go the same way.
        val kept = EmailKeywords.custom(
            listOf("\$seen", "\$flagged", "\$draft", "\$answered", "\\Seen", "\\Deleted", "work"),
        )
        assertEquals(listOf("work"), kept)
    }

    @Test fun `a keyword invented after this was written is still excluded`() {
        // Excluded by the $ rule rather than by a list, so a system keyword nobody has heard of
        // yet does not show up as a tag the user is invited to remove.
        assertEquals(emptyList<String>(), EmailKeywords.custom(listOf("\$somethingnew")))
    }

    @Test fun `case differences are one tag, not two`() {
        // Both protocols treat keywords case-insensitively, so a server that echoes "Work" after
        // we stored "work" must not double the chip.
        assertEquals(listOf("work"), EmailKeywords.custom(listOf("Work", "WORK", "work")))
    }

    @Test fun `output is sorted so two syncs of one message agree byte for byte`() {
        // Unsorted, the same set arriving in a different order would rewrite the column on every
        // refresh and churn every Flow watching the row.
        assertEquals(
            EmailKeywords.encode(listOf("urgent", "work")),
            EmailKeywords.encode(listOf("work", "urgent")),
        )
    }

    // --- toKeyword(): a typed label → the wire token ---------------------------------------------

    @Test fun `a label becomes a token both protocols will carry`() {
        assertEquals("tax-2026", EmailKeywords.toKeyword("Tax 2026"))
        assertEquals("follow-up", EmailKeywords.toKeyword("Follow-up"))
        assertEquals("work", EmailKeywords.toKeyword("  Work  "))
        assertEquals("a-b", EmailKeywords.toKeyword("a / b"))
    }

    @Test fun `punctuation at the edges is trimmed rather than left dangling`() {
        assertEquals("urgent", EmailKeywords.toKeyword("!!! urgent !!!"))
    }

    @Test fun `a label with nothing usable in it has no wire identity`() {
        // Null rather than a fabricated name: the tag manager tells the user to pick another
        // instead of silently creating a tag the server would spell differently.
        assertNull(EmailKeywords.toKeyword("!!!"))
        assertNull(EmailKeywords.toKeyword("   "))
    }

    @Test fun `an emoji label still yields a token`() {
        // The emoji lives on the label, on-device; the wire gets what is left.
        assertEquals("red", EmailKeywords.toKeyword("🔴 Red"))
    }

    @Test fun `a very long label is capped`() {
        val token = EmailKeywords.toKeyword("x".repeat(200))
        assertEquals(EmailKeywords.MAX_LENGTH, token?.length)
    }

    // --- encode/decode/likePattern ---------------------------------------------------------------

    @Test fun `no tags encodes to null and null decodes to no tags`() {
        assertNull(EmailKeywords.encode(emptyList()))
        assertNull(EmailKeywords.encode(listOf("\$seen")))
        assertEquals(emptyList<String>(), EmailKeywords.decode(null))
        assertEquals(emptyList<String>(), EmailKeywords.decode(""))
        assertEquals(emptyList<String>(), EmailKeywords.decode("   "))
    }

    @Test fun `the packed value is wrapped so a LIKE can match a whole name`() {
        val packed = EmailKeywords.encode(listOf("work", "urgent"))
        assertEquals(" urgent work ", packed)
        assertEquals(listOf("urgent", "work"), EmailKeywords.decode(packed))
    }

    @Test fun `the like pattern of one tag does not match a longer tag containing it`() {
        // The bug the wrapping exists to prevent, asserted directly rather than only through SQL.
        val homework = EmailKeywords.encode(listOf("homework"))!!
        val pattern = EmailKeywords.likePattern("work")
        assertEquals(false, sqlLike(homework, pattern))
        assertEquals(true, sqlLike(EmailKeywords.encode(listOf("work"))!!, pattern))
    }

    /** The `%`-only subset of SQL LIKE these patterns use, so the assertion needs no database. */
    private fun sqlLike(value: String, pattern: String): Boolean {
        val regex = pattern.split("%").joinToString(".*") { Regex.escape(it) }
        return Regex(regex).matches(value)
    }
}
