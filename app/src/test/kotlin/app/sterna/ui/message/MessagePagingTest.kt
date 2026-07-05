package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Test

class MessagePagingTest {
    private val ids = listOf<String?>("a", "b", "c", "d")

    @Test fun `anchor found wins over fallback`() {
        assertEquals(2, MessagePaging.resolveInitialPage(ids, anchorId = "c", fallbackIndex = 0))
    }

    @Test fun `anchor missing falls back to index`() {
        assertEquals(1, MessagePaging.resolveInitialPage(ids, anchorId = "z", fallbackIndex = 1))
    }

    @Test fun `fallback beyond the end is clamped to the last entry`() {
        assertEquals(3, MessagePaging.resolveInitialPage(ids, anchorId = "z", fallbackIndex = 99))
    }

    @Test fun `negative fallback is clamped to the first entry`() {
        assertEquals(0, MessagePaging.resolveInitialPage(ids, anchorId = "z", fallbackIndex = -5))
    }

    @Test fun `empty list resolves to zero`() {
        assertEquals(0, MessagePaging.resolveInitialPage(emptyList(), anchorId = "a", fallbackIndex = 7))
    }

    @Test fun `null gaps in the loaded window are skipped when matching`() {
        val withGaps = listOf<String?>(null, "b", null, "d")
        assertEquals(3, MessagePaging.resolveInitialPage(withGaps, anchorId = "d", fallbackIndex = 0))
    }

    // --- mergeEntries: the reading session's sticky entry list ---

    private fun merge(stable: List<String>, live: List<String>) =
        MessagePaging.mergeEntries(stable, live) { it }

    @Test fun `empty stable adopts the live list`() {
        assertEquals(listOf("a", "b"), merge(emptyList(), listOf("a", "b")))
    }

    @Test fun `a removed entry keeps its slot`() {
        assertEquals(listOf("a", "b", "c"), merge(listOf("a", "b", "c"), listOf("b", "c")))
    }

    @Test fun `unread-filter cascade - successive removals never shrink the session list`() {
        // Unread-only list [a,b,c]; a is read and drops out, then b: the pager's entries
        // must stay [a,b,c] throughout so the settled page never re-binds.
        var entries = merge(listOf("a", "b", "c"), listOf("b", "c"))
        entries = merge(entries, listOf("c"))
        assertEquals(listOf("a", "b", "c"), entries)
    }

    @Test fun `older mail paged in appends at the end`() {
        assertEquals(listOf("a", "b", "c", "d"), merge(listOf("a", "b"), listOf("a", "b", "c", "d")))
    }

    @Test fun `new mail prepends at the top`() {
        assertEquals(listOf("x", "a", "b"), merge(listOf("a", "b"), listOf("x", "a", "b")))
    }

    @Test fun `an insertion lands between its live neighbours`() {
        assertEquals(listOf("a", "b", "c"), merge(listOf("a", "c"), listOf("a", "b", "c")))
    }

    @Test fun `appends still merge after a removal`() {
        assertEquals(listOf("a", "b", "c"), merge(listOf("a", "b"), listOf("b", "c")))
    }

    @Test fun `empty live leaves the session list untouched`() {
        assertEquals(listOf("a", "b"), merge(listOf("a", "b"), emptyList()))
    }

    @Test fun `identical lists come back equal`() {
        assertEquals(listOf("a", "b"), merge(listOf("a", "b"), listOf("a", "b")))
    }
}
