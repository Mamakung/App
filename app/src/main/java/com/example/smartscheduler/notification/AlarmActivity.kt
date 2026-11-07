package com.example.smartscheduler.notification

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartscheduler.domain.model.TaskKind
import com.example.smartscheduler.settings.AppSettings
import com.example.smartscheduler.ui.components.TaskIcons
import com.example.smartscheduler.ui.components.color
import com.example.smartscheduler.ui.components.icon
import com.example.smartscheduler.ui.theme.AppTheme
import com.example.smartscheduler.ui.theme.SmartSchedulerTheme
import com.example.smartscheduler.ui.theme.TaskPalette
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * หน้าจอปลุกเต็มจอ: ขึ้นแม้ล็อกจออยู่ เวลา + ไอคอนกิจกรรมในวงที่ขยายเป็นจังหวะ
 * ปุ่ม "เลื่อน n นาที" และ "หยุด" — ปิดตัวเองเมื่อการปลุกหยุดจากที่อื่น
 */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val info = AlarmInfo.from(intent)
        val snooze = AppSettings.prefs(this).getInt(AppSettings.KEY_SNOOZE, 5)
        setContent {
            SmartSchedulerTheme {
                val ringing by AlarmState.ringing.collectAsState()
                var seen by remember { mutableStateOf(false) }
                LaunchedEffect(ringing) {
                    if (ringing) seen = true
                    else if (seen) finish()
                }
                AlarmScreen(
                    info = info,
                    snoozeMinutes = snooze,
                    onSnooze = { AlarmService.send(this, AlarmService.ACTION_SNOOZE); finish() },
                    onStop = { AlarmService.send(this, AlarmService.ACTION_STOP); finish() }
                )
            }
        }
    }
}

@Composable
private fun AlarmScreen(info: AlarmInfo, snoozeMinutes: Int, onSnooze: () -> Unit, onStop: () -> Unit) {
    val c = AppTheme.colors
    val kind = TaskKind.from(info.kind)
    val accent = TaskPalette.resolve(info.color, c.dark)?.first ?: kind.color()
    val icon = TaskIcons.find(info.icon)?.vector ?: kind.icon()
    val pulse = rememberInfiniteTransition(label = "pulse")
    val p by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "p")
    val now = remember { LocalTime.now() }
    val date = LocalDate.ofEpochDay(info.dateEpochDay)

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.18f), c.ground), radius = 1400f))
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(72.dp))
            Text(date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale("th", "TH"))), color = c.muted, fontSize = 16.sp)
            Text(
                now.format(DateTimeFormatter.ofPattern("HH:mm")),
                color = c.ink, fontSize = 80.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(24.dp))
            // วงขยายเป็นจังหวะ 2 วง + ไอคอนกิจกรรม
            Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                for (k in 0..1) {
                    val t = (p + k * 0.5f) % 1f
                    Box(
                        Modifier
                            .size(120.dp)
                            .graphicsLayer { scaleX = 1f + 0.65f * t; scaleY = 1f + 0.65f * t; alpha = 1f - t }
                            .border(2.dp, accent, CircleShape)
                    )
                }
                Box(
                    Modifier.size(116.dp).clip(CircleShape).background(accent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = c.ground, modifier = Modifier.size(56.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(info.title, color = c.ink, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            if (info.range.isNotBlank()) Text(info.range, color = c.muted, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .border(1.5.dp, c.outline, RoundedCornerShape(28.dp))
                    .clickable(role = Role.Button, onClick = onSnooze)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Snooze, contentDescription = null, tint = c.ink)
                Spacer(Modifier.width(8.dp))
                Text("เลื่อน $snoozeMinutes นาที", color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(c.ink)
                    .clickable(role = Role.Button, onClick = onStop)
                    .semantics { contentDescription = "หยุดปลุก" },
                contentAlignment = Alignment.Center
            ) {
                Text("หยุด", color = c.ground, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(44.dp))
        }
    }
}
