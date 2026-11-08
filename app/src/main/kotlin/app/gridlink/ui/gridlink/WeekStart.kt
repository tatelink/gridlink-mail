package app.gridlink.ui.gridlink

import android.content.res.Resources
import app.gridlink.appLocale
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The day a week starts on, for every grid in the calendar: the month view, the week view and the
 * date picker. 🔴 All three read it from here, because two of them are on screen at once (the
 * picker opens over the month) and grids offset by a column from each other are a real bug.
 *
 * It follows the phone the way the system calendar does: Monday in Germany and France, Sunday in
 * the US. The week start is a REGIONAL convention, not a language one, so the in-app language alone
 * does not move it: a US phone with Gridlink set to German keeps a Sunday week, and a German phone
 * with Gridlink in English keeps a Monday week. Order of evidence:
 *
 *  1. An explicit "first day of week" choice, which Android 14+ regional preferences write into the
 *     locale as a `-u-fw-` extension (app locale first, then the device's).
 *  2. The region of the app locale, else the device's region, through the platform's week data.
 *  3. The app locale as it stands, when neither names a region.
 */
internal object WeekStart {
    fun of(app: Locale, device: Locale): DayOfWeek {
        explicit(app)?.let { return it }
        explicit(device)?.let { return it }
        val region = app.country.ifBlank { device.country }
        val source = if (region.isNotBlank()) Locale.Builder().setRegion(region).build() else app
        return WeekFields.of(source).firstDayOfWeek
    }

    /** The seven days in grid order, starting from [first]. */
    fun order(first: DayOfWeek): List<DayOfWeek> = (0 until DAYS).map { first.plus(it.toLong()) }

    /** How many days back from [date] to the [first] day that starts its week. */
    fun index(date: LocalDate, first: DayOfWeek): Int = (date.dayOfWeek.value - first.value + DAYS) % DAYS

    private fun explicit(locale: Locale): DayOfWeek? =
        locale.getUnicodeLocaleType("fw")?.let { FW_CODES[it.lowercase(Locale.ROOT)] }

    private const val DAYS = 7

    private val FW_CODES = mapOf(
        "mon" to DayOfWeek.MONDAY,
        "tue" to DayOfWeek.TUESDAY,
        "wed" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY,
        "sat" to DayOfWeek.SATURDAY,
        "sun" to DayOfWeek.SUNDAY,
    )
}

/** The phone's own locale, which keeps its region when the app runs in another language. */
private val deviceLocale: Locale get() = Resources.getSystem().configuration.locales[0]

/** The first day of the week for this phone right now. See [WeekStart]. */
internal val weekStart: DayOfWeek get() = WeekStart.of(appLocale, deviceLocale)

/** The weekday column heads in grid order, in the app's language. */
internal fun weekdayInitials(first: DayOfWeek = weekStart): List<String> =
    WeekStart.order(first).map { it.getDisplayName(TextStyle.NARROW, appLocale) }

/** The date of the grid cell that starts this date's week. */
internal fun LocalDate.weekStartDate(first: DayOfWeek = weekStart): LocalDate =
    minusDays(WeekStart.index(this, first).toLong())
