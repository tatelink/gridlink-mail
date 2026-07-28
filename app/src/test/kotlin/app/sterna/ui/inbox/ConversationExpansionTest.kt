package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ConversationExpansionTest {

    @Test fun `thread key is the account plus the threadId when present`() {
        assertEquals(ThreadKey("accA", "T1"), ConversationExpansion.threadKey("accA", threadId = "T1", id = "m9"))
    }

    @Test fun `thread key falls back to the message id when thread-less`() {
        assertEquals(ThreadKey("accA", "m9"), ConversationExpansion.threadKey("accA", threadId = null, id = "m9"))
    }

    @Test fun `two accounts sharing a thread id are two different keys`() {
        // The #92 family: servers number threads per account, so the unified inbox shows both.
        assertNotEquals(
            ConversationExpansion.threadKey("accA", "T1", "m1"),
            ConversationExpansion.threadKey("accB", "T1", "m2"),
        )
    }

    @Test fun `toggle expands a collapsed thread`() {
        val key = ThreadKey("accA", "T1")
        assertEquals(setOf(key), ConversationExpansion.toggle(emptySet(), key))
    }

    @Test fun `toggle collapses an expanded thread without touching the rest`() {
        val a = ThreadKey("accA", "T1")
        val b = ThreadKey("accA", "T2")
        assertEquals(setOf(b), ConversationExpansion.toggle(setOf(a, b), a))
    }

    @Test fun `unfolding one account's conversation leaves its homonym in the other account folded`() {
        // Same thread id, two accounts: expanding A's row must not draw B's row expanded.
        val a = ConversationExpansion.threadKey("accA", "T1", "m1")
        val b = ConversationExpansion.threadKey("accB", "T1", "m2")
        val expanded = ConversationExpansion.toggle(emptySet(), a)
        assertEquals(true, a in expanded)
        assertEquals(false, b in expanded)
    }

    @Test fun `the members of one account's conversation never answer for the other's`() {
        // The map that feeds both the unfolded rows and the reader's swipe context.
        val a = ThreadKey("accA", "T1")
        val b = ThreadKey("accB", "T1")
        val members = mapOf(a to listOf(Email(id = "a2", accountId = "accA")))
        assertEquals(emptyList<Email>(), members[b].orEmpty())
    }

    // --- the key travels through navigation without shedding its account ---

    @Test fun `a thread key survives a round trip through the route argument`() {
        val key = ThreadKey("acc-uuid", "T1")
        assertEquals(key, ThreadKey.decode(key.encode()))
    }

    @Test fun `a thread id containing the separator round-trips too`() {
        val key = ThreadKey("acc-uuid", "T|1|x")
        assertEquals(key, ThreadKey.decode(key.encode()))
    }

    @Test fun `a single-account key keeps its null account through the route`() {
        val key = ThreadKey(null, "T1")
        assertEquals(key, ThreadKey.decode(key.encode()))
    }

    @Test fun `a bare thread id left by an older route is refused, not guessed`() {
        // Rather than assume an account, the reader falls back to the single message it was given.
        assertEquals(null, ThreadKey.decode("T1"))
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

    @Test fun `merge dedups by id and keeps the copy already on screen`() {
        val cached = listOf(Email(id = "m1", subject = "on screen"))
        val fetched = listOf(Email(id = "m1", subject = "from the wire"))
        val merged = ConversationExpansion.mergeMembers(cached, fetched, representativeId = "rep")
        assertEquals(1, merged.size)
        assertEquals("on screen", merged[0].subject)
    }

    @Test fun `merge never lets the fetched copy null out cached identity fields`() {
        val cached = listOf(Email(id = "m1", accountId = "acc", mailboxId = "trash"))
        val fetched = listOf(Email(id = "m1", subject = "from the wire"))
        val merged = ConversationExpansion.mergeMembers(cached, fetched, representativeId = "rep")
        assertEquals("acc", merged[0].accountId)
        assertEquals("trash", merged[0].mailboxId)
    }

    // --- the unfolded rows must not be redrawn under the user (Codeberg #63) ---

    @Test fun `merge does not add recipients to a member already on screen`() {
        // The #63 blink: a cache row carries no recipients, so a self-authored member renders with
        // the sender fallback; the wire copy has them, and adopting it flipped the row's name line
        // to "To: …" a beat after the conversation unfolded.
        val cached = listOf(Email(id = "sent1", from = listOf(EmailAddress(email = "me@x.test"))))
        val fetched = listOf(
            Email(
                id = "sent1",
                from = listOf(EmailAddress(email = "me@x.test")),
                to = listOf(EmailAddress(email = "bob@x.test")),
            ),
        )
        val merged = ConversationExpansion.mergeMembers(cached, fetched, representativeId = "rep")
        assertEquals(emptyList<String>(), merged[0].to.map { it.email })
    }

    @Test fun `merge does not revert a star applied while the fetch was in flight`() {
        val cached = listOf(Email(id = "m1", keywords = mapOf("\$flagged" to true)))
        val fetched = listOf(Email(id = "m1", keywords = emptyMap()))
        val merged = ConversationExpansion.mergeMembers(cached, fetched, representativeId = "rep")
        assertEquals(true, merged[0].isFlagged)
    }

    @Test fun `a member the cache never had still arrives complete`() {
        // Append-only holds only for rows already drawn: a message outside the cache window is
        // new to the list, so it is shown exactly as the server sent it, recipients included.
        val fetched = listOf(Email(id = "old1", to = listOf(EmailAddress(email = "bob@x.test"))))
        val merged = ConversationExpansion.mergeMembers(emptyList(), fetched, representativeId = "rep")
        assertEquals(listOf("bob@x.test"), merged[0].to.map { it.email })
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

    // --- threadEntries: the reading view's swipe context (Codeberg #13) ---

    @Test fun `thread entries lead with the representative, then the members in list order`() {
        val members = listOf(Email(id = "m2"), Email(id = "m1"))
        val entries = ConversationExpansion.threadEntries("m3", "acc", members)
        assertEquals(listOf("m3", "m2", "m1"), entries.map { it.first })
    }

    @Test fun `thread entries carry each message's own account`() {
        // A unified-view conversation belongs to one account; the members' accountId is what
        // the reader's per-page ViewModel needs to load and act on the right mailbox.
        val members = listOf(Email(id = "m2", accountId = "acc"))
        val entries = ConversationExpansion.threadEntries("m3", "acc", members)
        assertEquals(listOf("acc", "acc"), entries.map { it.second })
    }

    @Test fun `a member with no account keeps a null account rather than borrowing one`() {
        val entries = ConversationExpansion.threadEntries("m3", "acc", listOf(Email(id = "m2")))
        assertEquals(listOf("acc" as String?, null), entries.map { it.second })
    }

    @Test fun `a single-message conversation is just the representative`() {
        val entries = ConversationExpansion.threadEntries("only", "acc", emptyList())
        assertEquals(listOf("only"), entries.map { it.first })
    }

    @Test fun `the representative is never listed twice`() {
        // Defensive: a merge racing a refresh could leave the rep among the members.
        val members = listOf(Email(id = "m3"), Email(id = "m2"))
        val entries = ConversationExpansion.threadEntries("m3", "acc", members)
        assertEquals(listOf("m3", "m2"), entries.map { it.first })
    }

    @Test fun `the entries feed straight into the pager's opening page`() {
        // The tapped message is the second member — page 2 of a 3-message conversation.
        val members = listOf(Email(id = "m2"), Email(id = "m1"))
        val entries = ConversationExpansion.threadEntries("m3", "acc", members)
        assertEquals(
            2,
            app.sterna.ui.message.MessagePaging.resolveInitialPage(
                entries.map { it.first },
                anchorId = "m1",
                fallbackIndex = 2,
            ),
        )
    }

    @Test fun `opening on the representative lands on the first page`() {
        val members = listOf(Email(id = "m2"), Email(id = "m1"))
        val entries = ConversationExpansion.threadEntries("m3", "acc", members)
        assertEquals(
            0,
            app.sterna.ui.message.MessagePaging.resolveInitialPage(
                entries.map { it.first },
                anchorId = "m3",
                fallbackIndex = 0,
            ),
        )
    }
}
