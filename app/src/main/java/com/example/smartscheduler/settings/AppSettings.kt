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

    // ---------- แจ้งเตือนและนาฬิกาปลุก ----------
    private fun flowOf(key: String, def: Boolean) = MutableStateFlow(prefs.getBoolean(key, def))
    private fun flowOf(key: String, def: String) = MutableStateFlow(prefs.getString(key, null) ?: def)

    private val _notifyOn = flowOf(KEY_NOTIFY_ON, true)
    val notifyOn: StateFlow<Boolean> = _notifyOn.asStateFlow()
    fun setNotifyOn(v: Boolean) { prefs.edit().putBoolean(KEY_NOTIFY_ON, v).apply(); _notifyOn.value = v }

    private val _alarmOn = flowOf(KEY_ALARM_ON, true)
    val alarmOn: StateFlow<Boolean> = _alarmOn.asStateFlow()
    fun setAlarmOn(v: Boolean) { prefs.edit().putBoolean(KEY_ALARM_ON, v).apply(); _alarmOn.value = v }

    private val _notifySound = flowOf(KEY_NOTIFY_SOUND, DEFAULT_NOTIFY_SOUND)
    val notifySound: StateFlow<String> = _notifySound.asStateFlow()
    fun setNotifySound(v: String) { prefs.edit().putString(KEY_NOTIFY_SOUND, v).apply(); _notifySound.value = v }

    private val _alarmSound = flowOf(KEY_ALARM_SOUND, DEFAULT_ALARM_SOUND)
    val alarmSound: StateFlow<String> = _alarmSound.asStateFlow()
    fun setAlarmSound(v: String) { prefs.edit().putString(KEY_ALARM_SOUND, v).apply(); _alarmSound.value = v }

    private val _vibrate = flowOf(KEY_VIBRATE, true)
    val vibrate: StateFlow<Boolean> = _vibrate.asStateFlow()
    fun setVibrate(v: Boolean) { prefs.edit().putBoolean(KEY_VIBRATE, v).apply(); _vibrate.value = v }

    private val _snooze = MutableStateFlow(prefs.getInt(KEY_SNOOZE, 5))
    val snooze: StateFlow<Int> = _snooze.asStateFlow()
    fun setSnooze(v: Int) { prefs.edit().putInt(KEY_SNOOZE, v).apply(); _snooze.value = v }

    /** ไอคอนแอปแสดงวันที่ (เปิดเป็นค่าเริ่มต้น) มีผลตอนออกจากแอป */
    private val _dateIcon = flowOf(KEY_DATE_ICON, true)
    val dateIcon: StateFlow<Boolean> = _dateIcon.asStateFlow()
    fun setDateIcon(v: Boolean) { prefs.edit().putBoolean(KEY_DATE_ICON, v).apply(); _dateIcon.value = v }

    companion object {
        const val KEY_DATE_ICON = "date_icon"
        const val DRAG_WHITE = "white"
        const val DEFAULT_NOTIFY_SOUND = "app:snd_n1_sai"
        const val DEFAULT_ALARM_SOUND = "app:snd_a1_morning"
        const val PREFS = "settings"
        const val KEY_NOTIFY_ON = "notify_on"
        const val KEY_ALARM_ON = "alarm_on"
        const val KEY_NOTIFY_SOUND = "notify_sound"
        const val KEY_ALARM_SOUND = "alarm_sound"
        const val KEY_VIBRATE = "alarm_vibrate"
        const val KEY_SNOOZE = "alarm_snooze"

        /** อ่านค่าจากที่ที่ไม่มี Hilt (ตัวรับเวลาเตือน, เซอร์วิสปลุก) */
        fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private const val KEY_FAB = "fab_style"
        private const val KEY_DRAG = "drag_color"
        private const val KEY_MOTION = "motion"
    }
}
