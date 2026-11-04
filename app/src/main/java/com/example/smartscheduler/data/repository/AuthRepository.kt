package com.example.smartscheduler.data.repository

import com.example.smartscheduler.data.local.dao.UserDao
import com.example.smartscheduler.data.local.entity.UserEntity
import com.example.smartscheduler.data.session.CurrentUser
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

sealed class AuthResult {
    data class Success(val uid: String) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

/**
 * Repository pattern wrapper around Firebase Auth. Domain/ViewModel layers depend only
 * on this interface-like class, never directly on FirebaseAuth, so the backing auth
 * provider could be swapped later without touching UI code.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val session: CurrentUser,
    private val userDao: UserDao
) {
    private val firebaseAuth: FirebaseAuth
        get() = session.auth ?: throw IllegalStateException("ยังไม่ได้ตั้งค่า Firebase (ไม่มี google-services.json)")

    val currentFirebaseUser: FirebaseUser? get() = session.auth?.currentUser

    fun observeCurrentUser(): Flow<UserEntity?> {
        val uid = session.uid
        return userDao.observeUser(uid)
    }

    suspend fun register(email: String, password: String, displayName: String): AuthResult {
        return try {
            val result = firebaseAuth.createUserWithEmailAndPassword(email, password).await()
            val fbUser = result.user ?: return AuthResult.Error("Registration failed: no user returned")
            userDao.upsert(
                UserEntity(
                    uid = fbUser.uid,
                    email = email,
                    displayName = displayName
                )
            )
            AuthResult.Success(fbUser.uid)
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Unknown registration error")
        }
    }

    suspend fun login(email: String, password: String): AuthResult {
        return try {
            val result = firebaseAuth.signInWithEmailAndPassword(email, password).await()
            val fbUser = result.user ?: return AuthResult.Error("Login failed: no user returned")
            val existing = userDao.getUser(fbUser.uid)
            if (existing == null) {
                userDao.upsert(UserEntity(uid = fbUser.uid, email = email))
            } else {
                userDao.update(existing.copy(lastLoginEpochMillis = System.currentTimeMillis()))
            }
            AuthResult.Success(fbUser.uid)
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Unknown login error")
        }
    }

    suspend fun sendPasswordReset(email: String): AuthResult {
        return try {
            firebaseAuth.sendPasswordResetEmail(email).await()
            AuthResult.Success(uid = "")
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Could not send reset email")
        }
    }

    fun logout() {
        session.auth?.signOut()
    }

    suspend fun updatePreferences(
        uid: String,
        notificationsEnabled: Boolean? = null,
        syncEnabled: Boolean? = null,
        themeMode: String? = null
    ) {
        val user = userDao.getUser(uid) ?: return
        userDao.update(
            user.copy(
                notificationsEnabled = notificationsEnabled ?: user.notificationsEnabled,
                syncEnabled = syncEnabled ?: user.syncEnabled,
                themeMode = themeMode ?: user.themeMode
            )
        )
    }

    suspend fun updateDefaultWakeUp(uid: String, hour: Int, minute: Int) =
        userDao.updateDefaultWakeUp(uid, hour, minute)
}
