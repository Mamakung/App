package com.example.smartscheduler.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.smartscheduler.data.local.dao.*
import com.example.smartscheduler.data.local.entity.*

@Database(
    entities = [
        UserEntity::class,
        RoutineTemplateEntity::class,
        RoutineTemplateTaskEntity::class,
        ScheduleDayEntity::class,
        ScheduleTaskEntity::class,
        ScheduleOverrideEntity::class,
        WatchlistItemEntity::class,
        StockNoteEntity::class,
        MarketNewsSummaryEntity::class,
        StockRecommendationEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun routineDao(): RoutineDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun stockNoteDao(): StockNoteDao
    abstract fun marketNewsDao(): MarketNewsDao
    abstract fun stockRecommendationDao(): StockRecommendationDao

    companion object {
        const val DATABASE_NAME = "smart_scheduler_ai.db"
    }
}
