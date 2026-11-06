package com.example.smartscheduler.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One concrete calendar day for a user. Generated from the active RoutineTemplate
 * unless isCustomized = true, in which case template propagation (Spec #3) will
 * skip this day (Spec #5: single-day customization without touching the template).
 */
@Entity(
    tableName = "schedule_days",
    indices = [Index(value = ["ownerUid", "dateEpochDay"], unique = true)]
)
data class ScheduleDayEntity(
    @PrimaryKey(autoGenerate = true) val dayId: Long = 0,
    val ownerUid: String,
    val dateEpochDay: Long,          // LocalDate.toEpochDay() - unique per user per day
    val wakeUpHour: Int,
    val wakeUpMinute: Int,
    val sourceTemplateId: Long?,     // which template generated this day (nullable if fully manual)
    val isCustomized: Boolean = false, // true once user edits this specific day (Spec #5)
    val batchGroupId: String? = null   // ties together days created by one batch-assign action (Spec #4)
)

/**
 * A single time-slotted task on a concrete ScheduleDayEntity, with status tracking.
 */
@Entity(
    tableName = "schedule_tasks",
    foreignKeys = [
        ForeignKey(
            entity = ScheduleDayEntity::class,
            parentColumns = ["dayId"],
            childColumns = ["dayId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("dayId")]
)
data class ScheduleTaskEntity(
    @PrimaryKey(autoGenerate = true) val taskId: Long = 0,
    val dayId: Long,
    val title: String,
    val description: String? = null,
    val startHour: Int,
    val startMinute: Int,
    val durationMinutes: Int = 30,
    val status: String = "PENDING",   // PENDING / DONE / SKIPPED
    val sortOrder: Int = 0,
    val reminderEnabled: Boolean = true,
    val kind: String = "TASK", // WORK / BREAK / TASK
    val originTemplateTaskId: Long? = null, // link back to template task, null if manually inserted
    /** ชุดที่นำเข้าจากไฟล์ตาราง (null = เพิ่มเองหรือมาจากกิจวัตร) ใช้ลบหรือแทนที่ทั้งชุด */
    val importBatchId: String? = null,
    /** สีที่ผู้ใช้เลือก (ชื่อใน TaskPalette เช่น "teal") null = สีตามประเภท */
    val color: String? = null,
    /** ไอคอนที่เลือก (ชื่อใน TaskIcons) null = ตามหมวด/ประเภท */
    val icon: String? = null,
    /** หมวดหมู่ที่ผู้ใช้สร้าง (CategoryEntity) null = ใช้ประเภทพื้นฐาน */
    val categoryId: Long? = null,
    /** ชุดที่เพิ่มพร้อมกันจาก "เพิ่มหลายวัน" (หนึ่งกิจกรรม = หนึ่งชุด) ใช้ลบ/แก้สีทั้งชุด */
    val seriesId: String? = null
)

/**
 * Explicit override log so Spec #5 ("without affecting the underlying master template")
 * and analytics ("how often do users deviate from routine") are auditable.
 * One row per edit/insert/delete action performed directly on a specific day.
 */
@Entity(
    tableName = "schedule_overrides",
    foreignKeys = [
        ForeignKey(
            entity = ScheduleDayEntity::class,
            parentColumns = ["dayId"],
            childColumns = ["dayId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("dayId")]
)
data class ScheduleOverrideEntity(
    @PrimaryKey(autoGenerate = true) val overrideId: Long = 0,
    val dayId: Long,
    val taskId: Long?,               // nullable if the override is day-level (e.g. wake time change)
    val action: String,              // INSERT / EDIT / DELETE
    val previousValueJson: String? = null, // snapshot before the change, for undo/audit
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)
