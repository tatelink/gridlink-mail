package app.gridlink.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signature is ordinary text in the body (inserted when compose opens), never appended at send
 * time: what the composer shows is what leaves. These cover the insertion, the "is the block still
 * intact?" rule that governs both the HTML substitution and the From-change swap, and the
 * format=flowed guard (an html alternative is emitted even with no signature).
 *
 * Every case is run with the "-- " delimiter ON (the default, and the shape the app has always
 * written); the block below covers the same functions with it OFF (#90), plus the case the setting
 * makes possible: a body written in one shape and read back in the other.
 */
class ComposeSignatureTest {

    private val sig = "Alex Rivera\nAcme"

    // --- Insertion ------------------------------------------------------------------------------

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
        val body = bodyWithSignature(quote, sig, delimiter = true)
        assertTrue(body.indexOf("-- ") < body.indexOf("Alice wrote"))
        assertEquals("\n\n-- \nAlex Rivera\nAcme\n\nOn …, Alice wrote:\n> hi", body)
    }

    @Test fun replyCanPutTheSignatureBelowTheQuote() {
        val quote = "\n\nOn …, Alice wrote:\n> hi"
        val body = bodyWithSignature(quote, sig, signatureBelowQuote = true, delimiter = true)
        assertTrue(body.indexOf("Alice wrote") < body.indexOf("-- "))
        assertEquals("\n\nOn …, Alice wrote:\n> hi\n\n-- \nAlex Rivera\nAcme", body)
    }

    @Test fun theSignatureIsTrimmedButKeepsItsInnerLineBreaks() {
        assertEquals("\n\n-- \nAlex\nAcme", bodyWithSignature("", "  \nAlex\nAcme\n  ", delimiter = true))
    }

    // --- From change (D5) -----------------------------------------------------------------------
    // Three cases, and only three: the identity being left had a signature and its block is still
    // there → swap it; it had one and the block is gone → the user deleted it deliberately, leave
    // the body alone; it had none → nothing can have been deleted, so insert the new one where the
    // prefill would have put it.

    @Test fun changingIdentitySwapsAnUntouchedSignature() {
        val body = "Hi Bob," + bodyWithSignature("", sig, delimiter = true)
        assertEquals(
            "Hi Bob,\n\n-- \nAlex (work)",
            replaceSignatureBlock(body, sig, "Alex (work)", delimiter = true),
        )
    }

    @Test fun changingIdentityLeavesAnEditedSignatureAlone() {
        val edited = "Hi Bob,\n\n-- \nAlex Rivera\nAcme — mobile only"
        assertNull(replaceSignatureBlock(edited, sig, "Alex (work)", delimiter = true))
    }

    @Test fun changingIdentityLeavesADeletedSignatureDeleted() {
        assertNull(replaceSignatureBlock("Hi Bob,", sig, "Alex (work)", delimiter = true))
    }

    @Test fun switchingToASignaturelessIdentityRemovesTheBlock() {
        val body = "Hi Bob," + bodyWithSignature("", sig, delimiter = true)
        assertEquals("Hi Bob,", replaceSignatureBlock(body, sig, "", delimiter = true))
    }

    @Test fun theSwapNeverInvents_aBlankOutgoingSignatureIsTheInsertCase() {
        // replaceSignatureBlock is the swap half only: with nothing to match it declines, and the
        // caller reaches for insertSignatureBlock instead (tested below).
        assertNull(replaceSignatureBlock("Hi Bob,", "", "Alex (work)", delimiter = true))
    }

    // --- From change, third case: the identity being left had NO signature ----------------------

    @Test fun leavingASignaturelessIdentityInsertsTheNewOneAtTheEndOfANewMessage() {
        assertEquals(
            "Hi Bob,\n\n-- \nAlex (work)",
            insertSignatureBlock("Hi Bob,", "Alex (work)", delimiter = true),
        )
    }

    @Test fun onAnEmptyNewMessageTheInsertMatchesWhatThePrefillWouldHaveWritten() {
        assertEquals(
            bodyWithSignature("", sig, delimiter = true),
            insertSignatureBlock("", sig, delimiter = true),
        )
    }

    @Test fun onAReplyTheSignatureGoesAboveTheQuote_underTheAnswerBeingWritten() {
        // The layout a reply opens with: answer, signature, quote. Inserting must reproduce it,
        // not drop the signature at the very top above what the user has already typed.
        val quote = "\n\nOn …, Alice wrote:\n> hi"
        val body = "Hi Bob,$quote"
        assertEquals(
            "Hi Bob,\n\n-- \nAlex Rivera\nAcme\n\nOn …, Alice wrote:\n> hi",
            insertSignatureBlock(body, sig, quote, signatureBelowQuote = false, delimiter = true),
        )
    }

    @Test fun onAReplyTheBelowQuoteSettingPutsItAtTheEnd() {
        val quote = "\n\nOn …, Alice wrote:\n> hi"
        assertEquals(
            "Hi Bob,$quote\n\n-- \nAlex Rivera\nAcme",
            insertSignatureBlock("Hi Bob,$quote", sig, quote, signatureBelowQuote = true, delimiter = true),
        )
    }

    @Test fun anUntouchedReplyEndsUpExactlyAsThePrefillWouldHaveBuiltIt() {
        val quote = "\n\nOn …, Alice wrote:\n> hi"
        assertEquals(
            bodyWithSignature(quote, sig, delimiter = true),
            insertSignatureBlock(quote, sig, quote, delimiter = true),
        )
        assertEquals(
            bodyWithSignature(quote, sig, signatureBelowQuote = true, delimiter = true),
            insertSignatureBlock(quote, sig, quote, signatureBelowQuote = true, delimiter = true),
        )
    }

    @Test fun aQuoteTheUserHasEditedAwayFallsBackToTheEnd() {
        // The tail is no longer the quote we opened with: rather than guess a spot inside the
        // user's text, the block goes at the end where it is visible and movable.
        val quote = "\n\nOn …, Alice wrote:\n> hi"
        assertEquals(
            "Hi Bob, (quote deleted)\n\n-- \nAlex Rivera\nAcme",
            insertSignatureBlock("Hi Bob, (quote deleted)", sig, quote, delimiter = true),
        )
    }

    @Test fun insertingABlankSignatureIsANoOp() {
        assertEquals("Hi Bob,", insertSignatureBlock("Hi Bob,", "", delimiter = true))
        assertEquals("Hi Bob,", insertSignatureBlock("Hi Bob,", "   ", delimiter = true))
    }

    @Test fun onlyTheSignatureBlockIsSwapped_notAQuotedCopyOfIt() {
        // A reply quoting a previous message that ended with the same signature: the LAST block
        // (the live one, at the bottom) is the one swapped; the quoted copy above stays as quoted.
        val body = "Hi\n\n> \n> -- \n> Alex Rivera\n> Acme" + bodyWithSignature("", sig, delimiter = true)
        val swapped = replaceSignatureBlock(body, sig, "Alex (work)", delimiter = true)!!
        assertTrue(swapped.contains("> -- \n> Alex Rivera"))
        assertEquals("Hi\n\n> \n> -- \n> Alex Rivera\n> Acme\n\n-- \nAlex (work)", swapped)
    }

    // --- Send time: html alternative + HTML signature substitution -------------------------------

    @Test fun anHtmlAlternativeIsAlwaysProducedEvenWithNoSignature() {
        // The format=flowed guard: a lone text/plain body gets reflowed to one line by some servers
        // (Stalwart). The explicit <br> survives that, so the html alternative is never skipped.
        assertEquals(
            "line one<br>line two",
            htmlBodyWithSignature("line one\nline two", "", "", delimiter = true),
        )
    }

    @Test fun htmlSignatureIsSubstitutedForTheIntactBlock() {
        val body = "Hi Bob," + bodyWithSignature("", sig, delimiter = true)
        assertEquals(
            "Hi Bob,<br><br>-- <br><b>Alex Rivera</b><br>Acme",
            htmlBodyWithSignature(body, sig, "<b>Alex Rivera</b><br>Acme", delimiter = true),
        )
    }

    @Test fun anEditedSignatureWinsOverTheStoredHtml() {
        val edited = "Hi Bob,\n\n-- \nAlex Rivera\nAcme — mobile only"
        assertEquals(
            "Hi Bob,<br><br>-- <br>Alex Rivera<br>Acme — mobile only",
            htmlBodyWithSignature(edited, sig, "<b>Alex Rivera</b>", delimiter = true),
        )
    }

    @Test fun aDeletedSignatureIsNotResurrectedInHtml() {
        assertEquals("Hi Bob,", htmlBodyWithSignature("Hi Bob,", sig, "<b>Alex</b>", delimiter = true))
    }

    @Test fun theQuoteBelowTheSignatureSurvivesTheSubstitution() {
        // Signature above the quote (the default): what follows the block must come through.
        val body = bodyWithSignature("\n\nOn …, Alice wrote:\n> hi", sig, delimiter = true)
        assertEquals(
            "<br><br>-- <br><b>Alex</b><br><br>On …, Alice wrote:<br>&gt; hi",
            htmlBodyWithSignature(body, sig, "<b>Alex</b>", delimiter = true),
        )
    }

    @Test fun aSignatureRunOnByTheUserIsNotSubstituted() {
        // Text appended to the signature's last line means the user edited it: no substitution,
        // and the plain text they typed is what the recipient sees in both alternatives.
        val edited = "Hi" + bodyWithSignature("", sig, delimiter = true) + " (mobile)"
        assertEquals(
            "Hi<br><br>-- <br>Alex Rivera<br>Acme (mobile)",
            htmlBodyWithSignature(edited, sig, "<b>Alex</b>", delimiter = true),
        )
    }

    @Test fun theBodyIsStillEscapedAroundTheSubstitutedSignature() {
        val body = "1 < 2 & 3 > 2" + bodyWithSignature("", sig, delimiter = true)
        assertEquals(
            "1 &lt; 2 &amp; 3 &gt; 2<br><br>-- <br><b>Alex</b>",
            htmlBodyWithSignature(body, sig, "<b>Alex</b>", delimiter = true),
        )
    }

    @Test fun aPlainTextSignatureIsJustEscapedLikeTheRestOfTheBody() {
        val body = "Hi" + bodyWithSignature("", "Alex & co", delimiter = true)
        assertEquals(
            "Hi<br><br>-- <br>Alex &amp; co",
            htmlBodyWithSignature(body, "Alex & co", "", delimiter = true),
        )
    }

    // --- Draft / undo: the body already carries its signature ------------------------------------

    @Test fun aReopenedBodyIsSentBackUnchanged() {
        // A reopened draft (or an undone send) comes back with the signature already inside it.
        // Nothing appends a second one: the html alternative is just the same body, escaped.
        val saved = "Hi Bob," + bodyWithSignature("", sig, delimiter = true)
        assertEquals(
            "Hi Bob,<br><br>-- <br>Alex Rivera<br>Acme",
            htmlBodyWithSignature(saved, sig, "", delimiter = true),
        )
        assertEquals(1, Regex("-- ").findAll(saved).count())
    }

    // --- The delimiter switched OFF (#90) --------------------------------------------------------
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

    @Test fun withoutTheDelimiterTheFromSwapStillWorks() {
        val body = "Hi Bob," + bodyWithSignature("", sig, delimiter = false)
        assertEquals(
            "Hi Bob,\n\nAlex (work)",
            replaceSignatureBlock(body, sig, "Alex (work)", delimiter = false),
        )
    }

    @Test fun withoutTheDelimiterAnEditedSignatureIsStillLeftAlone() {
        val edited = "Hi Bob,\n\nAlex Rivera\nAcme — mobile only"
        assertNull(replaceSignatureBlock(edited, sig, "Alex (work)", delimiter = false))
    }

    @Test fun withoutTheDelimiterTheInsertMatchesWhatThePrefillWouldHaveWritten() {
        val quote = "\n\nOn …, Alice wrote:\n> hi"
        assertEquals(
            bodyWithSignature("", sig, delimiter = false),
            insertSignatureBlock("", sig, delimiter = false),
        )
        assertEquals(
            bodyWithSignature(quote, sig, delimiter = false),
            insertSignatureBlock(quote, sig, quote, delimiter = false),
        )
        assertEquals(
            bodyWithSignature(quote, sig, signatureBelowQuote = true, delimiter = false),
            insertSignatureBlock(quote, sig, quote, signatureBelowQuote = true, delimiter = false),
        )
    }

    @Test fun withoutTheDelimiterTheHtmlAlternativeCarriesNoDelimiterEither() {
        val body = "Hi Bob," + bodyWithSignature("", sig, delimiter = false)
        assertEquals(
            "Hi Bob,<br><br><b>Alex Rivera</b><br>Acme",
            htmlBodyWithSignature(body, sig, "<b>Alex Rivera</b><br>Acme", delimiter = false),
        )
    }

    @Test fun withoutTheDelimiterTheQuoteBelowTheSignatureStillSurvives() {
        val body = bodyWithSignature("\n\nOn …, Alice wrote:\n> hi", sig, delimiter = false)
        assertEquals(
            "<br><br><b>Alex</b><br><br>On …, Alice wrote:<br>&gt; hi",
            htmlBodyWithSignature(body, sig, "<b>Alex</b>", delimiter = false),
        )
    }

    // --- The trap: a body written in one shape, read back in the other (#90 §3) -------------------
    // The block is found "to the character", and two features hang off that lookup: the From swap
    // (which declines rather than rewrite text the user touched) and the HTML signature
    // substitution. A draft written with the delimiter and reopened after the switch was turned off
    // — or the reverse — must still be recognised, or both go quiet without a word.

    @Test fun aBodyWrittenWithTheDelimiterIsStillFoundOnceTheSettingIsOff() {
        val written = "Hi Bob," + bodyWithSignature("", sig, delimiter = true)
        // Recognised, and rewritten in the shape the setting asks for today.
        assertEquals(
            "Hi Bob,\n\nAlex (work)",
            replaceSignatureBlock(written, sig, "Alex (work)", delimiter = false),
        )
        // The html alternative mirrors the BODY, not the setting: the two alternatives of one
        // message have to say the same thing, and this body still carries its "-- " line.
        assertEquals(
            "Hi Bob,<br><br>-- <br><b>Alex</b>",
            htmlBodyWithSignature(written, sig, "<b>Alex</b>", delimiter = false),
        )
    }

    @Test fun aBodyWrittenWithoutTheDelimiterIsStillFoundOnceTheSettingIsOn() {
        val written = "Hi Bob," + bodyWithSignature("", sig, delimiter = false)
        assertEquals(
            "Hi Bob,\n\n-- \nAlex (work)",
            replaceSignatureBlock(written, sig, "Alex (work)", delimiter = true),
        )
        assertEquals(
            "Hi Bob,<br><br><b>Alex</b>",
            htmlBodyWithSignature(written, sig, "<b>Alex</b>", delimiter = true),
        )
    }

    @Test fun theSettingsOwnShapeWinsWhenABodyCouldBeReadEitherWay() {
        // A body holding BOTH shapes: the live block is the one the composer would write today, so
        // that is the one swapped — the other stays as the user's text.
        val both = "Hi\n\nAlex Rivera\nAcme" + bodyWithSignature("", sig, delimiter = true)
        assertEquals(
            "Hi\n\nAlex Rivera\nAcme\n\n-- \nAlex (work)",
            replaceSignatureBlock(both, sig, "Alex (work)", delimiter = true),
        )
    }

    @Test fun theEditedSignatureRuleHoldsInBothShapes() {
        // Neither shape is a way in for a signature the user has run on: "(mobile)" appended to the
        // last line means edited, and an edited block is never swapped nor substituted.
        val editedWith = "Hi" + bodyWithSignature("", sig, delimiter = true) + " (mobile)"
        val editedWithout = "Hi" + bodyWithSignature("", sig, delimiter = false) + " (mobile)"
        assertNull(replaceSignatureBlock(editedWith, sig, "Alex (work)", delimiter = false))
        assertNull(replaceSignatureBlock(editedWithout, sig, "Alex (work)", delimiter = true))
    }
}
