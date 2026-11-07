package com.example.smartscheduler.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** รูปแบบเมนูของปุ่ม + ในหน้าปฏิทิน */
enum class FabStyle(val label: String, val detail: String) {
    RADIAL("วงรอบปุ่ม", "ไอคอนกางออกเป็นวงโค้งรอบปุ่ม"),
    VERTICAL("แนวตั้ง", "ไอคอนเรียงขึ้นไปเหนือปุ่ม")
}

/** สีไฮไลต์ตอนกดค้างแล้วลากเลือกวันในปฏิทิน (ARGB) */
enum class DragColor(val label: String, val argb: Long) {
    WHITE("ขาว", 0xFFE6EEEA),
    PINK("ชมพู", 0xFFE59BD9),
    BLUE("ฟ้า", 0xFF7CC4F5),
    GREEN("เขียว", 0xFF63D094),
    YELLOW("เหลือง", 0xFFF2C94C)
}

/** ระดับการเคลื่อนไหว (เด้งตอนเลือก เปลี่ยนเดือน ฯลฯ) */
enum class Motion(val label: String) {
    FULL("เต็มที่"),
    LOW("น้อย"),
    OFF("ปิด")
}

/** การตั้งค่าของแอป เก็บในเครื่อง (SharedPreferences) */
@Singleton
class AppSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _fabStyle = MutableStateFlow(
        runCatching { FabStyle.valueOf(prefs.getString(KEY_FAB, null) ?: "") }.getOrDefault(FabStyle.RADIAL)
    )
    val fabStyle: StateFlow<FabStyle> = _fabStyle.asStateFlow()

    fun setFabStyle(style: FabStyle) {
        prefs.edit().putString(KEY_FAB, style.name).apply()
        _fabStyle.value = style
    }

    private val _dragColor = MutableStateFlow(
        runCatching { DragColor.valueOf(prefs.getString(KEY_DRAG, null) ?: "") }.getOrDefault(DragColor.WHITE)
    )
    val dragColor: StateFlow<DragColor> = _dragColor.asStateFlow()

    fun setDragColor(color: DragColor) {
        prefs.edit().putString(KEY_DRAG, color.name).apply()
        _dragColor.value = color
    }

    private val _motion = MutableStateFlow(
        runCatching { Motion.valueOf(prefs.getString(KEY_MOTION, null) ?: "") }.getOrDefault(Motion.FULL)
    )
    val motion: StateFlow<Motion> = _motion.asStateFlow()

    fun setMotion(motion: Motion) {
        prefs.edit().putString(KEY_MOTION, motion.name).apply()
        _motion.value = motion
    }

    private companion object {
        const val KEY_FAB = "fab_style"
        const val KEY_DRAG = "drag_color"
        const val KEY_MOTION = "motion"
    }
}
