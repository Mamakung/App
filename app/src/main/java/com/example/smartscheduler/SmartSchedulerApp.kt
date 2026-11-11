package com.example.smartscheduler

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.*
import com.example.smartscheduler.notification.DailyRefreshWorker
import com.example.smartscheduler.notification.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class SmartSchedulerApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var scheduleDao: com.example.smartscheduler.data.local.dao.ScheduleDao
    @Inject lateinit var categoryDao: com.example.smartscheduler.data.local.dao.CategoryDao
    @Inject lateinit var currentUser: com.example.smartscheduler.data.session.CurrentUser

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.registerAll(this)
        scheduleDailyRefreshWorker()
        com.example.smartscheduler.update.UpdateCheckWorker.schedule(this)
        // วิดเจ็ต "วันนี้": วาดใหม่เมื่อเปิดแอป และทุกครั้งที่กิจกรรมวันนี้เปลี่ยน
        com.example.smartscheduler.widget.Widgets.refreshAll(this)
        com.example.smartscheduler.widget.TodayWidget.watch(this, scheduleDao, categoryDao) { currentUser.uid }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /** สร้างตารางล่วงหน้าวันละครั้ง */
    private fun scheduleDailyRefreshWorker() {
        val request = PeriodicWorkRequestBuilder<DailyRefreshWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DailyRefreshWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
