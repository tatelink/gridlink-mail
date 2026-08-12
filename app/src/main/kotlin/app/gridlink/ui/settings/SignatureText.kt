package app.gridlink.ui.settings

/**
 * How a signature turns into text in a message body.
 *
 * 🔴 Kept out of `ui/compose/ComposeText.kt` when the upstream composer was retired, because the
 * Identities editor derives its preview from these functions rather than spelling the delimiter
 * out a second time (#90) — a preview that agrees with the writer by construction instead of by
 * agreement.
 *
 * ⚠️ Nothing WRITES a signature into a message today. Upstream's composer did; Gridlink's does not
 * yet, so the signature settings are configurable and currently inert. That is a gap in the
 * Gridlink composer, recorded in the build plan, not a reason to keep 800 lines of retired
 * composer around: this is the part the settings screen actually needs, and it is the part that
 * a Gridlink implementation should call.
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
