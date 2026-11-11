package com.example.smartscheduler.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.widget.RemoteViews
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.example.smartscheduler.MainActivity
import com.example.smartscheduler.R
import com.example.smartscheduler.data.local.dao.CategoryDao
import com.example.smartscheduler.data.local.dao.ScheduleDao
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * วิดเจ็ต 1×1 "วันนี้": ไอคอนปฏิทิน (เดือนย่อบนแถบ + เลขวันที่)
 * + คาดเฉียงมุมขวาล่าง เป็นเส้นสีขนานกันของกิจกรรมวันนั้น (สีละเส้น เรียงตามเวลา สูงสุด 4)
 * สีวิดเจ็ต = พื้นไอคอน, สีช่องวันนี้ = แถบบนปฏิทิน (เลือกได้ในหน้าสีวิดเจ็ต)
 */
@AndroidEntryPoint
class TodayWidgetProvider : AppWidgetProvider() {

    @Inject lateinit var data: WidgetData

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == TodayWidget.ACTION_MIDNIGHT ||
            intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED
        ) Widgets.refreshAll(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ids.forEach { id ->
                    val pal = WidgetStyle.load(app, id).palette(app)
                    val colors = runCatching { data.todayColors(pal.dark) }.getOrDefault(emptyList())
                    manager.updateAppWidget(id, TodayWidget.views(app, LocalDate.now(), colors, pal))
                }
                TodayWidget.scheduleMidnight(app)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, ids: IntArray) = WidgetStyle.delete(context, ids)
    override fun onDisabled(context: Context) = TodayWidget.cancelMidnight(context)
}

object TodayWidget {
    const val ACTION_MIDNIGHT = "com.example.smartscheduler.widget.MIDNIGHT"
    val months = listOf("ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.", "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค.")

    /** เดิมใช้ชื่อนี้ เรียกต่อได้ */
    fun refresh(context: Context) = Widgets.refreshAll(context)

    private fun midnightIntent(context: Context) = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, TodayWidgetProvider::class.java).setAction(ACTION_MIDNIGHT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun scheduleMidnight(context: Context) {
        val at = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 5_000
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        // ไม่ต้องตรงเป๊ะ: ช่วงเที่ยงคืนถึง +2 นาที ไม่ต้องขอสิทธิ์ปลุกแบบแม่นยำ
        am.setWindow(AlarmManager.RTC, at, 2 * 60_000L, midnightIntent(context))
    }

    fun cancelMidnight(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(midnightIntent(context))
    }

    fun openApp(context: Context, requestCode: Int, date: LocalDate? = null): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (date != null) i.putExtra(MainActivity.EXTRA_OPEN_DATE, date.toEpochDay())
        return PendingIntent.getActivity(context, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun views(context: Context, date: LocalDate, colors: List<Int>, pal: WidgetPalette): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_today).apply {
            setImageViewBitmap(R.id.widget_image, render(context, date, colors, pal))
            setContentDescription(
                R.id.widget_image,
                "วันนี้ ${date.dayOfMonth} ${months[date.monthValue - 1]}" + if (colors.isEmpty()) " ไม่มีกิจกรรม" else ""
            )
            setOnClickPendingIntent(R.id.widget_image, openApp(context, 0))
        }

    /** วาดไอคอนขนาด 108 หน่วย (เหมือน adaptive icon) ขยายเป็น [px] พิกเซล */
    fun render(context: Context, date: LocalDate, colors: List<Int>, pal: WidgetPalette, px: Int = 324): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(px / 108f, px / 108f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        val outline = Path().apply { addRoundRect(RectF(0f, 0f, 108f, 108f), 28f, 28f, Path.Direction.CW) }
        c.save()
        c.clipPath(outline)
        p.color = pal.bg
        c.drawRect(0f, 0f, 108f, 108f, p)

        // ปฏิทิน (บนพื้นสว่างใช้สีเทาอ่อนให้เห็นขอบ) + แถบบน = สีช่องวันนี้
        val cardColor = if (ColorUtils.calculateLuminance(pal.bg) > 0.7) 0xFFE9EEF1.toInt() else Color.WHITE
        val card = Path().apply { addRoundRect(RectF(28f, 30f, 80f, 84f), 6f, 6f, Path.Direction.CW) }
        p.color = cardColor
        c.drawPath(card, p)
        c.save()
        c.clipPath(card)
        p.color = pal.today
        c.drawRect(28f, 30f, 80f, 44f, p)
        c.restore()

        val bold = runCatching { ResourcesCompat.getFont(context, R.font.ibm_plex_sans_thai_bold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
        p.typeface = bold
        p.textAlign = Paint.Align.CENTER
        p.color = pal.onToday
        p.textSize = 9f
        c.drawText(months[date.monthValue - 1], 54f, 41f, p)
        p.color = 0xFF1E2430.toInt()
        p.textSize = 29f
        c.drawText(date.dayOfMonth.toString(), 54f, 74f, p)

        // คาดเฉียงมุมขวาล่าง: เส้นสีขนานกัน ไม่มีขอบขาว
        if (colors.isNotEmpty()) {
            val center = 88f
            val width = 13f
            val length = 80f
            val h = width / colors.size
            c.save()
            c.rotate(-45f, center, center)
            colors.forEachIndexed { i, col ->
                p.color = col
                val top = center - width / 2 + i * h
                c.drawRect(center - length / 2, top, center + length / 2, top + h + 0.3f, p)
            }
            c.restore()
        }
        c.restore()
        return bmp
    }

    /** ฟังการเปลี่ยนแปลงกิจกรรม (เดือนนี้ ± 2 สัปดาห์) ระหว่างที่แอปเปิดอยู่ แล้ววาดวิดเจ็ตใหม่ */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun watch(context: Context, scheduleDao: ScheduleDao, categoryDao: CategoryDao, uid: () -> String) {
        val app = context.applicationContext
        val today = flow {
            while (true) {
                val now = LocalDate.now()
                emit(now)
                val next = now.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                delay((next - System.currentTimeMillis()).coerceAtLeast(1_000) + 2_000)
            }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            combine(
                today.flatMapLatest { d ->
                    val first = d.withDayOfMonth(1)
                    scheduleDao.observeRange(uid(), first.minusDays(7).toEpochDay(), first.plusMonths(1).plusDays(14).toEpochDay())
                },
                categoryDao.observe(uid())
            ) { days, cats ->
                days.map { d -> d.day.dateEpochDay to d.tasks.map { listOf(it.taskId, it.color, it.icon, it.categoryId, it.kind, it.startHour, it.startMinute) } } to
                    cats.map { listOf(it.categoryId, it.color, it.icon) }
            }
                .distinctUntilChanged()
                .drop(1) // ครั้งแรกตอนเปิดแอป: สั่งวาดใหม่อยู่แล้ว
                .collect { Widgets.refreshAll(app) }
        }
    }
}
