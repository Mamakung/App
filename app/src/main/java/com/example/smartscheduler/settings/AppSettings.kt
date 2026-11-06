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

    private companion object {
        const val KEY_FAB = "fab_style"
    }
}
