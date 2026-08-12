package app.gridlink.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * How a signature turns into text in a body, with the "-- " delimiter on (the default, and the
 * shape the app has always written) and off (#90).
 *
 * Salvaged from the retired composer's own suite, which had grown a second half about swapping an
 * intact block when the From identity changes — that half went with the code it tested. What is
 * left is what the Identities editor derives its preview from, and what a Gridlink composer will
 * call the day it writes signatures at all.
 */
class SignatureTextTest {

    private val sig = "Alex Rivera\nAcme"

    @Test fun newMessageOpensWithADelimitedSignatureBlock() {
        assertEquals(
            "\n\n-- \nAlex Rivera\nAcme",
            bodyWithSignature(quoted = "", signature = sig, delimiter = true),
        )
    }

    @Test fun noSignatureLeavesTheBodyExactlyAsItWas() {
        assertEquals("", signatureBlock("", delimiter = true))
        assertEquals("", signatureBlock("   \n ", delimiter = true))
        assertEquals("", bodyWithSignature(quoted = "", signature = "", delimiter = true))
        assertEquals(
            "\n\nOn …, Alice wrote:\n> hi",
            bodyWithSignature("\n\nOn …, Alice wrote:\n> hi", "", delimiter = true),
        )
    }

    @Test fun replyPutsTheSignatureAboveTheQuoteByDefault() {
        val quote = "\n\nOn …, Alice wrote:\n> hi"
        assertEquals(
            "\n\n-- \nAlex Rivera\nAcme\n\nOn …, Alice wrote:\n> hi",
            bodyWithSignature(quote, sig, delimiter = true),
        )
    }

    @Test fun replyCanPutTheSignatureBelowTheQuote() {
        val quote = "\n\nOn …, Alice wrote:\n> hi"
        assertEquals(
            "\n\nOn …, Alice wrote:\n> hi\n\n-- \nAlex Rivera\nAcme",
            bodyWithSignature(quote, sig, signatureBelowQuote = true, delimiter = true),
        )
    }

    @Test fun theSignatureIsTrimmedButKeepsItsInnerLineBreaks() {
        assertEquals(
            "\n\n-- \nAlex\nAcme",
            bodyWithSignature("", "  \nAlex\nAcme\n  ", delimiter = true),
        )
    }

    // --- The delimiter switched OFF (#90) -------------------------------------------------------
    // The point of the setting: the signature field then holds EXACTLY what goes into the message,
    // so whoever wants "__", a rule of dashes, or no separator at all types it there. Nothing else
    // about the signature changes — the blank line that detaches it from the message above stays.

    @Test fun withoutTheDelimiterTheBlockIsTheBlankLineAndTheSignatureAlone() {
        assertEquals("\n\nAlex Rivera\nAcme", signatureBlock(sig, delimiter = false))
        assertEquals(
            "\n\nAlex Rivera\nAcme",
            bodyWithSignature(quoted = "", signature = sig, delimiter = false),
        )
        assertFalse(signatureBlock(sig, delimiter = false).contains("-- "))
    }

    @Test fun withoutTheDelimiterABlankSignatureStillAddsNothing() {
        assertEquals("", signatureBlock("", delimiter = false))
        assertEquals("", signatureBlock("   \n ", delimiter = false))
        assertEquals("", bodyWithSignature(quoted = "", signature = "", delimiter = false))
    }

    @Test fun withoutTheDelimiterASeparatorTypedIntoTheFieldIsTheOnlyOne() {
        // The whole reason for the switch: "__" (or anything else) typed in the signature field
        // reaches the message untouched, and the app adds no line of its own on top of it.
        val own = "__\nAlex Rivera"
        assertEquals("\n\n__\nAlex Rivera", signatureBlock(own, delimiter = false))
        assertEquals("\n\n-- \n__\nAlex Rivera", signatureBlock(own, delimiter = true))
    }

    @Test fun withoutTheDelimiterTheReplyLayoutIsUnchanged() {
        val quote = "\n\nOn …, Alice wrote:\n> hi"
        assertEquals(
            "\n\nAlex Rivera\nAcme\n\nOn …, Alice wrote:\n> hi",
            bodyWithSignature(quote, sig, delimiter = false),
        )
        assertEquals(
            "\n\nOn …, Alice wrote:\n> hi\n\nAlex Rivera\nAcme",
            bodyWithSignature(quote, sig, signatureBelowQuote = true, delimiter = false),
        )
    }
}
