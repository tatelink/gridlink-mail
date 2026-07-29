package app.sterna.push

import app.sterna.core.data.settings.NotificationContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which buttons a new-mail notification may carry (Codeberg #57). The rule is not "which setting
 * is on" but "does what is on screen name the message": acting on a notification that identifies
 * nothing is acting blind, and Delete then loses mail. So the hidden position never has buttons,
 * and the two informative positions lose theirs when the mail has nothing to put there — a
 * message with no From header at SENDER_ONLY reads "New message / New message".
 *
 * The two informative positions with an ordinary message were settled with the reporter and must
 * stay exactly as they were: three actions, REPLY → MARK_READ → DELETE.
 */
class NotificationActionsTest {

    private val all = listOf(
        MailNotificationAction.REPLY,
        MailNotificationAction.MARK_READ,
        MailNotificationAction.DELETE,
    )

    /** The shipped decision, fed the way [Notifications.notifyNewMail] feeds it. */
    private fun actions(content: NotificationContent, sender: String?, subject: String?) =
        Notifications.actionsFor(content, hasSender = sender != null, hasSubject = subject != null)

    /** What the shipped text rule would put on screen for the same mail, stand-ins included. */
    private fun reveal(content: NotificationContent, sender: String?, subject: String?) =
        MailNotificationText.resolve(content, sender ?: GENERIC, subject ?: NO_SUBJECT, GENERIC)

    @Test fun `sender and subject keeps reply, mark as read and delete, in that order`() {
        assertEquals(all, actions(NotificationContent.SENDER_AND_SUBJECT, SENDER, SUBJECT))
    }

    @Test fun `sender only keeps the same three actions, in the same order`() {
        assertEquals(all, actions(NotificationContent.SENDER_ONLY, SENDER, SUBJECT))
    }

    @Test fun `neither offers no action at all`() {
        assertEquals(
            emptyList<MailNotificationAction>(),
            actions(NotificationContent.NONE, SENDER, SUBJECT),
        )
    }

    @Test fun `nothing destructive is offered when the message cannot be identified`() {
        val offered = actions(NotificationContent.NONE, SENDER, SUBJECT)
        assertFalse("Delete acts blind at NONE", MailNotificationAction.DELETE in offered)
        assertFalse("Mark as read acts blind at NONE", MailNotificationAction.MARK_READ in offered)
        assertFalse("Reply acts blind at NONE", MailNotificationAction.REPLY in offered)
    }

    /**
     * The narrow case the setting alone cannot see: daemon mail or a malformed header leaves no
     * From, so SENDER_ONLY shows the generic line as title AND as text. The setting promises a
     * sender; this message has none, and Delete over it is the very harm the fix removes.
     */
    @Test fun `a message with no sender gets no actions at sender only`() {
        val reveal = reveal(NotificationContent.SENDER_ONLY, sender = null, subject = SUBJECT)
        assertEquals("the notification names nothing", GENERIC, reveal.title)
        assertEquals("the notification names nothing", GENERIC, reveal.text)
        assertEquals(
            emptyList<MailNotificationAction>(),
            actions(NotificationContent.SENDER_ONLY, sender = null, subject = SUBJECT),
        )
    }

    @Test fun `a message with no sender keeps its actions at sender and subject`() {
        // There the subject IS displayed, so the notification still names the message.
        assertEquals(all, actions(NotificationContent.SENDER_AND_SUBJECT, sender = null, subject = SUBJECT))
    }

    @Test fun `a message with neither sender nor subject gets no actions anywhere`() {
        NotificationContent.entries.forEach { content ->
            assertTrue(
                "$content offers actions over a mail that carries nothing",
                actions(content, sender = null, subject = null).isEmpty(),
            )
        }
    }

    @Test fun `a message with no subject keeps its actions where the sender shows`() {
        assertEquals(all, actions(NotificationContent.SENDER_AND_SUBJECT, SENDER, subject = null))
        assertEquals(all, actions(NotificationContent.SENDER_ONLY, SENDER, subject = null))
    }

    /**
     * The two rules crossed, over every position and every combination of what the mail carries:
     * the actions must be offered exactly when something the user can see is the mail's own and
     * not one of the stand-ins. Iterating the enum means a fourth position, or a drift between
     * [MailNotificationText.resolve] and [Notifications.actionsFor], cannot slip past silently.
     */
    @Test fun `actions are offered exactly when the notification names the message`() {
        val standIns = setOf(GENERIC, NO_SUBJECT)
        NotificationContent.entries.forEach { content ->
            listOf(SENDER, null).forEach { sender ->
                listOf(SUBJECT, null).forEach { subject ->
                    val reveal = reveal(content, sender, subject)
                    val onScreen = listOfNotNull(reveal.title, reveal.text, reveal.bigText)
                    val names = onScreen.any { it !in standIns }
                    val offered = actions(content, sender, subject)
                    val case = "$content sender=$sender subject=$subject shows $onScreen"
                    if (names) {
                        assertEquals("$case but lost its actions", all, offered)
                    } else {
                        assertTrue("$case names nothing yet offers $offered", offered.isEmpty())
                    }
                }
            }
        }
    }

    @Test fun `no position offers the same action twice`() {
        NotificationContent.entries.forEach { content ->
            val offered = actions(content, SENDER, SUBJECT)
            assertEquals("duplicate action at $content", offered.distinct(), offered)
        }
    }

    private companion object {
        const val SENDER = "Jordan Lee"
        const val SUBJECT = "Blood test results"
        const val GENERIC = "New message"
        const val NO_SUBJECT = "(no subject)"
    }
}
