package com.example.smartscheduler.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * หมวดหมู่กิจกรรมที่ผู้ใช้สร้างเอง เช่น "อ่านหนังสือ" "ออกกำลังกาย"
 * [kind] = ประเภทพื้นฐานที่หมวดนี้อยู่ (WORK / BREAK / TASK / CLASS) ใช้นับเวลาทำงาน/พัก
 * [color] = ชื่อใน TaskPalette หรือ "h<องศา>"  [icon] = ชื่อใน TaskIcons  (null = ตามประเภท)
 */
@Entity(tableName = "categories", indices = [Index("ownerUid")])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val categoryId: Long = 0,
    val ownerUid: String,
    val name: String,
    val kind: String = "TASK",
    val color: String? = null,
    val icon: String? = null,
    val sortOrder: Int = 0
)
