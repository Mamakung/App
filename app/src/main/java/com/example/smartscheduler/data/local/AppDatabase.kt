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
        StockRecommendationEntity::class,
        CategoryEntity::class
    ],
    version = 8,
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
    abstract fun categoryDao(): CategoryDao

    companion object {
        /** v3: วันทำซ้ำแยกต่อรายการกิจวัตร (เพิ่มคอลัมน์ ข้อมูลเดิมอยู่ครบ) */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routine_template_tasks ADD COLUMN repeatDays TEXT")
                db.execSQL("ALTER TABLE routine_template_tasks ADD COLUMN repeatMonths TEXT")
            }
        }

        /**
         * v4: จำว่าคาบไหนมาจากการนำเข้าตาราง เพื่อลบหรือแทนที่ทั้งชุดได้
         * คาบเรียนที่นำเข้าก่อนเวอร์ชันนี้ (ประเภทเรียน ไม่ได้มาจากกิจวัตร) ถูกรวมเป็นชุด "legacy"
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE schedule_tasks ADD COLUMN importBatchId TEXT")
                db.execSQL("UPDATE schedule_tasks SET importBatchId = '$LEGACY_IMPORT' WHERE kind = 'CLASS' AND originTemplateTaskId IS NULL")
            }
        }

        /** v5: สีกิจกรรมที่ผู้ใช้เลือกเอง */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE schedule_tasks ADD COLUMN color TEXT")
                db.execSQL("ALTER TABLE routine_template_tasks ADD COLUMN color TEXT")
            }
        }

        /** v6: หมวดหมู่ที่สร้างเอง + ไอคอนกิจกรรม */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `categories` (`categoryId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`ownerUid` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `color` TEXT, `icon` TEXT, " +
                        "`sortOrder` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_categories_ownerUid` ON `categories` (`ownerUid`)")
                for (table in listOf("schedule_tasks", "routine_template_tasks")) {
                    db.execSQL("ALTER TABLE $table ADD COLUMN icon TEXT")
                    db.execSQL("ALTER TABLE $table ADD COLUMN categoryId INTEGER")
                }
            }
        }

        /**
         * v7: จำชุดของกิจกรรมที่เพิ่มพร้อมกันหลายวัน
         * ข้อมูลเดิม: กิจกรรมที่เพิ่มเอง (ไม่ใช่กิจวัตร/ตารางนำเข้า) ชื่อ เวลาเริ่ม และความยาวเดียวกัน
         * ที่มีมากกว่า 1 วัน จัดเป็นชุดเดียวกัน
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE schedule_tasks ADD COLUMN seriesId TEXT")
                db.execSQL(
                    """UPDATE schedule_tasks
                       SET seriesId = 'legacy:' || title || ':' || startHour || ':' || startMinute || ':' || durationMinutes
                       WHERE originTemplateTaskId IS NULL AND importBatchId IS NULL
                         AND (SELECT COUNT(*) FROM schedule_tasks t2
                              WHERE t2.originTemplateTaskId IS NULL AND t2.importBatchId IS NULL
                                AND t2.title = schedule_tasks.title AND t2.startHour = schedule_tasks.startHour
                                AND t2.startMinute = schedule_tasks.startMinute
                                AND t2.durationMinutes = schedule_tasks.durationMinutes) > 1"""
                )
            }
        }

        /** v8: ปลุกเต็มจอต่อกิจกรรม + เสียงปลุกเฉพาะรายการ */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("schedule_tasks", "routine_template_tasks")) {
                    db.execSQL("ALTER TABLE $table ADD COLUMN alarm INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $table ADD COLUMN alarmSound TEXT")
                }
            }
        }

        const val LEGACY_IMPORT = "legacy"

        const val DATABASE_NAME = "smart_scheduler_ai.db"
    }
}
