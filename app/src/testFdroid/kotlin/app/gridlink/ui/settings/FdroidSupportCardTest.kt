package app.gridlink.ui.settings

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
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
 * The F-Droid build's tip jar: exactly the Ko-fi card it had before the store flavors split, the
 * same title, paragraph and one pill, and the pill opens Tate's Ko-fi through the hub's opener.
 * JVM-hosted under Robolectric, no device.
 */
@RunWith(RobolectricTestRunner::class)
class FdroidSupportCardTest {

    @get:Rule
    val rule = createComposeRule()

    private val opened = mutableListOf<String>()

    private fun show() {
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                StoreSupportCard(onOpenUrl = { opened += it })
            }
        }
    }

    @Test
    fun showsTheKofiCard() {
        show()
        rule.onNodeWithText("Support Gridlink").assertExists()
        rule.onNodeWithText(
            "Gridlink is free, has no ads and no trackers, and is built by one person. " +
                "A coffee keeps it going.",
        ).assertExists()
        // The pill is the clickable part, not the card's heading.
        rule.onNodeWithText("Buy me a coffee on Ko-fi").assert(hasClickAction())
    }

    @Test
    fun thePillOpensKofi() {
        show()
        rule.onNodeWithText("Buy me a coffee on Ko-fi").performClick()
        assertEquals(listOf("https://ko-fi.com/tatelink"), opened)
    }
}
