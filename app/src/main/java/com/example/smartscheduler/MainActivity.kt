package com.example.smartscheduler

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.smartscheduler.navigation.AppNavGraph
import com.example.smartscheduler.ui.theme.SmartSchedulerTheme
import com.example.smartscheduler.data.session.CurrentUser
import com.example.smartscheduler.navigation.Routes
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var currentUser: CurrentUser

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: reminders simply won't post a system notification until granted */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // ไม่มี Firebase หรือล็อกอินแล้ว → เข้าหน้าหลักเลย
        val startDestination = if (currentUser.needsLogin) Routes.AUTH else Routes.DASHBOARD

        val openUpdate = intent?.getBooleanExtra(EXTRA_OPEN_UPDATE, false) == true

        setContent {
            SmartSchedulerTheme {
                AppNavGraph(startDestination = startDestination, openUpdateOnStart = openUpdate)
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_UPDATE = "open_update"
    }
}
