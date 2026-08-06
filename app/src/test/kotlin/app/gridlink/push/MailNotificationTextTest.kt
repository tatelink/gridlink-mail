package app.gridlink.push

import app.gridlink.core.data.settings.NotificationContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a mail notification is allowed to reveal at each position of the notification-content
 * setting. The same rule now serves the arriving-mail path and a snooze waking up, which used
 * to post with the defaults and so showed sender and subject regardless (Codeberg #84).
 */
class MailNotificationTextTest {

    private fun resolve(content: NotificationContent) =
        MailNotificationText.resolve(content, SENDER, SUBJECT, GENERIC)

    @Test fun `sender and subject shows both, collapsed and expanded`() {
        val reveal = resolve(NotificationContent.SENDER_AND_SUBJECT)
        assertEquals(SENDER, reveal.title)
        assertEquals(SUBJECT, reveal.text)
        assertEquals(SUBJECT, reveal.bigText)
    }

    @Test fun `sender only keeps the name and hides the subject`() {
        val reveal = resolve(NotificationContent.SENDER_ONLY)
        assertEquals(SENDER, reveal.title)
        assertEquals(GENERIC, reveal.text)
        assertNull(reveal.bigText)
    }

    @Test fun `neither reveals nothing identifying`() {
        val reveal = resolve(NotificationContent.NONE)
        assertEquals(GENERIC, reveal.title)
        assertNull(reveal.text)
        assertNull(reveal.bigText)
    }

    @Test fun `the subject never leaks through a hidden position`() {
        listOf(NotificationContent.SENDER_ONLY, NotificationContent.NONE).forEach { content ->
            val reveal = resolve(content)
            assertNull("bigText leaks the subject at $content", reveal.bigText)
            assertEquals("title leaks the subject at $content", false, reveal.title == SUBJECT)
            assertEquals("text leaks the subject at $content", false, reveal.text == SUBJECT)
        }
    }

    @Test fun `the sender never leaks when the setting hides it`() {
        val reveal = resolve(NotificationContent.NONE)
        assertEquals(false, reveal.title == SENDER)
        assertEquals(false, reveal.text == SENDER)
    }

    private companion object {
        const val SENDER = "Jordan Lee"
        const val SUBJECT = "Blood test results"
        const val GENERIC = "New message"
    }
}
