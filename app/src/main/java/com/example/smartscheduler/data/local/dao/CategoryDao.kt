package com.example.smartscheduler.data.local.dao

import androidx.room.*
import com.example.smartscheduler.data.local.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE ownerUid = :ownerUid ORDER BY sortOrder ASC, categoryId ASC")
    fun observe(ownerUid: String): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: CategoryEntity): Long

    @Query("DELETE FROM categories WHERE categoryId = :categoryId")
    suspend fun delete(categoryId: Long)

    /** ลบหมวดแล้ว กิจกรรมที่เคยอยู่หมวดนี้กลับไปใช้สี/ไอคอนตามประเภท */
    @Query("UPDATE schedule_tasks SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun detachScheduleTasks(categoryId: Long)

    @Query("UPDATE routine_template_tasks SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun detachTemplateTasks(categoryId: Long)

    /** เปลี่ยนประเภทพื้นฐานของหมวด ให้กิจกรรมในหมวดเปลี่ยนตาม */
    @Query("UPDATE schedule_tasks SET kind = :kind WHERE categoryId = :categoryId")
    suspend fun setScheduleKind(categoryId: Long, kind: String)

    @Query("UPDATE routine_template_tasks SET kind = :kind WHERE categoryId = :categoryId")
    suspend fun setTemplateKind(categoryId: Long, kind: String)
}
