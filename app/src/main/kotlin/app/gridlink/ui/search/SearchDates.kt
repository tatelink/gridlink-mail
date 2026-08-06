package app.gridlink.ui.search

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Date bounds live in the DEVICE's time zone, not UTC.
 *
 * Material's date picker hands the picked day back as UTC midnight — a calendar day, not an
 * instant. Sending that straight to the server made "since 1 June" mean 1 June 00:00 UTC, which
 * east of Greenwich is already 1 June 02:00 at home (that morning's mail silently dropped) and
 * west of it is still 31 May (the previous evening's mail wrongly kept). The picked day is turned
 * into the local day it names instead.
 */
fun searchAfterBound(pickedUtcMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    pickedDay(pickedUtcMillis).atStartOfDay(zone).toInstant().toEpochMilli()

/** The "before" day is INCLUSIVE: the bound is the last millisecond of that local day. */
fun searchBeforeBound(pickedUtcMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    pickedDay(pickedUtcMillis).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

/** The local day a stored bound stands for — what the panel and the folded summary display. */
fun searchBoundDay(boundMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(boundMillis).atZone(zone).toLocalDate()

/** A stored bound back as the picker's own UTC-midnight value, so reopening it shows that day. */
fun searchPickerMillis(boundMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    searchBoundDay(boundMillis, zone).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun pickedDay(pickedUtcMillis: Long): LocalDate =
    Instant.ofEpochMilli(pickedUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()
