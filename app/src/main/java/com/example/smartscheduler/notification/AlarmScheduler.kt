package com.example.smartscheduler.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.smartscheduler.data.local.entity.ScheduleTaskEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps AlarmManager to schedule exact, wake-device alarms for:
 *   - the daily wake-up time (Spec #2 / #6)
 *   - each individual task's reminder (Spec #6)
 *
 * Uses setExactAndAllowWhileIdle so reminders fire even in Doze mode. On Android 12+
 * this requires the SCHEDULE_EXACT_ALARM permission (declared in the manifest) and,
 * on Android 13+, a runtime check via AlarmManager.canScheduleExactAlarms().
 */
@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun canScheduleExactAlarms(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms() else true

    /** Schedules the wake-up alarm + every task reminder for a single generated/edited day. */
    fun rescheduleForDay(
        dayId: Long,
        date: LocalDate,
        wakeUpHour: Int,
        wakeUpMinute: Int,
        tasks: List<ScheduleTaskEntity>
    ) {
        cancelForDay(dayId, maxOf(tasks.size, 40))

        scheduleAt(
            requestCode = wakeUpRequestCode(dayId),
            triggerAt = date.atTime(wakeUpHour, wakeUpMinute),
            title = "ตื่นได้แล้ว!",
            message = "เริ่มต้นวันใหม่กันเลย",
            channel = NotificationChannels.WAKE_UP
        )

        tasks.forEachIndexed { index, task ->
            if (!task.reminderEnabled) return@forEachIndexed
            scheduleAt(
                requestCode = taskRequestCode(dayId, index),
                triggerAt = date.atTime(task.startHour, task.startMinute),
                title = when (task.kind) {
                    "BREAK" -> "☕ ถึงเวลาพัก: ${task.title}"
                    "CLASS" -> "📚 เข้าเรียน: ${task.title}"
                    "WORK" -> "💼 เริ่มทำงาน: ${task.title}"
                    else -> task.title
                },
                message = task.description ?: run {
                    val end = task.startHour * 60 + task.startMinute + task.durationMinutes
                    "%02d:%02d – %02d:%02d".format(task.startHour, task.startMinute, (end / 60) % 24, end % 60)
                },
                channel = NotificationChannels.TASK_REMINDER
            )
        }
    }

    private fun scheduleAt(requestCode: Int, triggerAt: LocalDateTime, title: String, message: String, channel: String) {
        val triggerAtMillis = triggerAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (triggerAtMillis <= System.currentTimeMillis()) return // don't schedule in the past

        val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            putExtra(ReminderBroadcastReceiver.EXTRA_TITLE, title)
            putExtra(ReminderBroadcastReceiver.EXTRA_MESSAGE, message)
            putExtra(ReminderBroadcastReceiver.EXTRA_CHANNEL, channel)
            putExtra(ReminderBroadcastReceiver.EXTRA_NOTIFICATION_ID, requestCode)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            // Fallback: inexact alarm if exact-alarm permission was denied.
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    fun cancelForDay(dayId: Long, previousTaskCount: Int) {
        cancel(wakeUpRequestCode(dayId))
        for (i in 0 until previousTaskCount) cancel(taskRequestCode(dayId, i))
    }

    private fun cancel(requestCode: Int) {
        val intent = Intent(context, ReminderBroadcastReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    // Deterministic, collision-resistant-enough request codes derived from dayId.
    private fun wakeUpRequestCode(dayId: Long): Int = (dayId * 1000 + 1).toInt()
    private fun taskRequestCode(dayId: Long, index: Int): Int = (dayId * 1000 + 2 + index).toInt()
}
