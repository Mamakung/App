package com.example.smartscheduler.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smartscheduler.ui.components.HueBar
import com.example.smartscheduler.ui.components.Hint
import com.example.smartscheduler.ui.components.StrengthSlider
import com.example.smartscheduler.ui.theme.AppTheme
import com.example.smartscheduler.ui.theme.SmartSchedulerTheme
import com.example.smartscheduler.ui.theme.TaskPalette
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

/**
 * หน้า "สีวิดเจ็ต": เปิดตอนวางวิดเจ็ต และตอนกดค้าง › ตั้งค่า (Android 12+)
 * พรีเซ็ท อัตโนมัติ / มืด / สว่าง (ขาวฟ้า) / ชุดสีโทรศัพท์ / เลือกเอง (สีวิดเจ็ต + สีช่องวันนี้)
 */
@AndroidEntryPoint
class WidgetConfigActivity : ComponentActivity() {

    @Inject lateinit var data: WidgetData

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // กดย้อนกลับ = ยกเลิกการวาง
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider?.className.orEmpty()
        val isMonth = provider.endsWith("MonthWidgetProvider")
        val initial = WidgetStyle.load(this, id)

        setContent {
            SmartSchedulerTheme {
                ConfigScreen(
                    initial = initial,
                    isMonth = isMonth,
                    preview = { style -> renderPreview(style, isMonth) },
                    onDone = { style ->
                        WidgetStyle.save(this, id, style)
                        Widgets.refreshOne(this, id)
                        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                        finish()
                    }
                )
            }
        }
    }

    private suspend fun renderPreview(style: WidgetStyle, isMonth: Boolean): Bitmap = withContext(Dispatchers.IO) {
        val app = applicationContext
        val pal = style.palette(app)
        val today = LocalDate.now()
        if (isMonth) {
            val (from, to) = MonthWidget.range(today)
            val marks = runCatching { data.marks(from, to, pal.dark) }.getOrDefault(emptyMap())
            MonthWidget.render(app, today, marks, pal, 300, 260)
        } else {
            val colors = runCatching { data.todayColors(pal.dark) }.getOrDefault(emptyList())
            TodayWidget.render(app, today, colors, pal)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigScreen(
    initial: WidgetStyle,
    isMonth: Boolean,
    preview: suspend (WidgetStyle) -> Bitmap,
    onDone: (WidgetStyle) -> Unit
) {
    val c = AppTheme.colors
    var style by remember { mutableStateOf(initial) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var open by remember { mutableStateOf<Int?>(if (initial.preset == WidgetPreset.CUSTOM) 1 else null) }
    LaunchedEffect(style) { bmp = preview(style) }

    Surface(color = c.ground, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text("สีวิดเจ็ต", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            // ตัวอย่าง บนพื้นคล้ายวอลเปเปอร์
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF2A3D55), Color(0xFF121620))))
                    .padding(18.dp),
                contentAlignment = Alignment.Center
            ) {
                bmp?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = "ตัวอย่างวิดเจ็ต",
                        modifier = if (isMonth) Modifier.fillMaxWidth(0.85f).aspectRatio(300f / 260f) else Modifier.size(110.dp)
                    )
                } ?: Box(Modifier.height(if (isMonth) 220.dp else 110.dp))
            }

            Text("พรีเซ็ท", style = MaterialTheme.typography.titleSmall, color = c.muted, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
            FlowRow(
                Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 2
            ) {
                WidgetPreset.values().forEach { pr ->
                    PresetCard(pr, selected = style.preset == pr, modifier = Modifier.weight(1f)) {
                        style = style.copy(preset = pr)
                        if (pr == WidgetPreset.CUSTOM && open == null) open = 1
                    }
                }
                if (WidgetPreset.values().size % 2 == 1) Spacer(Modifier.weight(1f))
            }

            if (style.preset == WidgetPreset.CUSTOM) {
                Text("เลือกเอง", style = MaterialTheme.typography.titleSmall, color = c.muted, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
                ColorRow("สีวิดเจ็ต", "พื้นและหัว", style.bgKey, open == 1) { open = if (open == 1) null else 1 }
                if (open == 1) WidgetColorPicker(style.bgKey) { style = style.copy(bgKey = it) }
                ColorRow("สีช่องวันนี้", if (isMonth) "ช่องที่บอกว่าวันนี้" else "แถบบนปฏิทิน", style.todayKey, open == 2) { open = if (open == 2) null else 2 }
                if (open == 2) WidgetColorPicker(style.todayKey) { style = style.copy(todayKey = it) }
            } else {
                Hint(
                    when (style.preset) {
                        WidgetPreset.AUTO -> "โทรศัพท์โหมดมืดใช้พรีเซ็ทมืด โหมดสว่างใช้ขาวฟ้า สลับเอง"
                        WidgetPreset.PHONE -> "ใช้ชุดสีจากวอลเปเปอร์ (Android 12 ขึ้นไป) เปลี่ยนวอลเปเปอร์แล้ววิดเจ็ตเปลี่ยนตาม"
                        else -> "สีกิจกรรมในวิดเจ็ตยังตามที่ตั้งในปฏิทิน"
                    },
                    Modifier.padding(top = 10.dp)
                )
            }

            Button(
                onClick = { onDone(style) },
                modifier = Modifier.fillMaxWidth().padding(top = 22.dp).heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.ground)
            ) { Text("บันทึก", fontWeight = FontWeight.Bold) }
            Hint("เปลี่ยนทีหลังได้: กดค้างที่วิดเจ็ต › ตั้งค่า", Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun PresetCard(pr: WidgetPreset, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = AppTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) c.ink else c.line, RoundedCornerShape(14.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PresetSwatch(pr)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(pr.label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Text(pr.detail, style = MaterialTheme.typography.labelSmall, color = c.muted)
        }
    }
}

@Composable
private fun PresetSwatch(pr: WidgetPreset) {
    val shape = RoundedCornerShape(9.dp)
    val m = Modifier.size(32.dp).clip(shape)
    val dark = WidgetStyle.DARK_PAL
    val light = WidgetStyle.LIGHT_PAL
    when (pr) {
        WidgetPreset.AUTO -> Box(m.background(Brush.linearGradient(0.5f to Color(dark.bg), 0.5f to Color.White))) { Dot(Color(dark.today)) }
        WidgetPreset.DARK -> Box(m.background(Color(dark.bg)).border(1.dp, Color(0xFF3A4742), shape)) { Dot(Color(dark.today)) }
        WidgetPreset.LIGHT -> Box(m.background(Color.White).border(1.dp, Color(0xFFD9E0DB), shape)) { Dot(Color(light.today)) }
        WidgetPreset.PHONE -> Box(m.background(Color(0xFF233A30))) { Dot(Color(0xFF8BE0B5)) }
        WidgetPreset.CUSTOM -> Box(m.background(Brush.sweepGradient(listOf(Color(0xFFE57373), Color(0xFFFFB74D), Color(0xFFFFF176), Color(0xFF81C784), Color(0xFF64B5F6), Color(0xFFBA68C8), Color(0xFFE57373)))))
    }
}

@Composable
private fun BoxScope.Dot(color: Color) {
    Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).size(11.dp).clip(RoundedCornerShape(3.dp)).background(color))
}

@Composable
private fun ColorRow(title: String, detail: String, key: String, expanded: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    val color = TaskPalette.resolve(key, c.dark)?.first ?: c.ink
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$title เปลี่ยนสี" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = c.muted)
        }
        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = c.muted)
    }
}

/** วงสี 11 สี + แถบสี + ความเข้ม (เหมือนตอนเลือกสีกิจกรรม) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WidgetColorPicker(selected: String, onSelect: (String) -> Unit) {
    val c = AppTheme.colors
    val dark = c.dark
    val base = TaskPalette.baseOf(selected)
    val strength = TaskPalette.strengthOf(selected)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FlowRow(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TaskPalette.swatches.forEach { sw ->
                val isSel = sw.key == base
                val color = TaskPalette.resolve(TaskPalette.withStrength(sw.key, strength), dark)!!.first
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .border(2.dp, if (isSel) c.ink else Color.Transparent, CircleShape)
                        .selectable(selected = isSel, role = Role.RadioButton) {
                            onSelect(TaskPalette.withStrength(sw.key, strength) ?: sw.key)
                        }
                        .semantics { contentDescription = "สี${sw.label}" },
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                        if (isSel) Icon(Icons.Filled.Check, contentDescription = null, tint = c.surface, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Hint("หรือลากเลือกสีเองจากแถบ")
        HueBar(hue = TaskPalette.hueOf(selected), strength = strength, dark = dark) {
            onSelect(TaskPalette.withStrength(TaskPalette.hueKey(it), strength) ?: TaskPalette.hueKey(it))
        }
        StrengthSlider(base = base, strength = strength, dark = dark) {
            onSelect(TaskPalette.withStrength(base, it) ?: selected)
        }
    }
}
