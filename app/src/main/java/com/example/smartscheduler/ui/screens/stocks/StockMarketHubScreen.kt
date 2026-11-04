package com.example.smartscheduler.ui.screens.stocks

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartscheduler.data.local.entity.WatchlistItemEntity
import com.example.smartscheduler.data.repository.StockRepository
import com.example.smartscheduler.ui.components.EmptyState
import com.example.smartscheduler.ui.components.Hint
import com.example.smartscheduler.ui.components.Panel
import com.example.smartscheduler.ui.components.SectionTitle
import com.example.smartscheduler.ui.components.SegmentedToggle
import com.example.smartscheduler.ui.theme.AppTheme
import com.example.smartscheduler.data.session.CurrentUser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import javax.inject.Inject

// ============================================================================
// ViewModel
// ============================================================================

data class StockHubUiState(
    val market: String = "TH",
    val watchlist: List<WatchlistItemEntity> = emptyList(),
    val newsBullets: List<String> = emptyList(),
    val newsSourceCount: Int = 0,
    val refreshing: Boolean = false,
    val error: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StockHubViewModel @Inject constructor(
    private val repo: StockRepository,
    private val auth: CurrentUser
) : ViewModel() {

    private val market = MutableStateFlow("TH")
    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val json = Json { ignoreUnknownKeys = true }
    private val uid get() = auth.uid

    val state: StateFlow<StockHubUiState> = market.flatMapLatest { m ->
        combine(
            repo.observeWatchlist(uid, m),
            repo.observeNewsSummary(m, LocalDate.now()),
            refreshing,
            error
        ) { list, summary, busy, err ->
            StockHubUiState(
                market = m,
                watchlist = list,
                newsBullets = summary?.bulletPointsJson
                    ?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }
                    .orEmpty(),
                newsSourceCount = summary?.sourceArticleCount ?: 0,
                refreshing = busy,
                error = err
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StockHubUiState())

    fun selectMarket(m: String) { market.value = m }

    /** ดึงราคาและสรุปข่าวใหม่ ถ้าเชื่อมต่อไม่ได้จะแสดงข้อความแทนการปิดแอป */
    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        error.value = null
        viewModelScope.launch {
            try {
                repo.refreshAllWatchlistQuotes(uid)
                repo.generateDailyNewsSummary(market.value)
            } catch (e: Exception) {
                error.value = "อัปเดตไม่สำเร็จ ตรวจอินเทอร์เน็ตแล้วลองอีกครั้ง"
            } finally {
                refreshing.value = false
            }
        }
    }

    fun consumeError() { error.value = null }

    fun add(symbol: String, name: String) {
        viewModelScope.launch {
            repo.addToWatchlist(uid, symbol.trim().uppercase(), market.value, name.trim().ifBlank { symbol.trim().uppercase() })
        }
    }

    fun remove(symbol: String) {
        viewModelScope.launch { repo.removeFromWatchlist(uid, symbol, market.value) }
    }
}

// ============================================================================
// Screen
// ============================================================================

@Composable
fun StockMarketHubScreen(
    onOpenStockDetail: (symbol: String, market: String) -> Unit,
    viewModel: StockHubViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<WatchlistItemEntity?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val c = AppTheme.colors

    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it); viewModel.consumeError() }
    }

    Box(Modifier.fillMaxSize().background(c.ground)) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("หุ้น", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    if (state.refreshing) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.ink)
                    } else {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "อัปเดตราคาและข่าว", tint = c.ink)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                SegmentedToggle(
                    options = listOf("ไทย (SET)", "สหรัฐฯ"),
                    selectedIndex = if (state.market == "TH") 0 else 1,
                    onSelect = { viewModel.selectMarket(if (it == 0) "TH" else "US") }
                )
                Spacer(Modifier.height(12.dp))
                MarketStatusLine(state.watchlist.firstOrNull()?.marketStatus)
            }

            item {
                SectionTitle("สรุปข่าววันนี้", Modifier.padding(top = 8.dp))
                Panel {
                    if (state.newsBullets.isEmpty()) {
                        Text("ยังไม่มีสรุปข่าวของวันนี้", style = MaterialTheme.typography.bodyLarge)
                        Hint("แตะปุ่มอัปเดตด้านบนเพื่อให้ AI สรุปข่าวตลาด${if (state.market == "TH") "ไทย" else "สหรัฐฯ"}")
                    } else {
                        state.newsBullets.forEachIndexed { i, b ->
                            if (i > 0) HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 10.dp))
                            Row {
                                Box(Modifier.padding(top = 8.dp).size(6.dp).clip(CircleShape).background(c.ink))
                                Spacer(Modifier.width(10.dp))
                                Text(b, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Hint("สรุปโดย AI จากข่าว ${state.newsSourceCount} ชิ้น ไม่ใช่คำแนะนำการลงทุน")
                    }
                }
            }

            item {
                SectionTitle("หุ้นที่ติดตาม", Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = { adding = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = c.work)
                        Spacer(Modifier.width(4.dp))
                        Text("เพิ่มหุ้น", color = c.work)
                    }
                }
                if (state.watchlist.isEmpty()) {
                    Panel {
                        EmptyState(
                            icon = Icons.Filled.ShowChart,
                            title = "ยังไม่ได้ติดตามหุ้น",
                            subtitle = "เพิ่มหุ้นที่สนใจเพื่อดูราคาและการเปลี่ยนแปลงรายวัน",
                            actionLabel = "เพิ่มหุ้น",
                            onAction = { adding = true }
                        )
                    }
                } else {
                    Panel(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                        state.watchlist.forEachIndexed { i, item ->
                            if (i > 0) HorizontalDivider(color = c.line)
                            WatchRow(
                                item = item,
                                onClick = { onOpenStockDetail(item.symbol, item.market) },
                                onLongRemove = { removing = item }
                            )
                        }
                    }
                    Hint("แตะเพื่อดูการวิเคราะห์ กดค้างเพื่อเลิกติดตาม", Modifier.padding(top = 8.dp))
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (adding) {
        AddStockDialog(
            market = state.market,
            onAdd = { s, n -> viewModel.add(s, n); adding = false },
            onDismiss = { adding = false }
        )
    }
    removing?.let { item ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = c.surface,
            title = { Text("เลิกติดตาม ${item.symbol}?") },
            text = { Text("โน้ตที่เขียนไว้ของหุ้นนี้ยังเก็บไว้") },
            confirmButton = { TextButton(onClick = { viewModel.remove(item.symbol); removing = null }) { Text("เลิกติดตาม") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("ยกเลิก") } }
        )
    }
}

@Composable
private fun MarketStatusLine(status: String?) {
    val c = AppTheme.colors
    val (label, color) = when (status) {
        "OPEN" -> "ตลาดเปิดอยู่" to c.up
        "PRE_MARKET" -> "ก่อนตลาดเปิด" to c.task
        "AFTER_HOURS" -> "หลังตลาดปิด" to c.task
        "CLOSED" -> "ตลาดปิด" to c.muted
        else -> "ยังไม่มีข้อมูลสถานะตลาด" to c.line
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.muted)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WatchRow(item: WatchlistItemEntity, onClick: () -> Unit, onLongRemove: () -> Unit) {
    val c = AppTheme.colors
    val change = item.dayChangePercent
    val symbol = if (item.currency == "USD") "$" else "฿"
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongRemove)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(item.symbol, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(80.dp))
        Text(item.companyName, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.weight(1f), maxLines = 1)
        Column(horizontalAlignment = Alignment.End) {
            Text(
                item.lastPrice?.let { if (item.currency == "USD") "$%.2f".format(it) else "%.2f $symbol".format(it) } ?: "–",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                change?.let { (if (it >= 0) "+" else "−") + "%.2f%%".format(kotlin.math.abs(it)) } ?: "ยังไม่มีราคา",
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    change == null -> c.muted
                    change >= 0 -> c.up
                    else -> c.down
                }
            )
        }
    }
}

@Composable
private fun AddStockDialog(market: String, onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    var symbol by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppTheme.colors.surface,
        title = { Text(if (market == "TH") "เพิ่มหุ้นไทย" else "เพิ่มหุ้นสหรัฐฯ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = symbol,
                    onValueChange = { symbol = it.filter { ch -> !ch.isWhitespace() }.take(12) },
                    label = { Text("ชื่อย่อหุ้น") },
                    placeholder = { Text(if (market == "TH") "เช่น PTT" else "เช่น AAPL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("ชื่อบริษัท (ไม่บังคับ)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(enabled = symbol.isNotBlank(), onClick = { onAdd(symbol, name) }) { Text("เพิ่ม") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    )
}
