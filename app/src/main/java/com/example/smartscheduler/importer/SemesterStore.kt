package com.example.smartscheduler.importer

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ภาคเรียน: วันเปิดภาค + จำนวนสัปดาห์เรียน + สัปดาห์สอบปลายภาค (สัปดาห์ถัดจากสัปดาห์เรียนสุดท้าย)
 * สัปดาห์ที่ 1 เริ่มที่วันเปิดภาค ตัวอย่าง: เปิด 19 ต.ค. 2569, เรียน 17 สัปดาห์ → สอบสัปดาห์ที่ 18 (15–21 ก.พ. 2570)
 */
data class Semester(val start: LocalDate, val studyWeeks: Int) {
    val examWeek: Int get() = studyWeeks + 1

    fun weekStart(week: Int): LocalDate = start.plusWeeks((week - 1).toLong())
    fun weekEnd(week: Int): LocalDate = weekStart(week).plusDays(6)

    /** วันสุดท้ายของการเรียนตามปกติ (ก่อนสัปดาห์สอบ) */
    val lastStudyDay: LocalDate get() = weekEnd(studyWeeks)

    /** null เมื่อวันนั้นอยู่นอกภาคเรียน */
    fun weekOf(date: LocalDate): Int? {
        val days = ChronoUnit.DAYS.between(start, date)
        if (days < 0) return null
        val w = (days / 7).toInt() + 1
        return w.takeIf { it <= examWeek }
    }

    fun label(date: LocalDate): String? = when (val w = weekOf(date)) {
        null -> null
        examWeek -> "สัปดาห์สอบปลายภาค"
        else -> "สัปดาห์ที่ $w จาก $studyWeeks"
    }
}

@Singleton
class SemesterStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("semester", Context.MODE_PRIVATE)

    fun load(): Semester? {
        val day = prefs.getLong(KEY_START, Long.MIN_VALUE)
        if (day == Long.MIN_VALUE) return null
        return Semester(LocalDate.ofEpochDay(day), prefs.getInt(KEY_WEEKS, DEFAULT_WEEKS))
    }

    fun save(semester: Semester) {
        prefs.edit()
            .putLong(KEY_START, semester.start.toEpochDay())
            .putInt(KEY_WEEKS, semester.studyWeeks)
            .apply()
    }

    companion object {
        private const val KEY_START = "start_epoch_day"
        private const val KEY_WEEKS = "study_weeks"
        const val DEFAULT_WEEKS = 17
    }
}
