package com.example.smartscheduler.data.local.dao

import androidx.room.*
import com.example.smartscheduler.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {

    @Query("SELECT * FROM users WHERE uid = :uid LIMIT 1")
    fun observeUser(uid: String): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE uid = :uid LIMIT 1")
    suspend fun getUser(uid: String): UserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(user: UserEntity)

    @Update
    suspend fun update(user: UserEntity)

    @Query(
        """UPDATE users SET defaultWakeUpHour = :hour, defaultWakeUpMinute = :minute
           WHERE uid = :uid"""
    )
    suspend fun updateDefaultWakeUp(uid: String, hour: Int, minute: Int)

    @Query("UPDATE users SET notificationsEnabled = :enabled WHERE uid = :uid")
    suspend fun setNotificationsEnabled(uid: String, enabled: Boolean)

    @Query("UPDATE users SET syncEnabled = :enabled WHERE uid = :uid")
    suspend fun setSyncEnabled(uid: String, enabled: Boolean)

    @Query("UPDATE users SET themeMode = :mode WHERE uid = :uid")
    suspend fun setThemeMode(uid: String, mode: String)

    @Query("DELETE FROM users WHERE uid = :uid")
    suspend fun deleteUser(uid: String)
}
