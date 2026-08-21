package app.gridlink.security

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.gridlink.ui.gridlink.GridlinkApp
import app.gridlink.ui.theme.GridlinkMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The app-lock overlay: what it says, that it offers an Unlock button, and that nothing unlocks by
 * itself. The biometric prompt needs a FragmentActivity to attach to; the Compose test host is a
 * plain ComponentActivity, so the prompt is never raised here and the button is a safe tap: the
 * overlay must stay up and `onUnlocked` must stay uncalled. JVM-hosted under Robolectric, no device.
 */
@RunWith(RobolectricTestRunner::class)
class LockScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private var unlocks = 0

    private fun show() {
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                LockScreen(onUnlocked = { unlocks++ })
            }
        }
    }

    @Test
    fun saysItIsLocked_andOffersUnlock() {
        show()
        rule.onNodeWithText("Gridlink is locked").assertExists()
        rule.onNodeWithText("Unlock to read your mail.").assertExists()
        rule.onNode(hasText("Unlock") and hasClickAction()).assertIsEnabled()
        assertEquals(0, unlocks)
    }

    @Test
    fun unlockWithoutAPromptHost_staysLocked() {
        show()
        rule.onNode(hasText("Unlock") and hasClickAction()).performClick()
        rule.onNodeWithText("Gridlink is locked").assertExists()
        assertEquals(0, unlocks)
    }
}
