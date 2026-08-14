package app.gridlink.ui.settings

/**
 * How a signature turns into text in a message body.
 *
 * 🔴 Kept out of `ui/compose/ComposeText.kt` when the upstream composer was retired, because the
 * Identities editor derives its preview from these functions rather than spelling the delimiter
 * out a second time (#90) — a preview that agrees with the writer by construction instead of by
 * agreement.
 *
 * ⚠️ Between the upstream composer's retirement and 2026-08-12 nothing WROTE a signature into a
 * message: the settings were configurable and inert, and the Identities editor previewed a block no
 * message ever carried. Gridlink's composer now calls [signatureBlock] through
 * `GridlinkSignature.composeBodyWithSignature`, which is the "Gridlink implementation" this file
 * was kept for. One builder, so the preview agrees with what is sent by construction.
 *
 * ⛔ [bodyWithSignature] is the QUOTE-AWARE form and still has no production caller, but the reason
 * changed on 2026-08-14 and the difference matters. A Gridlink reply now DOES quote (see
 * [app.gridlink.ui.gridlink.GridlinkQuote]); what it does not do is put the quote in the editable
 * body. The original is appended at the write, after the body, so a signature written into the body
 * is above the quote by construction — the default every client ships. This is the shape to reach for
 * the day "Signature below the quoted text" is offered as a switch, which needs the quote to be part
 * of the body text. Its store, setter and flow were kept for the same reason.
 */

/** The standard signature delimiter line (RFC 3676 §4.3): two hyphens and a space. */
internal const val SIGNATURE_DELIMITER = "-- "

/**
 * The block a [signature] occupies in a body: a blank line, the delimiter line, then the signature
 * itself. Empty for a blank signature, so every caller can concatenate unconditionally.
 *
 * [delimiter] is the "Separator line above the signature" setting (#90), on by default. Turned off,
 * the block is the blank line and the signature alone: the signature field then holds EXACTLY what
 * goes into the message, so whoever wants "__", a rule, or nothing at all types it there. The
 * setting governs what is WRITTEN; both shapes are still recognised when reading a body back.
 */
internal fun signatureBlock(signature: String, delimiter: Boolean): String = when {
    signature.isBlank() -> ""
    delimiter -> "\n\n$SIGNATURE_DELIMITER\n${signature.trim()}"
    else -> "\n\n${signature.trim()}"
}

/**
 * The composer's initial body: the [quoted] original (empty for a new message) with the signature
 * block placed above it, or below when [signatureBelowQuote] is set. A reply's quote already starts
 * with its own blank lines, so the caret sits at the top of an empty first line either way.
 */
internal fun bodyWithSignature(
    quoted: String,
    signature: String,
    signatureBelowQuote: Boolean = false,
    delimiter: Boolean,
): String {
    val block = signatureBlock(signature, delimiter)
    if (block.isEmpty()) return quoted
    return if (signatureBelowQuote) quoted + block else block + quoted
}
