package app.sterna.core.data.db

import app.sterna.core.data.mail.DaoQuerySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The count on the overflow menu's Outbox entry (#70): what is in the Outbox **right now**.
 *
 * Two things have to hold at once, and they contradict each other on purpose.
 *
 * The count must appear the instant a message is queued — the reporter sent a message offline and
 * nothing anywhere told him it had been queued. And the dot on the toolbar button must KEEP its
 * thirty-second grace (#82), because it is in the field of view whether or not the user went
 * looking, whereas this count does not exist on screen until the menu is opened on purpose.
 *
 * So for the length of the grace the dot is absent while the menu reads "(1)". Every test here that
 * checks the count also checks the dot at the same instant, so flattening the two onto one flow —
 * in either direction — turns this file red.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OutboxQueuedCountTest {
    private val grace = OutboxLogic.BADGE_GRACE_MILLIS

    /** The undo window of a normal send: the row is only allowed to leave once it elapses. */
    private val window = 5_000L

    // ---------------------------------------------------------------- the count itself

    /**
     * Every state the outbox can be in, decided one by one. Driven off [OutboxState.entries] so a
     * sixth state cannot be added without someone deciding here whether it is in the Outbox or not.
     */
    @Test fun everyStateCountsExceptTheOneOpenInTheComposer() {
        OutboxState.entries.forEach { state ->
            val expected = if (state == OutboxState.EDITING) 0 else 1
            assertEquals(
                "a $state row is ${if (expected == 1) "in" else "not in"} the Outbox",
                expected,
                OutboxLogic.queuedCount(listOf(OutboxBadgeItem(state, notBeforeMillis = 0))),
            )
        }
    }

    /**
     * The reporter's case. He is offline, the send is queued, and he opens the menu straight away:
     * the entry must already say 1. No clock is consulted — the deadline here is still in the
     * future (the undo window has not even closed) and it makes no difference.
     */
    @Test fun aMessageQueuedThisInstantIsCountedWithNoDelay() {
        val justQueued = listOf(OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = window))

        assertEquals("queued is queued, the menu says so at once", 1, OutboxLogic.queuedCount(justQueued))
        // And the dot, at that same instant, is deliberately still dark.
        assertEquals("the dot keeps its grace", 0, OutboxLogic.activeCount(justQueued, now = 0))
    }

    /** Nothing queued, nothing announced — the entry carries no badge at all. */
    @Test fun anEmptyOutboxIsCountedAsZero() {
        assertEquals(0, OutboxLogic.queuedCount(emptyList()))
    }

    @Test fun severalWaitingMessagesAreAllCounted() {
        val rows = listOf(
            OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = window),
            OutboxBadgeItem(OutboxState.QUEUED, notBeforeMillis = 0),
            OutboxBadgeItem(OutboxState.SENDING, notBeforeMillis = 0),
            OutboxBadgeItem(OutboxState.FAILED, notBeforeMillis = 0),
            OutboxBadgeItem(OutboxState.EDITING, notBeforeMillis = 0), // held by the composer
        )
        assertEquals(4, OutboxLogic.queuedCount(rows))
    }

    /**
     * The count is a photograph, not a countdown: the same rows give the same number an hour later.
     * (The dot's count does move on its own — that is [OutboxLogic.activeCount]'s job, not this one.)
     */
    @Test fun theCountNeverChangesOnItsOwnHoweverLongTheMessageWaits() {
        val rows = listOf(OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = window))
        val first = OutboxLogic.queuedCount(rows)

        assertEquals(first, OutboxLogic.queuedCount(rows))
        // Same rows, and the number the menu shows is the same at every instant of the timeline.
        assertEquals(1, first)
    }

    // ---------------------------------------------------------------- the asymmetry, on the shipped paths

    /**
     * The whole point of the change, checked on the two shipped paths at once: the dot goes through
     * [OutboxLogic.badgeCount] on a real (virtual) clock, the menu through [OutboxLogic.queuedCount].
     *
     * Deleting the grace to "harmonise" the two makes the first assertion fail; giving the count a
     * grace makes the second fail. There is no way to make both indicators agree and keep this test
     * green, which is exactly the property #70 and #82 need from this file.
     */
    @Test fun theDotStaysDarkThroughTheGraceWhileTheMenuAlreadyShowsTheMessage() = runTest {
        val queued = listOf(OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = window))
        val rows = MutableStateFlow(queued)
        val dot = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            OutboxLogic.badgeCount(rows) { testScheduler.currentTime }.toList(dot)
        }
        runCurrent()

        assertEquals("the dot must not appear the moment a send is queued (#82)", listOf(0), dot)
        assertEquals("but the menu entry must (#70)", 1, OutboxLogic.queuedCount(rows.value))

        advanceTimeBy(window + 1) // the undo window has closed: the worker may now take the row
        assertEquals("the dot must not light up the moment the send is released (#82)", listOf(0), dot)
        assertEquals("the message is in the Outbox all the same", 1, OutboxLogic.queuedCount(rows.value))

        advanceTimeBy(grace - 2) // one millisecond short of the dot's threshold
        assertEquals("still nothing on the toolbar", listOf(0), dot)
        assertEquals("and still one message in the Outbox", 1, OutboxLogic.queuedCount(rows.value))

        advanceTimeBy(2) // the grace runs out; the message never left
        assertEquals("now the dot too", listOf(0, 1), dot)
        assertEquals("the menu says what it always said", 1, OutboxLogic.queuedCount(rows.value))

        rows.value = emptyList() // the network came back and the message went out
        runCurrent()
        assertEquals("both fall silent together when the row leaves", listOf(0, 1, 0), dot)
        assertEquals(0, OutboxLogic.queuedCount(rows.value))
        job.cancel()
    }

    /**
     * Guard on the grace itself rather than on one scenario: over the first half-minute of a send,
     * the dot's count and the menu's count must genuinely disagree. A grace quietly reduced to zero
     * (or a count that grew one) would make them agree everywhere, and that is what fails here.
     */
    @Test fun theTwoCountsDisagreeForTheWholeLengthOfTheGrace() {
        val rows = listOf(OutboxBadgeItem(OutboxState.QUEUED, notBeforeMillis = 0))
        assertTrue("the dot's grace is what buys the silence; it must not be zero", grace > 0)

        // Sampled across the grace, not just at its ends.
        listOf(0L, 1L, grace / 2, grace - 1).forEach { now ->
            assertEquals("the menu at ${now}ms", 1, OutboxLogic.queuedCount(rows))
            assertEquals("the dot at ${now}ms", 0, OutboxLogic.activeCount(rows, now))
            assertNotEquals(OutboxLogic.queuedCount(rows), OutboxLogic.activeCount(rows, now))
        }
        // Past the grace they finally say the same thing.
        assertEquals(1, OutboxLogic.activeCount(rows, now = grace))
    }

    /**
     * A failure is the one case where the two already agreed, and must keep agreeing: it counts on
     * both at once, with no delay on either side.
     */
    @Test fun aFailedMessageIsShownAtOnceOnBothIndicators() {
        val rows = listOf(OutboxBadgeItem(OutboxState.FAILED, notBeforeMillis = 0))
        assertEquals(1, OutboxLogic.queuedCount(rows))
        assertEquals(1, OutboxLogic.activeCount(rows, now = 0))
    }

    // ---------------------------------------------------------------- the WYSIWYG invariant

    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(OUTBOX_CREATE_SQL)
            // The columns later migrations added, so the shipped queries run against the table as
            // it is today rather than as it was at v10.
            st.executeUpdate("ALTER TABLE `outbox` ADD COLUMN `pgpMode` TEXT")
            st.executeUpdate("ALTER TABLE `outbox` ADD COLUMN `pgpEntityPath` TEXT")
            st.executeUpdate("ALTER TABLE `outbox` ADD COLUMN `draftEmailId` TEXT")
        }
    }

    @After fun tearDown() = db.close()

    private fun insert(state: OutboxState, notBefore: Long, createdAt: Long) {
        db.prepareStatement(
            "INSERT INTO `outbox`(accountId, recipients, subject, textBody, attachmentsJson, " +
                "createdAtMillis, notBeforeMillis, state, attemptCount) " +
                "VALUES ('a', 'to@example.org', 's', 'b', '[]', ?, ?, ?, 0)",
        ).use { st ->
            st.setLong(1, createdAt)
            st.setLong(2, notBefore)
            st.setString(3, state.name)
            st.executeUpdate()
        }
    }

    /**
     * The contract of this fix: the number on the menu entry is, at every instant, exactly the
     * number of lines the Outbox screen will show. Tapping the entry must not open a list with a
     * different number of messages in it than the badge just announced.
     *
     * Both sides are run for real here — the two `@Query` statements are read out of the shipped
     * [OutboxDao] and executed against a real SQLite engine, so a `WHERE` added to either one (they
     * are different queries over the same table) breaks the equality instead of being noticed on a
     * device six weeks later. The screen's own filtering is [OutboxLogic.isWaitingInOutbox], the
     * single predicate `MailRepository.outboxFlow` applies to the rows of `observeAll` and
     * `outboxQueuedCount` counts with — one function, so the two cannot drift.
     */
    @Test fun theMenuCountIsExactlyTheNumberOfRowsTheOutboxScreenLists() {
        insert(OutboxState.HELD, notBefore = 5_000, createdAt = 1)
        insert(OutboxState.QUEUED, notBefore = 0, createdAt = 2)
        insert(OutboxState.SENDING, notBefore = 0, createdAt = 3)
        insert(OutboxState.FAILED, notBefore = 0, createdAt = 4)
        insert(OutboxState.EDITING, notBefore = 0, createdAt = 5) // open in the composer
        insert(OutboxState.HELD, notBefore = 99_000, createdAt = 6) // queued a second ago

        // What the Outbox screen lists: observeAll, filtered the way outboxFlow filters it.
        val listed = mutableListOf<OutboxState>()
        db.createStatement().use { st ->
            st.executeQuery(DaoQuerySource.daoQuery("OutboxDao", "observeAll")).use { rs ->
                while (rs.next()) listed += OutboxState.valueOf(rs.getString("state"))
            }
        }
        val onScreen = listed.filter { OutboxLogic.isWaitingInOutbox(it) }

        // What the menu entry announces: observeBadgeItems, counted the way outboxQueuedCount counts.
        val badgeItems = mutableListOf<OutboxBadgeItem>()
        db.createStatement().use { st ->
            st.executeQuery(DaoQuerySource.daoQuery("OutboxDao", "observeBadgeItems")).use { rs ->
                while (rs.next()) {
                    badgeItems += OutboxBadgeItem(
                        OutboxState.valueOf(rs.getString("state")),
                        rs.getLong("notBeforeMillis"),
                    )
                }
            }
        }

        assertEquals("both queries must see the same rows of the same table", listed.size, badgeItems.size)
        assertEquals(
            "the count must equal the number of lines the screen shows",
            onScreen.size,
            OutboxLogic.queuedCount(badgeItems),
        )
        // Not a pair of zeroes agreeing by accident: six rows in, one of them being edited.
        assertEquals(6, listed.size)
        assertEquals(5, onScreen.size)
    }

    /**
     * The invariant again, on the state that breaks it if anything does: the row taken out for
     * editing must vanish from the count at the same moment it vanishes from the list, and come
     * back to both when the composer gives it up (`releaseOutboxEdit` only flips the state).
     */
    @Test fun theRowOpenInTheComposerLeavesBothTheListAndTheCountTogether() {
        insert(OutboxState.QUEUED, notBefore = 0, createdAt = 1)
        insert(OutboxState.QUEUED, notBefore = 0, createdAt = 2)
        assertEquals(2, countThroughBothPaths())

        db.createStatement().use { it.executeUpdate("UPDATE `outbox` SET state = 'EDITING' WHERE createdAtMillis = 2") }
        assertEquals("taken out to be edited: off the list AND off the count", 1, countThroughBothPaths())

        db.createStatement().use { it.executeUpdate("UPDATE `outbox` SET state = 'QUEUED' WHERE createdAtMillis = 2") }
        assertEquals("given back to the queue: on both again", 2, countThroughBothPaths())
    }

    /** Runs both shipped paths over the current table and asserts they agree, returning the count. */
    private fun countThroughBothPaths(): Int {
        val listed = mutableListOf<OutboxState>()
        db.createStatement().use { st ->
            st.executeQuery(DaoQuerySource.daoQuery("OutboxDao", "observeAll")).use { rs ->
                while (rs.next()) listed += OutboxState.valueOf(rs.getString("state"))
            }
        }
        val badgeItems = mutableListOf<OutboxBadgeItem>()
        db.createStatement().use { st ->
            st.executeQuery(DaoQuerySource.daoQuery("OutboxDao", "observeBadgeItems")).use { rs ->
                while (rs.next()) {
                    badgeItems += OutboxBadgeItem(
                        OutboxState.valueOf(rs.getString("state")),
                        rs.getLong("notBeforeMillis"),
                    )
                }
            }
        }
        val count = OutboxLogic.queuedCount(badgeItems)
        assertEquals(listed.count { OutboxLogic.isWaitingInOutbox(it) }, count)
        return count
    }
}
