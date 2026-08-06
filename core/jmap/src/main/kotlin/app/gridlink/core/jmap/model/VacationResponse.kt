package app.gridlink.core.jmap.model

import kotlinx.serialization.Serializable

/**
 * The JMAP VacationResponse singleton (RFC 8621 §8): a server-side auto-reply
 * the mail server sends on the user's behalf while away. There is exactly one
 * per account, with the fixed id `"singleton"`.
 *
 * [fromDate]/[toDate] are UTCDate strings (ISO-8601, e.g. "2026-06-23T00:00:00Z");
 * null means "no bound" on that end. The server only sends the reply when
 * [isEnabled] is true and the current time is within the date range.
 */
@Serializable
data class VacationResponse(
    val id: String = "singleton",
    val isEnabled: Boolean = false,
    val fromDate: String? = null,
    val toDate: String? = null,
    val subject: String? = null,
    val textBody: String? = null,
    val htmlBody: String? = null,
)
