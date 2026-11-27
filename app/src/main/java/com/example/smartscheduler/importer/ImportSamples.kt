package com.example.smartscheduler.importer

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.smartscheduler.ui.components.Hint
import com.example.smartscheduler.ui.theme.AppTheme
import java.io.File

/** ไฟล์ตัวอย่าง (วิชาสมมติ) หัวคอลัมน์ภาษาไทยที่ตัวอ่าน CSV รู้จัก */
internal object ImportSample {
    val header = listOf("วัน", "เริ่ม", "สิ้นสุด", "วิชา", "รหัสวิชา", "ห้อง", "ครู")
    val required = setOf(0, 1, 2, 3)
    val rows = listOf(
        listOf("จันทร์", "08:30", "10:30", "คณิตศาสตร์", "20000-1401", "301", "ครูสมชาย"),
        listOf("จันทร์", "10:30", "12:30", "ภาษาอังกฤษ", "20000-1202", "205", "ครูอร"),
        listOf("อังคาร", "08:30", "10:30", "ภาษาไทย", "20000-1101", "204", "ครูมาลี"),
        listOf("อังคาร", "13:00", "16:00", "วงจรไฟฟ้า", "20104-2001", "LAB2", "ครูวิทย์"),
        listOf("พุธ", "08:30", "12:30", "ฝึกงาน", "", "", ""),
        listOf("พุธ", "15:00", "16:00", "ชมรม", "", "หอประชุม", "")
    )

    /** มี BOM ให้ Excel เปิดภาษาไทยไม่เพี้ยน */
    fun csv(): String = "﻿" + (listOf(header) + rows).joinToString("\r\n") { r ->
        r.joinToString(",") { v -> if (v.contains(',') || v.contains('"')) "\"" + v.replace("\"", "\"\"") + "\"" else v }
    } + "\r\n"

    fun shareIntent(context: Context): Intent {
        val dir = File(context.cacheDir, "samples").apply { mkdirs() }
        val f = File(dir, FILE_NAME).apply { writeText(csv(), Charsets.UTF_8) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "เปิดไฟล์ตัวอย่างด้วย"
        )
    }

    const val FILE_NAME = "ตัวอย่างตาราง.csv"
}

enum class SampleTab { CSV, IMAGE }

/** แผ่นล่าง "ตัวอย่างไฟล์ตาราง": แท็บ CSV (ตาราง + บันทึก/แชร์) และแท็บรูปภาพ/PDF (รูปตัวอย่าง + วิธีถ่าย) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSampleSheet(initial: SampleTab, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    val context = LocalContext.current
    var tab by remember { mutableStateOf(initial) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val ok = runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(ImportSample.csv().toByteArray(Charsets.UTF_8)) } }.isSuccess
            Toast.makeText(context, if (ok) "บันทึกไฟล์ตัวอย่างแล้ว" else "บันทึกไม่สำเร็จ", Toast.LENGTH_SHORT).show()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 28.dp)) {
            Text("ตัวอย่างไฟล์ตาราง", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(Modifier.padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tab == SampleTab.CSV, onClick = { tab = SampleTab.CSV }, label = { Text("ไฟล์ CSV") })
                FilterChip(selected = tab == SampleTab.IMAGE, onClick = { tab = SampleTab.IMAGE }, label = { Text("รูปภาพ / PDF") })
            }
            if (tab == SampleTab.CSV) {
                Hint("แถวแรกเป็นหัวคอลัมน์ 1 แถว = 1 คาบ ทำใน Excel หรือ Google Sheets แล้วบันทึกเป็น .csv")
                Spacer(Modifier.height(10.dp))
                SampleTable()
                Hint("* ต้องมี · ช่องอื่นเว้นว่างได้ · เลื่อนตารางซ้ายขวาได้", Modifier.padding(top = 6.dp, bottom = 4.dp))
                RuleRow("วัน", "จันทร์ · จ. · Mon · 1")
                RuleRow("เวลา", "08:30 · 8.30 · 0830")
                RuleRow("หัวคอลัมน์", "ไทยหรืออังกฤษก็ได้ (day, start, end, title, code, room, teacher)")
                Button(
                    onClick = { save.launch(ImportSample.FILE_NAME) },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.ground)
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("บันทึกไฟล์ตัวอย่าง (.csv)", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { runCatching { context.startActivity(ImportSample.shareIntent(context)) } },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp), tint = c.ink)
                    Spacer(Modifier.width(8.dp))
                    Text("แชร์ไปเปิดใน Sheets / Excel", color = c.ink)
                }
            } else {
                SampleTimetableImage()
                Text("ถ่ายให้อ่านง่าย", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                Tip(true, "เห็นทั้งตาราง ถ่ายตรง ไม่เอียง")
                Tip(true, "เห็นชื่อวันและเวลาของแต่ละช่อง")
                Tip(true, "แสงพอ ตัวหนังสือชัด หรือใช้ PDF / ภาพแคปหน้าจอ")
                Tip(false, "รูปเบลอ มีเงามือบัง หรือตัดขอบตาราง")
                Hint("แอปจะให้ตรวจและแก้ทุกคาบก่อนบันทึกเสมอ", Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable
private fun SampleTable() {
    val widths: List<Dp> = listOf(56.dp, 50.dp, 54.dp, 90.dp, 84.dp, 68.dp, 70.dp)
    val line = Color(0xFFD6DBE0)
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).horizontalScroll(rememberScrollState())
    ) {
        Row {
            Cell("", 22.dp, Color(0xFFEEF0F3), line, Color(0xFF888888))
            ImportSample.header.forEachIndexed { i, h ->
                Cell(h + if (i in ImportSample.required) "*" else "", widths[i], Color(0xFF2B44C4), Color(0xFFC9D0E8), Color.White, bold = true)
            }
        }
        ImportSample.rows.forEachIndexed { r, row ->
            Row {
                Cell("${r + 2}", 22.dp, Color(0xFFEEF0F3), line, Color(0xFF888888))
                row.forEachIndexed { i, v -> Cell(v, widths[i], Color.White, line, Color(0xFF222222)) }
            }
        }
    }
}

@Composable
private fun Cell(text: String, width: Dp, bg: Color, border: Color, fg: Color, bold: Boolean = false) {
    Box(Modifier.width(width).heightIn(min = 30.dp).background(bg).border(0.5.dp, border).padding(horizontal = 5.dp, vertical = 6.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, color = fg, fontSize = 12.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

@Composable
private fun RuleRow(label: String, value: String) {
    Row(Modifier.padding(top = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(82.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.ink)
    }
}

@Composable
private fun Tip(ok: Boolean, text: String) {
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Filled.Check else Icons.Filled.Close, contentDescription = if (ok) "ควรทำ" else "ไม่ควร",
            tint = if (ok) AppTheme.colors.rest else MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** รูปตารางเรียนตัวอย่าง (วาดเอง สีกระดาษ) */
@Composable
private fun SampleTimetableImage() {
    val head = Color(0xFFE8E3D4)
    val ink = Color(0xFF222222)
    val blue = Color(0xFFDFE6FF); val green = Color(0xFFDFF3E6); val orange = Color(0xFFFBE8D0); val pink = Color(0xFFF5DFF0)
    // แต่ละแถว: ชื่อวัน + ช่อง (ข้อความ, สี, กินกี่ช่อง)
    data class Slot(val text: String, val color: Color, val span: Int = 1)
    val rows = listOf(
        "จ." to listOf(Slot("คณิต\n301", blue), Slot("อังกฤษ\n205", green), Slot("วงจร\nLAB2", orange), Slot("", Color.White)),
        "อ." to listOf(Slot("ไทย\n204", pink), Slot("คณิต\n301", blue), Slot("วงจรไฟฟ้า LAB2", orange, 2)),
        "พ." to listOf(Slot("ฝึกงาน", green, 2), Slot("", Color.White), Slot("ชมรม", pink))
    )
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFFBFAF6)).padding(8.dp)) {
        Text("ตารางเรียน ภาคเรียนที่ 2", color = ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            GridBox("", head, ink, Modifier.width(34.dp))
            listOf("8:30", "10:30", "13:00", "15:00").forEach { GridBox(it, head, ink, Modifier.weight(1f), bold = true) }
        }
        rows.forEach { (day, slots) ->
            Spacer(Modifier.height(2.dp))
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                GridBox(day, head, ink, Modifier.width(34.dp), bold = true)
                slots.forEach { s -> GridBox(s.text, s.color, ink, Modifier.weight(s.span.toFloat())) }
            }
        }
    }
}

@Composable
private fun GridBox(text: String, bg: Color, fg: Color, modifier: Modifier, bold: Boolean = false) {
    Box(modifier.fillMaxHeight().heightIn(min = 36.dp).background(bg).border(0.5.dp, Color(0xFFCCCCCC)).padding(4.dp), contentAlignment = Alignment.Center) {
        Text(text, color = fg, fontSize = 10.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center, lineHeight = 12.sp)
    }
}
