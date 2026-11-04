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
