package com.example.smartscheduler.ui.screens.notes

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartscheduler.data.local.entity.NoteEntity
import com.example.smartscheduler.data.repository.NoteRepository
import com.example.smartscheduler.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.sqrt

/** โหมดอ่าน: ปกติ / ถนอมสายตา (โทนอุ่น) / กลางคืน (กลับสี พื้นดำ ตัวหนังสือสว่าง) */
enum class ReadingTheme(val label: String) { NORMAL("ปกติ"), SEPIA("ถนอมสายตา"), NIGHT("กลางคืน") }

/** ทิศการอ่าน: เลื่อนขึ้นลง / พลิกซ้ายขวาทีละหน้า */
enum class ReadDirection(val label: String) { VERTICAL("เลื่อนขึ้นลง"), HORIZONTAL("พลิกซ้ายขวา (ทีละหน้า)") }

@HiltViewModel
class PdfReaderViewModel @Inject constructor(
    private val repo: NoteRepository,
    @ApplicationContext private val context: Context,
    savedState: SavedStateHandle
) : ViewModel() {
    private val id = savedState.get<Long>("id") ?: -1L
    private val prefs = context.getSharedPreferences("pdf_reader", Context.MODE_PRIVATE)

    private val _note = MutableStateFlow<NoteEntity?>(null)
    val note: StateFlow<NoteEntity?> = _note.asStateFlow()
    /** สัดส่วน สูง/กว้าง ของแต่ละหน้า (null = ยังเปิดไม่เสร็จ / เปิดไม่ได้) */
    private val _ratios = MutableStateFlow<List<Float>?>(null)
    val ratios: StateFlow<List<Float>?> = _ratios.asStateFlow()
    private val _error = MutableStateFlow(false)
    val error: StateFlow<Boolean> = _error.asStateFlow()
    private val _theme = MutableStateFlow(runCatching { ReadingTheme.valueOf(prefs.getString("theme", null) ?: "") }.getOrDefault(ReadingTheme.NORMAL))
    val theme: StateFlow<ReadingTheme> = _theme.asStateFlow()
    private val _direction = MutableStateFlow(runCatching { ReadDirection.valueOf(prefs.getString("direction", null) ?: "") }.getOrDefault(ReadDirection.VERTICAL))
    val direction: StateFlow<ReadDirection> = _direction.asStateFlow()
    fun setDirection(d: ReadDirection) { _direction.value = d; prefs.edit().putString("direction", d.name).apply() }

    private var fd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val lock = Mutex()
    // แคชรูปหน้า (จำกัดตามหน่วยความจำ)
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val n = repo.getNote(id)
            _note.value = n
            val path = n?.filePath
            if (path == null) { _error.value = true; return@launch }
            runCatching {
                lock.withLock {
                    val f = ParcelFileDescriptor.open(repo.fileOf(path), ParcelFileDescriptor.MODE_READ_ONLY)
                    val r = PdfRenderer(f)
                    fd = f; renderer = r
                    _ratios.value = (0 until r.pageCount).map { i -> r.openPage(i).use { it.height.toFloat() / it.width.coerceAtLeast(1) } }
                }
            }.onFailure { _error.value = true }
        }
    }

    fun setTheme(t: ReadingTheme) { _theme.value = t; prefs.edit().putString("theme", t.name).apply() }

    /** วาดหน้า [index] กว้าง [width] พิกเซล (จำกัดไม่เกิน ~8 ล้านพิกเซล) */
    suspend fun render(index: Int, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        val ratio = _ratios.value?.getOrNull(index) ?: return@withContext null
        var w = width.coerceIn(60, 4096)
        if (w * w * ratio > 8_000_000f) w = sqrt(8_000_000f / ratio).toInt()
        val key = "$index@$w"
        cache.get(key)?.let { return@withContext it }
        lock.withLock {
            val r = renderer ?: return@withLock null
            runCatching {
                r.openPage(index).use { page ->
                    val h = (w * ratio).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(AColor.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    cache.put(key, bmp)
                    bmp
                }
            }.getOrNull()
        }
    }

    private var saveJob: Job? = null
    fun pageChanged(page: Int) {
        val n = _note.value ?: return
        if (n.lastPage == page) return
        _note.value = n.copy(lastPage = page)
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(600); repo.setLastPage(n.noteId, page) }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        val n = _note.value
        if (n != null) GlobalScope.launch(NonCancellable) { repo.setLastPage(n.noteId, n.lastPage) }
        runCatching { renderer?.close() }
        runCatching { fd?.close() }
        cache.evictAll()
    }
}

private fun themeFilter(t: ReadingTheme): ColorFilter? = when (t) {
    ReadingTheme.NORMAL -> null
    // โทนอุ่น: ลดฟ้า เพิ่มเหลือง (พื้นขาวกลายเป็นครีม)
    ReadingTheme.SEPIA -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        0.98f, 0f, 0f, 0f, 0f,
        0f, 0.93f, 0f, 0f, 0f,
        0f, 0f, 0.80f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )))
    // กลับสี: พื้นเทาเข้ม ตัวหนังสือเทาอ่อน (ไม่ขาวจ้า)
    ReadingTheme.NIGHT -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        -0.85f, 0f, 0f, 0f, 235f,
        0f, -0.85f, 0f, 0f, 235f,
        0f, 0f, -0.85f, 0f, 230f,
        0f, 0f, 0f, 1f, 0f
    )))
}

private fun themeBackground(t: ReadingTheme) = when (t) {
    ReadingTheme.NORMAL -> Color(0xFF2B3330)
    ReadingTheme.SEPIA -> Color(0xFF3A3328)
    ReadingTheme.NIGHT -> Color(0xFF0B0E0D)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PdfReaderScreen(onBack: () -> Unit, viewModel: PdfReaderViewModel = hiltViewModel()) {
    val c = AppTheme.colors
    val note by viewModel.note.collectAsState()
    val ratios by viewModel.ratios.collectAsState()
    val error by viewModel.error.collectAsState()
    val theme by viewModel.theme.collectAsState()
    val direction by viewModel.direction.collectAsState()
    val horizontal = direction == ReadDirection.HORIZONTAL
    var dirMenu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val thumbs = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var bars by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var goTo by remember { mutableStateOf(false) }
    var resume by remember { mutableStateOf<Int?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var boxWidth by remember { mutableIntStateOf(0) }
    val pages = ratios?.size ?: 0
    val pager = androidx.compose.foundation.pager.rememberPagerState { pages }
    fun jump(i: Int, animate: Boolean = false) {
        if (pages == 0) return
        val p = i.coerceIn(0, pages - 1)
        scope.launch {
            if (horizontal) { if (animate) pager.animateScrollToPage(p) else pager.scrollToPage(p) }
            else list.scrollToItem(p)
        }
    }

    BackHandler { onBack() }

    // อ่านอยู่: จอไม่ดับเอง / ซ่อนแถบด้านบนของระบบเมื่อซ่อนแถบเครื่องมือ
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            (view.context as? Activity)?.window?.let { w ->
                WindowCompat.getInsetsController(w, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    LaunchedEffect(bars) {
        (view.context as? Activity)?.window?.let { w ->
            val ctl = WindowCompat.getInsetsController(w, view)
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (bars) ctl.show(WindowInsetsCompat.Type.systemBars()) else ctl.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // เปิดมา: กลับไปหน้าที่อ่านค้าง
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(pages, note?.noteId) {
        val n = note ?: return@LaunchedEffect
        if (!restored && pages > 0) {
            restored = true
            val last = n.lastPage.coerceIn(0, pages - 1)
            if (last > 0) { jump(last); resume = last; delay(4500); resume = null }
        }
    }

    // หน้าปัจจุบัน = หน้าที่กินพื้นที่จอมากสุด (โหมดพลิก = หน้าที่แสดงอยู่)
    val listCurrent by remember {
        derivedStateOf {
            val info = list.layoutInfo
            info.visibleItemsInfo.maxByOrNull { item ->
                val top = maxOf(item.offset, info.viewportStartOffset)
                val bottom = minOf(item.offset + item.size, info.viewportEndOffset)
                bottom - top
            }?.index ?: list.firstVisibleItemIndex
        }
    }
    val current = if (horizontal) pager.currentPage else listCurrent
    LaunchedEffect(current, restored) { if (restored) viewModel.pageChanged(current) }
    // เปลี่ยนทิศการอ่าน: อยู่หน้าเดิม
    LaunchedEffect(direction) { if (restored) note?.let { jump(it.lastPage) } }
    LaunchedEffect(current) { if (pages > 0) runCatching { thumbs.animateScrollToItem((current - 3).coerceAtLeast(0)) } }

    val filter = remember(theme) { themeFilter(theme) }
    // ซูมแล้ววาดใหม่ให้คมขึ้น (แบ่งเป็นขั้น ไม่วาดใหม่ทุกครั้งที่ขยับนิ้ว)
    val quality = when { scale > 2.6f -> 3f; scale > 1.6f -> 2f; else -> 1f }

    Box(Modifier.fillMaxSize().background(themeBackground(theme))) {
        when {
            error -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.BrokenImage, contentDescription = null, tint = c.muted, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("เปิดไฟล์นี้ไม่ได้", color = c.ink, style = MaterialTheme.typography.titleMedium)
                Text("ไฟล์อาจเสียหรือมีรหัสผ่าน", color = c.muted, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onBack) { Text("กลับ") }
            }
            ratios == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = c.ink)
            else -> Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { boxWidth = it.width }
                    // บีบนิ้วซูม / ลากซ้ายขวาเมื่อซูมอยู่ (ลากขึ้นลง = เลื่อนหน้าตามปกติ)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val ev = awaitPointerEvent()
                                val count = ev.changes.count { it.pressed }
                                if (count >= 2) {
                                    val z = ev.calculateZoom()
                                    val pan = ev.calculatePan()
                                    scale = (scale * z).coerceIn(1f, 4f)
                                    val max = (scale - 1f) * size.width / 2f
                                    offsetX = (offsetX + pan.x).coerceIn(-max, max)
                                    ev.changes.forEach { it.consume() }
                                } else if (count == 1 && scale > 1f) {
                                    val ch = ev.changes.first { it.pressed }
                                    val d = ch.positionChange()
                                    if (abs(d.x) > abs(d.y)) {
                                        val max = (scale - 1f) * size.width / 2f
                                        offsetX = (offsetX + d.x).coerceIn(-max, max)
                                        ch.consume()
                                    }
                                }
                            } while (ev.changes.any { it.pressed })
                            if (scale < 1.05f) { scale = 1f; offsetX = 0f }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { bars = !bars },
                            onDoubleTap = { if (scale > 1f) { scale = 1f; offsetX = 0f } else scale = 2f }
                        )
                    }
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale, translationX = offsetX,
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    )
            ) {
                if (horizontal) {
                    androidx.compose.foundation.pager.HorizontalPager(
                        state = pager,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = scale <= 1.01f,
                        beyondBoundsPageCount = 1
                    ) { i ->
                        val ratio = ratios!![i]
                        Box(Modifier.fillMaxSize().padding(top = 72.dp, bottom = 120.dp, start = 10.dp, end = 10.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.aspectRatio(1f / ratio.coerceAtLeast(0.05f), matchHeightConstraintsFirst = true)) {
                                PageImage(viewModel, i, ratio, (boxWidth * quality).toInt(), filter)
                            }
                        }
                    }
                } else LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 72.dp, bottom = 120.dp, start = 8.dp, end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(ratios!!) { i, ratio ->
                        PageImage(viewModel, i, ratio, (boxWidth * quality).toInt(), filter)
                    }
                }
            }
        }

        // โหมดพลิก: ลูกศรขอบจอ
        if (horizontal && bars && pages > 0 && scale <= 1.01f) {
            if (current > 0) EdgeArrow(Icons.Filled.ChevronLeft, "หน้าก่อน", Modifier.align(Alignment.CenterStart)) { jump(current - 1, animate = true) }
            if (current < pages - 1) EdgeArrow(Icons.Filled.ChevronRight, "หน้าถัดไป", Modifier.align(Alignment.CenterEnd)) { jump(current + 1, animate = true) }
        }

        // ป้ายซูม
        AnimatedVisibility(visible = scale > 1.01f, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter).padding(top = if (bars) 76.dp else 24.dp)) {
            Pill("${(scale * 100).toInt()}% · แตะ 2 ครั้งเพื่อกลับขนาดปกติ")
        }

        // แถบบน
        AnimatedVisibility(visible = bars, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().background(c.ground.copy(alpha = 0.94f)).statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "กลับ") }
                Text(note?.title ?: "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (pages > 0) Text(
                    "${current + 1} / $pages", style = MaterialTheme.typography.labelLarge, color = c.muted,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { goTo = true }.padding(horizontal = 8.dp, vertical = 6.dp)
                )
                Box {
                    IconButton(onClick = { dirMenu = true }) {
                        Icon(if (horizontal) Icons.Filled.SwapHoriz else Icons.Filled.SwapVert, contentDescription = "ทิศการอ่าน: ${direction.label}")
                    }
                    DropdownMenu(expanded = dirMenu, onDismissRequest = { dirMenu = false }) {
                        ReadDirection.values().forEach { d ->
                            DropdownMenuItem(
                                text = { Text(d.label) },
                                leadingIcon = { Icon(if (d == ReadDirection.HORIZONTAL) Icons.Filled.SwapHoriz else Icons.Filled.SwapVert, null) },
                                trailingIcon = { if (direction == d) Icon(Icons.Filled.Check, null) },
                                onClick = { viewModel.setDirection(d); dirMenu = false }
                            )
                        }
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.Contrast, contentDescription = "โหมดอ่าน") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        ReadingTheme.values().forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.label) },
                                leadingIcon = { if (theme == t) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
                                onClick = { viewModel.setTheme(t); menu = false }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("ไปหน้า…") }, leadingIcon = { Icon(Icons.Filled.FormatListNumbered, null) }, onClick = { menu = false; goTo = true })
                    }
                }
            }
        }

        // อ่านต่อจากหน้าเดิม
        AnimatedVisibility(visible = resume != null && bars, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter).padding(top = 84.dp, start = 16.dp, end = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.History, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("อ่านต่อจากหน้า ${(resume ?: 0) + 1}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { resume = null; jump(0) }) { Text("เริ่มหน้าแรก") }
            }
        }

        // แถบล่าง: หน้าเล็ก + แถบเลื่อนหน้า
        AnimatedVisibility(visible = bars && pages > 0, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.fillMaxWidth().background(c.ground.copy(alpha = 0.96f)).navigationBarsPadding().padding(top = 10.dp, bottom = 6.dp)) {
                LazyRow(state = thumbs, contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(ratios.orEmpty()) { i, ratio ->
                        val selected = i == current
                        Box(
                            Modifier
                                .width(34.dp)
                                .height((34 * ratio).coerceIn(30f, 60f).dp)
                                .clip(RoundedCornerShape(3.dp))
                                .then(if (selected) Modifier.border(2.dp, c.now, RoundedCornerShape(3.dp)) else Modifier)
                                .clickable { jump(i) }
                                .semantics { contentDescription = "ไปหน้า ${i + 1}" }
                        ) { PageImage(viewModel, i, ratio, 110, filter, thumb = true) }
                    }
                }
                var drag by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = drag ?: current.toFloat(),
                    onValueChange = { drag = it; jump(it.toInt()) },
                    onValueChangeFinished = { drag = null },
                    valueRange = 0f..(pages - 1).coerceAtLeast(1).toFloat(),
                    modifier = Modifier.padding(horizontal = 12.dp).height(32.dp)
                )
                Text(
                    if (horizontal) "ปัดหรือแตะลูกศรเพื่อพลิก · บีบนิ้วซูม · แตะกลางจอซ่อนแถบ" else "แตะกลางจอซ่อน/แสดงแถบ · บีบนิ้วซูม · แตะหน้าเล็กเพื่อกระโดด",
                    style = MaterialTheme.typography.labelSmall, color = c.muted, modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }

    if (goTo && pages > 0) {
        var text by remember { mutableStateOf("${current + 1}") }
        AlertDialog(
            onDismissRequest = { goTo = false },
            containerColor = c.surface,
            title = { Text("ไปหน้า") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.filter(Char::isDigit).take(5) }, singleLine = true,
                    label = { Text("หน้า (1–$pages)") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = (text.toIntOrNull() ?: 1).coerceIn(1, pages) - 1
                    jump(p)
                    goTo = false
                }) { Text("ไป") }
            },
            dismissButton = { TextButton(onClick = { goTo = false }) { Text("ยกเลิก") } }
        )
    }
}

@Composable
private fun EdgeArrow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.padding(6.dp).size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(AppTheme.colors.ground.copy(alpha = 0.7f)).clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = null, tint = AppTheme.colors.ink) }
}

@Composable
private fun Pill(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.ink,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(AppTheme.colors.ground.copy(alpha = 0.9f)).padding(horizontal = 14.dp, vertical = 6.dp)
    )
}

@Composable
private fun PageImage(vm: PdfReaderViewModel, index: Int, ratio: Float, widthPx: Int, filter: ColorFilter?, thumb: Boolean = false) {
    var bmp by remember(index) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(index, widthPx) {
        if (widthPx > 0) vm.render(index, widthPx)?.let { bmp = it.asImageBitmap() }
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f / ratio.coerceAtLeast(0.05f)).background(Color(0x33FFFFFF))
    ) {
        bmp?.let {
            Image(
                it, contentDescription = if (thumb) null else "หน้า ${index + 1}",
                contentScale = ContentScale.FillBounds, colorFilter = filter, modifier = Modifier.fillMaxSize()
            )
        }
    }
}
