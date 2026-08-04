package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the confirmation dialog NAMES, executed rather than read.
 *
 * This is the whole of the promise "the app says what is about to leave and to whom", and the
 * three gestures do not name the same kind of thing on purpose. Getting it wrong is not a
 * cosmetic slip: the page-opening path is the only unsubscribe that hands a stranger an IP
 * address and lets them run a document, and it shipped naming nothing at all.
 */
class UnsubscribeConfirmationTargetTest {

    private val oneClick = "List-Unsubscribe=One-Click"

    /** A POST names the HOST: one fixed body leaves, and who receives it is the disclosure. */
    @Test fun `a one-click post names the host it will be sent to`() {
        val options = UnsubscribeHeader.parse("<https://l.example.com/u/abc?token=xyz>", oneClick)!!

        assertEquals("l.example.com", options.confirmationTarget(UnsubscribeAction.ONE_CLICK))
    }

    /**
     * ⛔ A page load names the WHOLE URL — path and query included, exactly like the reader's
     * ordinary external-link dialog. Consenting to "open a page on l.example.com" is not the same
     * as consenting to open one specific address; this is the only path where a browser runs
     * whatever comes back, so it is the one where the least is left unsaid.
     */
    @Test fun `opening a page names the whole url, not just the host`() {
        val options = UnsubscribeHeader.parse("<https://l.example.com/u/abc?token=xyz>", null)!!

        assertEquals(
            "https://l.example.com/u/abc?token=xyz",
            options.confirmationTarget(UnsubscribeAction.OPEN_PAGE),
        )
    }

    @Test fun `a mail names the address it goes to`() {
        val options = UnsubscribeHeader.parse("<mailto:leave@l.example.com?subject=off>", null)!!

        assertEquals("leave@l.example.com", options.confirmationTarget(UnsubscribeAction.MAIL))
    }

    /**
     * The witness that the three are read from three DIFFERENT fields. A version that named the
     * one-click URL's host for every action would pass the first test and lie on the other two,
     * which is the shape the defect actually had.
     */
    @Test fun `each gesture names its own target, never a neighbour's`() {
        val options = UnsubscribeHeader.parse(
            "<https://post.example.com/u/abc>, <mailto:leave@mail.example.com>",
            oneClick,
        )!!

        assertEquals("post.example.com", options.confirmationTarget(UnsubscribeAction.ONE_CLICK))
        assertEquals("leave@mail.example.com", options.confirmationTarget(UnsubscribeAction.MAIL))
        assertNull(
            "there is no page to open here, and nothing may be invented for one",
            options.confirmationTarget(UnsubscribeAction.OPEN_PAGE),
        )
    }

    /**
     * Every gesture the parser is willing to offer can be named. This is the guarantee behind the
     * rule in [UnsubscribeHeader.parse] that an https URI without a host is dropped: without it,
     * the confirmation read "…send an unsubscribe request to ." and asked for consent to a
     * destination it had not managed to state.
     */
    @Test fun `anything the parser offers has a target that can be named`() {
        listOf(
            "<https://l.example.com/u>" to oneClick,
            "<https://l.example.com/u>" to null,
            "<mailto:leave@l.example.com>" to null,
            "<https://>, <mailto:leave@l.example.com>" to oneClick,
            "<https://l.example.com/u>, <mailto:leave@l.example.com>" to null,
        ).forEach { (header, post) ->
            val options = UnsubscribeHeader.parse(header, post) ?: return@forEach
            val action = options.preferredAction()!!
            val target = options.confirmationTarget(action)
            assertEquals(
                "\"$header\" is offered as $action but names nothing",
                true,
                !target.isNullOrBlank(),
            )
        }
    }
}
