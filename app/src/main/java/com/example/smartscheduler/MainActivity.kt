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
        readOpenDate(intent)

        setContent {
            SmartSchedulerTheme {
                AppNavGraph(startDestination = startDestination, openUpdateOnStart = openUpdate)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        readOpenDate(intent)
    }

    /** แตะวันในวิดเจ็ตเดือน → เปิดวันนั้นในหน้าปฏิทิน */
    private fun readOpenDate(intent: android.content.Intent?) {
        val day = intent?.getLongExtra(EXTRA_OPEN_DATE, Long.MIN_VALUE) ?: Long.MIN_VALUE
        if (day != Long.MIN_VALUE) {
            com.example.smartscheduler.widget.OpenDateRequest.date.value = java.time.LocalDate.ofEpochDay(day)
            intent?.removeExtra(EXTRA_OPEN_DATE)
        }
    }

    companion object {
        const val EXTRA_OPEN_DATE = "open_date"
        const val EXTRA_OPEN_UPDATE = "open_update"
    }
}
