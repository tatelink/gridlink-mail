package app.gridlink.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * The licence page: the bundled GPL text arrives from the APK's assets and is shown verbatim under
 * the "License" title, and Back leaves. JVM-hosted under Robolectric, no device.
 */
@RunWith(RobolectricTestRunner::class)
class LicenseScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private var backs = 0

    private fun show() {
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                LicenseScreen(onBack = { backs++ })
            }
        }
    }

    @Test
    fun showsTheBundledGpl_verbatim() {
        show()
        rule.onNodeWithText("License").assertExists()
        // Read off the IO dispatcher, so polled rather than asserted at once.
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("GNU GENERAL PUBLIC LICENSE", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Version 3, 29 June 2007", substring = true).assertExists()
    }

    @Test
    fun back_leavesOnce() {
        show()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }
}
