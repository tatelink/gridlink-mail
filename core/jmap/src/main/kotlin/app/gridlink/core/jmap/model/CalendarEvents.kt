package app.gridlink.core.jmap.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Duration

/** A JMAP Calendar (draft-ietf-jmap-calendars §2): a named collection CalendarEvents live in. */
@Serializable
data class JmapCalendar(
    val id: String,
    val name: String = "",
    val description: String? = null,
    /** CSS colour the server suggests for this calendar, e.g. `#2a9d8f`; null when unset. */
    val color: String? = null,
    /** The zone the calendar defaults to for floating times; null means the viewer's own. */
    val timeZone: String? = null,
    val sortOrder: Int = 0,
    val isDefault: Boolean = false,
    /**
     * Whether the user has subscribed to this calendar. Stalwart leaves secondary calendars
     * unsubscribed by default, so this is NOT a filter for "calendars to sync": the CalDAV
     * mirror lists them all, and hiding half of them on the JMAP path alone would make the
     * two protocols disagree about what the account contains.
     */
    val isSubscribed: Boolean = false,
    val myRights: JmapCalendarRights = JmapCalendarRights(),
)

/**
 * What this user may do in a calendar (draft-ietf-jmap-calendars §2).
 *
 * Defaults are all false on purpose. An absent rights block means the server did not say, and a
 * write attempted on that assumption fails at the server anyway; defaulting to true would instead
 * put an editable-looking calendar in front of the user and refuse at the last moment.
 */
@Serializable
data class JmapCalendarRights(
    val mayReadFreeBusy: Boolean = false,
    val mayReadItems: Boolean = false,
    val mayWriteAll: Boolean = false,
    val mayWriteOwn: Boolean = false,
    val mayUpdatePrivate: Boolean = false,
    val mayRSVP: Boolean = false,
    val mayShare: Boolean = false,
    val mayDelete: Boolean = false,
)

/**
 * The slice of a JSCalendar Event (RFC 8984) Gridlink reads.
 *
 * ## Why this is a different shape from the iCalendar parse
 * The CalDAV path receives a `.ics` file and parses text: `DTSTART;TZID=...`, an `RRULE` string, a
 * `DESCRIPTION` that can only ever be plain. JSCalendar hands the same facts over already typed,
 * and three things this app has wanted are simply fields here: [descriptionContentType] says
 * whether the description is HTML instead of it having to be smuggled through `X-ALT-DESC`,
 * [links] carries attachments, and [recurrenceRule] arrives as an object rather than a string this
 * app has to hand-validate before it dares write it back.
 *
 * ## 🔴 [start] is a LOCAL date-time, not an instant
 * RFC 8984 §4.1.1 defines it as a `LocalDateTime` with no offset, read in [timeZone] (or floating
 * when that is null). This is the same rule the Room cache follows and for the same reason: a
 * fortnightly 2:30pm meeting is 2:30pm on both sides of a daylight-saving change only when the
 * recurrence is worked out in the organiser's zone. Converting to an epoch here would throw that
 * away before anything downstream got a chance to use it.
 *
 * ## Deliberately NOT a full Event model
 * Alarms, participant scheduling state, `virtualLocations` and vendor extensions are not modelled.
 * Writes on this path are property patches, so unmodelled data survives an edit; regenerating a
 * whole Event from this class is what would destroy it.
 */
@Serializable
data class JmapCalendarEvent(
    val id: String,
    /** The iCalendar UID, carried verbatim. The one identifier JMAP and CalDAV both store. */
    val uid: String = "",
    /** Which calendars this event belongs to, as `{ calendarId: true }`. */
    val calendarIds: Map<String, Boolean> = emptyMap(),
    val title: String = "",
    val description: String? = null,
    /** `text/plain` or `text/html`; absent means plain (RFC 8984 §4.2.2). */
    val descriptionContentType: String? = null,
    /** Local date-time, e.g. `2026-06-10T14:30:00`. See the class note: this is not an instant. */
    val start: String? = null,
    /** ISO-8601 duration, e.g. `PT1H15M` or `P1D`. Absent means a zero-length event. */
    val duration: String? = null,
    /** IANA zone id [start] is written in, or null for a floating time. */
    val timeZone: String? = null,
    /** True for an all-day event: [start]'s time part is meaningless (RFC 8984 §4.1.3). */
    val showWithoutTime: Boolean = false,
    /** `confirmed`, `cancelled` or `tentative`; absent on servers that do not track it. */
    val status: String? = null,
    val privacy: String? = null,
    val freeBusyStatus: String? = null,
    val sequence: Int = 0,
    /** UTC timestamp of the last change, e.g. `2026-06-20T01:29:17Z`. */
    val updated: String? = null,
    val recurrenceRule: JmapRecurrenceRule? = null,
    /**
     * Instance-level changes, keyed by the occurrence's own local start.
     *
     * A value of `{"excluded": true}` is a cancelled instance (the `EXDATE` of the iCalendar
     * world); anything else is a detached override carrying the properties that differ. Held as
     * raw JSON rather than a typed map because an override may name ANY Event property, and a
     * typed subset would silently drop the ones this class does not model.
     */
    val recurrenceOverrides: Map<String, JsonObject> = emptyMap(),
    val locations: Map<String, JmapCalendarLocation> = emptyMap(),
    val participants: Map<String, JmapCalendarParticipant> = emptyMap(),
    /** Attachments and related URLs (RFC 8984 §4.2.7). */
    val links: Map<String, JmapCalendarLink> = emptyMap(),
    /** How to reply to an invitation, e.g. `{"imip": "mailto:organiser@example.com"}`. */
    val replyTo: Map<String, String> = emptyMap(),
    /** Free-form tags as `{ tag: true }`; JSCalendar's answer to iCalendar's `CATEGORIES`. */
    val keywords: Map<String, Boolean> = emptyMap(),
    /** Reminders, keyed by an id of the client's choosing (RFC 8984 §4.5.2). */
    val alerts: Map<String, JmapAlert> = emptyMap(),
    val isDraft: Boolean = false,
) {
    /** The first calendar id this event belongs to, or null when the server named none. */
    fun primaryCalendarId(): String? = calendarIds.entries.firstOrNull { it.value }?.key

    /** True when [description] is HTML rather than plain text. */
    fun descriptionIsHtml(): Boolean = descriptionContentType?.startsWith("text/html") == true

    /** The organiser's address, taken from [replyTo]'s iMIP entry; null when there is none. */
    fun organizerEmail(): String? =
        replyTo["imip"]?.removePrefix("mailto:")?.trim()?.takeIf { it.isNotEmpty() }

    /** The first named location, which is what a one-line summary shows; null when there is none. */
    fun locationName(): String? =
        locations.values.firstOrNull { !it.name.isNullOrBlank() }?.name?.trim()

    /** The first keyword, which is the single category the calendar UI has room for. */
    fun firstKeyword(): String? =
        keywords.entries.firstOrNull { it.value }?.key?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Reminder offsets in whole minutes BEFORE the start, ascending.
     *
     * Only offset triggers relative to the start are converted, because that is the only kind the
     * app can show or write. An absolute trigger, or one hung off the end, is left in the payload
     * rather than rounded into something it is not.
     */
    fun reminderMinutes(): List<Int> =
        alerts.values.mapNotNull { it.minutesBeforeStart() }.distinct().sorted()
}

/** A reminder on an event (RFC 8984 §4.5.2). */
@Serializable
data class JmapAlert(
    val trigger: JmapAlertTrigger? = null,
    /** `display` or `email`; absent means the server did not say. */
    val action: String? = null,
) {
    /**
     * How many whole minutes before the START this alert fires, or null when it is not that kind.
     *
     * Null covers an absolute trigger, one measured from the end, and one that fires AFTER the
     * start: the calendar UI can only express "N minutes before", and rounding the others into it
     * would move a reminder rather than decline to show it.
     */
    fun minutesBeforeStart(): Int? {
        val trigger = trigger ?: return null
        // An absent relativeTo means start (RFC 8984 §4.5.2), so only an explicit "end" disqualifies.
        if (trigger.relativeTo?.equals("start", ignoreCase = true) == false) return null
        val minutes = trigger.offset?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { Duration.parse(it) }.getOrNull() }
            ?.takeIf { it.isNegative }
            ?.let { -it.toMinutes() }
        return minutes?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
    }
}

/** When an alert fires (RFC 8984 §4.5.2). */
@Serializable
data class JmapAlertTrigger(
    /** `OffsetTrigger` or `AbsoluteTrigger`, in the JSCalendar `@type` slot. */
    @SerialName("@type") val type: String? = null,
    /** A signed ISO-8601 duration, e.g. `-PT30M` for half an hour before. */
    val offset: String? = null,
    /** `start` or `end`; absent means start (RFC 8984 §4.5.2). */
    val relativeTo: String? = null,
    /** UTC timestamp, for an absolute trigger. `when` is a Kotlin keyword, hence the rename. */
    @SerialName("when") val firesAt: String? = null,
)

/**
 * A JSCalendar RecurrenceRule (RFC 8984 §4.3.3).
 *
 * The fields are lower-cased words here (`weekly`, `mo`) where iCalendar spells them in capitals
 * (`WEEKLY`, `MO`). Anything rendering this to an `RRULE` line has to upper-case them; anything
 * comparing them must not assume either casing.
 */
@Serializable
data class JmapRecurrenceRule(
    /** `yearly`, `monthly`, `weekly`, `daily`, `hourly`, `minutely` or `secondly`. */
    val frequency: String = "",
    val interval: Int = 1,
    /** Local date-time the rule stops at, inclusive. Mutually exclusive with [count]. */
    val until: String? = null,
    val count: Int? = null,
    /** `su`..`sa`; the week start the rule is calculated against. */
    val firstDayOfWeek: String? = null,
    val byDay: List<JmapRecurrenceDay> = emptyList(),
    val byMonthDay: List<Int> = emptyList(),
    /** Months as strings, because RFC 8984 allows a leap suffix (`"5"`, `"5L"`). */
    val byMonth: List<String> = emptyList(),
    val byHour: List<Int> = emptyList(),
    val byMinute: List<Int> = emptyList(),
    val bySetPosition: List<Int> = emptyList(),
)

/** One `byDay` entry: a weekday, optionally pinned to an occurrence within the period. */
@Serializable
data class JmapRecurrenceDay(
    /** `su`, `mo`, `tu`, `we`, `th`, `fr` or `sa`. */
    val day: String = "",
    /** e.g. `2` for the second Tuesday, `-1` for the last; null for every one of them. */
    val nthOfPeriod: Int? = null,
)

/** A place an event happens (RFC 8984 §4.2.5). */
@Serializable
data class JmapCalendarLocation(
    val name: String? = null,
    val description: String? = null,
    /** A `geo:` URI when the server has coordinates. */
    val coordinates: String? = null,
    val timeZone: String? = null,
)

/**
 * A link on an event: an attachment, or a URL related to it (RFC 8984 §4.2.7).
 *
 * 🔴 [href] can be any URI the server chose, including one that needs the account's credentials to
 * fetch. Nothing here should be handed to a browser or an image loader without going through the
 * same gate mail attachments do.
 */
@Serializable
data class JmapCalendarLink(
    val href: String = "",
    /** The MIME type of what [href] points at, when the server said. */
    val contentType: String? = null,
    /** Bytes, when the server said. Null means unknown, which is NOT the same as empty. */
    val size: Long? = null,
    /** `enclosure` for a real attachment, `icon`, `describedby`, or absent. */
    val rel: String? = null,
    val title: String? = null,
    val display: String? = null,
    /** The JMAP blob backing this link, when the server exposes one to download. */
    val blobId: String? = null,
)

/** A person invited to an event (RFC 8984 §4.4.6). */
@Serializable
data class JmapCalendarParticipant(
    /** A `mailto:` URI in practice; the raw value is kept so a non-mail participant is not mangled. */
    val sendTo: Map<String, String> = emptyMap(),
    val name: String? = null,
    val email: String? = null,
    /** `needs-action`, `accepted`, `declined`, `tentative` or `delegated`. */
    val participationStatus: String? = null,
    /** Whether the organiser asked this participant to RSVP. */
    val expectReply: Boolean = false,
    /** `{ "owner": true }`, `attendee`, `optional`, `informational` or `chair`. */
    val roles: Map<String, Boolean> = emptyMap(),
) {
    /** The participant's address: the explicit field, else the iMIP `sendTo` entry. */
    fun address(): String? =
        email?.trim()?.takeIf { it.isNotEmpty() }
            ?: sendTo["imip"]?.removePrefix("mailto:")?.trim()?.takeIf { it.isNotEmpty() }

    /** True when this participant is the organiser. */
    fun isOrganizer(): Boolean = roles["owner"] == true
}

/**
 * One page of a `CalendarEvent/query`: the ids, and the state to resume from.
 *
 * [total] is how many events matched the whole window, not how many are in [ids]; it is null when
 * the server declined to count, and "it did not say" must not be read as zero.
 */
data class CalendarEventIdPage(
    val ids: List<String>,
    /**
     * ⚠️ The query's own state, which is NOT what seeds `CalendarEvent/changes`: that takes the
     * state from a `CalendarEvent/get` (`calendarEventState`). Confusing the two reports changes
     * against the wrong baseline, silently.
     */
    val queryState: String?,
    val total: Int? = null,
)

/** Result of `CalendarEvent/changes` (RFC 8620 §5.2), the incremental-sync counterpart. */
data class CalendarChangesResult(
    val newState: String?,
    val created: List<String>,
    val updated: List<String>,
    val destroyed: List<String>,
    val hasMoreChanges: Boolean,
    /**
     * False when the server answered `cannotCalculateChanges` and the caller must fall back to a
     * full re-query. A failed calculation is not an empty change set, and a caller that treated it
     * as one would stop syncing that calendar without ever reporting an error.
     */
    val calculated: Boolean,
)
