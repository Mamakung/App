package com.example.smartscheduler.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartscheduler.data.local.dao.ScheduleDayWithTasks
import com.example.smartscheduler.data.local.entity.ScheduleTaskEntity
import com.example.smartscheduler.data.repository.ScheduleRepository
import com.example.smartscheduler.domain.model.Recurrence
import com.example.smartscheduler.domain.model.TaskKind
import com.example.smartscheduler.ui.components.*
import com.example.smartscheduler.ui.theme.AppTheme
import com.example.smartscheduler.data.session.CurrentUser
import com.example.smartscheduler.importer.SemesterStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

// ============================================================================
// ViewModel
// ============================================================================

data class DashboardUiState(
    val date: LocalDate = LocalDate.now(),
    val day: ScheduleDayWithTasks? = null,
    val loading: Boolean = true,
    /** เช่น "สัปดาห์ที่ 3 จาก 17" หรือ "สัปดาห์สอบปลายภาค" ถ้าตั้งภาคเรียนไว้ */
    val weekLabel: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repo: ScheduleRepository,
    private val auth: CurrentUser,
    private val semesters: SemesterStore
) : ViewModel() {

    private val uid get() = auth.uid
    private val selected = MutableStateFlow(LocalDate.now())

    val state: StateFlow<DashboardUiState> = selected.flatMapLatest { date ->
        flow {
            emit(DashboardUiState(date = date, loading = true))
            repo.ensureDefaultTemplate(uid)
            repo.ensureDayGenerated(uid, date)
            val label = semesters.load()?.label(date)
            emitAll(repo.observeDay(uid, date).map { DashboardUiState(date, it, false, label) })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUiState())

    fun select(date: LocalDate) { selected.value = date }

    fun toggleDone(task: ScheduleTaskEntity) {
        viewModelScope.launch { repo.markTaskStatus(task.taskId, if (task.status == "DONE") "PENDING" else "DONE") }
    }

    fun save(date: LocalDate, existing: ScheduleTaskEntity?, draft: TaskDraft) {
        viewModelScope.launch {
            if (existing == null) repo.addTaskToDate(uid, date, draft.toScheduleTask(null, 0))
            else repo.editSingleDayTask(draft.toScheduleTask(existing, existing.dayId), existing)
        }
    }

    fun delete(task: ScheduleTaskEntity) { viewModelScope.launch { repo.deleteSingleDayTask(task) } }

    fun resetDay(date: LocalDate) { viewModelScope.launch { repo.resetDayToTemplate(uid, date) } }
}

// ============================================================================
// Screen
// ============================================================================

private data class DashEdit(val task: ScheduleTaskEntity?)

private val thai = Locale("th", "TH")
private val longDate = DateTimeFormatter.ofPattern("EEEEที่ d MMMM", thai)
private val monthYear = DateTimeFormatter.ofPattern("MMMM", thai)

/** นาทีปัจจุบัน อัปเดตทุก 30 วินาที เพื่อขยับเส้น "ตอนนี้" และตัวนับถอยหลัง */
@Composable
private fun rememberNowMinutes(): Int {
    val now by produceState(LocalTime.now().let { it.hour * 60 + it.minute }) {
        while (true) {
            delay(30_000)
            value = LocalTime.now().let { it.hour * 60 + it.minute }
        }
    }
    return now
}

@Composable
fun DashboardScreen(
    onOpenBatchAssign: () -> Unit,
    onOpenImport: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var editing by remember { mutableStateOf<DashEdit?>(null) }
    val nowMin = rememberNowMinutes()
    val isToday = state.date == LocalDate.now()
    val tasks = remember(state.day) { state.day?.tasks.orEmpty().sortedBy { it.startMin } }

    Box(Modifier.fillMaxSize().background(AppTheme.colors.ground)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 96.dp)
        ) {
            TopRow(state.date, state.weekLabel, onSelect = viewModel::select, onOpenBatchAssign = onOpenBatchAssign, onOpenImport = onOpenImport)
            StatusHeader(state.date, tasks, isToday, nowMin)
            Spacer(Modifier.height(22.dp))
            WeekStrip(state.date, onSelect = viewModel::select)
            Spacer(Modifier.height(18.dp))

            if (state.day?.day?.isCustomized == true) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppTheme.colors.taskBg)
                        .padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "วันนี้ปรับเองแล้ว กิจวัตรมาตรฐานจะไม่มาเปลี่ยนวันนี้",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.resetDay(state.date) }) { Text("ใช้ตามกิจวัตร") }
                }
                Spacer(Modifier.height(12.dp))
            }

            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                    CircularProgressIndicator(color = AppTheme.colors.ink)
                }
                tasks.isEmpty() -> EmptyState(
                    icon = Icons.Filled.EventAvailable,
                    title = "วันนี้ว่าง",
                    subtitle = "วันนี้ไม่อยู่ในวันหรือเดือนที่กิจวัตรมาตรฐานทำซ้ำ เพิ่มกิจกรรมเฉพาะวันนี้ได้",
                    actionLabel = "เพิ่มกิจกรรม",
                    onAction = { editing = DashEdit(null) }
                )
                else -> DayTimeline(
                    tasks = tasks,
                    wakeMin = state.day?.day?.let { it.wakeUpHour * 60 + it.wakeUpMinute },
                    nowMin = if (isToday) nowMin else null,
                    onToggle = viewModel::toggleDone,
                    onOpen = { editing = DashEdit(it) }
                )
            }
        }

        ExtendedFloatingActionButton(
            onClick = { editing = DashEdit(null) },
            containerColor = AppTheme.colors.ink,
            contentColor = AppTheme.colors.ground,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("เพิ่มเฉพาะวันนี้") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        )
    }

    editing?.let { edit ->
        TaskEditorDialog(
            title = if (edit.task == null) "เพิ่มกิจกรรม" else "แก้ไขกิจกรรม",
            initial = edit.task?.toDraft() ?: defaultDraft(TaskKind.TASK, start = ((nowMin / 60) + 1) * 60 % (24 * 60)),
            footnote = "มีผลเฉพาะวันที่เลือก กิจวัตรมาตรฐานไม่เปลี่ยน",
            onSave = { viewModel.save(state.date, edit.task, it); editing = null },
            onDelete = edit.task?.let { t -> { viewModel.delete(t); editing = null } },
            onDismiss = { editing = null }
        )
    }
}

// ============================================================================
// Header pieces
// ============================================================================

@Composable
private fun TopRow(date: LocalDate, weekLabel: String?, onSelect: (LocalDate) -> Unit, onOpenBatchAssign: () -> Unit, onOpenImport: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(date.format(longDate), style = MaterialTheme.typography.bodyLarge, color = AppTheme.colors.muted)
            weekLabel?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.study) }
        }
        if (date != LocalDate.now()) TextButton(onClick = { onSelect(LocalDate.now()) }) { Text("กลับวันนี้") }
        IconButton(onClick = onOpenImport) {
            Icon(Icons.Filled.UploadFile, contentDescription = "นำเข้าตารางจากไฟล์", tint = AppTheme.colors.ink)
        }
        IconButton(onClick = onOpenBatchAssign) {
            Icon(Icons.Filled.EventRepeat, contentDescription = "เพิ่มกิจกรรมหลายวัน", tint = AppTheme.colors.ink)
        }
    }
}

/** หัวหน้าจอ: บอกว่าเหลือเวลาเท่าไรก่อนจบช่วงปัจจุบัน หรือก่อนเริ่มช่วงถัดไป */
@Composable
private fun StatusHeader(date: LocalDate, tasks: List<ScheduleTaskEntity>, isToday: Boolean, nowMin: Int) {
    val c = AppTheme.colors
    val current = if (isToday) tasks.firstOrNull { nowMin in it.startMin until it.endMin } else null
    val next = if (isToday) tasks.firstOrNull { it.startMin > nowMin } else null
    val done = tasks.count { it.status == "DONE" }
    val currentColor = current?.let { TaskKind.from(it.kind).color() } ?: c.ink
    val nextColor = next?.let { TaskKind.from(it.kind).color() } ?: c.ink

    val big: String
    val line = buildAnnotatedString {
        when {
            tasks.isEmpty() -> { append("ยังไม่มีกิจกรรม") }
            !isToday -> { append("${durationText(tasks.filter { it.kind == TaskKind.WORK.code }.sumOf { it.durationMinutes })} สำหรับทำงาน") }
            current != null -> {
                append("ก่อนจบ ")
                withStyle(SpanStyle(color = currentColor, fontWeight = FontWeight.SemiBold)) { append(current.title) }
                if (next != null) append(" แล้ว${next.title}")
            }
            next != null -> {
                append("จะเริ่ม ")
                withStyle(SpanStyle(color = nextColor, fontWeight = FontWeight.SemiBold)) { append(next.title) }
            }
            else -> append("หมดตารางวันนี้แล้ว")
        }
    }
    big = when {
        tasks.isEmpty() -> date.format(monthYear)
        !isToday -> "${tasks.size} รายการ"
        current != null -> durationText(current.endMin - nowMin)
        next != null -> "อีก ${durationText(next.startMin - nowMin)}"
        else -> "เสร็จ $done จาก ${tasks.size}"
    }

    Column(Modifier.padding(top = 4.dp)) {
        Text(big, style = MaterialTheme.typography.displaySmall, color = c.ink)
        Text(line, style = MaterialTheme.typography.bodyLarge, color = c.ink)
        if (tasks.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                tasks.forEach { t ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (t.status == "DONE") c.ink else c.line)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Hint("ทำเสร็จ $done จาก ${tasks.size} รายการ")
        }
    }
}

@Composable
private fun WeekStrip(selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    val c = AppTheme.colors
    val sunday = Recurrence.weekStart(selected)
    val today = LocalDate.now()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onSelect(selected.minusWeeks(1)) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = "สัปดาห์ก่อน", tint = c.muted)
        }
        for (i in 0..6) {
            val d = sunday.plusDays(i.toLong())
            val on = d == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) c.ink else Color.Transparent)
                    .clickable { onSelect(d) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    Recurrence.dayLabelsTh.getValue(Recurrence.dayCode(d.dayOfWeek)).trimEnd('.'),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (on) c.ground.copy(alpha = 0.75f) else c.muted
                )
                Text(
                    "${d.dayOfMonth}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (on) c.ground else c.ink
                )
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(if (d == today && !on) c.now else Color.Transparent)
                )
            }
        }
        IconButton(onClick = { onSelect(selected.plusWeeks(1)) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.ChevronRight, contentDescription = "สัปดาห์ถัดไป", tint = c.muted)
        }
    }
}

// ============================================================================
// Proportional timeline — ความสูงของแต่ละช่วงเท่ากับเวลาจริง
// ============================================================================

private val PerMinute: Dp = 1.4.dp
private val LabelWidth: Dp = 46.dp
private val MinBlock: Dp = 30.dp
private val BlockGap: Dp = 3.dp

@Composable
private fun DayTimeline(
    tasks: List<ScheduleTaskEntity>,
    wakeMin: Int?,
    nowMin: Int?,
    onToggle: (ScheduleTaskEntity) -> Unit,
    onOpen: (ScheduleTaskEntity) -> Unit
) {
    val c = AppTheme.colors
    val firstMin = listOfNotNull(wakeMin, tasks.minOfOrNull { it.startMin }).min()
    val lastMin = tasks.maxOf { it.endMin }
    val from = (firstMin / 60) * 60
    val to = minOf(((lastMin + 59) / 60) * 60, 24 * 60)
    fun y(m: Int): Dp = PerMinute * (m - from)

    Box(Modifier.fillMaxWidth().height(y(to) + 8.dp)) {
        // hour grid
        for (h in (from / 60)..(to / 60)) {
            Box(Modifier.offset(y = y(h * 60) - 7.dp).width(LabelWidth - 8.dp)) {
                Text(
                    "%02d:00".format(h % 24),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.muted,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            HorizontalDivider(
                modifier = Modifier.offset(x = LabelWidth, y = y(h * 60)).fillMaxWidth(),
                color = c.line.copy(alpha = 0.6f)
            )
        }
        // rail
        Box(
            Modifier
                .offset(x = LabelWidth)
                .width(1.dp)
                .fillMaxHeight()
                .background(c.line)
        )
        // wake marker
        wakeMin?.let { w ->
            Row(
                Modifier.offset(x = LabelWidth - 4.dp, y = y(w) - 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c.ink))
                Spacer(Modifier.width(8.dp))
                Hint("ตื่น ${fmt(w)}")
            }
        }
        // blocks
        tasks.forEachIndexed { i, t ->
            // ช่วงสั้น (เช่น 15 นาที) ยืดได้ถึง MinBlock แต่ห้ามยืดทับช่วงถัดไป
            val nextStart = tasks.getOrNull(i + 1)?.startMin
            val natural = PerMinute * t.durationMinutes - BlockGap
            val room = nextStart?.let { PerMinute * (it - t.startMin) - BlockGap } ?: MinBlock
            val blockHeight = maxOf(natural, minOf(MinBlock, room), 14.dp)
            TimelineBlock(
                task = t,
                height = blockHeight,
                modifier = Modifier
                    .padding(start = LabelWidth + 10.dp)
                    .offset(y = y(t.startMin) + 1.dp)
                    .fillMaxWidth()
                    .height(blockHeight),
                onToggle = { onToggle(t) },
                onOpen = { onOpen(t) }
            )
        }
        // now line
        if (nowMin != null && nowMin in from..to) {
            Box(Modifier.offset(x = LabelWidth - 5.dp, y = y(nowMin) - 5.dp).size(10.dp).clip(CircleShape).background(c.now))
            Box(
                Modifier
                    .offset(x = LabelWidth, y = y(nowMin) - 1.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(c.now)
            )
        }
    }
}

@Composable
private fun TimelineBlock(task: ScheduleTaskEntity, height: Dp, modifier: Modifier, onToggle: () -> Unit, onOpen: () -> Unit) {
    val c = AppTheme.colors
    val kind = TaskKind.from(task.kind)
    val accent = kind.color()
    val fill = kind.bg()
    val stripe = c.surface.copy(alpha = 0.45f)
    val done = task.status == "DONE"
    val thin = height < 52.dp
    val tiny = height < 28.dp
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier
            .clip(shape)
            .background(fill)
            .then(
                if (kind == TaskKind.BREAK) Modifier.drawBehind {
                    // ลายทแยงให้ช่วงพักแยกจากช่วงทำงานได้ทันทีแม้ไม่ดูสี
                    val step = 12.dp.toPx()
                    var x = -size.height
                    while (x < size.width) {
                        drawLine(stripe, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 5.dp.toPx())
                        x += step
                    }
                } else Modifier
            )
            .clickable(onClick = onOpen)
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Row(
            Modifier
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = if (thin) 0.dp else 10.dp)
                .then(if (thin) Modifier.fillMaxHeight() else Modifier),
            verticalAlignment = if (thin) Alignment.CenterVertically else Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    task.title,
                    style = when {
                        tiny -> MaterialTheme.typography.labelSmall
                        thin -> MaterialTheme.typography.labelMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    color = if (done) c.muted else c.ink,
                    maxLines = if (thin) 1 else 2
                )
                if (!thin) Hint("${rangeText(task.startMin, task.endMin)}  ${task.description ?: durationText(task.durationMinutes)}")
            }
            if (thin) {
                Text(
                    rangeText(task.startMin, task.endMin),
                    style = if (tiny) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                    color = c.muted
                )
                Spacer(Modifier.width(8.dp))
            }
            run {
                Box(
                    Modifier
                        .size(if (tiny) 14.dp else if (thin) 20.dp else 26.dp)
                        .clip(CircleShape)
                        .background(if (done) c.ink else Color.Transparent)
                        .then(if (!done) Modifier.drawBehind {
                            drawCircle(c.muted, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                        } else Modifier)
                        .clickable(onClick = onToggle),
                    contentAlignment = Alignment.Center
                ) {
                    if (done) Icon(Icons.Filled.Check, contentDescription = "ทำเสร็จแล้ว", tint = c.ground, modifier = Modifier.size(if (tiny) 10.dp else if (thin) 13.dp else 16.dp))
                }
            }
        }
    }
}
