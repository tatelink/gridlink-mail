package app.sterna.ui.compose

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession

/**
 * Ask the keyboard not to learn from a message being written (#120).
 *
 * A keyboard builds its personal dictionary and its predictions from what it is given to type.
 * Mail is where the words worth not learning are: addresses, names, a password sent to a
 * colleague, a medical term. `IME_FLAG_NO_PERSONALIZED_LEARNING` is the one documented way to say
 * so — the same flag FairEmail's "Incognito keyboard" option sets. It is a REQUEST: an IME is
 * free to ignore it, and most honour it (AOSP, Gboard, OpenBoard). API 26+, and `minSdk = 26`, so
 * there is no version guard to write.
 *
 * Unconditional, no setting: a guarantee you have to switch on is one you can forget to switch on.
 *
 * WHY AN INTERCEPTOR AND NOT `KeyboardOptions`. Compose (BOM 2025.01.00) exposes no `imeOptions`
 * flags at all: `KeyboardOptions` has capitalization / autoCorrect / keyboardType / imeAction /
 * platformImeOptions, and `PlatformImeOptions` only carries `privateImeOptions`, a per-IME
 * proprietary string that never reaches `EditorInfo.imeOptions`. The only seam that hands us the
 * real `EditorInfo` is [InterceptPlatformTextInput]: it wraps the session every `BasicTextField`
 * in the subtree opens, so we can let the field fill the `EditorInfo` as it always did and then
 * add one bit. Nothing existing is rebuilt — in particular the recipients' `ImeAction.Done` and
 * the body's deliberate absence of an ime action are untouched (#83, #26).
 *
 * SCOPE. Wrapped around the composer only, never the application root. Search wants the keyboard's
 * memory (recalling a contact's name is the point), passwords are already excluded by the IME
 * itself via `KeyboardType.Password`, and settings text is not "a message being written".
 *
 * WHERE IT IS APPLIED: at the single navigation route that mounts `ComposeScreen`
 * (`SternaApp.kt`), not inside `ComposeScreen` itself. The scope is the same — what this installs
 * is a `CompositionLocal` read at each text field's own modifier node, so it covers the whole
 * subtree either way — and wrapping the call keeps the composer's 1500 lines untouched.
 * `ComposeScreenIncognitoTest` holds the two halves that placement splits: EVERY call site of
 * `ComposeScreen` is wrapped, and every field of `ComposeScreen.kt` belongs to that screen.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun IncognitoKeyboard(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(INCOGNITO_INTERCEPTOR, content)
}

/**
 * The interceptor itself, a top-level value rather than something built inside the composition.
 *
 * It captures NOTHING: it wraps whichever request it is handed and adds one constant bit, so every
 * instance would be interchangeable. Making it a constant is not a micro-optimisation — a fresh
 * instance on each recomposition would be a new value for the interceptor chain, and the chain
 * restarts the input session when its interceptor changes (`ChainedPlatformTextInputInterceptor
 * .updateInterceptor`). One identity, for the life of the process, removes the question.
 *
 * `internal` rather than `private` so a JVM test can RUN it: what it hands to
 * [PlatformTextInputSession.startInputMethod] is the whole fix, and it is the one thing a source
 * lint reads without ever proving (`startInputMethod(request)` instead of `startInputMethod(
 * incognito)` leaves every pinned line in place, in the right order, and cancels the fix).
 */
@OptIn(ExperimentalComposeUiApi::class)
internal val INCOGNITO_INTERCEPTOR = object : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession,
    ): Nothing {
        val incognito = object : PlatformTextInputMethodRequest {
            override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                // The delegate WRITES outAttributes (imeOptions, inputType, the action of the
                // field that asked). So it runs first and our bit is added after — reversing the
                // two lines would silently drop the flag.
                val connection = request.createInputConnection(outAttributes)
                outAttributes.imeOptions = incognitoImeOptions(outAttributes.imeOptions)
                return connection
            }
        }
        return nextHandler.startInputMethod(incognito)
    }
}

/**
 * The decision itself, out here so a JVM test can EXECUTE it rather than read it: add the
 * no-personalized-learning bit to whatever the field already asked for.
 *
 * `or`, never `=`. The value handed to us already carries the field's own action
 * (`IME_ACTION_DONE` on the recipient rows, `IME_ACTION_UNSPECIFIED` on the body) plus whatever
 * `EditorInfo` flags Compose set; an assignment would wipe them and take #83's Enter-makes-a-chip
 * with it.
 *
 * Kotlin inlines Java `static final int` constants at compile time, so this function does not load
 * `android.view.inputmethod.EditorInfo` at runtime and a plain JVM test can call it.
 */
internal fun incognitoImeOptions(imeOptions: Int): Int =
    imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
