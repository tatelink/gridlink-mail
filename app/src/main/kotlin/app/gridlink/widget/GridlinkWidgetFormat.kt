package app.gridlink.widget

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The widget's text rules, kept as pure functions so they can be tested without an
 * `AppWidgetManager`, a `Context` or a device clock.
 *
 * Nothing here reaches for a string resource. Every function that has an "and if there is nothing
 * to print?" case returns null and lets the caller name it, because the caller is the one holding
 * a `Context` — and because a formatter that invents the words "(No subject)" is a formatter that
 * cannot be translated later without being rewritten.
 */
internal object GridlinkWidgetFormat {

    /**
     * How long ago a message arrived, in the smallest space that still says something true.
     *
     * A widget row gives this about five characters, so the scale is coarse on purpose and gets
     * coarser as it goes back: minutes for the last hour, a clock time for today, a weekday for
     * the last week, a date beyond that. The steps are chosen so the string answers the question
     * the user is actually asking at each distance — "did this just arrive?", then "when today?",
     * then "which day?".
     *
     * Clock time rather than "6h" for earlier today: at that distance a person reads their own
     * day in hours of the clock, not in hours of arithmetic.
     *
     * ⚠️ A message stamped in the future prints as [justNowLabel] rather than as a negative age.
     * Servers with a fast clock, and messages whose Date header is simply wrong, are both common
     * enough that "in -3m" would show up on real mail.
     *
     * [justNowLabel] is passed in rather than written here because it is the one piece of this
     * function that is a word: everything else is a number or comes out of [DateTimeFormatter]
     * already localised, and hard-coding "now" would put an English string in the middle of a row
     * that is otherwise translated.
     */
    fun relativeTime(
        nowMillis: Long,
        thenMillis: Long,
        zone: ZoneId,
        locale: Locale,
        justNowLabel: String,
    ): String {
        val minutes = (nowMillis - thenMillis) / 60_000L
        if (minutes < 1L) return justNowLabel
        if (minutes < 60L) return "${minutes}m"
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val then = Instant.ofEpochMilli(thenMillis).atZone(zone)
        val days = ChronoUnit.DAYS.between(then.toLocalDate(), now.toLocalDate())
        return when {
            days < 1L -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                .withLocale(locale).format(then)
            // Under a week, and only then: at seven days the weekday name has come back round and
            // "Mon" stops distinguishing this Monday from the last one.
            days < 7L -> DateTimeFormatter.ofPattern("EEE", locale).format(then)
            else -> DateTimeFormatter.ofPattern("d MMM", locale).format(then)
        }
    }

    /**
     * Who a row is from, or null when the message named nobody.
     *
     * Trimmed and collapsed because display names arrive with whatever whitespace the sending
     * client left in them, and a leading space in a one-line row shifts the whole column.
     */
    fun sender(raw: String): String? = raw.collapseOrNull()

    /** A row's subject, or null when the message carries none. */
    fun subject(raw: String): String? = raw.collapseOrNull()

    /**
     * The first line of the body, or null when there is nothing cached to show.
     *
     * Truncated well past what any row can display: the point is to stop a pathological preview
     * (a base64 blob, a wall of quoted text) from being measured and ellipsized in full on every
     * bind, not to decide the visible length — the layout's `ellipsize` does that, and it knows
     * the actual width.
     */
    fun preview(raw: String): String? = raw.collapseOrNull()?.take(PREVIEW_LIMIT)

    /**
     * The header badge: how many unread, or null when the badge should not be drawn at all.
     *
     * Null covers two different situations that look the same on screen and must: a mailbox that
     * has never synced (count unknown) and a mailbox with nothing unread. Neither deserves a
     * badge — one because it would be a guess, the other because a badge reading "0" is a mark
     * demanding attention for the absence of anything to attend to.
     *
     * Large counts cap at [BADGE_CAP], since the difference between 400 and 3000 unread is not a
     * difference the user is going to act on differently, and the untruncated number pushes the
     * header buttons off a narrow widget.
     */
    fun unreadBadge(count: Int?): String? = when {
        count == null || count <= 0 -> null
        count > BADGE_CAP -> "$BADGE_CAP+"
        else -> count.toString()
    }

    /**
     * The big number on the small widget, or null when nothing has synced.
     *
     * 🔴 Null is NOT zero, and the caller must print something other than a digit for it (see
     * `R.string.widget_count_unknown`). This is the one glance-only surface in the app: a
     * confident "0" on a mailbox that has never been read is a claim the user has no reason to
     * doubt and no way to check without opening the thing the widget exists to save them opening.
     */
    fun unreadCount(count: Int?): String? = when {
        count == null -> null
        count > COUNT_CAP -> "$COUNT_CAP+"
        else -> count.coerceAtLeast(0).toString()
    }

    /** Collapse runs of whitespace (including the newlines a preview arrives with) or give up. */
    private fun String.collapseOrNull(): String? =
        trim().replace(WHITESPACE, " ").takeIf { it.isNotEmpty() }

    private val WHITESPACE = Regex("\\s+")

    private const val PREVIEW_LIMIT = 160
    private const val BADGE_CAP = 999
    private const val COUNT_CAP = 9999
}
