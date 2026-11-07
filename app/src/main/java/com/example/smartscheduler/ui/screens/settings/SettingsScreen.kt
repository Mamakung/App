package com.example.smartscheduler.ui.screens.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import com.example.smartscheduler.settings.DragColor
import com.example.smartscheduler.settings.LocalAppearance
import com.example.smartscheduler.settings.Motion
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.example.smartscheduler.settings.AppSettings
import com.example.smartscheduler.settings.FabStyle
import com.example.smartscheduler.ui.components.*
import com.example.smartscheduler.ui.theme.AppTheme
import com.example.smartscheduler.update.AppVersionRow
import com.example.smartscheduler.update.rememberAppUpdateViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val settings: AppSettings) : ViewModel() {
    val fabStyle = settings.fabStyle
    val dragColor = settings.dragColor
    val motion = settings.motion
    fun setFabStyle(style: FabStyle) = settings.setFabStyle(style)
    fun setDragColor(color: com.example.smartscheduler.settings.DragColor) = settings.setDragColor(color)
    fun setMotion(motion: com.example.smartscheduler.settings.Motion) = settings.setMotion(motion)
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val c = AppTheme.colors
    val fabStyle by viewModel.fabStyle.collectAsState()
    val dragColor by viewModel.dragColor.collectAsState()
    val motion by viewModel.motion.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize().background(c.ground),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp)
    ) {
        item {
            Text(
                "ตั้งค่า",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp).semantics { heading() }
            )
        }

        // ---------- ปรับแต่งหน้าตา ----------
        item {
            SectionTitle("ปรับแต่งหน้าตา", Modifier.padding(top = 0.dp))
            AppearancePreview()
            Spacer(Modifier.height(12.dp))
            Panel {
                Text("สีตอนลากเลือกวัน", style = MaterialTheme.typography.titleSmall)
                Hint("กดค้างที่วันในปฏิทินแล้วลาก")
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp).selectableGroup(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    DragColor.values().forEach { dc ->
                        DragSwatch(dc, selected = dc == dragColor, onClick = { viewModel.setDragColor(dc) })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Panel {
                Text("การเคลื่อนไหว", style = MaterialTheme.typography.titleSmall)
                Hint("เด้งตอนเลือกวัน เลือกหมวด และเปลี่ยนเดือน")
                Spacer(Modifier.height(10.dp))
                SegmentedToggle(
                    options = Motion.values().map { it.label },
                    selectedIndex = Motion.values().indexOf(motion),
                    onSelect = { viewModel.setMotion(Motion.values()[it]) }
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("เมนูปุ่มเพิ่ม (+)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
            Row(
                Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FabStyle.values().forEach { style ->
                    FabStyleCard(
                        style = style,
                        selected = style == fabStyle,
                        onClick = { viewModel.setFabStyle(style) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Hint("กดปุ่ม + ทีเดียว หรือกดค้างแล้วลากนิ้วไปที่ไอคอน ปล่อยเพื่อเลือก", Modifier.padding(top = 8.dp))
        }

        item { CategoriesSection() }

        item {
            SectionTitle("เกี่ยวกับแอป", Modifier.padding(top = 12.dp))
            Panel { AppVersionRow(rememberAppUpdateViewModel()) }
        }
    }
}

@Composable
private fun FabStyleCard(style: FabStyle, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.ink else c.line, RoundedCornerShape(16.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FabStylePreview(style, accent = if (selected) c.study else c.muted)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = c.ink, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(style.label, style = MaterialTheme.typography.titleSmall)
        }
        Hint(style.detail)
    }
}

/**
 * ตัวอย่างสด: ปฏิทินย่อ 2 แถว วันที่ 3–11 ถูกเลือกด้วยสีตอนลาก
 * เปลี่ยนสีหรือระดับการเคลื่อนไหวแล้ว เด้งให้ดูใหม่ทันที (ไล่ทีละวัน)
 */
@Composable
private fun AppearancePreview() {
    val c = AppTheme.colors
    val look = LocalAppearance.current
    Panel {
        Row {
            Hint("ตัวอย่าง", Modifier.weight(1f))
            Hint("${DragColor.values().firstOrNull { Color(it.argb) == look.dragColor }?.label ?: ""} · เคลื่อนไหว${look.motion.label}")
        }
        Spacer(Modifier.height(8.dp))
        for (week in 0..1) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0..6) {
                    val day = week * 7 + col + 1
                    val inRange = day in 3..11
                    val a = remember { Animatable(0f) }
                    LaunchedEffect(look) {
                        a.snapTo(0f)
                        if (inRange) {
                            if (look.animate) delay((day - 3) * 45L)
                            a.animateTo(1f, look.pop())
                        }
                    }
                    Box(
                        Modifier.weight(1f).height(40.dp).padding(2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (inRange) {
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .graphicsLayer {
                                        val sc = 0.82f + 0.18f * a.value
                                        scaleX = sc; scaleY = sc; alpha = a.value.coerceIn(0f, 1f)
                                    }
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(look.dragColor.copy(alpha = 0.14f))
                                    .border(1.5.dp, look.dragColor.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                            )
                        }
                        Text("$day", style = MaterialTheme.typography.bodyMedium, color = c.ink)
                    }
                }
            }
        }
    }
}

/** วงสีตอนลาก: ตัวที่เลือกมีวงรอบนอก + เครื่องหมายถูก */
@Composable
private fun DragSwatch(dc: DragColor, selected: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    val color = Color(dc.argb)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .border(2.dp, if (selected) c.ink else Color.Transparent, CircleShape)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .semantics { contentDescription = "สี${dc.label}" },
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = c.ground, modifier = Modifier.size(20.dp))
            }
        }
        Hint(dc.label)
    }
}
