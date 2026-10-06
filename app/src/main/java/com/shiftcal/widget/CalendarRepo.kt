package com.shiftcal.widget

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

data class CalendarInfo(
    val id: Long, val name: String, val account: String, val color: Int,
    val accountType: String = "",
    /** False if the phone isn't downloading this calendar's events (common for calendars added from a URL). */
    val syncing: Boolean = true,
    /** True if the app may add events to it (owner/contributor access). */
    val writable: Boolean = false,
    val primary: Boolean = false,
) {
    /** True for Google accounts – only these calendars sync to Google Calendar. */
    val isGoogle get() = accountType == "com.google"
}

data class DayEvent(
    val title: String,
    val color: Int,
    val allDay: Boolean,
    val begin: Long,
    val end: Long,
    /** First day shown on, and the day after the last (exclusive) – used to join multi-day bars. */
    val startDay: LocalDate,
    val endDayExcl: LocalDate,
    val eventId: Long = 0,
    val calendarId: Long = 0,
    /** In a calendar we can change, and not a repeating event. */
    val editable: Boolean = false,
    val repeating: Boolean = false,
) {
    fun covers(d: LocalDate) = !d.isBefore(startDay) && d.isBefore(endDayExcl)
}

/** A colour an event can be given. [key] is the calendar's own colour id (Google's 11 colours), if it has them. */
data class EventColor(val key: String?, val color: Int)

data class ShiftInfo(val title: String, val color: Int, val count: Int)

/**
 * Reads events from the phone's calendar database. Google Calendar events are here
 * as long as the Google account is synced on the device — no Google API/OAuth needed.
 */
object CalendarRepo {

    fun hasPermission(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(c: Context): List<CalendarInfo> {
        if (!hasPermission(c)) return emptyList()
        val projection = arrayOf(
            "_id", "calendar_displayName", "account_name", "calendar_color", "calendar_access_level", "isPrimary",
            "account_type", "sync_events",
        )
        val out = ArrayList<CalendarInfo>()
        c.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, null, null,
            "account_name, calendar_displayName"
        )?.use { cur ->
            while (cur.moveToNext()) {
                out += CalendarInfo(
                    id = cur.getLong(0),
                    name = cur.getString(1) ?: "(unnamed)",
                    account = cur.getString(2) ?: "",
                    color = cur.getInt(3) or 0xFF000000.toInt(),
                    writable = cur.getInt(4) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                    primary = cur.getInt(5) == 1,
                    accountType = cur.getString(6) ?: "",
                    syncing = cur.getInt(7) == 1,
                )
            }
        }
        return out
    }

    /** Turn on downloading of a calendar's events to this phone (and make it visible). */
    fun enableSync(c: Context, cal: CalendarInfo): Boolean {
        if (!canWrite(c)) return false
        val values = ContentValues().apply {
            put("sync_events", 1)
            put("visible", 1)
        }
        val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, cal.id)
        val ok = runCatching { c.contentResolver.update(uri, values, null, null) > 0 }.getOrDefault(false)
        if (ok) requestSync(c)
        return ok
    }

    /**
     * Ask Android to sync every calendar account right now (normally it syncs on its own schedule).
     * Returns how many accounts were asked. Note: calendars added "from URL" are re-read by Google's
     * servers only every few hours – this fetches whatever Google already has.
     */
    fun requestSync(c: Context): Int {
        val accounts = calendars(c)
            .filter { it.accountType.isNotEmpty() && it.accountType != CalendarContract.ACCOUNT_TYPE_LOCAL }
            .map { Account(it.account, it.accountType) }
            .distinct()
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        accounts.forEach { runCatching { ContentResolver.requestSync(it, CalendarContract.AUTHORITY, extras) } }
        return accounts.size
    }

    fun canWrite(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /**
     * Calendar new notes are saved to: the one picked in settings, else the main Google calendar.
     * Phone-only calendars (e.g. Samsung's "My calendar") are a last resort: they never reach Google.
     */
    fun noteCalendar(c: Context): CalendarInfo? {
        val writable = calendars(c).filter { it.writable }
        return writable.firstOrNull { it.id == Prefs.noteCalendarId(c) }
            ?: writable.firstOrNull { it.isGoogle && it.primary && it.syncing }
            ?: writable.firstOrNull { it.isGoogle && it.syncing }
            ?: writable.firstOrNull { it.isGoogle }
            ?: writable.firstOrNull { it.primary }
            ?: writable.firstOrNull()
    }

    /** Push local changes in [cal] up to its account now, instead of waiting for Android's next sync. */
    private fun uploadChanges(c: Context, cal: CalendarInfo?) {
        if (cal == null || cal.accountType.isEmpty() || cal.accountType == CalendarContract.ACCOUNT_TYPE_LOCAL) return
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, true)
        }
        runCatching { ContentResolver.requestSync(Account(cal.account, cal.accountType), CalendarContract.AUTHORITY, extras) }
    }

    private fun calendarOf(c: Context, id: Long) = calendars(c).firstOrNull { it.id == id }

    /**
     * Adds a note to the calendar, from [date] to [last] (inclusive; same day = a one-day note).
     * No [time] → an all-day event across those days. With a time → a 1-hour event, or for a range,
     * from that time on the first day to that time on the last day.
     * Returns the calendar it was saved to, or null if it couldn't be saved.
     */
    fun addNote(
        c: Context, date: LocalDate, title: String, time: LocalTime?, color: EventColor?, last: LocalDate = date,
    ): CalendarInfo? {
        if (!canWrite(c)) return null
        val cal = noteCalendar(c) ?: return null
        val values = ContentValues().apply {
            put("calendar_id", cal.id)
            put("title", title)
            val end = if (last.isAfter(date)) last else date
            if (time == null) putAllDay(date, end)
            else putTimed(date, time, if (end == date) ONE_HOUR
                else Duration.between(date.atTime(time).atZone(ZoneId.systemDefault()),
                    end.atTime(time).atZone(ZoneId.systemDefault())).toMillis())
            if (color != null) putColor(color)
        }
        return runCatching { c.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) }
            .getOrNull()?.let { uploadChanges(c, cal); cal }
    }

    /**
     * Changes an event's text, time and colour. Keeps a multi-day all-day event's span if it stays
     * all-day, and a timed event's length if it stays timed. [color] null = the calendar's default colour.
     */
    fun updateEvent(c: Context, ev: DayEvent, date: LocalDate, title: String, time: LocalTime?, color: EventColor?): Boolean {
        if (!canWrite(c) || !ev.editable) return false
        val values = ContentValues().apply {
            put("title", title)
            if (time == null) {
                if (!ev.allDay) putAllDay(date)
            } else {
                putTimed(date, time, if (ev.allDay) ONE_HOUR else ev.end - ev.begin)
            }
            if (color != null) putColor(color) else {
                putNull("eventColor_index")
                putNull("eventColor")
            }
        }
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ev.eventId)
        val ok = runCatching { c.contentResolver.update(uri, values, null, null) > 0 }.getOrDefault(false)
        if (ok) uploadChanges(c, calendarOf(c, ev.calendarId))
        return ok
    }

    fun deleteEvent(c: Context, ev: DayEvent): Boolean {
        if (!canWrite(c) || !ev.editable) return false
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ev.eventId)
        val ok = runCatching { c.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
        if (ok) uploadChanges(c, calendarOf(c, ev.calendarId))
        return ok
    }

    /**
     * Colours events in [cal] can have. Google calendars only sync their own 11 colours, so use
     * those when the calendar provides them; otherwise fall back to the app's palette.
     */
    fun eventColors(c: Context, cal: CalendarInfo?): List<EventColor> {
        if (cal != null && hasPermission(c)) {
            val out = ArrayList<EventColor>()
            runCatching {
                c.contentResolver.query(
                    CalendarContract.Colors.CONTENT_URI, arrayOf("color_index", "color"),
                    "account_name=? AND account_type=? AND color_type=1",
                    arrayOf(cal.account, cal.accountType), null
                )?.use { cur ->
                    while (cur.moveToNext()) out += EventColor(cur.getString(0), cur.getInt(1) or 0xFF000000.toInt())
                }
            }
            if (out.isNotEmpty()) return out
        }
        return ColorPickerView.PALETTE.take(11).map { EventColor(null, it) }
    }

    private const val ONE_HOUR = 3_600_000L

    private fun ContentValues.putAllDay(date: LocalDate, last: LocalDate = date) {
        // All-day events are stored as UTC midnight to midnight (end = the day after the last day).
        put("dtstart", date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        put("dtend", last.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        put("allDay", 1)
        put("eventTimezone", "UTC")
    }

    private fun ContentValues.putTimed(date: LocalDate, time: LocalTime, lengthMillis: Long) {
        val zone = ZoneId.systemDefault()
        val start = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
        put("dtstart", start)
        put("dtend", start + lengthMillis)
        put("allDay", 0)
        put("eventTimezone", zone.id)
    }

    private fun ContentValues.putColor(color: EventColor) {
        if (color.key != null) put("eventColor_index", color.key) else put("eventColor", color.color)
    }

    /** All visible events whose day falls in [from, toExclusive), grouped by local date. */
    fun eventsByDay(c: Context, from: LocalDate, toExclusive: LocalDate): Map<LocalDate, List<DayEvent>> {
        val result = HashMap<LocalDate, MutableList<DayEvent>>()
        if (!hasPermission(c)) return result
        val zone = ZoneId.systemDefault()
        // Widen by a day either side: all-day events are stored in UTC.
        val begin = from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = toExclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val hiddenCals = Prefs.hiddenCalendars(c)

        fun add(d: LocalDate, ev: DayEvent) {
            if (!d.isBefore(from) && d.isBefore(toExclusive)) result.getOrPut(d) { ArrayList() } += ev
        }

        queryInstances(c, begin, end) { r ->
            if (r.calId in hiddenCals || Prefs.isShiftHidden(c, r.title)) return@queryInstances
            fun event(first: LocalDate, last: LocalDate) = DayEvent(
                r.title, r.color, r.allDay, r.begin, r.end, first, last,
                eventId = r.eventId, calendarId = r.calId,
                editable = r.writable && !r.repeating, repeating = r.repeating,
            )
            if (r.allDay) {
                val first = Instant.ofEpochMilli(r.begin).atZone(ZoneOffset.UTC).toLocalDate()
                val last = maxOf(Instant.ofEpochMilli(r.end).atZone(ZoneOffset.UTC).toLocalDate(), first.plusDays(1))
                val ev = event(first, last)
                var d = first
                while (d.isBefore(last)) {
                    add(d, ev); d = d.plusDays(1)
                }
            } else {
                // Timed events are shown on the day they start (night shifts don't spill over).
                val day = Instant.ofEpochMilli(r.begin).atZone(zone).toLocalDate()
                add(day, event(day, day.plusDays(1)))
            }
        }
        result.values.forEach { list -> list.sortWith(compareBy({ !it.allDay }, { it.begin }, { -it.end })) }
        return result
    }

    /** Distinct event titles from 2 months back to 6 months ahead, most frequent first. */
    fun distinctShifts(c: Context): List<ShiftInfo> {
        if (!hasPermission(c)) return emptyList()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val begin = today.minusMonths(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusMonths(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val hiddenCals = Prefs.hiddenCalendars(c)
        val map = LinkedHashMap<String, ShiftInfo>()
        queryInstances(c, begin, end) { r ->
            if (r.calId in hiddenCals) return@queryInstances
            val k = r.title.trim().lowercase()
            val prev = map[k]
            map[k] = if (prev == null) ShiftInfo(r.title, r.color, 1) else prev.copy(count = prev.count + 1)
        }
        return map.values.sortedByDescending { it.count }
    }

    private class InstanceRow(
        val title: String, val begin: Long, val end: Long, val allDay: Boolean, val color: Int,
        val calId: Long, val eventId: Long, val writable: Boolean, val repeating: Boolean,
    )

    private inline fun queryInstances(c: Context, begin: Long, end: Long, each: (InstanceRow) -> Unit) {
        val projection = arrayOf(
            "title", "begin", "end", "allDay", "displayColor", "calendar_id",
            "event_id", "calendar_access_level", "rrule",
        )
        CalendarContract.Instances.query(c.contentResolver, projection, begin, end)?.use { cur ->
            while (cur.moveToNext()) {
                each(InstanceRow(
                    title = cur.getString(0)?.trim().orEmpty().ifEmpty { "(No title)" },
                    begin = cur.getLong(1), end = cur.getLong(2), allDay = cur.getInt(3) == 1,
                    color = cur.getInt(4) or 0xFF000000.toInt(), calId = cur.getLong(5),
                    eventId = cur.getLong(6),
                    writable = cur.getInt(7) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                    repeating = !cur.getString(8).isNullOrEmpty(),
                ))
            }
        }
    }
}
