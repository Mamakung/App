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

    /**
     * สีตอนลากเลือกวัน: "white" หรือรูปแบบเดียวกับสีกิจกรรม ("h200", "h200~70", "teal")
     * ค่าเก่าจากรุ่นที่เป็นวงสี 5 สี แปลงให้เอง
     */
    private val _dragColor = MutableStateFlow(
        when (val v = prefs.getString(KEY_DRAG, null)) {
            null, "WHITE" -> DRAG_WHITE
            "PINK" -> "pink"
            "BLUE" -> "sky"
            "GREEN" -> "green"
            "YELLOW" -> "amber"
            else -> v
        }
    )
    val dragColor: StateFlow<String> = _dragColor.asStateFlow()

    fun setDragColor(key: String) {
        prefs.edit().putString(KEY_DRAG, key).apply()
        _dragColor.value = key
    }

    private val _motion = MutableStateFlow(
        runCatching { Motion.valueOf(prefs.getString(KEY_MOTION, null) ?: "") }.getOrDefault(Motion.FULL)
    )
    val motion: StateFlow<Motion> = _motion.asStateFlow()

    fun setMotion(motion: Motion) {
        prefs.edit().putString(KEY_MOTION, motion.name).apply()
        _motion.value = motion
    }

    companion object {
        const val DRAG_WHITE = "white"
        private const val KEY_FAB = "fab_style"
        private const val KEY_DRAG = "drag_color"
        private const val KEY_MOTION = "motion"
    }
}
