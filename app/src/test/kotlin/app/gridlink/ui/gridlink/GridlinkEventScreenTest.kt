package app.gridlink.ui.gridlink

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.gridlink.ui.gridlink.GridlinkSampleContacts.GridlinkContact
import app.gridlink.ui.theme.GridlinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalTime

/**
 * The appointment card, driven by tap, with every callback the host wires recorded.
 *
 * The contract under test is the one between this screen and its caller. The header names the day
 * in full and says when the event runs: "All day" for one with no clock time, the start alone for
 * one with no end, and the span plus its duration for one with both. Back closes once. Edit sits in
 * the pill exactly when a writer was supplied and hands back the SAME event; with no writer there is
 * no Edit at all, not a dead one. Location, notes, category and reminders are drawn when present and
 * omitted (not labelled as missing) when absent, with the reminders sorted soonest-last on one line
 * per reminder. An attachment chip opens THAT file through the opener; the chip is inert with no
 * opener; Remove is offered only when the server manages this event's files AND the file is one the
 * server named, and it asks first; the Attach a file row appears only once the server has said yes
 * and hands back the event. "Also that day" lists the book's other events on the same date, all-day
 * first, and opens the one tapped. The outside party comes from the book (a live book answers for a
 * live calendar, the fixtures for the sample): an internal event has no With row and no mail
 * section; a live calendar never lists the sample's mail under a real appointment; the sample does,
 * and a tapped row hands back that message. Copy puts the card on the clipboard as text, without
 * the reminders.
 *
 * Not covered here: Share and the Where row leave the app through intents (Share is a chooser, Where
 * is a `geo:` view); both go through `leaveOnce` and are pinned at the guard. HTML notes render in a
 * WebView, which under Robolectric is a shadow that draws nothing, so only the branch (markup is not
 * printed as prose) is checked. JVM-hosted under Robolectric, no device.
 */
@RunWith(RobolectricTestRunner::class)
class GridlinkEventScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private var backs = 0
    private val openedMessages = mutableListOf<GridlinkMessage>()
    private val openedEvents = mutableListOf<GridlinkEvent>()
    private val edited = mutableListOf<GridlinkEvent>()
    private val openedFiles = mutableListOf<GridlinkAttachment>()
    private val attachedTo = mutableListOf<GridlinkEvent>()
    private val removed = mutableListOf<Pair<GridlinkEvent, GridlinkAttachment>>()

    /** The context the screen composed under, so the clipboard read back is the one it wrote to. */
    private var screenContext: Context? = null

    private fun event(
        id: String = "e1",
        title: String = "Dish machine service",
        date: LocalDate = DAY,
        start: LocalTime? = LocalTime.of(13, 0),
        end: LocalTime? = LocalTime.of(16, 0),
        location: String? = null,
        domain: String = OWN_DOMAIN,
        notes: String? = null,
        notesAreHtml: Boolean = false,
        attachments: List<GridlinkAttachment> = emptyList(),
        category: String? = null,
        reminders: List<Int> = emptyList(),
    ) = GridlinkEvent(
        id = id,
        title = title,
        date = date,
        start = start,
        end = end,
        location = location,
        domain = domain,
        notes = notes,
        notesAreHtml = notesAreHtml,
        attachments = attachments,
        category = category,
        reminders = reminders,
    )

    /**
     * A live book: a real calendar and a real address book, so nothing on the card can come from
     * the fixtures. [events] is the whole calendar, [contacts] the whole book.
     */
    private fun liveBook(
        events: List<GridlinkEvent> = emptyList(),
        contacts: List<GridlinkContact> = emptyList(),
    ) = GridlinkBook(
        calendar = GridlinkCalendarContent(events = events, today = DAY),
        addressBook = GridlinkContactContent(contacts = contacts),
        ownDomain = OWN_DOMAIN,
    )

    private fun show(
        event: GridlinkEvent = event(),
        book: GridlinkBook? = liveBook(events = listOf(event)),
        onEdit: ((GridlinkEvent) -> Unit)? = null,
        onOpenAttachment: ((GridlinkAttachment) -> Unit)? = null,
        attachmentsSupported: (suspend (GridlinkEvent) -> Boolean)? = null,
        onAttachFile: ((GridlinkEvent) -> Unit)? = null,
        onRemoveAttachment: ((GridlinkEvent, GridlinkAttachment) -> Unit)? = null,
    ) {
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                screenContext = LocalContext.current
                // A null book keeps the default: the sample calendar and the sample address book.
                CompositionLocalProvider(LocalGridlinkBook provides (book ?: LocalGridlinkBook.current)) {
                    GridlinkEventScreen(
                        event = event,
                        onBack = { backs++ },
                        onOpenMessage = { openedMessages += it },
                        onOpenEvent = { openedEvents += it },
                        onEdit = onEdit,
                        onOpenAttachment = onOpenAttachment,
                        attachmentsSupported = attachmentsSupported,
                        onAttachFile = onAttachFile,
                        onRemoveAttachment = onRemoveAttachment,
                    )
                }
            }
        }
    }

    /** A clickable whose text is exactly [label]; see [GridlinkFolderScreenTest] for why exactly. */
    private fun button(label: String) = rule.onNode(hasTextExactly(label) and hasClickAction())

    // ---- the header ----------------------------------------------------------------------------

    @Test
    fun header_namesTheDayInFull_saysTheSpanWithItsDuration_andBackClosesOnce() {
        show()
        rule.onNodeWithText("Dish machine service").assertExists()
        rule.onNodeWithText("Thursday 30 July 2026").assertExists()
        rule.onNodeWithText("1 PM – 4 PM · 3 hr").assertExists()
        // No writer, so no Edit; Copy and Share are real everywhere.
        rule.onAllNodesWithText("Edit").assertCountEquals(0)
        rule.onNodeWithText("Copy").assertExists()
        rule.onNodeWithText("Share").assertExists()

        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun allDayEvent_saysAllDay_andDrawsNoSectionForAnythingItDoesNotHave() {
        show(event = event(start = null, end = null))
        rule.onNodeWithText("All day").assertExists()
        // Nothing labelled as missing: a bare internal event is a date, a title and nothing else.
        rule.onAllNodesWithText("Notes").assertCountEquals(0)
        rule.onAllNodesWithText("Reminders").assertCountEquals(0)
        rule.onAllNodesWithText("Attachments").assertCountEquals(0)
        rule.onAllNodesWithText("Also that day").assertCountEquals(0)
        rule.onAllNodesWithText("Nothing from them yet.").assertCountEquals(0)
    }

    // ---- edit ----------------------------------------------------------------------------------

    @Test
    fun edit_isInThePillOnlyWithAWriter_andHandsBackTheSameEvent() {
        val appointment = event()
        show(event = appointment, onEdit = { edited += it })
        rule.onNodeWithText("Edit").performClick()
        assertEquals(listOf(appointment), edited)
        assertTrue("edit is not back", backs == 0)
    }

    // ---- the facts -----------------------------------------------------------------------------

    @Test
    fun facts_locationNotesCategoryAndReminders_areDrawn_withRemindersSortedOnOneLine() {
        show(
            event = event(
                location = "Store 604",
                notes = "Bring the gasket kit.",
                category = "Maintenance",
                // Unsorted on purpose: the card sorts, soonest-to-fire last.
                reminders = listOf(1440, 0, 10),
            ),
        )
        rule.onNodeWithText("Store 604").assertExists().assertHasClickAction()
        rule.onNodeWithText("Notes").assertExists()
        rule.onNodeWithText("Bring the gasket kit.").assertExists()
        rule.onNodeWithText("Maintenance").assertExists()
        rule.onNodeWithText("Reminders").assertExists()
        rule.onNodeWithText("At time of event\n10 minutes before\n1 day before").assertExists()
    }

    @Test
    fun htmlNotes_areRenderedNotPrinted() {
        show(event = event(notes = "<p>Agenda: <b>gaskets</b></p>", notesAreHtml = true))
        rule.onNodeWithText("Notes").assertExists()
        rule.onAllNodesWithText("<p>Agenda: <b>gaskets</b></p>").assertCountEquals(0)
    }

    // ---- attachments ---------------------------------------------------------------------------

    @Test
    fun attachmentChips_openTheFileTapped_removeOnlyWhatTheServerNamed_andAttachOnceTheServerSaysYes() {
        val managed = GridlinkAttachment(name = "quote.pdf", size = "84 KB", id = "u1", removable = true)
        val linked = GridlinkAttachment(name = "map.png", size = "1.2 MB", id = "u2", removable = false)
        val appointment = event(attachments = listOf(managed, linked))
        show(
            event = appointment,
            onOpenAttachment = { openedFiles += it },
            attachmentsSupported = { true },
            onAttachFile = { attachedTo += it },
            onRemoveAttachment = { e, a -> removed += e to a },
        )
        rule.onNodeWithText("Attachments").assertExists()
        rule.onNodeWithText("map.png").performClick()
        assertEquals(listOf(linked), openedFiles)

        // Remove is offered on the server-managed file only; the plain URL has nothing to delete.
        rule.onNodeWithContentDescription("Remove quote.pdf from this event").assertExists()
        rule.onAllNodesWithContentDescription("Remove map.png from this event").assertCountEquals(0)

        // Asked first. Cancel sends nothing; confirm sends the event and the file, once.
        rule.onNodeWithContentDescription("Remove quote.pdf from this event").performClick()
        rule.onNodeWithText("Remove this file?").assertExists()
        rule.onNodeWithText(
            "quote.pdf will be deleted from Dish machine service for everyone it is shared with. " +
                "This cannot be undone.",
        ).assertExists()
        button("Cancel").performClick()
        assertTrue(removed.isEmpty())
        rule.onAllNodesWithText("Remove this file?").assertCountEquals(0)
        rule.onNodeWithContentDescription("Remove quote.pdf from this event").performClick()
        button("Remove").performClick()
        assertEquals(listOf(appointment to managed), removed)
        rule.onAllNodesWithText("Remove this file?").assertCountEquals(0)
        // Removing did not also open the file.
        assertEquals(listOf(linked), openedFiles)

        rule.onNodeWithText("Attach a file").performClick()
        assertEquals(listOf(appointment), attachedTo)
    }

    @Test
    fun attachmentChips_withNoOpenerAndAServerThatSaysNo_areInert_withNoAttachRowAndNoRemove() {
        val managed = GridlinkAttachment(name = "quote.pdf", size = "84 KB", id = "u1", removable = true)
        show(
            event = event(attachments = listOf(managed)),
            attachmentsSupported = { false },
            onAttachFile = { attachedTo += it },
            onRemoveAttachment = { e, a -> removed += e to a },
        )
        // One file, so the heading is singular.
        rule.onNodeWithText("Attachment").assertExists()
        rule.onNodeWithText("quote.pdf").assertExists().assertHasNoClickAction()
        rule.onNodeWithText("84 KB").assertExists()
        rule.onAllNodesWithContentDescription("Remove quote.pdf from this event").assertCountEquals(0)
        rule.onAllNodesWithText("Attach a file").assertCountEquals(0)
    }

    // ---- also that day -------------------------------------------------------------------------

    @Test
    fun alsoThatDay_listsTheBooksOtherEventsOnTheDate_allDayFirst_andOpensTheOneTapped() {
        val opened = event(id = "e1", title = "Dish machine service", start = LocalTime.of(8, 30), end = null)
        val deadline = event(id = "e2", title = "CAP due", start = null, end = null)
        val later = event(id = "e3", title = "Callout coverage", start = LocalTime.of(18, 0), end = LocalTime.of(19, 0))
        val tomorrow = event(id = "e4", title = "Store walk", date = DAY.plusDays(1))
        show(event = opened, book = liveBook(events = listOf(later, tomorrow, opened, deadline)))

        // A start with no end is just the start.
        rule.onNodeWithText("8:30 AM").assertExists()
        rule.onNodeWithText("Also that day").assertExists()
        // The open event is not listed under itself, and another day's event is not on this card.
        rule.onAllNodesWithText("Dish machine service").assertCountEquals(1)
        rule.onAllNodesWithText("Store walk").assertCountEquals(0)
        // All-day first, then by start; the row's time is the start alone.
        rule.onNodeWithText("All day").assertExists()
        rule.onNodeWithText("6 PM").assertExists()
        val capTop = rule.onNodeWithText("CAP due").getUnclippedBoundsInRoot().top
        val calloutTop = rule.onNodeWithText("Callout coverage").getUnclippedBoundsInRoot().top
        assertTrue("all-day row above the timed one, got $capTop vs $calloutTop", capTop < calloutTop)

        rule.onNodeWithText("Callout coverage").performClick()
        assertEquals(listOf(later), openedEvents)
        assertTrue(openedMessages.isEmpty())
    }

    // ---- the outside party ---------------------------------------------------------------------

    @Test
    fun internalEvent_hasNoWithRowAndNoMailSection() {
        show(event = event(domain = OWN_DOMAIN, location = "Store 604"))
        rule.onAllNodesWithText(OWN_DOMAIN).assertCountEquals(0)
        rule.onAllNodesWithText("Mail from $OWN_DOMAIN").assertCountEquals(0)
        rule.onAllNodesWithText("Nothing from them yet.").assertCountEquals(0)
    }

    @Test
    fun outsideParty_isNamedFromTheLiveBook_andALiveCalendarListsNoSampleMail() {
        val sanivex = GridlinkContact(
            id = "c-sanivex",
            given = "",
            family = "Sanivex",
            role = "Dish machines",
            email = "service@sanivex.example",
        )
        val tech = GridlinkContact(
            id = "c-tech",
            given = "Rosa",
            family = "Quint",
            role = "Technician",
            email = "rosa@sanivex.example",
        )
        val appointment = event(domain = "sanivex.example")
        // The technician sorts first in the list; the card still names the organisation.
        show(event = appointment, book = liveBook(events = listOf(appointment), contacts = listOf(tech, sanivex)))
        rule.onNodeWithText("Sanivex").assertExists()
        rule.onNodeWithText("service@sanivex.example").assertExists()
        rule.onAllNodesWithText("Rosa Quint").assertCountEquals(0)
        rule.onNodeWithText("Mail from Sanivex").assertExists()
        rule.onNodeWithText("Nothing from them yet.").assertExists()
    }

    @Test
    fun outsideParty_unknownToTheBook_isNamedByItsDomainAlone() {
        val appointment = event(domain = "brightmar.example")
        show(event = appointment, book = liveBook(events = listOf(appointment)))
        rule.onNodeWithText("brightmar.example").assertExists()
        rule.onNodeWithText("Mail from brightmar.example").assertExists()
        rule.onNodeWithText("Nothing from them yet.").assertExists()
    }

    @Test
    fun sampleBook_listsTheSamplesMailFromTheDomain_andOpensTheMessageTapped() {
        val domain = "verdantfs.example"
        val expected = GridlinkSample.messagesFromDomain(domain)
        val counterpart = GridlinkSampleContacts.forDomain(domain)
        assertTrue("the sample must carry mail from $domain for this test to mean anything", expected.isNotEmpty())
        assertTrue("the sample must know $domain", counterpart != null)

        show(event = event(domain = domain), book = null)
        rule.onNodeWithText("Mail from ${counterpart!!.displayName}").assertExists()
        rule.onAllNodesWithText("Nothing from them yet.").assertCountEquals(0)
        expected.forEach { rule.onAllNodesWithText(it.subject)[0].assertExists() }

        val first = expected.first()
        rule.onAllNodesWithText(first.subject)[0].performClick()
        assertEquals(listOf(first), openedMessages)
        assertTrue(openedEvents.isEmpty())
    }

    // ---- copy ----------------------------------------------------------------------------------

    @Test
    fun copy_putsTheCardOnTheClipboardAsText_withoutTheReminders() {
        show(
            event = event(
                location = "Store 604",
                category = "Maintenance",
                notes = "Bring the gasket kit.",
                reminders = listOf(10),
            ),
        )
        rule.onNodeWithText("Copy").performClick()
        val clipboard = screenContext!!.getSystemService(ClipboardManager::class.java)
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        assertEquals(
            "Dish machine service\nThursday 30 July 2026\n1 PM – 4 PM · 3 hr\nStore 604\nMaintenance\n" +
                "Bring the gasket kit.",
            copied,
        )
    }

    private companion object {
        /** The sample's pinned today, which is a Thursday. */
        val DAY: LocalDate = LocalDate.of(2026, 7, 30)

        /** The signed-in account's domain for the live book; not the sample's, on purpose. */
        const val OWN_DOMAIN = "acme-ops.test"
    }
}
