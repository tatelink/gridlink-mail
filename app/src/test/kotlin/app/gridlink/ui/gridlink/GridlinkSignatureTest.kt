package app.gridlink.ui.gridlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What [composeBodyWithSignature] promises. The append-only rule is the one worth pinning: the
 * composer's formatting marks are character ranges over this string, so a signature that ever
 * lands ahead of the body silently moves every bold and italic in the message.
 */
class GridlinkSignatureTest {

    @Test fun `a new message gets the delimiter and the signature`() {
        assertEquals(
            "\n\n-- \nTate",
            composeBodyWithSignature("", "Tate", isReply = false, onReplies = true, delimiter = true),
        )
    }

    @Test fun `no delimiter means the signature field is exactly what goes in`() {
        assertEquals(
            "\n\nTate",
            composeBodyWithSignature("", "Tate", isReply = false, onReplies = true, delimiter = false),
        )
    }

    @Test fun `the signature is APPENDED, never inserted ahead of the body`() {
        val seeded = composeBodyWithSignature(
            body = "See the attached invoice.",
            signature = "Tate",
            isReply = false,
            onReplies = true,
            delimiter = true,
        )
        assertEquals("See the attached invoice.\n\n-- \nTate", seeded)
    }

    @Test fun `a reply is skipped when replies are off`() {
        assertNull(
            composeBodyWithSignature("", "Tate", isReply = true, onReplies = false, delimiter = true),
        )
    }

    @Test fun `a reply is signed when replies are on`() {
        assertEquals(
            "\n\n-- \nTate",
            composeBodyWithSignature("", "Tate", isReply = true, onReplies = true, delimiter = true),
        )
    }

    @Test fun `the replies setting does not touch a new message`() {
        assertEquals(
            "\n\n-- \nTate",
            composeBodyWithSignature("", "Tate", isReply = false, onReplies = false, delimiter = true),
        )
    }

    @Test fun `a blank signature adds nothing at all`() {
        assertNull(composeBodyWithSignature("", "", isReply = false, onReplies = true, delimiter = true))
        assertNull(composeBodyWithSignature("", "   \n ", isReply = false, onReplies = true, delimiter = true))
    }

    @Test fun `surrounding whitespace in the stored signature is not carried into the body`() {
        assertEquals(
            "\n\n-- \nTate\nGridlink",
            composeBodyWithSignature(
                body = "",
                signature = "\n  Tate\nGridlink  \n",
                isReply = false,
                onReplies = true,
                delimiter = true,
            ),
        )
    }

    // ---- changing From swaps the signature ----

    private fun swap(body: String, seeded: String?, next: String, reply: Boolean = false, onReplies: Boolean = true) =
        gridlinkSwapSignature(body, seeded, next, isReply = reply, onReplies = onReplies, delimiter = true)

    @Test fun `changing From swaps an untouched signature for the new identity's`() {
        assertEquals("Hello\n\n-- \nSupport", swap("Hello\n\n-- \nTate", "Tate", "Support"))
    }

    @Test fun `a signature the user edited is theirs, and is left alone`() {
        assertNull(swap("Hello\n\n-- \nTate (mobile)", "Tate", "Support"))
    }

    @Test fun `moving to an identity with no signature drops the old block`() {
        assertEquals("Hello", swap("Hello\n\n-- \nTate", "Tate", ""))
    }

    @Test fun `moving from no signature to one appends it under the opening rules`() {
        assertEquals("Hello\n\n-- \nSupport", swap("Hello", null, "Support"))
        assertNull(swap("Hello", null, "Support", reply = true, onReplies = false))
    }
}
