package com.example.smartscheduler.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local mirror of the authenticated user (Firebase Auth is the source of truth for
 * credentials; this table stores profile + preference data used for local sync/offline UX).
 */
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val uid: String,                // Firebase Auth UID
    val email: String,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val defaultWakeUpHour: Int = 7,
    val defaultWakeUpMinute: Int = 0,
    val preferredMarketTab: String = "TH",       // "TH" or "US" - last opened tab
    val notificationsEnabled: Boolean = true,
    val syncEnabled: Boolean = true,             // whether local data syncs to remote backend
    val themeMode: String = "SYSTEM",            // LIGHT / DARK / SYSTEM
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val lastLoginEpochMillis: Long = System.currentTimeMillis()
)
