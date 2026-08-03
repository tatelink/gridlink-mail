package app.sterna.ui.message

import app.sterna.core.data.mail.MailtoUnsubscribe
import app.sterna.core.data.mail.UnsubscribeAction
import app.sterna.core.data.mail.UnsubscribeOptions
import app.sterna.core.data.unsubscribe.UnsubscribeFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Whether the unsubscribe is still on offer — the decision the banner, the overflow entry and the
 * action itself all take, executed here rather than restated three times in Compose.
 *
 * The defect it closes: the banner hid its button once the request had gone out, but the overflow
 * entry never looked at the state at all, and the action only refused while a POST was in flight.
 * Menu → confirm → **a second POST**, to a list that had already answered, from an address that
 * had just proved it is read. The one place the three could be made to agree is a function they
 * all call, and the only way to know they agree is to execute it.
 */
class UnsubscribeOfferTest {

    private val oneClick = UnsubscribeOptions(oneClickUrl = "https://l.example.com/u/abc")
    private val mail = UnsubscribeOptions(mailto = MailtoUnsubscribe("leave@l.example.com"))
    private val page = UnsubscribeOptions(pageUrl = "https://l.example.com/u/abc")

    @Test fun `an untouched message offers its gesture`() {
        assertEquals(
            UnsubscribeAction.ONE_CLICK,
            offeredUnsubscribeAction(oneClick, UnsubscribeState.Idle),
        )
        assertEquals(UnsubscribeAction.MAIL, offeredUnsubscribeAction(mail, UnsubscribeState.Idle))
        assertEquals(
            UnsubscribeAction.OPEN_PAGE,
            offeredUnsubscribeAction(page, UnsubscribeState.Idle),
        )
    }

    /** ⛔ Done is done. This is the second POST that used to be one menu tap away. */
    @Test fun `a completed unsubscribe is not offered again`() {
        assertNull(offeredUnsubscribeAction(oneClick, UnsubscribeState.Sent))
        assertNull(offeredUnsubscribeAction(mail, UnsubscribeState.Queued))
    }

    /** Nor while it is in flight: the button would send the same request twice over. */
    @Test fun `an unsubscribe in flight is not offered again`() {
        assertNull(offeredUnsubscribeAction(oneClick, UnsubscribeState.Sending))
    }

    /** A failure IS offered again — a refused request or a dead network is a retry, not an end. */
    @Test fun `a failure can be retried`() {
        UnsubscribeFailure.entries.forEach { reason ->
            assertEquals(
                "after $reason the reader must be able to try again",
                UnsubscribeAction.ONE_CLICK,
                offeredUnsubscribeAction(oneClick, UnsubscribeState.Failed(reason)),
            )
        }
    }

    /** A message that offers nothing offers nothing, whatever the state says. */
    @Test fun `a message with no way out offers nothing`() {
        assertNull(offeredUnsubscribeAction(null, UnsubscribeState.Idle))
        assertNull(offeredUnsubscribeAction(UnsubscribeOptions(), UnsubscribeState.Idle))
    }

    /**
     * The pending confirmation carries the options it was opened for, and names its target from
     * them — so what the dialog says and what the button then does are the same thing, even if
     * the message behind the dialog is reloaded in the meantime.
     */
    @Test fun `a pending confirmation names the target it captured`() {
        val pending = PendingUnsubscribe(UnsubscribeAction.OPEN_PAGE, page)

        assertEquals("https://l.example.com/u/abc", pending.target)
        assertEquals(
            "the one-click host, not the page url",
            "l.example.com",
            PendingUnsubscribe(UnsubscribeAction.ONE_CLICK, oneClick).target,
        )
    }
}
