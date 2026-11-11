package com.example.smartscheduler.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.*
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.example.smartscheduler.MainActivity
import com.example.smartscheduler.R
import com.example.smartscheduler.data.local.dao.CategoryDao
import com.example.smartscheduler.data.local.dao.ScheduleDao
import com.example.smartscheduler.data.session.CurrentUser
import com.example.smartscheduler.domain.model.TaskKind
import com.example.smartscheduler.ui.theme.TaskPalette
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * วิดเจ็ต 1×1 "วันนี้": หน้าตาเหมือนไอคอนแอป (พื้นน้ำเงิน ปฏิทินขาว เดือนย่อบนแถบ เลขวันที่)
 * + คาดเฉียงมุมขวาล่าง เป็นเส้นสีขนานกันของกิจกรรมวันนั้น (สีละเส้น เรียงตามเวลา สูงสุด 4)
 * สีเหมือนในปฏิทิน: สีที่เลือก > สีหมวด > สีประเภท ตามโหมดมืด/สว่างของเครื่อง
 * อัปเดต: เที่ยงคืน (AlarmManager) + ทุก 30 นาที (สำรอง) + ทันทีเมื่อกิจกรรมเปลี่ยน (ดู [TodayWidget.watch])
 */
@AndroidEntryPoint
class TodayWidgetProvider : AppWidgetProvider() {

    @Inject lateinit var scheduleDao: ScheduleDao
    @Inject lateinit var categoryDao: CategoryDao
    @Inject lateinit var currentUser: CurrentUser

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == TodayWidget.ACTION_MIDNIGHT ||
            intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            val mgr = AppWidgetManager.getInstance(context)
            onUpdate(context, mgr, mgr.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java)))
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val colors = runCatching { todayColors(app) }.getOrDefault(emptyList())
                val views = TodayWidget.views(app, LocalDate.now(), colors)
                ids.forEach { manager.updateAppWidget(it, views) }
                TodayWidget.scheduleMidnight(app)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDisabled(context: Context) = TodayWidget.cancelMidnight(context)

    private suspend fun todayColors(context: Context): List<Int> {
        val uid = currentUser.uid
        val day = scheduleDao.getDayOnce(uid, LocalDate.now().toEpochDay()) ?: return emptyList()
        val tasks = scheduleDao.getTasksForDayOnce(day.dayId)
        val categories = categoryDao.observe(uid).first().associateBy { it.categoryId }
        val dark = TodayWidget.isDark(context)
        return tasks
            .sortedWith(compareBy({ it.startHour }, { it.startMinute }))
            .map { t ->
                val key = t.color ?: t.categoryId?.let { categories[it]?.color }
                TaskPalette.resolve(key, dark)?.first?.toArgb() ?: TodayWidget.kindColor(TaskKind.from(t.kind), dark)
            }
            .distinct()
            .take(4)
    }
}

object TodayWidget {
    const val ACTION_MIDNIGHT = "com.example.smartscheduler.widget.MIDNIGHT"
    private val months = listOf("ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.", "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค.")

    fun isDark(context: Context) =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** สีประเภทเดียวกับ AppTheme (โหมดสว่าง/มืด) */
    fun kindColor(kind: TaskKind, dark: Boolean): Int = when (kind) {
        TaskKind.WORK -> if (dark) 0xFF8FA0FF else 0xFF2B44C4
        TaskKind.BREAK -> if (dark) 0xFF63D094 else 0xFF2F9E62
        TaskKind.TASK -> if (dark) 0xFFF0B45A else 0xFFB5741A
        TaskKind.CLASS -> if (dark) 0xFFE59BD9 else 0xFF9B3E8F
    }.toInt()

    /** ให้วิดเจ็ตทุกตัววาดใหม่ (เรียกเมื่อกิจกรรมเปลี่ยน) */
    fun refresh(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
        if (ids.isEmpty()) return
        context.sendBroadcast(
            Intent(context, TodayWidgetProvider::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        )
    }

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

    fun views(context: Context, date: LocalDate, colors: List<Int>): RemoteViews {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteViews(context.packageName, R.layout.widget_today).apply {
            setImageViewBitmap(R.id.widget_image, render(context, date, colors))
            setContentDescription(
                R.id.widget_image,
                "วันนี้ ${date.dayOfMonth} ${months[date.monthValue - 1]}" +
                    if (colors.isEmpty()) " ไม่มีกิจกรรม" else ""
            )
            setOnClickPendingIntent(R.id.widget_image, open)
        }
    }

    /** วาดไอคอนขนาด 108 หน่วย (เหมือน adaptive icon) ขยายเป็น [px] พิกเซล */
    fun render(context: Context, date: LocalDate, colors: List<Int>, px: Int = 324): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(px / 108f, px / 108f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        val outline = Path().apply { addRoundRect(RectF(0f, 0f, 108f, 108f), 28f, 28f, Path.Direction.CW) }
        c.save()
        c.clipPath(outline)
        p.color = 0xFF4F5BD5.toInt()
        c.drawRect(0f, 0f, 108f, 108f, p)

        // ปฏิทินขาว + แถบบน
        val card = Path().apply { addRoundRect(RectF(28f, 30f, 80f, 84f), 6f, 6f, Path.Direction.CW) }
        p.color = Color.WHITE
        c.drawPath(card, p)
        c.save()
        c.clipPath(card)
        p.color = 0xFF3A45B8.toInt()
        c.drawRect(28f, 30f, 80f, 44f, p)
        c.restore()

        val bold = runCatching { ResourcesCompat.getFont(context, R.font.ibm_plex_sans_thai_bold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
        p.typeface = bold
        p.textAlign = Paint.Align.CENTER
        p.color = Color.WHITE
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

    /** ฟังการเปลี่ยนแปลงกิจกรรมวันนี้ระหว่างที่แอปเปิดอยู่ แล้ววาดวิดเจ็ตใหม่ */
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
                today.flatMapLatest { d -> scheduleDao.observeDay(uid(), d.toEpochDay()) },
                categoryDao.observe(uid())
            ) { day, cats ->
                day?.day?.dateEpochDay to (day?.tasks?.map { listOf(it.taskId, it.color, it.categoryId, it.kind, it.startHour, it.startMinute) } to cats.map { it.categoryId to it.color })
            }
                .distinctUntilChanged()
                .drop(1) // ครั้งแรกตอนเปิดแอป: วิดเจ็ตวาดเองอยู่แล้ว
                .collect { refresh(app) }
        }
    }
}
