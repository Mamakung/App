package com.example.smartscheduler.ui.screens.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartscheduler.data.repository.ScheduleRepository
import com.example.smartscheduler.domain.model.Recurrence
import com.example.smartscheduler.domain.model.TaskKind
import com.example.smartscheduler.ui.components.*
import com.example.smartscheduler.ui.theme.AppTheme
import com.example.smartscheduler.data.session.CurrentUser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

enum class BatchMode { PATTERN, EXPLICIT }

data class BatchUiState(
    val mode: BatchMode = BatchMode.PATTERN,
    val startDate: LocalDate = LocalDate.now(),
    val endDate: LocalDate = LocalDate.now().plusMonths(1),
    val days: Set<String> = Recurrence.weekdays,
    val months: Set<Int> = Recurrence.allMonths,
    val explicitDates: List<LocalDate> = emptyList(),
    val wakeUpMin: Int = 7 * 60,
    val tasks: List<TaskDraft> = emptyList(),
    val submitting: Boolean = false,
    val message: String? = null
)

/** วันที่ทั้งหมดที่จะได้รับกิจกรรม (จำกัดช่วงไม่เกิน 1 ปีเพื่อกันกดพลาด) */
fun BatchUiState.resolvedDates(): List<LocalDate> {
    if (mode == BatchMode.EXPLICIT) return explicitDates
    if (endDate.isBefore(startDate)) return emptyList()
    val limit = startDate.plusDays(366)
    return generateSequence(startDate) { it.plusDays(1) }
        .takeWhile { !it.isAfter(endDate) && !it.isAfter(limit) }
        .filter { Recurrence.dayCode(it.dayOfWeek) in days && it.monthValue in months }
        .toList()
}

@HiltViewModel
class BatchAssignViewModel @Inject constructor(
    private val repo: ScheduleRepository,
    private val auth: CurrentUser
) : ViewModel() {

    private val _state = MutableStateFlow(BatchUiState())
    val state: StateFlow<BatchUiState> = _state.asStateFlow()

    fun setMode(m: BatchMode) = _state.update { it.copy(mode = m) }
    fun setStart(d: LocalDate) = _state.update { it.copy(startDate = d, endDate = if (it.endDate.isBefore(d)) d else it.endDate) }
    fun setEnd(d: LocalDate) = _state.update { it.copy(endDate = d, startDate = if (it.startDate.isAfter(d)) d else it.startDate) }
    fun setDays(v: Set<String>) = _state.update { it.copy(days = v) }
    fun setMonths(v: Set<Int>) = _state.update { it.copy(months = v) }
    fun setWake(v: Int) = _state.update { it.copy(wakeUpMin = v) }
    fun addDate(d: LocalDate) = _state.update { it.copy(explicitDates = (it.explicitDates + d).distinct().sorted()) }
    fun removeDate(d: LocalDate) = _state.update { it.copy(explicitDates = it.explicitDates - d) }

    fun saveTask(index: Int?, draft: TaskDraft) = _state.update {
        val list = it.tasks.toMutableList()
        if (index == null) list.add(draft) else list[index] = draft
        it.copy(tasks = list.sortedBy { t -> t.startMin })
    }

    fun removeTask(index: Int) = _state.update { it.copy(tasks = it.tasks.toMutableList().also { l -> l.removeAt(index) }) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun submit() {
        val s = _state.value
        val uid = auth.uid
        val dates = s.resolvedDates()
        if (dates.isEmpty() || s.tasks.isEmpty()) return
        _state.update { it.copy(submitting = true) }
        viewModelScope.launch {
            val entities = s.tasks.mapIndexed { i, d -> d.toScheduleTask(null, 0).copy(sortOrder = i) }
            repo.batchAssign(uid, dates, s.wakeUpMin, entities)
            _state.update { it.copy(submitting = false, message = "เพิ่มกิจกรรมให้ ${dates.size} วันเรียบร้อยแล้ว") }
        }
    }
}

private data class BatchEdit(val index: Int?, val kind: TaskKind)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchAssignScreen(onBack: () -> Unit, viewModel: BatchAssignViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<BatchEdit?>(null) }
    var showAddDate by remember { mutableStateOf(false) }
    val dates = remember(state) { state.resolvedDates() }
    val c = AppTheme.colors

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() }
    }

    Scaffold(
        containerColor = c.ground,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("เพิ่มกิจกรรมหลายวัน", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "ย้อนกลับ") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.ground)
            )
        },
        bottomBar = {
            Surface(color = c.surface) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
                    HorizontalDivider(color = c.line, modifier = Modifier.padding(bottom = 12.dp))
                    Text(
                        when {
                            dates.isEmpty() -> "ยังไม่มีวันที่ตรงกับที่เลือก"
                            state.tasks.isEmpty() -> "เลือกได้ ${dates.size} วัน เพิ่มกิจกรรมอย่างน้อย 1 รายการ"
                            else -> "จะเพิ่ม ${state.tasks.size} รายการ ให้ ${dates.size} วัน ต่อท้ายตารางเดิม"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.muted
                    )
                    Spacer(Modifier.height(10.dp))
                    InkButton(
                        text = if (state.submitting) "กำลังเพิ่ม…" else "เพิ่มให้ ${dates.size} วัน",
                        onClick = viewModel::submit,
                        enabled = !state.submitting && dates.isNotEmpty() && state.tasks.isNotEmpty()
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
        ) {
            item {
                SegmentedToggle(
                    options = listOf("ทำซ้ำตามรูปแบบ", "เลือกวันที่เอง"),
                    selectedIndex = if (state.mode == BatchMode.PATTERN) 0 else 1,
                    onSelect = { viewModel.setMode(if (it == 0) BatchMode.PATTERN else BatchMode.EXPLICIT) }
                )
                Spacer(Modifier.height(16.dp))
            }

            if (state.mode == BatchMode.PATTERN) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DateButton("ตั้งแต่", state.startDate, Modifier.weight(1f), viewModel::setStart)
                        DateButton("ถึง", state.endDate, Modifier.weight(1f), viewModel::setEnd)
                    }
                    SectionTitle("ทุกวัน")
                    DayOfWeekPicker(state.days, viewModel::setDays)
                    SectionTitle("เฉพาะเดือน")
                    MonthPicker(state.months, viewModel::setMonths)
                }
            } else {
                item {
                    SectionTitle("วันที่ที่เลือก") {
                        TextButton(onClick = { showAddDate = true }) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp)); Text("เพิ่มวันที่")
                        }
                    }
                    if (state.explicitDates.isEmpty()) {
                        Hint("ยังไม่ได้เลือกวันที่ แตะ \"เพิ่มวันที่\" เพื่อเลือกจากปฏิทิน")
                    }
                    state.explicitDates.forEach { d ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(thaiShortDate(d), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { viewModel.removeDate(d) }) {
                                Icon(Icons.Filled.Close, contentDescription = "เอาวันที่ออก", tint = c.muted)
                            }
                        }
                    }
                }
            }

            if (dates.isNotEmpty()) {
                item {
                    SectionTitle("วันที่จะได้รับ")
                    val months = dates.map { YearMonth.from(it) }.distinct()
                    Panel(padding = PaddingValues(12.dp)) {
                        MonthPreview(months.first(), dates.toSet())
                    }
                    if (months.size > 1) {
                        val rest = dates.count { YearMonth.from(it) != months.first() }
                        Hint("และอีก $rest วันใน ${months.size - 1} เดือนถัดไป", Modifier.padding(top = 6.dp))
                    }
                }
            }

            item {
                SectionTitle("กิจกรรมที่จะเพิ่ม")
                if (state.tasks.isNotEmpty()) {
                    Panel(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                        state.tasks.forEachIndexed { i, t ->
                            TimeRow(
                                title = t.title, kind = t.kind, startMin = t.startMin, endMin = t.endMin,
                                reminder = t.reminder, showDivider = i > 0, color = t.color,
                                onClick = { editing = BatchEdit(i, t.kind) }
                            )
                        }
                    }
                }
                AddKindButtons { kind -> editing = BatchEdit(null, kind) }
                Spacer(Modifier.height(12.dp))
                Hint("เพิ่มต่อท้ายตารางของแต่ละวัน รายการเดิมยังอยู่ วันเหล่านี้จะนับเป็นวันที่ปรับเอง")
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showAddDate) {
        DatePickerDialogHost(
            initial = state.explicitDates.lastOrNull()?.plusDays(1) ?: LocalDate.now(),
            onPick = { viewModel.addDate(it); showAddDate = false },
            onDismiss = { showAddDate = false }
        )
    }

    editing?.let { edit ->
        val lastEnd = state.tasks.maxOfOrNull { it.endMin } ?: (18 * 60)
        TaskEditorDialog(
            title = if (edit.index == null) "เพิ่มรายการ" else "แก้ไขรายการ",
            initial = edit.index?.let { state.tasks[it] } ?: defaultDraft(edit.kind, start = minOf(lastEnd, 23 * 60)),
            onSave = { viewModel.saveTask(edit.index, it); editing = null },
            onDelete = edit.index?.let { i -> { viewModel.removeTask(i); editing = null } },
            onDismiss = { editing = null }
        )
    }
}

/** ปฏิทินหนึ่งเดือน ไฮไลต์วันที่จะได้รับกิจกรรม (สัปดาห์เริ่มวันอาทิตย์) */
@Composable
private fun MonthPreview(month: YearMonth, hits: Set<LocalDate>) {
    val c = AppTheme.colors
    Text(
        "${Recurrence.monthLabelsTh[month.monthValue - 1]} ${month.year + 543}",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
    Row {
        Recurrence.dayCodes.forEach { code ->
            Text(
                Recurrence.dayLabelsTh.getValue(code).trimEnd('.'),
                style = MaterialTheme.typography.labelSmall,
                color = c.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    val lead = Recurrence.column(month.atDay(1).dayOfWeek)
    val cells = lead + month.lengthOfMonth()
    for (week in 0 until (cells + 6) / 7) {
        Row(Modifier.padding(vertical = 2.dp)) {
            for (col in 0..6) {
                val dayNum = week * 7 + col - lead + 1
                val inMonth = dayNum in 1..month.lengthOfMonth()
                val hit = inMonth && month.atDay(dayNum) in hits
                Box(
                    Modifier
                        .weight(1f)
                        .height(32.dp)
                        .padding(horizontal = 2.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (hit) c.workBg else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    if (inMonth) {
                        Text(
                            "$dayNum",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (hit) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (hit) c.work else c.muted.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}
