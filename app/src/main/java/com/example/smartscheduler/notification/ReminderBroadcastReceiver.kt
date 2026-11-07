package com.example.smartscheduler.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.example.smartscheduler.R
import dagger.hilt.android.AndroidEntryPoint

object NotificationChannels {
    const val WAKE_UP = "wake_up_channel"
    const val TASK_REMINDER = "task_reminder_channel"
    const val APP_UPDATE = "app_update_channel"
    const val ALARM = "alarm_ring_channel"

    /**
     * ช่องแจ้งเตือนกิจกรรมตามเสียงที่เลือก (Android ล็อกเสียงไว้กับช่อง จึงสร้างช่องใหม่ต่อเสียง
     * แล้วลบช่องเสียงเก่าทิ้ง) คืน id ช่องที่ใช้ได้ตอนนี้
     */
    fun taskChannel(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return TASK_REMINDER
        val manager = context.getSystemService(NotificationManager::class.java)
        val key = com.example.smartscheduler.settings.AppSettings.prefs(context)
            .getString(com.example.smartscheduler.settings.AppSettings.KEY_NOTIFY_SOUND, null)
            ?: com.example.smartscheduler.settings.AppSettings.DEFAULT_NOTIFY_SOUND
        val id = "task_sound_" + Integer.toHexString(key.hashCode())
        if (manager.getNotificationChannel(id) == null) {
            val uri = com.example.smartscheduler.settings.SoundLibrary(context).uriOf(key)
            manager.createNotificationChannel(
                NotificationChannel(id, "เตือนกิจกรรม", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "แจ้งเมื่อถึงเวลาเริ่มแต่ละกิจกรรม"
                    if (uri != null) setSound(
                        uri,
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                }
            )
        }
        manager.notificationChannels
            .filter { (it.id.startsWith("task_sound_") && it.id != id) || it.id == TASK_REMINDER }
            .forEach { manager.deleteNotificationChannel(it.id) }
        return id
    }

    fun registerAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(WAKE_UP, "ปลุกตอนเช้า", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "แจ้งเวลาตื่นนอนทุกวัน"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(ALARM, "นาฬิกาปลุก", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "ปลุกเต็มจอเมื่อถึงเวลากิจกรรมที่ตั้งปลุกไว้"
                setSound(null, null) // เสียงเล่นจากเซอร์วิสปลุกเอง (วนจนกดหยุด)
                enableVibration(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(APP_UPDATE, "อัปเดตแอป", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "แจ้งเมื่อมีแอปเวอร์ชันใหม่"
            }
        )
    }
}

/** Receives the AlarmManager trigger and posts the actual system notification. */
@AndroidEntryPoint
class ReminderBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Reminder"
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: ""
        val requested = intent.getStringExtra(EXTRA_CHANNEL) ?: NotificationChannels.TASK_REMINDER
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)

        NotificationChannels.registerAll(context)
        // เตือนกิจกรรม: ใช้ช่องตามเสียงที่เลือกในตั้งค่า
        val channel = if (requested == NotificationChannels.TASK_REMINDER) NotificationChannels.taskChannel(context) else requested

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return // caller must have requested POST_NOTIFICATIONS at runtime beforehand
        }
        androidx.core.app.NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_CHANNEL = "extra_channel"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }
}

/**
 * AlarmManager alarms are cleared on device reboot; this receiver re-derives and
 * re-schedules today's + tomorrow's alarms from Room on BOOT_COMPLETED via WorkManager
 * (kept out of the direct receiver to respect Android's background-execution limits).
 */
@AndroidEntryPoint
class BootRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            RescheduleAlarmsWorker.enqueue(context)
        }
    }
}
