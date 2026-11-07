package com.example.smartscheduler.ui.screens.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.graphics.graphicsLayer
import com.example.smartscheduler.settings.LocalAppearance
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import com.example.smartscheduler.data.repository.DeleteScope
import com.example.smartscheduler.data.repository.ScheduleRepository
import com.example.smartscheduler.data.repository.isRepeating
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
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

/** มุมมองของหน้าปฏิทิน */
enum class CalendarMode(val label: String) { MONTH("เดือน"), WEEK("สัปดาห์"), DAY("วัน") }

data class DashboardUiState(
    val mode: CalendarMode = CalendarMode.MONTH,
    val date: LocalDate = LocalDate.now(),
    /** ตารางของวันที่เลือก */
    val day: ScheduleDayWithTasks? = null,
    /** ตารางของทุกวันในช่วงที่แสดง (เดือน/สัปดาห์) */
    val range: Map<LocalDate, ScheduleDayWithTasks> = emptyMap(),
    val loading: Boolean = true,
    /** เช่น "สัปดาห์ที่ 3 จาก 17" หรือ "สัปดาห์สอบปลายภาค" ถ้าตั้งภาคเรียนไว้ */
    val weekLabel: String? = null
)

/** ช่วงวันที่ที่มุมมองต้องใช้: เดือน = 6 สัปดาห์เต็มของตาราง, สัปดาห์ = อาทิตย์–เสาร์ */
fun visibleRange(mode: CalendarMode, date: LocalDate): ClosedRange<LocalDate> = when (mode) {
    CalendarMode.MONTH -> {
        val first = Recurrence.weekStart(date.withDayOfMonth(1))
        first..first.plusDays(41)
    }
    CalendarMode.WEEK, CalendarMode.DAY -> Recurrence.weekStart(date).let { it..it.plusDays(6) }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val repo: ScheduleRepository,
    private val auth: CurrentUser,
    private val semesters: SemesterStore,
    settings: com.example.smartscheduler.settings.AppSettings
) : ViewModel() {
    /** รูปแบบเมนูปุ่ม + (ตั้งค่าในหน้าตั้งค่า) */
    val fabStyle = settings.fabStyle

    private val uid get() = auth.uid
    private val prefs = context.getSharedPreferences("calendar", Context.MODE_PRIVATE)
    private val selected = MutableStateFlow(LocalDate.now())
    private val mode = MutableStateFlow(
        runCatching { CalendarMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }.getOrDefault(CalendarMode.MONTH)
    )

    /**
     * ตารางของทุกวันในช่วงที่มองเห็น โหลดใหม่เฉพาะเมื่อ "ช่วง" เปลี่ยน (เลื่อนเดือน/สัปดาห์ หรือสลับมุมมอง)
     * แตะวันอื่นในช่วงเดิมจึงไม่ล้างข้อมูลแล้วโหลดใหม่ (เดิมทำให้สีกะพริบ)
     * ระหว่างโหลดช่วงใหม่ยังแสดงข้อมูลเดิมไว้ ไม่ล้างหน้าจอ
     */
    private val rangeDays: StateFlow<Map<LocalDate, ScheduleDayWithTasks>?> =
        combine(mode, selected) { m, d -> visibleRange(m, d).let { it.start to it.endInclusive } }
            .distinctUntilChanged()
            .flatMapLatest { (start, end) ->
                flow {
                    repo.ensureDefaultTemplate(uid)
                    var d = start
                    while (!d.isAfter(end)) { repo.ensureDayGenerated(uid, d); d = d.plusDays(1) }
                    emitAll(
                        repo.observeRange(uid, start, end)
                            .map { days -> days.associateBy { LocalDate.ofEpochDay(it.day.dateEpochDay) } }
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val state: StateFlow<DashboardUiState> = combine(mode, selected, rangeDays) { m, date, days ->
        DashboardUiState(
            mode = m,
            date = date,
            day = days?.get(date),
            range = days.orEmpty(),
            loading = days == null,
            weekLabel = semesters.load()?.label(date)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUiState(mode = mode.value))

    fun select(date: LocalDate) { selected.value = date }

    fun setMode(m: CalendarMode) {
        mode.value = m
        prefs.edit().putString(KEY_MODE, m.name).apply()
    }

    /** ‹ › เลื่อนทีละเดือน / สัปดาห์ / วัน ตามมุมมอง */
    fun step(forward: Boolean) {
        val n = if (forward) 1L else -1L
        selected.value = when (mode.value) {
            CalendarMode.MONTH -> selected.value.plusMonths(n)
            CalendarMode.WEEK -> selected.value.plusWeeks(n)
            CalendarMode.DAY -> selected.value.plusDays(n)
        }
    }

    /** แตะวันในเดือน/สัปดาห์: ครั้งแรกเลือก ครั้งที่สองเปิดรายวัน */
    fun openDay(date: LocalDate) {
        if (selected.value == date) setMode(CalendarMode.DAY) else selected.value = date
    }

    fun toggleDone(task: ScheduleTaskEntity) {
        viewModelScope.launch { repo.markTaskStatus(task.taskId, if (task.status == "DONE") "PENDING" else "DONE") }
    }

    fun save(date: LocalDate, existing: ScheduleTaskEntity?, draft: TaskDraft) {
        viewModelScope.launch {
            if (existing == null) repo.addTaskToDate(uid, date, draft.toScheduleTask(null, 0))
            else {
                val updated = draft.toScheduleTask(existing, existing.dayId)
                repo.editSingleDayTask(updated, existing)
                val styleChanged = updated.color != existing.color || updated.icon != existing.icon ||
                    updated.categoryId != existing.categoryId || updated.kind != existing.kind
                if (existing.isRepeating && styleChanged) repo.setSeriesStyle(uid, existing, updated)
            }
        }
    }

    /** เพิ่มกิจกรรมเดียวให้ทุกวันตั้งแต่ [from] ถึง [to] (จากการลากเลือกในปฏิทิน) */
    fun addToRange(from: LocalDate, to: LocalDate, draft: TaskDraft) {
        viewModelScope.launch { repo.addTaskToRange(uid, from, to, draft.toScheduleTask(null, 0)) }
    }

    fun delete(task: ScheduleTaskEntity, scope: DeleteScope = DeleteScope.THIS) {
        viewModelScope.launch { repo.deleteTask(uid, task, scope) }
    }

    fun resetDay(date: LocalDate) { viewModelScope.launch { repo.resetDayToTemplate(uid, date) } }

    private companion object { const val KEY_MODE = "mode" }
}

// ============================================================================
// Screen
// ============================================================================

private data class DashEdit(val task: ScheduleTaskEntity?, val date: LocalDate)

private val thai = Locale("th", "TH")
private val longDate = DateTimeFormatter.ofPattern("EEEEที่ d MMMM", thai)

private fun monthTitle(d: LocalDate) = "${Recurrence.monthFullTh[d.monthValue - 1]} ${d.year + 543}"
private fun shortDay(d: LocalDate) = "${d.dayOfMonth} ${Recurrence.monthLabelsTh[d.monthValue - 1]}"
private fun dayLetter(d: LocalDate) = Recurrence.dayLabelsTh.getValue(Recurrence.dayCode(d.dayOfWeek)).trimEnd('.')

private fun periodTitle(mode: CalendarMode, date: LocalDate): String = when (mode) {
    CalendarMode.MONTH -> monthTitle(date)
    CalendarMode.WEEK -> {
        val s = Recurrence.weekStart(date); val e = s.plusDays(6)
        if (s.month == e.month) "${s.dayOfMonth}–${e.dayOfMonth} ${Recurrence.monthLabelsTh[e.monthValue - 1]} ${e.year + 543}"
        else "${shortDay(s)} – ${shortDay(e)} ${e.year + 543}"
    }
    CalendarMode.DAY -> date.format(longDate)
}

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

private fun ScheduleDayWithTasks?.sortedTasks(): List<ScheduleTaskEntity> =
    this?.tasks.orEmpty().sortedBy { it.startMin }

@Composable
fun DashboardScreen(
    onOpenBatchAssign: () -> Unit,
    onOpenBatchRange: (LocalDate, LocalDate) -> Unit,
    onOpenImport: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    // ปุ่มย้อนกลับของเครื่อง: ดูรายวัน/รายสัปดาห์อยู่ ให้กลับไปหน้าเดือนก่อน (ยังไม่ออกจากแอป)
    BackHandler(enabled = state.mode != CalendarMode.MONTH) { viewModel.setMode(CalendarMode.MONTH) }
    var editing by remember { mutableStateOf<DashEdit?>(null) }
    var deleting by remember { mutableStateOf<DashEdit?>(null) }
    var confirmAll by remember { mutableStateOf<DashEdit?>(null) }
    // ลากเลือกหลายวันในปฏิทินแล้วปล่อย: เพิ่มกิจกรรมเดียวให้ทุกวันในช่วง
    var rangeEdit by remember { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    val context = LocalContext.current
    val nowMin = rememberNowMinutes()
    val today = LocalDate.now()
    val c = AppTheme.colors

    Box(Modifier.fillMaxSize().background(c.ground)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 96.dp)
        ) {
            AppBarRow(onOpenBatchAssign = onOpenBatchAssign, onOpenImport = onOpenImport)
            SegmentedToggle(
                options = CalendarMode.values().map { it.label },
                selectedIndex = state.mode.ordinal,
                onSelect = { viewModel.setMode(CalendarMode.values()[it]) }
            )
            PeriodNav(
                title = periodTitle(state.mode, state.date),
                subtitle = state.weekLabel,
                showToday = if (state.mode == CalendarMode.DAY) state.date != today else today !in visibleRange(state.mode, state.date),
                onPrev = { viewModel.step(false) },
                onNext = { viewModel.step(true) },
                onToday = { viewModel.select(today) },
                stepName = when (state.mode) {
                    CalendarMode.MONTH -> "เดือน"; CalendarMode.WEEK -> "สัปดาห์"; CalendarMode.DAY -> "วัน"
                }
            )

            when (state.mode) {
                CalendarMode.MONTH -> {
                    MonthGrid(
                        anchor = state.date,
                        selected = state.date,
                        days = state.range,
                        onTap = viewModel::openDay,
                        onAddDay = { d -> viewModel.select(d); editing = DashEdit(null, d) },
                        onAddRange = { a, b -> rangeEdit = a to b },
                        onShiftMonth = viewModel::step
                    )
                    Spacer(Modifier.height(20.dp))
                    DayAgenda(
                        date = state.date,
                        tasks = state.day.sortedTasks(),
                        loading = state.loading,
                        onOpenDay = { viewModel.setMode(CalendarMode.DAY) },
                        onOpenTask = { editing = DashEdit(it, state.date) }
                    )
                }
                CalendarMode.WEEK -> WeekAgenda(
                    start = Recurrence.weekStart(state.date),
                    selected = state.date,
                    days = state.range,
                    loading = state.loading,
                    onOpenDay = { viewModel.select(it); viewModel.setMode(CalendarMode.DAY) },
                    onOpenTask = { task, date -> editing = DashEdit(task, date) }
                )
                CalendarMode.DAY -> DayView(
                    state = state,
                    nowMin = nowMin,
                    onSelect = viewModel::select,
                    onReset = { viewModel.resetDay(state.date) },
                    onToggle = viewModel::toggleDone,
                    onOpenTask = { editing = DashEdit(it, state.date) },
                    onAdd = { editing = DashEdit(null, state.date) }
                )
            }
        }

        // ปุ่ม + : กด/กดค้างแล้วกางเมนูไอคอน (เพิ่มลงวันที่เลือก / เพิ่มหลายวัน / นำเข้าไฟล์ตาราง)
        val fabStyle by viewModel.fabStyle.collectAsState()
        val addDayLabel = if (state.date == today) "เพิ่มวันนี้" else "เพิ่มวันที่ ${shortDay(state.date)}"
        AddMenuFab(
            style = fabStyle,
            actions = listOf(
                AddMenuAction(
                    label = addDayLabel,
                    tint = { AppTheme.colors.now },
                    icon = { tint -> DayNumberIcon(state.date.dayOfMonth, tint) },
                    onClick = { editing = DashEdit(null, state.date) }
                ),
                AddMenuAction(
                    label = "เพิ่มหลายวัน",
                    tint = { AppTheme.colors.study },
                    icon = { tint -> Icon(StackedCalendars, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp)) },
                    onClick = onOpenBatchAssign
                ),
                AddMenuAction(
                    label = "นำเข้าไฟล์ตาราง",
                    tint = { AppTheme.colors.task },
                    icon = { tint -> Icon(Icons.Filled.UploadFile, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp)) },
                    onClick = onOpenImport
                )
            )
        )
    }

    editing?.let { edit ->
        val live = edit.task?.let { t -> state.range.values.flatMap { it.tasks }.plus(state.day?.tasks.orEmpty()).firstOrNull { it.taskId == t.taskId } } ?: edit.task
        TaskEditorDialog(
            title = if (edit.task == null) "เพิ่มกิจกรรม ${shortDay(edit.date)}" else "แก้ไขกิจกรรม",
            initial = edit.task?.toDraft() ?: defaultDraft(TaskKind.TASK, start = ((nowMin / 60) + 1) * 60 % (24 * 60)),
            footnote = "เวลาและชื่อมีผลเฉพาะวันที่ ${shortDay(edit.date)}" +
                if (edit.task?.isRepeating == true) " ส่วนหมวด สี และไอคอนเปลี่ยนให้ทุกวันของรายการนี้" else "",
            onSave = { viewModel.save(edit.date, edit.task, it); editing = null },
            onDelete = edit.task?.let { t ->
                {
                    editing = null
                    if (t.isRepeating) deleting = edit
                    else {
                        viewModel.delete(t)
                        Toast.makeText(context, "ลบ \"${t.title}\" แล้ว", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            done = live?.let { it.status == "DONE" },
            onDoneChange = live?.let { t -> { _: Boolean -> viewModel.toggleDone(t) } },
            onDismiss = { editing = null }
        )
    }

    rangeEdit?.let { (a, b) ->
        val n = java.time.temporal.ChronoUnit.DAYS.between(a, b) + 1
        TaskEditorDialog(
            title = "เพิ่มกิจกรรม",
            initial = defaultDraft(TaskKind.TASK, start = 9 * 60),
            topContent = {
                RangeRow(from = a, to = b, days = n.toInt(), onMore = { rangeEdit = null; onOpenBatchRange(a, b) })
            },
            saveLabel = "บันทึก $n วัน",
            onSave = { draft ->
                viewModel.addToRange(a, b, draft)
                Toast.makeText(context, "เพิ่ม \"${draft.title}\" ให้ $n วันแล้ว", Toast.LENGTH_SHORT).show()
                rangeEdit = null
            },
            onDismiss = { rangeEdit = null }
        )
    }

    // ลบรวมวันที่ผ่านมาแล้ว: ถามยืนยันอีกครั้ง
    confirmAll?.let { d ->
        d.task?.let { t ->
            ConfirmDeleteDialog(
                title = "ลบ \"${t.title}\" ทุกวัน",
                message = "รวมวันที่ผ่านมาแล้วด้วย ประวัติของรายการนี้จะหายทั้งหมด และย้อนกลับไม่ได้",
                confirmText = "ลบทั้งหมด",
                onConfirm = {
                    viewModel.delete(t, DeleteScope.EVERYTHING)
                    Toast.makeText(context, "ลบ \"${t.title}\" ทุกวันแล้ว รวมวันที่ผ่านมา", Toast.LENGTH_SHORT).show()
                    confirmAll = null
                },
                onDismiss = { confirmAll = null }
            )
        }
    }

    deleting?.let { d ->
        DeleteRepeatingDialog(
            edit = d,
            onConfirm = { scope ->
                if (scope == DeleteScope.EVERYTHING) { confirmAll = d; deleting = null; return@DeleteRepeatingDialog }
                d.task?.let { t ->
                    viewModel.delete(t, scope)
                    val what = when (scope) {
                        DeleteScope.THIS -> "เฉพาะวันที่ ${shortDay(d.date)}"
                        DeleteScope.FOLLOWING -> "ตั้งแต่ ${shortDay(d.date)} เป็นต้นไป"
                        DeleteScope.ALL -> "ทุกวันข้างหน้า"
                        DeleteScope.EVERYTHING -> "ทุกวัน"
                    }
                    Toast.makeText(context, "ลบ \"${t.title}\" $what แล้ว", Toast.LENGTH_SHORT).show()
                }
                deleting = null
            },
            onDismiss = { deleting = null }
        )
    }
}

// ============================================================================
// Range row (ลากเลือกหลายวัน)
// ============================================================================

/** แถว "ตั้งแต่ → ถึง · n วัน" บนหน้าต่างเพิ่มกิจกรรม + ลิงก์ไปหน้าเพิ่มหลายวันแบบเต็ม */
@Composable
private fun RangeRow(from: LocalDate, to: LocalDate, days: Int, onMore: () -> Unit) {
    val c = AppTheme.colors
    Column {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.ground.copy(alpha = 0.5f)).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Hint("ตั้งแต่")
                Text(from.format(shortWeekday), style = MaterialTheme.typography.titleSmall)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "ถึง", tint = c.muted, modifier = Modifier.padding(horizontal = 10.dp).size(18.dp))
            Column {
                Hint("ถึง")
                Text(to.format(shortWeekday), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "$days วัน",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = c.ground,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(LocalAppearance.current.dragColor).padding(horizontal = 9.dp, vertical = 2.dp)
            )
        }
        TextButton(onClick = onMore) { Text("เลือกวันเอง / เพิ่มหลายกิจกรรม") }
    }
}

private val shortWeekday = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale("th", "TH"))

// ============================================================================
// Delete repeating task
// ============================================================================

@Composable
private fun DeleteRepeatingDialog(
    edit: DashEdit,
    onConfirm: (DeleteScope) -> Unit,
    onDismiss: () -> Unit
) {
    val t = edit.task ?: return
    val day = shortDay(edit.date)
    val weekday = com.example.smartscheduler.importer.DayNames.labelTh(Recurrence.dayCode(edit.date.dayOfWeek))
    val everything = DeleteOption(DeleteScope.EVERYTHING, "ทุกวัน รวมวันที่ผ่านมาแล้ว", "ลบประวัติด้วย ย้อนกลับไม่ได้ (ถามยืนยันอีกครั้ง)")
    val options = when {
        t.importBatchId != null -> listOf(
            DeleteOption(DeleteScope.THIS, "เฉพาะวันที่ $day", "สัปดาห์อื่นยังมีคาบนี้ตามเดิม"),
            DeleteOption(DeleteScope.FOLLOWING, "ตั้งแต่ $day เป็นต้นไป", "ทุก$weekday ที่เหลือของภาคเรียน"),
            DeleteOption(DeleteScope.ALL, "ทุกสัปดาห์", "ทุก$weekday ตั้งแต่วันนี้ วันที่ผ่านมาแล้วเก็บไว้"),
            everything
        )
        t.originTemplateTaskId != null -> listOf(
            DeleteOption(DeleteScope.THIS, "เฉพาะวันที่ $day", "กิจวัตรยังอยู่ วันอื่นไม่เปลี่ยน"),
            DeleteOption(DeleteScope.ALL, "ทุกวัน (เอาออกจากกิจวัตร)", "ลบจากกิจวัตรและทุกวันข้างหน้า วันที่ผ่านมาแล้วเก็บไว้"),
            everything
        )
        // เพิ่มพร้อมกันหลายวัน
        else -> listOf(
            DeleteOption(DeleteScope.THIS, "เฉพาะวันที่ $day", "วันอื่นที่เพิ่มพร้อมกันยังอยู่"),
            DeleteOption(DeleteScope.FOLLOWING, "ตั้งแต่ $day เป็นต้นไป", "รายการนี้ทุกวันหลังจากนั้น"),
            DeleteOption(DeleteScope.ALL, "ทุกวันที่เพิ่มพร้อมกัน", "ตั้งแต่วันนี้ วันที่ผ่านมาแล้วเก็บไว้"),
            everything
        )
    }
    DeleteScopeDialog(itemTitle = t.title, options = options, onConfirm = onConfirm, onDismiss = onDismiss)
}

// ============================================================================
// Header
// ============================================================================

@Composable
private fun AppBarRow(onOpenBatchAssign: () -> Unit, onOpenImport: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("ปฏิทิน", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).semantics { heading() })
        IconButton(onClick = onOpenImport) {
            Icon(Icons.Filled.UploadFile, contentDescription = "นำเข้าตารางจากไฟล์", tint = AppTheme.colors.ink)
        }
        IconButton(onClick = onOpenBatchAssign) {
            Icon(Icons.Filled.EventRepeat, contentDescription = "เพิ่มกิจกรรมหลายวัน", tint = AppTheme.colors.ink)
        }
    }
}

@Composable
private fun PeriodNav(
    title: String,
    subtitle: String?,
    showToday: Boolean,
    stepName: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit
) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(Icons.Filled.ChevronLeft, contentDescription = "${stepName}ก่อนหน้า", tint = c.ink) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                showToday -> TextButton(onClick = onToday, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("กลับมาวันนี้") }
                subtitle != null -> Text(subtitle, style = MaterialTheme.typography.labelMedium, color = c.study)
            }
        }
        IconButton(onClick = onNext) { Icon(Icons.Filled.ChevronRight, contentDescription = "${stepName}ถัดไป", tint = c.ink) }
    }
}

// ============================================================================
// Month view
// ============================================================================

private fun rangeStartOf(a: LocalDate?, b: LocalDate?) = listOfNotNull(a, b).minOrNull()
private fun rangeEndOf(a: LocalDate?, b: LocalDate?) = listOfNotNull(a, b).maxOrNull()

private const val StackIcon = 14
private const val StackStep = 4
private const val StackMaxLayers = 3

/** กว้างของหนึ่งกอง (dp) */
private fun stackWidth(layers: Int) = StackIcon + (minOf(layers, StackMaxLayers) - 1) * StackStep

/** กี่กองที่ใส่ในช่องกว้าง [budget] dp ได้ (เว้นที่ให้ "+n" ถ้าใส่ไม่หมด) */
private fun fitStacks(layerCounts: List<Int>, budget: Int): Int {
    var used = 0
    layerCounts.forEachIndexed { i, n ->
        val w = stackWidth(n) + if (i > 0) 2 else 0
        val reserve = if (i < layerCounts.lastIndex) 14 else 0
        if (used + w + reserve > budget && i > 0) return i
        used += w
    }
    return layerCounts.size
}

/**
 * ไอคอนเดียวกันซ้อนเป็นชั้น ชั้นละสี (สูงสุด 3 ชั้น) ชั้นแรกอยู่หน้าสุด ชั้นถัดไปเลื่อนไปทางขวา
 * แต่ละชั้นมีขอบสีพื้น ([halo]) ให้เห็นรอยต่อระหว่างชั้น
 */
@Composable
private fun StackedIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, colors: List<Color>, layers: Int, halo: Color) {
    val n = minOf(layers, StackMaxLayers)
    Box(Modifier.width(stackWidth(n).dp).height(StackIcon.dp)) {
        for (i in (n - 1) downTo 0) {
            val tint = colors.getOrElse(i) { colors.last() }
            val x = (i * StackStep).dp
            // ขอบ: วาดไอคอนสีพื้นเลื่อนรอบตัว 1dp ก่อน แล้วค่อยวาดไอคอนสีจริงทับ
            if (i < n - 1 || n > 1) {
                for ((dx, dy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1, -1 to -1, 1 to 1, -1 to 1, 1 to -1)) {
                    Icon(icon, contentDescription = null, tint = halo,
                        modifier = Modifier.offset(x = x + dx.dp, y = dy.dp).size(StackIcon.dp))
                }
            }
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.offset(x = x).size(StackIcon.dp))
        }
    }
}

private val kindOrder = listOf(TaskKind.CLASS, TaskKind.WORK, TaskKind.BREAK, TaskKind.TASK)

@Composable
private fun MonthGrid(
    anchor: LocalDate,
    selected: LocalDate,
    days: Map<LocalDate, ScheduleDayWithTasks>,
    onTap: (LocalDate) -> Unit,
    onAddDay: (LocalDate) -> Unit,
    onAddRange: (LocalDate, LocalDate) -> Unit,
    onShiftMonth: (Boolean) -> Unit
) {
    val c = AppTheme.colors
    val look = LocalAppearance.current
    val selColor = look.dragColor
    val today = LocalDate.now()
    val first = Recurrence.weekStart(anchor.withDayOfMonth(1))

    // ---------- กดค้างแล้วลากเลือกช่วงวัน ----------
    // กดค้างแล้วปล่อยที่เดิม = เพิ่มกิจกรรมวันนั้น / ลากไปหลายวัน = เพิ่มกิจกรรมให้ทุกวันในช่วง
    // ลากถึงช่องมุมขวาล่าง (หรือเลยตาราง) = เดือนถัดไป / มุมซ้ายบน = เดือนก่อน
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var gridSize by remember { mutableStateOf(IntSize.Zero) }
    var dragFrom by remember { mutableStateOf<LocalDate?>(null) }
    var dragTo by remember { mutableStateOf<LocalDate?>(null) }
    var fingerPos by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    // หลังปล่อยนิ้ว: ค้างช่วงไว้ให้เห็นคลื่นกระเพื่อมก่อนเปิดหน้าต่าง
    var holding by remember { mutableStateOf(false) }
    var waveKey by remember { mutableStateOf(0) }
    val currentFirst by rememberUpdatedState(first)
    val addDay by rememberUpdatedState(onAddDay)
    val addRange by rememberUpdatedState(onAddRange)
    val shift by rememberUpdatedState(onShiftMonth)

    fun cellAt(pos: Offset): Pair<Int, Int> {
        val w = gridSize.width.coerceAtLeast(1) / 7f
        val h = gridSize.height.coerceAtLeast(1) / 6f
        return (pos.y / h).toInt().coerceIn(0, 5) to (pos.x / w).toInt().coerceIn(0, 6)
    }
    fun dateAt(pos: Offset): LocalDate {
        val (row, col) = cellAt(pos)
        return currentFirst.plusDays((row * 7 + col).toLong())
    }

    // มุมเปลี่ยนเดือน (ต้องลากออกจากวันแรกก่อน กันเลื่อนเดือนตั้งแต่เริ่มกด)
    val cornerDir = if (!dragging || dragTo == dragFrom && fingerPos.y in 0f..gridSize.height.toFloat()) 0 else {
        val (row, col) = cellAt(fingerPos)
        when {
            fingerPos.y > gridSize.height || (row == 5 && col == 6) -> 1
            fingerPos.y < 0f || (row == 0 && col == 0) -> -1
            else -> 0
        }
    }
    LaunchedEffect(cornerDir) {
        if (cornerDir == 0) return@LaunchedEffect
        delay(280)
        while (true) {
            shift(cornerDir > 0)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(40)
            dragTo = dateAt(fingerPos)
            delay(750)
        }
    }

    val active = dragging || holding
    val rangeStart = if (active) rangeStartOf(dragFrom, dragTo) else null
    val rangeEnd = if (active) rangeEndOf(dragFrom, dragTo) else null
    fun inRangeOf(d: LocalDate) = rangeStart != null && rangeEnd != null && !d.isBefore(rangeStart) && !d.isAfter(rangeEnd)

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(22.dp).padding(bottom = 4.dp), contentAlignment = Alignment.Center) {
            if (active && rangeStart != null && rangeEnd != null) {
                // ระหว่างลาก: แทนแถบชื่อวันด้วยสรุปช่วงที่เลือก
                val n = java.time.temporal.ChronoUnit.DAYS.between(rangeStart, rangeEnd) + 1
                val pillScale = remember { Animatable(0.85f) }
                LaunchedEffect(Unit) { pillScale.animateTo(1f, look.pop()) }
                Text(
                    if (n == 1L) "${shortDay(rangeStart)} · ปล่อยเพื่อเพิ่มกิจกรรม"
                    else "${shortDay(rangeStart)} – ${shortDay(rangeEnd)} · $n วัน",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = c.ground,
                    modifier = Modifier
                        .graphicsLayer { scaleX = pillScale.value; scaleY = pillScale.value }
                        .clip(RoundedCornerShape(8.dp)).background(selColor).padding(horizontal = 10.dp, vertical = 1.dp)
                )
            } else {
                Row(Modifier.fillMaxWidth()) {
                    for (i in 0..6) {
                        Text(
                            dayLetter(first.plusDays(i.toLong())),
                            style = MaterialTheme.typography.labelMedium,
                            color = c.muted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).clearAndSetSemantics { }
                        )
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { gridSize = it }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { pos ->
                            if (holding) return@detectDragGesturesAfterLongPress
                            val d = dateAt(pos)
                            dragFrom = d; dragTo = d; fingerPos = pos; dragging = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            if (!dragging) return@detectDragGesturesAfterLongPress
                            fingerPos += amount
                            val d = dateAt(fingerPos)
                            if (d != dragTo) {
                                dragTo = d
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onDragEnd = {
                            val a = rangeStartOf(dragFrom, dragTo)
                            val b = rangeEndOf(dragFrom, dragTo)
                            dragging = false
                            if (a == null || b == null) { dragFrom = null; dragTo = null; return@detectDragGesturesAfterLongPress }
                            // กระเพื่อมเป็นคลื่นจากวันแรกถึงวันสุดท้าย แล้วค่อยเปิดหน้าต่างเพิ่มกิจกรรม
                            holding = true
                            waveKey++
                            scope.launch {
                                val shown = java.time.temporal.ChronoUnit.DAYS.between(a, b).coerceAtMost(41)
                                delay(if (look.animate) 260L + shown * 18L else 0L)
                                if (a == b) addDay(a) else addRange(a, b)
                                holding = false; dragFrom = null; dragTo = null
                            }
                        },
                        onDragCancel = { dragging = false; dragFrom = null; dragTo = null }
                    )
                }
        ) {
            // เปลี่ยนเดือน: ตารางเลื่อนขึ้น/ลงนุ่มๆ
            AnimatedContent(
                targetState = first,
                transitionSpec = {
                    if (!look.animate) EnterTransition.None togetherWith ExitTransition.None
                    else {
                        val dir = if (targetState.isAfter(initialState)) 1 else -1
                        (slideInVertically(tween(260)) { dir * it / 6 } + fadeIn(tween(200))) togetherWith
                            (slideOutVertically(tween(180)) { -dir * it / 6 } + fadeOut(tween(150)))
                    }
                },
                label = "month"
            ) { gridFirst ->
                Column(Modifier.fillMaxWidth()) {
                    for (week in 0 until 6) {
                        Row(Modifier.fillMaxWidth()) {
                            for (col in 0..6) {
                                val d = gridFirst.plusDays((week * 7 + col).toLong())
                                MonthCell(
                                    date = d,
                                    inMonth = d.month == gridFirst.plusDays(14).month,
                                    tasks = days[d]?.tasks.orEmpty(),
                                    isSelected = d == selected && !active,
                                    isToday = d == today,
                                    inRange = inRangeOf(d),
                                    isEdge = d == rangeStart || d == rangeEnd,
                                    isHot = dragging && d == dragTo,
                                    waveKey = waveKey,
                                    waveDelay = if (rangeStart != null) java.time.temporal.ChronoUnit.DAYS.between(rangeStart, d).coerceAtLeast(0) * 18L else 0L,
                                    cornerHint = dragging && dragTo != dragFrom && week == 5 && col == 6,
                                    selColor = selColor,
                                    onTap = { if (!active) onTap(d) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            kindOrder.forEach { k ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(k.icon(), contentDescription = null, tint = k.color(), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Hint(k.label)
                }
            }
        }
    }
}

/**
 * หนึ่งช่องวันในตารางเดือน
 * ตอนลากเลือก: ทุกวันในช่วงมีกรอบมนของตัวเอง (สีจากตั้งค่า) เด้งเข้าตอนนิ้วผ่าน หดออกตอนหลุดช่วง
 * วันใต้นิ้วใหญ่ขึ้นนิดและกรอบหนากว่า / ปล่อยนิ้วแล้วกระเพื่อมเป็นคลื่น
 */
@Composable
private fun MonthCell(
    date: LocalDate,
    inMonth: Boolean,
    tasks: List<ScheduleTaskEntity>,
    isSelected: Boolean,
    isToday: Boolean,
    inRange: Boolean,
    isEdge: Boolean,
    isHot: Boolean,
    waveKey: Int,
    waveDelay: Long,
    cornerHint: Boolean,
    selColor: Color,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = AppTheme.colors
    val look = LocalAppearance.current
    // ไอคอนใต้วันที่: ไอคอนเดียวกันรวมเป็นกอง แต่ละชั้นคือหนึ่งสี เรียงตามเวลา
    val marks = tasks.sortedBy { it.startMin }
        .map { it.iconVector() to it.accent() }
        .groupBy({ it.first }, { it.second })
        .map { (icon, colors) -> icon to colors.distinct() }
    val kindsUsed = kindOrder.filter { k -> tasks.any { it.kind == k.code } }

    // 0 = ไม่อยู่ในช่วง, 1 = อยู่ในช่วง (สปริงเกิน 1 นิดหนึ่ง = เด้ง)
    val sel = remember { Animatable(0f) }
    LaunchedEffect(inRange) { sel.animateTo(if (inRange) 1f else 0f, if (inRange) look.pop() else look.out()) }
    val hot by animateFloatAsState(if (isHot) 1f else 0f, look.pop(), label = "hot")
    val wave = remember { Animatable(0f) }
    LaunchedEffect(waveKey) {
        if (waveKey > 0 && inRange && look.animate) {
            delay(waveDelay)
            wave.animateTo(1f, tween(140))
            wave.animateTo(0f, look.pop())
        }
    }
    val selBg by animateColorAsState(if (isSelected) c.ink else Color.Transparent, tween(150), label = "cellBg")
    val corner = if (cornerHint && look.animate) {
        val t = rememberInfiniteTransition(label = "corner")
        t.animateFloat(0.35f, 0.9f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "cornerA").value
    } else if (cornerHint) 0.7f else 0f

    val summary = if (tasks.isEmpty()) "ว่าง" else "${tasks.size} รายการ " + kindsUsed.joinToString(" ") { it.label }
    Box(
        modifier
            .heightIn(min = 56.dp)
            .padding(2.dp)
            .selectable(selected = isSelected, role = Role.Button, onClick = onTap)
            .semantics { contentDescription = "${date.format(longDate)} $summary" + if (isToday) " วันนี้" else "" }
    ) {
        // ชั้นไฮไลต์ตอนลากเลือก (กรอบทุกช่อง)
        val p = sel.value
        if (p > 0.001f) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val s = (0.82f + 0.18f * p) * (1f + 0.09f * hot) * (1f + 0.12f * wave.value)
                        scaleX = s; scaleY = s
                        alpha = p.coerceIn(0f, 1f)
                    }
                    .clip(RoundedCornerShape(12.dp))
                    .background(selColor.copy(alpha = 0.14f + 0.10f * hot + 0.14f * wave.value))
                    .border(
                        (1.6f + (if (isEdge) 0.6f else 0f) + 1.0f * hot).dp,
                        selColor.copy(alpha = 0.85f),
                        RoundedCornerShape(12.dp)
                    )
            )
        }
        // วันที่เลือกอยู่ (ไม่ได้ลาก)
        Box(Modifier.matchParentSize().clip(RoundedCornerShape(12.dp)).background(selBg))
        // มุมเปลี่ยนเดือน: กรอบเส้นประกะพริบเบาๆ
        if (corner > 0f) {
            Box(
                Modifier.matchParentSize().drawBehind {
                    drawRoundRect(
                        color = selColor.copy(alpha = corner),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                        )
                    )
                }
            )
        }
        Column(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .then(if (isToday && !isSelected && !inRange) Modifier.border(1.5.dp, c.now, CircleShape) else Modifier),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${date.dayOfMonth}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected || isToday || isHot) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        isSelected -> c.ground
                        !inMonth -> c.muted.copy(alpha = 0.55f)
                        else -> c.ink
                    }
                )
            }
            Spacer(Modifier.height(4.dp))
            // กองไอคอนเรียงในช่อง ถ้าไม่พอที่แสดง "+จำนวนกองที่เหลือ"
            val fit = fitStacks(marks.map { it.second.size }, budget = 40)
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(16.dp)
            ) {
                marks.take(fit).forEach { (icon, colors) ->
                    StackedIcon(
                        icon = icon,
                        colors = if (isSelected) listOf(c.ground) else colors,
                        layers = colors.size,
                        halo = if (isSelected) c.ink else c.ground
                    )
                }
                if (fit < marks.size) {
                    Text(
                        "+${marks.size - fit}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) c.ground else c.muted,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** รายการของวันที่เลือก ใต้ตารางเดือน */
@Composable
private fun DayAgenda(
    date: LocalDate,
    tasks: List<ScheduleTaskEntity>,
    loading: Boolean,
    onOpenDay: () -> Unit,
    onOpenTask: (ScheduleTaskEntity) -> Unit
) {
    SectionTitle(date.format(longDate)) {
        TextButton(onClick = onOpenDay) { Text("ดูรายวัน") }
    }
    when {
        loading -> LoadingRow()
        tasks.isEmpty() -> Hint("ว่าง แตะ \"เพิ่ม\" เพื่อใส่กิจกรรมของวันนี้")
        else -> Panel(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            tasks.forEachIndexed { i, t ->
                TimeRow(
                    title = t.title, kind = TaskKind.from(t.kind), startMin = t.startMin, endMin = t.endMin,
                    reminder = t.reminderEnabled, showDivider = i > 0, subtitle = t.description, color = t.color, icon = t.icon, categoryId = t.categoryId,
                    onClick = { onOpenTask(t) }
                )
            }
        }
    }
}

// ============================================================================
// Week view (agenda: one block per day)
// ============================================================================

@Composable
private fun WeekAgenda(
    start: LocalDate,
    selected: LocalDate,
    days: Map<LocalDate, ScheduleDayWithTasks>,
    loading: Boolean,
    onOpenDay: (LocalDate) -> Unit,
    onOpenTask: (ScheduleTaskEntity, LocalDate) -> Unit
) {
    val c = AppTheme.colors
    val today = LocalDate.now()
    if (loading && days.isEmpty()) { LoadingRow(); return }
    for (i in 0..6) {
        val d = start.plusDays(i.toLong())
        val tasks = days[d].sortedTasks()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = if (i == 0) 4.dp else 16.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = "ดูรายวัน") { onOpenDay(d) }
                .heightIn(min = 48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (d == selected) c.ink else if (d == today) c.surface else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${d.dayOfMonth}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (d == selected) c.ground else c.ink
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(DayNamesFull[Recurrence.column(d.dayOfWeek)] + if (d == today) "  วันนี้" else "", style = MaterialTheme.typography.titleSmall)
                Hint(if (tasks.isEmpty()) "ว่าง" else "${tasks.size} รายการ  ${rangeText(tasks.first().startMin, tasks.maxOf { it.endMin })}")
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = c.muted)
        }
        if (tasks.isNotEmpty()) {
            Panel(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                tasks.forEachIndexed { j, t ->
                    TimeRow(
                        title = t.title, kind = TaskKind.from(t.kind), startMin = t.startMin, endMin = t.endMin,
                        reminder = t.reminderEnabled, showDivider = j > 0, subtitle = t.description, color = t.color, icon = t.icon, categoryId = t.categoryId,
                        onClick = { onOpenTask(t, d) }
                    )
                }
            }
        }
    }
}

private val DayNamesFull = listOf("วันอาทิตย์", "วันจันทร์", "วันอังคาร", "วันพุธ", "วันพฤหัสบดี", "วันศุกร์", "วันเสาร์")

@Composable
private fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator(color = AppTheme.colors.ink) }
}

// ============================================================================
// Day view
// ============================================================================

@Composable
private fun DayView(
    state: DashboardUiState,
    nowMin: Int,
    onSelect: (LocalDate) -> Unit,
    onReset: () -> Unit,
    onToggle: (ScheduleTaskEntity) -> Unit,
    onOpenTask: (ScheduleTaskEntity) -> Unit,
    onAdd: () -> Unit
) {
    val isToday = state.date == LocalDate.now()
    val tasks = state.day.sortedTasks()
    WeekStrip(state.date, onSelect = onSelect)
    Spacer(Modifier.height(18.dp))
    StatusHeader(state.date, tasks, isToday, nowMin)
    Spacer(Modifier.height(20.dp))

    if (state.day?.day?.isCustomized == true) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AppTheme.colors.taskBg)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("วันนี้ปรับเองแล้ว กิจวัตรมาตรฐานจะไม่มาเปลี่ยนวันนี้", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onReset) { Text("ใช้ตามกิจวัตร") }
        }
        Spacer(Modifier.height(12.dp))
    }

    when {
        state.loading -> LoadingRow()
        tasks.isEmpty() -> EmptyState(
            icon = Icons.Filled.EventAvailable,
            title = "วันนี้ว่าง",
            subtitle = "วันนี้ไม่อยู่ในวันหรือเดือนที่กิจวัตรมาตรฐานทำซ้ำ เพิ่มกิจกรรมเฉพาะวันนี้ได้",
            actionLabel = "เพิ่มกิจกรรม",
            onAction = onAdd
        )
        else -> DayTimeline(
            tasks = tasks,
            wakeMin = state.day?.day?.let { it.wakeUpHour * 60 + it.wakeUpMinute },
            nowMin = if (isToday) nowMin else null,
            onToggle = onToggle,
            onOpen = onOpenTask
        )
    }
}

/** หัวรายวัน: บอกว่าเหลือเวลาเท่าไรก่อนจบช่วงปัจจุบัน หรือก่อนเริ่มช่วงถัดไป */
@Composable
private fun StatusHeader(date: LocalDate, tasks: List<ScheduleTaskEntity>, isToday: Boolean, nowMin: Int) {
    val c = AppTheme.colors
    val current = if (isToday) tasks.firstOrNull { nowMin in it.startMin until it.endMin } else null
    val next = if (isToday) tasks.firstOrNull { it.startMin > nowMin } else null
    val done = tasks.count { it.status == "DONE" }
    val currentColor = current?.accent() ?: c.ink
    val nextColor = next?.accent() ?: c.ink

    val line = buildAnnotatedString {
        when {
            tasks.isEmpty() -> append("ยังไม่มีกิจกรรม")
            !isToday -> append("${tasks.size} รายการ ตั้งแต่ ${rangeText(tasks.first().startMin, tasks.maxOf { it.endMin })}")
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
    val big = when {
        !isToday || tasks.isEmpty() -> null
        current != null -> durationText(current.endMin - nowMin)
        next != null -> "อีก ${durationText(next.startMin - nowMin)}"
        else -> "เสร็จ $done จาก ${tasks.size}"
    }

    Column {
        big?.let { Text(it, style = MaterialTheme.typography.displaySmall, color = c.ink) }
        Text(line, style = MaterialTheme.typography.bodyLarge, color = c.ink)
        if (tasks.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.clearAndSetSemantics { }) {
                tasks.forEach { t ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (t.status == "DONE") c.ink else c.outline.copy(alpha = 0.45f))
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Hint("ทำเสร็จ $done จาก ${tasks.size} รายการ")
        }
    }
}

/** แถบ 7 วันของสัปดาห์ในมุมมองรายวัน (เลื่อนสัปดาห์ด้วย ‹ › ด้านบน) */
@Composable
private fun WeekStrip(selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    val c = AppTheme.colors
    val sunday = Recurrence.weekStart(selected)
    val today = LocalDate.now()
    Row(Modifier.fillMaxWidth().selectableGroup()) {
        for (i in 0..6) {
            val d = sunday.plusDays(i.toLong())
            val on = d == selected
            val stripBg by animateColorAsState(if (on) c.ink else Color.Transparent, tween(150), label = "stripBg")
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 2.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(stripBg)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(d) })
                    .semantics { contentDescription = d.format(longDate) + if (d == today) " วันนี้" else "" }
                    .heightIn(min = 56.dp)
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(dayLetter(d), style = MaterialTheme.typography.labelSmall, color = if (on) c.ground.copy(alpha = 0.8f) else c.muted)
                Text("${d.dayOfMonth}", style = MaterialTheme.typography.titleMedium, color = if (on) c.ground else c.ink)
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(if (d == today && !on) c.now else Color.Transparent)
                )
            }
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
    val accent = task.accent()
    val fill = task.fill()
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
                .padding(start = 14.dp, end = 2.dp, top = if (thin) 0.dp else 10.dp, bottom = if (thin) 0.dp else 10.dp)
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
                    maxLines = if (thin) 1 else 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!thin) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(task.iconVector(), contentDescription = task.categoryLabel(), tint = accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Hint("${rangeText(task.startMin, task.endMin)}  ${task.description ?: durationText(task.durationMinutes)}")
                }
            }
            if (thin) {
                Text(
                    rangeText(task.startMin, task.endMin),
                    style = if (tiny) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                    color = c.muted
                )
                Spacer(Modifier.width(8.dp))
            }
            // พื้นที่กดกว้าง 48dp (ช่วงสั้นมากติ๊กได้จากหน้าต่างแก้ไขด้วย)
            Box(
                Modifier
                    .width(48.dp)
                    .then(if (thin) Modifier.fillMaxHeight() else Modifier.height(48.dp))
                    .toggleable(value = done, role = Role.Checkbox, onValueChange = { onToggle() })
                    .semantics { contentDescription = "ทำเสร็จ ${task.title}" },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(if (tiny) 14.dp else if (thin) 20.dp else 26.dp)
                        .clip(CircleShape)
                        .background(if (done) c.ink else Color.Transparent)
                        .then(if (!done) Modifier.drawBehind {
                            drawCircle(c.outline, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                        } else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    if (done) Icon(Icons.Filled.Check, contentDescription = null, tint = c.ground, modifier = Modifier.size(if (tiny) 10.dp else if (thin) 13.dp else 16.dp))
                }
            }
        }
    }
}
