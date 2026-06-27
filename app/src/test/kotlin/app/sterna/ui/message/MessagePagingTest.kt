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
}
