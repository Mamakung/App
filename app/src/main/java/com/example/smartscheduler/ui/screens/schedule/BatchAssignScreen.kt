package com.example.smartscheduler.ui.screens.schedule

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
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
    /** วันที่ตรงรูปแบบแต่ผู้ใช้แตะเอาออก (โหมดช่วงวันที่) */
    val excluded: Set<LocalDate> = emptySet(),
    /** วันที่ไม่ตรงรูปแบบแต่ผู้ใช้แตะใส่เพิ่ม (โหมดช่วงวันที่) */
    val added: Set<LocalDate> = emptySet(),
    val wakeUpMin: Int = 7 * 60,
    val tasks: List<TaskDraft> = emptyList(),
    val submitting: Boolean = false,
    val message: String? = null
)

/** วันที่ตรงรูปแบบ (ช่วงวันที่ + วันในสัปดาห์ + เดือน) จำกัดไม่เกิน 1 ปีเพื่อกันกดพลาด */
fun BatchUiState.patternDates(): List<LocalDate> {
    if (endDate.isBefore(startDate)) return emptyList()
    val limit = startDate.plusDays(366)
    return generateSequence(startDate) { it.plusDays(1) }
        .takeWhile { !it.isAfter(endDate) && !it.isAfter(limit) }
        .filter { Recurrence.dayCode(it.dayOfWeek) in days && it.monthValue in months }
        .toList()
}

/** อยู่ในช่วงวันที่ที่เลือก (แตะใส่เพิ่มได้) */
fun BatchUiState.inRange(d: LocalDate): Boolean =
    !d.isBefore(startDate) && !d.isAfter(endDate) && !d.isAfter(startDate.plusDays(366))

/** วันที่ทั้งหมดที่จะได้รับกิจกรรม = ตามรูปแบบ − ที่แตะเอาออก + ที่แตะใส่เพิ่ม */
fun BatchUiState.resolvedDates(): List<LocalDate> {
    if (mode == BatchMode.EXPLICIT) return explicitDates
    return (patternDates().filterNot { it in excluded } + added.filter { inRange(it) }).distinct().sorted()
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

    /** แตะวันในปฏิทินตัวอย่าง: เอาออก / ใส่กลับ / ใส่เพิ่ม ทีละวัน */
    fun toggleDay(d: LocalDate) = _state.update { s ->
        when {
            s.mode == BatchMode.EXPLICIT ->
                s.copy(explicitDates = if (d in s.explicitDates) s.explicitDates - d else (s.explicitDates + d).sorted())
            d in s.added -> s.copy(added = s.added - d)
            d in s.excluded -> s.copy(excluded = s.excluded - d)
            d in s.patternDates() -> s.copy(excluded = s.excluded + d)
            s.inRange(d) -> s.copy(added = s.added + d)
            else -> s
        }
    }

    /** ล้างการแตะเอาออก/ใส่เพิ่ม กลับไปตามรูปแบบ */
    fun resetDayEdits() = _state.update { it.copy(excluded = emptySet(), added = emptySet()) }

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

            if (dates.isNotEmpty() || state.mode == BatchMode.PATTERN) {
                item {
                    SectionTitle("วันที่จะได้รับ")
                    // เดือนที่เลื่อนดูได้: ตั้งแต่เดือนแรกถึงเดือนสุดท้ายของช่วง
                    val first = YearMonth.from(if (state.mode == BatchMode.PATTERN) state.startDate else dates.firstOrNull() ?: LocalDate.now())
                    val last = YearMonth.from(if (state.mode == BatchMode.PATTERN) minOf(state.endDate, state.startDate.plusDays(366)) else dates.lastOrNull() ?: LocalDate.now())
                        .let { if (it.isBefore(first)) first else it }
                    var shown by remember { mutableStateOf(first) }
                    if (shown.isBefore(first) || shown.isAfter(last)) shown = first
                    val pattern = remember(state) { if (state.mode == BatchMode.PATTERN) state.patternDates().toSet() else emptySet() }
                    // ไอคอนของกิจกรรมที่จะเพิ่ม (ไอคอนเดียวกันสีเดียวกันนับครั้งเดียว) เรียงตามเวลา
                    val marks = state.tasks.sortedBy { it.startMin }
                        .map { taskIcon(it.kind, it.icon, it.categoryId) to taskAccent(it.kind, it.color, it.categoryId) }
                        .distinct()
                    Panel(padding = PaddingValues(12.dp)) {
                        MonthPreview(
                            month = shown,
                            marks = marks,
                            hits = dates.toSet(),
                            excluded = if (state.mode == BatchMode.PATTERN) state.excluded.filter { it in pattern }.toSet() else emptySet(),
                            canTap = { d -> state.mode == BatchMode.EXPLICIT || state.inRange(d) },
                            onPrev = if (shown.isAfter(first)) ({ shown = shown.minusMonths(1) }) else null,
                            onNext = if (shown.isBefore(last)) ({ shown = shown.plusMonths(1) }) else null,
                            onTap = viewModel::toggleDay
                        )
                    }
                    val removed = state.excluded.count { it in pattern }
                    val extra = state.added.count { state.inRange(it) && it !in pattern }
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Hint(
                            buildString {
                                append("แตะวันเพื่อเอาออกหรือใส่กลับ")
                                if (removed > 0) append(" · เอาออก $removed วัน")
                                if (extra > 0) append(" · เพิ่มเอง $extra วัน")
                            },
                            Modifier.weight(1f)
                        )
                        if (state.mode == BatchMode.PATTERN && (removed > 0 || extra > 0)) {
                            TextButton(onClick = viewModel::resetDayEdits) { Text("คืนค่า") }
                        }
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
                                reminder = t.reminder, showDivider = i > 0, color = t.color, icon = t.icon, categoryId = t.categoryId,
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

/**
 * ปฏิทินหนึ่งเดือน ไฮไลต์วันที่จะได้รับกิจกรรม (สัปดาห์เริ่มวันอาทิตย์) แตะทีละวันเพื่อเอาออก/ใส่กลับ
 *  - ได้รับ: พื้นน้ำเงิน ตัวหนา
 *  - เอาออกแล้ว: ขอบเส้น ตัวเลขขีดฆ่า
 *  - แตะได้แต่ยังไม่ได้รับ: ตัวเลขสีจาง
 */
@Composable
private fun MonthPreview(
    month: YearMonth,
    marks: List<Pair<androidx.compose.ui.graphics.vector.ImageVector, Color>>,
    hits: Set<LocalDate>,
    excluded: Set<LocalDate>,
    canTap: (LocalDate) -> Boolean,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onTap: (LocalDate) -> Unit
) {
    val c = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${Recurrence.monthLabelsTh[month.monthValue - 1]} ${month.year + 543}",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f).padding(start = 4.dp)
        )
        IconButton(onClick = { onPrev?.invoke() }, enabled = onPrev != null) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = "เดือนก่อน", tint = if (onPrev != null) c.ink else c.line)
        }
        IconButton(onClick = { onNext?.invoke() }, enabled = onNext != null) {
            Icon(Icons.Filled.ChevronRight, contentDescription = "เดือนถัดไป", tint = if (onNext != null) c.ink else c.line)
        }
    }
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
                val date = if (inMonth) month.atDay(dayNum) else null
                val hit = date != null && date in hits
                val out = date != null && date in excluded
                val tappable = date != null && canTap(date)
                val bg by animateColorAsState(if (hit) c.workBg else Color.Transparent, tween(160), label = "bg")
                Box(
                    Modifier
                        .weight(1f)
                        .height(if (marks.isEmpty()) 44.dp else 52.dp)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(bg)
                        .then(if (out) Modifier.border(1.5.dp, c.outline, RoundedCornerShape(10.dp)) else Modifier)
                        .then(
                            if (tappable && date != null) Modifier.toggleable(
                                value = hit, role = Role.Checkbox, onValueChange = { onTap(date) }
                            ).semantics {
                                contentDescription = "${date.dayOfMonth} ${Recurrence.monthLabelsTh[date.monthValue - 1]}"
                                stateDescription = when { hit -> "ได้รับกิจกรรม"; out -> "เอาออกแล้ว"; else -> "ไม่ได้รับ" }
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (inMonth) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "$dayNum",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (hit) FontWeight.SemiBold else FontWeight.Normal,
                                textDecoration = if (out) TextDecoration.LineThrough else null,
                                color = when {
                                    hit -> c.work
                                    out -> c.muted
                                    tappable -> c.muted
                                    else -> c.muted.copy(alpha = 0.45f)
                                }
                            )
                            // ตัวอย่างไอคอนกิจกรรมในวันที่ได้รับ (พอที่ 2 ไอคอน เกินนั้นแสดง +n)
                            if (hit && marks.isNotEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.CenterVertically) {
                                    val show = if (marks.size > 2) marks.take(1) else marks
                                    show.forEach { (icon, tint) ->
                                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
                                    }
                                    if (marks.size > 2) {
                                        Text("+${marks.size - 1}", fontSize = 9.sp, color = c.work, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
