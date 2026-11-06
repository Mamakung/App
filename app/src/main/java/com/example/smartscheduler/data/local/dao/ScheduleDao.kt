package com.example.smartscheduler.data.local.dao

import androidx.room.*
import androidx.room.Transaction
import com.example.smartscheduler.data.local.entity.ScheduleDayEntity
import com.example.smartscheduler.data.local.entity.ScheduleOverrideEntity
import com.example.smartscheduler.data.local.entity.ScheduleTaskEntity
import kotlinx.coroutines.flow.Flow

/** Convenience projection used by the Dashboard / day-detail screens. */
data class ScheduleDayWithTasks(
    @Embedded val day: ScheduleDayEntity,
    @Relation(parentColumn = "dayId", entityColumn = "dayId")
    val tasks: List<ScheduleTaskEntity>
)

/** สรุปหนึ่งชุดตารางที่นำเข้า (นับเฉพาะตั้งแต่วันที่ระบุ) */
data class ImportBatchSummary(
    val batchId: String,
    val taskCount: Int,
    val subjectCount: Int,
    val firstDay: Long,
    val lastDay: Long
)

/** หนึ่งคาบในชุดที่ซ้ำกัน พร้อมวันที่ */
data class TaskOnDate(val taskId: Long, val dayId: Long, val dateEpochDay: Long)

@Dao
interface ScheduleDao {

    // ---------- Days ----------

    @Transaction
    @Query("SELECT * FROM schedule_days WHERE ownerUid = :ownerUid AND dateEpochDay = :epochDay LIMIT 1")
    fun observeDay(ownerUid: String, epochDay: Long): Flow<ScheduleDayWithTasks?>

    @Transaction
    @Query("SELECT * FROM schedule_days WHERE ownerUid = :ownerUid AND dateEpochDay BETWEEN :startEpochDay AND :endEpochDay ORDER BY dateEpochDay ASC")
    fun observeRange(ownerUid: String, startEpochDay: Long, endEpochDay: Long): Flow<List<ScheduleDayWithTasks>>

    @Query("SELECT * FROM schedule_days WHERE ownerUid = :ownerUid AND dateEpochDay = :epochDay LIMIT 1")
    suspend fun getDayOnce(ownerUid: String, epochDay: Long): ScheduleDayEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDay(day: ScheduleDayEntity): Long

    @Update
    suspend fun updateDay(day: ScheduleDayEntity)

    @Query("UPDATE schedule_days SET isCustomized = 1 WHERE dayId = :dayId")
    suspend fun markCustomized(dayId: Long)

    @Query("SELECT * FROM schedule_days WHERE ownerUid = :ownerUid AND isCustomized = 0 AND dateEpochDay >= :fromEpochDay AND sourceTemplateId = :templateId")
    suspend fun getFutureUneditedDaysForTemplate(ownerUid: String, fromEpochDay: Long, templateId: Long): List<ScheduleDayEntity>

    // ---------- Tasks ----------

    @Query("SELECT * FROM schedule_tasks WHERE dayId = :dayId ORDER BY startHour ASC, startMinute ASC, sortOrder ASC")
    fun observeTasksForDay(dayId: Long): Flow<List<ScheduleTaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: ScheduleTaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTasks(tasks: List<ScheduleTaskEntity>): List<Long>

    @Update
    suspend fun updateTask(task: ScheduleTaskEntity)

    @Query("UPDATE schedule_tasks SET status = :status WHERE taskId = :taskId")
    suspend fun updateTaskStatus(taskId: Long, status: String)

    @Delete
    suspend fun deleteTask(task: ScheduleTaskEntity)

    @Query("DELETE FROM schedule_tasks WHERE dayId = :dayId")
    suspend fun deleteAllTasksForDay(dayId: Long)

    // ---------- Imported timetables ----------

    @Query(
        """SELECT t.importBatchId AS batchId, COUNT(*) AS taskCount, COUNT(DISTINCT t.title) AS subjectCount,
                  MIN(d.dateEpochDay) AS firstDay, MAX(d.dateEpochDay) AS lastDay
           FROM schedule_tasks t INNER JOIN schedule_days d ON d.dayId = t.dayId
           WHERE d.ownerUid = :ownerUid AND t.importBatchId IS NOT NULL AND d.dateEpochDay >= :fromEpochDay
           GROUP BY t.importBatchId ORDER BY firstDay ASC"""
    )
    fun observeImportBatches(ownerUid: String, fromEpochDay: Long): Flow<List<ImportBatchSummary>>

    /** วันที่มีคาบจากการนำเข้า ([batchId] = null คือทุกชุด) */
    @Query(
        """SELECT DISTINCT t.dayId FROM schedule_tasks t INNER JOIN schedule_days d ON d.dayId = t.dayId
           WHERE d.ownerUid = :ownerUid AND t.importBatchId IS NOT NULL AND d.dateEpochDay >= :fromEpochDay
             AND (:batchId IS NULL OR t.importBatchId = :batchId)"""
    )
    suspend fun getDaysWithImports(ownerUid: String, fromEpochDay: Long, batchId: String?): List<Long>

    @Query("DELETE FROM schedule_tasks WHERE dayId = :dayId AND importBatchId IS NOT NULL AND (:batchId IS NULL OR importBatchId = :batchId)")
    suspend fun deleteImportedTasks(dayId: Long, batchId: String?)

    /** คาบเดียวกันจากชุดนำเข้าเดียวกัน (ชื่อและเวลาเริ่มตรงกัน) ตั้งแต่วันที่ระบุ */
    @Query(
        """SELECT t.taskId, t.dayId, d.dateEpochDay FROM schedule_tasks t INNER JOIN schedule_days d ON d.dayId = t.dayId
           WHERE d.ownerUid = :ownerUid AND t.importBatchId = :batchId AND t.title = :title
             AND t.startHour = :startHour AND t.startMinute = :startMinute AND d.dateEpochDay >= :fromEpochDay"""
    )
    suspend fun findImportedSeries(
        ownerUid: String, batchId: String, title: String, startHour: Int, startMinute: Int, fromEpochDay: Long
    ): List<TaskOnDate>

    /** รายการที่มาจากกิจวัตรรายการเดียวกัน ตั้งแต่วันที่ระบุ */
    @Query(
        """SELECT t.taskId, t.dayId, d.dateEpochDay FROM schedule_tasks t INNER JOIN schedule_days d ON d.dayId = t.dayId
           WHERE d.ownerUid = :ownerUid AND t.originTemplateTaskId IN (:templateTaskIds) AND d.dateEpochDay >= :fromEpochDay"""
    )
    suspend fun findTemplateCopies(ownerUid: String, templateTaskIds: List<Long>, fromEpochDay: Long): List<TaskOnDate>

    /** เปลี่ยนหน้าตา (ประเภท หมวด สี ไอคอน) ทุกคาบชื่อเดียวกันในชุดนำเข้าเดียวกัน */
    @Query(
        """UPDATE schedule_tasks SET kind = :kind, categoryId = :categoryId, color = :color, icon = :icon
           WHERE importBatchId = :batchId AND title = :title
             AND dayId IN (SELECT dayId FROM schedule_days WHERE ownerUid = :ownerUid)"""
    )
    suspend fun setImportedStyle(
        ownerUid: String, batchId: String, title: String, kind: String, categoryId: Long?, color: String?, icon: String?
    )

    /** เปลี่ยนหน้าตาทุกวันที่สร้างจากรายการกิจวัตรนี้ */
    @Query("UPDATE schedule_tasks SET kind = :kind, categoryId = :categoryId, color = :color, icon = :icon WHERE originTemplateTaskId = :templateTaskId")
    suspend fun setTemplateCopiesStyle(templateTaskId: Long, kind: String, categoryId: Long?, color: String?, icon: String?)

    /** กิจกรรมชุดเดียวกัน (เพิ่มพร้อมกันหลายวัน) ตั้งแต่วันที่ระบุ */
    @Query(
        """SELECT t.taskId, t.dayId, d.dateEpochDay FROM schedule_tasks t INNER JOIN schedule_days d ON d.dayId = t.dayId
           WHERE d.ownerUid = :ownerUid AND t.seriesId = :seriesId AND d.dateEpochDay >= :fromEpochDay"""
    )
    suspend fun findSeries(ownerUid: String, seriesId: String, fromEpochDay: Long): List<TaskOnDate>

    /** เปลี่ยนหน้าตาทั้งชุด (เพิ่มพร้อมกันหลายวัน) */
    @Query(
        """UPDATE schedule_tasks SET kind = :kind, categoryId = :categoryId, color = :color, icon = :icon
           WHERE seriesId = :seriesId AND dayId IN (SELECT dayId FROM schedule_days WHERE ownerUid = :ownerUid)"""
    )
    suspend fun setSeriesStyle(ownerUid: String, seriesId: String, kind: String, categoryId: Long?, color: String?, icon: String?)

    @Query("DELETE FROM schedule_tasks WHERE taskId IN (:taskIds)")
    suspend fun deleteTasksByIds(taskIds: List<Long>)

    // ---------- Overrides (audit log for Spec #5) ----------

    @Insert
    suspend fun insertOverride(entry: ScheduleOverrideEntity): Long

    @Query("SELECT * FROM schedule_overrides WHERE dayId = :dayId ORDER BY createdAtEpochMillis DESC")
    fun observeOverridesForDay(dayId: Long): Flow<List<ScheduleOverrideEntity>>

    // ---------- Helpers ----------

    @Query("SELECT * FROM schedule_days WHERE dayId = :dayId LIMIT 1")
    suspend fun getDayById(dayId: Long): ScheduleDayEntity?

    @Query("SELECT * FROM schedule_tasks WHERE dayId = :dayId ORDER BY startHour ASC, startMinute ASC, sortOrder ASC")
    suspend fun getTasksForDayOnce(dayId: Long): List<ScheduleTaskEntity>

    @Query("DELETE FROM schedule_days WHERE dayId = :dayId")
    suspend fun deleteDay(dayId: Long)
}
