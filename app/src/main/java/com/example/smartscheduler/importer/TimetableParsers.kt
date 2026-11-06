package com.example.smartscheduler.importer

import com.example.smartscheduler.domain.model.Recurrence
import com.example.smartscheduler.domain.model.TaskKind

/** หนึ่งคาบ/หนึ่งช่วงในตารางรายสัปดาห์ (ซ้ำทุกสัปดาห์ในวันเดียวกัน) */
data class TimetableEntry(
    val day: String,          // SUN..SAT
    val startMin: Int,
    val endMin: Int,
    val title: String,
    val code: String = "",
    val room: String = "",
    val teacher: String = "",
    val kind: TaskKind = TaskKind.CLASS,
    /** สีที่เลือกตอนตรวจก่อนบันทึก (ชื่อใน TaskPalette) null = สีตามประเภท */
    val color: String? = null
) {
    /** บรรทัดรองที่แสดงใต้ชื่อ เช่น "20105-2012  ห้อง 7102  ครูต่วนมะรูดี" */
    fun detail(): String = listOfNotNull(
        code.ifBlank { null },
        room.ifBlank { null }?.let { if (it.any(Char::isDigit)) "ห้อง $it" else it },
        teacher.ifBlank { null }?.let { "ครู$it" }
    ).joinToString("  ")
}

class TimetableParseException(message: String) : Exception(message)

/** แปลงชื่อวันได้หลายแบบ: จันทร์ / จ / จ. / MON / Monday / 1 (จันทร์) ... 7 (อาทิตย์) */
object DayNames {
    private val map: Map<String, String> = buildMap {
        fun add(code: String, vararg names: String) = names.forEach { put(it.lowercase(), code) }
        add("SUN", "อาทิตย์", "วันอาทิตย์", "อา", "อา.", "sun", "sunday", "7", "0")
        add("MON", "จันทร์", "วันจันทร์", "จ", "จ.", "mon", "monday", "1")
        add("TUE", "อังคาร", "วันอังคาร", "อ", "อ.", "tue", "tuesday", "2")
        add("WED", "พุธ", "วันพุธ", "พ", "พ.", "wed", "wednesday", "3")
        add("THU", "พฤหัสบดี", "พฤหัส", "วันพฤหัสบดี", "พฤ", "พฤ.", "thu", "thursday", "4")
        add("FRI", "ศุกร์", "วันศุกร์", "ศ", "ศ.", "fri", "friday", "5")
        add("SAT", "เสาร์", "วันเสาร์", "ส", "ส.", "sat", "saturday", "6")
    }

    fun parse(raw: String): String? = map[raw.trim().lowercase()]

    fun labelTh(code: String): String = when (code) {
        "SUN" -> "วันอาทิตย์"; "MON" -> "วันจันทร์"; "TUE" -> "วันอังคาร"; "WED" -> "วันพุธ"
        "THU" -> "วันพฤหัสบดี"; "FRI" -> "วันศุกร์"; "SAT" -> "วันเสาร์"; else -> code
    }
}

/** "08:15" / "8.15" / "0815" → นาทีนับจากเที่ยงคืน */
fun parseClock(raw: String): Int? {
    // รองรับ "08.15 น." ด้วย: เหลือไว้แค่ตัวเลขกับ ':'
    val t = raw.trim().replace('.', ':').filter { it.isDigit() || it == ':' }.trim(':')
    val (h, m) = when {
        ':' in t -> t.substringBefore(':') to t.substringAfter(':')
        t.length in 3..4 && t.all(Char::isDigit) -> t.dropLast(2) to t.takeLast(2)
        t.all(Char::isDigit) -> t to "0"
        else -> return null
    }
    val hh = h.toIntOrNull() ?: return null
    val mm = m.toIntOrNull() ?: return null
    if (hh !in 0..24 || mm !in 0..59) return null
    return (hh * 60 + mm).coerceAtMost(24 * 60)
}

/**
 * อ่านไฟล์ CSV ตารางรายสัปดาห์
 *
 * หัวตาราง (ลำดับคอลัมน์ไม่สำคัญ, รองรับไทย/อังกฤษ):
 *   day/วัน, start/เริ่ม, end/สิ้นสุด, title/วิชา/ชื่อ, code/รหัสวิชา, room/ห้อง, teacher/ครู, kind/ประเภท
 * จำเป็นต้องมี: วัน, เริ่ม, สิ้นสุด, ชื่อ (หรือรหัสวิชา)
 */
object CsvTimetableParser {

    private val aliases = mapOf(
        "day" to listOf("day", "วัน"),
        "start" to listOf("start", "เริ่ม", "เวลาเริ่ม", "from"),
        "end" to listOf("end", "สิ้นสุด", "ถึง", "เวลาสิ้นสุด", "to"),
        "title" to listOf("title", "name", "subject", "วิชา", "ชื่อ", "ชื่อวิชา", "ชื่อรายวิชา", "กิจกรรม"),
        "code" to listOf("code", "รหัส", "รหัสวิชา"),
        "room" to listOf("room", "ห้อง", "สถานที่"),
        "teacher" to listOf("teacher", "ครู", "ผู้สอน", "อาจารย์"),
        "kind" to listOf("kind", "type", "ประเภท")
    )

    fun parse(text: String): List<TimetableEntry> {
        val lines = text.removePrefix("﻿").lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) throw TimetableParseException("ไฟล์ว่าง")
        val delimiter = if (lines.first().count { it == '\t' } > lines.first().count { it == ',' }) '\t' else ','
        val header = splitRow(lines.first(), delimiter).map { it.trim().lowercase() }
        val index = aliases.mapValues { (_, names) -> header.indexOfFirst { h -> names.any { it == h } } }

        if (index.getValue("day") < 0 || index.getValue("start") < 0 || index.getValue("end") < 0 ||
            (index.getValue("title") < 0 && index.getValue("code") < 0)
        ) throw TimetableParseException("หัวตารางต้องมีคอลัมน์ วัน, เริ่ม, สิ้นสุด และ ชื่อวิชา (หรือรหัสวิชา)")

        val result = mutableListOf<TimetableEntry>()
        lines.drop(1).forEachIndexed { i, line ->
            val cells = splitRow(line, delimiter)
            fun cell(key: String) = index.getValue(key).takeIf { it >= 0 }?.let { cells.getOrNull(it) }?.trim().orEmpty()
            val rowNo = i + 2
            val day = DayNames.parse(cell("day")) ?: throw TimetableParseException("แถว $rowNo: ไม่รู้จักวัน \"${cell("day")}\"")
            val start = parseClock(cell("start")) ?: throw TimetableParseException("แถว $rowNo: เวลาเริ่ม \"${cell("start")}\" ไม่ถูกต้อง")
            val end = parseClock(cell("end")) ?: throw TimetableParseException("แถว $rowNo: เวลาสิ้นสุด \"${cell("end")}\" ไม่ถูกต้อง")
            if (end <= start) throw TimetableParseException("แถว $rowNo: เวลาสิ้นสุดต้องอยู่หลังเวลาเริ่ม")
            val title = cell("title").ifBlank { cell("code") }
            result += TimetableEntry(
                day = day, startMin = start, endMin = end, title = title,
                code = cell("code"), room = cell("room"), teacher = cell("teacher"),
                kind = TaskKind.values().firstOrNull { it.code.equals(cell("kind"), true) || it.label == cell("kind") }
                    ?: TaskKind.CLASS
            )
        }
        if (result.isEmpty()) throw TimetableParseException("ไม่พบรายการในไฟล์")
        return result.sortedWith(compareBy({ Recurrence.dayCodes.indexOf(it.day) }, { it.startMin }))
    }

    /** แยกคอลัมน์ รองรับเครื่องหมายคำพูด "a, b" และ "" ภายใน */
    private fun splitRow(line: String, delimiter: Char): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == delimiter && !quoted -> { out += sb.toString(); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
