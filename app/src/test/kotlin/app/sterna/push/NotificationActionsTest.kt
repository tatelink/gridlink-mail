package app.sterna.push

import app.sterna.core.data.settings.NotificationContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which buttons a new-mail notification may carry at each position of the notification-content
 * setting (Codeberg #57). At the hidden position the notification says only "New message": acting
 * on it is acting blind, and Delete over an unidentifiable message loses mail. The two informative
 * positions must be untouched — they were settled with the reporter and are not up for revision.
 */
class NotificationActionsTest {

    private val all = listOf(
        MailNotificationAction.REPLY,
        MailNotificationAction.MARK_READ,
        MailNotificationAction.DELETE,
    )

    @Test fun `sender and subject keeps reply, mark as read and delete, in that order`() {
        assertEquals(all, Notifications.actionsFor(NotificationContent.SENDER_AND_SUBJECT))
    }

    @Test fun `sender only keeps the same three actions, in the same order`() {
        assertEquals(all, Notifications.actionsFor(NotificationContent.SENDER_ONLY))
    }

    @Test fun `neither offers no action at all`() {
        assertEquals(emptyList<MailNotificationAction>(), Notifications.actionsFor(NotificationContent.NONE))
    }

    @Test fun `nothing destructive is offered when the message cannot be identified`() {
        val actions = Notifications.actionsFor(NotificationContent.NONE)
        assertFalse("Delete acts blind at NONE", MailNotificationAction.DELETE in actions)
        assertFalse("Mark as read acts blind at NONE", MailNotificationAction.MARK_READ in actions)
        assertFalse("Reply acts blind at NONE", MailNotificationAction.REPLY in actions)
    }

    /**
     * The two rules crossed: a position that reveals nothing identifying must offer nothing, and a
     * position that names the message must keep its buttons. Iterating the enum means a fourth
     * position added later cannot slip past this without a decision.
     */
    @Test fun `actions are offered exactly where the notification identifies the message`() {
        NotificationContent.entries.forEach { content ->
            val reveal = MailNotificationText.resolve(content, SENDER, SUBJECT, GENERIC)
            val identifies = reveal.title != GENERIC || reveal.text != null
            val actions = Notifications.actionsFor(content)
            if (identifies) {
                assertEquals("$content names the message but lost its actions", all, actions)
            } else {
                assertTrue("$content reveals nothing yet offers $actions", actions.isEmpty())
            }
        }
    }

    @Test fun `no position offers the same action twice`() {
        NotificationContent.entries.forEach { content ->
            val actions = Notifications.actionsFor(content)
            assertEquals("duplicate action at $content", actions.distinct(), actions)
        }
    }

    private companion object {
        const val SENDER = "Jordan Lee"
        const val SUBJECT = "Blood test results"
        const val GENERIC = "New message"
    }
}
