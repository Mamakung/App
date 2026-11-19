package com.example.smartscheduler.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** โฟลเดอร์ในสมุด ซ้อนกันได้ ([parentId] = null คือชั้นบนสุด) สี/ไอคอนรูปแบบเดียวกับหมวดกิจกรรม */
@Entity(tableName = "note_folders", indices = [Index("ownerUid")])
data class NoteFolderEntity(
    @PrimaryKey(autoGenerate = true) val folderId: Long = 0,
    val ownerUid: String,
    val parentId: Long? = null,
    val name: String,
    val color: String? = null,
    val icon: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * รายการในสมุด: บันทึก ([kind] = "NOTE") หรือไฟล์ PDF ("PDF")
 * [body] = เนื้อหาบันทึกเป็น JSON (ข้อความ + รูป + ไฟล์แนบ)
 * [filePath] / [pageCount] / [lastPage] ใช้กับ PDF
 */
@Entity(tableName = "notes", indices = [Index("ownerUid")])
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val noteId: Long = 0,
    val ownerUid: String,
    val folderId: Long? = null,
    val kind: String = "NOTE",
    val title: String = "",
    val body: String = "",
    val filePath: String? = null,
    val pageCount: Int = 0,
    val lastPage: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
