package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [selectionUndoEntries] — who may be put back, executed rather than re-derived.
 *
 * What it guards is irreversible. Undo is `restoreAll`, i.e. `client.move(..., sourceMailboxId)`,
 * which OVERWRITES the message's `mailboxIds` with `{source: true}`: an Undo built on a folder read
 * off a row that only ever existed on screen (frozen at crawl time, sometimes empty) files the
 * message into a folder it left long ago AND strips every other folder it belongs to, in silence.
 *
 * So: an untrusted row never yields an entry, an empty source never yields one, and one untrusted
 * row going through withholds the WHOLE batch's Undo — half an Undo lies about what it undoes.
 */
class SelectionUndoTest {

    private fun email(id: String, account: String? = "accA", mailbox: String? = "mbA") =
        Email(id = id, accountId = account, mailboxId = mailbox, subject = "s")

    private fun trusted(id: String, mailbox: String? = "mbA") =
        UndoCandidate(SelectionTarget(email(id, mailbox = mailbox), folderTrusted = true), "trash")

    private fun untrusted(id: String, mailbox: String? = "mbA") =
        UndoCandidate(SelectionTarget(email(id, mailbox = mailbox), folderTrusted = false), "trash")

    @Test
    fun `trusted rows are put back into the folder they came from`() {
        val out = selectionUndoEntries(listOf(trusted("m1"), trusted("m2", mailbox = "mbB")))
        assertEquals(
            listOf(
                UndoEntry("m1", "accA", "mbA", "trash"),
                UndoEntry("m2", "accA", "mbB", "trash"),
            ),
            out,
        )
    }

    @Test
    fun `a snooze carries no destination, and that is not a reason to drop the entry`() {
        val candidate = UndoCandidate(SelectionTarget(email("m1"), folderTrusted = true), null)
        assertEquals(listOf(UndoEntry("m1", "accA", "mbA", null)), selectionUndoEntries(listOf(candidate)))
    }

    @Test
    fun `an untrusted row never becomes an Undo entry`() {
        assertEquals(emptyList<UndoEntry>(), selectionUndoEntries(listOf(untrusted("m1"))))
    }

    /**
     * The rule that is easy to get wrong and expensive to get wrong: dropping only the untrusted
     * rows would leave an Undo that puts back one of the two messages the user just archived.
     *
     * Played in BOTH orders: a guard written as "stop at the first untrusted row" passes one order
     * by accident.
     */
    @Test
    fun `one untrusted row going through withholds the whole batch's Undo`() {
        assertEquals(
            "trusted first",
            emptyList<UndoEntry>(),
            selectionUndoEntries(listOf(trusted("m1"), untrusted("m2"))),
        )
        assertEquals(
            "untrusted first",
            emptyList<UndoEntry>(),
            selectionUndoEntries(listOf(untrusted("m2"), trusted("m1"))),
        )
    }

    /**
     * The same id twice, trusted and not — the two rows a merge can hand over for one message.
     * Both orders, because a decision that takes "the first one wins" gives the right answer by
     * accident in one of them.
     */
    @Test
    fun `the same id trusted and untrusted withholds the Undo in either order`() {
        assertEquals(
            "trusted copy first",
            emptyList<UndoEntry>(),
            selectionUndoEntries(listOf(trusted("m1"), untrusted("m1"))),
        )
        assertEquals(
            "untrusted copy first",
            emptyList<UndoEntry>(),
            selectionUndoEntries(listOf(untrusted("m1"), trusted("m1"))),
        )
    }

    /**
     * `email.mailboxId?.let { }` lets `""` through, and an Undo to an empty folder id is a move to
     * a folder that does not exist — the same write, just as irreversible. Its twin guard is on the
     * destruction side (TrashPurge.kt:146).
     */
    @Test
    fun `an empty or absent source folder yields no entry, and does not sink the others`() {
        val out = selectionUndoEntries(
            listOf(trusted("m1", mailbox = ""), trusted("m2", mailbox = null), trusted("m3")),
        )
        assertEquals(listOf(UndoEntry("m3", "accA", "mbA", "trash")), out)
    }

    @Test
    fun `nothing written means nothing to offer`() {
        assertEquals(emptyList<UndoEntry>(), selectionUndoEntries(emptyList()))
    }
}
