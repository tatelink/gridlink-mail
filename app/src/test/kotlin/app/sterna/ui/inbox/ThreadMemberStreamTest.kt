package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A chip and its unfold must still agree A MINUTE AFTER the row was opened.
 *
 * [app.sterna.core.data.mail.ConversationScopeTest] pins that they agree when both are handed the
 * same folders, at one instant. Nothing pinned what happens next, and next is where the reader saw
 * the defect come back: the chip is a live query — Room re-runs it on every write to `emails` — and
 * the unfold was read once, when the row opened. So a message arriving in a conversation left open
 * (a notification, a refresh, a reply of one's own) moved the number and not the messages, and a
 * folder cache that finished syncing after the tap rebuilt the chip around a Sent folder the
 * unfolded list had never been given. Neither corrected itself while the folder stayed open.
 *
 * The fake here is the `emails` table: one mutable list, read through the SAME scope decision the
 * chip is bound with ([ListScope.folders]). The chip is that table counted over those folders, the
 * unfold is [ThreadMemberStream] reading it, and every case moves the table or the scope UNDER an
 * already-unfolded row and asks whether the two still say the same thing.
 *
 * The witness in the first case is the shape this replaces: the same reading taken once and kept.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadMemberStreamTest {

    /** The `emails` table, and the two queries that read it — one per side of the row. */
    private class Cache {
        val rows = MutableStateFlow<List<Email>>(emptyList())

        /** Reads that were opened — a subscription per (thread, scope), as Room would give. */
        var opened = 0

        /** `EmailDao.cachedThreadEmails`: one account, these folders, this thread, newest first. */
        fun read(accountId: String, folders: List<String>, threadKey: String): Flow<List<Email>> {
            opened++
            return rows.map { all -> members(all, accountId, folders, threadKey) }
        }

        /**
         * The chip: the same messages, counted rather than listed — the shipped SQL's `c`
         * sub-query for one thread (`conversationSql`, exercised for real in core:data).
         */
        fun chip(scope: ListScope, key: ThreadKey): Int =
            members(rows.value, key.accountId.orEmpty(), scope.folders(key.accountId), key.threadId).size

        private fun members(all: List<Email>, accountId: String, folders: List<String>, threadKey: String) =
            all.filter { it.accountId == accountId && it.mailboxId in folders && (it.threadId ?: it.id) == threadKey }
                .sortedByDescending { it.receivedAt }

        fun put(email: Email) {
            rows.value = rows.value.filterNot { it.id == email.id } + email
        }

        fun moveTo(id: String, mailboxId: String?) {
            rows.value = rows.value.mapNotNull { row ->
                when {
                    row.id != id -> row
                    mailboxId == null -> null
                    else -> row.copy(mailboxId = mailboxId)
                }
            }
        }
    }

    private fun mail(id: String, mailbox: String, receivedAt: String, account: String = "accA") =
        Email(id = id, accountId = account, mailboxId = mailbox, threadId = "T1", receivedAt = receivedAt)

    private val key = ThreadKey("accA", "T1")

    /** A thread the user has answered: two in the Inbox, the reply filed in Sent. */
    private fun Cache.anAnsweredThread() {
        put(mail("in1", "inbox", "2026-07-01T09:00:00Z"))
        put(mail("in2", "inbox", "2026-07-01T10:00:00Z")) // the representative the row draws
        put(mail("se1", "sentbox", "2026-07-01T11:00:00Z"))
    }

    /** The unfold, live: what [InboxViewModel] subscribes the expanded rows to. */
    private fun stream(
        cache: Cache,
        expanded: Flow<Set<ThreadKey>>,
        scope: Flow<ListScope>,
        representativeId: String = "in2",
    ) = ThreadMemberStream.members(
        expanded = expanded,
        scope = scope,
        fallbackAccountId = { "accA" },
        representative = { representativeId },
        read = cache::read,
    )

    /** The row on screen: the representative it draws, plus the members listed beneath it. */
    private fun unfoldedSize(members: Map<ThreadKey, List<Email>>): Int = members[key].orEmpty().size + 1

    /**
     * What the screen currently holds, kept up to date by a collection running beside the test —
     * the [InboxViewModel] collector, in other words. Unconfined so an emission lands the moment
     * the table or the scope is written, which is the timing every case here is about.
     */
    private fun TestScope.draw(flow: () -> Flow<Map<ThreadKey, List<Email>>>): MutableStateFlow<Map<ThreadKey, List<Email>>> {
        val out = MutableStateFlow<Map<ThreadKey, List<Email>>>(emptyMap())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow().collect { out.value = it } }
        return out
    }

    // -- the case this fix exists for ------------------------------------------------------

    @Test fun `a Sent folder resolved after the row was opened is listed under it`() = runTest {
        // A fresh install: the folder cache has not synced yet, so the chip and the unfold both
        // start on the smaller, honest conversation — two messages. Then the first folder sync
        // lands and the resolution grows the chip to three.
        val cache = Cache().apply { anAnsweredThread() }
        val expanded = MutableStateFlow(setOf(key))
        val scope = MutableStateFlow(ListScope(viewedMailboxIds = listOf("inbox")))
        val drawn = draw { stream(cache, expanded, scope) }
        advanceUntilIdle()

        assertEquals(2, cache.chip(scope.value, key))
        assertEquals(2, unfoldedSize(drawn.value))

        // The row is still open when the Sent folder finally resolves.
        val onceSynced = ListScope(listOf("inbox"), listOf("accA" to "sentbox"))
        // The witness: the reading the old code kept — taken when the row opened, and kept.
        val snapshotAtUnfold = drawn.value[key].orEmpty()

        scope.value = onceSynced
        advanceUntilIdle()

        assertEquals(3, cache.chip(onceSynced, key))
        assertEquals(3, unfoldedSize(drawn.value))
        assertEquals(listOf("se1", "in1"), drawn.value[key].orEmpty().map { it.id })

        // …and what that snapshot would still be showing: a chip of 3 over an unfold of 2, which is
        // the defect, with nothing on screen to explain the difference.
        assertEquals(2, snapshotAtUnfold.size + 1)
        assertNotEquals(cache.chip(onceSynced, key), snapshotAtUnfold.size + 1)
    }

    @Test fun `a message arriving in a conversation already unfolded joins it`() = runTest {
        // The frequent one: nothing is tapped, the reader is simply looking at the list when mail
        // lands in a thread they had left open.
        val cache = Cache().apply { anAnsweredThread() }
        val scope = MutableStateFlow(ListScope(listOf("inbox"), listOf("accA" to "sentbox")))
        val drawn = draw { stream(cache, MutableStateFlow(setOf(key)), scope) }
        advanceUntilIdle()
        assertEquals(3, unfoldedSize(drawn.value))

        cache.put(mail("in3", "inbox", "2026-07-01T12:00:00Z"))
        advanceUntilIdle()

        assertEquals(4, cache.chip(scope.value, key))
        assertEquals(4, unfoldedSize(drawn.value))
        assertEquals(listOf("in3", "se1", "in1"), drawn.value[key].orEmpty().map { it.id })
    }

    @Test fun `a member that leaves the viewed folders leaves the unfolded list`() = runTest {
        // Deleted from another client, or by this one: the conversation is folder-scoped, so the
        // member belongs to Trash's conversation now — and the chip has already stopped counting it.
        val cache = Cache().apply { anAnsweredThread() }
        val scope = MutableStateFlow(ListScope(listOf("inbox"), listOf("accA" to "sentbox")))
        val drawn = draw { stream(cache, MutableStateFlow(setOf(key)), scope) }
        advanceUntilIdle()

        cache.moveTo("in1", "trash")
        advanceUntilIdle()

        assertEquals(2, cache.chip(scope.value, key))
        assertEquals(2, unfoldedSize(drawn.value))
        assertEquals(listOf("se1"), drawn.value[key].orEmpty().map { it.id })
    }

    @Test fun `folding and unfolding again reads the conversation, it does not replay it`() = runTest {
        // The old members map was only ever emptied on a folder or account change, so a row folded
        // and opened again showed whatever was loaded the first time — however old.
        val cache = Cache().apply { anAnsweredThread() }
        val expanded = MutableStateFlow(setOf(key))
        val scope = MutableStateFlow(ListScope(listOf("inbox"), listOf("accA" to "sentbox")))
        val drawn = draw { stream(cache, expanded, scope) }
        advanceUntilIdle()
        assertEquals(3, unfoldedSize(drawn.value))

        expanded.value = emptySet()
        advanceUntilIdle()
        assertEquals(emptyMap<ThreadKey, List<Email>>(), drawn.value)

        cache.put(mail("in3", "inbox", "2026-07-01T12:00:00Z"))
        expanded.value = setOf(key)
        advanceUntilIdle()

        assertEquals(4, cache.chip(scope.value, key))
        assertEquals(4, unfoldedSize(drawn.value))
    }

    @Test fun `a conversation of another account keeps its own Sent folder in the unified list`() = runTest {
        // #92, on the live path: two accounts, one thread id, one Sent folder each. Each row must
        // be read with ITS account's folders, whichever account is current.
        val cache = Cache()
        cache.put(mail("a1", "inbox", "2026-07-01T09:00:00Z"))
        cache.put(mail("aSent", "sentA", "2026-07-01T10:00:00Z"))
        cache.put(mail("b1", "inbox", "2026-07-01T09:30:00Z", account = "accB"))
        cache.put(mail("bSent", "sentB", "2026-07-01T10:30:00Z", account = "accB"))
        val keyB = ThreadKey("accB", "T1")
        val scope = MutableStateFlow(ListScope(listOf("inbox"), listOf("accA" to "sentA", "accB" to "sentB")))
        val drawn = draw {
            ThreadMemberStream.members(
                expanded = MutableStateFlow(setOf(key, keyB)),
                scope = scope,
                fallbackAccountId = { "accA" },
                representative = { k -> if (k == key) "a1" else "b1" },
                read = cache::read,
            )
        }
        advanceUntilIdle()

        assertEquals(listOf("aSent"), drawn.value[key].orEmpty().map { it.id })
        assertEquals(listOf("bSent"), drawn.value[keyB].orEmpty().map { it.id })

        // The witness: scoped to A's Sent folder alone, B's row would list nothing — so the two
        // rows are genuinely being read with different folders and not with one pooled set.
        val onlyA = ListScope(listOf("inbox"), listOf("accA" to "sentA"))
        assertEquals(1, cache.chip(onlyA, keyB))
    }

    @Test fun `nothing unfolded reads nothing`() = runTest {
        val cache = Cache().apply { anAnsweredThread() }
        val drawn = draw { stream(cache, MutableStateFlow(emptySet()), MutableStateFlow(ListScope(listOf("inbox")))) }
        advanceUntilIdle()

        assertEquals(emptyMap<ThreadKey, List<Email>>(), drawn.value)
        assertEquals(0, cache.opened)
    }

    @Test fun `the representative is never listed twice under its own row`() = runTest {
        val cache = Cache().apply { anAnsweredThread() }
        val drawn = draw {
            stream(cache, MutableStateFlow(setOf(key)), MutableStateFlow(ListScope(listOf("inbox"), listOf("accA" to "sentbox"))))
        }
        advanceUntilIdle()

        assertEquals(listOf("se1", "in1"), drawn.value[key].orEmpty().map { it.id })
    }

    @Test fun `a live reading answers the same folders the chip counted, in the same order`() = runTest {
        // Stated as the invariant, over a sequence of writes: whatever happens to the table or the
        // scope, the number on the row and the messages under it are one answer.
        val cache = Cache().apply { anAnsweredThread() }
        val scope = MutableStateFlow(ListScope(listOf("inbox")))
        val drawn = draw { stream(cache, MutableStateFlow(setOf(key)), scope) }

        val steps: List<() -> Unit> = listOf(
            { scope.value = ListScope(listOf("inbox"), listOf("accA" to "sentbox")) },
            { cache.put(mail("in3", "inbox", "2026-07-01T12:00:00Z")) },
            { cache.moveTo("in1", "trash") },
            { cache.moveTo("se1", null) },
            { scope.value = ListScope(listOf("inbox")) },
        )
        steps.forEach { step ->
            step()
            advanceUntilIdle()
            assertEquals(cache.chip(scope.value, key), unfoldedSize(drawn.value))
        }

        // The witness: the sequence genuinely moved the number around, so the equality above is not
        // one constant compared with itself.
        assertEquals(2, cache.chip(scope.value, key))
    }

    // -- what a live reading may and may not redraw -----------------------------------------

    @Test fun `a reading keeps the copies already on screen`() {
        // Codeberg #63: the row under the reader's eyes is not rewritten. A cache row carries no
        // recipients until the memo is warm, and adopting a richer copy a beat later flipped a
        // self-authored member's name line from the sender to "To: …".
        val drawn = listOf(Email(id = "m1", accountId = "acc", subject = "on screen"))
        val live = listOf(Email(id = "m1", accountId = "acc", subject = "from the cache"))
        val out = ThreadMemberStream.reconcile(drawn, live, removed = emptySet())
        assertEquals("on screen", out.single().subject)
        assertSame(drawn.single(), out.single())
    }

    @Test fun `a reading does not revert a star applied while the write was in flight`() {
        // The optimistic toggles reach the screen before the server has acknowledged anything, and
        // the cache only after.
        val drawn = listOf(Email(id = "m1", accountId = "acc", keywords = mapOf("\$flagged" to true)))
        val live = listOf(Email(id = "m1", accountId = "acc", keywords = emptyMap()))
        assertEquals(true, ThreadMemberStream.reconcile(drawn, live, removed = emptySet()).single().isFlagged)
    }

    @Test fun `a member the screen never had arrives as the cache has it`() {
        val live = listOf(Email(id = "m2", accountId = "acc", subject = "new"))
        assertEquals("new", ThreadMemberStream.reconcile(emptyList(), live, removed = emptySet()).single().subject)
    }

    @Test fun `a member a swipe just took away is not put back by the next reading`() {
        // Its cache row outlives the gesture until the server acknowledges the move.
        val swiped = Email(id = "m1", accountId = "acc")
        val out = ThreadMemberStream.reconcile(
            drawn = emptyList(),
            live = listOf(swiped, Email(id = "m2", accountId = "acc")),
            removed = setOf(EmailKey("acc", "m1")),
        )
        assertEquals(listOf("m2"), out.map { it.id })
    }

    @Test fun `a tombstone is account-qualified`() {
        // Two accounts of one server share message ids as they share thread ids (#92): swiping one
        // must not blank the other's homonym.
        val out = ThreadMemberStream.reconcile(
            drawn = emptyList(),
            live = listOf(Email(id = "m1", accountId = "accA"), Email(id = "m1", accountId = "accB")),
            removed = setOf(EmailKey("accA", "m1")),
        )
        assertEquals(listOf("accB"), out.map { it.accountId })
    }

    @Test fun `an unchanged conversation reconciles to an equal list, so nothing redraws`() {
        // A StateFlow does not re-emit an equal value: this is what keeps an unfolded row still
        // while mail is arriving elsewhere in the folder.
        val drawn = listOf(Email(id = "m1", accountId = "acc"), Email(id = "m2", accountId = "acc"))
        assertEquals(drawn, ThreadMemberStream.reconcile(drawn, drawn.map { it.copy() }, removed = emptySet()))
    }

    // -- the scope decision the two sides share ---------------------------------------------

    @Test fun `the unfold's folders are the viewed ones plus that account's Sent`() = runTest {
        val scope = ListScope(listOf("inbox"), listOf("accA" to "sentbox", "accB" to "otherSent"))
        assertEquals(listOf("inbox", "sentbox"), scope.folders("accA"))
        assertEquals(listOf("inbox"), scope.folders("accC"))
        assertEquals(emptyList<String>(), ListScope().folders("accA"))

        // And a scope that resolves nothing still lists the folder on screen — the smaller, honest
        // conversation both sides then show.
        val cache = Cache().apply { anAnsweredThread() }
        assertEquals(listOf("in2", "in1"), cache.read("accA", ListScope(listOf("inbox")).folders("accA"), "T1").first().map { it.id })
    }
}
