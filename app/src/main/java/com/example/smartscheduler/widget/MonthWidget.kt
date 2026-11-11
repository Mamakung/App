package com.example.smartscheduler.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.widget.RemoteViews
import androidx.core.content.res.ResourcesCompat
import com.example.smartscheduler.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * วิดเจ็ต "เดือนนี้": ปฏิทินเดือนพร้อมไอคอนกิจกรรมแบบในแอป
 *  - ไอคอนเดียวกันหลายสี = ซ้อนเป็นชั้นเลื่อนไปทางขวา มีขอบสีพื้นคั่น (สูงสุด 3 ชั้น)
 *  - ไม่พอที่ = "+N"; วันที่ผ่านไปแล้วไอคอนจางลง; วันนี้ไฮไลต์ด้วยสีช่องวันนี้
 *  - แตะวันไหนเปิดวันนั้นในแอป; ลากปรับขนาดได้ (วาดใหม่ตามขนาด)
 */
@AndroidEntryPoint
class MonthWidgetProvider : AppWidgetProvider() {

    @Inject lateinit var data: WidgetData

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == MonthWidget.ACTION_MIDNIGHT) Widgets.refreshAll(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context, manager, ids)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        update(context, manager, intArrayOf(id))

    override fun onDeleted(context: Context, ids: IntArray) = WidgetStyle.delete(context, ids)
    override fun onDisabled(context: Context) = MonthWidget.cancelMidnight(context)

    private fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val today = LocalDate.now()
                val (from, to) = MonthWidget.range(today)
                ids.forEach { id ->
                    val pal = WidgetStyle.load(app, id).palette(app)
                    val marks = runCatching { data.marks(from, to, pal.dark) }.getOrDefault(emptyMap())
                    val opts = manager.getAppWidgetOptions(id)
                    val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 250
                    val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 250
                    manager.updateAppWidget(id, MonthWidget.views(app, id, today, marks, pal, wDp, hDp))
                }
                MonthWidget.scheduleMidnight(app)
            } finally {
                pending.finish()
            }
        }
    }
}

object MonthWidget {
    const val ACTION_MIDNIGHT = "com.example.smartscheduler.widget.MONTH_MIDNIGHT"
    private val monthNames = listOf("มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน", "พฤษภาคม", "มิถุนายน",
        "กรกฎาคม", "สิงหาคม", "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม")
    private val weekdays = listOf("อา", "จ", "อ", "พ", "พฤ", "ศ", "ส")

    // ระยะ (dp) ต้องตรงกับ res/layout/widget_month.xml
    private const val PAD = 10f
    private const val HEADER = 40f
    private const val TITLE = 24f

    private const val STACK_MAX = 3

    /** วันแรกที่แสดง (อาทิตย์ก่อนวันที่ 1) และจำนวนสัปดาห์ */
    private fun grid(today: LocalDate): Pair<LocalDate, Int> {
        val first = today.withDayOfMonth(1)
        val offset = first.dayOfWeek.value % 7 // อาทิตย์ = 0
        val weeks = (offset + first.lengthOfMonth() + 6) / 7
        return first.minusDays(offset.toLong()) to weeks
    }

    fun range(today: LocalDate): Pair<LocalDate, LocalDate> {
        val (start, weeks) = grid(today)
        return start to start.plusDays(weeks * 7L - 1)
    }

    private fun midnightIntent(context: Context) = PendingIntent.getBroadcast(
        context, 1,
        Intent(context, MonthWidgetProvider::class.java).setAction(ACTION_MIDNIGHT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun scheduleMidnight(context: Context) {
        val at = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 5_000
        context.getSystemService(AlarmManager::class.java)?.setWindow(AlarmManager.RTC, at, 2 * 60_000L, midnightIntent(context))
    }

    fun cancelMidnight(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(midnightIntent(context))
    }

    fun views(
        context: Context, id: Int, today: LocalDate, marks: Map<LocalDate, List<DayMark>>,
        pal: WidgetPalette, wDp: Int, hDp: Int
    ): RemoteViews {
        val (start, weeks) = grid(today)
        return RemoteViews(context.packageName, R.layout.widget_month).apply {
            setImageViewBitmap(R.id.month_image, render(context, today, marks, pal, wDp, hDp))
            setContentDescription(R.id.month_image, "ปฏิทิน ${monthNames[today.monthValue - 1]}")
            setOnClickPendingIntent(R.id.month_header, TodayWidget.openApp(context, id * 100 + 99))
            for (r in 0 until 6) {
                val rowId = context.resources.getIdentifier("month_row$r", "id", context.packageName)
                setViewVisibility(rowId, if (r < weeks) android.view.View.VISIBLE else android.view.View.GONE)
            }
            for (i in 0 until weeks * 7) {
                val cellId = context.resources.getIdentifier("cell_$i", "id", context.packageName)
                val date = start.plusDays(i.toLong())
                setOnClickPendingIntent(cellId, TodayWidget.openApp(context, id * 100 + i, date))
                setContentDescription(cellId, "${date.dayOfMonth} ${TodayWidget.months[date.monthValue - 1]}")
            }
        }
    }

    fun render(
        context: Context, today: LocalDate, marks: Map<LocalDate, List<DayMark>>,
        pal: WidgetPalette, wDp: Int, hDp: Int
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        // จำกัดขนาดรูป (หน่วยความจำของวิดเจ็ตมีจำกัด)
        val s = min(density, sqrt(1_400_000f / (wDp * hDp)))
        val bmp = Bitmap.createBitmap(max(1, (wDp * s).toInt()), max(1, (hDp * s).toInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(s, s) // ต่อจากนี้หน่วยเป็น dp
        val w = wDp.toFloat()
        val h = hDp.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val regular = runCatching { ResourcesCompat.getFont(context, R.font.ibm_plex_sans_thai_regular) }.getOrNull() ?: Typeface.DEFAULT
        val bold = runCatching { ResourcesCompat.getFont(context, R.font.ibm_plex_sans_thai_bold) }.getOrNull() ?: Typeface.DEFAULT_BOLD

        p.color = pal.bg
        c.drawRoundRect(RectF(0f, 0f, w, h), 20f, 20f, p)

        // หัว: ชื่อเดือน + ปี พ.ศ.
        p.typeface = bold
        p.textSize = 15f
        p.color = pal.head
        p.textAlign = Paint.Align.LEFT
        c.drawText(monthNames[today.monthValue - 1], PAD + 4f, PAD + 17f, p)
        p.typeface = regular
        p.textSize = 11f
        p.color = pal.muted
        p.textAlign = Paint.Align.RIGHT
        c.drawText("${today.year + 543}", w - PAD - 4f, PAD + 17f, p)

        val (start, weeks) = grid(today)
        val gridW = w - PAD * 2
        val cellW = gridW / 7f
        val gridTop = PAD + HEADER
        val cellH = (h - gridTop - PAD) / weeks

        // ชื่อวัน
        p.textAlign = Paint.Align.CENTER
        p.textSize = 10f
        for (i in 0..6) c.drawText(weekdays[i], PAD + cellW * (i + 0.5f), PAD + TITLE + 11f, p)

        val numSize = min(13f, max(10f, cellH * 0.3f))
        val iconSize = min(15f, max(8f, cellH * 0.32f))
        val step = max(2.5f, iconSize * 0.28f)
        val halo = max(0.7f, iconSize / 14f)

        for (i in 0 until weeks * 7) {
            val date = start.plusDays(i.toLong())
            val col = i % 7
            val row = i / 7
            val x0 = PAD + col * cellW
            val y0 = gridTop + row * cellH
            val inMonth = date.monthValue == today.monthValue
            val isToday = date == today

            if (isToday) {
                p.color = pal.today
                c.drawRoundRect(RectF(x0 + 1.5f, y0 + 1f, x0 + cellW - 1.5f, y0 + cellH - 1f), 8f, 8f, p)
            }
            p.typeface = if (isToday) bold else regular
            p.textSize = numSize
            p.textAlign = Paint.Align.CENTER
            p.color = when {
                isToday -> pal.onToday
                inMonth -> pal.ink
                else -> pal.muted
            }
            p.alpha = if (inMonth || isToday) 255 else 115
            val numBase = y0 + 3f + numSize
            c.drawText("${date.dayOfMonth}", x0 + cellW / 2, numBase, p)
            p.alpha = 255

            if (!inMonth) continue
            val dayMarks = marks[date].orEmpty()
            if (dayMarks.isEmpty()) continue

            // จัดกองไอคอนให้พอดีช่อง เหลือที่ให้ "+N"
            val budget = cellW - 4f
            var used = 0f
            var fit = dayMarks.size
            for ((k, m) in dayMarks.withIndex()) {
                val n = min(m.colors.size, STACK_MAX)
                val wd = iconSize + (n - 1) * step + if (k > 0) 2f else 0f
                val reserve = if (k < dayMarks.lastIndex) 12f else 0f
                if (used + wd + reserve > budget && k > 0) { fit = k; break }
                used += wd
            }
            val plus = if (fit < dayMarks.size) "+${dayMarks.size - fit}" else null
            p.typeface = regular
            p.textSize = max(8f, iconSize * 0.7f)
            val plusW = plus?.let { p.measureText(it) + 2f } ?: 0f
            var x = x0 + (cellW - used - plusW) / 2
            val y = numBase + 3f
            val past = date.isBefore(today)
            val layer = if (past) c.saveLayerAlpha(x0, y0, x0 + cellW, y0 + cellH, 140) else -1
            dayMarks.take(fit).forEachIndexed { k, m ->
                if (k > 0) x += 2f
                val n = min(m.colors.size, STACK_MAX)
                for (li in (n - 1) downTo 0) {
                    val lx = x + li * step
                    if (n > 1) {
                        for ((dx, dy) in HALO_DIRS) {
                            VectorDraw.draw(c, m.icon, lx + dx * halo, y + dy * halo, iconSize, if (isToday) pal.today else pal.bg, p)
                        }
                    }
                    VectorDraw.draw(c, m.icon, lx, y, iconSize, if (isToday) pal.onToday else m.colors[li], p)
                }
                x += iconSize + (n - 1) * step
            }
            if (plus != null) {
                p.color = if (isToday) pal.onToday else pal.muted
                p.textAlign = Paint.Align.LEFT
                c.drawText(plus, x + 2f, y + iconSize * 0.8f, p)
            }
            if (layer >= 0) c.restoreToCount(layer)
        }
        return bmp
    }

    private val HALO_DIRS = listOf(-1f to 0f, 1f to 0f, 0f to -1f, 0f to 1f, -1f to -1f, 1f to 1f, -1f to 1f, 1f to -1f)
}
