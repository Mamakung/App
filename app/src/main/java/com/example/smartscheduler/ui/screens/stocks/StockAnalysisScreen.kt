package com.example.smartscheduler.ui.screens.stocks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import com.example.smartscheduler.data.local.entity.StockNoteEntity
import com.example.smartscheduler.data.local.entity.StockRecommendationEntity
import com.example.smartscheduler.data.repository.StockRepository
import com.example.smartscheduler.data.session.CurrentUser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class StockAnalysisUiState(
    val symbol: String = "",
    val market: String = "",
    val recommendation: StockRecommendationEntity? = null,
    val notes: List<StockNoteEntity> = emptyList(),
    val isGenerating: Boolean = false,
    val draftNote: String = ""
)

@HiltViewModel
class StockAnalysisViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val stockRepository: StockRepository,
    private val firebaseAuth: CurrentUser
) : ViewModel() {

    private val symbol: String = checkNotNull(savedStateHandle["symbol"])
    private val market: String = checkNotNull(savedStateHandle["market"])
    private val json = Json { ignoreUnknownKeys = true }
    private val uid get() = firebaseAuth.uid

    private val draftNote = MutableStateFlow("")
    private val isGenerating = MutableStateFlow(false)

    val state: StateFlow<StockAnalysisUiState> = combine(
        stockRepository.observeRecommendation(symbol, market),
        stockRepository.observeNotes(uid, symbol, market),
        draftNote,
        isGenerating
    ) { recommendation, notes, draft, generating ->
        StockAnalysisUiState(symbol, market, recommendation, notes, generating, draft)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StockAnalysisUiState(symbol, market))

    fun refreshRecommendation() {
        isGenerating.value = true
        viewModelScope.launch {
            try {
                stockRepository.generateRecommendation(symbol, market)
            } catch (e: Exception) {
                // ยังไม่มี API จริง / ออฟไลน์: ไม่ให้แอปปิดตัว
            } finally {
                isGenerating.value = false
            }
        }
    }

    fun onDraftNoteChange(text: String) { draftNote.value = text }

    fun saveNote() {
        val text = draftNote.value.trim()
        if (text.isBlank()) return
        viewModelScope.launch {
            stockRepository.addNote(uid, symbol, market, text)
            draftNote.value = ""
        }
    }

    fun deleteNote(note: StockNoteEntity) {
        viewModelScope.launch { stockRepository.deleteNote(note) }
    }

    fun reasoningList(rec: StockRecommendationEntity): List<String> =
        runCatching { json.decodeFromString<List<String>>(rec.reasoningJson) }.getOrDefault(emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockAnalysisScreen(onBack: () -> Unit, viewModel: StockAnalysisViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${state.symbol} · ${state.market}") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Button(onClick = viewModel::refreshRecommendation, enabled = !state.isGenerating, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.isGenerating) "Analyzing…" else "Generate / Refresh AI Analysis")
                }
            }

            val rec = state.recommendation
            if (rec == null) {
                item { Text("No analysis yet. Tap the button above to generate a Buy/Hold/Sell signal.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                item { SignalCard(rec) }

                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Key Indicators", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            IndicatorRow("P/E Ratio", rec.peRatio?.let { "%.2f".format(it) } ?: "N/A")
                            IndicatorRow("Dividend Yield", rec.dividendYieldPercent?.let { "%.2f%%".format(it) } ?: "N/A")
                            IndicatorRow("Technical Signal", rec.technicalSignal ?: "N/A")
                            IndicatorRow("Confidence", "%.0f%%".format(rec.confidence * 100))
                        }
                    }
                }

                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Reasoning Breakdown", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            viewModel.reasoningList(rec).forEach { bullet ->
                                Row(Modifier.padding(vertical = 2.dp)) {
                                    Text("•  ", fontWeight = FontWeight.SemiBold)
                                    Text(bullet, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Risk Disclaimer", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(rec.riskDisclaimer, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item { HorizontalDivider() }

            // Spec #8: personal notes per stock
            item { Text("My Notes", style = MaterialTheme.typography.titleMedium) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.draftNote,
                        onValueChange = viewModel::onDraftNoteChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Add a personal note…") }
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = viewModel::saveNote) { Text("Save") }
                }
            }
            items(state.notes, key = { it.noteId }) { note ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(note.noteText, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.deleteNote(note) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalCard(rec: StockRecommendationEntity) {
    val (label, color) = when (rec.signal) {
        "BUY" -> "BUY" to Color(0xFF2E7D32)
        "SELL" -> "SELL" to Color(0xFFC62828)
        else -> "HOLD" to Color(0xFFF9A825)
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.displaySmall, color = color, fontWeight = FontWeight.ExtraBold)
            Text("AI Recommendation", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun IndicatorRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}
