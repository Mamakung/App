package com.example.smartscheduler.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.smartscheduler.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * สีเฉพาะของแอป (นอกเหนือจาก Material colorScheme)
 * work = น้ำเงินหมึก, rest = เขียวใบเตย, task = ขมิ้น, now = เส้นเวลาปัจจุบัน
 */
@Immutable
data class AppColors(
    val ground: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val work: Color,
    val workBg: Color,
    val rest: Color,
    val restBg: Color,
    val task: Color,
    val taskBg: Color,
    val study: Color,
    val studyBg: Color,
    val now: Color,
    val up: Color,
    val down: Color
)

private val LightApp = AppColors(
    ground = Color(0xFFF2F4F0),
    surface = Color(0xFFFFFFFF),
    ink = Color(0xFF18302C),
    muted = Color(0xFF5F6F6B),
    line = Color(0xFFD9E0DB),
    work = Color(0xFF2B44C4),
    workBg = Color(0xFFE4E8FA),
    rest = Color(0xFF2F9E62),
    restBg = Color(0xFFDDF1E5),
    task = Color(0xFFC9821C),
    taskBg = Color(0xFFF8ECD6),
    study = Color(0xFF9B3E8F),
    studyBg = Color(0xFFF5E2F1),
    now = Color(0xFFE0473A),
    up = Color(0xFF1E8A52),
    down = Color(0xFFC8382E)
)

private val DarkApp = AppColors(
    ground = Color(0xFF101816),
    surface = Color(0xFF19231F),
    ink = Color(0xFFE6EEEA),
    muted = Color(0xFF9AA9A4),
    line = Color(0xFF2C3833),
    work = Color(0xFF8FA0FF),
    workBg = Color(0xFF1F2856),
    rest = Color(0xFF63D094),
    restBg = Color(0xFF15392A),
    task = Color(0xFFF0B45A),
    taskBg = Color(0xFF3D2C10),
    study = Color(0xFFE59BD9),
    studyBg = Color(0xFF3B1D37),
    now = Color(0xFFFF7A6E),
    up = Color(0xFF63D094),
    down = Color(0xFFFF8A80)
)

val LocalAppColors = staticCompositionLocalOf { LightApp }

/** ใช้ใน composable: `AppTheme.colors.work` */
object AppTheme {
    val colors: AppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current
}

private fun schemeFrom(c: AppColors, dark: Boolean) = if (dark) darkColorScheme(
    primary = c.ink, onPrimary = c.ground,
    primaryContainer = c.workBg, onPrimaryContainer = c.ink,
    secondary = c.rest, onSecondary = c.ground,
    secondaryContainer = c.restBg, onSecondaryContainer = c.ink,
    tertiary = c.task, tertiaryContainer = c.taskBg, onTertiaryContainer = c.ink,
    background = c.ground, onBackground = c.ink,
    surface = c.surface, onSurface = c.ink,
    surfaceVariant = c.line, onSurfaceVariant = c.muted,
    surfaceContainer = c.surface, surfaceContainerHigh = c.surface, surfaceContainerLow = c.ground,
    outline = c.line, outlineVariant = c.line,
    error = c.down
) else lightColorScheme(
    primary = c.ink, onPrimary = Color.White,
    primaryContainer = c.workBg, onPrimaryContainer = c.ink,
    secondary = c.rest, onSecondary = Color.White,
    secondaryContainer = c.restBg, onSecondaryContainer = c.ink,
    tertiary = c.task, tertiaryContainer = c.taskBg, onTertiaryContainer = c.ink,
    background = c.ground, onBackground = c.ink,
    surface = c.surface, onSurface = c.ink,
    surfaceVariant = Color(0xFFE3E8E4), onSurfaceVariant = c.muted,
    surfaceContainer = c.surface, surfaceContainerHigh = c.surface, surfaceContainerLow = c.ground,
    outline = c.line, outlineVariant = c.line,
    error = c.down
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** IBM Plex Sans Thai: ฟอนต์ไทยแบบไม่มีหัว (OFL) ฝังไว้ใน res/font */
val PlexThai = FontFamily(
    Font(R.font.ibm_plex_sans_thai_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_thai_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_thai_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_thai_bold, FontWeight.Bold)
)

/**
 * ตัวอักษรไทยมีวรรณยุกต์และสระบน/ล่าง จึงให้ระยะบรรทัดประมาณ 1.6 เท่าของขนาดตัวอักษร
 * หัวข้อใช้ SemiBold (ไม่ใช้ Bold) เพื่อให้หน้าดูโปร่ง
 */
private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Float = 0.1f) =
    TextStyle(fontFamily = PlexThai, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.sp)

private val AppTypography = Typography(
    displaySmall = style(38, 50, FontWeight.SemiBold, -0.4f),
    headlineMedium = style(28, 38, FontWeight.SemiBold, -0.2f),
    headlineSmall = style(24, 34, FontWeight.SemiBold),
    titleLarge = style(20, 30, FontWeight.SemiBold),
    titleMedium = style(17, 26, FontWeight.SemiBold),
    titleSmall = style(15, 23, FontWeight.SemiBold),
    bodyLarge = style(16, 26),
    bodyMedium = style(14, 23),
    bodySmall = style(12, 20, tracking = 0.2f),
    labelLarge = style(15, 22, FontWeight.SemiBold),
    labelMedium = style(13, 20, FontWeight.Medium, 0.2f),
    labelSmall = style(11, 16, FontWeight.Medium, 0.3f)
)

@Composable
fun SmartSchedulerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val app = if (darkTheme) DarkApp else LightApp
    val scheme = schemeFrom(app, darkTheme)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = app.ground.toArgb()
            window.navigationBarColor = app.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalAppColors provides app) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
