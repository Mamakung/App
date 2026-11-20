package com.example.smartscheduler.data.backup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.example.smartscheduler.MainActivity
import com.example.smartscheduler.R
import java.util.concurrent.TimeUnit

/** เตือนให้สำรองข้อมูล ถ้าไม่ได้สำรองเกิน 7 วัน (เช็กวันละครั้ง, เตือนซ้ำได้ทุก 7 วัน) */
class BackupReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val p = ctx.getSharedPreferences("backup", Context.MODE_PRIVATE)
        if (!p.getBoolean("remind", true)) return Result.success()
        val now = System.currentTimeMillis()
        val week = TimeUnit.DAYS.toMillis(7)
        val firstSeen = p.getLong("first_seen", 0L).takeIf { it > 0 } ?: now.also { p.edit().putLong("first_seen", it).apply() }
        val last = maxOf(p.getLong("last_time", 0L), firstSeen)
        val lastNotified = p.getLong("last_notified", 0L)
        if (now - last < week || now - lastNotified < week) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "เตือนสำรองข้อมูล", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "เตือนเมื่อไม่ได้สำรองข้อมูลเกิน 7 วัน" }
            )
        }
        val open = PendingIntent.getActivity(
            ctx, 7301,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_BACKUP, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val days = TimeUnit.MILLISECONDS.toDays(now - last)
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(ctx).notify(
            7301,
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("ถึงเวลาสำรองข้อมูล")
                .setContentText(if (p.getLong("last_time", 0L) == 0L) "ยังไม่เคยสำรองข้อมูล แตะเพื่อสำรองตอนนี้" else "ไม่ได้สำรองมา $days วันแล้ว แตะเพื่อสำรองตอนนี้")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
        p.edit().putLong("last_notified", now).apply()
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "backup_reminder"
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "backup_reminder", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BackupReminderWorker>(1, TimeUnit.DAYS).setInitialDelay(6, TimeUnit.HOURS).build()
            )
        }
    }
}
