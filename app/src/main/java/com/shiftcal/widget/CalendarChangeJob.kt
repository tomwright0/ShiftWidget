package com.shiftcal.widget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.CalendarContract

/**
 * Fires whenever anything in the phone's calendar database changes (e.g. Google Calendar
 * syncs a new shift), redraws the widgets, then re-arms itself.
 */
class CalendarChangeJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            BaseWidgetProvider.updateAll(applicationContext)
            jobFinished(params, false)
            schedule(applicationContext)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val JOB_ID = 4711

        fun schedule(c: Context) {
            val info = JobInfo.Builder(JOB_ID, ComponentName(c, CalendarChangeJob::class.java))
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        CalendarContract.CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                    )
                )
                .setTriggerContentUpdateDelay(2_000)
                .setTriggerContentMaxDelay(10_000)
                .build()
            c.getSystemService(JobScheduler::class.java).schedule(info)
        }
    }
}
