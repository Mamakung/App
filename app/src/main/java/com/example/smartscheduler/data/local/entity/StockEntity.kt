package com.example.smartscheduler.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A ticker the user has favorited ("Stocks I Like" - Spec #8).
 * Live price/percent-change fields are cached locally for offline display and
 * are refreshed by StockRepository from the remote API; they are NOT the source
 * of truth for real-time data.
 */
@Entity(
    tableName = "watchlist_items",
    indices = [Index(value = ["ownerUid", "symbol", "market"], unique = true)]
)
data class WatchlistItemEntity(
    @PrimaryKey(autoGenerate = true) val watchlistItemId: Long = 0,
    val ownerUid: String,
    val symbol: String,              // e.g. "PTT", "AAPL"
    val market: String,              // "TH" (SET) or "US" (NYSE/NASDAQ)
    val companyName: String,
    val addedAtEpochMillis: Long = System.currentTimeMillis(),
    // --- cached last-known quote (refreshed periodically) ---
    val lastPrice: Double? = null,
    val currency: String = "THB",    // THB or USD
    val dayChangePercent: Double? = null,
    val marketStatus: String = "UNKNOWN", // OPEN / CLOSED / PRE_MARKET / AFTER_HOURS
    val lastQuoteUpdateEpochMillis: Long? = null
)

/**
 * Free-text personal notes attached per stock (Spec #8: "attach personal notes per stock").
 * Kept as its own table (rather than a column) to support multiple timestamped notes per symbol.
 */
@Entity(
    tableName = "stock_notes",
    indices = [Index(value = ["ownerUid", "symbol", "market"])]
)
data class StockNoteEntity(
    @PrimaryKey(autoGenerate = true) val noteId: Long = 0,
    val ownerUid: String,
    val symbol: String,
    val market: String,
    val noteText: String,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val updatedAtEpochMillis: Long = System.currentTimeMillis()
)

/**
 * Cached daily AI-generated news summary (Spec #9), stored per market so it can be
 * shown offline and regenerated at most once per day.
 */
@Entity(
    tableName = "market_news_summaries",
    indices = [Index(value = ["market", "summaryDateEpochDay"], unique = true)]
)
data class MarketNewsSummaryEntity(
    @PrimaryKey(autoGenerate = true) val summaryId: Long = 0,
    val market: String,                 // "TH" or "US"
    val summaryDateEpochDay: Long,
    val bulletPointsJson: String,       // JSON array of short bullet strings
    val generatedAtEpochMillis: Long = System.currentTimeMillis(),
    val sourceArticleCount: Int = 0
)

/**
 * Cached AI Buy/Hold/Sell recommendation (Spec #10) so the analysis screen loads
 * instantly and can display "last computed at" timestamps.
 */
@Entity(
    tableName = "stock_recommendations",
    indices = [Index(value = ["symbol", "market"], unique = true)]
)
data class StockRecommendationEntity(
    @PrimaryKey(autoGenerate = true) val recommendationId: Long = 0,
    val symbol: String,
    val market: String,
    val signal: String,                 // BUY / HOLD / SELL
    val confidence: Double,             // 0.0 - 1.0
    val peRatio: Double? = null,
    val dividendYieldPercent: Double? = null,
    val technicalSignal: String? = null, // e.g. "GOLDEN_CROSS", "RSI_OVERBOUGHT"
    val reasoningJson: String,          // JSON array of reasoning bullet strings
    val riskDisclaimer: String = DEFAULT_DISCLAIMER,
    val generatedAtEpochMillis: Long = System.currentTimeMillis()
) {
    companion object {
        const val DEFAULT_DISCLAIMER =
            "This is not financial advice. All investments carry risk, including loss of " +
                "principal. Signals are generated automatically from historical and public " +
                "data and may be inaccurate, delayed, or incomplete. Please conduct your own " +
                "research or consult a licensed financial advisor before making investment decisions."
    }
}
