package app.gridlink.ui.gridlink

import app.gridlink.ui.settings.signatureBlock

/**
 * Where a signature goes when the composer opens.
 *
 * 🔴 Until 2026-08-12 nothing in this app wrote a signature into a message. Three settings were
 * offered for it (the identity's signature text, "Add signature to replies", "Separator line above
 * the signature") and the composer read none of them: the account editor rendered a preview of a
 * block no message ever carried. Tate's call on the settings audit was to wire it.
 *
 * ## Appended, never prepended
 * The block goes at the END of whatever the draft already holds, and that is a constraint rather
 * than a preference: [GridlinkComposeDraft.bodySpans] are character ranges over the body text, so
 * inserting anything ahead of them silently shifts every mark in the message. Appending leaves
 * them where they are. The CARET is what puts the user above the signature, not the text order.
 *
 * ## The one setting this cannot honour
 * "Signature below quoted text" is not decided here, because a Gridlink reply carries no quoted
 * text to be below. The composer shows the original as a collapsible label and sends In-Reply-To
 * and References headers; it does not paste the previous message into the body. Deciding the
 * placement against a quote that is not in the string would be a coin flip dressed as a setting.
 */

/**
 * The body a composer should open with, or null when the draft should open exactly as passed.
 *
 * Null rather than "the unchanged body" so the caller can tell "nothing to add" from "add this",
 * and skip the state write entirely in the ordinary case of an account with no signature.
 *
 * [isReply] is a reply or a forward, which is the case [onReplies] governs. A resumed draft is not
 * this function's business at all: it already contains whatever signature it was written with, and
 * the caller must not ask.
 */
internal fun composeBodyWithSignature(
    body: String,
    signature: String,
    isReply: Boolean,
    onReplies: Boolean,
    delimiter: Boolean,
): String? {
    if (signature.isBlank()) return null
    if (isReply && !onReplies) return null
    // ⚠️ The block is BUILT in the settings package rather than spelled out a second time here. The
    // account editor renders its preview from that same function, so a second copy would be a
    // preview that agrees with what actually gets sent only until somebody fixes one of them. It is
    // RFC 3676 §4.3's "-- " line, which is what other clients cut a quote at.
    return body + signatureBlock(signature, delimiter)
}
