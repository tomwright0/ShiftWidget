package com.shiftcal.widget

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

/** Widget-wide colours the user can edit. */
enum class StyleColor(val key: String, val label: String, val default: Int) {
    BACKGROUND("c_background", "Widget background", 0xFF1E1E24.toInt()),
    HEADER("c_header", "Header bar", 0xFF2D2D36.toInt()),
    HEADER_TEXT("c_header_text", "Header text", 0xFFFFFFFF.toInt()),
    DAY_TEXT("c_day_text", "Day numbers / dates", 0xFFE0E0E0.toInt()),
    WEEKDAY_TEXT("c_weekday_text", "Weekday names", 0xFF9E9EA8.toInt()),
    EVENT_TEXT("c_event_text", "Event text", 0xFFFFFFFF.toInt()),
    OFF_BLOCK("c_off_block", "Upcoming: day-off block", 0xFF000000.toInt()),
    TODAY("c_today", "Today highlight", 0xFFFFC107.toInt()),
}

object Prefs {
    private const val FILE = "shift_widget"

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- widget style ----

    fun color(c: Context, sc: StyleColor): Int = sp(c).getInt(sc.key, sc.default)
    fun setColor(c: Context, sc: StyleColor, v: Int) = sp(c).edit().putInt(sc.key, v).apply()

    /** Background opacity, 0 (fully transparent) – 100. */
    fun bgOpacity(c: Context): Int = sp(c).getInt("bg_opacity", 92)
    fun setBgOpacity(c: Context, v: Int) = sp(c).edit().putInt("bg_opacity", v).apply()

    /** Header bar opacity, 0 – 100. */
    fun headerOpacity(c: Context): Int = sp(c).getInt("header_opacity", 100)
    fun setHeaderOpacity(c: Context, v: Int) = sp(c).edit().putInt("header_opacity", v).apply()

    /** Pick title/date/event text colours automatically for best contrast. */
    fun autoTextColors(c: Context): Boolean = sp(c).getBoolean("auto_text", true)
    fun setAutoTextColors(c: Context, v: Boolean) = sp(c).edit().putBoolean("auto_text", v).apply()

    /** App theme: 0 = follow the phone, 1 = light, 2 = dark. */
    fun appTheme(c: Context): Int = sp(c).getInt("app_theme", 0)
    fun setAppTheme(c: Context, v: Int) = sp(c).edit().putInt("app_theme", v).apply()

    fun weekStartsMonday(c: Context): Boolean = sp(c).getBoolean("week_monday", true)
    fun setWeekStartsMonday(c: Context, v: Boolean) = sp(c).edit().putBoolean("week_monday", v).apply()

    fun monthShowTimes(c: Context): Boolean = sp(c).getBoolean("month_times", false)
    fun setMonthShowTimes(c: Context, v: Boolean) = sp(c).edit().putBoolean("month_times", v).apply()

    fun joinMultiDay(c: Context): Boolean = sp(c).getBoolean("join_multiday", false)
    fun setJoinMultiDay(c: Context, v: Boolean) = sp(c).edit().putBoolean("join_multiday", v).apply()

    fun upcomingDays(c: Context): Int = sp(c).getInt("upcoming_days", 31)
    fun setUpcomingDays(c: Context, v: Int) = sp(c).edit().putInt("upcoming_days", v).apply()

    /** Thickness of the line between days in Upcoming, in dp (0 = no line). */
    fun upcomingLineDp(c: Context): Int = sp(c).getInt("upcoming_line_dp", 2)
    fun setUpcomingLineDp(c: Context, v: Int) = sp(c).edit().putInt("upcoming_line_dp", v).apply()

    fun upcomingSkipEmpty(c: Context): Boolean = sp(c).getBoolean("upcoming_skip_empty", false)
    fun setUpcomingSkipEmpty(c: Context, v: Boolean) = sp(c).edit().putBoolean("upcoming_skip_empty", v).apply()

    fun resetStyle(c: Context) {
        val e = sp(c).edit()
        StyleColor.entries.forEach { e.remove(it.key) }
        e.remove("bg_opacity").remove("header_opacity").apply()
    }

    // ---- per-shift overrides (keyed by event title) ----

    private fun key(title: String) = title.trim().lowercase()

    fun shiftColor(c: Context, title: String): Int? =
        sp(c).let { if (it.contains("sc_" + key(title))) it.getInt("sc_" + key(title), 0) else null }

    fun setShiftColor(c: Context, title: String, color: Int?) {
        val e = sp(c).edit()
        if (color == null) e.remove("sc_" + key(title)) else e.putInt("sc_" + key(title), color)
        e.apply()
    }

    fun shiftLabel(c: Context, title: String): String? = sp(c).getString("sl_" + key(title), null)

    fun setShiftLabel(c: Context, title: String, label: String?) {
        val e = sp(c).edit()
        if (label.isNullOrBlank()) e.remove("sl_" + key(title)) else e.putString("sl_" + key(title), label.trim())
        e.apply()
    }

    /** Whether this event is a shift (colours the date block in Upcoming). Default: name contains "shift". */
    fun isShift(c: Context, title: String): Boolean =
        sp(c).let { if (it.contains("is_" + key(title))) it.getBoolean("is_" + key(title), false) else title.contains("shift", ignoreCase = true) }

    fun setIsShift(c: Context, title: String, v: Boolean) = sp(c).edit().putBoolean("is_" + key(title), v).apply()

    fun isShiftHidden(c: Context, title: String): Boolean = sp(c).getBoolean("sh_" + key(title), false)
    fun setShiftHidden(c: Context, title: String, hidden: Boolean) =
        sp(c).edit().putBoolean("sh_" + key(title), hidden).apply()

    // ---- calendars ----

    fun hiddenCalendars(c: Context): Set<Long> =
        sp(c).getStringSet("hidden_cals", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()

    fun setCalendarHidden(c: Context, id: Long, hidden: Boolean) {
        val set = sp(c).getStringSet("hidden_cals", emptySet())!!.toMutableSet()
        if (hidden) set.add(id.toString()) else set.remove(id.toString())
        sp(c).edit().putStringSet("hidden_cals", set).apply()
    }

    /** Calendar that notes added in the app are saved to (-1 = automatic). */
    fun noteCalendarId(c: Context): Long = sp(c).getLong("note_calendar", -1)
    fun setNoteCalendarId(c: Context, id: Long) = sp(c).edit().putLong("note_calendar", id).apply()

    // ---- per-widget navigation ----

    fun monthOffset(c: Context, widgetId: Int): Int = sp(c).getInt("offset_$widgetId", 0)
    fun setMonthOffset(c: Context, widgetId: Int, v: Int) = sp(c).edit().putInt("offset_$widgetId", v).apply()
    /** Day picked on the month widget for Upcoming to start from (epoch day), or null for today. */
    fun selectedDay(c: Context): LocalDate? =
        sp(c).getLong("selected_day", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let { LocalDate.ofEpochDay(it) }

    /** The day the widgets were last drawn for, to notice when the date has moved on. */
    fun renderedDay(c: Context): LocalDate? =
        sp(c).getLong("rendered_day", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let { LocalDate.ofEpochDay(it) }
    fun setRenderedDay(c: Context, d: LocalDate) = sp(c).edit().putLong("rendered_day", d.toEpochDay()).apply()

    fun setSelectedDay(c: Context, d: LocalDate?) {
        val e = sp(c).edit()
        if (d == null) e.remove("selected_day") else e.putLong("selected_day", d.toEpochDay())
        e.apply()
    }

    fun removeWidget(c: Context, widgetId: Int) = sp(c).edit().remove("offset_$widgetId").apply()
}
