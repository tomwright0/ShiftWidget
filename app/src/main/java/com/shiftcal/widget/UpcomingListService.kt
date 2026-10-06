package com.shiftcal.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/** Supplies the Upcoming list's rows on Android 8–11 (Android 12+ passes rows directly). */
class UpcomingListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val c: Context) : RemoteViewsFactory {
        private var rows: List<RemoteViews> = emptyList()

        override fun onCreate() {}
        override fun onDataSetChanged() { rows = WidgetRenderer.upcomingRows(c).map { it.second } }
        override fun onDestroy() {}
        override fun getCount() = rows.size
        override fun getViewAt(position: Int): RemoteViews = rows[position]
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
    }
}
