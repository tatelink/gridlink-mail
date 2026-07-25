package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signature is ordinary text in the body (inserted when compose opens), never appended at send
 * time: what the composer shows is what leaves. These cover the insertion, the "is the block still
 * intact?" rule that governs both the HTML substitution and the From-change swap, and the
 * format=flowed guard (an html alternative is emitted even with no signature).
 */
class ComposeSignatureTest {

    private val sig = "Alex Rivera\nAcme"

    // --- Insertion ------------------------------------------------------------------------------

    @Test fun newMessageOpensWithADelimitedSignatureBlock() {
        assertEquals("\n\n-- \nAlex Rivera\nAcme", bodyWithSignature(quoted = "", signature = sig))
    }

    @Test fun noSignatureLeavesTheBodyExactlyAsItWas() {
        assertEquals("", signatureBlock(""))
        assertEquals("", signatureBlock("   \n "))
        assertEquals("", bodyWithSignature(quoted = "", signature = ""))
        assertEquals("\n\nOn …, Alice wrote:\n> hi", bodyWithSignature("\n\nOn …, Alice wrote:\n> hi", ""))
    }

    @Test fun replyPutsTheSignatureAboveTheQuoteByDefault() {
        val quote = "\n\nOn …, Alice wrote:\n> hi"
        val body = bodyWithSignature(quote, sig)
        assertTrue(body.indexOf("-- ") < body.indexOf("Alice wrote"))
        assertEquals("\n\n-- \nAlex Rivera\nAcme\n\nOn …, Alice wrote:\n> hi", body)
    }

    @Test fun replyCanPutTheSignatureBelowTheQuote() {
        val quote = "\n\nOn …, Alice wrote:\n> hi"
        val body = bodyWithSignature(quote, sig, signatureBelowQuote = true)
        assertTrue(body.indexOf("Alice wrote") < body.indexOf("-- "))
        assertEquals("\n\nOn …, Alice wrote:\n> hi\n\n-- \nAlex Rivera\nAcme", body)
    }

    @Test fun theSignatureIsTrimmedButKeepsItsInnerLineBreaks() {
        assertEquals("\n\n-- \nAlex\nAcme", bodyWithSignature("", "  \nAlex\nAcme\n  "))
    }

    // --- From change (D5): swap only an untouched block -----------------------------------------

    @Test fun changingIdentitySwapsAnUntouchedSignature() {
        val body = "Hi Bob," + bodyWithSignature("", sig)
        assertEquals("Hi Bob,\n\n-- \nAlex (work)", replaceSignatureBlock(body, sig, "Alex (work)"))
    }

    @Test fun changingIdentityLeavesAnEditedSignatureAlone() {
        val edited = "Hi Bob,\n\n-- \nAlex Rivera\nAcme — mobile only"
        assertNull(replaceSignatureBlock(edited, sig, "Alex (work)"))
    }

    @Test fun changingIdentityLeavesADeletedSignatureDeleted() {
        assertNull(replaceSignatureBlock("Hi Bob,", sig, "Alex (work)"))
    }

    @Test fun switchingToASignaturelessIdentityRemovesTheBlock() {
        val body = "Hi Bob," + bodyWithSignature("", sig)
        assertEquals("Hi Bob,", replaceSignatureBlock(body, sig, ""))
    }

    @Test fun anIdentityWithNoSignatureInsertsNothingIntoTextAlreadyWritten() {
        // Nothing to match, so nothing is rearranged: the user's text is never reshuffled.
        assertNull(replaceSignatureBlock("Hi Bob,", "", "Alex (work)"))
    }

    @Test fun onlyTheSignatureBlockIsSwapped_notAQuotedCopyOfIt() {
        // A reply quoting a previous message that ended with the same signature: the LAST block
        // (the live one, at the bottom) is the one swapped; the quoted copy above stays as quoted.
        val body = "Hi\n\n> \n> -- \n> Alex Rivera\n> Acme" + bodyWithSignature("", sig)
        val swapped = replaceSignatureBlock(body, sig, "Alex (work)")!!
        assertTrue(swapped.contains("> -- \n> Alex Rivera"))
        assertEquals("Hi\n\n> \n> -- \n> Alex Rivera\n> Acme\n\n-- \nAlex (work)", swapped)
    }

    // --- Send time: html alternative + HTML signature substitution -------------------------------

    @Test fun anHtmlAlternativeIsAlwaysProducedEvenWithNoSignature() {
        // The format=flowed guard: a lone text/plain body gets reflowed to one line by some servers
        // (Stalwart). The explicit <br> survives that, so the html alternative is never skipped.
        assertEquals("line one<br>line two", htmlBodyWithSignature("line one\nline two", "", ""))
    }

    @Test fun htmlSignatureIsSubstitutedForTheIntactBlock() {
        val body = "Hi Bob," + bodyWithSignature("", sig)
        assertEquals(
            "Hi Bob,<br><br>-- <br><b>Alex Rivera</b><br>Acme",
            htmlBodyWithSignature(body, sig, "<b>Alex Rivera</b><br>Acme"),
        )
    }

    @Test fun anEditedSignatureWinsOverTheStoredHtml() {
        val edited = "Hi Bob,\n\n-- \nAlex Rivera\nAcme — mobile only"
        assertEquals(
            "Hi Bob,<br><br>-- <br>Alex Rivera<br>Acme — mobile only",
            htmlBodyWithSignature(edited, sig, "<b>Alex Rivera</b>"),
        )
    }

    @Test fun aDeletedSignatureIsNotResurrectedInHtml() {
        assertEquals("Hi Bob,", htmlBodyWithSignature("Hi Bob,", sig, "<b>Alex</b>"))
    }

    @Test fun theQuoteBelowTheSignatureSurvivesTheSubstitution() {
        // Signature above the quote (the default): what follows the block must come through.
        val body = bodyWithSignature("\n\nOn …, Alice wrote:\n> hi", sig)
        assertEquals(
            "<br><br>-- <br><b>Alex</b><br><br>On …, Alice wrote:<br>&gt; hi",
            htmlBodyWithSignature(body, sig, "<b>Alex</b>"),
        )
    }

    @Test fun aSignatureRunOnByTheUserIsNotSubstituted() {
        // Text appended to the signature's last line means the user edited it: no substitution,
        // and the plain text they typed is what the recipient sees in both alternatives.
        val edited = "Hi" + bodyWithSignature("", sig) + " (mobile)"
        assertEquals(
            "Hi<br><br>-- <br>Alex Rivera<br>Acme (mobile)",
            htmlBodyWithSignature(edited, sig, "<b>Alex</b>"),
        )
    }

    @Test fun theBodyIsStillEscapedAroundTheSubstitutedSignature() {
        val body = "1 < 2 & 3 > 2" + bodyWithSignature("", sig)
        assertEquals(
            "1 &lt; 2 &amp; 3 &gt; 2<br><br>-- <br><b>Alex</b>",
            htmlBodyWithSignature(body, sig, "<b>Alex</b>"),
        )
    }

    @Test fun aPlainTextSignatureIsJustEscapedLikeTheRestOfTheBody() {
        val body = "Hi" + bodyWithSignature("", "Alex & co")
        assertEquals("Hi<br><br>-- <br>Alex &amp; co", htmlBodyWithSignature(body, "Alex & co", ""))
    }

    // --- Draft / undo: the body already carries its signature ------------------------------------

    @Test fun aReopenedBodyIsSentBackUnchanged() {
        // A reopened draft (or an undone send) comes back with the signature already inside it.
        // Nothing appends a second one: the html alternative is just the same body, escaped.
        val saved = "Hi Bob," + bodyWithSignature("", sig)
        assertEquals(
            "Hi Bob,<br><br>-- <br>Alex Rivera<br>Acme",
            htmlBodyWithSignature(saved, sig, ""),
        )
        assertEquals(1, Regex("-- ").findAll(saved).count())
    }
}
