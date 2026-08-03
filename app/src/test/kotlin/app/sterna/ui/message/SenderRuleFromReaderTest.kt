package app.sterna.ui.message

import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.RuleField
import app.sterna.core.data.filter.RuleMatch
import app.sterna.core.data.mail.FilterRulesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The per-sender filter rule offered from the message being read (the ⋮ of the SENDER's row in
 * the participants panel).
 *
 * Everything that decides WHETHER the entry is there and whether it can be tapped is
 * [senderRuleEntry], a plain function, and it is RUN here — not read back out of a `@Composable`
 * nobody can instantiate. The last test is a source lint and says so; it pins the ARGUMENTS of
 * the call, because on this feature every mutation that survived kept the call and changed what
 * it was given.
 */
class SenderRuleFromReaderTest {

    private val address = "news@example.com"

    private fun ruled(vararg addresses: String) = FilterRulesState.Loaded(
        addresses.map {
            FilterRule(name = it, field = RuleField.FROM, match = RuleMatch.IS, value = it)
        },
    )

    @Test fun `the sender of the open message gets the entry`() {
        assertEquals(
            SenderRuleEntry.OFFERED,
            senderRuleEntry(isSender = true, trashPath = "Trash", rules = ruled(), address = address),
        )
    }

    @Test fun `a recipient does not`() {
        // A rule on FROM aimed at someone who was only in To or Cc is a rule about mail that
        // person has not sent. The panel lists all three groups with the same row, so the group
        // is an argument of the decision and not "whichever group happened to get a callback".
        assertEquals(
            SenderRuleEntry.ABSENT,
            senderRuleEntry(isSender = false, trashPath = "Trash", rules = ruled(), address = address),
        )
        assertEquals(
            "and no state of the account brings it back on a recipient's row",
            listOf(SenderRuleEntry.ABSENT, SenderRuleEntry.ABSENT, SenderRuleEntry.ABSENT),
            listOf(ruled(), ruled(address), FilterRulesState.Unsupported).map {
                senderRuleEntry(isSender = false, trashPath = "Trash", rules = it, address = address)
            },
        )
    }

    @Test fun `no Trash to name, and no server support, take the entry away`() {
        // The screen's own two silences, kept: with nothing to file the mail into there is
        // nothing honest to offer, and on an account whose server has no Sieve the rule would be
        // refused. Neither can be explained here any better than it is on the Filters screen.
        assertEquals(
            SenderRuleEntry.ABSENT,
            senderRuleEntry(isSender = true, trashPath = null, rules = ruled(), address = address),
        )
        assertEquals(
            SenderRuleEntry.ABSENT,
            senderRuleEntry(
                isSender = true,
                trashPath = "Trash",
                rules = FilterRulesState.Unsupported,
                address = address,
            ),
        )
    }

    @Test fun `an address the script already handles keeps its entry, greyed, and says why`() {
        val entry = senderRuleEntry(
            isSender = true,
            trashPath = "Trash",
            rules = ruled(address),
            address = address,
        )
        assertEquals(SenderRuleEntry.ALREADY_RULED, entry)
        assertEquals(app.sterna.R.string.sender_volume_block_done, senderRuleLabel(entry))
    }

    @Test fun `the address is matched the way the rule matches it`() {
        // alreadyBlocked compares FROM/IS rules case-insensitively, which is how the per-sender
        // screen groups. A second identical rule would file the same mail twice.
        assertEquals(
            SenderRuleEntry.ALREADY_RULED,
            senderRuleEntry(true, "Trash", ruled("News@Example.com"), address),
        )
        assertEquals(
            "a rule about somebody else is not this address's rule",
            SenderRuleEntry.OFFERED,
            senderRuleEntry(true, "Trash", ruled("other@example.com"), address),
        )
    }

    @Test fun `another Sieve script greys the entry instead of hiding it`() {
        // ⭐ The one place this location beats the list row: the reason can be SAID. On the
        // per-sender screen the entry disappears with no word anywhere; here it stays, disabled,
        // wearing the reason.
        val entry = senderRuleEntry(
            isSender = true,
            trashPath = "Trash",
            rules = FilterRulesState.Loaded(emptyList(), foreignActiveScript = true),
            address = address,
        )
        assertEquals(SenderRuleEntry.FOREIGN_SCRIPT, entry)
        assertEquals(app.sterna.R.string.sender_volume_block_foreign, senderRuleLabel(entry))
    }

    @Test fun `already handled answers before another script is blamed`() {
        // Both true at once. Neither branch writes anything, so they differ only in what the
        // reader is told, and "another script is active" on an address that is already handled
        // reports an obstacle where there is none. Same order as addBlockRule's, and for the
        // same reason (that one went red with "expected:<ALREADY_PRESENT> but was:<FAILED>").
        assertEquals(
            SenderRuleEntry.ALREADY_RULED,
            senderRuleEntry(
                isSender = true,
                trashPath = "Trash",
                rules = FilterRulesState.Loaded(
                    ruled(address).rules,
                    foreignActiveScript = true,
                ),
                address = address,
            ),
        )
    }

    @Test fun `a script nobody has read yet leaves the entry offered`() {
        // Null is "not read, or the read failed". Hiding the entry there makes an unreachable
        // server look like an IMAP account — no word, no retry — while tapping it runs
        // addBlockRule, which reads AGAIN at the moment of writing and reports what happened.
        // That read is the guard, and it is the reason this state is safe.
        assertEquals(
            SenderRuleEntry.OFFERED,
            senderRuleEntry(isSender = true, trashPath = "Trash", rules = null, address = address),
        )
        assertEquals(
            "…but a Trash that cannot be named is still nothing to offer",
            SenderRuleEntry.ABSENT,
            senderRuleEntry(isSender = true, trashPath = null, rules = null, address = address),
        )
    }

    @Test fun `the offered entry wears the gesture's own words, which are not the unsubscribe's`() {
        // The distinction the two menus are two taps apart on the same message: "Unsubscribe"
        // ASKS the sender to stop and depends on their goodwill; this one asks nobody — the
        // server applies it, to anyone. They must not be able to read as the same thing.
        assertEquals(app.sterna.R.string.sender_volume_block, senderRuleLabel(SenderRuleEntry.OFFERED))
        assertEquals(app.sterna.R.string.sender_volume_block, senderRuleLabel(SenderRuleEntry.ABSENT))
    }

    /**
     * SOURCE LINT, and the only one here. What it covers cannot be reached from the JVM: the
     * decision's arguments at the call site, and the fact that the confirmation stands between
     * the entry and the write.
     */
    @Test fun `the panel calls the decision with the row's own group and address`() {
        val screen = SOURCE.readText()
        assertTrue(
            "the participants panel must ask senderRuleEntry(isSender, trashFilePath(" +
                "accountMailboxes), senderRules, address) — the account's OWN cached folder " +
                "list, and the script as last read",
            "senderRuleEntry(isSender, trashFilePath(accountMailboxes), senderRules, address)" in screen,
        )
        assertTrue(
            "the From group must be the only one flagged as the sender",
            "ParticipantGroup(R.string.participants_from, from, isSender = true," in screen &&
                "ParticipantGroup(R.string.participants_to, to, isSender = false," in screen &&
                "ParticipantGroup(R.string.participants_cc, cc, isSender = false," in screen,
        )
        assertTrue(
            "each row must ask the decision for ITS OWN group and address, not once for the panel",
            "senderRule.entryFor(isSender, addr.email)" in screen,
        )
        assertTrue(
            "the entry may only be tappable in the one state that means 'go ahead'",
            "enabled = entry == SenderRuleEntry.OFFERED," in screen,
        )
        assertTrue(
            "tapping it must open the confirmation and write nothing: 'confirmRule = true'",
            "onClick = { menuOpen = false; confirmRule = true }," in screen,
        )
        assertTrue(
            "and only the dialog's confirm button may write, as 'senderRule.onBlock(addr.email)'",
            "confirmRule = false; senderRule.onBlock(addr.email)" in screen,
        )
        assertEquals(
            "…exactly once in the file: a second write path is a confirmation that can be walked past",
            1,
            Regex(Regex.escape("senderRule.onBlock(")).findAll(screen).count(),
        )
    }

    /**
     * Both rules below were written after WATCHING their mutation survive the whole campaign.
     * Neither breaks anything a test could see: the code compiles, every decision above still
     * runs, and the feature quietly stops being what it says it is.
     */
    @Test fun `opening the panel is what reads the account's script`() {
        // The mutation that survived: delete the one line that arms the read. The state then
        // stays null for ever, so senderRuleEntry answers OFFERED every time — "a rule already
        // exists for this sender" and "another filter script is active" are then WORDS THAT CAN
        // NEVER APPEAR, and every test above goes on passing because each of them hands the
        // decision a state directly.
        val screen = SOURCE.readText()
        assertTrue(
            "the participants panel must arm the read, as 'LaunchedEffect(Unit) { " +
                "senderRule.onOpened() }' — nothing else in this screen ever asks for the script",
            "LaunchedEffect(Unit) { senderRule.onOpened() }" in screen,
        )
        assertTrue(
            "…and that callback must be the ViewModel's read: 'onOpened = viewModel::loadSenderRules'",
            "onOpened = viewModel::loadSenderRules," in screen,
        )
        assertTrue(
            "the entry's state must come from the script the panel read — 'senderRules' — and " +
                "not from a constant",
            "val senderRules by viewModel.senderRules.collectAsStateWithLifecycle()" in screen,
        )
    }

    @Test fun `the confirmation names the address it is about to file`() {
        // The other survivor: keep the dialog, keep its title, drop the address it interpolates.
        // The reader is then asked to confirm a rule about nobody in particular — on a panel
        // that lists several people, one row apart.
        val screen = SOURCE.readText()
        assertTrue(
            "the dialog's title must name the address: 'stringResource(R.string." +
                "sender_volume_block_title, addr.email)'",
            "stringResource(R.string.sender_volume_block_title, addr.email)" in screen,
        )
        assertTrue(
            "and its body must say where the rule can be undone, in the app's own localised " +
                "labels rather than an English path typed into nine translations",
            "R.string.sender_volume_block_body," in screen &&
                "stringResource(R.string.inbox_settings)," in screen &&
                "stringResource(R.string.settings_filters_title)," in screen,
        )
    }

    @Test fun `the reader's write path is addBlockRule, with a read taken at the moment of writing`() {
        // Same rule as the per-sender screen's, for the same reason: saveFilterRules rewrites the
        // WHOLE script, so a save built on anything but a fresh successful read deletes the
        // account's filters instead of adding one. And the foreign-script refusal lives inside
        // addBlockRule, where no caller can pass an argument that switches it off.
        val vm = VIEW_MODEL.readText()
        assertTrue(
            "MessageViewModel.blockSender must go through addBlockRule(...)",
            "val outcome = addBlockRule(" in vm,
        )
        assertTrue(
            "its load must be the repository call itself — 'load = { repo.loadFilterRules(" +
                "credentials) }' — never a snapshot taken when the message opened",
            "load = { repo.loadFilterRules(credentials) }," in vm,
        )
        val saves = vm.lines().filter { "saveFilterRules(" in it && !it.trimStart().startsWith("*") }
        assertEquals(
            "every saveFilterRules call in this ViewModel must be addBlockRule's 'save =' " +
                "callback. Found:\n" + saves.joinToString("\n"),
            saves.size,
            saves.count { "save = " in it },
        )
        assertTrue(
            "the Trash must be named from the account's own cached folders",
            "val trashPath = trashFilePath(accountMailboxes.value)" in vm,
        )
    }

    private companion object {
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt").isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }
        val SOURCE: File by lazy { File(root, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt") }
        val VIEW_MODEL: File by lazy { File(root, "app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt") }
    }
}
