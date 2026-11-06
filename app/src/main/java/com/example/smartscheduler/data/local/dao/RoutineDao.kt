package com.example.smartscheduler.data.local.dao

import androidx.room.*
import com.example.smartscheduler.data.local.entity.RoutineTemplateEntity
import com.example.smartscheduler.data.local.entity.RoutineTemplateTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutineDao {

    // ---------- Templates ----------

    @Query("SELECT * FROM routine_templates WHERE ownerUid = :ownerUid ORDER BY updatedAtEpochMillis DESC")
    fun observeTemplates(ownerUid: String): Flow<List<RoutineTemplateEntity>>

    @Query("SELECT * FROM routine_templates WHERE ownerUid = :ownerUid AND isActiveDefault = 1 LIMIT 1")
    suspend fun getActiveTemplate(ownerUid: String): RoutineTemplateEntity?

    @Query("SELECT * FROM routine_templates WHERE templateId = :templateId LIMIT 1")
    suspend fun getTemplate(templateId: Long): RoutineTemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTemplate(template: RoutineTemplateEntity): Long

    @Query("UPDATE routine_templates SET isActiveDefault = 0 WHERE ownerUid = :ownerUid")
    suspend fun clearActiveFlag(ownerUid: String)

    @Update
    suspend fun updateTemplate(template: RoutineTemplateEntity)

    @Query("UPDATE routine_templates SET isActiveDefault = 1, updatedAtEpochMillis = :now WHERE templateId = :templateId")
    suspend fun markActive(templateId: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE routine_templates SET updatedAtEpochMillis = :now WHERE templateId = :templateId")
    suspend fun touchUpdatedAt(templateId: Long, now: Long = System.currentTimeMillis())

    @Delete
    suspend fun deleteTemplate(template: RoutineTemplateEntity)

    // ---------- Template Tasks ----------

    @Query("SELECT * FROM routine_template_tasks WHERE templateId = :templateId ORDER BY startHour ASC, startMinute ASC, sortOrder ASC")
    fun observeTemplateTasks(templateId: Long): Flow<List<RoutineTemplateTaskEntity>>

    @Query("SELECT * FROM routine_template_tasks WHERE templateId = :templateId ORDER BY startHour ASC, startMinute ASC, sortOrder ASC")
    suspend fun getTemplateTasksOnce(templateId: Long): List<RoutineTemplateTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTemplateTask(task: RoutineTemplateTaskEntity): Long

    @Query("UPDATE routine_template_tasks SET repeatDays = :days, repeatMonths = :months WHERE templateTaskId IN (:ids)")
    suspend fun setRepeat(ids: List<Long>, days: String, months: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTemplateTasks(tasks: List<RoutineTemplateTaskEntity>)

    @Query("UPDATE routine_template_tasks SET kind = :kind, categoryId = :categoryId, color = :color, icon = :icon WHERE templateTaskId = :templateTaskId")
    suspend fun setStyle(templateTaskId: Long, kind: String, categoryId: Long?, color: String?, icon: String?)

    @Delete
    suspend fun deleteTemplateTask(task: RoutineTemplateTaskEntity)

    @Query("DELETE FROM routine_template_tasks WHERE templateTaskId = :templateTaskId")
    suspend fun deleteTemplateTaskById(templateTaskId: Long)
}
