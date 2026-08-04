package app.sterna.core.data.filter

import app.sterna.core.data.mail.FilterRulesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What reading an account's filters answers — [loadedFilterRules], executed.
 *
 * `MailRepository` cannot be built in a JVM test (session, JMAP client, device), so the decision
 * lives outside it and is called here with the two things it actually depends on: the body of the
 * `sterna` script, and whether another script is active. Nothing here reads a line of source.
 */
class FilterRulesReadTest {

    private val rule = FilterRule(
        name = "Boss", field = RuleField.FROM, match = RuleMatch.IS, value = "boss@example.com",
        moveTo = "Work",
    )

    @Test fun `a script this app wrote gives its rules back, with no warning`() {
        val state = loadedFilterRules(SieveCodec.generate(listOf(rule)), otherActiveScript = false)
        assertEquals(listOf(rule), state.rules)
        assertFalse("a script we can read is not foreign", state.foreignActiveScript)
    }

    @Test fun `no sterna script at all is zero rules, and no warning`() {
        val state = loadedFilterRules(sternaScript = null, otherActiveScript = false)
        assertEquals(emptyList<FilterRule>(), state.rules)
        assertFalse(state.foreignActiveScript)
    }

    /**
     * ⛔ THE BUG. A `sterna` script with content nobody can decode used to come back as
     * `Loaded(rules = [], foreignActiveScript = false)` — an ordinary account with no filters.
     * The per-sender gesture was then offered, and confirming it recompiled the entire script
     * from that one rule, deleting whatever the unreadable script held.
     *
     * The flag every writer already refuses is the answer: no new state, no new string.
     */
    @Test fun `an unreadable sterna script warns instead of reporting no rules`() {
        val state = loadedFilterRules("if header :is \"subject\" \"x\" { discard; }", otherActiveScript = false)
        assertEquals("nothing readable, so no rules to show", emptyList<FilterRule>(), state.rules)
        assertTrue(
            "a script we cannot read must refuse the write, not look like an empty one",
            state.foreignActiveScript,
        )
        assertTrue(
            "…and it must say WHICH of the two refusals it is: folded into foreignActiveScript " +
                "alone, the filters screen can only show a sentence about somebody else's script",
            state.scriptUnreadable,
        )
    }

    /**
     * The two causes are told apart on the way out even though they add up on the way in. Without
     * this, "unreadable" is indistinguishable from "another script is active" and the screen keeps
     * announcing the wrong cost.
     */
    @Test fun `only the unreadable script raises the unreadable flag`() {
        assertFalse(
            "another active script says nothing about whether OURS could be read",
            loadedFilterRules(SieveCodec.generate(listOf(rule)), otherActiveScript = true).scriptUnreadable,
        )
        assertFalse(
            "no script at all is not an unreadable one",
            loadedFilterRules(null, otherActiveScript = true).scriptUnreadable,
        )
        assertFalse(
            "a blank script holds nothing to lose",
            loadedFilterRules("  ", otherActiveScript = false).scriptUnreadable,
        )
        assertTrue(
            "broken metadata is unreadable, whatever else is running",
            loadedFilterRules("# STERNA-RULES-V1: {oops\nif true { keep; }", otherActiveScript = true)
                .scriptUnreadable,
        )
    }

    /** Marker present but its JSON broken: same refusal. Half a script is not zero rules. */
    @Test fun `a sterna script with broken metadata warns too`() {
        val state = loadedFilterRules("# STERNA-RULES-V1: {oops\nif true { keep; }", otherActiveScript = false)
        assertEquals(emptyList<FilterRule>(), state.rules)
        assertTrue(state.foreignActiveScript)
    }

    /**
     * ⛔ The witness. A blank script is not unreadable — it holds nothing, so rewriting it loses
     * nothing. Without this, every account whose `sterna` script is an empty stub would be
     * refused the gesture for ever.
     */
    @Test fun `a blank sterna script is zero rules, and no warning`() {
        for (blank in listOf("", "   ", "\n\n")) {
            val state = loadedFilterRules(blank, otherActiveScript = false)
            assertEquals(blank.length.toString(), emptyList<FilterRule>(), state.rules)
            assertFalse("a blank script has nothing to lose: $blank", state.foreignActiveScript)
        }
    }

    /** Unchanged: another active script warns, whatever our own script says. */
    @Test fun `another active script warns even when ours reads fine`() {
        val state = loadedFilterRules(SieveCodec.generate(listOf(rule)), otherActiveScript = true)
        assertEquals(listOf(rule), state.rules)
        assertTrue(state.foreignActiveScript)
    }

    /** The two causes add up rather than cancelling: either one alone is enough. */
    @Test fun `the two refusals add up`() {
        assertTrue(loadedFilterRules("if true { keep; }", otherActiveScript = true).foreignActiveScript)
        assertFalse(loadedFilterRules(null, otherActiveScript = false).foreignActiveScript)
    }

    // ---- the wiring, read out of the shipped source: a LAST RESORT, not the proof ----

    /**
     * Everything above executes the decision; this one cannot. `MailRepository` opens shared
     * preferences in its constructor and needs a JMAP session, so no JVM test builds one — and a
     * decision that is correct but never called is worth nothing. A mutation campaign measured
     * exactly that: replacing the script argument with `null` in `loadFilterRules` left the whole
     * suite green.
     *
     * So the ARGUMENTS are pinned, not the fact that a function of the right name is called: that
     * the blob it downloaded is what gets handed over, and that the answer is no longer assembled
     * on the spot from `parseRules`. A rename fails this loudly rather than quietly passing.
     */
    @Test fun `the repository hands the decision the script it downloaded`() {
        val body = loadFilterRulesBody()
        assertTrue(
            "loadFilterRules must decode the downloaded blob into a script it can hand over",
            body.contains("val script = managed?.let {") && body.contains(".toString(Charsets.UTF_8)"),
        )
        assertTrue(
            "the downloaded script itself must be the argument — not null, not an empty string",
            body.contains("sternaScript = script,"),
        )
        assertTrue(
            "the other-script question must still be asked of the OTHER scripts",
            body.contains("otherActiveScript = scripts.any { it.isActive && it.name != SieveCodec.SCRIPT_NAME }"),
        )
        // Measured: adding `&& it.isActive` to this selection left the whole suite green, and it
        // reopens the very bug above — an unreadable script that is merely INACTIVE is then not
        // seen at all, so the gesture is offered and the save recompiles the script from an empty
        // list. The same edit also reports zero rules for a healthy but inactive script, which is
        // the state the return from holiday leaves every account in.
        assertTrue(
            "the account's own script must be selected BY NAME ALONE, as `val managed = scripts" +
                ".firstOrNull { it.name == SieveCodec.SCRIPT_NAME }`: a script that is not the " +
                "active one is still the script this save would overwrite",
            body.contains("val managed = scripts.firstOrNull { it.name == SieveCodec.SCRIPT_NAME }"),
        )
        assertFalse(
            "the state must come from loadedFilterRules, not be assembled beside it",
            body.contains("FilterRulesState.Loaded("),
        )
    }

    /** The body of `MailRepository.loadFilterRules`, from its signature to the next declaration. */
    private fun loadFilterRulesBody(): String {
        val source = locate("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt").readText()
        val start = source.indexOf("suspend fun loadFilterRules(credentials: AccountCredentials)")
        check(start >= 0) { "MailRepository no longer declares loadFilterRules(credentials)" }
        val end = source.indexOf("\n    /**", start)
        check(end > start) { "no declaration follows loadFilterRules — the slice would be the whole file" }
        return source.substring(start, end)
    }

    /** [relative] resolved from the test's working directory, walking up. */
    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("core/data/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }
}
