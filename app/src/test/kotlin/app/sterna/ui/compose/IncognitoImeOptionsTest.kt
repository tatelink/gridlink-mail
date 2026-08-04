package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one decision the incognito-keyboard fix makes, EXECUTED (#120): which bit is added to the
 * `EditorInfo.imeOptions` a composer field just filled in, and how.
 *
 * The expected values are written as literals on purpose. Asserting
 * `incognitoImeOptions(x) == x or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING` would be the
 * production expression copied into the test: it would stay green with the wrong constant, since
 * both sides would name the same wrong thing. 0x01000000 is the value of
 * `IME_FLAG_NO_PERSONALIZED_LEARNING` in `android.jar` (platform 36, `javap -constants`:
 * 16777216), and 6 is `IME_ACTION_DONE` — the action the recipient rows really set
 * (`ComposeScreen.kt`, the `KeyboardOptions` of `RecipientChipsField`).
 *
 * WHAT THIS DOES NOT PROVE: that the flag ever reaches a keyboard. This is an `Int -> Int`
 * function. That the interceptor is installed, that a `BasicTextField` session goes through it,
 * and that the IME honours the bit are all outside any JVM test — they close on a bench run in a
 * MINIFIED RELEASE build, reading a keyboard's incognito indicator in the composer and checking it
 * is absent in search.
 */
class IncognitoImeOptionsTest {

    /** The bit itself, as the platform defines it. Nothing here recomputes it. */
    private val noPersonalizedLearning = 0x01000000

    @Test fun `the bit added is no-personalized-learning`() {
        assertEquals(noPersonalizedLearning, incognitoImeOptions(0))
    }

    @Test fun `the field's own ime action survives`() {
        // The recipient rows carry ImeAction.Done so Enter turns the address into a chip (#83).
        // An assignment instead of an `or` would erase it and Enter would fold the keyboard away.
        val done = 6
        assertEquals(0x01000006, incognitoImeOptions(done))
        assertEquals(done, incognitoImeOptions(done) and 0xff)
    }

    @Test fun `every other flag the field set survives`() {
        // A full house of unrelated EditorInfo flags plus an action: all of them must come back.
        val busy = 0x40000000 or 0x10000000 or 0x02000000 or 5
        assertEquals(busy or noPersonalizedLearning, incognitoImeOptions(busy))
        assertTrue((incognitoImeOptions(busy) and busy) == busy)
    }

    @Test fun `asking twice asks for the same thing`() {
        // The interceptor wraps a session that may be restarted; adding the bit again is a no-op.
        val once = incognitoImeOptions(6)
        assertEquals(once, incognitoImeOptions(once))
    }

    @Test fun `nothing but that one bit is added`() {
        val before = 6
        assertEquals(noPersonalizedLearning, incognitoImeOptions(before) xor before)
    }
}
