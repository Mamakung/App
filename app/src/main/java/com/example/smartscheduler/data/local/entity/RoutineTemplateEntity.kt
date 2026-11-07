package com.example.smartscheduler.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The "Standard Daily Routine" master template referenced in Spec #3.
 * A user can have multiple named templates (e.g. "Weekday", "Weekend") but
 * exactly one is marked isActiveDefault = true and is used to auto-generate
 * future ScheduleDay rows.
 */
@Entity(
    tableName = "routine_templates",
    indices = [Index("ownerUid")]
)
data class RoutineTemplateEntity(
    @PrimaryKey(autoGenerate = true) val templateId: Long = 0,
    val ownerUid: String,
    val name: String,                     // e.g. "Standard Weekday Routine"
    val wakeUpHour: Int,
    val wakeUpMinute: Int,
    val isActiveDefault: Boolean = false,
    val appliesToDaysOfWeek: String = "MON,TUE,WED,THU,FRI", // CSV of day codes
    val appliesToMonths: String = "1,2,3,4,5,6,7,8,9,10,11,12", // CSV of month numbers
    val updatedAtEpochMillis: Long = System.currentTimeMillis()
)

/**
 * Individual time-slotted task belonging to a RoutineTemplate.
 * Editing these + bumping template.updatedAtEpochMillis triggers propagation
 * to all future ScheduleDay entries that have not been individually overridden.
 */
@Entity(
    tableName = "routine_template_tasks",
    foreignKeys = [
        ForeignKey(
            entity = RoutineTemplateEntity::class,
            parentColumns = ["templateId"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("templateId")]
)
data class RoutineTemplateTaskEntity(
    @PrimaryKey(autoGenerate = true) val templateTaskId: Long = 0,
    val templateId: Long,
    val title: String,
    val description: String? = null,
    val startHour: Int,
    val startMinute: Int,
    val durationMinutes: Int = 30,
    val sortOrder: Int = 0,
    val reminderEnabled: Boolean = true,
    val kind: String = "TASK", // WORK / BREAK / TASK / CLASS
    /** วันที่รายการนี้ทำซ้ำ (CSV เช่น "MON,WED") null = ใช้ค่าเริ่มต้นของกิจวัตร */
    val repeatDays: String? = null,
    /** เดือนที่รายการนี้ทำซ้ำ (CSV เช่น "1,2,3") null = ใช้ค่าเริ่มต้นของกิจวัตร */
    val repeatMonths: String? = null,
    /** สีที่ผู้ใช้เลือก (ชื่อใน TaskPalette) null = สีตามประเภท */
    val color: String? = null,
    /** ไอคอนที่เลือก (ชื่อใน TaskIcons) null = ตามหมวด/ประเภท */
    val icon: String? = null,
    /** หมวดหมู่ที่ผู้ใช้สร้าง null = ใช้ประเภทพื้นฐาน */
    val categoryId: Long? = null,
    /** true = ปลุกเต็มจอ (ดังวนจนกดหยุด) แทนแจ้งเตือนธรรมดา */
    @androidx.room.ColumnInfo(defaultValue = "0") val alarm: Boolean = false,
    /** เสียงปลุกเฉพาะรายการนี้ (null = ใช้เสียงปลุกในตั้งค่า) */
    val alarmSound: String? = null
)
