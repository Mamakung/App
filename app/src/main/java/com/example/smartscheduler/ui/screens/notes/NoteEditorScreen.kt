package com.example.smartscheduler.ui.screens.notes

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartscheduler.data.local.entity.NoteEntity
import com.example.smartscheduler.data.local.entity.NoteFolderEntity
import com.example.smartscheduler.data.repository.NoteBlock
import com.example.smartscheduler.data.repository.NoteDoc
import com.example.smartscheduler.data.repository.NoteRepository
import com.example.smartscheduler.ui.components.ConfirmDeleteDialog
import com.example.smartscheduler.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import javax.inject.Inject

@HiltViewModel
class NoteEditorViewModel @Inject constructor(
    private val repo: NoteRepository,
    savedState: SavedStateHandle
) : ViewModel() {
    private val argId = savedState.get<Long>("id") ?: -1L
    private val argFolder = savedState.get<Long>("folder")?.takeIf { it >= 0 }

    private val _note = MutableStateFlow<NoteEntity?>(null)
    val note: StateFlow<NoteEntity?> = _note.asStateFlow()
    private val _blocks = MutableStateFlow<List<NoteBlock>>(emptyList())
    val blocks: StateFlow<List<NoteBlock>> = _blocks.asStateFlow()
    private val _saved = MutableStateFlow(true)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()
    val folders: StateFlow<List<NoteFolderEntity>> = repo.observeFolders().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            val n = if (argId >= 0) repo.getNote(argId) else null
            val note = n ?: NoteEntity(ownerUid = "", folderId = argFolder).let { it.copy(noteId = repo.saveNote(it)) }
            _note.value = note
            _blocks.value = NoteDoc.parse(note.body)
        }
    }

    fun setTitle(t: String) { _note.value = _note.value?.copy(title = t); scheduleSave() }

    fun setText(id: String, text: String) {
        _blocks.value = _blocks.value.map { if (it.id == id && it is NoteBlock.Text) it.copy(text = text) else it }
        scheduleSave()
    }

    /** แทรกรูป/ไฟล์ตรงตำแหน่งเคอร์เซอร์ (แยกข้อความเป็นสองส่วน) */
    fun insert(uri: Uri, image: Boolean, atId: String?, cursor: Int, onFail: () -> Unit) = viewModelScope.launch {
        val n = _note.value ?: return@launch
        val (path, name, size) = repo.copyIn(n.noteId, uri) ?: run { onFail(); return@launch }
        val media = if (image) NoteBlock.Image(NoteDoc.newId(), path) else NoteBlock.Attachment(NoteDoc.newId(), path, name, size)
        val list = _blocks.value.toMutableList()
        val idx = list.indexOfFirst { it.id == atId }.takeIf { it >= 0 } ?: list.lastIndex
        val target = list[idx]
        if (target is NoteBlock.Text) {
            val cut = cursor.coerceIn(0, target.text.length)
            val before = target.text.substring(0, cut).removeSuffix("\n")
            val after = target.text.substring(cut).removePrefix("\n")
            list[idx] = NoteBlock.Text(NoteDoc.newId(), before)
            list.add(idx + 1, media)
            list.add(idx + 2, NoteBlock.Text(NoteDoc.newId(), after))
        } else list.add(idx + 1, media)
        _blocks.value = NoteDoc.normalize(list)
        scheduleSave()
    }

    fun removeMedia(id: String) {
        val b = _blocks.value.firstOrNull { it.id == id } ?: return
        val path = when (b) { is NoteBlock.Image -> b.path; is NoteBlock.Attachment -> b.path; else -> null }
        _blocks.value = NoteDoc.normalize(_blocks.value.filterNot { it.id == id })
        path?.let { repo.fileOf(it).delete() }
        scheduleSave()
    }

    fun move(to: Long?) = viewModelScope.launch {
        val n = _note.value ?: return@launch
        repo.moveNote(n.noteId, to); _note.value = n.copy(folderId = to)
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        _note.value?.let { repo.deleteNote(it.noteId) }
        _note.value = null
        onDone()
    }

    fun fileOf(path: String) = repo.fileOf(path)

    private fun scheduleSave() {
        _saved.value = false
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(500); saveNow() }
    }

    private suspend fun saveNow() {
        val n = _note.value ?: return
        val body = NoteDoc.serialize(_blocks.value)
        val updated = n.copy(body = body, updatedAt = System.currentTimeMillis())
        repo.saveNote(updated)
        _note.value = updated
        _saved.value = true
    }

    /** ออกจากหน้า: บันทึกทันที และลบทิ้งถ้าไม่ได้เขียนอะไรเลย */
    @OptIn(DelicateCoroutinesApi::class)
    fun finish() {
        saveJob?.cancel()
        val n = _note.value ?: return
        val blocks = _blocks.value
        val empty = n.title.isBlank() && blocks.all { it is NoteBlock.Text && it.text.isBlank() }
        GlobalScope.launch(NonCancellable) {
            if (empty) repo.deleteNote(n.noteId)
            else repo.saveNote(n.copy(body = NoteDoc.serialize(blocks), updatedAt = if (_saved.value) n.updatedAt else System.currentTimeMillis()))
        }
    }
}

// ---------- แก้ข้อความ ----------

private fun lineStartOf(text: String, i: Int): Int = if (i <= 0) 0 else text.lastIndexOf('\n', i - 1) + 1
private fun lineEndOf(text: String, i: Int): Int = text.indexOf('\n', i).let { if (it < 0) text.length else it }

private fun prefixOf(line: String): String? = NoteDoc.prefixes.firstOrNull { line.startsWith(it) }

/** สลับคำนำหน้าบรรทัด (หัวข้อ / รายการติ๊ก / จุด) ทุกบรรทัดที่เลือก */
private fun togglePrefix(v: TextFieldValue, prefix: String): TextFieldValue {
    val t = v.text
    val start = lineStartOf(t, v.selection.min)
    val end = lineEndOf(t, v.selection.max)
    val lines = t.substring(start, end).split("\n")
    val isCheck = prefix == NoteDoc.CHECK
    val allHave = lines.all { l -> if (isCheck) l.startsWith(NoteDoc.CHECK) || l.startsWith(NoteDoc.CHECKED) else l.startsWith(prefix) }
    val newLines = lines.map { l ->
        val bare = prefixOf(l)?.let { l.removePrefix(it) } ?: l
        if (allHave) bare else prefix + bare
    }
    val replaced = newLines.joinToString("\n")
    val text = t.substring(0, start) + replaced + t.substring(end)
    val delta = replaced.length - (end - start)
    val cursor = if (v.selection.collapsed) (v.selection.start + if (lines.size == 1) delta else 0).coerceIn(start, start + replaced.length)
        else start + replaced.length
    return TextFieldValue(text, TextRange(cursor))
}

/** ครอบคำที่เลือกด้วยเครื่องหมาย (** = ตัวหนา, _ = ตัวเอียง) กดซ้ำ = เอาออก */
private fun wrap(v: TextFieldValue, mark: String): TextFieldValue {
    val t = v.text
    val s = v.selection.min
    val e = v.selection.max
    if (s == e) {
        return TextFieldValue(t.substring(0, s) + mark + mark + t.substring(s), TextRange(s + mark.length))
    }
    val wrapped = s >= mark.length && t.startsWith(mark, s - mark.length) && t.startsWith(mark, e)
    return if (wrapped) {
        TextFieldValue(t.substring(0, s - mark.length) + t.substring(s, e) + t.substring(e + mark.length), TextRange(s - mark.length, e - mark.length))
    } else {
        TextFieldValue(t.substring(0, s) + mark + t.substring(s, e) + mark + t.substring(e), TextRange(s + mark.length, e + mark.length))
    }
}

/** กด Enter บนบรรทัดรายการ: ต่อคำนำหน้าให้ / บรรทัดว่าง = ออกจากรายการ */
private fun continueList(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    val c = new.selection.start
    if (!new.selection.collapsed || new.text.length != old.text.length + 1 || c <= 0 || new.text[c - 1] != '\n') return new
    val t = new.text
    val prevStart = lineStartOf(t, c - 1)
    val prevLine = t.substring(prevStart, c - 1)
    val p = prefixOf(prevLine) ?: return new
    if (p == NoteDoc.HEADING) return new
    val cont = if (p == NoteDoc.CHECKED) NoteDoc.CHECK else p
    return if (prevLine == p) {
        // รายการว่าง: เอาคำนำหน้าและบรรทัดใหม่ออก
        TextFieldValue(t.substring(0, prevStart) + t.substring(c), TextRange(prevStart))
    } else {
        TextFieldValue(t.substring(0, c) + cont + t.substring(c), TextRange(c + cont.length))
    }
}

/** แสดงรูปแบบ: หัวข้อใหญ่, ติ๊กแล้วขีดฆ่า, **ตัวหนา**, _ตัวเอียง_ (เครื่องหมายแสดงจางๆ ตำแหน่งตัวอักษรไม่เปลี่ยน) */
private class NoteFormatting(val ink: Color, val muted: Color, val check: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val t = text.text
        val b = AnnotatedString.Builder(t)
        var i = 0
        for (line in t.split("\n")) {
            val p = prefixOf(line)
            when (p) {
                NoteDoc.HEADING -> {
                    b.addStyle(SpanStyle(color = muted, fontSize = 13.sp), i, i + 2)
                    b.addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp), i + 2, i + line.length)
                }
                NoteDoc.CHECK -> b.addStyle(SpanStyle(color = muted, fontSize = 19.sp), i, i + 1)
                NoteDoc.CHECKED -> {
                    b.addStyle(SpanStyle(color = check, fontSize = 19.sp), i, i + 1)
                    b.addStyle(SpanStyle(color = muted, textDecoration = TextDecoration.LineThrough), i + 2, i + line.length)
                }
                NoteDoc.BULLET -> b.addStyle(SpanStyle(color = muted, fontWeight = FontWeight.Bold), i, i + 1)
            }
            Regex("""\*\*(.+?)\*\*""").findAll(line).forEach { m ->
                val s = i + m.range.first
                val e = i + m.range.last + 1
                b.addStyle(SpanStyle(color = muted.copy(alpha = 0.5f)), s, s + 2)
                b.addStyle(SpanStyle(fontWeight = FontWeight.Bold), s + 2, e - 2)
                b.addStyle(SpanStyle(color = muted.copy(alpha = 0.5f)), e - 2, e)
            }
            Regex("""(?<![\w*])_(.+?)_(?!\w)""").findAll(line).forEach { m ->
                val s = i + m.range.first
                val e = i + m.range.last + 1
                b.addStyle(SpanStyle(color = muted.copy(alpha = 0.5f)), s, s + 1)
                b.addStyle(SpanStyle(fontStyle = FontStyle.Italic), s + 1, e - 1)
                b.addStyle(SpanStyle(color = muted.copy(alpha = 0.5f)), e - 1, e)
            }
            i += line.length + 1
        }
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }
}

@Composable
fun NoteEditorScreen(onBack: () -> Unit, viewModel: NoteEditorViewModel = hiltViewModel()) {
    val c = AppTheme.colors
    val context = LocalContext.current
    val note by viewModel.note.collectAsState()
    val blocks by viewModel.blocks.collectAsState()
    val saved by viewModel.saved.collectAsState()
    val folders by viewModel.folders.collectAsState()

    // สถานะช่องพิมพ์แต่ละส่วน (เก็บตำแหน่งเคอร์เซอร์)
    val fields = remember { mutableStateMapOf<String, TextFieldValue>() }
    var activeId by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<NoteBlock?>(null) }
    val firstFocus = remember { FocusRequester() }

    fun leave() { viewModel.finish(); onBack() }
    BackHandler { leave() }

    fun cursorAt(): Pair<String?, Int> {
        val id = activeId ?: blocks.lastOrNull { it is NoteBlock.Text }?.id
        return id to (fields[id]?.selection?.max ?: (blocks.firstOrNull { it.id == id } as? NoteBlock.Text)?.text?.length ?: 0)
    }
    fun edit(transform: (TextFieldValue) -> TextFieldValue) {
        val id = activeId ?: blocks.firstOrNull { it is NoteBlock.Text }?.id ?: return
        val cur = fields[id] ?: TextFieldValue((blocks.firstOrNull { it.id == id } as? NoteBlock.Text)?.text ?: "")
        val v = transform(cur)
        fields[id] = v
        viewModel.setText(id, v.text)
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { val (id, cur) = cursorAt(); viewModel.insert(uri, true, id, cur) { Toast.makeText(context, "เพิ่มรูปไม่ได้", Toast.LENGTH_SHORT).show() } }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { val (id, cur) = cursorAt(); viewModel.insert(uri, false, id, cur) { Toast.makeText(context, "แนบไฟล์ไม่ได้", Toast.LENGTH_SHORT).show() } }
    }

    Column(Modifier.fillMaxSize().background(c.ground)) {
        // แถบบน
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { leave() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "กลับ") }
            val folderName = folders.firstOrNull { it.folderId == note?.folderId }?.name ?: "สมุด"
            Text(folderName, style = MaterialTheme.typography.labelLarge, color = c.muted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (saved) "บันทึกแล้ว" else "กำลังบันทึก…", style = MaterialTheme.typography.labelMedium, color = c.muted)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "ตัวเลือกบันทึก") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("ย้ายไปโฟลเดอร์") }, leadingIcon = { Icon(Icons.Filled.DriveFileMove, null) }, onClick = { menu = false; moving = true })
                    DropdownMenuItem(text = { Text("ลบบันทึก", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            item(key = "title") {
                var title by remember(note?.noteId) { mutableStateOf(note?.title ?: "") }
                LaunchedEffect(note?.noteId) { title = note?.title ?: "" }
                Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    if (title.isEmpty()) Text("ชื่อบันทึก", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = c.muted)
                    BasicTextField(
                        value = title,
                        onValueChange = { title = it.take(120); viewModel.setTitle(title) },
                        textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, color = c.ink),
                        cursorBrush = SolidColor(c.ink),
                        modifier = Modifier.fillMaxWidth().focusRequester(firstFocus).semantics { contentDescription = "ชื่อบันทึก" }
                    )
                }
            }
            items(blocks, key = { it.id }) { b ->
                when (b) {
                    is NoteBlock.Text -> TextBlock(
                        block = b,
                        value = fields[b.id] ?: TextFieldValue(b.text),
                        placeholder = if (blocks.size == 1) "เริ่มเขียน… (หัวข้อ ตัวหนา รายการติ๊ก ใช้แถบด้านล่าง)" else null,
                        onChange = { v -> fields[b.id] = v; viewModel.setText(b.id, v.text) },
                        onFocus = { activeId = b.id }
                    )
                    is NoteBlock.Image -> ImageBlock(viewModel.fileOf(b.path), onRemove = { removing = b })
                    is NoteBlock.Attachment -> AttachmentBlock(b, onOpen = {
                        val f = viewModel.fileOf(b.path)
                        runCatching {
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
                            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }.onFailure { Toast.makeText(context, "ไม่มีแอปที่เปิดไฟล์นี้ได้", Toast.LENGTH_SHORT).show() }
                    }, onRemove = { removing = b })
                }
            }
        }

        // แถบเครื่องมือ (อยู่เหนือคีย์บอร์ด)
        Surface(color = c.surface) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ToolButton(Icons.Filled.Title, "หัวข้อ") { edit { togglePrefix(it, NoteDoc.HEADING) } }
                ToolButton(Icons.Filled.FormatBold, "ตัวหนา") { edit { wrap(it, "**") } }
                ToolButton(Icons.Filled.FormatItalic, "ตัวเอียง") { edit { wrap(it, "_") } }
                ToolButton(Icons.Filled.CheckBox, "รายการติ๊ก") { edit { togglePrefix(it, NoteDoc.CHECK) } }
                ToolButton(Icons.AutoMirrored.Filled.FormatListBulleted, "รายการจุด") { edit { togglePrefix(it, NoteDoc.BULLET) } }
                ToolButton(Icons.Filled.Image, "แทรกรูป") { pickImage.launch("image/*") }
                ToolButton(Icons.Filled.AttachFile, "แนบไฟล์") { pickFile.launch(arrayOf("*/*")) }
            }
        }
    }

    // บันทึกใหม่: โฟกัสที่ชื่อเลย
    LaunchedEffect(note?.noteId) {
        if (note != null && note?.title.isNullOrEmpty() && blocks.all { it !is NoteBlock.Text || it.text.isEmpty() }) {
            delay(150); runCatching { firstFocus.requestFocus() }
        }
    }

    if (moving) {
        MoveDialog(folders = folders, current = note?.folderId, blocked = emptySet(), onPick = { viewModel.move(it); moving = false }, onDismiss = { moving = false })
    }
    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = "ลบบันทึกนี้",
            message = "ลบแล้วย้อนกลับไม่ได้ รูปและไฟล์แนบจะถูกลบด้วย",
            confirmText = "ลบ",
            onConfirm = { confirmDelete = false; viewModel.delete(onBack) },
            onDismiss = { confirmDelete = false }
        )
    }
    removing?.let { b ->
        ConfirmDeleteDialog(
            title = if (b is NoteBlock.Image) "เอารูปออก" else "เอาไฟล์แนบออก",
            message = "ไฟล์ที่เก็บไว้ในบันทึกจะถูกลบ (ไฟล์ต้นฉบับในโทรศัพท์ยังอยู่)",
            confirmText = "เอาออก",
            onConfirm = { viewModel.removeMedia(b.id); removing = null },
            onDismiss = { removing = null }
        )
    }
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = label, tint = AppTheme.colors.ink) }
}

@Composable
private fun TextBlock(
    block: NoteBlock.Text,
    value: TextFieldValue,
    placeholder: String?,
    onChange: (TextFieldValue) -> Unit,
    onFocus: () -> Unit
) {
    val c = AppTheme.colors
    val formatting = remember(c) { NoteFormatting(c.ink, c.muted, c.rest) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        if (value.text.isEmpty() && placeholder != null) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = c.muted)
        BasicTextField(
            value = value,
            onValueChange = { change(continueList(current, it)) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink, lineHeight = 26.sp),
            cursorBrush = SolidColor(c.ink),
            visualTransformation = formatting,
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .onFocusChanged { if (it.isFocused) onFocus() }
                // แตะช่องติ๊กหน้าบรรทัด = ติ๊ก/เอาติ๊กออก
                .pointerInputCheckbox(
                    layout = { layout },
                    text = { current.text },
                    onToggle = { lineStart ->
                        val t = current.text
                        val newText = if (t.startsWith(NoteDoc.CHECK, lineStart))
                            t.substring(0, lineStart) + NoteDoc.CHECKED + t.substring(lineStart + 2)
                        else t.substring(0, lineStart) + NoteDoc.CHECK + t.substring(lineStart + 2)
                        change(current.copy(text = newText))
                    }
                )
        )
    }
}

private fun Modifier.pointerInputCheckbox(layout: () -> TextLayoutResult?, text: () -> String, onToggle: (Int) -> Unit) =
    this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial)
            val l = layout() ?: return@awaitEachGesture
            val t = text()
            val off = l.getOffsetForPosition(down.position).coerceIn(0, t.length)
            val ls = lineStartOf(t, off)
            val isBox = t.startsWith(NoteDoc.CHECK, ls) || t.startsWith(NoteDoc.CHECKED, ls)
            if (!isBox) return@awaitEachGesture
            val boxRight = l.getHorizontalPosition((ls + 2).coerceAtMost(t.length), true)
            val line = l.getLineForOffset(ls)
            val inLine = down.position.y in l.getLineTop(line)..l.getLineBottom(line)
            if (down.position.x > boxRight + 8f || !inLine) return@awaitEachGesture
            down.consume()
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                ev.changes.forEach { it.consume() }
                if (ev.changes.all { !it.pressed }) break
            }
            onToggle(ls)
        }
    }

@Composable
private fun ImageBlock(file: java.io.File, onRemove: () -> Unit) {
    val c = AppTheme.colors
    var bmp by remember(file.path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.path) {
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, o)
                var sample = 1
                while (o.outWidth / sample > 1600) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val b = bmp
        if (b != null) Image(
            b, contentDescription = "รูปในบันทึก", contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        ) else Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(12.dp)).background(c.surface), contentAlignment = Alignment.Center) {
            Text("กำลังโหลดรูป…", color = c.muted, style = MaterialTheme.typography.bodySmall)
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(32.dp).clip(CircleShape).background(Color(0x99000000)).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.Close, contentDescription = "เอารูปออก", tint = Color.White, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun AttachmentBlock(b: NoteBlock.Attachment, onOpen: () -> Unit, onRemove: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).clickable(onClick = onOpen).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.AttachFile, contentDescription = null, tint = c.ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(b.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sizeLabel(b.size), style = MaterialTheme.typography.labelSmall, color = c.muted)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "เอาไฟล์แนบออก", tint = c.muted) }
    }
}

private fun sizeLabel(bytes: Long) = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}
