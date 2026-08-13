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
}
