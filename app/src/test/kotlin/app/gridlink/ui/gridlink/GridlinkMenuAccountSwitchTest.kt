package app.gridlink.ui.gridlink

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
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

    private fun setPanel(
        others: List<GridlinkMenuAccount>,
        onSwitch: (String) -> Unit = {},
        protocol: String = "",
    ) {
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
                    protocol = protocol,
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

    @Test
    fun `the status line names the account own protocol`() {
        setPanel(emptyList(), protocol = "IMAP")
        rule.onNodeWithText("IMAP · ", substring = true).assertExists()
        rule.onAllNodesWithText("JMAP", substring = true).assertCountEquals(0)
    }

    @Test
    fun `an unknown protocol claims none`() {
        assertEquals("Synced", gridlinkSyncLine("", "Synced"))
        assertEquals("JMAP · Synced", gridlinkSyncLine("JMAP", "Synced"))
    }

    @Test
    fun `the sync age reads from resources with a plural per unit`() {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        val minute = 60_000L
        assertEquals("just now", gridlinkSyncAge(res, 0L))
        assertEquals("just now", gridlinkSyncAge(res, -3 * minute))
        assertEquals("1 min ago", gridlinkSyncAge(res, minute))
        assertEquals("5 min ago", gridlinkSyncAge(res, 5 * minute))
        assertEquals("1 hr ago", gridlinkSyncAge(res, 60 * minute))
        assertEquals("2 d ago", gridlinkSyncAge(res, 2 * 24 * 60 * minute))
    }

    @Test
    fun `the sync sentence reads from resources`() {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        val now = 10_000_000L
        val fiveMinutesAgo = now - 5 * 60_000L
        assertEquals("Syncing all accounts", gridlinkSyncSentence(res, GridlinkSyncState.SYNCING, fiveMinutesAgo, now))
        assertEquals("Synced", gridlinkSyncSentence(res, GridlinkSyncState.SYNCED, null, now))
        assertEquals("Synced 5 min ago", gridlinkSyncSentence(res, GridlinkSyncState.SYNCED, fiveMinutesAgo, now))
        assertEquals("Offline · never synced", gridlinkSyncSentence(res, GridlinkSyncState.OFFLINE, null, now))
        assertEquals(
            "Offline · synced 5 min ago",
            gridlinkSyncSentence(res, GridlinkSyncState.OFFLINE, fiveMinutesAgo, now),
        )
    }
}
