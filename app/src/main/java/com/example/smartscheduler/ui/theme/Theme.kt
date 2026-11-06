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
    /** ขอบของปุ่ม/ช่องที่กดได้ (ต้องเห็นชัด 3:1) ต่างจาก line ที่ใช้เป็นเส้นแบ่ง */
    val outline: Color,
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
    val down: Color,
    /** โหมดมืด: ใช้เลือกคู่สีใน TaskPalette */
    val dark: Boolean = false
)

private val LightApp = AppColors(
    ground = Color(0xFFF2F4F0),
    surface = Color(0xFFFFFFFF),
    ink = Color(0xFF18302C),
    muted = Color(0xFF5A6A66),
    line = Color(0xFFD9E0DB),
    outline = Color(0xFF828F8A),
    work = Color(0xFF2B44C4),
    workBg = Color(0xFFE4E8FA),
    rest = Color(0xFF2F9E62),
    restBg = Color(0xFFDDF1E5),
    task = Color(0xFFB5741A),
    taskBg = Color(0xFFF8ECD6),
    study = Color(0xFF9B3E8F),
    studyBg = Color(0xFFF5E2F1),
    now = Color(0xFFE0473A),
    up = Color(0xFF187A48),
    down = Color(0xFFC8382E)
)

private val DarkApp = AppColors(
    ground = Color(0xFF101816),
    surface = Color(0xFF19231F),
    ink = Color(0xFFE6EEEA),
    muted = Color(0xFF9AA9A4),
    line = Color(0xFF2C3833),
    outline = Color(0xFF66756F),
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
    down = Color(0xFFFF8A80),
    dark = true
)

/**
 * สีที่ผู้ใช้เลือกให้กิจกรรมได้ แต่ละสีมีคู่ เข้ม (เส้น/ไอคอน) กับ อ่อน (พื้นบล็อก)
 * โหมดสว่าง: สีเข้มบนพื้นขาว ≥ 4.5:1  โหมดมืด: สีสว่างบนพื้นเข้ม
 */
object TaskPalette {
    data class Swatch(val key: String, val label: String, val light: Pair<Color, Color>, val dark: Pair<Color, Color>)

    val swatches = listOf(
        Swatch("red", "แดง", Color(0xFFC0392B) to Color(0xFFFBE9E7), Color(0xFFFF8A80) to Color(0xFF3D1A17)),
        Swatch("orange", "ส้ม", Color(0xFFA9520E) to Color(0xFFFCEDE0), Color(0xFFFFB074) to Color(0xFF3D2510)),
        Swatch("amber", "เหลือง", Color(0xFF8A6500) to Color(0xFFFAF2D9), Color(0xFFF2C94C) to Color(0xFF3A2E0B)),
        Swatch("green", "เขียว", Color(0xFF2C7A4B) to Color(0xFFE3F3E8), Color(0xFF6FD39A) to Color(0xFF14392A)),
        Swatch("teal", "เขียวน้ำทะเล", Color(0xFF0E7470) to Color(0xFFDCF1EF), Color(0xFF5FD3CC) to Color(0xFF103734)),
        Swatch("blue", "น้ำเงิน", Color(0xFF2B44C4) to Color(0xFFE6E9FA), Color(0xFF8FA0FF) to Color(0xFF1F2856)),
        Swatch("violet", "ม่วง", Color(0xFF6741C0) to Color(0xFFEEE8FB), Color(0xFFB9A0FF) to Color(0xFF2A1F4D)),
        Swatch("pink", "ชมพู", Color(0xFFB02D6B) to Color(0xFFFBE7F0), Color(0xFFFF8FC0) to Color(0xFF3D1529)),
        Swatch("gray", "เทา", Color(0xFF56625E) to Color(0xFFECEFEE), Color(0xFFB5C0BC) to Color(0xFF29322F))
    )

    fun find(key: String?): Swatch? = key?.let { k -> swatches.firstOrNull { it.key == k } }
}

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
    outline = c.outline, outlineVariant = c.line,
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
    outline = c.outline, outlineVariant = c.line,
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
