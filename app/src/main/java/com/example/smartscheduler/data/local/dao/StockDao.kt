package com.example.smartscheduler.data.local.dao

import androidx.room.*
import com.example.smartscheduler.data.local.entity.MarketNewsSummaryEntity
import com.example.smartscheduler.data.local.entity.StockNoteEntity
import com.example.smartscheduler.data.local.entity.StockRecommendationEntity
import com.example.smartscheduler.data.local.entity.WatchlistItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchlistDao {

    @Query("SELECT * FROM watchlist_items WHERE ownerUid = :ownerUid AND market = :market ORDER BY addedAtEpochMillis DESC")
    fun observeWatchlist(ownerUid: String, market: String): Flow<List<WatchlistItemEntity>>

    @Query("SELECT * FROM watchlist_items WHERE ownerUid = :ownerUid")
    suspend fun getAllOnce(ownerUid: String): List<WatchlistItemEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(item: WatchlistItemEntity): Long

    @Query("DELETE FROM watchlist_items WHERE ownerUid = :ownerUid AND symbol = :symbol AND market = :market")
    suspend fun remove(ownerUid: String, symbol: String, market: String)

    @Query(
        """UPDATE watchlist_items SET lastPrice = :price, dayChangePercent = :changePercent,
           marketStatus = :marketStatus, lastQuoteUpdateEpochMillis = :updatedAt
           WHERE symbol = :symbol AND market = :market"""
    )
    suspend fun updateQuote(
        symbol: String,
        market: String,
        price: Double,
        changePercent: Double,
        marketStatus: String,
        updatedAt: Long = System.currentTimeMillis()
    )
}

@Dao
interface StockNoteDao {

    @Query("SELECT * FROM stock_notes WHERE ownerUid = :ownerUid AND symbol = :symbol AND market = :market ORDER BY createdAtEpochMillis DESC")
    fun observeNotes(ownerUid: String, symbol: String, market: String): Flow<List<StockNoteEntity>>

    @Insert
    suspend fun addNote(note: StockNoteEntity): Long

    @Update
    suspend fun updateNote(note: StockNoteEntity)

    @Delete
    suspend fun deleteNote(note: StockNoteEntity)
}

@Dao
interface MarketNewsDao {

    @Query("SELECT * FROM market_news_summaries WHERE market = :market AND summaryDateEpochDay = :epochDay LIMIT 1")
    fun observeSummary(market: String, epochDay: Long): Flow<MarketNewsSummaryEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: MarketNewsSummaryEntity)
}

@Dao
interface StockRecommendationDao {

    @Query("SELECT * FROM stock_recommendations WHERE symbol = :symbol AND market = :market LIMIT 1")
    fun observeRecommendation(symbol: String, market: String): Flow<StockRecommendationEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(recommendation: StockRecommendationEntity)
}
