package app.gridlink.ui.gridlink

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.gridlink.ui.theme.GridlinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the embedded form does with its paneFloor spacer once the keyboard is up.
 *
 * The spacer exists to start the form's glass on the same line as the list column's panel beside
 * it, and unfolded with the keys up that alignment was costing the form most of the window it had
 * left. So it is now paid for out of whatever the keyboard is NOT taking. Held here: the full floor
 * with no keyboard, the floor minus the keyboard for one shorter than it, and nothing at all (never
 * a negative, never a still-shrinking title) for any keyboard taller than the floor.
 *
 * 🔴 This is the only test in the suite that drives real window insets, so the mechanism is written
 * out: Compose reads the ime inset from an `OnApplyWindowInsetsListener` the composition installs on
 * the host view, so a test moves it by DISPATCHING an inset down the view tree, not by setting any
 * state. Robolectric serves that faithfully; what it cannot serve is a keyboard actually opening, so
 * the animation between these three resting points is not covered here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h1280dp")
class GridlinkFormKeyboardTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show() {
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                CompositionLocalProvider(LocalGridlinkPaneHeaderHeight provides PANE_FLOOR) {
                    GridlinkFormScreen(
                        title = TITLE,
                        onClose = {},
                        confirmLabel = "Save",
                        onConfirm = {},
                        confirmEnabled = true,
                        embedded = true,
                    ) {
                        Text("A field would go here")
                    }
                }
            }
        }
    }

    /** Raise a keyboard [height] tall, or lower it entirely at zero. */
    private fun keyboard(height: Dp) {
        val px = with(rule.density) { height.roundToPx() }
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, px))
            .setVisible(WindowInsetsCompat.Type.ime(), px > 0)
            .build()
        rule.runOnUiThread {
            val root: View = rule.activity.window.decorView
            ViewCompat.dispatchApplyWindowInsets(root, insets)
        }
        rule.waitForIdle()
    }

    /**
     * Where the form's own title sits, which is the first thing inside the glass and so moves with
     * the spacer above it. Read rather than the glass itself because the panel is a plain Box with
     * no semantics of its own to find.
     */
    private fun titleTop(): Dp = rule.onNodeWithText(TITLE).getUnclippedBoundsInRoot().top

    @Test
    fun withNoKeyboard_theFormKeepsItsFullAlignmentSpacer() {
        show()
        val floored = titleTop()
        keyboard(PANE_FLOOR)
        val raised = titleTop()
        // Not an absolute position: the glass also carries its own top padding, and the assertion
        // that matters is the DIFFERENCE, which is the whole spacer.
        assertEquals(PANE_FLOOR.value, (floored - raised).value, 1f)
    }

    @Test
    fun aKeyboardShorterThanTheSpacer_takesOnlyItsOwnHeight() {
        show()
        val floored = titleTop()
        keyboard(PANE_FLOOR / 2)
        assertEquals((PANE_FLOOR / 2).value, (floored - titleTop()).value, 1f)
    }

    @Test
    fun aKeyboardTallerThanTheSpacer_stopsAtNothingLeft() {
        show()
        keyboard(PANE_FLOOR)
        val exhausted = titleTop()
        // 🔴 The floor. A keyboard is routinely taller than the pane's chrome, so this is the
        // ordinary case, not an edge one, and what it holds is that the subtraction stops at zero
        // instead of going on to drag the title up out of the window.
        keyboard(PANE_FLOOR * 4)
        assertEquals(exhausted.value, titleTop().value, 1f)
        assertTrue("the title should still be on screen", titleTop().value >= 0f)
    }

    @Test
    fun loweringTheKeyboard_givesTheSpacerBack() {
        show()
        val floored = titleTop()
        keyboard(PANE_FLOOR * 2)
        keyboard(0.dp)
        assertEquals(floored.value, titleTop().value, 1f)
    }

    private companion object {
        const val TITLE = "New contact"

        /** A believable pane floor: the chrome row's height on the Fold's inner screen. */
        val PANE_FLOOR = 96.dp
    }
}
