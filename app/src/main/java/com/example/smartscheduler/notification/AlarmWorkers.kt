package com.example.smartscheduler.notification

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.smartscheduler.data.repository.ScheduleRepository
import com.example.smartscheduler.data.session.CurrentUser
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Re-derives today's and tomorrow's schedule (generating from the active template if
 * needed) and re-registers their AlarmManager alarms, since exact alarms are cleared
 * by the system on reboot.
 */
@HiltWorker
class RescheduleAlarmsWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val scheduleRepository: ScheduleRepository,
    private val currentUser: CurrentUser
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val uid = currentUser.uid
        return try {
            scheduleRepository.rescheduleUpcomingAlarms(uid, 30)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "reschedule_alarms_after_boot"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<RescheduleAlarmsWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                androidx.work.ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}

/**
 * Periodic worker (enqueued daily) that pre-generates the next days'
 * schedule from the active template ahead of time.
 */
@HiltWorker
class DailyRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val scheduleRepository: ScheduleRepository,
    private val currentUser: CurrentUser
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val uid = currentUser.uid
        return try {
            scheduleRepository.rescheduleUpcomingAlarms(uid, 30)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "daily_refresh_worker"
    }
}
