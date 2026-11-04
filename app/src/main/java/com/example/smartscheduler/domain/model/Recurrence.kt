package com.example.smartscheduler.domain.model

import java.time.DayOfWeek
import java.time.LocalDate

/** ประเภทของช่วงเวลาในตาราง: ทำงาน / พักเบรก / กิจกรรมทั่วไป */
enum class TaskKind(val code: String, val label: String) {
    WORK("WORK", "ทำงาน"),
    BREAK("BREAK", "พัก"),
    TASK("TASK", "กิจกรรม"),
    CLASS("CLASS", "เรียน");

    companion object {
        fun from(code: String?): TaskKind = values().firstOrNull { it.code == code } ?: TASK
    }
}

/** ตัวช่วยจัดการกฎการทำซ้ำ: วันในสัปดาห์ + เดือนในปี (เก็บใน DB เป็น CSV) */
object Recurrence {
    /** สัปดาห์เริ่มวันอาทิตย์: อา จ อ พ พฤ ศ ส */
    val dayCodes = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
    val dayLabelsTh = mapOf(
        "MON" to "จ.", "TUE" to "อ.", "WED" to "พ.", "THU" to "พฤ.",
        "FRI" to "ศ.", "SAT" to "ส.", "SUN" to "อา."
    )
    val monthLabelsTh = listOf(
        "ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.",
        "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค."
    )

    const val ALL_MONTHS_CSV = "1,2,3,4,5,6,7,8,9,10,11,12"
    const val WEEKDAYS_CSV = "MON,TUE,WED,THU,FRI"

    val allDays: Set<String> = dayCodes.toSet()
    val weekdays: Set<String> = parseDays(WEEKDAYS_CSV)
    val weekend: Set<String> = setOf("SAT", "SUN")
    val allMonths: Set<Int> = (1..12).toSet()

    /** ตำแหน่งคอลัมน์ในสัปดาห์ที่เริ่มวันอาทิตย์ (อา = 0 ... ส = 6) */
    fun column(day: DayOfWeek): Int = day.value % 7

    fun dayCode(day: DayOfWeek): String = dayCodes[column(day)]

    /** วันอาทิตย์แรกของสัปดาห์ที่มี [date] อยู่ */
    fun weekStart(date: LocalDate): LocalDate = date.minusDays(column(date.dayOfWeek).toLong())

    fun parseDays(csv: String): Set<String> =
        csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun parseMonths(csv: String): Set<Int> =
        csv.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()

    fun daysToCsv(days: Set<String>): String = dayCodes.filter { it in days }.joinToString(",")

    fun monthsToCsv(months: Set<Int>): String = months.sorted().joinToString(",")

    fun matches(date: LocalDate, daysCsv: String, monthsCsv: String): Boolean =
        dayCode(date.dayOfWeek) in parseDays(daysCsv) && date.monthValue in parseMonths(monthsCsv)
}
