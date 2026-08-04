package app.sterna.ui.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — the same instrument and the same disclaimer as
 * [app.sterna.ui.inbox.OutboxCountWiringTest]: it reads source files as text, it proves nothing
 * about what happens on screen, and it would still pass if the code it points at were broken
 * inside.
 *
 * It covers the couplings no JVM test in this repo can reach, because they live in a
 * `@Composable` and an `AndroidViewModel`: where the inbox's overflow entry sits and when it is
 * hidden; that the row menu is built by [senderMenuEntries] over that row's own state, arguments
 * included; that each answer of [screenBody] is drawn as the widget it promises; that the
 * confirmation counts the list it will delete AND that the delete acts on that same list; that
 * the list key is the address as stored; that a failed count neither strands the screen on a
 * spinner nor is drawn as a count of zero; that the in-flight flag comes down on every way out;
 * that no read reports a cancellation as a failure; that the script is only ever saved as
 * [app.sterna.core.data.filter.addBlockRule]'s `save` callback, with a `load` that goes to the
 * server at the moment of writing; and that nothing in this package can name a permanent destroy.
 *
 * WHICH entries a menu holds, and which of them are tappable, is NOT read here: it is
 * [senderMenuEntries], and [MailBySenderTest] runs it. What is read here is the call — because
 * two mutations of its arguments have already survived every rule in this file.
 *
 * Four of these rules exist because a mutation went through the file as it stood. The rule on the
 * `load` callback counted the lines that mention `loadFilterRules(`, so replacing that callback
 * with a snapshot taken when the screen opened dropped the count by one — back to the number the
 * rule expected, green. **A rule that counts occurrences is satisfied by any edit that keeps the
 * count.** It pins the line now. The three others were written after watching their mutation
 * survive: a delete that re-reads its ids, a confirmation opened over `ids.take(1)`, and
 * `canDelete = true`. Each of those pins an ARGUMENT, because each of the mutations kept the
 * call.
 *
 * What it does NOT do: it reads names. That the composition is right is `SenderBlockTest`'s job,
 * that the numbers are right is `SenderVolumeSqlTest`'s, that the words are honest is
 * `SenderVolumeCopyTest`'s. And the navigation guard on the new route is not checked here at all
 * — `NavHostSourceRulesTest` already holds every action in every NavHost file, this one included.
 */
class MailBySenderWiringTest {

    @Test fun `the inbox menu entry opens the screen`() {
        val entry = menuEntry(INBOX_SCREEN, BY_SENDER_LABEL)
        assertTrue(
            "the '$BY_SENDER_LABEL' entry must call onOpenMailBySender(). Entry was:\n$entry",
            "onOpenMailBySender()" in entry,
        )
    }

    @Test fun `the entry sits after the Outbox and before anything destructive`() {
        // #48: the rarely-visited lists come after the frequent actions, and the destructive
        // "Empty trash" stays last so the finger never finds it where a harmless entry was.
        val text = code(INBOX_SCREEN)
        val outbox = text.indexOf("R.string.inbox_outbox")
        val bySender = text.indexOf(BY_SENDER_LABEL)
        val emptyTrash = text.indexOf("R.string.inbox_empty_trash")
        assertTrue("R.string.inbox_outbox is no longer in InboxScreen", outbox >= 0)
        assertTrue("$BY_SENDER_LABEL is no longer in InboxScreen", bySender >= 0)
        assertTrue("R.string.inbox_empty_trash is no longer in InboxScreen", emptyTrash >= 0)
        assertTrue("the by-sender entry must come after the Outbox entry", outbox < bySender)
        assertTrue("the by-sender entry must come before Empty trash", bySender < emptyTrash)
    }

    @Test fun `the entry is hidden in the Trash`() {
        val lines = codeLines(INBOX_SCREEN)
        val at = lines.indexOfFirst { BY_SENDER_LABEL in it }
        assertTrue("$BY_SENDER_LABEL is no longer in InboxScreen", at >= 0)
        assertTrue(
            "the by-sender entry must sit inside an 'if (!isTrash)' block, like the scheduled " +
                "and snoozed entries: it counts the folders mail is kept in, and the Trash is " +
                "not one of them",
            lines.enclosedBy(at, "if (!isTrash)"),
        )
    }

    @Test fun `the row menu is drawn from the pure decision, over that row's own state`() {
        // WHICH entries exist and which are tappable is senderMenuEntries', and MailBySenderTest
        // RUNS it. What no JVM test can reach is the call: the arguments it is given. Two of them
        // have already been mutated to a constant with every other rule staying green —
        // `blocked = false` (the "already handled" entry never greys again, and the string it
        // would have shown is still in the file, so every copy rule passes) and the `working`
        // flag folded into the availability (the entries vanish instead of greying).
        val screen = code(SCREEN)
        assertTrue(
            "the menu must be built by senderMenuEntries(canDelete, canBlock, blocked, working, " +
                "row.email, ownAddresses) — the decision a test executes — and each item's " +
                "`enabled` must come from it. The last two are the row's own address and the " +
                "addresses that ARE this account: without them the row that is the account " +
                "itself offers to file the account's own mail into the Trash (R6)",
            "senderMenuEntries(canDelete, canBlock, blocked, working, row.email, ownAddresses)" in screen &&
                "enabled = entry.enabled" in screen,
        )
        val row = callArguments(screen, "SenderRow").single { "row = row" in it }
        listOf(
            "canDelete = state.canDelete,",
            "canBlock = state.canBlock,",
            "ownAddresses = state.ownAddresses,",
            "working = state.working,",
            "blocked = viewModel.isBlocked(row.email),",
        ).forEach { expected ->
            assertTrue(
                "SenderRow must be given '$expected'. `blocked` decides whether the row says a " +
                    "rule already exists; a constant there is invisible to every other rule in " +
                    "this file. Call was:\n$row",
                expected in row,
            )
        }
        assertTrue(
            "`working` must NOT be folded into canDelete/canBlock: those two say 'this account " +
                "cannot do it' and HIDE the entry, `working` says 'not right now' and GREYS it. " +
                "Folded together, every tap on ⋮ during a batch opens an empty menu. Call was:\n$row",
            "state.working" !in row.substringBefore("working = state.working,"),
        )
    }

    @Test fun `the search entry opens the search on that row's own address`() {
        // The argument, not the call: `onSearch = {}` compiles, the entry is there, every rule
        // about WHICH entries exist stays green, and the one gesture that gives something to
        // look at before anything is destroyed quietly does nothing.
        val row = callArguments(code(SCREEN), "SenderRow").single { "row = row" in it }
        assertTrue(
            "SenderRow must be given 'onSearch = { onOpenSearch(row.email) }' — that row's " +
                "address, not the screen's or a constant. Call was:\n$row",
            "onSearch = { onOpenSearch(row.email) }," in row,
        )
    }

    @Test fun `the deleted message is counted, in this screen's own plural`() {
        // "Message deleted", singular, for forty messages — on a screen whose entire job is to
        // count. The count must be the offer's `deleted` (what the server confirmed it moved),
        // not `targets.size` (what can be put back, which excludes mail already in the Trash).
        val screen = code(SCREEN)
        assertTrue(
            "the snackbar must read 'pluralStringResource(R.plurals.sender_volume_deleted, " +
                "it.deleted, it.deleted)'",
            "pluralStringResource(R.plurals.sender_volume_deleted, it.deleted, it.deleted)" in screen,
        )
        assertTrue(
            "R.string.status_message_deleted must be gone from this screen: it is the inbox's " +
                "singular, it is shared with a path this lot does not touch, and it is the lie " +
                "this correction is about",
            "R.string.status_message_deleted" !in screen,
        )
        assertTrue(
            "no count read off the targets may reach the snackbar",
            "targets.size" !in screen && "it.targets" !in screen,
        )
    }

    @Test fun `the undo offer carries the number the server confirmed`() {
        val body = functionBody(VIEW_MODEL, "confirmDelete")
        assertTrue(
            "the offer must be built as 'SenderUndo(credentials, targets, deletedCount(result))' " +
                "— the count executed by MailBySenderTest, over the result of the delete. Body " +
                "was:\n$body",
            "SenderUndo(credentials, targets, deletedCount(result))" in body,
        )
    }

    @Test fun `the foot note comes from the pure decision, over the same two readings`() {
        // The note and the missing entry must describe ONE account: written from a different
        // reading, the screen can print "another script is active" beside a working entry, or
        // hide the entry with no note at all. Two assignments in one copy() — exactly the shape
        // a mutation drops half of.
        val body = functionBody(VIEW_MODEL, "load")
        assertTrue(
            "load() must set 'canBlock = canBlockSender(loaded, trashPath)'. Body was:\n$body",
            "canBlock = canBlockSender(loaded, trashPath)," in body,
        )
        assertTrue(
            "…and 'blockNote = blockNoteRes(blockAvailability(loaded, trashPath))', from the " +
                "same two readings. Body was:\n$body",
            "blockNote = blockNoteRes(blockAvailability(loaded, trashPath))," in body,
        )
        val screen = code(SCREEN)
        assertTrue(
            "the screen must draw the note the state carries, as 'state.blockNote?.let', and " +
                "decide nothing of its own",
            "state.blockNote?.let" in screen,
        )
        assertTrue(
            "the note must name the Filters screen by its own localised labels, not by an " +
                "English path typed into nine translations",
            "R.string.settings_filters_title" in screen && "R.string.inbox_settings" in screen,
        )
        // NOT pinned here, and said so rather than left to be discovered: that the note carries
        // no button. Taking a running Sieve script over is the Filters screen's decision — it
        // warns in red above its own Save button — but the `when` branch this note lives in runs
        // to the end of the file, so any rule about what it does NOT contain would be a rule
        // about the whole screen.
    }

    @Test fun `the rule entry is held back until the script has actually been read`() {
        // R6's second half, on this side: the entry may not be offered on what nobody has asked
        // the server yet. The screen already had it right and nothing pinned it — canBlock is
        // written FALSE with the counts, which land at once from the local query, and only the
        // second write, after the round-trip, may let canBlockSender decide. A mutation that
        // hoists canBlockSender into the first write offers the gesture on a state read from
        // nothing at all, and every decision test stays green because each is handed its state.
        val body = functionBody(VIEW_MODEL, "load")
        val counts = body.substringBefore("val loaded = ")
        assertTrue(
            "the state written with the counts must set 'canBlock = false' — the script has not " +
                "been read at that point, and there is nothing to decide from. Body was:\n$counts",
            "canBlock = false," in counts && "canBlockSender(" !in counts,
        )
        assertTrue(
            "…and the account's own addresses must be written THERE, with the rows, not with the " +
                "script: the row that is the account itself must be refused from the moment it " +
                "is drawn, and the round-trip that follows may never come back. Body was:\n$counts",
            "ownAddresses = ownAddresses(credentials)," in counts,
        )
        assertTrue(
            "…and that helper must be the shared one, whole: 'accountAddresses(store.identities(" +
                "credentials.id), credentials.username)'. What an identity contributes is " +
                "AccountAddressesTest's to run; pinned here is that the identities are THIS " +
                "account's and that the login goes in with them — an argument swapped one line " +
                "down empties the list and every decision test stays green, because each builds " +
                "its own",
            "accountAddresses(store.identities(credentials.id), credentials.username)" in code(VIEW_MODEL),
        )
    }

    @Test fun `the script is only ever saved as addBlockRule's save callback`() {
        val lines = codeLines(VIEW_MODEL)
        assertTrue(
            "MailBySenderViewModel must go through addBlockRule(...): that is where 'read the " +
                "rules, then write them back with one more' is written down and executed by a test.",
            lines.any { "addBlockRule(" in it },
        )
        val saves = lines.filter { "saveFilterRules(" in it }
        assertEquals(
            "every saveFilterRules call in this ViewModel must be addBlockRule's 'save =' " +
                "callback. A direct call is how an account's whole Sieve script gets replaced by " +
                "one rule — saveFilterRules rewrites everything it is handed. Found:\n" +
                saves.joinToString("\n"),
            saves.size,
            saves.count { "save = " in it },
        )
    }

    @Test fun `addBlockRule's load callback is the repository call itself`() {
        // Pinned by SHAPE, not by counting. Counting the lines that mention loadFilterRules let
        // the worst mutation of this module through: replace the callback with a snapshot taken
        // when the screen opened, and the count of mentions drops by exactly one — back to the
        // number the rule wanted. The script would then be rewritten from stale rules, and every
        // rule added since the screen opened would disappear from the server.
        val body = code(VIEW_MODEL)
        assertTrue(
            "addBlockRule's 'load =' must be exactly '$LOAD_CALLBACK' — a lambda that goes to " +
                "the server AT THE MOMENT OF WRITING. Anything captured earlier is a stale list, " +
                "and saveFilterRules writes whatever list it is given over the whole script.",
            LOAD_CALLBACK in body,
        )
    }

    @Test fun `nothing in this screen's package can name the permanent destroy`() {
        // The repository forbids nothing: destroyAll is one word away from deleteAll at any call
        // site, and it destroys server-side with no Trash and no undo. From a row menu that is
        // the worst thing this feature could grow. The only guard was a comment; this is the rule.
        val offenders = packageSources()
            .flatMap { file -> codeLines(file).map { file.name to it } }
            .filter { (_, line) -> "destroyAll" in line || "heldBackDestroy" in line }
        assertTrue(
            "a permanent destroy must never be reachable from the per-sender screen: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test fun `the server-side rule is written only from its own confirmation`() {
        // SOURCE LINT, and a narrow one: it proves where the CALL sits, never that a dialog is
        // drawn or that a finger can reach it. What it does pin is the argument — which row the
        // rule is written for — and the one sentence that dialog exists for.
        //
        // The gesture reached the row menu with no confirmation at all: one tap wrote a permanent
        // server-side rule, invisibly, with no Undo, while the SAME write from the reader went
        // through a dialog. What the dialog adds is not the description of the effect (the menu
        // entry's own label already says it) but sender_volume_block_body — "Nothing already
        // received moves. The rule can be changed or removed in Settings → Filters": the sentence
        // that says how to undo it. It is asserted here for that reason and not for tidiness.
        val screen = code(SCREEN)
        val dialogs = callArguments(screen, "AlertDialog")
        assertEquals(
            "the screen must draw TWO dialogs — the delete's and the rule's. Found ${dialogs.size}:" +
                "\n${dialogs.joinToString("\n--\n")}",
            2, dialogs.size,
        )
        val rule = dialogs.single { "R.string.sender_volume_block_title" in it }
        val delete = dialogs.single { "pending" in it }
        assertTrue(
            "the rule dialog must name THE ROW's address in its title, as 'stringResource(" +
                "R.string.sender_volume_block_title, ruleFor.email)'. A constant, or the screen's " +
                "own anything, asks about one address and writes another. Dialog was:\n$rule",
            "stringResource(R.string.sender_volume_block_title, ruleFor.email)" in rule,
        )
        val body = namedLambda(rule, "text")
        listOf(
            "R.string.sender_volume_block_body",
            "R.string.inbox_settings",
            "R.string.settings_filters_title",
        ).forEach { expected ->
            assertTrue(
                "the rule dialog's body must be '$expected' — the sentence that says nothing " +
                    "already received moves and where the rule can be removed, named by the " +
                    "app's own localised labels. That sentence IS the dialog's reason to exist. " +
                    "Body was:\n$body",
                expected in body,
            )
        }
        val confirm = namedLambda(rule, "confirmButton")
        assertTrue(
            "the write must leave from the confirm button, as 'viewModel.blockSender(ruleFor)' — " +
                "the row the dialog was opened over. Confirm button was:\n$confirm",
            "viewModel.blockSender(ruleFor)" in confirm,
        )
        assertTrue(
            "…under this screen's own confirm label (R.string.sender_volume_block), the same one " +
                "the reader's dialog uses. Confirm button was:\n$confirm",
            "R.string.sender_volume_block)" in confirm,
        )
        assertTrue(
            "…and it must close the dialog as it writes, as 'confirmRule = null': the half that " +
                "is invisible in review. Left open over a write already gone, its button then " +
                "returns at the ViewModel's `working` guard — a second tap that does nothing and " +
                "says nothing, the defect this screen was audited for. Confirm button was:\n$confirm",
            "confirmRule = null" in confirm,
        )
        val dismiss = namedLambda(rule, "dismissButton")
        assertTrue(
            "cancelling must write nothing and say so with R.string.inbox_cancel. Dismiss " +
                "button was:\n$dismiss",
            "blockSender" !in dismiss && "R.string.inbox_cancel" in dismiss,
        )
        assertEquals(
            "…and nowhere else in the screen may call it: one call site, inside that confirm " +
                "button. A second one is the tap that writes without asking — which is what this " +
                "screen shipped with.",
            1, Regex("""viewModel\.blockSender\(""").findAll(screen).count(),
        )
        val row = callArguments(screen, "SenderRow").single { "row = row" in it }
        assertTrue(
            "the menu entry must only ARM the confirmation, over its own row, as 'onBlock = { " +
                "confirmRule = row }'. This is the argument, not the call: the call was there " +
                "all along and went straight to the server. Call was:\n$row",
            "onBlock = { confirmRule = row }," in row,
        )
        assertTrue(
            "the delete's dialog must have nothing to do with the rule. Dialog was:\n$delete",
            "blockSender" !in delete,
        )
    }

    @Test fun `a delete that half worked is not announced as a total failure`() {
        // The screen said "Couldn't complete the action" for a single rejected id and, in the
        // next breath, counted the ones that DID go — two contradictory sentences about one
        // gesture. Which of the three things there is to say is deleteMessageRes(), executed by
        // MailBySenderTest; pinned here are the two numbers it is given, because the decision
        // stays perfectly right while being handed the wrong pair.
        val body = functionBody(VIEW_MODEL, "confirmDelete")
        assertTrue(
            "confirmDelete must ask 'deleteMessageRes(ids.size, result.failed.size)' — the list " +
                "actually handed to the repository, and what came back rejected. Body was:\n$body",
            "deleteMessageRes(ids.size, result.failed.size)" in body,
        )
        assertTrue(
            "…and must decide nothing of its own from the rejected set: 'result.failed" +
                ".isNotEmpty()' is the line that called one rejected id a total failure. Body " +
                "was:\n$body",
            "result.failed.isNotEmpty()" !in body,
        )
        assertTrue(
            "…and must name no status string itself: which one is the decision's answer, and a " +
                "second one written here is how the two parted company. Body was:\n$body",
            "R.string.status_action" !in body,
        )
        assertTrue(
            "…and the answer must actually be SHOWN, as '_message.value = getApplication<" +
                "Application>().getString(it)': the decision is allowed to say nothing (NONE), so " +
                "a body dropped from the ?.let is a total failure reported as silence, and every " +
                "rule above stays green. Body was:\n$body",
            "_message.value = getApplication<Application>().getString(it)" in body,
        )
    }

    @Test fun `the confirmation counts the list it will delete`() {
        // `.single { "pending" in it }` and not `.single()`: the screen draws a second dialog
        // now, the rule's, and this rule is about the delete's.
        val dialog = callArguments(code(SCREEN), "AlertDialog").single { "pending" in it }
        assertTrue(
            "the dialog's plural must be given pending.ids.size — the list its confirm button " +
                "hands to the delete. The row's own total was read when the SCREEN loaded and can " +
                "be older; announcing that number and deleting this list is the dishonesty this " +
                "whole feature is written against. Dialog was:\n$dialog",
            "pending.ids.size" in dialog,
        )
        assertTrue(
            "the confirm button must call viewModel.confirmDelete(), which acts on that same " +
                "list — not re-read the ids. Dialog was:\n$dialog",
            "viewModel.confirmDelete()" in dialog,
        )
        assertTrue(
            "no count read off the row may appear in the dialog. Dialog was:\n$dialog",
            "sender.total" !in dialog && "pending.total" !in dialog,
        )
    }

    @Test fun `the confirmation is opened over the WHOLE counted list`() {
        // Found by a mutation that survived every other rule here: `PendingDelete(sender, ids)`
        // → `PendingDelete(sender, ids.take(1))`. Announced and done stay equal — the dialog
        // says one and deletes one — so the honesty invariant is untouched, and the gesture
        // still silently does a fraction of what the row it was opened from says. What has to
        // be pinned is the ARGUMENT, not the call.
        val body = functionBody(VIEW_MODEL, "askDelete")
        assertTrue(
            "askDelete must read the ids from the shared scope query, as 'val ids = " +
                "repo.senderMessageIds(credentials.id, sender.email)'. Body was:\n$body",
            "val ids = repo.senderMessageIds(credentials.id, sender.email)" in body,
        )
        assertTrue(
            "…and must hand that list WHOLE to the confirmation, as 'PendingDelete(sender, " +
                "ids)': anything narrower is a gesture doing less than the row it came from " +
                "says, with the dialog agreeing with itself all the way down. Body was:\n$body",
            "PendingDelete(sender, ids)" in body,
        )
    }

    @Test fun `the delete's availability is the pure decision, not a rewrite of it`() {
        // Also found by a surviving mutation: `canDelete = true`. D12 says the gesture is
        // UNAVAILABLE on an account with no resolvable Trash, not silently ineffective — with
        // no Trash, deleteWouldDestroy answers true and deleteAll fails the whole batch. The
        // rule pins the call WITH its argument, and canDeleteFrom itself is executed by
        // MailBySenderTest.
        val body = functionBody(VIEW_MODEL, "load")
        assertTrue(
            "load() must set 'canDelete = canDeleteFrom(mailboxes)' — the decision executed by " +
                "a test, over the account's own cached folder list. Body was:\n$body",
            "canDelete = canDeleteFrom(mailboxes)" in body,
        )
    }

    @Test fun `the confirmed delete acts on the list the dialog counted`() {
        // The other half of "announced and done are the same set", and the half nothing held:
        // the dialog can be given `pending.ids.size` while confirmDelete re-reads the ids from
        // the database — same query, later instant, and the two numbers part company again. That
        // mutation survives every other rule in this file, because the count and the read are
        // then each individually right.
        val body = functionBody(VIEW_MODEL, "confirmDelete")
        assertTrue(
            "confirmDelete must take its batch from the confirmation, as 'val ids = " +
                "pending.ids'. Body was:\n$body",
            "val ids = pending.ids" in body,
        )
        assertTrue(
            "confirmDelete must NOT read the ids again: the list the dialog counted is the list " +
                "that goes, or the number on screen was about something else. Body was:\n$body",
            "senderMessageIds" !in body,
        )
        assertTrue(
            "confirmDelete must move to the state deleteStarted() defines — batch in flight AND " +
                "dialog gone — as '_state.value = deleteStarted(_state.value)'. Both halves are " +
                "executed by MailBySenderTest; written out here as a copy(), one of them can be " +
                "dropped inside a line that carries the other and nothing sees it. Body was:\n$body",
            "_state.value = deleteStarted(_state.value)" in body,
        )
        assertTrue(
            "…and it must be raised SYNCHRONOUSLY, before the launch: two taps in one frame both " +
                "reach the guard otherwise. Body was:\n$body",
            body.indexOf("deleteStarted(") < body.indexOf("viewModelScope.launch"),
        )
    }

    @Test fun `the list is keyed on the address exactly as stored`() {
        // SQLite's lower() is ASCII-only, Kotlin's lowercase() is not: "Éric@x" and "éric@x" are
        // two rows out of the query and ONE lowercase() key. A LazyColumn given the same key
        // twice throws, and the screen dies with no way back. Two rows already have two distinct
        // addresses — there is nothing to normalise.
        val screen = code(SCREEN)
        assertTrue(
            "the LazyColumn key must be 'key = { it.email }', with no transformation",
            "key = { it.email }" in screen,
        )
        assertTrue(
            "no lowercase() may touch the list key: $screen",
            "it.email.lowercase()" !in screen,
        )
    }

    @Test fun `each body the decision names draws the widget it promises`() {
        // screenBody() is executed by MailBySenderTest; what it cannot see is what each of its
        // answers is DRAWN as. The one that matters is LOADING: the previous version of this
        // screen answered "count not in yet" by drawing nothing at all — no account name, no
        // sentence, no spinner — which on the big cache this screen exists for is a white page
        // under a title for the whole scan.
        assertTrue(
            "the screen must render `when (screenBody(state))` rather than re-deciding",
            "when (screenBody(state))" in code(SCREEN),
        )
        assertTrue(
            "the LOADING body must be a CircularProgressIndicator — the same thing the Filters " +
                "screen shows while it reads. Branch was:\n${bodyBranch("LOADING")}",
            "CircularProgressIndicator(" in bodyBranch("LOADING"),
        )
        assertTrue(
            "the FAILED body must name the failure (settings_vacation_load_error) and offer the " +
                "read again (settings_vacation_retry): a count that could not be made must not " +
                "be drawn as a count of zero. Branch was:\n${bodyBranch("FAILED")}",
            "R.string.settings_vacation_load_error" in bodyBranch("FAILED") &&
                "R.string.settings_vacation_retry" in bodyBranch("FAILED"),
        )
        assertTrue(
            "the EMPTY body is the empty note", "R.string.sender_volume_empty" in bodyBranch("EMPTY"),
        )
        assertTrue(
            "the NO_ACCOUNT body is the no-account note",
            "R.string.settings_vacation_no_account" in bodyBranch("NO_ACCOUNT"),
        )
        val rows = bodyBranch("ROWS")
        assertTrue(
            "the header sentence and its number belong to the ROWS body ALONE: `total` is 0 " +
                "until the query lands, and '0 messages stored on this phone' is the one " +
                "sentence a counting screen must never show before it has counted. Branch was:\n$rows",
            SCOPE_LABEL in rows && "items(state.rows" in rows,
        )
        assertEquals(
            "…and it must appear nowhere else on the screen",
            1, Regex(Regex.escape(SCOPE_LABEL)).findAll(code(SCREEN)).count(),
        )
    }

    @Test fun `the count read cannot strand the screen on a spinner`() {
        // `loading` starts true and is only ever lowered by the code that follows the read. An
        // unprotected read that throws kills the coroutine there: the spinner stays up for the
        // life of the ViewModel, with no message and no retry — worse than the blank page it
        // replaced, because it looks like work in progress.
        val body = functionBody(VIEW_MODEL, "load")
        assertTrue(
            "load() must survive a failing count, as 'runCatching { repo.senderVolumes(" +
                "credentials.id) }.getOrElseUnlessCancelled { null }'. Body was:\n$body",
            "runCatching { repo.senderVolumes(credentials.id) }" in body &&
                "getOrElseUnlessCancelled" in body,
        )
        assertTrue(
            "…and must route the failure into the state the screen can read — 'loadError =' " +
                "with `loading` down — rather than leaving the spinner up. Body was:\n$body",
            "loadError = " in body && "loading = false" in body,
        )
    }

    @Test fun `no read in this ViewModel turns a cancellation into a failure`() {
        // getOrNull()/getOrDefault() swallow CancellationException like any other throwable, so a
        // screen left mid-read runs its failure branch on behalf of a caller that is gone. The
        // module has getOrElseUnlessCancelled for exactly this (Codeberg #99).
        val offenders = codeLines(VIEW_MODEL).filter {
            "runCatching" in it && ("getOrNull()" in it || "getOrDefault(" in it)
        } + codeLines(VIEW_MODEL).filter { ".getOrNull()" in it || ".getOrDefault(" in it }
        assertEquals(
            "a read in the ViewModel's lifecycle must use getOrElseUnlessCancelled, not " +
                "getOrNull()/getOrDefault(): those two report a cancelled read as a failed one. " +
                "Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders.distinct(),
        )
    }

    @Test fun `nothing lowers the in-flight flag except a finally`() {
        // With the flag raised and an exception on the way out — cachedEmailsByIds throwing, a
        // repository that dies mid-batch — the coroutine dies before the line that lowers it, and
        // both entries of every row stay greyed for the life of the ViewModel, with no word. The
        // flag must come down on EVERY way out, which in Kotlin is one construct.
        listOf("confirmDelete", "blockSender").forEach { name ->
            val body = functionBody(VIEW_MODEL, name)
            assertTrue(
                "$name must lower `working` in a finally, as 'finally { _state.value = " +
                    "_state.value.copy(working = false) }'. Body was:\n$body",
                Regex("""finally\s*\{\s*_state\.value = _state\.value\.copy\(working = false\)\s*}""")
                    .containsMatchIn(body),
            )
            assertEquals(
                "…and nowhere else: a second assignment is a path someone will later remove the " +
                    "finally for. Body was:\n$body",
                1, Regex("""working = false""").findAll(body).count(),
            )
        }
    }

    // -- reading the sources ---------------------------------------------------------------------

    /** The argument text of the single `DropdownMenuItem` carrying [label] in [file]. */
    private fun menuEntry(file: File, label: String): String {
        val entries = callArguments(code(file), "DropdownMenuItem").filter { label in it }
        assertEquals(
            "exactly one DropdownMenuItem must carry $label. Found ${entries.size}.",
            1, entries.size,
        )
        return entries.single()
    }

    /**
     * The text of the `name = { … }` argument of a call, braces balanced — so a rule can ask what
     * ONE slot of a dialog holds instead of what the dialog mentions somewhere. Fails loudly when
     * the slot is gone: a rule quietly matching an empty string is worse than no rule.
     */
    private fun namedLambda(text: String, name: String): String {
        val at = text.indexOf("$name = {")
        check(at >= 0) { "no '$name = {' in:\n$text" }
        val open = text.indexOf('{', at)
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
            i++
        }
        error("unbalanced braces after '$name ='")
    }

    /**
     * The text of one `when (screenBody(state))` branch of the screen, from its label to the next
     * label (or to the end, for the last one). Fails loudly if the branch is gone.
     */
    private fun bodyBranch(name: String): String {
        val text = code(SCREEN)
        val marker = "SenderScreenBody.$name ->"
        val at = text.indexOf(marker)
        check(at >= 0) { "the screen draws no '$marker' branch — was the body renamed?" }
        val rest = text.substring(at + marker.length)
        val next = Regex("""SenderScreenBody\.\w+ ->""").find(rest)
        return if (next == null) rest else rest.substring(0, next.range.first)
    }

    /** Whether the block opened by a line containing [opener] encloses line [index]. Walks
     *  strictly outwards by indentation, as the other source lints do (string templates carry
     *  braces of their own and make naive counting lie). */
    private fun List<String>.enclosedBy(index: Int, opener: String): Boolean {
        var indent = this[index].indentWidth()
        for (i in index - 1 downTo 0) {
            val candidate = this[i]
            if (candidate.isBlank() || candidate.indentWidth() >= indent) continue
            if (opener in candidate) return true
            indent = candidate.indentWidth()
        }
        return false
    }

    /**
     * The body of `fun [name]` in [file], braces included and comments stripped — so a rule can
     * ask what ONE function does instead of what the file mentions somewhere. Fails loudly if the
     * function is gone: a rule that quietly matches an empty string is worse than no rule.
     */
    private fun functionBody(file: File, name: String): String {
        val text = code(file)
        val at = Regex("""\bfun\s+$name\s*\(""").find(text)
            ?: error("MailBySenderViewModel has no 'fun $name' — did it get renamed?")
        val open = text.indexOf('{', at.range.last)
        check(open >= 0) { "'$name' has no block body" }
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
            i++
        }
        error("Unbalanced braces in $name")
    }

    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trimStart()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }

    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    private fun code(file: File): String = codeLines(file).joinToString("\n")

    private fun callArguments(text: String, name: String): List<String> =
        Regex("""\b${Regex.escape(name)}\(""").findAll(text)
            .map { balanced(text, it.range.last) }
            .toList()

    private fun balanced(text: String, from: Int): String {
        val start = text.indexOf('(', from).let { if (it < 0) from else it + 1 }
        var depth = 1
        var i = start
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return text.substring(start, (i - 1).coerceAtLeast(start)).trim()
    }

    private fun String.indentWidth() = length - trimStart().length

    companion object {
        private const val BY_SENDER_LABEL = "R.string.inbox_by_sender"
        private const val SCOPE_LABEL = "R.string.sender_volume_scope"

        /** The write path's read, in full: it must go to the server where it is written. */
        private const val LOAD_CALLBACK = "load = { repo.loadFilterRules(credentials) }"

        private const val APP_SOURCES = "app/src/main/kotlin"
        private const val INBOX_SCREEN_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxScreen.kt"
        private const val PACKAGE_PATH = "$APP_SOURCES/app/sterna/ui/sender"
        private const val SCREEN_PATH = "$PACKAGE_PATH/MailBySenderScreen.kt"
        private const val VIEW_MODEL_PATH = "$PACKAGE_PATH/MailBySenderViewModel.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
        private val SCREEN: File by lazy { File(root, SCREEN_PATH) }
        private val VIEW_MODEL: File by lazy { File(root, VIEW_MODEL_PATH) }

        /** Every source file of this screen's package — the rules that apply to the whole of it
         *  cover a file added tomorrow, not a list written today. */
        fun packageSources(): List<File> = (File(root, PACKAGE_PATH).listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.name }
            .also { check(it.size >= 2) { "the per-sender package holds ${it.size} sources — did it move?" } }
    }
}
