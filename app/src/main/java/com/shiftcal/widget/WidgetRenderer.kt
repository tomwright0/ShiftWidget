package com.shiftcal.widget

import android.app.PendingIntent
import android.os.Build
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

object WidgetRenderer {

    private const val PI_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    // ------------------------------------------------------------------ month

    fun buildMonth(c: Context, widgetId: Int): RemoteViews {
        val rv = RemoteViews(c.packageName, R.layout.widget_month)
        val tc = textColors(c)
        applyFrame(c, rv, tc)

        val today = LocalDate.now()
        val month = YearMonth.from(today).plusMonths(Prefs.monthOffset(c, widgetId).toLong())
        val firstDow = if (Prefs.weekStartsMonday(c)) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
        val gridStart = month.atDay(1).with(TemporalAdjusters.previousOrSame(firstDow))
        val events = CalendarRepo.eventsByDay(c, gridStart, gridStart.plusDays(42))
        val showTimes = Prefs.monthShowTimes(c)
        val join = Prefs.joinMultiDay(c)
        // With an Upcoming widget placed, tapping a day makes it jump there.
        val hasUpcoming = BaseWidgetProvider.hasUpcomingWidget(c)
        val selected = if (hasUpcoming) Prefs.selectedDay(c) else null

        val dayText = tc.day
        val todayColor = Prefs.color(c, StyleColor.TODAY)
        val weekdayText = tc.weekday

        rv.setTextViewText(R.id.title, monthTitle(c, month))
        navIntents(c, rv, widgetId, MonthWidgetProvider::class.java)

        rv.removeAllViews(R.id.weekdays)
        for (i in 0 until 7) {
            val label = RemoteViews(c.packageName, R.layout.widget_weekday_label)
            label.setTextViewText(R.id.weekday, firstDow.plus(i.toLong()).getDisplayName(TextStyle.SHORT, Locale.getDefault()))
            label.setTextColor(R.id.weekday, weekdayText)
            rv.addView(R.id.weekdays, label)
        }

        rv.removeAllViews(R.id.grid)
        for (w in 0 until 6) {
            val row = RemoteViews(c.packageName, R.layout.widget_week_row)
            for (d in 0 until 7) {
                val date = gridStart.plusDays((w * 7 + d).toLong())
                val inMonth = YearMonth.from(date) == month
                val cell = RemoteViews(c.packageName, R.layout.widget_day_cell)
                cell.setTextViewText(R.id.day_num, date.dayOfMonth.toString())
                cell.setTextColor(R.id.day_num, if (inMonth) dayText else withAlpha(dayText, 0x55))
                if (date == today || date == selected) {
                    cell.setViewVisibility(R.id.today_ring, View.VISIBLE)
                    // Today keeps its highlight colour; the picked day gets a softer outline.
                    cell.setInt(R.id.today_ring, "setColorFilter", if (date == today) todayColor else withAlpha(dayText, 0xAA))
                }
                val dayEvents = events[date].orEmpty()
                val slot = Slot(date, d, inMonth, showTimes, join, tc)
                bindShift(c, cell, R.id.shift1, R.id.shift1_bg, R.id.shift1_text, dayEvents.getOrNull(0), slot, View.INVISIBLE)
                bindShift(c, cell, R.id.shift2, R.id.shift2_bg, R.id.shift2_text, dayEvents.getOrNull(1), slot, View.GONE)
                val code = widgetId * 64 + w * 7 + d
                cell.setOnClickPendingIntent(
                    R.id.cell,
                    if (hasUpcoming) selectDay(c, date, code) else openCalendarAt(c, date, code)
                )
                row.addView(R.id.week_row, cell)
            }
            rv.addView(R.id.grid, row)
        }
        return rv
    }

    /** Where a day cell sits, for drawing its shift blocks. */
    private class Slot(
        val date: LocalDate, val column: Int, val inMonth: Boolean,
        val showTimes: Boolean, val join: Boolean, val text: TextColors,
    )

    private fun bindShift(
        c: Context, cell: RemoteViews, frameId: Int, bgId: Int, textId: Int,
        ev: DayEvent?, slot: Slot, emptyVisibility: Int,
    ) {
        if (ev == null) {
            cell.setViewVisibility(frameId, emptyVisibility)
            return
        }
        val inMonth = slot.inMonth
        val color = shiftColor(c, ev)

        // Multi-day events: join into one bar, breaking at the edges of each week row.
        val joinLeft = slot.join && slot.column > 0 && ev.covers(slot.date.minusDays(1))
        val joinRight = slot.join && slot.column < 6 && ev.covers(slot.date.plusDays(1))
        val gap = (2 * c.resources.displayMetrics.density).toInt()
        cell.setImageViewResource(bgId, when {
            joinLeft && joinRight -> R.drawable.shift_bg_mid
            joinLeft -> R.drawable.shift_bg_end
            joinRight -> R.drawable.shift_bg_start
            else -> R.drawable.shift_bg
        })
        cell.setViewPadding(bgId, if (joinLeft) 0 else gap, 0, if (joinRight) 0 else gap, 0)
        cell.setViewPadding(textId, gap, 0, gap, 0)

        // Name only at the start of the bar (or the start of each week row).
        var label = if (joinLeft) "" else Prefs.shiftLabel(c, ev.title) ?: ev.title
        if (slot.showTimes && !ev.allDay) label += "\n" + formatTime(c, ev.begin)
        cell.setViewVisibility(frameId, View.VISIBLE)
        cell.setInt(bgId, "setColorFilter", color)
        cell.setInt(bgId, "setImageAlpha", if (inMonth) 255 else 110)
        cell.setTextViewText(textId, label)
        cell.setTextColor(textId, slot.text.onEvent(color, if (inMonth) 255 else 110))
    }

    // --------------------------------------------------------------- upcoming

    fun buildUpcoming(c: Context, widgetId: Int): RemoteViews {
        val rv = RemoteViews(c.packageName, R.layout.widget_upcoming)
        applyFrame(c, rv, textColors(c), header = false)

        // Tapping a day opens it in the app: one template, each row fills in its date.
        val template = Intent(c, CalendarActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        rv.setPendingIntentTemplate(
            R.id.list,
            PendingIntent.getActivity(c, widgetId, template, mutable or PendingIntent.FLAG_UPDATE_CURRENT)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: hand the rows straight to the scrolling list.
            val items = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(1)
            upcomingRows(c).forEach { (date, row) -> items.addItem(date.toEpochDay(), row) }
            rv.setRemoteAdapter(R.id.list, items.build())
        } else {
            // Older Android: the list asks UpcomingListService for its rows.
            val intent = Intent(c, UpcomingListService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .setData(Uri.parse("shiftwidget://upcoming/$widgetId"))
            @Suppress("DEPRECATION")
            rv.setRemoteAdapter(R.id.list, intent)
        }
        return rv
    }

    /** One row per day for the Upcoming list: from today (or the day picked on the month widget). */
    fun upcomingRows(c: Context): List<Pair<LocalDate, RemoteViews>> {
        val tc = textColors(c)
        val today = LocalDate.now()
        val start = Prefs.selectedDay(c) ?: today
        val days = Prefs.upcomingDays(c)
        val skipEmpty = Prefs.upcomingSkipEmpty(c)
        // When skipping empty days, look further ahead so the list still fills up.
        val lookahead = if (skipEmpty) days * 3 else days
        val events = CalendarRepo.eventsByDay(c, start, start.plusDays(lookahead.toLong()))

        val dayText = tc.day
        val todayColor = Prefs.color(c, StyleColor.TODAY)
        val lineDp = Prefs.upcomingLineDp(c)
        val rows = ArrayList<Pair<LocalDate, RemoteViews>>()

        var i = 0
        while (rows.size < days && i < lookahead) {
            val date = start.plusDays(i.toLong())
            val dayEvents = events[date].orEmpty()
            i++
            if (skipEmpty && dayEvents.isEmpty()) continue

            // The day's shift colours the date block; everything else is listed beside it.
            val shift = dayEvents.firstOrNull { Prefs.isShift(c, it.title) }
            val others = dayEvents.filter { it !== shift }
            val blockColor = if (shift != null) shiftColor(c, shift) else Prefs.color(c, StyleColor.OFF_BLOCK)
            val blockText = Contrast.bestText(blockColor)

            val row = RemoteViews(c.packageName, R.layout.widget_upcoming_day)
            // List rows are recycled, so every visibility is set explicitly (never left to the layout).
            row.setViewVisibility(R.id.divider, if (rows.isEmpty() || lineDp == 0) View.GONE else View.VISIBLE)
            row.setViewPadding(R.id.divider, 0, (lineDp * c.resources.displayMetrics.density).toInt(), 0, 0)
            row.setInt(R.id.block_bg, "setColorFilter", blockColor)
            row.setTextViewText(R.id.dow, date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()))
            row.setTextViewText(R.id.date, String.format(Locale.getDefault(), "%02d", date.dayOfMonth))
            row.setTextViewText(R.id.month, date.month.getDisplayName(TextStyle.FULL, Locale.getDefault()))
            for (id in intArrayOf(R.id.dow, R.id.date, R.id.month)) row.setTextColor(id, blockText)
            row.setViewVisibility(R.id.today_ring, if (date == today) View.VISIBLE else View.GONE)
            row.setInt(R.id.today_ring, "setColorFilter", todayColor)

            row.setViewVisibility(R.id.shift_time, if (shift?.allDay == true) View.GONE else View.VISIBLE)
            if (shift != null) {
                row.setTextViewText(R.id.shift_name, Prefs.shiftLabel(c, shift.title) ?: shift.title)
                if (!shift.allDay) row.setTextViewText(R.id.shift_time, timeRange(c, shift, " - "))
            } else {
                row.setTextViewText(R.id.shift_name, c.getString(R.string.day_off))
                row.setTextViewText(R.id.shift_time, "-")
            }
            row.setTextColor(R.id.shift_name, dayText)
            row.setTextColor(R.id.shift_time, dayText)

            row.removeAllViews(R.id.others)
            for (e in others) {
                val line = RemoteViews(c.packageName, R.layout.widget_upcoming_line)
                line.setTextViewText(R.id.line, if (e.allDay) e.title else formatTime(c, e.begin) + "  " + e.title)
                line.setTextColor(R.id.line, dayText)
                row.addView(R.id.others, line)
            }
            row.setOnClickFillInIntent(R.id.day_row, Intent()
                .putExtra(CalendarActivity.EXTRA_DAY, date.toEpochDay())
                .setData(Uri.parse("shiftwidget://open/${date.toEpochDay()}")))
            rows += date to row
        }
        return rows
    }

    // ----------------------------------------------------------------- shared

    /** Background, header and transparency – common to both widgets. */
    private fun applyFrame(c: Context, rv: RemoteViews, tc: TextColors, header: Boolean = true) {
        rv.setInt(R.id.bg, "setColorFilter", Prefs.color(c, StyleColor.BACKGROUND))
        rv.setInt(R.id.bg, "setImageAlpha", Prefs.bgOpacity(c) * 255 / 100)
        if (!header) return
        rv.setInt(R.id.header_bg, "setColorFilter", Prefs.color(c, StyleColor.HEADER))
        rv.setInt(R.id.header_bg, "setImageAlpha", Prefs.headerOpacity(c) * 255 / 100)
        val headerText = tc.header
        for (id in intArrayOf(R.id.title, R.id.prev, R.id.next, R.id.settings)) rv.setTextColor(id, headerText)
    }

    private fun navIntents(c: Context, rv: RemoteViews, widgetId: Int, provider: Class<*>) {
        fun broadcast(action: String, code: Int) = PendingIntent.getBroadcast(
            c, widgetId * 8 + code,
            Intent(c, provider).setAction(action).putExtra(BaseWidgetProvider.EXTRA_ID, widgetId),
            PI_FLAGS
        )
        rv.setOnClickPendingIntent(R.id.prev, broadcast(BaseWidgetProvider.ACTION_PREV, 1))
        rv.setOnClickPendingIntent(R.id.next, broadcast(BaseWidgetProvider.ACTION_NEXT, 2))
        rv.setOnClickPendingIntent(R.id.title, broadcast(BaseWidgetProvider.ACTION_TODAY, 3))
        rv.setOnClickPendingIntent(R.id.settings, openSettings(c))
    }

    private fun openSettings(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 0, Intent().setComponent(ComponentName(c, SettingsActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PI_FLAGS
    )

    /** Tells the Upcoming widget to start from this day. */
    private fun selectDay(c: Context, date: LocalDate, code: Int): PendingIntent = PendingIntent.getBroadcast(
        c, code,
        Intent(c, MonthWidgetProvider::class.java)
            .setAction(BaseWidgetProvider.ACTION_SELECT_DAY)
            .setData(Uri.parse("shiftwidget://day/${date.toEpochDay()}"))
            .putExtra(BaseWidgetProvider.EXTRA_DAY, date.toEpochDay()),
        PI_FLAGS
    )

    /** Opens the phone's calendar app (e.g. Google Calendar) on the given day. */
    private fun openCalendarAt(c: Context, date: LocalDate, code: Int): PendingIntent {
        val millis = date.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/$millis"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(c, code, intent, PI_FLAGS)
    }

    fun shiftColor(c: Context, ev: DayEvent): Int = Prefs.shiftColor(c, ev.title) ?: ev.color

    private fun monthTitle(c: Context, m: YearMonth): String =
        m.format(DateTimeFormatter.ofPattern(if (m.year == LocalDate.now().year) "MMMM" else "MMMM yyyy", Locale.getDefault()))

    private fun formatTime(c: Context, millis: Long): String {
        val pattern = if (DateFormat.is24HourFormat(c)) "HH:mm" else "h:mma"
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault())).lowercase()
    }

    fun timeRange(c: Context, ev: DayEvent, sep: String = "–"): String =
        if (ev.allDay) "All day" else formatTime(c, ev.begin) + sep + formatTime(c, ev.end)

    /** Text colours for one render: picked for best contrast (auto) or the user's own. */
    private class TextColors(
        val header: Int, val day: Int, val weekday: Int,
        private val auto: Boolean, private val body: Int, private val manualEvent: Int,
    ) {
        /** Text on an event block drawn at [alpha] over the widget body. */
        fun onEvent(eventColor: Int, alpha: Int = 255): Int =
            if (auto) Contrast.bestText(Contrast.blend(eventColor, body, alpha)) else manualEvent
    }

    private fun textColors(c: Context): TextColors {
        val bg = Contrast.backdrop(c)
        if (!Prefs.autoTextColors(c)) return TextColors(
            Prefs.color(c, StyleColor.HEADER_TEXT), Prefs.color(c, StyleColor.DAY_TEXT),
            Prefs.color(c, StyleColor.WEEKDAY_TEXT), false, bg.body, Prefs.color(c, StyleColor.EVENT_TEXT),
        )
        val day = Contrast.bestText(bg.body)
        return TextColors(Contrast.bestText(bg.header), day, withAlpha(day, 0xB3), true, bg.body, 0)
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
}
