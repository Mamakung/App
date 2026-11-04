package com.example.smartscheduler.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartscheduler.data.local.entity.ScheduleTaskEntity
import com.example.smartscheduler.data.repository.ScheduleRepository
import com.example.smartscheduler.data.session.CurrentUser
import com.example.smartscheduler.domain.model.Recurrence
import com.example.smartscheduler.ui.components.*
import com.example.smartscheduler.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
import java.time.LocalDate
import javax.inject.Inject

// ============================================================================
// ViewModel
// ============================================================================

enum class ImportStage { PICK, READING, REVIEW, SAVING }

data class ImportUiState(
    val stage: ImportStage = ImportStage.PICK,
    val fileName: String = "",
    val entries: List<TimetableEntry> = emptyList(),
    val aiNote: String = "",
    val semesterStart: LocalDate = LocalDate.now(),
    val studyWeeks: Int = SemesterStore.DEFAULT_WEEKS,
    /** วิชา (รหัสวิชา หรือชื่อ ถ้าไม่มีรหัส) ที่สอบในสัปดาห์เรียนสุดท้าย จึงเรียนถึงสัปดาห์ก่อนหน้า */
    val examInLastWeek: Set<String> = emptySet(),
    val replaceExisting: Boolean = true,
    val hasApiKey: Boolean = false,
    val askApiKey: Boolean = false,
    val error: String? = null,
    val savedDays: Int? = null
) {
    val semester: Semester get() = Semester(semesterStart, studyWeeks)

    /** วันที่ผ่านไปแล้วไม่แก้ เริ่มลงตั้งแต่วันนี้ (หรือวันเปิดภาคถ้ายังไม่ถึง) */
    val importFrom: LocalDate get() = maxOf(LocalDate.now(), semesterStart)

    fun untilFor(e: TimetableEntry): LocalDate =
        if (e.subjectKey() in examInLastWeek) semester.weekEnd(studyWeeks - 1) else semester.lastStudyDay

    /** วิชาที่มีรหัส เลือกได้ว่าสอบในสัปดาห์เรียนสุดท้าย */
    val subjects: List<Pair<String, String>>
        get() = entries.filter { it.code.isNotBlank() }
            .distinctBy { it.subjectKey() }
            .map { it.subjectKey() to it.title }

    val activeDays: Int
        get() {
            val dates = mutableSetOf<LocalDate>()
            for (e in entries) {
                var d = importFrom
                val last = untilFor(e)
                while (!d.isAfter(last)) {
                    if (Recurrence.dayCode(d.dayOfWeek) == e.day) dates += d
                    d = d.plusDays(1)
                }
            }
            return dates.size
        }
}

fun TimetableEntry.subjectKey(): String = code.ifBlank { title }

@HiltViewModel
class ImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ai: AiTimetableReader,
    private val repo: ScheduleRepository,
    private val user: CurrentUser,
    private val semesters: SemesterStore
) : ViewModel() {

    private fun freshState(): ImportUiState {
        val saved = semesters.load()
        return ImportUiState(
            hasApiKey = ai.hasApiKey,
            semesterStart = saved?.start ?: LocalDate.now(),
            studyWeeks = saved?.studyWeeks ?: SemesterStore.DEFAULT_WEEKS
        )
    }

    private val _state = MutableStateFlow(freshState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    private var pendingUri: Uri? = null

    fun onFilePicked(uri: Uri) {
        val name = displayName(uri)
        val mime = context.contentResolver.getType(uri).orEmpty()
        val isText = mime.startsWith("text/") || name.endsWith(".csv", true) || name.endsWith(".tsv", true) || name.endsWith(".txt", true)
        if (!isText && !ai.hasApiKey) {
            pendingUri = uri
            _state.update { it.copy(askApiKey = true, fileName = name) }
            return
        }
        read(uri, name, mime, isText)
    }

    private fun read(uri: Uri, name: String, mime: String, isText: Boolean) {
        _state.update { it.copy(stage = ImportStage.READING, fileName = name, error = null, aiNote = "") }
        viewModelScope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw TimetableParseException("เปิดไฟล์ไม่ได้")
                if (isText) {
                    CsvTimetableParser.parse(decodeText(bytes)) to ""
                } else {
                    val type = when {
                        mime == "application/pdf" || name.endsWith(".pdf", true) -> "application/pdf"
                        else -> "image/*"
                    }
                    ai.read(bytes, type).let { it.entries to it.note }
                }
            }.onSuccess { (entries, note) ->
                _state.update { it.copy(stage = ImportStage.REVIEW, entries = entries, aiNote = note) }
            }.onFailure { e ->
                val msg = (e as? TimetableParseException)?.message ?: "อ่านไฟล์ไม่สำเร็จ ตรวจอินเทอร์เน็ตแล้วลองอีกครั้ง"
                _state.update { it.copy(stage = ImportStage.PICK, error = msg) }
            }
        }
    }

    fun saveApiKey(key: String) {
        ai.apiKey = key
        _state.update { it.copy(askApiKey = false, hasApiKey = ai.hasApiKey) }
        pendingUri?.let { uri ->
            pendingUri = null
            if (ai.hasApiKey) onFilePicked(uri)
        }
    }

    fun openApiKey() = _state.update { it.copy(askApiKey = true) }
    fun closeApiKey() { pendingUri = null; _state.update { it.copy(askApiKey = false) } }
    fun currentApiKey(): String = ai.apiKey

    fun setSemesterStart(d: LocalDate) = _state.update { it.copy(semesterStart = d) }
    fun changeWeeks(delta: Int) = _state.update { it.copy(studyWeeks = (it.studyWeeks + delta).coerceIn(2, 30)) }
    fun toggleExamInLastWeek(key: String) = _state.update {
        it.copy(examInLastWeek = if (key in it.examInLastWeek) it.examInLastWeek - key else it.examInLastWeek + key)
    }
    fun setReplace(v: Boolean) = _state.update { it.copy(replaceExisting = v) }
    fun dismissError() = _state.update { it.copy(error = null) }
    fun restart() = _state.update { freshState() }

    fun updateEntry(old: TimetableEntry, draft: TaskDraft) = _state.update { s ->
        s.copy(entries = s.entries.map {
            if (it === old) it.copy(title = draft.title.trim(), startMin = draft.startMin, endMin = draft.endMin, kind = draft.kind) else it
        })
    }

    fun removeEntry(e: TimetableEntry) = _state.update { s -> s.copy(entries = s.entries.filterNot { it === e }) }

    fun save() {
        val s = _state.value
        if (s.entries.isEmpty()) return
        _state.update { it.copy(stage = ImportStage.SAVING) }
        viewModelScope.launch {
            semesters.save(s.semester)
            val items = s.entries.sortedBy { it.startMin }.mapIndexed { i, e ->
                ScheduleRepository.WeeklyItem(
                    day = e.day,
                    until = s.untilFor(e),
                    task = ScheduleTaskEntity(
                        dayId = 0,
                        title = e.title,
                        description = e.detail().ifBlank { null },
                        startHour = e.startMin / 60,
                        startMinute = e.startMin % 60,
                        durationMinutes = e.endMin - e.startMin,
                        sortOrder = 100 + i,
                        kind = e.kind.code
                    )
                )
            }
            runCatching { repo.importWeekly(user.uid, items, s.importFrom, s.replaceExisting) }
                .onSuccess { days -> _state.update { it.copy(stage = ImportStage.REVIEW, savedDays = days) } }
                .onFailure { _state.update { it.copy(stage = ImportStage.REVIEW, error = "บันทึกไม่สำเร็จ ลองอีกครั้ง") } }
        }
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()

    /** CSV จาก Excel ภาษาไทยอาจเป็น UTF-8 หรือ Windows-874 */
    private fun decodeText(bytes: ByteArray): String {
        val utf8 = bytes.toString(Charsets.UTF_8)
        if ('�' !in utf8) return utf8
        return runCatching { String(bytes, Charset.forName("windows-874")) }.getOrDefault(utf8)
    }
}

// ============================================================================
// Screen
// ============================================================================

private val pickTypes = arrayOf(
    "image/*", "application/pdf", "text/csv", "text/comma-separated-values",
    "text/tab-separated-values", "text/plain"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onBack: () -> Unit, onDone: () -> Unit, viewModel: ImportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val c = AppTheme.colors
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onFilePicked)
    }
    var editing by remember { mutableStateOf<TimetableEntry?>(null) }

    Scaffold(
        containerColor = c.ground,
        topBar = {
            TopAppBar(
                title = { Text("นำเข้าตารางจากไฟล์", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "ย้อนกลับ") }
                },
                actions = {
                    IconButton(onClick = viewModel::openApiKey) {
                        Icon(Icons.Filled.Key, contentDescription = "ตั้งค่า API key สำหรับ AI", tint = c.muted)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.ground)
            )
        },
        bottomBar = {
            if (state.stage == ImportStage.REVIEW || state.stage == ImportStage.SAVING) {
                Surface(color = c.surface) {
                    Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
                        if (state.savedDays != null) {
                            Text("ลงตารางให้ ${state.savedDays} วันแล้ว", style = MaterialTheme.typography.bodyMedium, color = c.muted)
                            Spacer(Modifier.height(10.dp))
                            InkButton("ไปที่หน้าวันนี้", onClick = onDone)
                        } else {
                            Text(
                                "${state.entries.size} รายการต่อสัปดาห์ ลงให้ ${state.activeDays} วัน",
                                style = MaterialTheme.typography.bodyMedium, color = c.muted
                            )
                            Spacer(Modifier.height(10.dp))
                            InkButton(
                                text = if (state.stage == ImportStage.SAVING) "กำลังบันทึก…" else "บันทึกลงตาราง",
                                onClick = viewModel::save,
                                enabled = state.stage == ImportStage.REVIEW && state.entries.isNotEmpty() && state.activeDays > 0
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
        ) {
            when (state.stage) {
                ImportStage.PICK -> item { PickStep(state, onPick = { picker.launch(pickTypes) }) }
                ImportStage.READING -> item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = c.ink)
                        Spacer(Modifier.height(16.dp))
                        Text("กำลังอ่าน ${state.fileName}", style = MaterialTheme.typography.bodyLarge)
                        Hint("รูปหรือ PDF ใช้เวลาประมาณ 10–40 วินาที")
                    }
                }
                ImportStage.REVIEW, ImportStage.SAVING -> {
                    item {
                        Text("ตรวจก่อนบันทึก", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "จาก ${state.fileName}  แตะรายการเพื่อแก้ชื่อหรือเวลา",
                            style = MaterialTheme.typography.bodyMedium, color = c.muted
                        )
                        if (state.aiNote.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "AI บอกว่า: ${state.aiNote}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.taskBg).padding(12.dp)
                            )
                        }
                        SemesterSection(state, viewModel)
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { viewModel.setReplace(!state.replaceExisting) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("แทนที่ตารางเดิมในวันที่มีเรียน", style = MaterialTheme.typography.bodyLarge)
                                Hint(
                                    if (state.replaceExisting) "ช่วงทำงาน/พักของกิจวัตรในวันเหล่านั้นจะถูกแทนด้วยตารางนี้"
                                    else "เพิ่มต่อท้ายรายการเดิม อาจมีเวลาซ้อนกัน"
                                )
                            }
                            Switch(checked = state.replaceExisting, onCheckedChange = viewModel::setReplace)
                        }
                    }
                    val grouped = state.entries.groupBy { it.day }
                    Recurrence.dayCodes.filter { it in grouped }.forEach { day ->
                        item(key = "day-$day") {
                            SectionTitle(DayNames.labelTh(day))
                            Panel(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                                grouped.getValue(day).sortedBy { it.startMin }.forEachIndexed { i, e ->
                                    TimeRow(
                                        title = e.title,
                                        kind = e.kind,
                                        startMin = e.startMin,
                                        endMin = e.endMin,
                                        reminder = true,
                                        showDivider = i > 0,
                                        subtitle = e.detail().ifBlank { null },
                                        onClick = { editing = e }
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(16.dp))
                        TextButton(onClick = viewModel::restart) { Text("เลือกไฟล์อื่น") }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    state.error?.let { msg ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            containerColor = c.surface,
            title = { Text("นำเข้าไม่สำเร็จ") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("ตกลง") } }
        )
    }

    if (state.askApiKey) {
        ApiKeyDialog(initial = viewModel.currentApiKey(), onSave = viewModel::saveApiKey, onDismiss = viewModel::closeApiKey)
    }

    editing?.let { e ->
        TaskEditorDialog(
            title = "แก้รายการ",
            initial = TaskDraft(e.title, e.kind, e.startMin, e.endMin, true),
            footnote = e.detail().ifBlank { null },
            onSave = { viewModel.updateEntry(e, it); editing = null },
            onDelete = { viewModel.removeEntry(e); editing = null },
            onDismiss = { editing = null }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SemesterSection(state: ImportUiState, viewModel: ImportViewModel) {
    val c = AppTheme.colors
    val sem = state.semester
    SectionTitle("ภาคเรียน")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        DateButton("วันเปิดภาค", state.semesterStart, Modifier.weight(1f), viewModel::setSemesterStart)
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(c.surface)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.changeWeeks(-1) }) { Text("−", style = MaterialTheme.typography.titleLarge) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Hint("เรียน")
                Text("${state.studyWeeks} สัปดาห์", style = MaterialTheme.typography.titleSmall)
            }
            IconButton(onClick = { viewModel.changeWeeks(1) }) { Text("+", style = MaterialTheme.typography.titleLarge) }
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        "เรียนถึง ${thaiShortDate(sem.lastStudyDay)}  สอบปลายภาคสัปดาห์ที่ ${sem.examWeek} " +
            "(${sem.weekStart(sem.examWeek).dayOfMonth}–${thaiShortDate(sem.weekEnd(sem.examWeek))})",
        style = MaterialTheme.typography.bodyMedium
    )
    sem.weekOf(LocalDate.now())?.takeIf { state.semesterStart.isBefore(LocalDate.now()) }?.let { w ->
        Hint("ตอนนี้สัปดาห์ที่ $w จะลงตารางตั้งแต่วันนี้ วันที่ผ่านมาแล้วไม่แก้")
    }

    if (state.subjects.isNotEmpty()) {
        SectionTitle("วิชาที่สอบในสัปดาห์ที่ ${state.studyWeeks}")
        Hint("วิชาที่เลือกจะเรียนถึงสัปดาห์ที่ ${state.studyWeeks - 1} สัปดาห์ที่ ${state.studyWeeks} เว้นช่วงนั้นไว้ให้ใส่เวลาสอบเอง")
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.subjects.forEach { (key, title) ->
                FilterChip(
                    selected = key in state.examInLastWeek,
                    onClick = { viewModel.toggleExamInLastWeek(key) },
                    label = { Text(title, maxLines = 1) }
                )
            }
        }
    }
}

@Composable
private fun PickStep(state: ImportUiState, onPick: () -> Unit) {
    val c = AppTheme.colors
    Column {
        Text("ให้แอปจัดตารางจากไฟล์", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "เลือกรูปถ่ายหรือ PDF ของตารางเรียน ตารางงาน หรือตารางเวร แอปจะอ่านทุกช่อง แล้วลงให้ทุกสัปดาห์ตลอดช่วงที่เลือก คุณตรวจและแก้ได้ก่อนบันทึก",
            style = MaterialTheme.typography.bodyMedium, color = c.muted
        )
        Spacer(Modifier.height(20.dp))
        InkButton("เลือกไฟล์ตาราง", onClick = onPick)
        Spacer(Modifier.height(24.dp))
        Panel {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Image, contentDescription = null, tint = c.study)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("รูปภาพหรือ PDF", style = MaterialTheme.typography.titleSmall)
                    Hint(
                        if (state.hasApiKey) "AI อ่านตารางให้ ใช้อินเทอร์เน็ตและ API key ที่ใส่ไว้"
                        else "AI อ่านตารางให้ ต้องใส่ Claude API key ครั้งแรกที่ใช้ (มีค่าใช้จ่ายตามการใช้งาน)"
                    )
                }
            }
            HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 14.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Description, contentDescription = null, tint = c.work)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("ไฟล์ CSV", style = MaterialTheme.typography.titleSmall)
                    Hint("อ่านในเครื่อง ไม่ต้องใช้ AI คอลัมน์: วัน, เริ่ม, สิ้นสุด, วิชา, รหัสวิชา, ห้อง, ครู")
                }
            }
        }
    }
}

@Composable
private fun ApiKeyDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppTheme.colors.surface,
        title = { Text("Claude API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "ใช้ให้ AI อ่านรูปหรือ PDF ของตาราง สร้าง key ได้ที่ console.anthropic.com คิดค่าใช้จ่ายตามการใช้งาน (อ่านตารางหนึ่งรูปประมาณไม่กี่บาท)",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.trim() },
                    label = { Text("API key") },
                    placeholder = { Text("sk-ant-...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Hint("เก็บไว้ในเครื่องนี้เท่านั้น")
            }
        },
        confirmButton = { TextButton(enabled = key.isNotBlank(), onClick = { onSave(key) }) { Text("บันทึก") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    )
}
