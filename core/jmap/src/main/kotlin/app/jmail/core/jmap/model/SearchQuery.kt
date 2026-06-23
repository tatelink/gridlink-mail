package app.jmail.core.jmap.model

/**
 * A structured mail search. Non-blank/non-null fields are combined with AND into
 * a JMAP Email/query filter (RFC 8621 §4.4.1). [afterMillis]/[beforeMillis] are
 * epoch-millis bounds on the received date (rendered as UTCDate at the boundary).
 */
data class SearchQuery(
    val text: String = "",
    val from: String = "",
    val subject: String = "",
    val hasAttachment: Boolean = false,
    val afterMillis: Long? = null,
    val beforeMillis: Long? = null,
) {
    fun isEmpty(): Boolean =
        text.isBlank() && from.isBlank() && subject.isBlank() &&
            !hasAttachment && afterMillis == null && beforeMillis == null
}
