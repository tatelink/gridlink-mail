package app.sterna.core.data.filter

import app.sterna.core.data.mail.FilterRulesState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-sender "future mail to Trash" gesture, EXECUTED: the rule it writes, the composition
 * that adds it to what the account already has, and the order in which the two server calls
 * happen.
 *
 * [addBlockRule] takes its load and its save as parameters precisely so this can run them: the
 * failure that matters (saving a script built on a read that did not succeed) is a failure of
 * ORDER, and no assertion about a rule object can see it.
 */
class SenderBlockTest {

    private val existing = listOf(
        FilterRule(name = "work", field = RuleField.SUBJECT, match = RuleMatch.CONTAINS, value = "invoice", moveTo = "Work"),
        FilterRule(name = "family", field = RuleField.FROM, match = RuleMatch.CONTAINS, value = "@family.example", flag = true),
        FilterRule(name = "lists", field = RuleField.TO, match = RuleMatch.IS, value = "list@example.com", markRead = true),
    )

    @Test fun `the rule tests the whole address and files it read into the trash`() {
        val rule = blockRule("News@Example.com", "Trash")
        assertEquals(RuleField.FROM, rule.field)
        assertEquals(RuleMatch.IS, rule.match)
        assertEquals("News@Example.com", rule.value)
        assertEquals("Trash", rule.moveTo)
        assertTrue("the mail must arrive already read, or it lights a badge", rule.markRead)
        assertTrue(rule.enabled)
        assertFalse("nothing flags it: this is not a message to look at", rule.flag)
        assertEquals("the rule is named by the address, with no invented prefix", "News@Example.com", rule.name)
    }

    @Test fun `the compiled Sieve moves and marks, and can neither discard nor reject`() {
        val script = SieveCodec.generate(listOf(blockRule("news@example.com", "INBOX.Trash")))
        assertTrue(script, "address :is \"from\" \"news@example.com\"" in script)
        assertTrue(script, "fileinto \"INBOX.Trash\";" in script)
        assertTrue(script, "addflag \"\\\\Seen\";" in script)
        // The invariant of the whole feature: a one-finger gesture in a list must never be able
        // to destroy mail on the server with no undo.
        assertFalse(script, "discard" in script)
        assertFalse(script, "reject" in script)
    }

    @Test fun `composing ADDS the rule to the ones already there`() {
        // saveFilterRules rewrites the WHOLE script, so "add a rule" is really "save everything
        // plus one". Getting this wrong does not add a filter: it deletes every filter.
        val next = withBlockRule(existing, "news@example.com", "Trash")
        assertEquals(4, next.size)
        assertEquals(existing, next.take(3))
        assertEquals(blockRule("news@example.com", "Trash"), next.last())
    }

    @Test fun `an address already handled is recognised whatever its case`() {
        val rules = existing + blockRule("News@Example.com", "Trash")
        assertTrue(alreadyBlocked(rules, "news@example.com"))
        assertTrue(alreadyBlocked(rules, "NEWS@EXAMPLE.COM"))
        assertEquals("nothing is added twice", rules, withBlockRule(rules, "news@example.com", "Trash"))
    }

    @Test fun `a CONTAINS rule or another field is not this rule`() {
        // "@family.example" as a CONTAINS would catch news@family.example, but it is not the
        // rule this gesture writes, and treating it as one would silently refuse to act.
        assertFalse(alreadyBlocked(existing, "news@family.example"))
        assertFalse(alreadyBlocked(existing, "list@example.com")) // a TO/IS rule, not FROM
        assertFalse(alreadyBlocked(emptyList(), "news@example.com"))
    }

    // -- the order of the two server calls -------------------------------------------------------

    @Test fun `the save receives everything that was loaded, plus one`() = runBlocking {
        var saved: List<FilterRule>? = null
        val outcome = addBlockRule(
            address = "news@example.com",
            trashFolder = "Trash",
            load = { FilterRulesState.Loaded(existing) },
            save = { saved = it },
        )
        assertEquals(BlockOutcome.ADDED, outcome)
        assertEquals(existing + blockRule("news@example.com", "Trash"), saved)
    }

    @Test fun `a load that throws writes nothing`() = runBlocking {
        var saved: List<FilterRule>? = null
        val outcome = addBlockRule(
            address = "news@example.com",
            trashFolder = "Trash",
            load = { throw IllegalStateException("offline") },
            save = { saved = it },
        )
        assertEquals(BlockOutcome.FAILED, outcome)
        assertNull("a save here would wipe the account's filters", saved)
    }

    @Test fun `an unsupported account writes nothing`() = runBlocking {
        var saved: List<FilterRule>? = null
        val outcome = addBlockRule(
            address = "news@example.com",
            trashFolder = "Trash",
            load = { FilterRulesState.Unsupported },
            save = { saved = it },
        )
        assertEquals(BlockOutcome.FAILED, outcome)
        assertNull(saved)
    }

    @Test fun `an address already handled is not written again`() = runBlocking {
        var saved: List<FilterRule>? = null
        val outcome = addBlockRule(
            address = "NEWS@example.com",
            trashFolder = "Trash",
            load = { FilterRulesState.Loaded(existing + blockRule("news@example.com", "Trash")) },
            save = { saved = it },
        )
        assertEquals(BlockOutcome.ALREADY_PRESENT, outcome)
        assertNull(saved)
    }

    @Test fun `a save the server refuses is reported as a failure`() = runBlocking {
        val outcome = addBlockRule(
            address = "news@example.com",
            trashFolder = "Trash",
            load = { FilterRulesState.Loaded(existing) },
            save = { throw IllegalStateException("the server rejected the filters") },
        )
        assertEquals(BlockOutcome.FAILED, outcome)
    }
}
