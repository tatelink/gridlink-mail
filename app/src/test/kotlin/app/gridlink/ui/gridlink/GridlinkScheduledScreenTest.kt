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
 * The Scheduled list: sends that are waiting to go. An empty account says so, rows come soonest
 * first whatever order the flow handed them in (the row order IS the send order), and the cancel
 * on a row hands back THAT row's id. JVM-hosted under Robolectric, no device.
 */
@RunWith(RobolectricTestRunner::class)
class GridlinkScheduledScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val cancelled = mutableListOf<Long>()
    private var closed = 0

    private fun show(content: GridlinkScheduledContent) {
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                GridlinkScheduledScreen(
                    onClose = { closed++ },
                    scheduled = content,
                    onCancel = { cancelled += it },
                )
            }
        }
    }

    private fun send(id: Long, to: String, subject: String, hoursFromNow: Long) = GridlinkScheduledSend(
        id = id,
        to = to,
        subject = subject,
        sendAtMillis = NOW.plusHours(hoursFromNow).toInstant().toEpochMilli(),
    )

    @Test
    fun nothingWaiting_saysSo_ratherThanDrawingTheSample() {
        show(GridlinkScheduledContent(items = emptyList()))
        rule.onNodeWithText("Scheduled").assertExists()
        rule.onNodeWithText("Nothing waiting").assertExists()
        rule.onAllNodesWithContentDescription("Cancel send").assertCountEquals(0)
    }

    @Test
    fun rows_comeSoonestFirst_andNameTheirRecipientAndHour() {
        val late = send(7, "board@acme.example", "Q3 deck", hoursFromNow = 24 * 5)
        val soon = send(3, "avery@gridlink.me", "Groceries", hoursFromNow = 2)
        val mid = send(5, "vendor@acme.example", "PO 4471", hoursFromNow = 30)
        show(GridlinkScheduledContent(items = listOf(late, soon, mid)))

        val tops = listOf("Groceries", "PO 4471", "Q3 deck")
            .map { rule.onNodeWithText(it).getUnclippedBoundsInRoot().top }
        assertTrue("expected soonest first, got tops $tops", tops[0] < tops[1] && tops[1] < tops[2])
        rule.onNodeWithText("To avery@gridlink.me").assertExists()
        rule.onNodeWithText(gridlinkScheduledLabel(soon.sendAtMillis)).assertExists()
        rule.onNodeWithText(gridlinkScheduledLabel(late.sendAtMillis)).assertExists()
    }

    @Test
    fun cancelSend_handsBackTheIdOfTheRowTapped() {
        val first = send(3, "avery@gridlink.me", "Groceries", hoursFromNow = 2)
        val second = send(5, "vendor@acme.example", "PO 4471", hoursFromNow = 30)
        show(GridlinkScheduledContent(items = listOf(first, second)))

        rule.onAllNodesWithContentDescription("Cancel send")[1].performClick()
        assertEquals(listOf(5L), cancelled)
    }

    @Test
    fun blankSubject_stillReadsAsARow() {
        show(GridlinkScheduledContent(items = listOf(send(1, "x@y.example", "", hoursFromNow = 1))))
        rule.onNodeWithText("(No subject)").assertExists()
    }

    @Test
    fun back_closesOnce() {
        show(GridlinkScheduledContent(items = emptyList()))
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, closed)
    }

    private companion object {
        val NOW: ZonedDateTime = ZonedDateTime.now()
    }
}
