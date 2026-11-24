package com.example.smartscheduler.notification

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** สิทธิ์ "แสดงเต็มหน้าจอ" (Android 14+ ต้องอนุญาตเอง) ใช้ให้หน้าปลุกเด้งขึ้นตอนล็อกจอ */
object AlarmPermissions {
    fun canFullScreen(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 34) context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() else true

    fun openFullScreenSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).setData(Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** เช็กสิทธิ์เต็มจอใหม่ทุกครั้งที่กลับเข้าหน้า (เช่น กลับจากหน้าตั้งค่า) */
@Composable
fun rememberFullScreenAllowed(): Boolean {
    val context = LocalContext.current
    var ok by remember { mutableStateOf(AlarmPermissions.canFullScreen(context)) }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) ok = AlarmPermissions.canFullScreen(context) }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    return ok
}
