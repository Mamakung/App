package com.example.smartscheduler.ui.screens.notes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartscheduler.data.local.entity.NoteEntity
import com.example.smartscheduler.data.local.entity.NoteFolderEntity
import com.example.smartscheduler.data.repository.NoteDoc
import com.example.smartscheduler.data.repository.NoteRepository
import com.example.smartscheduler.ui.components.*
import com.example.smartscheduler.ui.theme.AppTheme
import com.example.smartscheduler.ui.theme.TaskPalette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class NoteSort(val label: String) {
    UPDATED("แก้ไขล่าสุด"), CREATED("สร้างล่าสุด"), NAME_ASC("ชื่อ ก–ฮ / A–Z"), NAME_DESC("ชื่อ ฮ–ก / Z–A"), KIND("ประเภท (บันทึกก่อน แล้ว PDF)")
}

enum class NoteFilter(val label: String) { ALL("ทั้งหมด"), NOTE("บันทึก"), PDF("PDF") }

data class NotebookState(
    val folders: List<NoteFolderEntity> = emptyList(),
    val notes: List<NoteEntity> = emptyList(),
    val query: String = "",
    val sort: NoteSort = NoteSort.UPDATED,
    val filter: NoteFilter = NoteFilter.ALL
)

private val thaiCollator: java.text.Collator = java.text.Collator.getInstance(java.util.Locale("th", "TH"))

/** เรียงบันทึก/PDF ตามที่เลือก */
private fun List<NoteEntity>.sortedNotes(sort: NoteSort): List<NoteEntity> {
    val byName = Comparator<NoteEntity> { a, b -> thaiCollator.compare(a.title.ifBlank { "\uFFFF" }, b.title.ifBlank { "\uFFFF" }) }
    return when (sort) {
        NoteSort.UPDATED -> sortedByDescending { it.updatedAt }
        NoteSort.CREATED -> sortedByDescending { it.createdAt }
        NoteSort.NAME_ASC -> sortedWith(byName)
        NoteSort.NAME_DESC -> sortedWith(byName.reversed())
        NoteSort.KIND -> sortedWith(compareBy<NoteEntity> { if (it.kind == "PDF") 1 else 0 }.thenByDescending { it.updatedAt })
    }
}

/** เรียงโฟลเดอร์: แก้ไขล่าสุด = ตามบันทึกข้างในที่แก้ล่าสุด */
private fun List<NoteFolderEntity>.sortedFolders(sort: NoteSort, notes: List<NoteEntity>): List<NoteFolderEntity> {
    val byName = Comparator<NoteFolderEntity> { a, b -> thaiCollator.compare(a.name, b.name) }
    return when (sort) {
        NoteSort.UPDATED -> {
            val last = notes.groupBy { it.folderId }.mapValues { (_, l) -> l.maxOf { it.updatedAt } }
            sortedByDescending { maxOf(last[it.folderId] ?: 0L, it.createdAt) }
        }
        NoteSort.CREATED -> sortedByDescending { it.createdAt }
        NoteSort.NAME_DESC -> sortedWith(byName.reversed())
        else -> sortedWith(byName)
    }
}

private fun List<NoteEntity>.filtered(f: NoteFilter) = when (f) {
    NoteFilter.ALL -> this
    NoteFilter.NOTE -> filter { it.kind != "PDF" }
    NoteFilter.PDF -> filter { it.kind == "PDF" }
}

@HiltViewModel
class NotebookViewModel @Inject constructor(
    private val repo: NoteRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
    settings: com.example.smartscheduler.settings.AppSettings,
    savedState: SavedStateHandle
) : ViewModel() {
    val fabStyle = settings.fabStyle
    /** โฟลเดอร์ที่เปิดอยู่ (null = หน้าสมุด) */
    val folderId: Long? = savedState.get<Long>("id")?.takeIf { it >= 0 }
    private val query = MutableStateFlow("")
    private val prefs = context.getSharedPreferences("notebook", android.content.Context.MODE_PRIVATE)
    private val view = MutableStateFlow(
        (runCatching { NoteSort.valueOf(prefs.getString("sort", null) ?: "") }.getOrNull() ?: NoteSort.UPDATED) to
            (runCatching { NoteFilter.valueOf(prefs.getString("filter", null) ?: "") }.getOrNull() ?: NoteFilter.ALL)
    )

    val state: StateFlow<NotebookState> = combine(repo.observeFolders(), repo.observeNotes(), query, view) { f, n, q, v ->
        NotebookState(f, n, q, v.first, v.second)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotebookState())

    fun setQuery(q: String) { query.value = q }
    fun setSort(s: NoteSort) { view.value = s to view.value.second; prefs.edit().putString("sort", s.name).apply() }
    fun setFilter(f: NoteFilter) { view.value = view.value.first to f; prefs.edit().putString("filter", f.name).apply() }

    // ---------- นำเข้า PDF ----------
    suspend fun inspectPdf(uri: android.net.Uri) = repo.inspectPdf(uri)
    /** ความคืบหน้า: (ไฟล์ที่, จากทั้งหมด, สัดส่วนของไฟล์นั้น) */
    private val _importProgress = MutableStateFlow<Triple<Int, Int, Float>?>(null)
    val importProgress: StateFlow<Triple<Int, Int, Float>?> = _importProgress.asStateFlow()
    /** นำเข้าทีละไฟล์ คืนรายการ id ที่สำเร็จ */
    fun importPdfs(items: List<Triple<android.net.Uri, String, NoteRepository.PdfInfo>>, folder: Long?, onDone: (List<Long>, Int) -> Unit) = viewModelScope.launch {
        val ok = mutableListOf<Long>()
        items.forEachIndexed { i, (uri, title, info) ->
            _importProgress.value = Triple(i + 1, items.size, 0f)
            repo.importPdf(uri, title, folder, info) { _importProgress.value = Triple(i + 1, items.size, it) }?.let { ok += it }
        }
        _importProgress.value = null
        onDone(ok, items.size - ok.size)
    }
    fun saveFolder(f: NoteFolderEntity, onSaved: (Long) -> Unit = {}) = viewModelScope.launch { onSaved(repo.saveFolder(f)) }
    fun deleteFolder(id: Long) = viewModelScope.launch { repo.deleteFolder(id) }
    fun deleteNote(id: Long) = viewModelScope.launch { repo.deleteNote(id) }
    fun deleteMany(notes: Set<Long>, folders: Set<Long>) = viewModelScope.launch {
        notes.forEach { repo.deleteNote(it) }; folders.forEach { repo.deleteFolder(it) }
    }
    fun moveMany(notes: Set<Long>, folders: Set<Long>, to: Long?) = viewModelScope.launch {
        notes.forEach { repo.moveNote(it, to) }; folders.forEach { repo.moveFolder(it, to) }
    }
    fun moveNote(id: Long, to: Long?) = viewModelScope.launch { repo.moveNote(id, to) }
    fun moveFolder(id: Long, to: Long?) = viewModelScope.launch { repo.moveFolder(id, to) }
}

/** จำนวนรายการในโฟลเดอร์ (บันทึก/PDF + โฟลเดอร์ย่อย ชั้นเดียว) */
private fun countIn(s: NotebookState, folderId: Long) =
    s.notes.count { it.folderId == folderId } + s.folders.count { it.parentId == folderId }

/** ทุกโฟลเดอร์ใต้ [folderId] (รวมตัวเอง) กันย้ายโฟลเดอร์ไปไว้ในลูกของตัวเอง */
private fun descendants(all: List<NoteFolderEntity>, folderId: Long): Set<Long> {
    val ids = mutableSetOf(folderId)
    var grew = true
    while (grew) {
        val more = all.filter { it.parentId in ids && it.folderId !in ids }.map { it.folderId }
        grew = more.isNotEmpty(); ids += more
    }
    return ids
}

fun relativeTime(ms: Long): String {
    val diff = (System.currentTimeMillis() - ms) / 60_000
    return when {
        diff < 1 -> "เมื่อสักครู่"
        diff < 60 -> "$diff นาทีที่แล้ว"
        diff < 24 * 60 -> "${diff / 60} ชั่วโมงที่แล้ว"
        diff < 48 * 60 -> "เมื่อวาน"
        else -> java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate().let {
            "${it.dayOfMonth} ${com.example.smartscheduler.domain.model.Recurrence.monthLabelsTh[it.monthValue - 1]}"
        }
    }
}

@Composable
fun folderAccent(f: NoteFolderEntity?): Color =
    TaskPalette.resolve(f?.color, AppTheme.colors.dark)?.first ?: AppTheme.colors.ink

/**
 * หน้าสมุด (folderId = null) และหน้าในโฟลเดอร์ ใช้หน้าเดียวกัน
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotebookScreen(
    onOpenFolder: (Long) -> Unit,
    onOpenNote: (Long) -> Unit,
    onOpenPdf: (Long) -> Unit,
    onNewNote: (Long?) -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: NotebookViewModel = hiltViewModel()
) {
    val c = AppTheme.colors
    val s by viewModel.state.collectAsState()
    val folderId = viewModel.folderId
    val folder = s.folders.firstOrNull { it.folderId == folderId }
    var editFolder by remember { mutableStateOf<NoteFolderEntity?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var deleteFolder by remember { mutableStateOf<NoteFolderEntity?>(null) }
    var deleteNote by remember { mutableStateOf<NoteEntity?>(null) }
    var moveTarget by remember { mutableStateOf<Any?>(null) }
    // โหมดเลือกหลายรายการ (กดค้างเพื่อเริ่ม)
    var selNotes by remember { mutableStateOf(setOf<Long>()) }
    var selFolders by remember { mutableStateOf(setOf<Long>()) }
    var selectMode by remember { mutableStateOf(false) } // แตะปุ่ม "เลือก" = เข้าโหมดแม้ยังไม่ติ๊ก
    val selecting = selectMode || selNotes.isNotEmpty() || selFolders.isNotEmpty()
    val selCount = selNotes.size + selFolders.size
    var sortSheet by remember { mutableStateOf(false) }
    /** กดค้างแล้วลาก: true = ติ๊กเพิ่ม, false = เอาออก, null = ไม่ได้ลาก */
    var dragSelect by remember { mutableStateOf<Boolean?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var bulkDelete by remember { mutableStateOf(false) }
    var bulkMove by remember { mutableStateOf(false) }
    fun clearSel() { selNotes = emptySet(); selFolders = emptySet(); selectMode = false }
    fun toggleNote(id: Long) { selNotes = if (id in selNotes) selNotes - id else selNotes + id }
    fun toggleFolder(id: Long) { selFolders = if (id in selFolders) selFolders - id else selFolders + id }
    fun startDragNote(id: Long) { val on = id !in selNotes; toggleNote(id); dragSelect = on }
    fun startDragFolder(id: Long) { val on = id !in selFolders; toggleFolder(id); dragSelect = on }
    androidx.activity.compose.BackHandler(enabled = selecting) { clearSel() }
    var topMenu by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    // ไฟล์ที่เลือกมา: (uri, info ที่อ่านได้ หรือ null = เปิดไม่ได้)
    var pdfPending by remember { mutableStateOf<List<Pair<android.net.Uri, NoteRepository.PdfInfo?>>?>(null) }
    val pickPdf = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            pdfPending = uris.distinct().map { it to viewModel.inspectPdf(it) }
        }
    }

    // ทางกลับไปหน้าสมุด เช่น สมุด › เรียน › เซนเซอร์
    val path = remember(s.folders, folderId) {
        val out = mutableListOf<NoteFolderEntity>()
        var cur = s.folders.firstOrNull { it.folderId == folderId }
        while (cur != null && out.size < 20) { out.add(0, cur); cur = s.folders.firstOrNull { it.folderId == cur!!.parentId } }
        out
    }

    val searching = s.query.isNotBlank()
    val q = s.query.trim()
    val subFolders = (if (searching) s.folders.filter { it.name.contains(q, ignoreCase = true) }
        else s.folders.filter { it.parentId == folderId }).sortedFolders(s.sort, s.notes)
    val shown = s.notes.filtered(s.filter)
    val items = when {
        searching -> shown.filter { it.title.contains(q, true) || NoteDoc.plain(it.body).contains(q, true) }
        folderId == null -> shown.filter { it.folderId == null }
        else -> shown.filter { it.folderId == folderId }
    }.sortedNotes(s.sort)
    // "ล่าสุด" ยังเรียงตามแก้ไขล่าสุดเสมอ
    val recent = if (folderId == null && !searching) shown.sortedByDescending { it.updatedAt }.take(5) else emptyList()
    val folderRows = subFolders.chunked(2)

    // ลากเลือก: หาว่านิ้วอยู่บนรายการไหน (คีย์ของแถวใน LazyColumn)
    val hitTest = rememberUpdatedState { x: Float, y: Float, width: Int ->
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }
        when (val k = info?.key as? String) {
            null -> null
            else -> when {
                k.startsWith("f") -> folderRows.firstOrNull { "f" + it.first().folderId == k }?.let { row ->
                    (if (x > width / 2f && row.size > 1) row[1] else row[0]).let { "F" to it.folderId }
                }
                k.startsWith("r") || k.startsWith("n") -> k.drop(1).toLongOrNull()?.let { "N" to it }
                else -> null
            }
        }
    }

    Box(Modifier.fillMaxSize().background(c.ground)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                // หลังกดค้างที่รายการ ลากต่อเพื่อติ๊ก/เอาออกหลายอัน (กินการเลื่อนระหว่างลาก)
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val mode = dragSelect ?: continue
                        val ch = ev.changes.firstOrNull() ?: continue
                        if (!ch.pressed) { dragSelect = null; continue }
                        if (ch.position != ch.previousPosition) {
                            ch.consume()
                            hitTest.value(ch.position.x, ch.position.y, size.width)?.let { (t, id) ->
                                if (t == "F") selFolders = if (mode) selFolders + id else selFolders - id
                                else selNotes = if (mode) selNotes + id else selNotes - id
                            }
                        }
                    }
                }
            },
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 110.dp)
        ) {
            item {
                if (selecting) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                        IconButton(onClick = { clearSel() }) { Icon(Icons.Filled.Close, contentDescription = "ยกเลิกการเลือก") }
                        Text(if (selCount == 0) "เลือกรายการ" else "เลือก $selCount รายการ", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            selFolders = subFolders.map { it.folderId }.toSet()
                            selNotes = (recent + items).map { it.noteId }.toSet()
                        }) { Text("เลือกทั้งหมด") }
                    }
                } else if (onBack == null) {
                    Text("สมุด", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "กลับ") }
                        Icon(taskIconByKey(folder?.icon) ?: Icons.Filled.Folder, contentDescription = null, tint = folderAccent(folder), modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(folder?.name ?: "", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box {
                            IconButton(onClick = { topMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "ตัวเลือกโฟลเดอร์") }
                            DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                                DropdownMenuItem(text = { Text("แก้ไขโฟลเดอร์") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { topMenu = false; editFolder = folder })
                                DropdownMenuItem(text = { Text("ย้ายโฟลเดอร์") }, leadingIcon = { Icon(Icons.Filled.DriveFileMove, null) }, onClick = { topMenu = false; moveTarget = folder })
                                DropdownMenuItem(text = { Text("ลบโฟลเดอร์", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { topMenu = false; deleteFolder = folder })
                            }
                        }
                    }
                    Text(
                        (listOf("สมุด") + path.map { it.name }).joinToString(" › "),
                        style = MaterialTheme.typography.labelMedium, color = c.muted,
                        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                // ค้นหา + ปุ่มเรียง + ปุ่มเลือก
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(c.surface).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = c.muted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (s.query.isEmpty()) Text("ค้นหาบันทึกและโฟลเดอร์", color = c.muted, style = MaterialTheme.typography.bodyLarge)
                        BasicTextField(
                            value = s.query, onValueChange = viewModel::setQuery, singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink), cursorBrush = SolidColor(c.ink),
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "ค้นหา" }
                        )
                    }
                    if (s.query.isNotEmpty()) Icon(Icons.Filled.Close, contentDescription = "ล้างคำค้น", tint = c.muted, modifier = Modifier.size(20.dp).clickable { viewModel.setQuery("") })
                }
                SquareButton(Icons.AutoMirrored.Filled.Sort, "เรียงและกรอง") { sortSheet = true }
                SquareButton(Icons.Filled.Checklist, "เลือกหลายรายการ", active = selecting) { if (selecting) clearSel() else selectMode = true }
                }
                Text(
                    "เรียง: ${s.sort.label.substringBefore(" (")}" + if (s.filter != NoteFilter.ALL) " · แสดง: ${s.filter.label}" else "",
                    style = MaterialTheme.typography.labelMedium, color = c.muted,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp).clip(RoundedCornerShape(8.dp)).clickable { sortSheet = true }.padding(4.dp)
                )
            }

            if (subFolders.isNotEmpty()) {
                item { Hint(if (searching) "โฟลเดอร์" else if (folderId == null) "โฟลเดอร์" else "โฟลเดอร์ย่อย", Modifier.padding(top = 18.dp, bottom = 8.dp)) }
                items(folderRows, key = { row -> "f" + row.first().folderId }) { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { f ->
                            FolderCard(
                                f, countIn(s, f.folderId), Modifier.weight(1f),
                                selected = f.folderId in selFolders, selecting = selecting,
                                onClick = { if (selecting) toggleFolder(f.folderId) else onOpenFolder(f.folderId) },
                                onLongClick = { startDragFolder(f.folderId) }
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            if (recent.isNotEmpty()) {
                item { Hint("ล่าสุด", Modifier.padding(top = 14.dp, bottom = 4.dp)) }
                items(recent, key = { "r" + it.noteId }) { n ->
                    NoteRow(n, s.folders.firstOrNull { it.folderId == n.folderId }?.name, selected = n.noteId in selNotes, selecting = selecting, onClick = { if (selecting) toggleNote(n.noteId) else if (n.kind == "PDF") onOpenPdf(n.noteId) else onOpenNote(n.noteId) }, onLongClick = { startDragNote(n.noteId) })
                }
            }

            val listItems = if (folderId == null && !searching) items.filter { n -> recent.none { it.noteId == n.noteId } } else items
            if (listItems.isNotEmpty()) {
                item { Hint(if (searching) "บันทึก" else if (folderId == null) "ไม่มีโฟลเดอร์" else "รายการ", Modifier.padding(top = 14.dp, bottom = 4.dp)) }
                items(listItems, key = { "n" + it.noteId }) { n ->
                    NoteRow(n, if (searching) s.folders.firstOrNull { it.folderId == n.folderId }?.name else null, selected = n.noteId in selNotes, selecting = selecting, onClick = { if (selecting) toggleNote(n.noteId) else if (n.kind == "PDF") onOpenPdf(n.noteId) else onOpenNote(n.noteId) }, onLongClick = { startDragNote(n.noteId) })
                }
            }

            if (subFolders.isEmpty() && items.isEmpty() && recent.isEmpty()) {
                item {
                    EmptyState(
                        icon = if (searching) Icons.Filled.SearchOff else Icons.Filled.EditNote,
                        title = if (searching) "ไม่พบ \"$q\"" else if (s.filter != NoteFilter.ALL) "ไม่มี${s.filter.label}ในที่นี้" else if (folderId == null) "สมุดยังว่าง" else "โฟลเดอร์นี้ยังว่าง",
                        subtitle = if (searching) "ลองคำอื่น" else "แตะ + เพื่อเขียนบันทึกหรือสร้างโฟลเดอร์",
                        actionLabel = if (searching) null else "บันทึกใหม่",
                        onAction = if (searching) null else ({ onNewNote(folderId) })
                    )
                }
            }
        }

        // โหมดเลือก: แถบ ย้าย / ลบ แทนปุ่ม +
        if (selecting) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(c.surface).padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                BulkAction(Icons.Filled.DriveFileMove, "ย้าย", if (selCount > 0) c.ink else c.muted.copy(alpha = 0.5f)) { if (selCount > 0) bulkMove = true }
                BulkAction(Icons.Filled.Delete, "ลบ", if (selCount > 0) MaterialTheme.colorScheme.error else c.muted.copy(alpha = 0.5f)) { if (selCount > 0) bulkDelete = true }
            }
        }

        // ปุ่ม + : เหมือนหน้าปฏิทิน (แตะ/กดค้างกางไอคอน, ลากไปปล่อยที่ไอคอน)
        val fabStyle by viewModel.fabStyle.collectAsState()
        if (!selecting) AddMenuFab(
            style = fabStyle,
            actions = listOf(
                AddMenuAction(
                    label = "บันทึกใหม่",
                    tint = { AppTheme.colors.work },
                    icon = { tint -> Icon(Icons.Filled.EditNote, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp)) },
                    onClick = { onNewNote(folderId) }
                ),
                AddMenuAction(
                    label = "โฟลเดอร์ใหม่",
                    tint = { AppTheme.colors.rest },
                    icon = { tint -> Icon(Icons.Filled.CreateNewFolder, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp)) },
                    onClick = { newFolder = true }
                ),
                AddMenuAction(
                    label = "นำเข้า PDF",
                    tint = { AppTheme.colors.task },
                    icon = { tint -> Icon(Icons.Filled.PictureAsPdf, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp)) },
                    onClick = { pickPdf.launch(arrayOf("application/pdf")) }
                )
            )
        )
    }

    // ---------- หน้าต่าง ----------
    if (sortSheet) SortSheet(s.sort, s.filter, viewModel::setSort, viewModel::setFilter) { sortSheet = false }

    if (bulkDelete) {
        val inside = selFolders.flatMap { descendants(s.folders, it) }.toSet()
        val extra = s.notes.count { it.folderId in inside && it.noteId !in selNotes } + (inside.size - selFolders.size)
        ConfirmDeleteDialog(
            title = "ลบ ${selNotes.size + selFolders.size} รายการ",
            message = (if (selFolders.isNotEmpty() && extra > 0) "รวมของในโฟลเดอร์ที่เลือกอีก $extra รายการ " else "") + "ลบแล้วย้อนกลับไม่ได้",
            confirmText = "ลบ",
            onConfirm = { viewModel.deleteMany(selNotes, selFolders); bulkDelete = false; clearSel() },
            onDismiss = { bulkDelete = false }
        )
    }
    if (bulkMove) {
        MoveDialog(
            folders = s.folders,
            current = null,
            blocked = selFolders.flatMap { descendants(s.folders, it) }.toSet(),
            onPick = { to -> viewModel.moveMany(selNotes, selFolders, to); bulkMove = false; clearSel() },
            onDismiss = { bulkMove = false }
        )
    }
    pdfPending?.let { files ->
        val progress by viewModel.importProgress.collectAsState()
        ImportPdfDialog(
            files = files, folders = s.folders, initialFolder = folderId, progress = progress,
            onImport = { items, folder ->
                viewModel.importPdfs(items, folder) { ids, failed ->
                    pdfPending = null
                    when {
                        ids.isEmpty() -> android.widget.Toast.makeText(context, "นำเข้าไม่สำเร็จ", android.widget.Toast.LENGTH_SHORT).show()
                        ids.size == 1 && failed == 0 -> onOpenPdf(ids.first())
                        else -> {
                            android.widget.Toast.makeText(
                                context,
                                "นำเข้าแล้ว ${ids.size} ไฟล์" + if (failed > 0) " (ไม่สำเร็จ $failed)" else "",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            if (folder != null && folder != folderId) onOpenFolder(folder)
                        }
                    }
                }
            },
            onDismiss = { if (progress == null) pdfPending = null }
        )
    }
    if (newFolder || editFolder != null) {
        FolderDialog(
            initial = editFolder,
            onSave = { name, color, icon ->
                val base = editFolder ?: NoteFolderEntity(ownerUid = "", parentId = folderId, name = name)
                viewModel.saveFolder(base.copy(name = name, color = color, icon = icon))
                newFolder = false; editFolder = null
            },
            onDismiss = { newFolder = false; editFolder = null }
        )
    }
    deleteFolder?.let { f ->
        val n = descendants(s.folders, f.folderId).let { ids -> s.notes.count { it.folderId in ids } + ids.size - 1 }
        ConfirmDeleteDialog(
            title = "ลบโฟลเดอร์ \"${f.name}\"",
            message = if (n == 0) "โฟลเดอร์ว่าง ลบได้เลย" else "จะลบทุกอย่างข้างใน $n รายการด้วย (รวมโฟลเดอร์ย่อย) ย้อนกลับไม่ได้",
            confirmText = "ลบ",
            onConfirm = {
                viewModel.deleteFolder(f.folderId); deleteFolder = null
                if (f.folderId == folderId) onBack?.invoke()
            },
            onDismiss = { deleteFolder = null }
        )
    }
    deleteNote?.let { n ->
        ConfirmDeleteDialog(
            title = "ลบ \"${n.title.ifBlank { "บันทึกไม่มีชื่อ" }}\"",
            message = "ลบแล้วย้อนกลับไม่ได้ รูปและไฟล์แนบในบันทึกจะถูกลบด้วย",
            confirmText = "ลบ",
            onConfirm = { viewModel.deleteNote(n.noteId); deleteNote = null },
            onDismiss = { deleteNote = null }
        )
    }
    moveTarget?.let { t ->
        val blocked = if (t is NoteFolderEntity) descendants(s.folders, t.folderId) else emptySet()
        MoveDialog(
            folders = s.folders,
            current = if (t is NoteFolderEntity) t.parentId else (t as NoteEntity).folderId,
            blocked = blocked,
            onPick = { to ->
                if (t is NoteFolderEntity) viewModel.moveFolder(t.folderId, to) else viewModel.moveNote((t as NoteEntity).noteId, to)
                moveTarget = null
            },
            onDismiss = { moveTarget = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(
    f: NoteFolderEntity, count: Int, modifier: Modifier,
    selected: Boolean, selecting: Boolean, onClick: () -> Unit, onLongClick: () -> Unit
) {
    val c = AppTheme.colors
    val accent = folderAccent(f)
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) c.now.copy(alpha = 0.22f) else c.surface)
            .then(if (selected) Modifier.border(2.dp, c.now, RoundedCornerShape(16.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp)
            .heightIn(min = 64.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(taskIconByKey(f.icon) ?: Icons.Filled.Folder, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            }
            if (selecting) SelCheck(selected, Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 6.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(f.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$count รายการ", style = MaterialTheme.typography.labelSmall, color = c.muted)
    }
}

@Composable
private fun SelCheck(checked: Boolean, modifier: Modifier) {
    val c = AppTheme.colors
    if (checked) Box(
        modifier.size(22.dp).clip(CircleShape).background(c.ground).padding(2.dp).clip(CircleShape).background(c.now),
        contentAlignment = Alignment.Center
    ) { Icon(Icons.Filled.Check, contentDescription = "เลือกแล้ว", tint = c.ground, modifier = Modifier.size(14.dp)) }
    else Box(
        modifier.size(22.dp).clip(CircleShape).background(c.ground).padding(2.dp).border(2.dp, c.outline, CircleShape)
            .semantics { contentDescription = "ยังไม่เลือก" }
    )
}

@Composable
private fun SquareButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    val c = AppTheme.colors
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
            .background(if (active) c.ink else c.surface)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = null, tint = if (active) c.ground else c.ink, modifier = Modifier.size(22.dp)) }
}

/** แผ่นล่าง "เรียงตาม" + "แสดง" */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SortSheet(sort: NoteSort, filter: NoteFilter, onSort: (NoteSort) -> Unit, onFilter: (NoteFilter) -> Unit, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("เรียงตาม", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
            NoteSort.values().forEach { opt ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .selectable(selected = opt == sort, role = androidx.compose.ui.semantics.Role.RadioButton) { onSort(opt) }
                        .heightIn(min = 48.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = opt == sort, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(opt.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text("แสดง", style = MaterialTheme.typography.titleSmall, color = c.muted, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NoteFilter.values().forEach { f ->
                    FilterChip(selected = f == filter, onClick = { onFilter(f) }, label = { Text(f.label) })
                }
            }
            Hint("จำไว้ใช้ทุกโฟลเดอร์ · \"ล่าสุด\" หน้าแรกยังเรียงตามแก้ไขล่าสุด", Modifier.padding(top = 14.dp))
        }
    }
}

@Composable
private fun BulkAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 28.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(n: NoteEntity, folderName: String?, selected: Boolean, selecting: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = AppTheme.colors
    val pdf = n.kind == "PDF"
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) c.now.copy(alpha = 0.18f) else Color.Transparent)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(width = 40.dp, height = 46.dp).clip(RoundedCornerShape(8.dp)).background(if (pdf) Color(0xFF5A2A2A) else c.surface),
                contentAlignment = Alignment.Center
            ) {
                Icon(if (pdf) Icons.Filled.PictureAsPdf else Icons.Filled.Description, contentDescription = null, tint = if (pdf) Color(0xFFFFB4A8) else c.ink, modifier = Modifier.size(20.dp))
                if (selecting) SelCheck(selected, Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 6.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(n.title.ifBlank { "บันทึกไม่มีชื่อ" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val (done, total) = NoteDoc.checks(n.body)
                val bits = listOfNotNull(
                    folderName,
                    if (pdf && n.pageCount > 0) "อ่านถึงหน้า ${n.lastPage + 1}/${n.pageCount}" else null,
                    if (total > 0) "☑ $done/$total" else null,
                    relativeTime(n.updatedAt)
                )
                Text(bits.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** ไอคอนจากชุดเดียวกับกิจกรรม */
fun taskIconByKey(key: String?) = TaskIcons.find(key)?.vector

@Composable
fun FolderDialog(initial: NoteFolderEntity?, onSave: (String, String?, String?) -> Unit, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var color by remember { mutableStateOf(initial?.color) }
    var icon by remember { mutableStateOf(initial?.icon) }
    var expanded by remember { mutableStateOf(false) }
    val accent = TaskPalette.resolve(color, c.dark)?.first ?: c.ink
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(if (initial == null) "โฟลเดอร์ใหม่" else "แก้ไขโฟลเดอร์") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScrollCompat()) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, label = { Text("ชื่อโฟลเดอร์") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                ColorField(title = "สี", selected = color, defaultColor = c.ink, defaultLabel = "สีตัวหนังสือ", icon = taskIconByKey(icon) ?: Icons.Filled.Folder) { color = it }
                Hint("ไอคอน", Modifier.padding(top = 8.dp, bottom = 6.dp))
                val shown = if (expanded) TaskIcons.all else TaskIcons.all.take(15)
                shown.chunked(5).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { item ->
                            val sel = icon == item.key
                            Box(
                                Modifier.weight(1f).height(48.dp).clip(CircleShape)
                                    .background(if (sel) accent.copy(alpha = 0.18f) else Color.Transparent)
                                    .clickable { icon = if (sel) null else item.key }
                                    .semantics { contentDescription = "ไอคอน${item.label}" },
                                contentAlignment = Alignment.Center
                            ) { Icon(item.vector, contentDescription = null, tint = if (sel) accent else c.muted) }
                        }
                        repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "แสดงน้อยลง" else "ไอคอนทั้งหมด") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim(), color, icon) }, enabled = name.isNotBlank()) { Text("บันทึก") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    )
}

@Composable
private fun Modifier.verticalScrollCompat() = this.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))

/** เลือกโฟลเดอร์ปลายทาง (แสดงเป็นลำดับชั้น) */
@Composable
fun MoveDialog(folders: List<NoteFolderEntity>, current: Long?, blocked: Set<Long>, onPick: (Long?) -> Unit, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    val rows = remember(folders) {
        val out = mutableListOf<Pair<NoteFolderEntity, Int>>()
        fun add(parent: Long?, depth: Int) {
            folders.filter { it.parentId == parent }.forEach { f -> out += f to depth; if (depth < 8) add(f.folderId, depth + 1) }
        }
        add(null, 0); out
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("ย้ายไปที่") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    MoveRow("สมุด (ชั้นบนสุด)", Icons.Filled.Home, c.ink, 0, current == null, enabled = true) { onPick(null) }
                }
                items(rows, key = { it.first.folderId }) { (f, depth) ->
                    MoveRow(f.name, taskIconByKey(f.icon) ?: Icons.Filled.Folder, folderAccent(f), depth + 1, current == f.folderId, enabled = f.folderId !in blocked) { onPick(f.folderId) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    )
}

@Composable
private fun MoveRow(name: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, depth: Int, current: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled && !current, onClick = onClick)
            .padding(start = (depth * 16).dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) tint else c.line, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(name, color = if (enabled) c.ink else c.line, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (current) Text("อยู่ที่นี่", style = MaterialTheme.typography.labelSmall, color = c.muted)
    }
}


/** แผ่นนำเข้า PDF (หลายไฟล์): ติ๊กเลือก/แก้ชื่อ + โฟลเดอร์เดียวสำหรับทุกไฟล์ + ความคืบหน้า */
@Composable
private fun ImportPdfDialog(
    files: List<Pair<android.net.Uri, NoteRepository.PdfInfo?>>,
    folders: List<NoteFolderEntity>,
    initialFolder: Long?,
    progress: Triple<Int, Int, Float>?,
    onImport: (List<Triple<android.net.Uri, String, NoteRepository.PdfInfo>>, Long?) -> Unit,
    onDismiss: () -> Unit
) {
    val c = AppTheme.colors
    val names = remember { mutableStateListOf(*files.map { (_, i) -> (i?.name ?: "").removeSuffix(".pdf").removeSuffix(".PDF") }.toTypedArray()) }
    val checked = remember { mutableStateListOf(*files.map { it.second != null }.toTypedArray()) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var folder by remember { mutableStateOf(initialFolder) }
    var picking by remember { mutableStateOf(false) }
    val busy = progress != null
    val count = files.indices.count { checked[it] && files[it].second != null }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(if (files.size == 1) "นำเข้า PDF" else "นำเข้า PDF ${files.size} ไฟล์") },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexedCompat(files) { i, (_, info) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.ground).padding(start = 2.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (files.size > 1) Checkbox(checked = checked[i] && info != null, onCheckedChange = { checked[i] = it }, enabled = info != null && !busy)
                            else Spacer(Modifier.width(8.dp))
                            Box(Modifier.size(width = 34.dp, height = 40.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF5A2A2A)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.PictureAsPdf, contentDescription = null, tint = Color(0xFFFFB4A8), modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                if (editing == i) {
                                    OutlinedTextField(
                                        value = names[i], onValueChange = { names[i] = it.take(120) }, singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth(),
                                        trailingIcon = { IconButton(onClick = { editing = null }) { Icon(Icons.Filled.Check, contentDescription = "เสร็จ") } }
                                    )
                                } else {
                                    Text(
                                        if (info == null) "ไฟล์ที่ ${i + 1}" else names[i].ifBlank { info.name },
                                        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.clickable(enabled = info != null && !busy) { editing = i }
                                    )
                                }
                                Text(
                                    if (info == null) "เปิดไม่ได้ (อาจมีรหัสผ่านหรือไฟล์เสีย) จะข้าม"
                                    else "${info.pages} หน้า" + (if (info.size > 0) " · %.1f MB".format(info.size / 1_000_000.0) else "") + if (editing != i) " · แตะชื่อเพื่อแก้" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (info == null) MaterialTheme.colorScheme.error else c.muted
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                val f = folders.firstOrNull { it.folderId == folder }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = !busy) { picking = true }.padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(taskIconByKey(f?.icon) ?: if (f == null) Icons.Filled.Home else Icons.Filled.Folder, contentDescription = null, tint = folderAccent(f), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (files.size > 1) "เก็บทุกไฟล์ในโฟลเดอร์" else "เก็บในโฟลเดอร์", style = MaterialTheme.typography.labelSmall, color = c.muted)
                        Text(f?.name ?: "สมุด (ชั้นบนสุด)", style = MaterialTheme.typography.bodyMedium)
                    }
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = c.muted)
                }
                if (progress != null) {
                    val (n, total, frac) = progress
                    LinearProgressIndicator(progress = { ((n - 1) + frac) / total }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), color = c.task)
                    Text(
                        (if (total > 1) "ไฟล์ $n จาก $total · " else "กำลังคัดลอก ") + "${(((n - 1) + frac) / total * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall, color = c.muted, modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val items = files.indices.filter { checked[it] }.mapNotNull { i ->
                        val info = files[i].second ?: return@mapNotNull null
                        Triple(files[i].first, names[i].trim().ifBlank { info.name.removeSuffix(".pdf") }, info)
                    }
                    onImport(items, folder)
                },
                enabled = !busy && count > 0
            ) { Text(if (count > 1) "นำเข้า $count ไฟล์" else "นำเข้า") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("ยกเลิก") } }
    )
    if (picking) MoveDialog(folders = folders, current = folder, blocked = emptySet(), onPick = { folder = it; picking = false }, onDismiss = { picking = false })
}

private fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexedCompat(
    list: List<T>, content: @Composable androidx.compose.foundation.lazy.LazyItemScope.(Int, T) -> Unit
) = items(list.size) { i -> content(i, list[i]) }
