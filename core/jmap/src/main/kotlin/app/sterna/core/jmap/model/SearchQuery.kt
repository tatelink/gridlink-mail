package app.sterna.core.jmap.model

/**
 * A structured mail search. Non-blank/non-null fields are combined with AND into
 * a JMAP Email/query filter (RFC 8621 §4.4.1). [afterMillis]/[beforeMillis] are
 * epoch-millis bounds on the received date (rendered as UTCDate at the boundary).
 *
 * [recipient] matches `To` OR `Cc` — a single field on purpose: a message addressed to an
 * alias in copy WAS received at that address, so splitting the two would silently drop half
 * the expected hits on an account with aliases or shared mailboxes.
 */
data class SearchQuery(
    val text: String = "",
    val from: String = "",
    val recipient: String = "",
    val subject: String = "",
    val hasAttachment: Boolean = false,
    val afterMillis: Long? = null,
    val beforeMillis: Long? = null,
) {
    fun isEmpty(): Boolean =
        text.isBlank() && from.isBlank() && recipient.isBlank() && subject.isBlank() &&
            !hasAttachment && afterMillis == null && beforeMillis == null
}
