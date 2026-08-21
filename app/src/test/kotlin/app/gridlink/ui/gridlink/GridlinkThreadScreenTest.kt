package app.gridlink.ui.gridlink

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.gridlink.core.data.settings.ThreadToolbarAction
import app.gridlink.ui.theme.GridlinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The reading screen, driven by tap, with every callback the host wires recorded.
 *
 * The contract under test is the one between this screen and its caller: every control on it maps
 * to exactly one [GridlinkThreadAction] (or one of the named callbacks) and hands that back
 * unchanged, so the host never has to guess what a tap meant. Specifically: Reply is the accent
 * circle; the bar shows what [ThreadToolbarAction] the reader enabled and nothing else, with More
 * opening the rest; the star at the title reports STAR or UNSTAR by the state it moves TO and
 * shows the answer before the mailbox confirms it; Unsubscribe is the one action that is asked
 * about first, so it never reaches the caller on the first tap and reaches it exactly once on
 * confirm; remote images stay blocked until the reader says Show (once, here) or Always (the
 * caller's list); an attachment chip opens or saves THAT file; the invitation's three buttons send
 * their PARTSTAT; the receipt row sends the receipt.
 *
 * The body is a WebView. Under Robolectric it is a shadow that renders nothing and reports no
 * height, which is fine: nothing below reads the body, and the chrome around it is what this
 * screen owns. How the bar splits between slots and sheet is pinned in [GridlinkToolbarLayoutTest]
 * and the unsubscribe header parse in [GridlinkUnsubscribeTest]; this file is the screen over both.
 * JVM-hosted under Robolectric, no device.
 */
@RunWith(RobolectricTestRunner::class)
class GridlinkThreadScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val actions = mutableListOf<GridlinkThreadAction>()
    private var backs = 0

    private fun message(
        body: String = "<p>Your statement for July is attached.</p>",
        unsubscribe: GridlinkUnsubscribe? = null,
        attachments: List<GridlinkAttachment> = emptyList(),
        starred: Boolean = false,
    ) = GridlinkMessage(
        id = "m1",
        sender = "Dalton Energy",
        domain = "dalton-energy.example",
        subject = "Your July statement",
        timestamp = "9:41 AM",
        body = body,
        addressOverride = "billing@dalton-energy.example",
        unsubscribe = unsubscribe,
        attachments = attachments,
        starred = starred,
    )

    private fun show(
        message: GridlinkMessage = message(),
        initiallyConfirmingUnsubscribe: Boolean = false,
        imagesAlwaysAllowed: Boolean = false,
        onAlwaysAllowImages: (Boolean) -> Unit = {},
        onOpenAttachment: ((GridlinkAttachment) -> Unit)? = null,
        onSaveAttachment: ((GridlinkAttachment) -> Unit)? = null,
        toolbarActions: Set<ThreadToolbarAction> = ThreadToolbarAction.DEFAULTS,
        invite: GridlinkInvite? = null,
        onRespondToInvite: ((String) -> Unit)? = null,
        receipt: GridlinkReceipt? = null,
        onSendReceipt: (() -> Unit)? = null,
    ) {
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                GridlinkThreadScreen(
                    message = message,
                    onBack = { backs++ },
                    onAction = { actions += it },
                    initiallyConfirmingUnsubscribe = initiallyConfirmingUnsubscribe,
                    imagesAlwaysAllowed = imagesAlwaysAllowed,
                    onAlwaysAllowImages = onAlwaysAllowImages,
                    onOpenAttachment = onOpenAttachment,
                    onSaveAttachment = onSaveAttachment,
                    toolbarActions = toolbarActions,
                    invite = invite,
                    onRespondToInvite = onRespondToInvite,
                    receipt = receipt,
                    onSendReceipt = onSendReceipt,
                )
            }
        }
    }

    // ---- the header -----------------------------------------------------------------------------

    @Test
    fun header_showsSubjectSenderAndTime_hidesTheAddressUntilAsked_andBackClosesOnce() {
        show()
        rule.onNodeWithText("Your July statement").assertExists()
        rule.onNodeWithText("Dalton Energy").assertExists()
        rule.onNodeWithText("9:41 AM").assertExists()
        // The raw address collapses behind the chevron; one tap on the block opens it.
        rule.onNodeWithText("billing@dalton-energy.example").assertDoesNotExist()
        rule.onNodeWithContentDescription("Show sender details").performClick()
        rule.onNodeWithText("billing@dalton-energy.example").assertExists()
        rule.onNodeWithContentDescription("Hide sender details").assertExists()

        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
        assertTrue("back is not an action", actions.isEmpty())
    }

    // ---- the bar --------------------------------------------------------------------------------

    @Test
    fun reply_isTheAccentCircle_andTheDefaultBarHandsForwardAndArchiveStraightBack() {
        show()
        rule.onNodeWithText("Reply").performClick()
        rule.onNodeWithText("Forward").performClick()
        rule.onNodeWithText("Archive").performClick()
        assertEquals(
            listOf(GridlinkThreadAction.REPLY, GridlinkThreadAction.FORWARD, GridlinkThreadAction.ARCHIVE),
            actions,
        )
        // The defaults overflow, so More is the third slot; what overflowed is not on the bar.
        rule.onNodeWithText("More").assertExists()
        rule.onAllNodesWithText("Reply all").assertCountEquals(0)
        rule.onAllNodesWithText("Junk").assertCountEquals(0)
    }

    @Test
    fun more_opensTheOverflow_andASheetRowHandsBackItsActionAndCloses() {
        show()
        rule.onNodeWithText("More").performClick()
        rule.onNodeWithText("Reply all").assertExists()
        rule.onNodeWithText("Junk").assertExists()
        rule.onNodeWithText("Snooze").assertExists()
        // No unsubscribe header on this message, so no Unsubscribe row.
        rule.onAllNodesWithText("Unsubscribe").assertCountEquals(0)

        rule.onNodeWithText("Snooze").performClick()
        assertEquals(listOf(GridlinkThreadAction.SNOOZE), actions)
        rule.onAllNodesWithText("Snooze").assertCountEquals(0)

        rule.onNodeWithText("More").performClick()
        rule.onNodeWithText("Junk").performClick()
        assertEquals(listOf(GridlinkThreadAction.SNOOZE, GridlinkThreadAction.SPAM), actions)
    }

    @Test
    fun toolbarActions_decideTheBar_threeFitWithoutMore_andNoneLeavesOnlyReply() {
        show(
            toolbarActions = setOf(
                ThreadToolbarAction.DELETE, ThreadToolbarAction.MOVE, ThreadToolbarAction.MARK_UNREAD,
            ),
        )
        rule.onAllNodesWithText("More").assertCountEquals(0)
        rule.onAllNodesWithText("Forward").assertCountEquals(0)
        rule.onAllNodesWithText("Archive").assertCountEquals(0)
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithText("Move").performClick()
        rule.onNodeWithText("Mark unread").performClick()
        assertEquals(
            listOf(GridlinkThreadAction.DELETE, GridlinkThreadAction.MOVE, GridlinkThreadAction.MARK_UNREAD),
            actions,
        )
    }

    @Test
    fun everySwitchOff_drawsNoPillAtAll_butReplyStays() {
        show(toolbarActions = emptySet())
        rule.onAllNodesWithText("More").assertCountEquals(0)
        rule.onAllNodesWithText("Forward").assertCountEquals(0)
        rule.onNodeWithText("Reply").performClick()
        assertEquals(listOf(GridlinkThreadAction.REPLY), actions)
    }

    // ---- the star -------------------------------------------------------------------------------

    @Test
    fun star_reportsTheStateItMovesTo_andShowsItBeforeTheMailboxCatchesUp() {
        show()
        // Labelled with what it will DO, so an unstarred message offers "Star".
        rule.onAllNodesWithContentDescription("Remove star").assertCountEquals(0)
        rule.onNodeWithContentDescription("Star").performClick()
        assertEquals(listOf(GridlinkThreadAction.STAR), actions)
        // message.starred is still false (nothing round-tripped), yet the control already reads lit.
        rule.onNodeWithContentDescription("Remove star").performClick()
        assertEquals(listOf(GridlinkThreadAction.STAR, GridlinkThreadAction.UNSTAR), actions)
        rule.onNodeWithContentDescription("Star").assertExists()
    }

    @Test
    fun aStarredMessage_offersRemoveStar_andTheBarsStarSwitchAgrees() {
        show(message = message(starred = true), toolbarActions = setOf(ThreadToolbarAction.STAR))
        // Title control and the bar's own Star slot both name the move away from starred.
        rule.onAllNodesWithContentDescription("Remove star").assertCountEquals(2)
        rule.onNodeWithText("Remove star").performClick()
        assertEquals(listOf(GridlinkThreadAction.UNSTAR), actions)
    }

    // ---- unsubscribe ----------------------------------------------------------------------------

    @Test
    fun unsubscribe_isAskedFirst_cancelSendsNothing_confirmSendsItOnce() {
        val method = GridlinkUnsubscribe(mailto = "mailto:leave@dalton-energy.example")
        show(message = message(unsubscribe = method))
        // A contextual method forces the More door open even though it holds no bar slot.
        rule.onNodeWithText("More").performClick()
        rule.onNodeWithText("Unsubscribe").performClick()
        assertTrue("the first tap must only ask", actions.isEmpty())
        rule.onNodeWithText("Unsubscribe from Dalton Energy?").assertExists()
        rule.onNodeWithText(gridlinkUnsubscribeWarning(message(unsubscribe = method))).assertExists()

        rule.onNodeWithText("Cancel").performClick()
        rule.onAllNodesWithText("Unsubscribe from Dalton Energy?").assertCountEquals(0)
        assertTrue("cancel sends nothing", actions.isEmpty())

        rule.onNodeWithText("More").performClick()
        rule.onNodeWithText("Unsubscribe").performClick()
        // The sheet has closed, so the only "Unsubscribe" left is the dialog's confirm.
        rule.onNodeWithText("Unsubscribe").performClick()
        assertEquals(listOf(GridlinkThreadAction.UNSUBSCRIBE), actions)
        rule.onAllNodesWithText("Unsubscribe from Dalton Energy?").assertCountEquals(0)
    }

    @Test
    fun initiallyConfirmingUnsubscribe_opensTheQuestionAtOnce_withNothingSentYet() {
        val method = GridlinkUnsubscribe(httpUrl = "https://dalton-energy.example/u/9f2")
        show(message = message(unsubscribe = method), initiallyConfirmingUnsubscribe = true)
        rule.onNodeWithText("Unsubscribe from Dalton Energy?").assertExists()
        assertTrue(actions.isEmpty())
        rule.onNodeWithText("Unsubscribe").performClick()
        assertEquals(listOf(GridlinkThreadAction.UNSUBSCRIBE), actions)
    }

    // ---- remote images --------------------------------------------------------------------------

    @Test
    fun remoteImages_areBlockedWithABanner_showIsOnce_alwaysGoesToTheCaller() {
        val allowed = mutableListOf<Boolean>()
        val tracked = """<p>Hi</p><img src="https://dalton-energy.example/open.gif" width="1">"""
        show(message = message(body = tracked), onAlwaysAllowImages = { allowed += it })
        val banner = "Images blocked so Dalton Energy can't tell you opened this."
        rule.onNodeWithText(banner).assertExists()

        // Always is the caller's list; the screen only reports the grant and keeps drawing what it
        // was told until imagesAlwaysAllowed comes back true.
        rule.onNodeWithText("Always").performClick()
        assertEquals(listOf(true), allowed)
        rule.onNodeWithText(banner).assertExists()

        // Show is this message only, and it is answered here.
        rule.onNodeWithText("Show").performClick()
        rule.onAllNodesWithText(banner).assertCountEquals(0)
        assertTrue("images are not an action", actions.isEmpty())
    }

    @Test
    fun noBanner_whenTheBodyAsksForNothing() {
        val banner = "Images blocked so Dalton Energy can't tell you opened this."
        show()
        rule.onAllNodesWithText(banner).assertCountEquals(0)
        rule.onAllNodesWithText("Show").assertCountEquals(0)
    }

    @Test
    fun noBanner_whenTheSenderIsOnTheAllowlist() {
        val tracked = """<p>Hi</p><img src="https://dalton-energy.example/open.gif" width="1">"""
        show(message = message(body = tracked), imagesAlwaysAllowed = true)
        rule.onAllNodesWithText("Images blocked so Dalton Energy can't tell you opened this.").assertCountEquals(0)
    }

    // ---- attachments ----------------------------------------------------------------------------

    @Test
    fun attachmentChips_openOrSaveTheFileTapped_andSaveDoesNotAlsoOpen() {
        val invoice = GridlinkAttachment(name = "invoice.pdf", size = "84 KB", id = "0")
        val photo = GridlinkAttachment(name = "meter.jpg", size = "1.2 MB", id = "1")
        val opened = mutableListOf<GridlinkAttachment>()
        val saved = mutableListOf<GridlinkAttachment>()
        show(
            message = message(attachments = listOf(invoice, photo)),
            onOpenAttachment = { opened += it },
            onSaveAttachment = { saved += it },
        )
        rule.onNodeWithText("invoice.pdf").performClick()
        assertEquals(listOf(invoice), opened)
        assertTrue(saved.isEmpty())

        rule.onNodeWithContentDescription("Save meter.jpg to the phone").performClick()
        assertEquals(listOf(photo), saved)
        assertEquals("save must not fall through to open", listOf(invoice), opened)
    }

    @Test
    fun attachmentChips_withNoSaver_drawNoSaveButton() {
        val invoice = GridlinkAttachment(name = "invoice.pdf", size = "84 KB", id = "0")
        show(message = message(attachments = listOf(invoice)), onOpenAttachment = {})
        rule.onNodeWithText("invoice.pdf").assertExists()
        rule.onNodeWithText("84 KB").assertExists()
        rule.onAllNodesWithContentDescription("Save invoice.pdf to the phone").assertCountEquals(0)
    }

    // ---- invitation and receipt -----------------------------------------------------------------

    @Test
    fun inviteButtons_sendTheirPartstat() {
        val replies = mutableListOf<String>()
        show(
            invite = GridlinkInvite(
                title = "Quarterly review",
                whenLine = "Tue 12 Aug, 10:00 to 11:00",
                canRsvp = true,
            ),
            onRespondToInvite = { replies += it },
        )
        rule.onNodeWithText("Invitation").assertExists()
        rule.onNodeWithText("Quarterly review").assertExists()
        rule.onNodeWithText("Tue 12 Aug, 10:00 to 11:00").assertExists()
        rule.onNodeWithText("Accept").performClick()
        rule.onNodeWithText("Maybe").performClick()
        rule.onNodeWithText("Decline").performClick()
        assertEquals(listOf("ACCEPTED", "TENTATIVE", "DECLINED"), replies)
        assertTrue("an RSVP is not a thread action", actions.isEmpty())
    }

    @Test
    fun invite_withNoResponder_drawsTheCardButNoButtons() {
        show(invite = GridlinkInvite(title = "Quarterly review", canRsvp = true))
        rule.onNodeWithText("Quarterly review").assertExists()
        rule.onAllNodesWithText("Accept").assertCountEquals(0)
    }

    @Test
    fun receiptRow_namesTheRequester_andSendReceiptCallsTheCallerOnce() {
        var sent = 0
        show(receipt = GridlinkReceipt(requester = "Dalton Energy"), onSendReceipt = { sent++ })
        rule.onNodeWithText("Dalton Energy asked to be told when you read this.").assertExists()
        rule.onNodeWithText("Send receipt").performClick()
        assertEquals(1, sent)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun receiptRow_withNoSender_offersNoButton() {
        show(receipt = GridlinkReceipt(requester = "Dalton Energy"))
        rule.onNodeWithText("Dalton Energy asked to be told when you read this.").assertExists()
        rule.onAllNodesWithText("Send receipt").assertCountEquals(0)
    }
}
