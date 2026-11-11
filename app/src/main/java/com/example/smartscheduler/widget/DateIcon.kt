package com.example.smartscheduler.widget

import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.example.smartscheduler.settings.AppSettings
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * ไอคอนแอปตามวันที่: activity-alias ".DateIcon_<เดือน>_<วัน>" (372 อัน สร้างโดย tools/gen_date_icons.py)
 * เปิดได้ทีละอัน สลับตอนเที่ยงคืน/ตอนออกจากแอป (ไม่สลับตอนแอปเปิดอยู่ กันแอปถูกปิด)
 * ปิดสวิตช์ในตั้งค่า = กลับไปใช้ ".DefaultIcon"
 */
object DateIcon {
    private const val PREFS = "date_icon"
    private const val KEY_CURRENT = "current_alias"
    private const val DEFAULT = "DefaultIcon"
    private val worker = Executors.newSingleThreadExecutor()

    /** จำนวนหน้าจอของแอปที่เปิดอยู่ (0 = อยู่เบื้องหลัง) */
    @Volatile private var started = 0

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { started++ }
            override fun onActivityStopped(a: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0 && !a.isChangingConfigurations) applyAsync(app)
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        scheduleMidnight(app)
    }

    private fun desired(context: Context): String {
        val on = AppSettings.prefs(context).getBoolean(AppSettings.KEY_DATE_ICON, true)
        if (!on) return DEFAULT
        val d = LocalDate.now()
        return "DateIcon_${d.monthValue}_${d.dayOfMonth}"
    }

    /** สลับไอคอนทันทีถ้าแอปไม่ได้เปิดอยู่ (เรียกจากตัวรับเวลา) */
    fun applyIfBackground(context: Context) {
        if (started == 0) runCatching { apply(context.applicationContext) }
    }

    private fun applyAsync(context: Context) = worker.execute { runCatching { apply(context) } }

    private fun apply(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getString(KEY_CURRENT, DEFAULT) ?: DEFAULT
        val want = desired(context)
        if (current == want) return
        val pm = context.packageManager
        fun cn(name: String) = ComponentName(context.packageName, "${context.packageName}.$name")
        // เปิดอันใหม่ก่อน แล้วค่อยปิดอันเก่า (กันช่วงที่ไม่มีไอคอนเลย)
        pm.setComponentEnabledSetting(cn(want), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(
            cn(current),
            if (current == DEFAULT) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP
        )
        prefs.edit().putString(KEY_CURRENT, want).apply()
    }

    private fun midnightIntent(context: Context) = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, DateIconReceiver::class.java).setAction(DateIconReceiver.ACTION_MIDNIGHT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun scheduleMidnight(context: Context) {
        val at = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 10_000
        context.getSystemService(AlarmManager::class.java)?.setWindow(AlarmManager.RTC, at, 5 * 60_000L, midnightIntent(context))
    }
}

/** เที่ยงคืน / เปิดเครื่อง / เปลี่ยนเวลา / อัปเดตแอป → สลับไอคอน */
class DateIconReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DateIcon.applyIfBackground(context)
        DateIcon.scheduleMidnight(context)
    }

    companion object {
        const val ACTION_MIDNIGHT = "com.example.smartscheduler.widget.DATE_ICON_MIDNIGHT"
    }
}
