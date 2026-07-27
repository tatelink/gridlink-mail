package app.sterna.ui.settings

/**
 * Display rules for the Identities editor: which row opens on entry, and what a collapsed row says
 * about its signature.
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

/** Expand or collapse [rowId]; the set is the whole state, and it is never saved. */
internal fun Set<String>.toggleIdentityRow(rowId: String): Set<String> =
    if (rowId in this) this - rowId else this + rowId
