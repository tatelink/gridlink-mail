package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    // --- hasPrevious / hasNext: the position line's chevrons (Codeberg #13) ---

    @Test fun `both chevrons are live in the middle of a conversation`() {
        assertTrue(MessagePaging.hasPrevious(page = 1, pageCount = 3))
        assertTrue(MessagePaging.hasNext(page = 1, pageCount = 3))
    }

    @Test fun `the first message greys out the previous chevron only`() {
        assertFalse(MessagePaging.hasPrevious(page = 0, pageCount = 3))
        assertTrue(MessagePaging.hasNext(page = 0, pageCount = 3))
    }

    @Test fun `the last message greys out the next chevron only`() {
        assertTrue(MessagePaging.hasPrevious(page = 2, pageCount = 3))
        assertFalse(MessagePaging.hasNext(page = 2, pageCount = 3))
    }

    @Test fun `a two-message conversation is live at both ends in turn`() {
        assertFalse(MessagePaging.hasPrevious(page = 0, pageCount = 2))
        assertTrue(MessagePaging.hasNext(page = 0, pageCount = 2))
        assertTrue(MessagePaging.hasPrevious(page = 1, pageCount = 2))
        assertFalse(MessagePaging.hasNext(page = 1, pageCount = 2))
    }

    @Test fun `a lone message has nowhere to go in either direction`() {
        assertFalse(MessagePaging.hasPrevious(page = 0, pageCount = 1))
        assertFalse(MessagePaging.hasNext(page = 0, pageCount = 1))
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

    // --- entryKey: the reader's (account, id) identity (#92) ---
    //
    // JMAP ids are per account, so two accounts on one server can hold the same message id.
    // The reader's page key and its "already loaded?" guard must separate them.

    @Test fun `the same id under two accounts gets two different keys`() {
        assertNotEquals(
            MessagePaging.entryKey("M42", "acct-a"),
            MessagePaging.entryKey("M42", "acct-b"),
        )
    }

    @Test fun `the same id under the same account gets the same key`() {
        assertEquals(
            MessagePaging.entryKey("M42", "acct-a"),
            MessagePaging.entryKey("M42", "acct-a"),
        )
    }

    @Test fun `two ids under one account stay distinct`() {
        assertNotEquals(
            MessagePaging.entryKey("M42", "acct-a"),
            MessagePaging.entryKey("M43", "acct-a"),
        )
    }

    @Test fun `a missing account is a stable key, not a crash or a wildcard`() {
        assertEquals(MessagePaging.entryKey("M42", null), MessagePaging.entryKey("M42", null))
        assertNotEquals(MessagePaging.entryKey("M42", null), MessagePaging.entryKey("M43", null))
        // "no account" must not collide with a real account either.
        assertNotEquals(MessagePaging.entryKey("M42", null), MessagePaging.entryKey("M42", "acct-a"))
    }

    @Test fun `no pair of parts can be glued into another pair's key`() {
        // The separator is not a legal character in an id, so account+id can't be re-cut:
        // ("a", "b-c") and ("a-b", "c") must not land on the same key.
        assertNotEquals(
            MessagePaging.entryKey(accountId = "a", emailId = "b-c"),
            MessagePaging.entryKey(accountId = "a-b", emailId = "c"),
        )
    }

    @Test fun `for a single account the keys are as discriminating as the bare ids`() {
        // Granularity guarantee: within one account the key partitions the ids exactly as the
        // bare id did, so nothing about single-account paging changes.
        val ids = listOf("a", "b", "c", "d")
        val keys = ids.map { MessagePaging.entryKey(it, "solo") }
        assertEquals(ids.size, keys.toSet().size)
    }

    // --- needsLoad: the reading view's load guard (#92) ---

    @Test fun `nothing loaded yet always loads`() {
        assertTrue(MessagePaging.needsLoad(null, null, "M42", "acct-a"))
        assertTrue(MessagePaging.needsLoad(null, null, "M42", null))
    }

    @Test fun `the same message under the same account does not reload`() {
        assertFalse(MessagePaging.needsLoad("M42", "acct-a", "M42", "acct-a"))
        assertFalse(MessagePaging.needsLoad("M42", null, "M42", null))
    }

    @Test fun `the same id under another account reloads`() {
        // The reported defect: the guard saw the id alone, returned early, and left the
        // previously loaded account's message on screen.
        assertTrue(MessagePaging.needsLoad("M42", "acct-a", "M42", "acct-b"))
    }

    @Test fun `switching between no account and an account reloads`() {
        assertTrue(MessagePaging.needsLoad("M42", null, "M42", "acct-a"))
        assertTrue(MessagePaging.needsLoad("M42", "acct-a", "M42", null))
    }

    @Test fun `another id reloads whatever the account`() {
        assertTrue(MessagePaging.needsLoad("M42", "acct-a", "M43", "acct-a"))
        assertTrue(MessagePaging.needsLoad("M42", "acct-a", "M43", "acct-b"))
    }

    @Test fun `the guard agrees with the page key`() {
        // One rule, two uses: a reload is needed exactly when the entry key changes.
        fun agree(id1: String, acc1: String?, id2: String, acc2: String?) {
            val differentKey = MessagePaging.entryKey(id1, acc1) != MessagePaging.entryKey(id2, acc2)
            assertEquals(differentKey, MessagePaging.needsLoad(id1, acc1, id2, acc2))
        }
        agree("M42", "acct-a", "M42", "acct-a")
        agree("M42", "acct-a", "M42", "acct-b")
        agree("M42", "acct-a", "M43", "acct-a")
        agree("M42", null, "M42", null)
        agree("M42", null, "M42", "acct-a")
    }
}
