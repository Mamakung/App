package com.example.smartscheduler.update

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.example.smartscheduler.MainActivity
import com.example.smartscheduler.R
import com.example.smartscheduler.notification.NotificationChannels
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * เช็กอัปเดตเบื้องหลังทุก 12 ชั่วโมง (เมื่อมีอินเทอร์เน็ต)
 * ถ้ามีเวอร์ชันใหม่จะแจ้งเตือนครั้งเดียวต่อเวอร์ชัน แตะแล้วเปิดหน้าต่างอัปเดตในแอป
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repo: UpdateRepository
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val update = runCatching { repo.checkForUpdate() }.getOrElse { return Result.retry() }
            ?: return Result.success()

        val prefs = applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_NOTIFIED_VERSION, 0) >= update.versionCode) return Result.success()

        if (notify(update)) prefs.edit().putInt(KEY_NOTIFIED_VERSION, update.versionCode).apply()
        return Result.success()
    }

    private fun notify(update: AppUpdate): Boolean {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false

        NotificationChannels.registerAll(ctx)
        val open = Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            ctx, NOTIFICATION_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val firstLine = update.notes.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        val notification = NotificationCompat.Builder(ctx, NotificationChannels.APP_UPDATE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("มีอัปเดตใหม่ ${update.versionName}")
            .setContentText(firstLine ?: "แตะเพื่ออัปเดตแอป")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, notification)
        return true
    }

    companion object {
        private const val WORK_NAME = "app_update_check"
        private const val KEY_NOTIFIED_VERSION = "notified_version"
        private const val NOTIFICATION_ID = 7001

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
