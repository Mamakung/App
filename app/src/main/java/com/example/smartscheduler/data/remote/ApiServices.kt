package com.example.smartscheduler.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.*

// ============================================================================
// STOCK MARKET DATA API
// Backed by a provider such as Alpha Vantage / Finnhub (US) and SETTRADE / yfinance
// proxy (Thailand). Two base URLs are configured via separate Retrofit instances
// (see di/NetworkModule.kt) and unified behind StockRepository.
// ============================================================================

@Serializable
data class QuoteDto(
    val symbol: String,
    val companyName: String,
    val price: Double,
    val changePercent: Double,
    val changeAbsolute: Double,
    val currency: String,
    val marketStatus: String, // OPEN / CLOSED / PRE_MARKET / AFTER_HOURS
    val timestampEpochMillis: Long
)

@Serializable
data class SymbolSearchResultDto(
    val symbol: String,
    val companyName: String,
    val market: String
)

@Serializable
data class FundamentalsDto(
    val symbol: String,
    val peRatio: Double?,
    val dividendYieldPercent: Double?,
    val marketCap: Double?,
    val fiftyTwoWeekHigh: Double?,
    val fiftyTwoWeekLow: Double?
)

@Serializable
data class TechnicalIndicatorsDto(
    val symbol: String,
    val rsi14: Double?,
    val sma50: Double?,
    val sma200: Double?,
    val macdSignal: String? // e.g. "BULLISH_CROSS", "BEARISH_CROSS", "NEUTRAL"
)

interface StockQuoteApi {

    @GET("v1/quote")
    suspend fun getQuote(@Query("symbol") symbol: String, @Query("market") market: String): QuoteDto

    @GET("v1/quotes")
    suspend fun getQuotes(@Query("symbols") commaSeparatedSymbols: String, @Query("market") market: String): List<QuoteDto>

    @GET("v1/search")
    suspend fun searchSymbols(@Query("query") query: String, @Query("market") market: String): List<SymbolSearchResultDto>

    @GET("v1/fundamentals")
    suspend fun getFundamentals(@Query("symbol") symbol: String, @Query("market") market: String): FundamentalsDto

    @GET("v1/technicals")
    suspend fun getTechnicals(@Query("symbol") symbol: String, @Query("market") market: String): TechnicalIndicatorsDto

    @GET("v1/market-status")
    suspend fun getMarketStatus(@Query("market") market: String): MarketStatusDto
}

@Serializable
data class MarketStatusDto(
    val market: String,
    val isOpen: Boolean,
    val nextOpenEpochMillis: Long?,
    val nextCloseEpochMillis: Long?
)

// ============================================================================
// FINANCIAL NEWS FEED API
// ============================================================================

@Serializable
data class NewsArticleDto(
    val id: String,
    val headline: String,
    val summary: String,
    val url: String,
    val source: String,
    val publishedAtEpochMillis: Long,
    val relatedSymbols: List<String> = emptyList()
)

interface NewsFeedApi {

    @GET("v1/news")
    suspend fun getTopNews(
        @Query("market") market: String,       // "TH" or "US"
        @Query("limit") limit: Int = 20
    ): List<NewsArticleDto>
}

// ============================================================================
// LLM SUMMARIZATION API (Anthropic Claude via backend proxy)
// The app should NOT call the LLM provider directly with an embedded API key;
// route through a thin backend (e.g. Cloud Function) that holds the key server-side.
// ============================================================================

@Serializable
data class SummarizeNewsRequest(
    val market: String,
    val articles: List<NewsArticleDto>,
    val maxBulletPoints: Int = 6
)

@Serializable
data class SummarizeNewsResponse(
    val market: String,
    val bulletPoints: List<String>,
    val generatedAtEpochMillis: Long
)

@Serializable
data class AnalyzeStockRequest(
    val symbol: String,
    val market: String,
    val fundamentals: FundamentalsDto,
    val technicals: TechnicalIndicatorsDto,
    val recentNews: List<NewsArticleDto> = emptyList()
)

@Serializable
data class AnalyzeStockResponse(
    val symbol: String,
    val signal: String,       // BUY / HOLD / SELL
    val confidence: Double,
    val reasoning: List<String>,
    val riskDisclaimer: String
)

interface AiAssistantApi {

    @POST("v1/ai/summarize-news")
    suspend fun summarizeNews(@Body request: SummarizeNewsRequest): SummarizeNewsResponse

    @POST("v1/ai/analyze-stock")
    suspend fun analyzeStock(@Body request: AnalyzeStockRequest): AnalyzeStockResponse
}
