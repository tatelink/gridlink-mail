package app.gridlink.ui.gridlink

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.gridlink.ui.theme.GridlinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZonedDateTime

/**
 * The composer, driven by tap and keyboard with every callback the host wires recorded: the
 * contract between this screen and its caller. Recipients chip from a typed address on its
 * separator, from the book's suggestions, or from the "Not in your contacts" row; Send hands back
 * the request that would reopen this exact composer (a typed, un-chipped address folded in); Close
 * hands back null for an untouched or emptied-again draft and the edits otherwise; the schedule
 * sheet's presets report the moment they name; a reply draft draws its quote and its attachments.
 * Sending is never refused here (the host's sender owns the refusals and hands them back as
 * [GridlinkComposeScreen]'s `error`), so an empty composer still reports an empty draft.
 *
 * Outside the real application: the book is the sample book, there is no signature store and no
 * device contacts, which is what the screen is documented to tolerate. The file picker cannot
 * return a file under Robolectric, so attachments are seeded through the draft. JVM-hosted, no
 * device.
 */
@RunWith(RobolectricTestRunner::class)
class GridlinkComposeScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val closed = mutableListOf<GridlinkComposeRequest?>()
    private val sent = mutableListOf<GridlinkComposeRequest>()
    private val scheduled = mutableListOf<Pair<GridlinkComposeRequest, Long>>()

    private fun show(
        draft: GridlinkComposeDraft = GridlinkComposeDraft.Fresh,
        // No field focused, so the bottom bar (Attach, the large Send) is on screen; the default
        // focus lands in TO and swaps it for the header's Send.
        initialFocus: GridlinkComposeField = GridlinkComposeField.NONE,
        initiallyScheduling: Boolean = false,
        error: String? = null,
    ) {
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                GridlinkComposeScreen(
                    onClose = { closed += it },
                    onSend = { sent += it },
                    onSchedule = { request, millis -> scheduled += request to millis },
                    draft = draft,
                    initialFocus = initialFocus,
                    initiallyScheduling = initiallyScheduling,
                    error = error,
                )
            }
        }
    }

    // The three editors have no labels of their own; they sit in composition order.
    private fun toField() = rule.onAllNodes(hasSetTextAction())[0]
    private fun subjectField() = rule.onAllNodes(hasSetTextAction())[1]
    private fun messageField() = rule.onAllNodes(hasSetTextAction())[2]

    /** Header Send and bottom Send swap with focus, through an animation; let it finish first. */
    private fun tapSend() {
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Send").performClick()
    }

    @Test
    fun fresh_showsAnEmptyComposer_withAttachAndSendBelow() {
        show()
        rule.onNodeWithText("Compose").assertExists()
        rule.onNodeWithContentDescription("Close").assertExists()
        rule.onNodeWithText("TO").assertExists()
        rule.onNodeWithText("SUBJECT").assertExists()
        rule.onNodeWithText("Message").assertExists()
        rule.onNodeWithContentDescription("Attach a file").assertExists()
        rule.onNodeWithContentDescription("Send").assertExists()
        rule.onNodeWithText("Not in your contacts").assertDoesNotExist()
    }

    @Test
    fun sendWithNothingTyped_stillReportsAnEmptyDraft_theHostRefuses() {
        show()
        tapSend()
        assertEquals(1, sent.size)
        val draft = sent.single().draft
        assertTrue(draft.recipients.isEmpty())
        assertEquals("", draft.subject)
        assertEquals("", draft.body)
        assertTrue("send never closes by itself", closed.isEmpty())
    }

    @Test
    fun typedAddress_chipsOnItsSeparator_andTheChipCanBeRemoved() {
        show()
        toField().performTextInput("b@gridlink.me ")
        rule.onNodeWithContentDescription("Remove b@gridlink.me").assertExists()
        rule.onNodeWithText("b@gridlink.me").assertExists()
        rule.onNodeWithText("Not in your contacts").assertDoesNotExist()

        rule.onNodeWithContentDescription("Remove b@gridlink.me").performClick()
        rule.onNodeWithContentDescription("Remove b@gridlink.me").assertDoesNotExist()
    }

    @Test
    fun typedAddressStillInTheField_isFoldedIntoSend_withTheQueryCleared() {
        show()
        toField().performTextInput("b@gridlink.me")
        subjectField().performTextInput("Schedule")
        tapSend()
        val request = sent.single()
        assertEquals(listOf("b@gridlink.me"), request.draft.recipients.map { it.email })
        assertEquals("", request.draft.recipientQuery)
        assertEquals("Schedule", request.draft.subject)
        assertEquals("Compose", request.draft.title)
    }

    @Test
    fun partialName_offersTheBooksWordPrefixMatches_andATapChipsOne() {
        show()
        toField().performTextInput("ma")
        rule.onNodeWithText("Malcolm Bexley").assertExists()
        rule.onNodeWithText("Thea Maddox").assertExists()
        rule.onNodeWithText("Marden Halloway").assertExists()
        rule.onNodeWithText("m.bexley@gridlink.me").assertExists()

        rule.onNodeWithText("Malcolm Bexley").performClick()
        rule.onNodeWithContentDescription("Remove Malcolm Bexley").assertExists()
        rule.onNodeWithText("M. Bexley").assertExists()
        // The query is spent on the chip, so the other suggestions go.
        rule.onNodeWithText("Thea Maddox").assertDoesNotExist()
    }

    @Test
    fun unknownAddress_isOfferedAsNotInContacts_andATapChipsIt() {
        show()
        toField().performTextInput("zz@gridlink.me")
        rule.onNodeWithText("Not in your contacts").assertExists()
        rule.onNode(hasText("zz@gridlink.me") and hasText("Not in your contacts")).performClick()
        rule.onNodeWithContentDescription("Remove zz@gridlink.me").assertExists()
        rule.onNodeWithText("Not in your contacts").assertDoesNotExist()
    }

    @Test
    fun closeUntouched_reportsNull() {
        show()
        rule.onNodeWithContentDescription("Close").performClick()
        assertEquals(1, closed.size)
        assertNull(closed.single())
    }

    @Test
    fun closeAfterAnEdit_carriesTheEdit_andEmptiedAgainReportsNull() {
        show()
        subjectField().performTextInput("Hello")
        messageField().performTextInput("First line.")
        rule.onNodeWithContentDescription("Close").performClick()
        val kept = closed.single()
        assertNotNull(kept)
        assertEquals("Hello", kept!!.draft.subject)
        assertEquals("First line.", kept.draft.body)

        // Emptied again: a fresh draft that says nothing is nothing worth keeping.
        subjectField().performTextClearance()
        messageField().performTextClearance()
        rule.onNodeWithContentDescription("Close").performClick()
        assertEquals(2, closed.size)
        assertNull(closed[1])
    }

    @Test
    fun aRefusalFromTheHost_isShownOnTheComposer() {
        show(error = "Add someone to send this to.")
        rule.onNodeWithText("Add someone to send this to.").assertIsDisplayed()
    }

    @Test
    fun scheduling_offersThePresets_andTomorrowReportsItsMoment() {
        show(initiallyScheduling = true)
        rule.onNodeWithText("SEND LATER").assertExists()
        rule.onNodeWithText("Tomorrow").assertExists()
        rule.onNodeWithText("7:00 AM").assertExists()
        rule.onNodeWithText("Pick a time").assertExists()

        rule.onNodeWithText("Tomorrow").performClick()
        val (request, millis) = scheduled.single()
        val expected = gridlinkSchedulePresets(ZonedDateTime.now()).first { it.label == "Tomorrow" }
        assertEquals(expected.millis, millis)
        assertEquals(GridlinkComposeDraft.Fresh.title, request.draft.title)
        assertTrue("schedule never closes by itself", closed.isEmpty())
    }

    @Test
    fun replyDraft_drawsItsQuoteAndAttachment_andRemoveDropsTheAttachment() {
        show(draft = GridlinkComposeDraft.Reply)
        rule.onNodeWithText("Reply").assertExists()
        rule.onNodeWithText(GridlinkComposeDraft.Reply.quoted!!.attribution).assertExists()
        rule.onNodeWithText(GridlinkComposeDraft.Reply.quoted!!.text).assertExists()
        rule.onNodeWithText("wk32_schedule_1155.pdf").assertExists()
        rule.onNodeWithText("84 KB").assertExists()
        messageField().assert(hasText(GridlinkComposeDraft.Reply.body))

        rule.onNodeWithContentDescription("Remove wk32_schedule_1155.pdf").performClick()
        rule.onNodeWithText("wk32_schedule_1155.pdf").assertDoesNotExist()
        tapSend()
        assertTrue(sent.single().draft.attachments.isEmpty())
        assertEquals("Reply", sent.single().draft.title)
    }

    @Test
    fun focusInTheBody_showsTheFormatToolbar_andMovesSendToTheHeader() {
        show(initialFocus = GridlinkComposeField.BODY)
        rule.waitForIdle()
        listOf(
            "Bold", "Italic", "Underline", "Strikethrough", "Bulleted list", "Numbered list",
            "Quote", "Heading", "Subheading", "Link",
        ).forEach { rule.onNodeWithContentDescription(it).assertExists() }
        rule.onNodeWithContentDescription("Send").assertExists()
        rule.onNodeWithContentDescription("Attach a file").assertDoesNotExist()
    }
}
