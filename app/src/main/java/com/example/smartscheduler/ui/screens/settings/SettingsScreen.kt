package com.example.smartscheduler.ui.screens.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.example.smartscheduler.data.local.entity.CategoryEntity
import com.example.smartscheduler.domain.model.TaskKind
import com.example.smartscheduler.settings.AppSettings
import com.example.smartscheduler.settings.FabStyle
import com.example.smartscheduler.settings.LocalAppearance
import com.example.smartscheduler.settings.Motion
import com.example.smartscheduler.ui.components.*
import com.example.smartscheduler.ui.theme.AppTheme
import com.example.smartscheduler.ui.theme.TaskPalette
import com.example.smartscheduler.update.AppVersionRow
import com.example.smartscheduler.update.rememberAppUpdateViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val settings: AppSettings) : ViewModel() {
    val fabStyle = settings.fabStyle
    val dragColor = settings.dragColor
    val motion = settings.motion
    fun setFabStyle(style: FabStyle) = settings.setFabStyle(style)
    fun setDragColor(key: String) = settings.setDragColor(key)
    fun setMotion(motion: Motion) = settings.setMotion(motion)
}

/** ชื่อสีตอนลากสำหรับแสดงสรุป */
private fun dragColorLabel(key: String): String = when {
    key == AppSettings.DRAG_WHITE -> "ขาว"
    TaskPalette.find(key) != null -> TaskPalette.find(key)!!.label
    else -> "สีจากแถบ"
}

// ============================================================================
// หน้าตั้งค่าหลัก: แถวให้แตะเข้าไป + เกี่ยวกับแอป (ตรวจหาอัปเดตกดได้ทันที)
// ============================================================================

@Composable
fun SettingsScreen(
    onOpenAppearance: () -> Unit,
    onOpenCategories: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val c = AppTheme.colors
    val fabStyle by viewModel.fabStyle.collectAsState()
    val dragColor by viewModel.dragColor.collectAsState()
    val motion by viewModel.motion.collectAsState()
    val categoryVm: CategoryViewModel = hiltViewModel()
    val categories by categoryVm.categories.collectAsState()

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
        item {
            Panel(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                SettingsRow(
                    icon = Icons.Filled.Palette,
                    title = "ปรับแต่งหน้าตา",
                    subtitle = "สีตอนลาก ${dragColorLabel(dragColor)} · เคลื่อนไหว${motion.label} · ${fabStyle.label}",
                    onClick = onOpenAppearance
                )
                HorizontalDivider(color = c.line)
                SettingsRow(
                    icon = Icons.Filled.Category,
                    title = "หมวดหมู่กิจกรรม",
                    subtitle = if (categories.isEmpty()) "ยังไม่มีหมวดที่สร้างเอง"
                    else "${categories.size} หมวด · " + categories.take(3).joinToString(" ") { it.name },
                    onClick = onOpenCategories
                )
            }
        }
        item {
            SectionTitle("เกี่ยวกับแอป", Modifier.padding(top = 12.dp))
            Panel { AppVersionRow(rememberAppUpdateViewModel()) }
        }
    }
}

/** แถวหน้าตั้งค่า: ไอคอน (สีเดียวกับตัวหนังสือ ไม่มีกล่องสี) + ชื่อ + สรุป + ลูกศร */
@Composable
private fun SettingsRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Hint(subtitle)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = c.muted)
    }
}

/** หัวหน้าย่อย: ปุ่มย้อนกลับ + ชื่อ + ปุ่มด้านขวา (ถ้ามี) */
@Composable
private fun SubPageTop(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "ย้อนกลับ") }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() }
        )
        action?.invoke()
    }
}

// ============================================================================
// ปรับแต่งหน้าตา
// ============================================================================

@Composable
fun AppearanceScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val c = AppTheme.colors
    val fabStyle by viewModel.fabStyle.collectAsState()
    val dragColor by viewModel.dragColor.collectAsState()
    val motion by viewModel.motion.collectAsState()
    val isWhite = dragColor == AppSettings.DRAG_WHITE
    val strength = if (isWhite) TaskPalette.DEFAULT_STRENGTH else TaskPalette.strengthOf(dragColor)
    val base = if (isWhite) null else TaskPalette.baseOf(dragColor)

    LazyColumn(
        Modifier.fillMaxSize().background(c.ground),
        contentPadding = PaddingValues(start = 12.dp, end = 20.dp, top = 8.dp, bottom = 32.dp)
    ) {
        item { SubPageTop("ปรับแต่งหน้าตา", onBack) }
        item {
            Column(Modifier.padding(start = 8.dp)) {
                AppearancePreview(dragColorLabel(dragColor))
                Spacer(Modifier.height(12.dp))
                Panel {
                    Text("สีตอนลากเลือกวัน", style = MaterialTheme.typography.titleSmall)
                    Hint("กดค้างที่วันในปฏิทินแล้วลาก")
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("สี", style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.weight(1f))
                        FilterChip(
                            selected = isWhite,
                            onClick = { viewModel.setDragColor(AppSettings.DRAG_WHITE) },
                            label = { Text("ใช้สีขาว") },
                            leadingIcon = {
                                Box(Modifier.size(16.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE6EEEA)))
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                    // แถบสีรุ้ง + ความเข้ม แบบเดียวกับตอนเลือกสีไอคอน
                    HueBar(
                        hue = TaskPalette.hueOf(base),
                        strength = strength,
                        dark = c.dark,
                        onChange = { h -> viewModel.setDragColor(TaskPalette.withStrength(TaskPalette.hueKey(h), strength)!!) }
                    )
                    StrengthSlider(
                        base = base,
                        strength = strength,
                        dark = c.dark,
                        onChange = { st -> base?.let { b -> viewModel.setDragColor(TaskPalette.withStrength(b, st)!!) } }
                    )
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
                Panel {
                    Text("เมนูปุ่มเพิ่ม (+)", style = MaterialTheme.typography.titleSmall)
                    Hint("กดปุ่ม + ทีเดียว หรือกดค้างแล้วลากนิ้วไปที่ไอคอน ปล่อยเพื่อเลือก")
                    Spacer(Modifier.height(10.dp))
                    SegmentedToggle(
                        options = FabStyle.values().map { it.label },
                        selectedIndex = FabStyle.values().indexOf(fabStyle),
                        onSelect = { viewModel.setFabStyle(FabStyle.values()[it]) }
                    )
                }
            }
        }
    }
}

/**
 * ตัวอย่างสด: ปฏิทินย่อ 2 แถว วันที่ 3–11 ถูกเลือกด้วยสีตอนลาก
 * เปลี่ยนสีหรือระดับการเคลื่อนไหวแล้ว เด้งให้ดูใหม่ทันที (ไล่ทีละวัน)
 */
@Composable
private fun AppearancePreview(colorLabel: String) {
    val c = AppTheme.colors
    val look = LocalAppearance.current
    Panel {
        Row {
            Hint("ตัวอย่าง", Modifier.weight(1f))
            Hint("$colorLabel · เคลื่อนไหว${look.motion.label}")
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
                    Box(Modifier.weight(1f).height(40.dp).padding(2.dp), contentAlignment = Alignment.Center) {
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

// ============================================================================
// หมวดหมู่กิจกรรม
// ============================================================================

@Composable
fun CategoriesScreen(onBack: () -> Unit) {
    val c = AppTheme.colors
    val vm: CategoryViewModel = hiltViewModel()
    val categories by vm.categories.collectAsState()
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().background(c.ground),
        contentPadding = PaddingValues(start = 12.dp, end = 20.dp, top = 8.dp, bottom = 32.dp)
    ) {
        item {
            SubPageTop("หมวดหมู่กิจกรรม", onBack) {
                TextButton(onClick = { creating = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("เพิ่มหมวด")
                }
            }
        }
        item {
            Column(Modifier.padding(start = 8.dp)) {
                if (categories.isEmpty()) {
                    Hint("สร้างหมวดของตัวเองได้ เช่น อ่านหนังสือ ออกกำลังกาย แต่ละหมวดมีสีและไอคอนของตัวเอง แล้วเลือกได้ตอนเพิ่มกิจกรรม")
                } else {
                    Panel(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                        categories.forEachIndexed { i, cat ->
                            val k = TaskKind.from(cat.kind)
                            if (i > 0) HorizontalDivider(color = c.line)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 60.dp)
                                    .clickable(role = Role.Button) { editing = cat }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // เหลือแค่ไอคอนสีของหมวด ไม่มีกล่องสีรอบ
                                Icon(taskIcon(k, null, cat.categoryId), contentDescription = null, tint = taskAccent(k, null, cat.categoryId), modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(cat.name, style = MaterialTheme.typography.titleMedium)
                                    Hint("นับเป็น${k.label}")
                                }
                                Icon(Icons.Filled.ChevronRight, contentDescription = "แก้หมวด ${cat.name}", tint = c.muted)
                            }
                        }
                    }
                    Hint("แตะหมวดเพื่อแก้ชื่อ สี ไอคอน หรือลบ", Modifier.padding(top = 10.dp))
                }
            }
        }
    }

    if (creating) {
        CategoryDialog(initial = null, onSave = { vm.save(it); creating = false }, onDismiss = { creating = false })
    }
    editing?.let { cat ->
        CategoryDialog(
            initial = cat,
            onSave = { vm.save(it); editing = null },
            onDelete = { vm.delete(cat.categoryId); editing = null },
            onDismiss = { editing = null }
        )
    }
}
