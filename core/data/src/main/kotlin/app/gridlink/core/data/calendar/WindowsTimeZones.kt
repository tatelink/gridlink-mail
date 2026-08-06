package app.gridlink.core.data.calendar

import java.time.ZoneId

/**
 * Maps a Windows time-zone name to an IANA zone.
 *
 * ## 🔴 Why this file exists
 * The account's calendar was migrated out of Exchange, and Exchange writes its own zone names into
 * TZID. What the live server actually sends is:
 *
 * ```
 * DTSTART;TZID="Eastern Standard Time":20260610T143000
 * ```
 *
 * `ZoneId.of("Eastern Standard Time")` throws. Eight of the account's 27 events are written this
 * way, and the naive recovery (fall back to the device's own zone) is worse than it looks: it is
 * *correct for Brandon*, who is on US Eastern, and silently wrong by hours for anyone else running
 * this app. That is the exact shape of bug that ships, because the person who would notice it is the
 * one person who cannot see it.
 *
 * The mapping is the standard CLDR `windowsZones` one, narrowed to the territory-default zone for
 * each Windows name. It is not exhaustive: it covers the zones Exchange actually emits for the
 * Americas, Europe, Africa, Asia and Oceania, which is every zone a mailbox in this fork has any
 * realistic chance of carrying. An unknown name falls through to the caller's next strategy rather
 * than being guessed at.
 *
 * ⚠️ Note the trap in the name itself: "Eastern Standard Time" is Windows' name for the *zone*, not
 * for standard time within it. It means America/New_York including its summer time, not a fixed
 * UTC-5. Anything that reads it as a fixed offset is an hour out for seven months of the year.
 */
object WindowsTimeZones {

    /** The IANA zone for a Windows zone name, or null when the name is not one we know. */
    fun zoneFor(name: String?): ZoneId? {
        val key = name?.trim()?.trim('"')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val iana = BY_NAME[key] ?: return null
        return runCatching { ZoneId.of(iana) }.getOrNull()
    }

    private val BY_NAME: Map<String, String> = mapOf(
        // Americas
        "dateline standard time" to "Etc/GMT+12",
        "utc-11" to "Etc/GMT+11",
        "aleutian standard time" to "America/Adak",
        "hawaiian standard time" to "Pacific/Honolulu",
        "marquesas standard time" to "Pacific/Marquesas",
        "alaskan standard time" to "America/Anchorage",
        "utc-09" to "Etc/GMT+9",
        "pacific standard time (mexico)" to "America/Tijuana",
        "utc-08" to "Etc/GMT+8",
        "pacific standard time" to "America/Los_Angeles",
        "us mountain standard time" to "America/Phoenix",
        "mountain standard time (mexico)" to "America/Chihuahua",
        "mountain standard time" to "America/Denver",
        "yukon standard time" to "America/Whitehorse",
        "central america standard time" to "America/Guatemala",
        "central standard time" to "America/Chicago",
        "easter island standard time" to "Pacific/Easter",
        "central standard time (mexico)" to "America/Mexico_City",
        "canada central standard time" to "America/Regina",
        "sa pacific standard time" to "America/Bogota",
        "eastern standard time (mexico)" to "America/Cancun",
        "eastern standard time" to "America/New_York",
        "haiti standard time" to "America/Port-au-Prince",
        "cuba standard time" to "America/Havana",
        "us eastern standard time" to "America/Indianapolis",
        "turks and caicos standard time" to "America/Grand_Turk",
        "paraguay standard time" to "America/Asuncion",
        "atlantic standard time" to "America/Halifax",
        "venezuela standard time" to "America/Caracas",
        "central brazilian standard time" to "America/Cuiaba",
        "sa western standard time" to "America/La_Paz",
        "pacific sa standard time" to "America/Santiago",
        "newfoundland standard time" to "America/St_Johns",
        "tocantins standard time" to "America/Araguaina",
        "e. south america standard time" to "America/Sao_Paulo",
        "sa eastern standard time" to "America/Cayenne",
        "argentina standard time" to "America/Buenos_Aires",
        "greenland standard time" to "America/Godthab",
        "montevideo standard time" to "America/Montevideo",
        "magallanes standard time" to "America/Punta_Arenas",
        "saint pierre standard time" to "America/Miquelon",
        "bahia standard time" to "America/Bahia",

        // Atlantic and Europe
        "utc-02" to "Etc/GMT+2",
        "azores standard time" to "Atlantic/Azores",
        "cape verde standard time" to "Atlantic/Cape_Verde",
        "utc" to "Etc/UTC",
        "gmt standard time" to "Europe/London",
        "greenwich standard time" to "Atlantic/Reykjavik",
        "sao tome standard time" to "Africa/Sao_Tome",
        "morocco standard time" to "Africa/Casablanca",
        "w. europe standard time" to "Europe/Berlin",
        "central europe standard time" to "Europe/Budapest",
        "romance standard time" to "Europe/Paris",
        "central european standard time" to "Europe/Warsaw",
        "w. central africa standard time" to "Africa/Lagos",
        "gtb standard time" to "Europe/Bucharest",
        "middle east standard time" to "Asia/Beirut",
        "egypt standard time" to "Africa/Cairo",
        "e. europe standard time" to "Europe/Chisinau",
        "syria standard time" to "Asia/Damascus",
        "west bank standard time" to "Asia/Hebron",
        "south africa standard time" to "Africa/Johannesburg",
        "fle standard time" to "Europe/Kiev",
        "israel standard time" to "Asia/Jerusalem",
        "south sudan standard time" to "Africa/Juba",
        "kaliningrad standard time" to "Europe/Kaliningrad",
        "sudan standard time" to "Africa/Khartoum",
        "libya standard time" to "Africa/Tripoli",
        "namibia standard time" to "Africa/Windhoek",
        "jordan standard time" to "Asia/Amman",
        "turkey standard time" to "Europe/Istanbul",
        "arabic standard time" to "Asia/Baghdad",
        "belarus standard time" to "Europe/Minsk",
        "russian standard time" to "Europe/Moscow",
        "e. africa standard time" to "Africa/Nairobi",
        "volgograd standard time" to "Europe/Volgograd",

        // Asia and Oceania
        "iran standard time" to "Asia/Tehran",
        "arab standard time" to "Asia/Riyadh",
        "arabian standard time" to "Asia/Dubai",
        "astrakhan standard time" to "Europe/Astrakhan",
        "azerbaijan standard time" to "Asia/Baku",
        "russia time zone 3" to "Europe/Samara",
        "mauritius standard time" to "Indian/Mauritius",
        "saratov standard time" to "Europe/Saratov",
        "georgian standard time" to "Asia/Tbilisi",
        "caucasus standard time" to "Asia/Yerevan",
        "afghanistan standard time" to "Asia/Kabul",
        "west asia standard time" to "Asia/Tashkent",
        "qyzylorda standard time" to "Asia/Qyzylorda",
        "ekaterinburg standard time" to "Asia/Yekaterinburg",
        "pakistan standard time" to "Asia/Karachi",
        "india standard time" to "Asia/Calcutta",
        "sri lanka standard time" to "Asia/Colombo",
        "nepal standard time" to "Asia/Katmandu",
        "central asia standard time" to "Asia/Almaty",
        "bangladesh standard time" to "Asia/Dhaka",
        "omsk standard time" to "Asia/Omsk",
        "myanmar standard time" to "Asia/Rangoon",
        "se asia standard time" to "Asia/Bangkok",
        "altai standard time" to "Asia/Barnaul",
        "w. mongolia standard time" to "Asia/Hovd",
        "north asia standard time" to "Asia/Krasnoyarsk",
        "n. central asia standard time" to "Asia/Novosibirsk",
        "tomsk standard time" to "Asia/Tomsk",
        "china standard time" to "Asia/Shanghai",
        "north asia east standard time" to "Asia/Irkutsk",
        "singapore standard time" to "Asia/Singapore",
        "w. australia standard time" to "Australia/Perth",
        "taipei standard time" to "Asia/Taipei",
        "ulaanbaatar standard time" to "Asia/Ulaanbaatar",
        "aus central w. standard time" to "Australia/Eucla",
        "transbaikal standard time" to "Asia/Chita",
        "tokyo standard time" to "Asia/Tokyo",
        "north korea standard time" to "Asia/Pyongyang",
        "korea standard time" to "Asia/Seoul",
        "yakutsk standard time" to "Asia/Yakutsk",
        "cen. australia standard time" to "Australia/Adelaide",
        "aus central standard time" to "Australia/Darwin",
        "e. australia standard time" to "Australia/Brisbane",
        "aus eastern standard time" to "Australia/Sydney",
        "west pacific standard time" to "Pacific/Port_Moresby",
        "tasmania standard time" to "Australia/Hobart",
        "vladivostok standard time" to "Asia/Vladivostok",
        "lord howe standard time" to "Australia/Lord_Howe",
        "bougainville standard time" to "Pacific/Bougainville",
        "russia time zone 10" to "Asia/Srednekolymsk",
        "magadan standard time" to "Asia/Magadan",
        "norfolk standard time" to "Pacific/Norfolk",
        "sakhalin standard time" to "Asia/Sakhalin",
        "central pacific standard time" to "Pacific/Guadalcanal",
        "russia time zone 11" to "Asia/Kamchatka",
        "new zealand standard time" to "Pacific/Auckland",
        "utc+12" to "Etc/GMT-12",
        "fiji standard time" to "Pacific/Fiji",
        "chatham islands standard time" to "Pacific/Chatham",
        "utc+13" to "Etc/GMT-13",
        "tonga standard time" to "Pacific/Tongatapu",
        "samoa standard time" to "Pacific/Apia",
        "line islands standard time" to "Pacific/Kiritimati",
    )
}
