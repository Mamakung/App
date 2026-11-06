package com.example.smartscheduler.data.repository

import androidx.room.withTransaction
import com.example.smartscheduler.data.local.AppDatabase
import com.example.smartscheduler.data.local.dao.CategoryDao
import com.example.smartscheduler.data.local.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepository @Inject constructor(
    private val db: AppDatabase,
    private val dao: CategoryDao
) {
    fun observe(uid: String): Flow<List<CategoryEntity>> = dao.observe(uid)

    /** บันทึกหมวด คืน id (หมวดใหม่ต่อท้ายรายการ) */
    suspend fun save(category: CategoryEntity, nextOrder: Int): Long = db.withTransaction {
        val toSave = if (category.categoryId == 0L) category.copy(sortOrder = nextOrder) else category
        val id = dao.upsert(toSave)
        if (category.categoryId != 0L) {
            dao.setScheduleKind(category.categoryId, category.kind)
            dao.setTemplateKind(category.categoryId, category.kind)
        }
        if (category.categoryId == 0L) id else category.categoryId
    }

    suspend fun delete(categoryId: Long) = db.withTransaction {
        dao.detachScheduleTasks(categoryId)
        dao.detachTemplateTasks(categoryId)
        dao.delete(categoryId)
    }
}
