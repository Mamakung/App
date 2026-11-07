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
    val categoryId: Long? = null,
    /** ปลุกเต็มจอแทนแจ้งเตือน (ใช้คู่กับ reminder: ไม่เตือน = ทั้งสองเป็น false) */
    val alarm: Boolean = false,
    /** เสียงปลุกเฉพาะรายการนี้ null = ตามตั้งค่า */
    val alarmSound: String? = null
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
    TaskDraft(title, TaskKind.from(kind), startMin, endMin, reminderEnabled, color = color, icon = icon, categoryId = categoryId, alarm = alarm, alarmSound = alarmSound)
fun RoutineTemplateTaskEntity.toDraft() =
    TaskDraft(title, TaskKind.from(kind), startMin, endMin, reminderEnabled, color = color, icon = icon, categoryId = categoryId, alarm = alarm, alarmSound = alarmSound)

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
        categoryId = categoryId,
        alarm = alarm,
        alarmSound = alarmSound
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
        alarm = alarm,
        alarmSound = alarmSound,
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
    onClick: (() -> Unit)?,
    /** ปุ่มท้ายแถว เช่น ถังขยะ */
    trailing: (@Composable () -> Unit)? = null,
    /** ตั้งปลุกไว้: โชว์ไอคอนนาฬิกาสีขาว */
    alarm: Boolean = false
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
                    if (alarm) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Alarm, contentDescription = "ตั้งปลุก", tint = AppTheme.colors.ink, modifier = Modifier.size(14.dp))
                    }
                }
            }
            if (!reminder && !alarm) {
                Icon(
                    Icons.Filled.NotificationsOff, contentDescription = "ไม่แจ้งเตือน",
                    modifier = Modifier.size(16.dp), tint = AppTheme.colors.muted
                )
            }
            trailing?.invoke()
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
    /** เนื้อหาเพิ่มบนสุด เช่น แถวช่วงวันที่ตอนลากเลือกหลายวัน */
    topContent: (@Composable () -> Unit)? = null,
    saveLabel: String = "บันทึก",
    onDismiss: () -> Unit
) {
    var draft by remember { mutableStateOf(initial ?: TaskDraft()) }
    // แตะหมวดแล้วเติมให้: ชื่อ (ถ้ายังว่างหรือเป็นชื่อที่เติมให้ก่อนหน้า) + เวลาที่ใช้ล่าสุด (เฉพาะรายการใหม่)
    val context = androidx.compose.ui.platform.LocalContext.current
    val quick = remember { com.example.smartscheduler.settings.QuickStore(context) }
    val categoryMap = LocalCategories.current
    val isNew = onDelete == null
    var autoTitle by remember { mutableStateOf<String?>(null) }
    val flash = remember { androidx.compose.animation.core.Animatable(0f) }
    var flashKey by remember { mutableStateOf(0) }
    val look = com.example.smartscheduler.settings.LocalAppearance.current
    LaunchedEffect(flashKey) {
        if (flashKey > 0 && look.animate) {
            flash.snapTo(0.28f)
            flash.animateTo(0f, tween(550))
        }
    }
    val flashColor = taskAccent(draft.kind, draft.color, draft.categoryId).copy(alpha = flash.value)
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
                topContent?.invoke()
                CategoryChips(kind = draft.kind, categoryId = draft.categoryId) { k, cat ->
                    var d = draft.copy(kind = k, categoryId = cat)
                    var filled = false
                    val name = cat?.let { categoryMap[it]?.name }
                    if (name != null && (draft.title.isBlank() || draft.title == autoTitle)) {
                        d = d.copy(title = name); autoTitle = name; filled = true
                    }
                    if (isNew) {
                        quick.lastTimes(com.example.smartscheduler.settings.QuickStore.keyFor(k.code, cat))?.let { (st, en) ->
                            if (st != d.startMin || en != d.endMin) { d = d.copy(startMin = st, endMin = en); filled = true }
                        }
                    }
                    draft = d
                    if (filled) flashKey++
                }
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { draft = draft.copy(title = it) },
                    label = { Text("ชื่อ") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().background(flashColor, RoundedCornerShape(4.dp))
                )
                Row(Modifier.fillMaxWidth().background(flashColor, RoundedCornerShape(16.dp)), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                // เตือนตอนเริ่ม: ไม่เตือน / แจ้งเตือน / ปลุก
                Text("เตือน", style = MaterialTheme.typography.titleSmall)
                AlertModeSelector(
                    mode = when { draft.alarm -> 2; draft.reminder -> 1; else -> 0 },
                    onSelect = { m -> draft = draft.copy(reminder = m >= 1, alarm = m == 2) }
                )
                if (draft.alarm) {
                    var pickSound by remember { mutableStateOf(false) }
                    val lib = remember { com.example.smartscheduler.settings.SoundLibrary(context) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Alarm, contentDescription = null, tint = AppTheme.colors.ink, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Hint(
                            "ปลุกเต็มจอ ${fmt(draft.startMin)} · เสียง" + (draft.alarmSound?.let { lib.labelOf(it) } ?: "ตามตั้งค่า"),
                            Modifier.weight(1f)
                        )
                        TextButton(onClick = { pickSound = true }) { Text("เปลี่ยน") }
                    }
                    if (pickSound) {
                        SoundPickerDialog(
                            alarm = true, selected = draft.alarmSound, allowDefault = true,
                            onSelect = { draft = draft.copy(alarmSound = it); pickSound = false },
                            onDismiss = { pickSound = false }
                        )
                    }
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
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                // จำเวลาและนับการใช้ของหมวดนี้ ไว้เติมให้ครั้งหน้า และเรียงหมวดที่ใช้บ่อยก่อน
                quick.record(com.example.smartscheduler.settings.QuickStore.keyFor(draft.kind.code, draft.categoryId), draft.startMin, draft.endMin)
                onSave(draft)
            }) { Text(saveLabel) }
        },
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
// Alert mode (ไม่เตือน / แจ้งเตือน / ปลุก)
// ============================================================================

/** 3 ช่องมีไอคอน: 0 = ไม่เตือน, 1 = แจ้งเตือน, 2 = ปลุก */
@Composable
fun AlertModeSelector(mode: Int, onSelect: (Int) -> Unit) {
    val c = AppTheme.colors
    val items = listOf(
        Triple(Icons.Filled.NotificationsOff, "ไม่เตือน", 0),
        Triple(Icons.Filled.Notifications, "แจ้งเตือน", 1),
        Triple(Icons.Filled.Alarm, "ปลุก", 2)
    )
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.ground.copy(alpha = 0.6f)).padding(3.dp).selectableGroup()
    ) {
        items.forEach { (icon, label, m) ->
            val on = m == mode
            val bg by animateColorAsState(if (on) c.ink else Color.Transparent, tween(150), label = "alertBg")
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(bg)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(m) })
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(icon, contentDescription = null, tint = if (on) c.ground else c.muted, modifier = Modifier.size(20.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) c.ground else c.muted)
            }
        }
    }
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
    val baseIcon = taskIcon(kind, null, categoryId)
    val base = TaskPalette.baseOf(selected)
    val strength = TaskPalette.strengthOf(selected)
    // วงสีแสดงตามความเข้มที่ตั้งอยู่ จะได้เห็นสีจริงก่อนเลือก
    val items: List<Triple<String?, String, Color>> =
        listOf(Triple(null, "ตาม${taskLabel(kind, categoryId)}", baseAccent)) +
            TaskPalette.swatches.map {
                Triple(it.key, it.label, TaskPalette.resolve(TaskPalette.withStrength(it.key, strength), dark)!!.first)
            }
    FlowRow(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { (key, label, color) ->
            val isSel = key == base
            // ตัวที่เลือก: มีกรอบรอบนอก (ไม่เปลี่ยนสีพื้น) + เครื่องหมายถูกบนวงสี
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (isSel) c.ink else Color.Transparent, CircleShape)
                    .selectable(
                        selected = isSel, role = Role.RadioButton,
                        onClick = { onSelect(TaskPalette.withStrength(key, strength)) }
                    )
                    .semantics { contentDescription = "สี$label" },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .then(
                            if (key == null) Modifier.border(2.dp, color, CircleShape)
                            else Modifier.background(color)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        key == null -> Icon(baseIcon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                        isSel -> Icon(Icons.Filled.Check, contentDescription = null, tint = c.surface, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
    Hint("หรือลากเลือกสีเองจากแถบ")
    HueBar(
        hue = TaskPalette.hueOf(selected),
        strength = strength,
        dark = dark,
        onChange = { onSelect(TaskPalette.withStrength(TaskPalette.hueKey(it), strength)) }
    )
    StrengthSlider(
        base = base,
        strength = strength,
        dark = dark,
        onChange = { onSelect(TaskPalette.withStrength(base, it)) }
    )
}

/**
 * แถบเลื่อนความเข้ม: ไล่สีที่เลือกจากจางสุด (ซ้าย) ไปเข้มสุด (ขวา) ลากได้ต่อเนื่อง 0–100 (50 = ปกติ)
 * หน้าตาเหมือนแถบสีรุ้ง ตัวเลื่อนแสดงสีที่ได้จริง
 */
@Composable
internal fun StrengthSlider(base: String?, strength: Int, dark: Boolean, onChange: (Int) -> Unit) {
    val c = AppTheme.colors
    val enabled = base != null
    val word = when {
        strength <= 20 -> "จางมาก"
        strength < 45 -> "จาง"
        strength <= 55 -> "ปกติ"
        strength < 80 -> "เข้ม"
        else -> "เข้มมาก"
    }
    var widthPx by remember { mutableStateOf(1) }
    val density = LocalDensity.current
    val thumb = 32.dp
    val thumbPx = with(density) { thumb.toPx() }
    val latest by rememberUpdatedState(onChange)
    val ramp = remember(base, dark) {
        if (base == null) null
        else Brush.horizontalGradient((0..10).map { TaskPalette.resolve(TaskPalette.withStrength(base, it * 10), dark)!!.first })
    }

    fun valueAt(x: Float): Int {
        val usable = (widthPx - thumbPx).coerceAtLeast(1f)
        return (((x - thumbPx / 2) / usable).coerceIn(0f, 1f) * 100).roundToInt()
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ความเข้มของสี", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (enabled) Text(word, style = MaterialTheme.typography.bodyMedium, color = c.muted)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .onSizeChanged { widthPx = it.width }
                .then(
                    if (!enabled) Modifier else Modifier
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                latest(valueAt(down.position.x))
                                horizontalDrag(down.id) { change ->
                                    latest(valueAt(change.position.x))
                                    change.consume()
                                }
                            }
                        }
                        .semantics {
                            contentDescription = "แถบความเข้มของสี"
                            stateDescription = word
                            progressBarRangeInfo = ProgressBarRangeInfo(strength.toFloat(), 0f..100f)
                            setProgress { v -> latest(v.roundToInt().coerceIn(0, 100)); true }
                        }
                ),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .padding(horizontal = thumb / 2)
                    .fillMaxWidth()
                    .height(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .then(if (ramp != null) Modifier.background(ramp) else Modifier.background(c.line))
            )
            if (enabled) {
                val usable = (widthPx - thumbPx).coerceAtLeast(0f)
                val x = with(density) { (usable * strength / 100f).toDp() }
                val chosen = TaskPalette.resolve(TaskPalette.withStrength(base, strength), dark)?.first ?: c.ink
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
        if (!enabled) Hint("เลือกสีด้านบนก่อน แล้วเลื่อนให้เข้มหรือจางได้")
    }
}

/**
 * แถบสีรุ้ง แตะหรือลากเพื่อเลือกสี (0–359 องศา)
 * สูง 48dp ให้กดง่าย ตัวเลื่อนแสดงสีจริงที่จะใช้ โปรแกรมอ่านหน้าจอปรับค่าได้
 */
@Composable
internal fun HueBar(hue: Int?, strength: Int, dark: Boolean, onChange: (Int) -> Unit) {
    val c = AppTheme.colors
    var widthPx by remember { mutableStateOf(1) }
    val density = LocalDensity.current
    val thumb = 32.dp
    val thumbPx = with(density) { thumb.toPx() }
    // วาดแถบด้วยสีที่จะได้จริง (หลังปรับความเข้ม) จุดไหนบนแถบ = สีที่ได้ตรงนั้น
    val rainbow = remember(dark, strength) {
        Brush.horizontalGradient((0..36).map {
            TaskPalette.resolve(TaskPalette.withStrength(TaskPalette.hueKey(it * 10), strength), dark)!!.first
        })
    }
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
            val chosen = TaskPalette.resolve(TaskPalette.withStrength(TaskPalette.hueKey(hue), strength), dark)?.first ?: c.ink
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
