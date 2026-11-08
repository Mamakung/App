package com.example.smartscheduler.update

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * เช็กอัปเดตเบื้องหลังทุก 12 ชั่วโมง (เมื่อมีอินเทอร์เน็ต)
 * ถ้ามีเวอร์ชันใหม่จะแจ้งเตือนครั้งเดียวต่อเวอร์ชัน มีปุ่ม "อัปเดตเลย" (ดู UpdateNotifications)
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

    private fun notify(update: AppUpdate): Boolean = UpdateNotifications.showAvailable(applicationContext, update)

    companion object {
        private const val WORK_NAME = "app_update_check"
        private const val KEY_NOTIFIED_VERSION = "notified_version"

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
