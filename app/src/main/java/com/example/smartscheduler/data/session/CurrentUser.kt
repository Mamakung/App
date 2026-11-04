package com.example.smartscheduler.data.session

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ตัวบอกว่า "ผู้ใช้ตอนนี้คือใคร"
 *
 * - ถ้าแอปตั้งค่า Firebase แล้ว (มี google-services.json) และล็อกอินอยู่ → ใช้ uid ของ Firebase
 * - ถ้ายังไม่ได้ตั้งค่า Firebase → ใช้ผู้ใช้ในเครื่อง (LOCAL_UID) ข้อมูลทั้งหมดเก็บใน Room บนเครื่อง
 *
 * ทำให้ APK เปิดใช้งานได้ทันทีโดยไม่ต้องมีโปรเจกต์ Firebase
 */
@Singleton
class CurrentUser @Inject constructor(@ApplicationContext context: Context) {

    /** null เมื่อยังไม่ได้ตั้งค่า Firebase */
    val auth: FirebaseAuth? = runCatching {
        if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context)
        if (FirebaseApp.getApps(context).isEmpty()) null else FirebaseAuth.getInstance()
    }.getOrNull()

    val firebaseAvailable: Boolean get() = auth != null

    val isLoggedIn: Boolean get() = auth?.currentUser != null

    /** ต้องแสดงหน้าล็อกอินไหม: เฉพาะตอนมี Firebase แต่ยังไม่ล็อกอิน */
    val needsLogin: Boolean get() = firebaseAvailable && !isLoggedIn

    val uid: String get() = auth?.currentUser?.uid ?: LOCAL_UID

    companion object {
        const val LOCAL_UID = "local-user"
    }
}
