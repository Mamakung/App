package com.example.smartscheduler.data.repository

import com.example.smartscheduler.data.local.dao.*
import com.example.smartscheduler.data.local.entity.*
import com.example.smartscheduler.data.remote.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StockRepository @Inject constructor(
    private val quoteApi: StockQuoteApi,
    private val newsApi: NewsFeedApi,
    private val aiApi: AiAssistantApi,
    private val watchlistDao: WatchlistDao,
    private val stockNoteDao: StockNoteDao,
    private val marketNewsDao: MarketNewsDao,
    private val recommendationDao: StockRecommendationDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ---------- Spec #7: Dual-market quotes ----------

    suspend fun fetchQuote(symbol: String, market: String): QuoteDto = quoteApi.getQuote(symbol, market)

    suspend fun fetchMarketStatus(market: String): MarketStatusDto = quoteApi.getMarketStatus(market)

    suspend fun searchSymbols(query: String, market: String): List<SymbolSearchResultDto> =
        quoteApi.searchSymbols(query, market)

    // ---------- Spec #8: Watchlist + notes ----------

    fun observeWatchlist(ownerUid: String, market: String): Flow<List<WatchlistItemEntity>> =
        watchlistDao.observeWatchlist(ownerUid, market)

    suspend fun addToWatchlist(ownerUid: String, symbol: String, market: String, companyName: String) {
        watchlistDao.add(
            WatchlistItemEntity(
                ownerUid = ownerUid, symbol = symbol, market = market, companyName = companyName,
                currency = if (market == "US") "USD" else "THB"
            )
        )
        // ราคาโหลดไม่ได้ (ออฟไลน์/ยังไม่ต่อ API) ก็ยังเก็บหุ้นไว้ในรายการได้
        runCatching { refreshQuoteInWatchlist(symbol, market) }
    }

    suspend fun removeFromWatchlist(ownerUid: String, symbol: String, market: String) =
        watchlistDao.remove(ownerUid, symbol, market)

    /** Pulls the latest quote and updates the cached watchlist row (call from a periodic WorkManager job or pull-to-refresh). */
    suspend fun refreshQuoteInWatchlist(symbol: String, market: String) {
        val quote = quoteApi.getQuote(symbol, market)
        watchlistDao.updateQuote(symbol, market, quote.price, quote.changePercent, quote.marketStatus)
    }

    suspend fun refreshAllWatchlistQuotes(ownerUid: String) {
        val items = watchlistDao.getAllOnce(ownerUid)
        val bySymbolMarket = items.groupBy { it.market }
        bySymbolMarket.forEach { (market, group) ->
            val symbols = group.joinToString(",") { it.symbol }
            if (symbols.isEmpty()) return@forEach
            val quotes = quoteApi.getQuotes(symbols, market)
            quotes.forEach { q ->
                watchlistDao.updateQuote(q.symbol, market, q.price, q.changePercent, q.marketStatus)
            }
        }
    }

    fun observeNotes(ownerUid: String, symbol: String, market: String): Flow<List<StockNoteEntity>> =
        stockNoteDao.observeNotes(ownerUid, symbol, market)

    suspend fun addNote(ownerUid: String, symbol: String, market: String, text: String) =
        stockNoteDao.addNote(StockNoteEntity(ownerUid = ownerUid, symbol = symbol, market = market, noteText = text))

    suspend fun deleteNote(note: StockNoteEntity) = stockNoteDao.deleteNote(note)

    // ---------- Spec #9: Daily AI news summarizer ----------

    fun observeNewsSummary(market: String, date: LocalDate): Flow<MarketNewsSummaryEntity?> =
        marketNewsDao.observeSummary(market, date.toEpochDay())

    /**
     * Fetches raw articles, sends them to the LLM summarization endpoint, and caches
     * the result so it is only regenerated once per day per market.
     */
    suspend fun generateDailyNewsSummary(market: String, date: LocalDate = LocalDate.now()): MarketNewsSummaryEntity {
        val articles = newsApi.getTopNews(market = market, limit = 20)
        val response = aiApi.summarizeNews(
            SummarizeNewsRequest(market = market, articles = articles, maxBulletPoints = 6)
        )
        val entity = MarketNewsSummaryEntity(
            market = market,
            summaryDateEpochDay = date.toEpochDay(),
            bulletPointsJson = json.encodeToString(response.bulletPoints),
            sourceArticleCount = articles.size
        )
        marketNewsDao.upsert(entity)
        return entity
    }

    // ---------- Spec #10: Buy/Hold/Sell recommendation engine ----------

    fun observeRecommendation(symbol: String, market: String): Flow<StockRecommendationEntity?> =
        recommendationDao.observeRecommendation(symbol, market)

    suspend fun generateRecommendation(symbol: String, market: String): StockRecommendationEntity {
        val fundamentals = quoteApi.getFundamentals(symbol, market)
        val technicals = quoteApi.getTechnicals(symbol, market)
        val recentNews = newsApi.getTopNews(market, limit = 10).filter { symbol in it.relatedSymbols }

        val response = aiApi.analyzeStock(
            AnalyzeStockRequest(
                symbol = symbol,
                market = market,
                fundamentals = fundamentals,
                technicals = technicals,
                recentNews = recentNews
            )
        )
        val entity = StockRecommendationEntity(
            symbol = symbol,
            market = market,
            signal = response.signal,
            confidence = response.confidence,
            peRatio = fundamentals.peRatio,
            dividendYieldPercent = fundamentals.dividendYieldPercent,
            technicalSignal = technicals.macdSignal,
            reasoningJson = json.encodeToString(response.reasoning),
            riskDisclaimer = response.riskDisclaimer.ifBlank { StockRecommendationEntity.DEFAULT_DISCLAIMER }
        )
        recommendationDao.upsert(entity)
        return entity
    }
}
