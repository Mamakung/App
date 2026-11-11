package com.example.smartscheduler.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.core.graphics.ColorUtils
import com.example.smartscheduler.data.local.dao.CategoryDao
import com.example.smartscheduler.data.local.dao.ScheduleDao
import com.example.smartscheduler.data.session.CurrentUser
import com.example.smartscheduler.domain.model.TaskKind
import com.example.smartscheduler.ui.components.TaskIcons
import com.example.smartscheduler.ui.components.icon
import com.example.smartscheduler.ui.theme.TaskPalette
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** ชุดสีของวิดเจ็ตหนึ่งอัน */
data class WidgetPalette(
    val bg: Int,
    val ink: Int,
    val muted: Int,
    val head: Int,
    val today: Int,
    val onToday: Int,
    /** พื้นเข้มไหม (ใช้เลือกเฉดสีกิจกรรมให้ตัดกับพื้น) */
    val dark: Boolean
)

/** พรีเซ็ทสีวิดเจ็ต */
enum class WidgetPreset(val label: String, val detail: String) {
    AUTO("อัตโนมัติ", "มืด/สว่างตามเครื่อง"),
    DARK("มืด", "เขียวเข้ม + ฟ้า"),
    LIGHT("สว่าง", "ขาว + ฟ้า"),
    PHONE("ชุดสีโทรศัพท์", "ตามวอลเปเปอร์"),
    CUSTOM("เลือกเอง", "2 สีด้านล่าง")
}

/** การตั้งค่าสีของวิดเจ็ตแต่ละอัน (เก็บตาม appWidgetId) */
data class WidgetStyle(
    val preset: WidgetPreset = WidgetPreset.AUTO,
    /** สีวิดเจ็ต (พื้น + หัว) รูปแบบเดียวกับสีกิจกรรม เช่น "sky", "h200~70" */
    val bgKey: String = "sky",
    /** สีช่องวันนี้ */
    val todayKey: String = "pink"
) {
    fun palette(context: Context): WidgetPalette {
        val phoneDark = isPhoneDark(context)
        return when (preset) {
            WidgetPreset.AUTO -> if (phoneDark) DARK_PAL else LIGHT_PAL
            WidgetPreset.DARK -> DARK_PAL
            WidgetPreset.LIGHT -> LIGHT_PAL
            WidgetPreset.PHONE -> phonePalette(context, phoneDark) ?: if (phoneDark) DARK_PAL else LIGHT_PAL
            WidgetPreset.CUSTOM -> custom(phoneDark)
        }
    }

    /** เลือกเอง: ใช้สีตรงตามที่เห็นในตัวเลือกสี ตัวหนังสือขาว/ดำตามพื้น */
    private fun custom(dark: Boolean): WidgetPalette {
        val bg = TaskPalette.resolve(bgKey, dark)?.first?.toArgb() ?: DARK_PAL.bg
        val today = TaskPalette.resolve(todayKey, dark)?.first?.toArgb() ?: DARK_PAL.today
        val bgDark = ColorUtils.calculateLuminance(bg) < 0.4
        val ink = if (bgDark) 0xFFE6EEEA.toInt() else 0xFF18302C.toInt()
        return WidgetPalette(
            bg = bg, ink = ink,
            muted = ColorUtils.blendARGB(ink, bg, 0.4f),
            head = ink, today = today, onToday = readableOn(today), dark = bgDark
        )
    }

    companion object {
        val DARK_PAL = WidgetPalette(
            bg = 0xFF19231F.toInt(), ink = 0xFFE6EEEA.toInt(), muted = 0xFF9AA9A4.toInt(),
            head = 0xFFE6EEEA.toInt(), today = 0xFF7CC4F5.toInt(), onToday = 0xFF0E2A3F.toInt(), dark = true
        )
        val LIGHT_PAL = WidgetPalette(
            bg = 0xFFFFFFFF.toInt(), ink = 0xFF18302C.toInt(), muted = 0xFF5A6A66.toInt(),
            head = 0xFF1F6FA8.toInt(), today = 0xFF1F6FA8.toInt(), onToday = 0xFFFFFFFF.toInt(), dark = false
        )

        fun isPhoneDark(context: Context) =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        fun readableOn(color: Int): Int =
            if (ColorUtils.calculateLuminance(color) > 0.45) 0xFF14201C.toInt() else 0xFFFFFFFF.toInt()

        /** ชุดสีจากวอลเปเปอร์ (Android 12+) */
        private fun phonePalette(context: Context, dark: Boolean): WidgetPalette? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
            fun c(id: Int) = context.getColor(id)
            return if (dark) WidgetPalette(
                bg = c(android.R.color.system_neutral1_900), ink = c(android.R.color.system_neutral1_50),
                muted = c(android.R.color.system_neutral2_400), head = c(android.R.color.system_accent1_200),
                today = c(android.R.color.system_accent1_200), onToday = c(android.R.color.system_accent1_800), dark = true
            ) else WidgetPalette(
                bg = c(android.R.color.system_neutral1_10), ink = c(android.R.color.system_neutral1_900),
                muted = c(android.R.color.system_neutral2_600), head = c(android.R.color.system_accent1_600),
                today = c(android.R.color.system_accent1_600), onToday = c(android.R.color.system_accent1_0), dark = false
            )
        }

        private fun prefs(context: Context) = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)

        fun load(context: Context, id: Int): WidgetStyle {
            val p = prefs(context)
            return WidgetStyle(
                preset = runCatching { WidgetPreset.valueOf(p.getString("w_${id}_preset", null) ?: "") }.getOrDefault(WidgetPreset.AUTO),
                bgKey = p.getString("w_${id}_bg", null) ?: "sky",
                todayKey = p.getString("w_${id}_today", null) ?: "pink"
            )
        }

        fun save(context: Context, id: Int, style: WidgetStyle) {
            prefs(context).edit()
                .putString("w_${id}_preset", style.preset.name)
                .putString("w_${id}_bg", style.bgKey)
                .putString("w_${id}_today", style.todayKey)
                .apply()
        }

        fun delete(context: Context, ids: IntArray) {
            val e = prefs(context).edit()
            ids.forEach { id -> listOf("preset", "bg", "today").forEach { e.remove("w_${id}_$it") } }
            e.apply()
        }
    }
}

/** สีประเภท (เหมือน AppTheme) */
fun kindColor(kind: TaskKind, dark: Boolean): Int = when (kind) {
    TaskKind.WORK -> if (dark) 0xFF8FA0FF else 0xFF2B44C4
    TaskKind.BREAK -> if (dark) 0xFF63D094 else 0xFF2F9E62
    TaskKind.TASK -> if (dark) 0xFFF0B45A else 0xFFB5741A
    TaskKind.CLASS -> if (dark) 0xFFE59BD9 else 0xFF9B3E8F
}.toInt()

/** ไอคอนหนึ่งกองของวัน: ไอคอนเดียวกัน หลายสี (เรียงตามเวลา) */
data class DayMark(val icon: ImageVector, val colors: List<Int>)

/** อ่านข้อมูลให้วิดเจ็ต (ใช้ทั้งตัววิดเจ็ตและหน้าเลือกสี) */
@Singleton
class WidgetData @Inject constructor(
    private val scheduleDao: ScheduleDao,
    private val categoryDao: CategoryDao,
    private val currentUser: CurrentUser
) {
    /** สีกิจกรรมของวันนี้ ไม่ซ้ำ เรียงตามเวลา สูงสุด 4 */
    suspend fun todayColors(dark: Boolean): List<Int> {
        val today = LocalDate.now()
        return items(today, today, dark)[today].orEmpty().map { it.second }.distinct().take(4)
    }

    /** ไอคอนของแต่ละวันในช่วง เหมือนปฏิทินเดือนในแอป (ไอคอนเดียวกันรวมเป็นกอง สีไม่ซ้ำ) */
    suspend fun marks(from: LocalDate, to: LocalDate, dark: Boolean): Map<LocalDate, List<DayMark>> =
        items(from, to, dark).mapValues { (_, list) ->
            list.groupBy({ it.first }, { it.second }).map { (icon, colors) -> DayMark(icon, colors.distinct()) }
        }

    /** กิจกรรมแต่ละวัน เรียงตามเวลา: (ไอคอน, สี) */
    private suspend fun items(from: LocalDate, to: LocalDate, dark: Boolean): Map<LocalDate, List<Pair<ImageVector, Int>>> {
        val uid = currentUser.uid
        val days = scheduleDao.observeRange(uid, from.toEpochDay(), to.toEpochDay()).first()
        val cats = categoryDao.observe(uid).first().associateBy { it.categoryId }
        return days.associate { d ->
            LocalDate.ofEpochDay(d.day.dateEpochDay) to d.tasks
                .sortedWith(compareBy({ it.startHour }, { it.startMinute }))
                .map { t ->
                    val kind = TaskKind.from(t.kind)
                    val cat = t.categoryId?.let { cats[it] }
                    val icon = (TaskIcons.find(t.icon) ?: TaskIcons.find(cat?.icon))?.vector ?: kind.icon()
                    val color = TaskPalette.resolve(t.color ?: cat?.color, dark)?.first?.toArgb() ?: kindColor(kind, dark)
                    icon to color
                }
        }
    }
}

/** วาด ImageVector ลง Canvas ธรรมดา (ใช้ไอคอนชุดเดียวกับในแอป) */
object VectorDraw {
    private val cache = HashMap<ImageVector, List<Pair<Path, Boolean>>>()

    private fun paths(v: ImageVector): List<Pair<Path, Boolean>> = cache.getOrPut(v) {
        val out = mutableListOf<Pair<Path, Boolean>>()
        fun walk(g: VectorGroup, m: Matrix) {
            val mm = Matrix(m).apply {
                preTranslate(g.translationX + g.pivotX, g.translationY + g.pivotY)
                preRotate(g.rotation)
                preScale(g.scaleX, g.scaleY)
                preTranslate(-g.pivotX, -g.pivotY)
            }
            for (node in g) when (node) {
                is VectorPath -> {
                    val p = PathParser().addPathNodes(node.pathData).toPath().asAndroidPath()
                    p.transform(mm)
                    p.fillType = if (node.pathFillType == PathFillType.EvenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
                    out += p to (node.fill != null)
                }
                is VectorGroup -> walk(node, mm)
            }
        }
        walk(v.root, Matrix())
        out
    }

    fun draw(canvas: Canvas, v: ImageVector, x: Float, y: Float, size: Float, color: Int, paint: Paint) {
        paint.color = color
        paint.style = Paint.Style.FILL
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(size / v.viewportWidth, size / v.viewportHeight)
        paths(v).forEach { (p, fill) -> if (fill) canvas.drawPath(p, paint) }
        canvas.restore()
    }
}

/** สั่งวิดเจ็ตทุกแบบวาดใหม่ */
object Widgets {
    fun refreshAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        listOf(TodayWidgetProvider::class.java, MonthWidgetProvider::class.java).forEach { cls ->
            val ids = mgr.getAppWidgetIds(ComponentName(context, cls))
            if (ids.isNotEmpty()) context.sendBroadcast(
                Intent(context, cls).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }
    }

    fun refreshOne(context: Context, id: Int) {
        val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(id) ?: return
        context.sendBroadcast(
            Intent().setComponent(info.provider).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(id))
        )
    }
}

/** วันที่ที่ขอให้เปิด (แตะวันในวิดเจ็ตเดือน) หน้าปฏิทินอ่านแล้วล้างค่า */
object OpenDateRequest {
    val date = kotlinx.coroutines.flow.MutableStateFlow<LocalDate?>(null)
}
