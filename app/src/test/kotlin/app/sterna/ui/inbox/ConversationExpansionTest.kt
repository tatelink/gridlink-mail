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

    @Test fun `merge adds fetched members missing from the cache, newest-first`() {
        // Cache only had the recent received reply; the server fills in two older received ones.
        val cached = listOf(Email(id = "c2", receivedAt = "2026-06-20T10:00:00Z"))
        val fetched = listOf(
            Email(id = "rep", receivedAt = "2026-06-21T10:00:00Z"),
            Email(id = "c2", receivedAt = "2026-06-20T10:00:00Z"),
            Email(id = "old1", receivedAt = "2026-06-18T10:00:00Z"),
            Email(id = "old2", receivedAt = "2026-06-19T10:00:00Z"),
        )
        val merged = ConversationExpansion.mergeMembers(cached, fetched, representativeId = "rep")
        // Representative excluded; rest newest-first; no duplicate of c2.
        assertEquals(listOf("c2", "old2", "old1"), merged.map { it.id })
    }

    @Test fun `merge dedups by id and prefers the fetched copy`() {
        val cached = listOf(Email(id = "m1", subject = "stale"))
        val fetched = listOf(Email(id = "m1", subject = "fresh"))
        val merged = ConversationExpansion.mergeMembers(cached, fetched, representativeId = "rep")
        assertEquals(1, merged.size)
        assertEquals("fresh", merged[0].subject)
    }

    @Test fun `merge never lets the fetched copy null out cached identity fields`() {
        val cached = listOf(Email(id = "m1", accountId = "acc", mailboxId = "trash"))
        val fetched = listOf(Email(id = "m1", subject = "fresh"))
        val merged = ConversationExpansion.mergeMembers(cached, fetched, representativeId = "rep")
        assertEquals("acc", merged[0].accountId)
        assertEquals("trash", merged[0].mailboxId)
        assertEquals("fresh", merged[0].subject)
    }

    @Test fun `merge with no fetched members keeps the cached list (offline)`() {
        val cached = listOf(Email(id = "a", receivedAt = "2026-06-20T10:00:00Z"))
        val merged = ConversationExpansion.mergeMembers(cached, emptyList(), representativeId = "rep")
        assertEquals(listOf("a"), merged.map { it.id })
    }

    @Test fun `scope keeps in-view and Sent members, drops other folders' members`() {
        val fetched = listOf(
            Email(id = "in1", mailboxIds = mapOf("inbox" to true)),
            Email(id = "sent1", mailboxIds = mapOf("sent" to true)),
            Email(id = "tr1", mailboxIds = mapOf("trash" to true)),
            Email(id = "junk1", mailboxIds = mapOf("junk" to true)),
            Email(id = "draft1", mailboxIds = mapOf("drafts" to true)),
        )
        val scoped = ConversationExpansion.membersInScope(fetched, allowed = setOf("inbox", "sent"))
        assertEquals(listOf("in1", "sent1"), scoped.map { it.id })
    }

    @Test fun `scope keeps a multi-mailbox member with at least one allowed home`() {
        // Label-style servers can file one message in several mailboxes at once.
        val fetched = listOf(Email(id = "m1", mailboxIds = mapOf("archive" to true, "inbox" to true)))
        val scoped = ConversationExpansion.membersInScope(fetched, allowed = setOf("inbox", "sent"))
        assertEquals(listOf("m1"), scoped.map { it.id })
    }

    @Test fun `scope falls back to the local mailboxId when the server map is absent`() {
        val fetched = listOf(
            Email(id = "in1", mailboxId = "inbox"),
            Email(id = "tr1", mailboxId = "trash"),
            Email(id = "lost", mailboxId = null),
        )
        val scoped = ConversationExpansion.membersInScope(fetched, allowed = setOf("inbox", "sent"))
        assertEquals(listOf("in1"), scoped.map { it.id })
    }
}
