package app.sterna.ui.settings

import app.sterna.ui.compose.SIGNATURE_DELIMITER
import app.sterna.ui.compose.signatureBlock

/**
 * Display rules for the Identities editor: which row opens on entry, what a collapsed row says
 * about its signature, and what the signature will look like once the composer adds its delimiter.
 *
 * Everything here is presentation only. Nothing is persisted and nothing touches stored data, so
 * reopening the screen re-derives the same state from the account.
 */

/**
 * One row of the editor, in display order: the server group first, then the manual group.
 *
 * [aliasIds] carries the other ids that may point at the same row — a server identity edited through
 * a manual override can be recorded under either id, and both must match the default sender.
 */
internal data class IdentityRowRef(val rowId: String, val aliasIds: List<String> = emptyList())

/**
 * The row left expanded when the editor is built: the **default sender**, so what is open is what
 * gets used when writing. Position would mean nothing to the reader, so it is only the fallback:
 * with no default set (or one pointing at a row that is gone), the first row of the first group
 * opens. Returns null only when there is no row at all.
 */
internal fun initialExpandedIdentityId(
    rows: List<IdentityRowRef>,
    defaultIdentityId: String?,
): String? {
    if (rows.isEmpty()) return null
    val default = defaultIdentityId?.takeIf { it.isNotBlank() }
    val match = default?.let { id -> rows.firstOrNull { it.rowId == id || id in it.aliasIds } }
    return (match ?: rows.first()).rowId
}

/** What a collapsed row reports about its signature. */
internal enum class SignatureState { HTML, TEXT, NONE }

/**
 * An imported HTML signature always keeps a flattened text version alongside it, so the HTML half
 * decides first: a row with both is an HTML signature, not a text one.
 */
internal fun signatureStateOf(signature: String, signatureHtml: String): SignatureState = when {
    signatureHtml.isNotBlank() -> SignatureState.HTML
    signature.isNotBlank() -> SignatureState.TEXT
    else -> SignatureState.NONE
}

/**
 * What the composer will actually put in the message for [signature]: the delimiter line the app
 * adds itself, then the signature as typed. Null when there is nothing to show, i.e. when a blank
 * signature adds nothing at all.
 *
 * The delimiter is NOT stored in the field — it is added when the body is built ([signatureBlock]),
 * so whoever types a signature never sees it, types their own, and ends up sending two of them
 * (#90). This is the answer to that: show it, store nothing. Derived from [signatureBlock] itself
 * (minus the blank line that separates the block from the message above it), so the preview and the
 * real thing cannot drift apart.
 */
internal fun signaturePreview(signature: String): String? =
    signatureBlock(signature).trimStart('\n').takeIf { it.isNotEmpty() }

/**
 * Whether [signature] already opens with a delimiter line of its own — the typed "-- " that the
 * app's own delimiter would then be stacked on top of. Reported, never removed: the field holds the
 * user's text and the app does not rewrite it behind their back. The preview shows both lines, so
 * the duplicate is visible rather than merely announced.
 *
 * Only the first line counts: a "--" further down is part of what they wrote. It is read off the
 * TRIMMED signature, exactly as [signatureBlock] builds it, so a delimiter typed under a blank line
 * — which the block strips, leaving the two delimiters adjacent — is caught too. A trailing space
 * makes no difference either: "--" alone is the same duplicate as the full "-- ".
 */
internal fun signatureHasOwnDelimiter(signature: String): Boolean {
    val first = signature.trim().lineSequence().firstOrNull() ?: return false
    return first.trimEnd() == SIGNATURE_DELIMITER.trimEnd()
}

/** Expand or collapse [rowId]; the set is the whole state, and it is never saved. */
internal fun Set<String>.toggleIdentityRow(rowId: String): Set<String> =
    if (rowId in this) this - rowId else this + rowId
