package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationExpansionTest {

    @Test fun `thread key is the threadId when present`() {
        assertEquals("T1", ConversationExpansion.threadKey(threadId = "T1", id = "m9"))
    }

    @Test fun `thread key falls back to the message id when thread-less`() {
        assertEquals("m9", ConversationExpansion.threadKey(threadId = null, id = "m9"))
    }

    @Test fun `toggle expands a collapsed thread`() {
        assertEquals(setOf("T1"), ConversationExpansion.toggle(emptySet(), "T1"))
    }

    @Test fun `toggle collapses an expanded thread without touching the rest`() {
        assertEquals(setOf("T2"), ConversationExpansion.toggle(setOf("T1", "T2"), "T1"))
    }

    @Test fun `members below excludes the representative already shown on the row`() {
        val all = listOf(Email(id = "m3"), Email(id = "m2"), Email(id = "m1"))
        val below = ConversationExpansion.membersBelow(all, representativeId = "m3")
        assertEquals(listOf("m2", "m1"), below.map { it.id })
    }

    @Test fun `members below keeps the cache order (newest-first)`() {
        val all = listOf(Email(id = "m3"), Email(id = "m2"), Email(id = "m1"))
        val below = ConversationExpansion.membersBelow(all, representativeId = "m1")
        assertEquals(listOf("m3", "m2"), below.map { it.id })
    }

    @Test fun `members below is empty for a single-message thread`() {
        val all = listOf(Email(id = "only"))
        assertEquals(emptyList<String>(), ConversationExpansion.membersBelow(all, "only").map { it.id })
    }
}
