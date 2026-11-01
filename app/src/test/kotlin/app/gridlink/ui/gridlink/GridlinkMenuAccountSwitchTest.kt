package app.gridlink.ui.gridlink

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.gridlink.ui.theme.GridlinkMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GridlinkMenuAccountSwitchTest {
    @get:Rule
    val rule = createComposeRule()

    private val sync = GridlinkSyncState.SYNCED

    // By its label: the panel's own surface is clickable too, so a bare click check proves nothing.
    private val switchAction = SemanticsMatcher("offers Switch account") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == "Switch account"
    }

    private fun setPanel(others: List<GridlinkMenuAccount>, onSwitch: (String) -> Unit = {}) {
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                GridlinkMenuPanel(
                    account = "me@example.com",
                    sync = sync,
                    mode = GridlinkMode.DAY,
                    followingClock = false,
                    onSelectMode = {},
                    onSelect = {},
                    onDismiss = {},
                    otherAccounts = others,
                    onSwitchAccount = onSwitch,
                )
            }
        }
    }

    @Test
    fun `tapping the address lists the other accounts and a pick switches to it`() {
        val picked = mutableListOf<String>()
        setPanel(
            listOf(
                GridlinkMenuAccount("b", "b@example.com", "b@example.com"),
                GridlinkMenuAccount("c", "Work", "c@example.com"),
            ),
            picked::add,
        )
        rule.onAllNodesWithText("b@example.com").assertCountEquals(0)
        rule.onAllNodes(switchAction).assertCountEquals(1)

        rule.onNodeWithText("me@example.com").performClick()
        // Once, not twice: a row whose name IS its address has no second line.
        rule.onAllNodesWithText("b@example.com").assertCountEquals(1)
        rule.onNodeWithText("c@example.com").assertExists()

        rule.onNodeWithText("Work").performClick()
        assertEquals(listOf("c"), picked)
    }

    @Test
    fun `one account leaves the address as a plain statement`() {
        setPanel(emptyList())
        rule.onAllNodes(switchAction).assertCountEquals(0)
    }
}
