package com.example.smartscheduler.update

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
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

/**
 * แจ้งเตือนอัปเดต:
 *  - "มีอัปเดตใหม่" + ปุ่ม "อัปเดตเลย" (โหลดเบื้องหลังโดยไม่ต้องเปิดแอป)
 *  - ระหว่างโหลดแสดงแถบความคืบหน้า
 *  - โหลดเสร็จ: "พร้อมติดตั้ง" แตะแล้วเปิดหน้าติดตั้งของ Android
 *  - แตะตัวแจ้งเตือน (ไม่ใช่ปุ่ม): เปิดแอปไปหน้าต่างอัปเดต
 */
object UpdateNotifications {
    const val NOTIFICATION_ID = 7001
    const val ACTION_DOWNLOAD = "com.example.smartscheduler.update.DOWNLOAD"
    private const val WORK_DOWNLOAD = "app_update_download"

    private const val K_CODE = "code"
    private const val K_NAME = "name"
    private const val K_URL = "url"
    private const val K_SIZE = "size"

    fun canPost(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun builder(ctx: Context) = NotificationCompat.Builder(ctx, NotificationChannels.APP_UPDATE)
        .setSmallIcon(R.drawable.ic_notification)
        .setOnlyAlertOnce(true)

    private fun openApp(ctx: Context): PendingIntent {
        val open = Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, NOTIFICATION_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun downloadAction(ctx: Context, update: AppUpdate, label: String): NotificationCompat.Action {
        val i = Intent(ctx, UpdateActionReceiver::class.java)
            .setAction(ACTION_DOWNLOAD)
            .putExtra(K_CODE, update.versionCode)
            .putExtra(K_NAME, update.versionName)
            .putExtra(K_URL, update.apkUrl)
            .putExtra(K_SIZE, update.sizeBytes)
        val pi = PendingIntent.getBroadcast(ctx, NOTIFICATION_ID, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action(0, label, pi)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun post(ctx: Context, b: NotificationCompat.Builder) {
        if (!canPost(ctx)) return
        NotificationChannels.registerAll(ctx)
        NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, b.build())
    }

    /** มีเวอร์ชันใหม่ */
    fun showAvailable(ctx: Context, update: AppUpdate): Boolean {
        if (!canPost(ctx)) return false
        val firstLine = update.notes.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        post(
            ctx, builder(ctx)
                .setContentTitle("มีอัปเดตใหม่ ${update.versionName}")
                .setContentText(firstLine ?: "กด \"อัปเดตเลย\" หรือแตะเพื่อดูว่ามีอะไรใหม่")
                .setContentIntent(openApp(ctx))
                .addAction(downloadAction(ctx, update, "อัปเดตเลย"))
                .setAutoCancel(true)
        )
        return true
    }

    fun showProgress(ctx: Context, update: AppUpdate, progress: Float?) {
        post(
            ctx, builder(ctx)
                .setContentTitle("กำลังดาวน์โหลด ${update.versionName}")
                .setContentText(progress?.let { "${(it * 100).toInt()}%" } ?: "กำลังเริ่ม…")
                .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
                .setContentIntent(openApp(ctx))
                .setOngoing(true)
        )
    }

    fun showReady(ctx: Context, update: AppUpdate, repo: UpdateRepository) {
        // ยังไม่อนุญาตติดตั้งแอปที่ไม่รู้จัก → เปิดแอป (หน้าต่างจะพาไปขออนุญาตแล้วติดตั้งไฟล์ที่โหลดไว้)
        val tap = if (repo.canInstall()) PendingIntent.getActivity(
            ctx, NOTIFICATION_ID + 1, repo.installIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ) else openApp(ctx)
        post(
            ctx, builder(ctx)
                .setContentTitle("พร้อมติดตั้ง ${update.versionName}")
                .setContentText("แตะเพื่อติดตั้ง แล้วกด \"อัปเดต\" ในหน้าถัดไป")
                .setContentIntent(tap)
                .setAutoCancel(true)
        )
    }

    fun showFailed(ctx: Context, update: AppUpdate) {
        post(
            ctx, builder(ctx)
                .setContentTitle("ดาวน์โหลด ${update.versionName} ไม่สำเร็จ")
                .setContentText("ตรวจอินเทอร์เน็ตแล้วลองอีกครั้ง")
                .setContentIntent(openApp(ctx))
                .addAction(downloadAction(ctx, update, "ลองอีกครั้ง"))
                .setAutoCancel(true)
        )
    }

    fun startDownload(ctx: Context, update: AppUpdate) {
        showProgress(ctx, update, null)
        val req = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setInputData(
                workDataOf(K_CODE to update.versionCode, K_NAME to update.versionName, K_URL to update.apkUrl, K_SIZE to update.sizeBytes)
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_DOWNLOAD, ExistingWorkPolicy.KEEP, req)
    }

    internal fun updateFrom(intent: Intent): AppUpdate? {
        val url = intent.getStringExtra(K_URL) ?: return null
        return AppUpdate(intent.getIntExtra(K_CODE, 0), intent.getStringExtra(K_NAME).orEmpty(), "", url, intent.getLongExtra(K_SIZE, 0))
    }

    internal fun updateFrom(data: Data): AppUpdate? {
        val url = data.getString(K_URL) ?: return null
        return AppUpdate(data.getInt(K_CODE, 0), data.getString(K_NAME).orEmpty(), "", url, data.getLong(K_SIZE, 0))
    }
}

/** ปุ่ม "อัปเดตเลย" / "ลองอีกครั้ง" ในแจ้งเตือน */
class UpdateActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateNotifications.ACTION_DOWNLOAD) return
        val update = UpdateNotifications.updateFrom(intent) ?: return
        UpdateNotifications.startDownload(context.applicationContext, update)
    }
}

/** โหลด APK เบื้องหลัง พร้อมแถบความคืบหน้าในแจ้งเตือน */
@HiltWorker
class UpdateDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repo: UpdateRepository
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val update = UpdateNotifications.updateFrom(inputData) ?: return Result.failure()
        var lastPct = -1
        return runCatching {
            repo.download(update) { p ->
                val pct = (p * 100).toInt()
                if (pct >= lastPct + 5 || pct == 100) {
                    lastPct = pct
                    UpdateNotifications.showProgress(ctx, update, p)
                }
            }
        }.fold(
            onSuccess = { UpdateNotifications.showReady(ctx, update, repo); Result.success() },
            onFailure = { UpdateNotifications.showFailed(ctx, update); Result.failure() }
        )
    }
}
