package app.sterna.ui.compose

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeTextTest {
    @Test fun detectsAttachmentMentionsAcrossLanguages() {
        val positives = listOf(
            "Please see the attached file",          // en
            "Voir le document ci-joint",             // fr
            "Voici la pièce jointe",                 // fr
            "Details im Anhang",                     // de
            "Te envío el documento adjunto",         // es
            "Trovi il file in allegato",             // it
            "Segue o documento em anexo",            // pt
            "Zie de bijlage",                        // nl
            "Смотри вложение",                       // ru
            "W załączeniu przesyłam plik",           // pl
        )
        positives.forEach { assertTrue("should flag: $it", mentionsAttachment(it)) }
    }

    @Test fun caseInsensitive() {
        assertTrue(mentionsAttachment("SEE THE ATTACHED FILE"))
        assertTrue(mentionsAttachment("PIÈCE JOINTE ci-dessous"))
    }

    @Test fun ignoresUnrelatedText() {
        listOf(
            "Hello, let's meet for lunch tomorrow",
            "Thanks for the quick reply",
            "Bonjour, à demain",
            "Re: Project Phoenix review",
        ).forEach { assertFalse("should not flag: $it", mentionsAttachment(it)) }
    }

    // --- Forwarding an HTML email while preserving its formatting ---

    private val sampleHtml = """
        <html><head><style>p{color:red}</style></head>
        <body>
          <p>Hello <strong>bold</strong> and <a href="https://example.com">link</a>.</p>
          <ul><li>one</li><li>two</li></ul>
          <script>alert('x')</script>
          <img src="cid:logo123@mail" alt="logo">
        </body></html>
    """.trimIndent()

    @Test fun forwardedHtmlPreservesOriginalFormatting() {
        val blocks = buildForwardedBlocks(
            from = "Alice <alice@example.com>", subject = "Quarterly report",
            date = "Mon, 1 Jun 2026 10:00:00 +0000", to = "Bob <bob@example.com>",
            originalText = "Hello bold and link.", originalHtml = sampleHtml,
        )
        assertTrue("strong kept", blocks.html.contains("<strong>bold</strong>"))
        assertTrue("list kept", blocks.html.contains("<li>one</li>") && blocks.html.contains("<li>two</li>"))
        assertTrue("link kept", blocks.html.contains("<a href=\"https://example.com\">link</a>"))
    }

    @Test fun forwardedHeaderPresentInBothOutputs() {
        val blocks = buildForwardedBlocks(
            from = "Alice <alice@example.com>", subject = "Quarterly report",
            date = "Mon, 1 Jun 2026 10:00:00 +0000", to = "Bob <bob@example.com>",
            originalText = "body", originalHtml = sampleHtml,
        )
        listOf(blocks.text, blocks.html).forEach { out ->
            assertTrue("forward header: $out", out.contains("Forwarded message"))
            assertTrue("From present: $out", out.contains("Alice <alice@example.com>") ||
                out.contains("Alice &lt;alice@example.com&gt;"))
            assertTrue("Subject present: $out", out.contains("Quarterly report"))
        }
    }

    @Test fun forwardedHtmlStripsScriptAndStyle() {
        val cleaned = cleanForwardedHtml(sampleHtml)
        assertFalse("no <script>", cleaned.contains("<script", ignoreCase = true))
        assertFalse("no alert body", cleaned.contains("alert('x')"))
        assertFalse("no <style>", cleaned.contains("<style", ignoreCase = true))
        assertFalse("no head", cleaned.contains("<head", ignoreCase = true))
    }

    @Test fun forwardedHtmlNeutralizesUncarriedCidImagesAsFallback() {
        // No image was carried (empty carriedCids) → the cid image is neutralised, not left broken.
        val cleaned = cleanForwardedHtml(sampleHtml)
        assertFalse("no cid src", cleaned.contains("cid:", ignoreCase = true))
        assertFalse("no leftover img tag", cleaned.contains("<img", ignoreCase = true))
        assertTrue("placeholder present", cleaned.contains("[image]"))
    }

    @Test fun forwardedHtmlKeepsCarriedCidImage() {
        // The image IS being re-attached (cid in carriedCids) → keep the <img src="cid:..."> intact.
        val cleaned = cleanForwardedHtml(sampleHtml, carriedCids = setOf("logo123@mail"))
        assertTrue("img kept", cleaned.contains("src=\"cid:logo123@mail\"", ignoreCase = true) ||
            cleaned.contains("cid:logo123@mail"))
        assertTrue("img tag kept", cleaned.contains("<img", ignoreCase = true))
        assertFalse("no placeholder", cleaned.contains("[image]"))
    }

    @Test fun carriedCidNormalisesAngleBrackets() {
        // The original src has no brackets; carriedCids supplied them — they must still match.
        val cleaned = cleanForwardedHtml(sampleHtml, carriedCids = setOf("<logo123@mail>"))
        assertTrue("img kept despite bracket mismatch", cleaned.contains("cid:logo123@mail"))
        assertFalse("no placeholder", cleaned.contains("[image]"))
    }

    @Test fun mixOfCarriedAndUncarriedCidImages() {
        val html = "<img src=\"cid:keep@x\"><img src=\"cid:drop@y\">"
        val cleaned = cleanForwardedHtml(html, carriedCids = setOf("keep@x"))
        assertTrue("carried kept", cleaned.contains("cid:keep@x"))
        assertFalse("uncarried dropped", cleaned.contains("cid:drop@y"))
        assertTrue("placeholder for the dropped one", cleaned.contains("[image]"))
    }

    @Test fun buildForwardedKeepsCarriedInlineImage() {
        val blocks = buildForwardedBlocks(
            from = "Alice", subject = "S", date = "d", to = "Bob",
            originalText = "t", originalHtml = sampleHtml,
            carriedCids = setOf("logo123@mail"),
        )
        assertTrue("carried img survives in html block", blocks.html.contains("cid:logo123@mail"))
    }

    @Test fun cidImageNeutralizedRegardlessOfAttributeOrderAndQuoting() {
        val variants = listOf(
            "<IMG alt='x' SRC=cid:abc>",
            "<img\n  src = \"cid:abc@host\" width=10>",
            "<img class='c' src='cid:zzz'/>",
        )
        variants.forEach { v ->
            val cleaned = cleanForwardedHtml("<p>before</p>$v<p>after</p>")
            assertFalse("cid removed in: $v", cleaned.contains("cid:", ignoreCase = true))
            assertTrue("structure kept around: $v", cleaned.contains("before") && cleaned.contains("after"))
        }
    }

    @Test fun remoteImagesAndStructureSurviveCleaning() {
        val html = "<p>x</p><img src=\"https://example.com/a.png\"><div>y</div>"
        val cleaned = cleanForwardedHtml(html)
        assertTrue("http img kept", cleaned.contains("src=\"https://example.com/a.png\""))
        assertTrue("div kept", cleaned.contains("<div>y</div>"))
    }

    @Test fun plainTextOriginalStillForwards() {
        val blocks = buildForwardedBlocks(
            from = "Alice", subject = "Notes", date = "today", to = "Bob",
            originalText = "line one\nline two", originalHtml = null,
        )
        assertTrue("text carries original", blocks.text.contains("line one\nline two"))
        // No HTML part: the plain text is escaped into the html alternative with <br> breaks.
        assertTrue("html carries original", blocks.html.contains("line one<br>line two"))
        assertTrue("header in text", blocks.text.contains("Forwarded message"))
        assertTrue("header in html", blocks.html.contains("Forwarded message"))
    }

    @Test fun htmlEscapingProtectsAgainstTagInjectionInHeader() {
        val blocks = buildForwardedBlocks(
            from = "<script>evil</script>", subject = "a & b < c", date = "d", to = "e",
            originalText = "t", originalHtml = null,
        )
        assertFalse("from escaped in html", blocks.html.contains("<script>evil"))
        assertTrue("ampersand escaped", blocks.html.contains("a &amp; b &lt; c"))
    }

    // --- Reopening a saved draft in compose (#63) ---

    @Test fun draftFieldsCarryAddressingSubjectAndPlainBody() {
        val draft = Email(
            id = "d1",
            subject = "Half-written",
            to = listOf(EmailAddress(email = "a@example.com"), EmailAddress(name = "B", email = "b@example.com")),
            cc = listOf(EmailAddress(email = "c@example.com")),
            bcc = listOf(EmailAddress(email = "d@example.com")),
            textBody = listOf(EmailBodyPart(partId = "1", type = "text/plain")),
            bodyValues = mapOf("1" to EmailBodyValue("first line\nsecond line")),
        )
        val fields = draftFieldsOf(draft)
        assertEquals("a@example.com, b@example.com", fields.to)
        assertEquals("c@example.com", fields.cc)
        assertEquals("d@example.com", fields.bcc)
        assertEquals("Half-written", fields.subject)
        assertEquals("first line\nsecond line", fields.body)
        assertTrue("cc/bcc row revealed", fields.expand)
    }

    @Test fun draftFieldsFlattenHtmlOnlyBodyToText() {
        // A draft saved by another client may be HTML-only; the plain-text editor gets it
        // flattened with paragraphs preserved, not raw markup on one line.
        val draft = Email(
            id = "d2",
            subject = "Html draft",
            to = listOf(EmailAddress(email = "a@example.com")),
            htmlBody = listOf(EmailBodyPart(partId = "h", type = "text/html")),
            bodyValues = mapOf("h" to EmailBodyValue("<p>one</p><p>two &amp; three</p>")),
        )
        val fields = draftFieldsOf(draft)
        assertEquals("one\ntwo & three", fields.body)
        assertFalse("no cc/bcc row", fields.expand)
    }

    @Test fun draftFieldsTolerateEmptyDraft() {
        val fields = draftFieldsOf(Email(id = "d3"))
        assertEquals("", fields.to)
        assertEquals("", fields.subject)
        assertEquals("", fields.body)
        assertFalse(fields.expand)
    }

    // --- Where the caret lands when compose opens prefilled (#63) ---

    @Test fun reopenedDraftResumesAfterItsLastCharacter() {
        assertEquals(12, initialBodyCaret(bodyLength = 12, isDraft = true, isReply = false))
    }

    @Test fun replyStartsAboveTheQuotedOriginal() {
        assertEquals(0, initialBodyCaret(bodyLength = 200, isDraft = false, isReply = true))
    }

    @Test fun newMailAndForwardLeaveTheBodyUnfocused() {
        assertEquals(null, initialBodyCaret(bodyLength = 0, isDraft = false, isReply = false))
    }

    // --- Where a tap on a header row puts the caret (#26) ---
    //
    // Geometry of a To row on a 1080px-wide phone: the row starts at x=0, the label spans 42→168
    // and the editable text starts at 200.

    @Test fun tapOnTheLabelGoesToTheStartOfTheText() {
        assertEquals(0, headerTapCaret(tapX = 100f, textStartX = 200f, textLength = 21))
    }

    @Test fun tapJustBeforeTheFirstCharacterGoesToTheStart() {
        assertEquals(0, headerTapCaret(tapX = 199f, textStartX = 200f, textLength = 21))
    }

    @Test fun tapOnTheTextItselfIsLeftToTheField() {
        // The leading edge belongs to the field: from there on the caret lands under the finger.
        assertEquals(null, headerTapCaret(tapX = 200f, textStartX = 200f, textLength = 21))
        assertEquals(null, headerTapCaret(tapX = 260f, textStartX = 200f, textLength = 21))
    }

    @Test fun tapPastTheEndOfTheTextIsLeftToTheField() {
        // Empty space after the text: the field keeps its own handling, i.e. the caret at the end.
        assertEquals(null, headerTapCaret(tapX = 900f, textStartX = 200f, textLength = 21))
    }

    @Test fun tapOnAnEmptyFieldForcesNothing() {
        assertEquals(null, headerTapCaret(tapX = 100f, textStartX = 200f, textLength = 0))
    }

    @Test fun unknownGeometryForcesNothing() {
        // Before the first layout, or a field whose text isn't composed (a collapsed chip row).
        assertEquals(null, headerTapCaret(tapX = Float.NaN, textStartX = 200f, textLength = 21))
        assertEquals(null, headerTapCaret(tapX = 100f, textStartX = Float.NaN, textLength = 21))
    }

    // --- Reply / reply-all header derivation (works from a cached row, so offline replies address) ---

    private val originalToReply = Email(
        id = "m1",
        subject = "Project Phoenix",
        from = listOf(EmailAddress(name = "Alice", email = "alice@example.com")),
        to = listOf(
            EmailAddress(email = "me@example.com"),
            EmailAddress(name = "Bob", email = "bob@example.com"),
        ),
        cc = listOf(EmailAddress(email = "carol@example.com")),
    )

    @Test fun replyGoesToTheOriginalSender() {
        assertEquals("alice@example.com", replyRecipient(originalToReply))
    }

    @Test fun replyRecipientEmptyWhenSenderUnknown() {
        assertEquals("", replyRecipient(Email(id = "x")))
    }

    @Test fun replyAllIncludesSenderToAndCcButNotSelf() {
        val all = replyAllRecipients(originalToReply, setOf("me@example.com"))
        assertEquals("alice@example.com, bob@example.com, carol@example.com", all)
    }

    @Test fun replyAllDropsSelfCaseInsensitivelyAndDeduplicates() {
        val o = originalToReply.copy(
            cc = listOf(EmailAddress(email = "ME@Example.com"), EmailAddress(email = "alice@example.com")),
        )
        // "ME@Example.com" == self (ignore case) is removed; the duplicate alice is collapsed.
        assertEquals("alice@example.com, bob@example.com", replyAllRecipients(o, setOf("me@example.com")))
    }

    @Test fun replyAllExcludesEveryAliasOfTheAccount() {
        // Three addresses on the account: none of them may end up in the recipients (B5). The
        // original was sent to two of them and Cc'd the third.
        val mine = setOf("me@example.com", "Alias@Example.com", "third@example.com")
        val o = Email(
            id = "m2",
            from = listOf(EmailAddress(email = "alice@example.com")),
            to = listOf(
                EmailAddress(email = "me@example.com"),
                EmailAddress(email = "alias@example.com"),
                EmailAddress(email = "bob@example.com"),
            ),
            cc = listOf(EmailAddress(email = "THIRD@example.com")),
        )
        assertEquals("alice@example.com, bob@example.com", replyAllRecipients(o, mine))
    }

    @Test fun replyAllToYourOwnAliasStillAnswersTheSender() {
        // The original came FROM one of your aliases: it drops out of the recipients too.
        val o = Email(
            id = "m3",
            from = listOf(EmailAddress(email = "alias@example.com")),
            to = listOf(EmailAddress(email = "bob@example.com")),
        )
        assertEquals(
            "bob@example.com",
            replyAllRecipients(o, setOf("me@example.com", "alias@example.com")),
        )
    }

    @Test fun subjectGetsRePrefixOnlyWhenMissing() {
        assertEquals("Re: Project Phoenix", withPrefix("Project Phoenix", "Re:"))
        assertEquals("Re: Project Phoenix", withPrefix("Re: Project Phoenix", "Re:"))
        assertEquals("RE: already", withPrefix("RE: already", "Re:")) // existing prefix kept, any case
        assertEquals("Fwd: ", withPrefix(null, "Fwd:"))
    }

    // --- Late-arriving quote must not clobber the user's typing (cache-first ordering) ---

    @Test fun quoteAppliesOntoTheUntouchedInitialBody() {
        // Header prefill applied, body still equals its baseline ("" for a reply) → apply the quote.
        assertTrue(canApplyReplyQuote(applied = true, bodyText = "", initialBody = ""))
    }

    @Test fun quoteSkippedWhenUserHasStartedTyping() {
        assertFalse(canApplyReplyQuote(applied = true, bodyText = "Hi there", initialBody = ""))
    }

    @Test fun quoteWaitsUntilHeaderPrefillApplied() {
        assertFalse(canApplyReplyQuote(applied = false, bodyText = "", initialBody = ""))
    }

    // --- Quoting cuts the sender's signature off (D4), on a strict delimiter only ---

    @Test fun quotedOriginalStopsAtTheSignatureDelimiter() {
        val original = "Sounds good.\n\n-- \nAlice\nAcme Ltd\n+33 1 23 45 67 89"
        assertEquals("Sounds good.", cutAtSignatureDelimiter(original))
    }

    @Test fun theTwoHyphenFormWithoutTrailingSpaceAlsoCuts() {
        assertEquals("Sounds good.", cutAtSignatureDelimiter("Sounds good.\n\n--\nAlice"))
    }

    @Test fun aDecorativeRuleIsNotADelimiter() {
        val original = "Part one\n----------\nPart two"
        assertEquals(original, cutAtSignatureDelimiter(original))
    }

    @Test fun aLineMerelyStartingWithHyphensIsNotADelimiter() {
        val original = "Agenda\n--- end ---\nSee you"
        assertEquals(original, cutAtSignatureDelimiter(original))
        assertEquals("a\n-- b\nc", cutAtSignatureDelimiter("a\n-- b\nc"))
    }

    @Test fun theFirstDelimiterWins() {
        assertEquals("Body", cutAtSignatureDelimiter("Body\n-- \nSig one\n-- \nSig two"))
    }

    @Test fun anOriginalWithoutASignatureIsQuotedWhole() {
        assertEquals("Just a line", cutAtSignatureDelimiter("Just a line"))
    }

    @Test fun quotingCutsTheSignatureButReopeningADraftDoesNot() {
        val mail = Email(
            id = "q1",
            textBody = listOf(EmailBodyPart(partId = "1", type = "text/plain")),
            bodyValues = mapOf("1" to EmailBodyValue("Hello\n\n-- \nAlice")),
        )
        assertEquals("Hello", quotedOriginalText(mail))
        // A draft is the user's own text: cutting it at its delimiter would delete their signature.
        assertEquals("Hello\n\n-- \nAlice", draftFieldsOf(mail).body)
    }

    // --- Forwarded header: labels translated, format untouched (D7) ---

    @Test fun forwardedHeaderUsesTheSuppliedLabels() {
        val blocks = buildForwardedBlocks(
            from = "Alice", subject = "Notes", date = "4 juil. 2026, 09:12", to = "Bob",
            originalText = "body", originalHtml = null,
            labels = ForwardLabels(from = "De", subject = "Objet", date = "Date", to = "À"),
        )
        assertTrue(blocks.text.contains("De: Alice"))
        assertTrue(blocks.text.contains("Objet: Notes"))
        assertTrue(blocks.text.contains("Date: 4 juil. 2026, 09:12"))
        assertTrue(blocks.text.contains("À: Bob"))
        assertTrue(blocks.html.contains("Objet: Notes"))
    }

    @Test fun forwardedHeaderKeepsItsDashedLineAndFieldOrder() {
        val blocks = buildForwardedBlocks(
            from = "Alice", subject = "Notes", date = "d", to = "Bob",
            originalText = "body", originalHtml = null,
            labels = ForwardLabels(from = "De", subject = "Objet", date = "Date", to = "À"),
        )
        assertTrue(blocks.text.startsWith("---------- Forwarded message ----------\n"))
        val order = listOf("De:", "Objet:", "Date:", "À:").map { blocks.text.indexOf(it) }
        assertEquals(order.sorted(), order)
    }
}
