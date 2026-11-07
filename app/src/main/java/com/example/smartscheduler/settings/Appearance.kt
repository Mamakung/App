package com.example.smartscheduler.settings

import android.content.Context
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/** หน้าตาที่ผู้ใช้ปรับเอง (ตั้งที่ AppNavGraph จาก AppSettings) */
data class Appearance(
    val dragColor: Color = Color(DragColor.WHITE.argb),
    val motion: Motion = Motion.FULL
) {
    /** เด้งเข้า: เต็มที่ = สปริงเกินนิดแล้วเข้าที่ / น้อย = เลื่อนเร็วๆ / ปิด = ทันที */
    fun <T> pop(): AnimationSpec<T> = when (motion) {
        Motion.FULL -> spring(dampingRatio = 0.45f, stiffness = 520f)
        Motion.LOW -> tween(120)
        Motion.OFF -> snap()
    }

    /** หดออก/จางหาย: เร็วกว่าเข้า */
    fun <T> out(): AnimationSpec<T> = when (motion) {
        Motion.FULL -> tween(200)
        Motion.LOW -> tween(100)
        Motion.OFF -> snap()
    }

    val animate: Boolean get() = motion != Motion.OFF
}

val LocalAppearance = compositionLocalOf { Appearance() }

/**
 * จำเวลาและจำนวนครั้งที่ใช้ของแต่ละหมวด/ประเภท ไว้เติมให้อัตโนมัติ และเรียงหมวดที่ใช้บ่อยขึ้นก่อน
 * key: "c<id>" = หมวดที่สร้างเอง, "k<KIND>" = ประเภทพื้นฐาน
 */
class QuickStore(context: Context) {
    private val prefs = context.getSharedPreferences("quick", Context.MODE_PRIVATE)

    fun lastTimes(key: String): Pair<Int, Int>? {
        val v = prefs.getString("t_$key", null) ?: return null
        val (s, e) = v.split(",").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return null
        return s to e
    }

    fun count(key: String): Int = prefs.getInt("n_$key", 0)

    fun record(key: String, startMin: Int, endMin: Int) {
        prefs.edit()
            .putString("t_$key", "$startMin,$endMin")
            .putInt("n_$key", count(key) + 1)
            .apply()
    }

    companion object {
        fun keyFor(kind: String, categoryId: Long?) = if (categoryId != null) "c$categoryId" else "k$kind"
    }
}
