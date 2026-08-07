package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The IMAP twin of [DestroyableIdsTest] (Codeberg #99): given the numbering a destroy was
 * confirmed under, what may be destroyed at all — and how the wave is routed.
 *
 * Why it is a decision of its own rather than a comment in `destroyAll`: an IMAP `UID STORE
 * +FLAGS (\Deleted)` + `UID EXPUNGE` names messages by number, and those numbers only mean
 * anything inside one UIDVALIDITY. A held-back destroy that opposes NOTHING re-reads the folder's
 * current numbering at execution, the SELECT matches it against itself, and a folder renumbered
 * during the hold-back is expunged by UIDs that now name OTHER, live messages — in the Trash, or
 * in the Inbox itself on an account with no Trash, where `deleteWouldDestroy` is true and the
 * folder carried is the Inbox.
 *
 * Everything below RUNS. The wiring test at the end is the only source-text part, and it is there
 * because `MailRepository` needs Room and a `Context`; it pins whole lines, never substrings.
 */
class ImapDestroyUnderNumberingTest {

    private fun id(mailbox: String, uid: Long) = ImapMailService.emailId(ACCOUNT, mailbox, uid)

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /** The code lines of [body] naming [needle] — comments dropped. Whole lines: the assertions
     *  compare them, never search inside them. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    // ---- the decision, executed --------------------------------------------------------------

    @Test fun `a destroy with no numbering to oppose destroys nothing`() {
        // The held-back destroy of a selection, enqueued by a version predating the key, or for a
        // folder whose numbering was never observed. Nothing can be shown to still mean anything.
        assertEquals(
            emptyList<String>(),
            UidValidity.destroyableUnderNumbering(listOf("a", "b"), expected = null),
        )
    }

    @Test fun `a numbering the server never announced destroys nothing`() {
        assertEquals(
            emptyList<String>(),
            UidValidity.destroyableUnderNumbering(listOf("a", "b"), expected = 0L),
        )
        assertEquals(
            emptyList<String>(),
            UidValidity.destroyableUnderNumbering(listOf("a", "b"), expected = -1L),
        )
    }

    /** The inverse witness, without which "always empty" would satisfy every case above — and
     *  refusing every destroy is a total regression, not a guard. */
    @Test fun `a destroy carrying its numbering keeps its whole list, in its own order`() {
        assertEquals(
            listOf("a", "b"),
            UidValidity.destroyableUnderNumbering(listOf("a", "b"), expected = 42L),
        )
        assertEquals(
            listOf("b", "a"),
            UidValidity.destroyableUnderNumbering(listOf("b", "a"), expected = 42L),
        )
    }

    // ---- the routing, executed ---------------------------------------------------------------

    @Test fun `with nothing to oppose, every id is refused and no folder is touched`() {
        val ids = listOf(id("INBOX", 1L), id("Trash", 2L))

        val plan = UidValidity.imapDestroyPlan(ids, expected = null)

        // Empty byFolder is what makes it safe: the destroy loop has nothing to iterate, so no
        // UID EXPUNGE is ever issued and nothing can land in `succeeded`.
        assertEquals(emptyMap<String, List<String>>(), plan.byFolder)
        assertEquals(ids, plan.refused)
    }

    @Test fun `an unverifiable numbering refuses the wave just as a missing one does`() {
        val ids = listOf(id("INBOX", 1L))

        assertEquals(emptyMap<String, List<String>>(), UidValidity.imapDestroyPlan(ids, 0L).byFolder)
        assertEquals(ids, UidValidity.imapDestroyPlan(ids, 0L).refused)
    }

    @Test fun `under its own numbering the wave is grouped by the folder its ids name`() {
        val ids = listOf(id("INBOX", 1L), id("Trash", 2L), id("INBOX", 3L))

        val plan = UidValidity.imapDestroyPlan(ids, expected = 42L)

        assertEquals(
            mapOf("INBOX" to listOf(ids[0], ids[2]), "Trash" to listOf(ids[1])),
            plan.byFolder,
        )
        assertEquals(emptyList<String>(), plan.refused)
    }

    @Test fun `an id naming no folder is refused, never destroyed`() {
        // A JMAP-shaped id in an IMAP account's wave: unparsable, so it names no UID either.
        val ids = listOf(id("INBOX", 1L), "Mabcdef")

        val plan = UidValidity.imapDestroyPlan(ids, expected = 42L)

        assertEquals(mapOf("INBOX" to listOf(ids[0])), plan.byFolder)
        assertEquals(listOf("Mabcdef"), plan.refused)
    }

    @Test fun `a folder path containing colons survives the grouping`() {
        // IMAP paths can hold ':' — the id parser keeps them, and so must the plan.
        val ids = listOf(id("a:b", 7L))

        assertEquals(mapOf("a:b" to ids), UidValidity.imapDestroyPlan(ids, expected = 42L).byFolder)
    }

    @Test fun `the plan is a partition - nothing destroyed twice, nothing invented, nothing dropped`() {
        val ids = listOf(id("INBOX", 1L), "Mabcdef", id("Trash", 2L))

        listOf(null, 0L, -1L, 1L, 42L, Long.MAX_VALUE).forEach { expected ->
            val plan = UidValidity.imapDestroyPlan(ids, expected)
            val destroyed = plan.byFolder.values.flatten()
            assertEquals("$expected: every id is accounted for, once", ids.sorted(), (destroyed + plan.refused).sorted())
            assertTrue(
                "$expected: an id may not be both destroyed and refused",
                destroyed.none { it in plan.refused },
            )
            assertTrue("$expected: the plan may not invent ids", destroyed.all { it in ids })
        }
    }

    // ---- where the decision is plugged in (source text: MailRepository needs Room) ------------

    @Test fun `the IMAP branch of destroyAll routes through the decision and fails what it refuses`() {
        val body = bodyOf("destroyAll")
        assertEquals(
            "the IMAP destroy must ask the decision what its numbering licenses — a predicate " +
                "written inline is a decision no test can execute",
            listOf("val plan = UidValidity.imapDestroyPlan(emailIds, expectedUidValidity)"),
            codeLinesNaming(body, "imapDestroyPlan("),
        )
        assertEquals(
            "what the decision refused must land in `failed` — never silently counted a success, " +
                "and `failed` is what makes the worker re-query so the survivors come back",
            listOf("failed += plan.refused"),
            codeLinesNaming(body, "plan.refused"),
        )
        assertEquals(
            "and only what it licensed may reach the expunge",
            listOf("plan.byFolder.forEach { (source, ids) ->"),
            codeLinesNaming(body, "plan.byFolder"),
        )
        assertEquals(
            "the frozen numbering must travel on to the SELECT, which is what actually refuses",
            listOf("imapDestroyGroup(credentials, source, ids, succeeded, failed, expectedUidValidity)"),
            codeLinesNaming(body, "imapDestroyGroup("),
        )
    }

    /**
     * One link further than the test above, and the link that actually destroys: the argument has
     * to reach `deleteBatch`. Dropping it COMPILES — `ImapMailService.deleteBatch` defaults
     * `expectedUidValidity` to null — and `onMailbox` then falls back on `expectedUidValidity ?:
     * recorded`, so the SELECT compares the folder's current number with itself, concludes SAME,
     * and expunges a renumbered folder without one failure. That is the original defect, entire.
     */
    @Test fun `the frozen numbering reaches the wire, not merely the branch that routes`() {
        assertEquals(
            "the expunge must SELECT under the numbering the caller froze — the argument is what " +
                "makes it refusable, and its absence is silent, legal Kotlin",
            listOf("imap.deleteBatch(credentials, source, uidToId.keys.toList(), expectedUidValidity)"),
            codeLinesNaming(bodyOf("imapDestroyGroup"), "imap.deleteBatch("),
        )
    }

    /**
     * The numbering ARRIVES on this path; it is never read on it. A local read — including one
     * that merely SHADOWS the parameter, which Kotlin accepts with a warning — hands the guard
     * the number a refresh recorded AFTER the renumbering, which is precisely the value that
     * makes it pass on the mail it exists to save. Same shape as the "no swallower" rule of
     * [DestroyChecksTheFolderWiringTest].
     */
    @Test fun `the destroy path never looks the numbering up for itself`() {
        listOf("destroyAll", "imapDestroyGroup").forEach { function ->
            assertEquals(
                "$function must take the frozen numbering as a parameter and read nothing: what it " +
                    "could read is the numbering of the renumbering it must refuse (#99)",
                emptyList<String>(),
                codeLinesNaming(bodyOf(function), "recordedUidValidity"),
            )
        }
    }

    /**
     * The inverse witness at system level, and the one the executable tests cannot give: they all
     * prove "handed nothing, destroy nothing". Nothing proves that a healthy account hands over a
     * REAL number — replace this accessor's body with `null` and every IMAP permanent delete
     * becomes a silent, permanent refusal, with the whole suite green.
     *
     * Read file-wide rather than per function: it is a single-expression body, which the brace
     * matching of [DaoQuerySource.mailFunctionBody] cannot delimit. The second line is the purge's
     * own read of the same value (#99), pinned here for the same reason.
     */
    @Test fun `the accessor really asks IMAP for the folder's numbering`() {
        assertEquals(
            "the numbering must come from the IMAP layer's record for THAT account and THAT folder, " +
                "on IMAP and only there — a body that answers null refuses every IMAP destroy for ever",
            listOf(
                "if (credentials.protocol == MailProtocol.IMAP) imap.recordedUidValidity(credentials.id, mailboxId) else null",
                "if (credentials.protocol == MailProtocol.IMAP) imap.recordedUidValidity(credentials.id, trashMailboxId)",
            ),
            codeLinesNaming(DaoQuerySource.mailSource("MailRepository"), "imap.recordedUidValidity("),
        )
    }

    private companion object {
        const val ACCOUNT = "acc1"
    }
}
