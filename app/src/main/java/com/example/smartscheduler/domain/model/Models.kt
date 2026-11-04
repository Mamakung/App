package com.example.smartscheduler.domain.model

import java.time.LocalDate
import java.time.LocalTime

enum class TaskStatus { PENDING, DONE, SKIPPED }
enum class Market(val code: String, val displayName: String, val currency: String) {
    THAILAND("TH", "Thailand (SET)", "THB"),
    UNITED_STATES("US", "United States (NYSE/NASDAQ)", "USD")
}
enum class MarketStatus { OPEN, CLOSED, PRE_MARKET, AFTER_HOURS, UNKNOWN }
enum class Signal { BUY, HOLD, SELL }
enum class OverrideAction { INSERT, EDIT, DELETE }

data class User(
    val uid: String,
    val email: String,
    val displayName: String?,
    val defaultWakeUp: LocalTime,
    val notificationsEnabled: Boolean,
    val syncEnabled: Boolean,
    val themeMode: String
)

data class RoutineTemplate(
    val templateId: Long,
    val name: String,
    val wakeUpTime: LocalTime,
    val isActiveDefault: Boolean,
    val daysOfWeek: Set<String>,
    val tasks: List<RoutineTask>
)

data class RoutineTask(
    val templateTaskId: Long,
    val title: String,
    val description: String?,
    val start: LocalTime,
    val durationMinutes: Int,
    val sortOrder: Int,
    val reminderEnabled: Boolean
)

data class ScheduleDay(
    val dayId: Long,
    val date: LocalDate,
    val wakeUpTime: LocalTime,
    val isCustomized: Boolean,
    val tasks: List<ScheduledTask>
)

data class ScheduledTask(
    val taskId: Long,
    val title: String,
    val description: String?,
    val start: LocalTime,
    val durationMinutes: Int,
    val status: TaskStatus,
    val reminderEnabled: Boolean
)

data class BatchAssignRequest(
    val dates: List<LocalDate>,
    val wakeUpTime: LocalTime,
    val tasks: List<RoutineTask>
)

data class WatchlistItem(
    val symbol: String,
    val market: Market,
    val companyName: String,
    val lastPrice: Double?,
    val dayChangePercent: Double?,
    val marketStatus: MarketStatus,
    val noteCount: Int = 0
)

data class StockNote(
    val noteId: Long,
    val symbol: String,
    val market: Market,
    val text: String,
    val createdAt: Long
)

data class StockQuote(
    val symbol: String,
    val market: Market,
    val companyName: String,
    val price: Double,
    val changePercent: Double,
    val changeAbsolute: Double,
    val marketStatus: MarketStatus,
    val asOfEpochMillis: Long
)

data class MarketNewsSummary(
    val market: Market,
    val date: LocalDate,
    val bulletPoints: List<String>,
    val generatedAtEpochMillis: Long
)

data class StockRecommendation(
    val symbol: String,
    val market: Market,
    val signal: Signal,
    val confidence: Double,
    val peRatio: Double?,
    val dividendYieldPercent: Double?,
    val technicalSignal: String?,
    val reasoning: List<String>,
    val riskDisclaimer: String
)
