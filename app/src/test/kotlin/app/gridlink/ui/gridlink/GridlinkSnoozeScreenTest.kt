package app.gridlink.ui.gridlink

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.gridlink.ui.theme.GridlinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZonedDateTime

/**
 * The Snoozed list and the sheet that puts a message into it, driven by tap.
 *
 * The presets' arithmetic and the row labels' wording are pinned in [GridlinkSnoozeTest]; this is
 * the screen: an empty account says so, rows come soonest first whatever order the flow handed them
 * in, the one action on a row hands back THAT row's key, and the sheet reports the millis of the
 * preset tapped. JVM-hosted under Robolectric, no device.
 */
@RunWith(RobolectricTestRunner::class)
class GridlinkSnoozeScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val woken = mutableListOf<GridlinkSnoozedKey>()
    private var closed = 0

    private fun show(content: GridlinkSnoozedContent) {
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                GridlinkSnoozeScreen(onClose = { closed++ }, snoozed = content, onWake = { woken += it })
            }
        }
    }

    private fun item(id: String, sender: String, subject: String, hoursFromNow: Long) = GridlinkSnoozedItem(
        key = GridlinkSnoozedKey("acct", id),
        sender = sender,
        subject = subject,
        untilMillis = NOW.plusHours(hoursFromNow).toInstant().toEpochMilli(),
    )

    @Test
    fun nothingSnoozed_saysSo_ratherThanDrawingTheSample() {
        show(GridlinkSnoozedContent(items = emptyList()))
        rule.onNodeWithText("Snoozed").assertExists()
        rule.onNodeWithText("Nothing snoozed").assertExists()
        rule.onAllNodesWithContentDescription("Wake now").assertCountEquals(0)
    }

    @Test
    fun rows_comeSoonestFirst_whateverOrderTheyArrivedIn() {
        val nextWeek = item("late", "Payroll", "Quarter close", hoursFromNow = 24 * 6)
        val tonight = item("soon", "Avery", "Dinner?", hoursFromNow = 3)
        val tomorrow = item("mid", "Vendor", "Invoice 118", hoursFromNow = 26)
        show(GridlinkSnoozedContent(items = listOf(nextWeek, tonight, tomorrow)))

        val tops = listOf("Dinner?", "Invoice 118", "Quarter close")
            .map { rule.onNodeWithText(it).getUnclippedBoundsInRoot().top }
        assertTrue("expected soonest first, got tops $tops", tops[0] < tops[1] && tops[1] < tops[2])
        // Each row says when it comes back, in the same words the label function uses.
        rule.onNodeWithText(gridlinkSnoozeLabel(tonight.untilMillis)).assertExists()
        rule.onNodeWithText(gridlinkSnoozeLabel(nextWeek.untilMillis)).assertExists()
    }

    @Test
    fun wakeNow_handsBackTheKeyOfTheRowTapped() {
        val first = item("a", "Avery", "Dinner?", hoursFromNow = 3)
        val second = item("b", "Vendor", "Invoice 118", hoursFromNow = 26)
        show(GridlinkSnoozedContent(items = listOf(first, second)))

        rule.onAllNodesWithContentDescription("Wake now")[1].performClick()
        assertEquals(listOf(second.key), woken)
    }

    @Test
    fun blankSenderAndSubject_stillReadAsARow() {
        show(GridlinkSnoozedContent(items = listOf(item("x", "", "", hoursFromNow = 1))))
        rule.onNodeWithText("(Unknown sender)").assertExists()
        rule.onNodeWithText("(No subject)").assertExists()
    }

    @Test
    fun back_closesOnce() {
        show(GridlinkSnoozedContent(items = emptyList()))
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun sheet_reportsThePresetTapped_andTheCustomRow() {
        var picked: Long? = null
        var custom = 0
        var dismissed = 0
        val presets = listOf(
            GridlinkPresetTime("Tomorrow", "8:00 AM", 1_000L),
            GridlinkPresetTime("Next week", "Mon 8:00 AM", 2_000L),
        )
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                GridlinkSnoozeSheet(
                    presets = presets,
                    onPick = { picked = it },
                    onPickCustom = { custom++ },
                    onDismiss = { dismissed++ },
                )
            }
        }
        rule.onNodeWithText("SNOOZE UNTIL").assertExists()
        rule.onNodeWithText("8:00 AM").assertExists()
        rule.onNodeWithText("Next week").performClick()
        assertEquals(2_000L, picked)
        rule.onNodeWithText("Pick a date & time").performClick()
        assertEquals(1, custom)
        assertEquals("the sheet leaves closing to its caller", 0, dismissed)
    }

    private companion object {
        val NOW: ZonedDateTime = ZonedDateTime.now()
    }
}
