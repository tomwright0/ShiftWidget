package com.shiftcal.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.ZoneId

abstract class BaseWidgetProvider : AppWidgetProvider() {

    abstract fun build(c: Context, widgetId: Int): RemoteViews

    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        if (startNewDay(c)) {
            updateAll(c)
        } else {
            ids.forEach { mgr.updateAppWidget(it, build(c, it)) }
        }
        scheduleRefreshes(c)
    }

    override fun onReceive(c: Context, intent: Intent) {
        super.onReceive(c, intent)
        val id = intent.getIntExtra(EXTRA_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        when (intent.action) {
            ACTION_PREV, ACTION_NEXT, ACTION_TODAY -> {
                if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
                val offset = when (intent.action) {
                    ACTION_PREV -> Prefs.monthOffset(c, id) - 1
                    ACTION_NEXT -> Prefs.monthOffset(c, id) + 1
                    else -> 0
                }
                Prefs.setMonthOffset(c, id, offset)
                AppWidgetManager.getInstance(c).updateAppWidget(id, build(c, id))
            }
            ACTION_SELECT_DAY -> {
                val day = LocalDate.ofEpochDay(intent.getLongExtra(EXTRA_DAY, LocalDate.now().toEpochDay()))
                // Tapping the already-selected day goes back to today.
                Prefs.setSelectedDay(c, if (day == Prefs.selectedDay(c) || day == LocalDate.now()) null else day)
                updateAll(c)
                scrollUpcomingToTop(c)
            }
            ACTION_REFRESH, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED -> updateAll(c)
        }
    }

    override fun onDeleted(c: Context, ids: IntArray) {
        ids.forEach { Prefs.removeWidget(c, it) }
    }

    override fun onEnabled(c: Context) = scheduleRefreshes(c)

    companion object {
        const val EXTRA_ID = AppWidgetManager.EXTRA_APPWIDGET_ID
        const val ACTION_PREV = "com.shiftcal.widget.PREV"
        const val ACTION_NEXT = "com.shiftcal.widget.NEXT"
        const val ACTION_TODAY = "com.shiftcal.widget.TODAY"
        const val ACTION_REFRESH = "com.shiftcal.widget.REFRESH"
        const val ACTION_SELECT_DAY = "com.shiftcal.widget.SELECT_DAY"
        const val EXTRA_DAY = "epoch_day"

        fun hasUpcomingWidget(c: Context): Boolean =
            AppWidgetManager.getInstance(c)
                .getAppWidgetIds(ComponentName(c, UpcomingWidgetProvider::class.java)).isNotEmpty()

        /**
         * True (once) when the date has changed since the widgets were last drawn: forgets the day
         * picked on the month widget, so Upcoming starts from today again.
         */
        private fun startNewDay(c: Context): Boolean {
            val today = LocalDate.now()
            if (Prefs.renderedDay(c) == today) return false
            Prefs.setRenderedDay(c, today)
            Prefs.setSelectedDay(c, null)
            return true
        }

        /** Redraw every placed widget of both kinds. On a new day, Upcoming also scrolls back to today. */
        fun updateAll(c: Context) {
            val newDay = startNewDay(c)
            val mgr = AppWidgetManager.getInstance(c)
            for (id in mgr.getAppWidgetIds(ComponentName(c, MonthWidgetProvider::class.java)))
                mgr.updateAppWidget(id, WidgetRenderer.buildMonth(c, id))
            val upcoming = mgr.getAppWidgetIds(ComponentName(c, UpcomingWidgetProvider::class.java))
            for (id in upcoming) mgr.updateAppWidget(id, WidgetRenderer.buildUpcoming(c, id))
            // Older Android loads list rows from UpcomingListService: tell it to reload.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) mgr.notifyAppWidgetViewDataChanged(upcoming, R.id.list)
            if (newDay) scrollUpcomingToTop(c)
            scheduleMidnight(c)
        }

        /** Show the first day of the Upcoming list (after it starts from a new day). */
        private fun scrollUpcomingToTop(c: Context) {
            val mgr = AppWidgetManager.getInstance(c)
            val ids = mgr.getAppWidgetIds(ComponentName(c, UpcomingWidgetProvider::class.java))
            val rv = RemoteViews(c.packageName, R.layout.widget_upcoming).apply { setScrollPosition(R.id.list, 0) }
            mgr.partiallyUpdateAppWidget(ids, rv)
        }

        fun scheduleRefreshes(c: Context) {
            CalendarChangeJob.schedule(c)
            scheduleMidnight(c)
        }

        /** Redraw just after midnight so "today" moves on. */
        private fun scheduleMidnight(c: Context) {
            val at = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli() + 5_000
            val pi = PendingIntent.getBroadcast(
                c, 0,
                Intent(c, MonthWidgetProvider::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val alarms = c.getSystemService(AlarmManager::class.java)
            // Exact if allowed (on time at midnight); otherwise Android may run it a while later.
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC, at, pi)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC, at, pi)
        }
    }
}

class MonthWidgetProvider : BaseWidgetProvider() {
    override fun build(c: Context, widgetId: Int) = WidgetRenderer.buildMonth(c, widgetId)
}

class UpcomingWidgetProvider : BaseWidgetProvider() {
    override fun build(c: Context, widgetId: Int) = WidgetRenderer.buildUpcoming(c, widgetId)
}
