package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument as [app.sterna.ui.NavHostSourceRulesTest],
 * and the same disclaimer: it reads source files as text, proves nothing about what happens on
 * screen, and would still pass if the code it points at were broken inside.
 *
 * It exists because the guarantee it guards is a WIRING guarantee, and nothing else can hold it.
 * `ConversationScope` decides which folders a conversation covers, and the whole fix is that the
 * chip and the unfold are handed ONE value — the scope the list on screen was built with — and read
 * it the SAME way, live. `ConversationScopeTest` pins the decision but resolves the folders itself;
 * `ThreadMemberStreamTest` pins the live reading but is handed its scope by the test. Both stay
 * green while either call site quietly stops using the recorded value. `InboxViewModel` is not
 * instantiable in a JVM test — no Robolectric, no instrumented tests here — so reading the source
 * is what is left.
 *
 * Verified against the mutations it exists for, one rule failing per mutation: scoping the unfold
 * with `emptyList()`, re-reading the selection at unfold time, giving the chip's pagers
 * `emptyList()` instead of the resolution their own flow produced, resolving the Sent folder a
 * second time, reading the members once instead of observing them, feeding the stream an empty
 * snooze set instead of the observed table, unwrapping one caller of the mask, and dropping the
 * recording of the conversation's order at the tap.
 *
 * What it does NOT do: it does not check that the value recorded is the value the pager used, only
 * that it is recorded where the pager is built and read where the unfold is scoped.
 */
class ConversationScopeWiringTest {

    // -- the unfold's side: one decision, on the recorded scope, read live -------------------

    @Test fun `the unfold's folders come from the recorded scope, through the shared decision`() {
        val body = body(CONVERSATION_EXPANSION, "folders")
        assertTrue(
            "ListScope.folders must derive the unfolded conversation's folders from its OWN recorded " +
                "fields through $SHARED_DECISION — the decision the chip's query is bound with. " +
                "Body was:\n$body",
            SHARED_DECISION in body && "viewedMailboxIds" in body && "sentMailboxes" in body,
        )
        assertTrue(
            "ListScope.folders must not hard-code an empty resolution: an unfold scoped to fewer " +
                "folders than the chip counted is exactly the defect. Body was:\n$body",
            "emptyList(" !in body,
        )
    }

    @Test fun `the unfold reads the scope it was given, and reads it live`() {
        val body = body(CONVERSATION_EXPANSION, "one")
        assertTrue(
            "the member stream must scope each thread through the ListScope it is handed " +
                "(scope.folders(...)), not resolve anything for itself. Body was:\n$body",
            "scope.folders(" in body,
        )
        assertTrue(
            "the member stream must OBSERVE the cache (read(...) returns a Flow): a reading taken " +
                "once cannot follow a chip that is recomputed on every write. Body was:\n$body",
            "read(" in body,
        )
    }

    @Test fun `the ViewModel feeds the stream the recorded scope and an observed query`() {
        val body = body(INBOX_VIEW_MODEL, "observeThreadMembers")
        assertTrue(
            "observeThreadMembers must hand $RECORDED — the scope the list on screen was built " +
                "with — to the member stream. Body was:\n$body",
            "scope = $RECORDED" in body,
        )
        assertTrue(
            "the members must come from the OBSERVED repository query: a one-shot read is the " +
                "snapshot this replaces. Body was:\n$body",
            "repo.observeThreadEmails(" in body,
        )
        assertTrue(
            "observeThreadMembers must hand the stream the OBSERVED snooze table ($SNOOZE_SOURCE)): " +
                "the members are read from `emails` alone, so nothing in that query notices a " +
                "snooze starting or lapsing. Handed an empty set instead, every rule here and the " +
                "whole suite stay green while a snoozed child is listed under a row whose chip " +
                "excludes it — and a child snoozed from the selection bar comes back under the " +
                "reader's thumb the moment the mask goes down. Body was:\n$body",
            "snoozed = $SNOOZE_SOURCE" in body,
        )
        assertTrue(
            "the unfold must not re-read the selection: the folders it shows are the ones the list " +
                "was BUILT with, and the two differ for as long as a new pager is loading. " +
                "Body was:\n$body",
            RE_READ !in body,
        )
    }

    // -- the recording ----------------------------------------------------------------------

    @Test fun `the scope is recorded exactly where the list is built, and nowhere else`() {
        val assignments = codeLines(INBOX_VIEW_MODEL).count { ASSIGNMENT.containsMatchIn(it) }
        assertEquals(
            "$RECORDED must have exactly ONE writer — the flow that feeds the pager. A second " +
                "writer is a second resolution wearing the same name.",
            1, assignments,
        )
        val scopes = body(INBOX_VIEW_MODEL, "sentScopes")
        assertTrue(
            "the writer must be sentScopes, the very flow whose value the chip counts over. " +
                "Body was:\n$scopes",
            ASSIGNMENT.containsMatchIn(scopes) && SENT_RESOLUTION in scopes,
        )
        assertEquals(
            "$SENT_RESOLUTION must be observed from exactly one place in the app module: one " +
                "resolution, read once, handed to both sides.",
            1,
            File(root, APP_SOURCES).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .sumOf { f -> codeLines(f).count { SENT_RESOLUTION in it } },
        )
    }

    @Test fun `the recorded folders are the paging key's, not a fresh read of the selection`() {
        val scopes = body(INBOX_VIEW_MODEL, "sentScopes")
        assertTrue(
            "sentScopes must record the folders the PAGING KEY pages, so the unfold and the chip " +
                "are scoped to the same list. Body was:\n$scopes",
            "viewedMailboxIds(key)" in scopes && RE_READ !in scopes,
        )
        val viewed = body(INBOX_VIEW_MODEL, "viewedMailboxIds")
        assertTrue(
            "viewedMailboxIds must read the key it is given: taking the live selection instead is " +
                "the same second lookup, on the other argument. Body was:\n$viewed",
            "key.sel" in viewed && "selection.value" !in viewed,
        )
    }

    // -- the representative: read where it is DRAWN ------------------------------------------

    @Test fun `the members listed under a row exclude the representative that row draws`() {
        val reads = codeLines(INBOX_SCREEN).filter { "threadMembers[" in it }
        assertEquals(
            "InboxScreen must read the unfolded members ONCE, into a value the children and their " +
                "positions both come from: two reads are two answers. Read at:\n" +
                reads.joinToString("\n"),
            1, reads.size,
        )
        assertTrue(
            "that read must subtract the representative THE ROW ITSELF DRAWS (email.id) through " +
                "ConversationExpansion.membersBelow. The representative is a live value — the " +
                "newest message of the thread in the viewed folder(s), recomputed on every write — " +
                "so subtracting a copy of it taken when the row was unfolded draws the new " +
                "representative twice and drops the old one, under a chip that still counts right. " +
                "Read was:\n${reads.singleOrNull()}",
            MEMBERS_BELOW.containsMatchIn(reads.singleOrNull().orEmpty()),
        )
    }

    @Test fun `nothing records the representative the unfolded list is subtracted with`() {
        val stream = body(CONVERSATION_EXPANSION, "members") + "\n" + body(CONVERSATION_EXPANSION, "one")
        assertTrue(
            "ThreadMemberStream must be given no representative: taking one is the snapshot this " +
                "whole path exists to remove, moved one notch down — the members became live and " +
                "the id subtracted from them stayed frozen at the tap. The subtraction belongs " +
                "where the representative is drawn. Body was:\n$stream",
            "representative" !in stream,
        )
        assertTrue(
            "InboxViewModel must keep no map of recorded representatives: it is a fourth source " +
                "for a value the row already holds, and a source more always ends up diverging. " +
                "Found:\n" + codeLines(INBOX_VIEW_MODEL).filter { "threadReps" in it }.joinToString("\n"),
            codeLines(INBOX_VIEW_MODEL).none { "threadReps" in it },
        )
    }

    // -- the mask over the unfolded rows -----------------------------------------------------

    @Test fun `a member hidden from the unfolded rows is hidden for the length of one call`() {
        val byHand = codeLines(INBOX_VIEW_MODEL).filter { BY_HAND.containsMatchIn(it) }
        assertEquals(
            "no path may raise or lift the unfolded rows' mask by hand. The mask hides a member " +
                "whose removal is still in flight from a LIVE reading; making its lifting a " +
                "caller's duty is how the selection bar's snooze lost a message for good — it " +
                "raised the mask, never lifted it, and the message came back into the chip's " +
                "count at its due date and never back under the row. Every hiding is scoped to " +
                "the call that justifies it ($SCOPED_HIDE). By hand at:\n" + byHand.joinToString("\n"),
            0, byHand.size,
        )
        val helper = body(INBOX_VIEW_MODEL, SCOPED_HIDE)
        assertTrue(
            "$SCOPED_HIDE must go through ThreadMemberMask, which owns the mask and is the only " +
                "thing that raises or lowers it. Body was:\n$helper",
            ".hiding(" in helper,
        )
        val hiding = body(CONVERSATION_EXPANSION, "hiding")
        assertTrue(
            "ThreadMemberMask.hiding must lower the mask in a finally: lowering it on the success " +
                "path only is a duty again, and a failed — or a merely local — op would leave the " +
                "grave standing. Body was:\n$hiding",
            "finally" in hiding,
        )
    }

    @Test fun `every path that takes a member off the unfolded rows wraps its call in the mask`() {
        // The rule above checks the wrapper EXISTS and goes through the mask; it says nothing about
        // it being used. Unwrap a single caller — leave the op bare — and everything stays green
        // while that gesture's child lands back under the reader's thumb for the length of the
        // network call, which is the one thing the mask is for. So: the callers, by name, and how
        // many there are. A new path that removes a member has to come here and say so.
        val missing = MASKED_PATHS.filterNot { "$SCOPED_HIDE(" in body(INBOX_VIEW_MODEL, it) }
        assertEquals(
            "these InboxViewModel paths remove a member from the unfolded rows without wrapping " +
                "the call in $SCOPED_HIDE: the cache row outlives the gesture, and the live reading " +
                "puts the child straight back under the thumb",
            emptyList<String>(), missing,
        )
        val calls = codeLines(INBOX_VIEW_MODEL).filter { isCall(it) }
        assertEquals(
            "$SCOPED_HIDE has ${MASKED_PATHS.size} call sites (${MASKED_PATHS.joinToString()}) and " +
                "this counts them, because the rule above cannot tell a wrapper that is called from " +
                "one that merely exists. A new masked path is welcome — add it to MASKED_PATHS. One " +
                "that DISAPPEARED is the defect. Calls found:\n" + calls.joinToString("\n"),
            MASKED_PATHS.size, calls.size,
        )
    }

    // -- the swipe context a conversation opens with ------------------------------------------

    @Test fun `opening a message inside a conversation records the order the row is showing`() {
        // Nothing else pins this: drop the call and the recorded order stays empty, so the reading
        // view's swipe — an existing feature — falls back to the single message it was handed and
        // the conversation it was opened from is no longer pageable. Every test stays green.
        val calls = codeLines(INBOX_SCREEN).filter { "$RECORD_ORDER(" in it }
        assertEquals(
            "InboxScreen must record the unfolded conversation's order at the tap, exactly once — " +
                "it is the only place holding both the representative the row draws and the members " +
                "drawn beneath it. Found:\n" + calls.joinToString("\n"),
            1, calls.size,
        )
        assertTrue(
            "the recorded order must be the row's OWN representative (email) plus the members it " +
                "is drawing (members, the single subtracted read) — rebuilding it from anything " +
                "else is the remembered representative this path exists to remove. Call was:\n" +
                calls.single(),
            RECORDED_ORDER.containsMatchIn(calls.single()),
        )
    }

    // -- the chip's side ----------------------------------------------------------------------

    @Test fun `the chip binds the Sent folders the shared decision hands it`() {
        // The other half of the same wiring, and just as free to drift: ConversationScopeTest
        // resolves the pairs itself before running the shipped SQL, so it would stay green if the
        // query went back to deriving its own.
        val body = body(MAIL_REPOSITORY, "conversationQuery")
        assertTrue(
            "conversationQuery must take its Sent pairs from ${SHARED_SENT} — the same decision " +
                "the unfold is scoped by. Body was:\n$body",
            SHARED_SENT in body,
        )
        assertTrue(
            "conversationQuery must not derive its own Sent scope beside the shared one. " +
                "Body was:\n$body",
            "sentMailboxes.distinct()" !in body && "sentMailboxes.filter" !in body,
        )
    }

    @Test fun `both pagers are built from the resolution the chip is counted over`() {
        // The mutation the unfold's rules cannot see: hand the PAGER an empty Sent scope and the
        // divergence comes back the other way round — an unfold of 3 under a chip of 2 — with every
        // other rule here satisfied. The repository's parameter has no default precisely so that
        // omitting it does not compile; this pins the other way of saying nothing.
        val body = body(INBOX_VIEW_MODEL, "pagedEmails")
        assertTrue(
            "pagedEmails must resolve the Sent scope through sentScopes(...) — the flow that also " +
                "records it for the unfold. Body was:\n$body",
            "sentScopes(" in body,
        )
        assertTrue(
            "both pagers must be given that resolution and nothing else. Body was:\n$body",
            "conversationView, sent)" in body,
        )
        assertEquals(
            "exactly two pagers, one per selection, both fed the same way. Body was:\n$body",
            2,
            Regex("""repo\.paged\w+\(""").findAll(body).count(),
        )
        assertTrue(
            "pagedEmails must not hand a pager an empty Sent scope: the chip would then count " +
                "fewer messages than the row unfolds into. Body was:\n$body",
            "emptyList(" !in body,
        )
    }

    // -- reading the sources ----------------------------------------------------------------

    /**
     * The lines of [file] that are code: a line whose first non-blank character opens a comment is
     * dropped whole. Trailing comments are LEFT IN — a line can hold a `//` inside a string, and
     * cutting there would hide code from these rules. The cost is that a comment can carry a
     * false match, which is a false failure, not a false pass.
     */
    private fun codeLines(file: File): List<String> = file.readLines().filterNot {
        val code = it.trimStart()
        code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
    }

    /**
     * The declaration of `fun`/`val` [name] in [file] and its body, as text: everything up to the
     * first line indented no deeper than the declaration — the closing brace of a block form, the
     * next declaration after an expression form. Comment lines are dropped ([codeLines]).
     *
     * The terminator only applies ONCE THE BODY HAS STARTED, at the `{` or `=` that opens it. A
     * function wrapping its parameters over several lines closes them with a `)` at the
     * declaration's own indent, and stopping there would hand every rule a signature and call it a
     * body — which is what the first draft of this file did, silently, for the repository's half.
     *
     * Fails loudly when the declaration is not found: renaming it must break these rules rather
     * than silently satisfy them against an empty string.
     */
    private fun body(file: File, name: String): String {
        val lines = codeLines(file)
        // `fun <T> name(...)`: the type parameters sit between the keyword and the name.
        val declaration = Regex("""\b(fun|val|var)\s+(<[^>]*>\s+)?$name\b""")
        val start = lines.indexOfFirst { declaration.containsMatchIn(it) }
        check(start >= 0) { "${file.name} declares no '$name' — did it get renamed?" }
        val indent = lines[start].indentWidth()
        val out = mutableListOf<String>()
        var open = false
        for (i in start until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (open && i > start && line.indentWidth() <= indent) break
            out += line
            val code = line.trimEnd()
            if (code.endsWith("{") || code.endsWith("=")) open = true
        }
        return out.joinToString("\n")
    }

    private fun String.indentWidth() = length - trimStart().length

    companion object {
        /** The scope the list on screen was built with, and the only one the unfold may use. */
        private const val RECORDED = "listScope"

        /** Its single writer, matched as an assignment rather than a mention. */
        private val ASSIGNMENT = Regex("""\b$RECORDED\.value\s*=[^=]""")

        /** The one resolution both sides descend from. */
        private const val SENT_RESOLUTION = "observeSentMailboxes"

        /** Reading the live selection instead of the value the list was built with. */
        private const val RE_READ = "currentMailboxIds("

        private const val SHARED_DECISION = "ConversationScope.folders("
        private const val SHARED_SENT = "ConversationScope.sentFolders("

        /** The row's own representative, subtracted from the members at the place both are drawn. */
        private val MEMBERS_BELOW =
            Regex("""membersBelow\(\s*threadMembers\[threadKey]\.orEmpty\(\)\s*,\s*email\.id\s*\)""")

        /** The observed snooze table the member stream must be fed — the chip's SQL excludes an
         *  active snooze, and the unfold has to see it start AND lapse. */
        private const val SNOOZE_SOURCE = "repo.observeActiveSnoozed("

        /** The only form allowed to hide a member from the unfolded rows. */
        private const val SCOPED_HIDE = "hidingThreadMembers"

        /** A CALL to it, never its declaration (`fun <T> hidingThreadMembers(...)`) — the whole
         *  point of the count is the call sites, and a declaration always answers for itself. */
        private fun isCall(line: String) = "$SCOPED_HIDE(" in line && "fun " !in line

        /** The paths that take a member off the unfolded rows, and must therefore mask it: the
         *  swipe of one message, the held-back destroy, and the two selection-bar batches. */
        private val MASKED_PATHS = listOf("heldBackDestroy", "swipeRemove", "bulk", "bulkBatched")

        /** The swipe context of a message opened from inside an unfolded conversation. */
        private const val RECORD_ORDER = "viewModel.recordThreadOrder"

        /** Recorded from what the row is DRAWING: its own representative and the members below. */
        private val RECORDED_ORDER =
            Regex("""$RECORD_ORDER\(\s*threadKey\s*,\s*email\s*,\s*members\s*\)""")

        /** Raising or lifting that mask outside [SCOPED_HIDE] — the shape a caller can forget. */
        private val BY_HAND = Regex("""\b(removedMembers|restoreThreadMembers|dropThreadMembers?)\b""")

        private const val APP_SOURCES = "app/src/main/kotlin"
        private const val INBOX_VIEW_MODEL_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxViewModel.kt"
        private const val INBOX_SCREEN_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxScreen.kt"
        private const val CONVERSATION_EXPANSION_PATH = "$APP_SOURCES/app/sterna/ui/inbox/ConversationExpansion.kt"
        private const val REPOSITORY_PATH = "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt"

        /** Repo root, walked up from the module's working directory — the rules read BOTH modules. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
        private val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
        private val CONVERSATION_EXPANSION: File by lazy { File(root, CONVERSATION_EXPANSION_PATH) }
        private val MAIL_REPOSITORY: File by lazy { File(root, REPOSITORY_PATH) }
    }
}
