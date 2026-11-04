package com.example.smartscheduler.ui.screens.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartscheduler.data.local.entity.RoutineTemplateEntity
import com.example.smartscheduler.data.local.entity.RoutineTemplateTaskEntity
import com.example.smartscheduler.data.repository.ScheduleRepository
import com.example.smartscheduler.domain.model.Recurrence
import com.example.smartscheduler.domain.model.TaskKind
import com.example.smartscheduler.ui.components.*
import com.example.smartscheduler.ui.theme.AppTheme
import com.example.smartscheduler.update.AppVersionRow
import com.example.smartscheduler.update.rememberAppUpdateViewModel
import androidx.compose.foundation.background
import com.example.smartscheduler.data.session.CurrentUser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    private fun updateMeta(wakeMin: Int? = null, days: Set<String>? = null, months: Set<Int>? = null) {
        val t = state.value.template ?: return
        viewModelScope.launch {
            repo.updateTemplateMeta(
                uid = uid,
                template = t,
                wakeUpMinutes = wakeMin ?: (t.wakeUpHour * 60 + t.wakeUpMinute),
                daysCsv = days?.let { Recurrence.daysToCsv(it) } ?: t.appliesToDaysOfWeek,
                monthsCsv = months?.let { Recurrence.monthsToCsv(it) } ?: t.appliesToMonths
            )
        }
    }

    fun setWake(minutes: Int) = updateMeta(wakeMin = minutes)
    fun setDays(days: Set<String>) = updateMeta(days = days)
    fun setMonths(months: Set<Int>) = updateMeta(months = months)

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
}

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
    val template = state.template
    val c = AppTheme.colors

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(c.ground),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp)
    ) {
        item {
            Text("กิจวัตรมาตรฐาน", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "ใช้กับทุกวันที่เลือกไว้ แก้ที่นี่แล้ววันข้างหน้าที่ยังไม่ได้ปรับเองจะเปลี่ยนตาม",
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

        // ภาพรวมทั้งวัน: เวลาตื่น + แถบช่วงเวลา + ชั่วโมงทำงาน/พัก
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
            SectionTitle("ทำซ้ำวันไหน", Modifier.padding(top = 12.dp))
            DayOfWeekPicker(Recurrence.parseDays(template.appliesToDaysOfWeek), viewModel::setDays)
        }

        item {
            val months = Recurrence.parseMonths(template.appliesToMonths)
            SectionTitle("ทำซ้ำเดือนไหน")
            MonthPicker(months, viewModel::setMonths)
            val off = (1..12).filter { it !in months }
            if (off.isNotEmpty()) {
                Hint("ไม่มีตารางในเดือน " + off.joinToString(" ") { Recurrence.monthLabelsTh[it - 1] })
            }
        }

        item {
            SectionTitle("ช่วงเวลาในหนึ่งวัน")
            Panel(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                if (state.tasks.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Schedule,
                        title = "ยังไม่มีช่วงเวลา",
                        subtitle = "เพิ่มช่วงทำงานและเวลาพักด้วยปุ่มด้านล่าง"
                    )
                }
                state.tasks.forEachIndexed { i, t ->
                    TimeRow(
                        title = t.title,
                        kind = TaskKind.from(t.kind),
                        startMin = t.startMin,
                        endMin = t.endMin,
                        reminder = t.reminderEnabled,
                        showDivider = i > 0,
                        onClick = { editing = RoutineEdit(t, TaskKind.from(t.kind)) }
                    )
                }
            }
            AddKindButtons { kind -> editing = RoutineEdit(null, kind) }
        }

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

    if (pickWake && template != null) {
        TimePickerDialog(
            title = "เวลาตื่นนอน",
            initialMinutes = template.wakeUpHour * 60 + template.wakeUpMinute,
            onConfirm = { viewModel.setWake(it); pickWake = false },
            onDismiss = { pickWake = false }
        )
    }

    editing?.let { edit ->
        val lastEnd = state.tasks.maxOfOrNull { it.endMin } ?: (9 * 60)
        TaskEditorDialog(
            title = if (edit.task == null) "เพิ่มช่วงเวลา" else "แก้ไขช่วงเวลา",
            initial = edit.task?.toDraft() ?: defaultDraft(edit.kind, start = minOf(lastEnd, 23 * 60)),
            footnote = "มีผลกับวันข้างหน้าทุกวันที่ตรงกับวันและเดือนที่เลือก ยกเว้นวันที่ปรับเองแล้ว",
            onSave = { viewModel.saveTask(edit.task, it); editing = null },
            onDelete = edit.task?.let { t -> { viewModel.deleteTask(t); editing = null } },
            onDismiss = { editing = null }
        )
    }
}
