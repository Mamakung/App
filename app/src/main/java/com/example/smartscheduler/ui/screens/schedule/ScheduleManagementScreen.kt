package com.example.smartscheduler.ui.screens.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartscheduler.data.local.entity.RoutineTemplateEntity
import com.example.smartscheduler.data.local.entity.RoutineTemplateTaskEntity
import com.example.smartscheduler.data.repository.ScheduleRepository
import com.example.smartscheduler.data.repository.effectiveDays
import com.example.smartscheduler.data.repository.effectiveMonths
import com.example.smartscheduler.data.session.CurrentUser
import com.example.smartscheduler.domain.model.Recurrence
import com.example.smartscheduler.domain.model.TaskKind
import com.example.smartscheduler.ui.components.*
import com.example.smartscheduler.ui.theme.AppTheme
import com.example.smartscheduler.update.AppVersionRow
import com.example.smartscheduler.update.rememberAppUpdateViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ============================================================================
// ViewModel
// ============================================================================

data class RoutineUiState(
    val template: RoutineTemplateEntity? = null,
    val tasks: List<RoutineTemplateTaskEntity> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RoutineViewModel @Inject constructor(
    private val repo: ScheduleRepository,
    private val auth: CurrentUser
) : ViewModel() {

    private val uid get() = auth.uid

    val state: StateFlow<RoutineUiState> = repo.observeActiveTemplate(uid)
        .flatMapLatest { template ->
            if (template == null) flowOf(RoutineUiState())
            else repo.observeTemplateTasks(template.templateId).map { RoutineUiState(template, it) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RoutineUiState())

    init {
        viewModelScope.launch { repo.ensureDefaultTemplate(uid) }
    }

    fun setWake(minutes: Int) {
        val t = state.value.template ?: return
        viewModelScope.launch {
            repo.updateTemplateMeta(uid, t, minutes, t.appliesToDaysOfWeek, t.appliesToMonths)
        }
    }

    fun saveTask(existing: RoutineTemplateTaskEntity?, draft: TaskDraft) {
        val t = state.value.template ?: return
        viewModelScope.launch {
            repo.upsertTemplateTask(uid, t.templateId, draft.toTemplateTask(existing, t.templateId))
        }
    }

    fun deleteTask(task: RoutineTemplateTaskEntity) {
        val t = state.value.template ?: return
        viewModelScope.launch { repo.deleteTemplateTask(uid, t.templateId, task) }
    }

    /** ตั้งวันทำซ้ำเดียวกันให้ทุกรายการที่เลือก */
    fun setRepeat(ids: Set<Long>, days: Set<String>, months: Set<Int>) {
        val t = state.value.template ?: return
        viewModelScope.launch {
            repo.setRepeatForTasks(uid, t.templateId, ids.toList(), Recurrence.daysToCsv(days), Recurrence.monthsToCsv(months))
        }
    }
}

// ============================================================================
// Screen
// ============================================================================

private data class RoutineEdit(val task: RoutineTemplateTaskEntity?, val kind: TaskKind)

@Composable
fun RoutineScreen(
    onOpenBatchAssign: () -> Unit,
    onOpenImport: () -> Unit,
    viewModel: RoutineViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var editing by remember { mutableStateOf<RoutineEdit?>(null) }
    var pickWake by remember { mutableStateOf(false) }
    /** null = ไม่ได้อยู่ในโหมดเลือก, ไม่ null = id ที่เลือกไว้ */
    var selection by remember { mutableStateOf<Set<Long>?>(null) }
    var askRepeat by remember { mutableStateOf(false) }
    val template = state.template
    val c = AppTheme.colors

    // ปุ่มย้อนกลับของระบบ: ออกจากโหมดเลือกก่อน ไม่ออกจากหน้า
    BackHandler(enabled = selection != null) { selection = null }

    // รายการที่ถูกลบไปแล้วไม่ต้องค้างอยู่ในที่เลือก
    LaunchedEffect(state.tasks) {
        selection = selection?.intersect(state.tasks.map { it.templateTaskId }.toSet())
    }

    Box(Modifier.fillMaxSize().background(c.ground)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = if (selection != null) 120.dp else 32.dp)
        ) {
            item {
                Text("กิจวัตรมาตรฐาน", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "แต่ละรายการตั้งวันทำซ้ำของตัวเองได้ แก้ที่นี่แล้ววันข้างหน้าที่ยังไม่ได้ปรับเองจะเปลี่ยนตาม",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.muted
                )
                Spacer(Modifier.height(16.dp))
            }

            if (template == null) {
                item { Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator(color = c.ink) } }
                return@LazyColumn
            }

            val wake = template.wakeUpHour * 60 + template.wakeUpMinute
            val blocks = state.tasks.map { TimeBlock(it.startMin, it.endMin, TaskKind.from(it.kind)) }

            item {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Hint("ตื่นนอน")
                            Text(fmt(wake), style = MaterialTheme.typography.headlineMedium)
                        }
                        OutlinedButton(onClick = { pickWake = true }, shape = MaterialTheme.shapes.small) { Text("เปลี่ยนเวลา") }
                    }
                    Spacer(Modifier.height(12.dp))
                    val from = minOf(wake, blocks.minOfOrNull { it.startMin } ?: wake) / 60 * 60
                    val to = maxOf(from + 12 * 60, ((blocks.maxOfOrNull { it.endMin } ?: 0) + 59) / 60 * 60).coerceAtMost(24 * 60)
                    DayStrip(blocks, fromMin = from, toMin = to)
                    Spacer(Modifier.height(10.dp))
                    KindTotals(blocks)
                }
            }

            item {
                val sel = selection
                SectionTitle("รายการกิจวัตร", Modifier.padding(top = 12.dp)) {
                    if (state.tasks.isNotEmpty()) {
                        if (sel == null) {
                            TextButton(onClick = { selection = emptySet() }) { Text("เลือกหลายรายการ") }
                        } else {
                            TextButton(onClick = {
                                selection = if (sel.size == state.tasks.size) emptySet() else state.tasks.map { it.templateTaskId }.toSet()
                            }) { Text(if (sel.size == state.tasks.size) "ไม่เลือกเลย" else "เลือกทั้งหมด") }
                        }
                    }
                }
                if (sel != null) {
                    Hint("แตะรายการที่อยากให้ทำซ้ำวันเดียวกัน แล้วกด \"ตั้งวันทำซ้ำ\" ด้านล่าง")
                    Spacer(Modifier.height(8.dp))
                }
                Panel(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    if (state.tasks.isEmpty()) {
                        EmptyState(
                            icon = Icons.Filled.Schedule,
                            title = "ยังไม่มีกิจวัตร",
                            subtitle = "เพิ่มช่วงทำงาน เวลาพัก หรือกิจกรรมด้วยปุ่มด้านล่าง"
                        )
                    }
                    state.tasks.forEachIndexed { i, t ->
                        val repeat = Recurrence.summary(
                            Recurrence.parseDays(t.effectiveDays(template)),
                            Recurrence.parseMonths(t.effectiveMonths(template))
                        )
                        if (sel == null) {
                            TimeRow(
                                title = t.title,
                                kind = TaskKind.from(t.kind),
                                startMin = t.startMin,
                                endMin = t.endMin,
                                reminder = t.reminderEnabled,
                                showDivider = i > 0,
                                subtitle = "ทำซ้ำ $repeat",
                                onClick = { editing = RoutineEdit(t, TaskKind.from(t.kind)) }
                            )
                        } else {
                            val checked = t.templateTaskId in sel
                            val toggle = {
                                selection = if (checked) sel - t.templateTaskId else sel + t.templateTaskId
                            }
                            if (i > 0) HorizontalDivider(color = c.line)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .toggleable(value = checked, role = Role.Checkbox, onValueChange = { toggle() }),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.weight(1f)) {
                                    TimeRow(
                                        title = t.title,
                                        kind = TaskKind.from(t.kind),
                                        startMin = t.startMin,
                                        endMin = t.endMin,
                                        reminder = t.reminderEnabled,
                                        showDivider = false,
                                        subtitle = "ทำซ้ำ $repeat",
                                        onClick = null
                                    )
                                }
                            }
                        }
                    }
                }
                if (sel == null) AddKindButtons { kind -> editing = RoutineEdit(null, kind) }
            }

            if (selection == null) {
                item {
                    Spacer(Modifier.height(20.dp))
                    TextButton(onClick = onOpenBatchAssign) {
                        Icon(Icons.Filled.EventRepeat, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("เพิ่มกิจกรรมพิเศษให้หลายวัน")
                    }
                    TextButton(onClick = onOpenImport) {
                        Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("นำเข้าตารางเรียนหรือตารางงานจากไฟล์")
                    }
                }

                item {
                    SectionTitle("เกี่ยวกับแอป", Modifier.padding(top = 12.dp))
                    Panel { AppVersionRow(rememberAppUpdateViewModel()) }
                }
            }
        }

        // แถบล่างของโหมดเลือกหลายรายการ
        selection?.let { sel ->
            Surface(color = c.surface, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                    HorizontalDivider(color = c.line, modifier = Modifier.padding(bottom = 12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { selection = null }) { Text("ยกเลิก") }
                        Spacer(Modifier.width(8.dp))
                        InkButton(
                            text = if (sel.isEmpty()) "เลือกอย่างน้อย 1 รายการ" else "ตั้งวันทำซ้ำ ${sel.size} รายการ",
                            onClick = { askRepeat = true },
                            enabled = sel.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    if (pickWake && template != null) {
        TimePickerDialog(
            title = "เวลาตื่นนอน",
            initialMinutes = template.wakeUpHour * 60 + template.wakeUpMinute,
            onConfirm = { viewModel.setWake(it); pickWake = false },
            onDismiss = { pickWake = false }
        )
    }

    val sel = selection
    if (askRepeat && sel != null && template != null) {
        // เริ่มจากวันทำซ้ำของรายการแรกที่เลือก
        val first = state.tasks.firstOrNull { it.templateTaskId in sel }
        RepeatDialog(
            count = sel.size,
            initialDays = Recurrence.parseDays(first?.effectiveDays(template) ?: template.appliesToDaysOfWeek),
            initialMonths = Recurrence.parseMonths(first?.effectiveMonths(template) ?: template.appliesToMonths),
            onApply = { days, months ->
                viewModel.setRepeat(sel, days, months)
                askRepeat = false
                selection = null
            },
            onDismiss = { askRepeat = false }
        )
    }

    editing?.let { edit ->
        if (template == null) return@let
        val lastEnd = state.tasks.maxOfOrNull { it.endMin } ?: (9 * 60)
        val initial = edit.task?.toDraft(template) ?: defaultDraft(edit.kind, start = minOf(lastEnd, 23 * 60)).copy(
            repeatDays = Recurrence.parseDays(template.appliesToDaysOfWeek),
            repeatMonths = Recurrence.parseMonths(template.appliesToMonths)
        )
        TaskEditorDialog(
            title = if (edit.task == null) "เพิ่มกิจวัตร" else "แก้ไขกิจวัตร",
            initial = initial,
            footnote = "มีผลกับวันข้างหน้าที่ตรงกับวันทำซ้ำ ยกเว้นวันที่ปรับเองแล้ว",
            onSave = { viewModel.saveTask(edit.task, it); editing = null },
            onDelete = edit.task?.let { t -> { viewModel.deleteTask(t); editing = null } },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun RepeatDialog(
    count: Int,
    initialDays: Set<String>,
    initialMonths: Set<Int>,
    onApply: (Set<String>, Set<Int>) -> Unit,
    onDismiss: () -> Unit
) {
    var days by remember { mutableStateOf(initialDays) }
    var months by remember { mutableStateOf(initialMonths) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppTheme.colors.surface,
        title = { Text("ตั้งวันทำซ้ำ $count รายการ") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("ทำซ้ำวัน", style = MaterialTheme.typography.titleSmall)
                DayOfWeekPicker(days) { days = it }
                Text("ทำซ้ำเดือน", style = MaterialTheme.typography.titleSmall)
                MonthPicker(months) { months = it }
                Hint("ทุกรายการที่เลือกจะใช้วันเดียวกันนี้: ${Recurrence.summary(days, months)}")
            }
        },
        confirmButton = { TextButton(onClick = { onApply(days, months) }) { Text("ใช้กับ $count รายการ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    )
}
