package com.example.smartscheduler.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.smartscheduler.data.local.entity.ScheduleTaskEntity
import com.example.smartscheduler.domain.model.TaskKind
import com.example.smartscheduler.ui.theme.AppTheme
import java.time.LocalDate

/** ลากเลือกหลายวันแล้ว: จะเพิ่มหรือลบ */
@Composable
fun RangeActionDialog(days: Int, rangeText: String, onAdd: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("เลือก $days วัน") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(rangeText, style = MaterialTheme.typography.bodySmall, color = c.muted)
                ActionChoice(Icons.Filled.Add, "เพิ่มกิจกรรม", "เพิ่มกิจกรรมเดียวกันให้ทุกวันที่เลือก", c.ink, onAdd)
                ActionChoice(Icons.Filled.DeleteSweep, "ลบกิจกรรม", "เลือกกิจกรรมที่จะลบออกจากวันที่เลือก", MaterialTheme.colorScheme.error, onDelete)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    )
}

@Composable
private fun ActionChoice(icon: ImageVector, title: String, detail: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.ground).clickable(role = Role.Button, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = tint)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
    }
}

private data class TaskGroupKey(val title: String, val start: Int, val kind: String, val categoryId: Long?)

/**
 * ลบหลายวัน: กิจกรรมชื่อ+เวลาเดียวกันรวมเป็นแถวเดียว (บอกว่ามีกี่วัน) ติ๊กแล้วลบ (ถามยืนยันอีกครั้ง)
 * ลบเฉพาะวันที่เลือก กิจวัตร/ตารางเรียนวันอื่นยังอยู่
 */
@Composable
fun BulkDeleteDialog(
    items: List<Pair<LocalDate, ScheduleTaskEntity>>?,
    dayCount: Int,
    onDelete: (List<ScheduleTaskEntity>) -> Unit,
    onDismiss: () -> Unit
) {
    val c = AppTheme.colors
    val groups = remember(items) {
        items.orEmpty()
            .groupBy { (_, t) -> TaskGroupKey(t.title, t.startHour * 60 + t.startMinute, t.kind, t.categoryId) }
            .toList()
            .sortedBy { it.first.start }
    }
    var picked by remember(items) { mutableStateOf(setOf<TaskGroupKey>()) }
    var confirm by remember { mutableStateOf(false) }
    val chosen = groups.filter { it.first in picked }.flatMap { g -> g.second }
    val chosenDays = chosen.map { it.first }.distinct().size

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("ลบกิจกรรมใน $dayCount วัน") },
        text = {
            when {
                items == null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                groups.isEmpty() -> Text("วันที่เลือกยังไม่มีกิจกรรม", color = c.muted)
                else -> Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Hint("ติ๊กกิจกรรมที่จะลบ", Modifier.weight(1f))
                        TextButton(onClick = { picked = if (picked.size == groups.size) emptySet() else groups.map { it.first }.toSet() }) {
                            Text(if (picked.size == groups.size) "ไม่เลือกเลย" else "เลือกทั้งหมด")
                        }
                    }
                    LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(groups, key = { it.first.hashCode() }) { (key, list) ->
                            val t = list.first().second
                            val kind = TaskKind.from(t.kind)
                            val on = key in picked
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (on) MaterialTheme.colorScheme.error.copy(alpha = 0.12f) else c.ground)
                                    .clickable(role = Role.Checkbox) { picked = if (on) picked - key else picked + key }
                                    .padding(end = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp))
                                Icon(taskIcon(kind, t.icon, t.categoryId), contentDescription = null, tint = taskAccent(kind, t.color, t.categoryId), modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(t.title.ifBlank { taskLabel(kind, t.categoryId) }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "%02d:%02d · มีใน %d วัน".format(t.startHour, t.startMinute, list.map { it.first }.distinct().size),
                                        style = MaterialTheme.typography.labelSmall, color = c.muted
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { confirm = true }, enabled = chosen.isNotEmpty()) {
                Text(if (chosen.isEmpty()) "ลบ" else "ลบ ${chosen.size} รายการ", color = if (chosen.isEmpty()) androidx.compose.ui.graphics.Color.Unspecified else MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    )

    if (confirm) {
        ConfirmDeleteDialog(
            title = "ลบ ${chosen.size} รายการ ใน $chosenDays วัน",
            message = "ลบเฉพาะวันที่เลือก กิจวัตรและตารางเรียนวันอื่นยังอยู่ ลบแล้วย้อนกลับไม่ได้",
            confirmText = "ลบ",
            onConfirm = { confirm = false; onDelete(chosen.map { it.second }) },
            onDismiss = { confirm = false }
        )
    }
}
