package com.example.smartscheduler.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.smartscheduler.ui.theme.TaskPalette
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.smartscheduler.data.local.entity.RoutineTemplateEntity
import com.example.smartscheduler.data.local.entity.RoutineTemplateTaskEntity
import com.example.smartscheduler.data.repository.DeleteScope
import com.example.smartscheduler.data.repository.effectiveDays
import com.example.smartscheduler.data.repository.effectiveMonths
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.smartscheduler.data.local.entity.ScheduleTaskEntity
import com.example.smartscheduler.domain.model.Recurrence
import com.example.smartscheduler.domain.model.TaskKind
import com.example.smartscheduler.ui.theme.AppTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// ============================================================================
// Draft model + mapping helpers
// ============================================================================

/** ข้อมูลที่ผู้ใช้กรอกใน dialog (เวลาเก็บเป็น "นาทีนับจากเที่ยงคืน") */
data class TaskDraft(
    val title: String = "",
    val kind: TaskKind = TaskKind.TASK,
    val startMin: Int = 9 * 60,
    val endMin: Int = 10 * 60,
    val reminder: Boolean = true,
    /** ใช้กับกิจวัตรเท่านั้น: null = ไม่แสดงตัวเลือกวันทำซ้ำในหน้าต่างแก้ไข */
    val repeatDays: Set<String>? = null,
    val repeatMonths: Set<Int>? = null,
    /** สีที่เลือก (ชื่อใน TaskPalette หรือ "h<องศา>") null = ตามหมวด/ประเภท */
    val color: String? = null,
    /** ไอคอนที่เลือก (ชื่อใน TaskIcons) null = ตามหมวด/ประเภท */
    val icon: String? = null,
    /** หมวดที่สร้างเอง null = ประเภทพื้นฐาน */
    val categoryId: Long? = null
)

/** ช่วงเวลาแบบย่อ ใช้วาดเส้นเวลา/แถบวัน */
data class TimeBlock(val startMin: Int, val endMin: Int, val kind: TaskKind, val color: String? = null, val categoryId: Long? = null)

fun fmt(minutes: Int): String = "%02d:%02d".format((minutes / 60) % 24, minutes % 60)

fun rangeText(start: Int, end: Int) = "${fmt(start)}–${fmt(end)}"

fun durationText(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "$h ชม. $m นาที"
        h > 0 -> "$h ชม."
        else -> "$m นาที"
    }
}

fun thaiShortDate(d: LocalDate) = "${d.dayOfMonth} ${Recurrence.monthLabelsTh[d.monthValue - 1]} ${d.year + 543}"

val ScheduleTaskEntity.startMin get() = startHour * 60 + startMinute
val ScheduleTaskEntity.endMin get() = startMin + durationMinutes
val RoutineTemplateTaskEntity.startMin get() = startHour * 60 + startMinute
val RoutineTemplateTaskEntity.endMin get() = startMin + durationMinutes

fun ScheduleTaskEntity.toDraft() =
    TaskDraft(title, TaskKind.from(kind), startMin, endMin, reminderEnabled, color = color, icon = icon, categoryId = categoryId)
fun RoutineTemplateTaskEntity.toDraft() =
    TaskDraft(title, TaskKind.from(kind), startMin, endMin, reminderEnabled, color = color, icon = icon, categoryId = categoryId)

/** ฉบับสำหรับหน้ากิจวัตร: ใส่วันทำซ้ำจริงของรายการ (หรือค่าเริ่มต้นของกิจวัตร) */
fun RoutineTemplateTaskEntity.toDraft(template: RoutineTemplateEntity) = toDraft().copy(
    repeatDays = Recurrence.parseDays(effectiveDays(template)),
    repeatMonths = Recurrence.parseMonths(effectiveMonths(template))
)

fun TaskDraft.toScheduleTask(base: ScheduleTaskEntity?, dayId: Long): ScheduleTaskEntity =
    (base ?: ScheduleTaskEntity(dayId = dayId, title = "", startHour = 0, startMinute = 0)).copy(
        title = title.trim(),
        startHour = startMin / 60,
        startMinute = startMin % 60,
        durationMinutes = endMin - startMin,
        reminderEnabled = reminder,
        kind = kind.code,
        color = color,
        icon = icon,
        categoryId = categoryId
    )

fun TaskDraft.toTemplateTask(base: RoutineTemplateTaskEntity?, templateId: Long): RoutineTemplateTaskEntity =
    (base ?: RoutineTemplateTaskEntity(templateId = templateId, title = "", startHour = 0, startMinute = 0)).copy(
        title = title.trim(),
        startHour = startMin / 60,
        startMinute = startMin % 60,
        durationMinutes = endMin - startMin,
        reminderEnabled = reminder,
        kind = kind.code,
        color = color,
        icon = icon,
        categoryId = categoryId,
        repeatDays = repeatDays?.let { Recurrence.daysToCsv(it) } ?: base?.repeatDays,
        repeatMonths = repeatMonths?.let { Recurrence.monthsToCsv(it) } ?: base?.repeatMonths
    )

fun defaultDraft(kind: TaskKind, start: Int = 9 * 60) = TaskDraft(
    title = when (kind) {
        TaskKind.WORK -> "ช่วงทำงาน"
        TaskKind.BREAK -> "พักเบรก"
        TaskKind.TASK -> ""
        TaskKind.CLASS -> "เรียน"
    },
    kind = kind,
    startMin = start,
    endMin = start + if (kind == TaskKind.BREAK) 15 else 60
)

@Composable
fun TaskKind.color(): Color = when (this) {
    TaskKind.WORK -> AppTheme.colors.work
    TaskKind.BREAK -> AppTheme.colors.rest
    TaskKind.TASK -> AppTheme.colors.task
    TaskKind.CLASS -> AppTheme.colors.study
}

@Composable
fun TaskKind.bg(): Color = when (this) {
    TaskKind.WORK -> AppTheme.colors.workBg
    TaskKind.BREAK -> AppTheme.colors.restBg
    TaskKind.TASK -> AppTheme.colors.taskBg
    TaskKind.CLASS -> AppTheme.colors.studyBg
}

fun TaskKind.icon(): ImageVector = when (this) {
    TaskKind.WORK -> Icons.Filled.Work
    TaskKind.BREAK -> Icons.Filled.Coffee
    TaskKind.TASK -> Icons.Filled.Checklist
    TaskKind.CLASS -> Icons.Filled.School
}

// ============================================================================
// Surfaces
// ============================================================================

/** พื้นขาวมุมโค้ง ไม่มีเงา ใช้จัดกลุ่มเนื้อหา */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(AppTheme.colors.surface)
            .padding(padding),
        content = content
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted, modifier = modifier)
}

/** ปุ่มหลักสีหมึก เต็มความกว้าง */
@Composable
fun InkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.ink, contentColor = AppTheme.colors.ground)
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** ตัวเลือก 2 ทางแบบแถบ (เช่น ไทย / สหรัฐฯ) */
@Composable
fun SegmentedToggle(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .selectableGroup()
            .padding(4.dp)
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selectedIndex
            val segBg by animateColorAsState(if (on) AppTheme.colors.surface else Color.Transparent, tween(150), label = "segBg")
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(segBg)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) })
                    .heightIn(min = 48.dp)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) AppTheme.colors.ink else AppTheme.colors.muted
                )
            }
        }
    }
}

// ============================================================================
// Repeat pickers: grid of toggle cells (7 days / 12 months)
// ============================================================================

/** ระยะห่างระหว่างปุ่มเลือกวัน/เดือน (ui-ux-pro-max: อย่างน้อย 8dp) */
private val PickerGap = 8.dp

@Composable
private fun ToggleCell(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = AppTheme.colors
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (on) c.ink else Color.Transparent)
            .border(1.dp, if (on) c.ink else c.outline, RoundedCornerShape(12.dp))
            .toggleable(value = on, role = Role.Checkbox, onValueChange = { onClick() }),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            color = if (on) c.ground else c.ink
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickPicks(items: List<Pair<String, () -> Unit>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (label, action) ->
            TextButton(onClick = action, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.work)
            }
        }
    }
}

@Composable
fun DayOfWeekPicker(selected: Set<String>, onChange: (Set<String>) -> Unit) {
    Column {
        // ช่องห่างกัน 8dp และกว้างอย่างน้อย 44dp: ถ้า 7 ช่องในแถวเดียวไม่พอ แบ่งเป็น 4 + 3
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val perRow = if ((maxWidth - PickerGap * 6) / 7 >= 44.dp) 7 else 4
            Column(verticalArrangement = Arrangement.spacedBy(PickerGap)) {
                Recurrence.dayCodes.chunked(perRow).forEach { rowCodes ->
                    Row(horizontalArrangement = Arrangement.spacedBy(PickerGap)) {
                        rowCodes.forEach { code ->
                            ToggleCell(
                                label = Recurrence.dayLabelsTh.getValue(code).trimEnd('.'),
                                on = code in selected,
                                modifier = Modifier.weight(1f)
                            ) {
                                val next = if (code in selected) selected - code else selected + code
                                if (next.isNotEmpty()) onChange(next)
                            }
                        }
                        repeat(perRow - rowCodes.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        QuickPicks(
            listOf(
                "จันทร์–ศุกร์" to { onChange(Recurrence.weekdays) },
                "ทุกวัน" to { onChange(Recurrence.allDays) },
                "เสาร์–อาทิตย์" to { onChange(Recurrence.weekend) }
            )
        )
    }
}

@Composable
fun MonthPicker(selected: Set<Int>, onChange: (Set<Int>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PickerGap)) {
        // 6 ช่องต่อแถวเมื่อกว้างพอ (≥ 48dp ต่อช่อง) ไม่งั้น 4 ช่องต่อแถว
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val perRow = if ((maxWidth - PickerGap * 5) / 6 >= 48.dp) 6 else 4
            Column(verticalArrangement = Arrangement.spacedBy(PickerGap)) {
                (1..12).chunked(perRow).forEach { rowMonths ->
                    Row(horizontalArrangement = Arrangement.spacedBy(PickerGap)) {
                        rowMonths.forEach { month ->
                            ToggleCell(
                                label = Recurrence.monthLabelsTh[month - 1],
                                on = month in selected,
                                modifier = Modifier.weight(1f)
                            ) {
                                val next = if (month in selected) selected - month else selected + month
                                if (next.isNotEmpty()) onChange(next)
                            }
                        }
                    }
                }
            }
        }
        QuickPicks(
            listOf(
                "ทุกเดือน" to { onChange(Recurrence.allMonths) },
                "ม.ค.–มิ.ย." to { onChange((1..6).toSet()) },
                "ก.ค.–ธ.ค." to { onChange((7..12).toSet()) }
            )
        )
    }
}

// ============================================================================
// Day strip (horizontal overview of one day) + legend
// ============================================================================

@Composable
fun DayStrip(blocks: List<TimeBlock>, modifier: Modifier = Modifier, fromMin: Int = 6 * 60, toMin: Int = 18 * 60) {
    val span = (toMin - fromMin).toFloat()
    Column(modifier) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            blocks.forEach { b ->
                val s = b.startMin.coerceIn(fromMin, toMin)
                val e = b.endMin.coerceIn(fromMin, toMin)
                if (e > s) {
                    Box(
                        Modifier
                            .offset(x = maxWidth * ((s - fromMin) / span))
                            .width(maxWidth * ((e - s) / span))
                            .fillMaxHeight()
                            .background(taskAccent(b.kind, b.color, b.categoryId))
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Hint(fmt(fromMin))
            Spacer(Modifier.weight(1f))
            Hint(fmt((fromMin + toMin) / 2))
            Spacer(Modifier.weight(1f))
            Hint(fmt(toMin))
        }
    }
}

@Composable
fun KindTotals(blocks: List<TimeBlock>, modifier: Modifier = Modifier) {
    val work = blocks.filter { it.kind == TaskKind.WORK }.sumOf { it.endMin - it.startMin }
    val rest = blocks.filter { it.kind == TaskKind.BREAK }.sumOf { it.endMin - it.startMin }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        LegendItem(TaskKind.WORK.color(), "ทำงาน ${durationText(work)}")
        LegendItem(TaskKind.BREAK.color(), "พัก ${durationText(rest)}")
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted)
    }
}

// ============================================================================
// List row (routine / batch)
// ============================================================================

@Composable
fun TimeRow(
    title: String,
    kind: TaskKind,
    startMin: Int,
    endMin: Int,
    reminder: Boolean,
    showDivider: Boolean,
    subtitle: String? = null,
    /** สี/ไอคอน/หมวดที่ผู้ใช้เลือก null = ตามประเภท */
    color: String? = null,
    icon: String? = null,
    categoryId: Long? = null,
    /** null = แถวนี้กดเองไม่ได้ (ให้ตัวที่ครอบอยู่จัดการ เช่นโหมดเลือกหลายรายการ) */
    onClick: (() -> Unit)?
) {
    Column {
        if (showDivider) HorizontalDivider(color = AppTheme.colors.line)
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .height(IntrinsicSize.Min)
                .padding(vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val accent = taskAccent(kind, color, categoryId)
            Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(accent))
            Spacer(Modifier.width(14.dp))
            Text(rangeText(startMin, endMin), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(110.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(taskIcon(kind, icon, categoryId), contentDescription = taskLabel(kind, categoryId), tint = accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Hint(subtitle ?: durationText(endMin - startMin))
                }
            }
            if (!reminder) {
                Icon(
                    Icons.Filled.NotificationsOff, contentDescription = "ไม่แจ้งเตือน",
                    modifier = Modifier.size(16.dp), tint = AppTheme.colors.muted
                )
            }
        }
    }
}

/** ปุ่มเพิ่มแต่ละประเภทใต้รายการ (2 แถว แถวละ 2 ปุ่ม) */
@Composable
fun AddKindButtons(onAdd: (TaskKind) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TaskKind.values().toList().chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { k ->
                    Row(
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(k.bg())
                            .clickable { onAdd(k) },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = k.color(), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(k.label, style = MaterialTheme.typography.labelLarge, color = AppTheme.colors.ink)
                    }
                }
            }
        }
    }
}

// ============================================================================
// Time / date pickers
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(title: String, initialMinutes: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(
        initialHour = (initialMinutes / 60) % 24,
        initialMinute = initialMinutes % 60,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppTheme.colors.surface,
        title = { Text(title) },
        text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = state) } },
        confirmButton = { TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("ตกลง") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    )
}

/** ปุ่มแสดงเวลา กดแล้วเปิดตัวเลือกเวลา */
@Composable
fun TimeButton(label: String, minutes: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, AppTheme.colors.outline, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = "เปลี่ยนเวลา") { show = true }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Hint(label)
        Text(fmt(minutes), style = MaterialTheme.typography.titleLarge)
    }
    if (show) {
        TimePickerDialog(label, minutes, onConfirm = { onPick(it); show = false }, onDismiss = { show = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerDialogHost(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
            }) { Text("ตกลง") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    ) { DatePicker(state = state) }
}

@Composable
fun DateButton(label: String, date: LocalDate, modifier: Modifier = Modifier, onPick: (LocalDate) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(AppTheme.colors.surface)
            .border(1.dp, AppTheme.colors.outline, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = "เปลี่ยนวันที่") { show = true }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Hint(label)
        Text(thaiShortDate(date), style = MaterialTheme.typography.titleSmall)
    }
    if (show) DatePickerDialogHost(date, onPick = { onPick(it); show = false }, onDismiss = { show = false })
}

// ============================================================================
// Task editor dialog
// ============================================================================

@Composable
fun TaskEditorDialog(
    title: String,
    initial: TaskDraft?,
    footnote: String? = null,
    onSave: (TaskDraft) -> Unit,
    onDelete: (() -> Unit)? = null,
    done: Boolean? = null,
    onDoneChange: ((Boolean) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var draft by remember { mutableStateOf(initial ?: TaskDraft()) }
    val timeOk = draft.endMin > draft.startMin && draft.endMin <= 24 * 60
    val valid = draft.title.isNotBlank() && timeOk
    val kinds = TaskKind.values().toList()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppTheme.colors.surface,
        title = { Text(title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                CategoryChips(kind = draft.kind, categoryId = draft.categoryId) { k, cat ->
                    draft = draft.copy(kind = k, categoryId = cat)
                }
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { draft = draft.copy(title = it) },
                    label = { Text("ชื่อ") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeButton("เริ่ม", draft.startMin, Modifier.weight(1f)) { s ->
                        val keep = (draft.endMin - draft.startMin).takeIf { it > 0 } ?: 60
                        draft = draft.copy(startMin = s, endMin = minOf(s + keep, 24 * 60))
                    }
                    TimeButton("สิ้นสุด", draft.endMin, Modifier.weight(1f)) { e -> draft = draft.copy(endMin = e) }
                }
                Text("สี", style = MaterialTheme.typography.titleSmall)
                ColorPicker(kind = draft.kind, selected = draft.color, categoryId = draft.categoryId) { draft = draft.copy(color = it) }
                Text("ไอคอน", style = MaterialTheme.typography.titleSmall)
                IconPicker(
                    kind = draft.kind, categoryId = draft.categoryId, selected = draft.icon,
                    accent = taskAccent(draft.kind, draft.color, draft.categoryId)
                ) { draft = draft.copy(icon = it) }
                if (timeOk) {
                    Hint("ยาว ${durationText(draft.endMin - draft.startMin)}")
                } else {
                    Text("เวลาสิ้นสุดต้องอยู่หลังเวลาเริ่ม", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (done != null && onDoneChange != null) {
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = done, role = Role.Checkbox, onValueChange = onDoneChange).heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("ทำเสร็จแล้ว", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Checkbox(checked = done, onCheckedChange = null)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().toggleable(value = draft.reminder, role = Role.Switch, onValueChange = { draft = draft.copy(reminder = it) }).heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("แจ้งเตือนตอนเริ่ม", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = draft.reminder, onCheckedChange = null)
                }
                draft.repeatDays?.let { days ->
                    Text("ทำซ้ำวัน", style = MaterialTheme.typography.titleSmall)
                    DayOfWeekPicker(days) { draft = draft.copy(repeatDays = it) }
                }
                draft.repeatMonths?.let { months ->
                    Text("ทำซ้ำเดือน", style = MaterialTheme.typography.titleSmall)
                    MonthPicker(months) { draft = draft.copy(repeatMonths = it) }
                }
                if (footnote != null) Hint(footnote)
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(draft) }) { Text("บันทึก") } },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("ลบ", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("ยกเลิก") }
            }
        }
    )
}

// ============================================================================
// Color picker
// ============================================================================

/**
 * เลือกสีกิจกรรม: วงแรก = สีตามประเภท แล้วตามด้วยสีใน TaskPalette
 * วงละ 40dp ในพื้นที่กด 48dp ตัวที่เลือกมีเครื่องหมายถูก (ไม่พึ่งสีอย่างเดียว)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPicker(kind: TaskKind, selected: String?, categoryId: Long? = null, onSelect: (String?) -> Unit) {
    val c = AppTheme.colors
    val dark = c.dark
    val baseAccent = taskAccent(kind, null, categoryId)
    val baseFill = taskFill(kind, null, categoryId)
    val baseIcon = taskIcon(kind, null, categoryId)
    val items: List<Triple<String?, String, Color>> =
        listOf(Triple(null, "ตาม${taskLabel(kind, categoryId)}", baseAccent)) +
            TaskPalette.swatches.map { Triple(it.key, it.label, if (dark) it.dark.first else it.light.first) }
    FlowRow(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { (key, label, color) ->
            val isSel = key == selected
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .selectable(selected = isSel, role = Role.RadioButton, onClick = { onSelect(key) })
                    .semantics { contentDescription = "สี$label" },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (key == null) baseFill else color)
                        .border(if (isSel) 3.dp else 0.dp, if (isSel) c.ink else Color.Transparent, CircleShape)
                        .then(if (key == null) Modifier.border(2.dp, color, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isSel -> Icon(
                            Icons.Filled.Check, contentDescription = null,
                            tint = if (key == null) color else c.surface, modifier = Modifier.size(20.dp)
                        )
                        key == null -> Icon(baseIcon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
    Hint("หรือลากเลือกสีเองจากแถบ แอปปรับความเข้มให้อ่านง่าย")
    HueBar(
        hue = TaskPalette.hueOf(selected),
        dark = dark,
        onChange = { onSelect(TaskPalette.hueKey(it)) }
    )
}

/**
 * แถบสีรุ้ง แตะหรือลากเพื่อเลือกสี (0–359 องศา)
 * สูง 48dp ให้กดง่าย ตัวเลื่อนแสดงสีจริงที่จะใช้ โปรแกรมอ่านหน้าจอปรับค่าได้
 */
@Composable
private fun HueBar(hue: Int?, dark: Boolean, onChange: (Int) -> Unit) {
    val c = AppTheme.colors
    var widthPx by remember { mutableStateOf(1) }
    val density = LocalDensity.current
    val thumb = 32.dp
    val thumbPx = with(density) { thumb.toPx() }
    val rainbow = remember { Brush.horizontalGradient((0..12).map { TaskPalette.pure(it * 30f) }) }
    val latest by rememberUpdatedState(onChange)

    fun hueAt(x: Float): Int {
        val usable = (widthPx - thumbPx).coerceAtLeast(1f)
        return (((x - thumbPx / 2) / usable).coerceIn(0f, 1f) * 359).roundToInt()
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    latest(hueAt(down.position.x))
                    horizontalDrag(down.id) { change ->
                        latest(hueAt(change.position.x))
                        change.consume()
                    }
                }
            }
            .semantics {
                contentDescription = "แถบเลือกสี"
                stateDescription = hue?.let { "เลือกไว้ $it องศา" } ?: "ยังไม่ได้เลือกจากแถบ"
                progressBarRangeInfo = ProgressBarRangeInfo((hue ?: 0).toFloat(), 0f..359f)
                setProgress { v -> latest(v.roundToInt().coerceIn(0, 359)); true }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .padding(horizontal = thumb / 2)
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(rainbow)
        )
        if (hue != null) {
            val usable = (widthPx - thumbPx).coerceAtLeast(0f)
            val x = with(density) { (usable * hue / 359f).toDp() }
            val chosen = TaskPalette.resolve(TaskPalette.hueKey(hue), dark)?.first ?: c.ink
            Box(
                Modifier
                    .offset(x = x)
                    .size(thumb)
                    .clip(CircleShape)
                    .background(c.surface)
                    .border(2.dp, c.ink, CircleShape)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(chosen)
            )
        }
    }
}

// ============================================================================
// Delete scope dialog (รายการที่ซ้ำหลายวัน)
// ============================================================================

/** หนึ่งตัวเลือกในกล่องลบ: ขอบเขต, หัวข้อ, คำอธิบาย */
data class DeleteOption(val scope: DeleteScope, val label: String, val detail: String)

/**
 * ถามก่อนลบรายการที่ซ้ำหลายวัน ว่าจะลบแค่วันนี้หรือทั้งชุด
 * ค่าเริ่มต้นคือตัวเลือกแรก (ลบน้อยที่สุด) ปุ่มยืนยันบอกชัดว่าจะลบอะไร
 */
@Composable
fun DeleteScopeDialog(
    itemTitle: String,
    options: List<DeleteOption>,
    onConfirm: (DeleteScope) -> Unit,
    onDismiss: () -> Unit
) {
    val c = AppTheme.colors
    var chosen by remember { mutableStateOf(options.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("ลบ \"$itemTitle\"") },
        text = {
            Column(Modifier.selectableGroup()) {
                Text("รายการนี้ซ้ำหลายวัน จะลบแค่ไหน", style = MaterialTheme.typography.bodyMedium, color = c.muted)
                Spacer(Modifier.height(8.dp))
                options.forEach { o ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(selected = o == chosen, role = Role.RadioButton, onClick = { chosen = o })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = o == chosen, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(o.label, style = MaterialTheme.typography.bodyLarge)
                            Hint(o.detail)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(chosen.scope) }) {
                Text(chosen.label.let { "ลบ$it" }, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    )
}

/** ถามยืนยันก่อนลบ (ไม่มีตัวเลือกขอบเขต) */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppTheme.colors.surface,
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    )
}

// ============================================================================
// Empty state
// ============================================================================

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) { Icon(icon, contentDescription = null, modifier = Modifier.size(30.dp), tint = AppTheme.colors.muted) }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.muted, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
