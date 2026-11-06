package com.example.smartscheduler.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 3,
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
        /** v3: วันทำซ้ำแยกต่อรายการกิจวัตร (เพิ่มคอลัมน์ ข้อมูลเดิมอยู่ครบ) */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routine_template_tasks ADD COLUMN repeatDays TEXT")
                db.execSQL("ALTER TABLE routine_template_tasks ADD COLUMN repeatMonths TEXT")
            }
        }

        const val DATABASE_NAME = "smart_scheduler_ai.db"
    }
}
