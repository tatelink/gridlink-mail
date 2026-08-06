package app.gridlink.core.data.account

import app.gridlink.core.data.text.htmlToText
import app.gridlink.core.data.text.looksLikeHtml
import kotlinx.serialization.Serializable

/**
 * A sending identity: a "from" name + address, with its own signature. An account
 * can have several (e.g. a work and a personal alias). The first is the default.
 *
 * The signature is stored twice: [signature] is the plain text the composer inserts into the body
 * and the user edits there, [signatureHtml] the optional imported HTML sent in the html alternative
 * as long as that inserted block comes back untouched. Serialized as JSON by `AccountStore` (not a
 * Room entity), so the added field with a default value reads back old stored accounts unchanged —
 * no migration.
 */
@Serializable
data class StoredIdentity(
    val id: String,
    val name: String,
    val email: String,
    val signature: String = "",
    /** Rich version of [signature], sent as-is in the html alternative. Empty = plain text only. */
    val signatureHtml: String = "",
) {
    /** "Name <email>" or just the address when unnamed. */
    fun display(): String = if (name.isBlank()) email else "$name <$email>"

    /**
     * Split a legacy signature that holds raw HTML in the plain-text [signature] field (what the
     * pre-1.3.13 "Import HTML" button wrote): the HTML moves to [signatureHtml] and [signature]
     * becomes its flattened text, so the composer never shows tag soup. Anything already split, or
     * a genuinely plain signature, is returned unchanged — the split is idempotent and lossless.
     */
    fun withSplitSignature(): StoredIdentity =
        if (signatureHtml.isBlank() && looksLikeHtml(signature)) {
            copy(signature = htmlToText(signature), signatureHtml = signature)
        } else {
            this
        }
}
