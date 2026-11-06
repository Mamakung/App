package com.example.smartscheduler.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartscheduler.data.local.entity.CategoryEntity
import com.example.smartscheduler.data.local.entity.ScheduleTaskEntity
import com.example.smartscheduler.data.repository.CategoryRepository
import com.example.smartscheduler.data.session.CurrentUser
import com.example.smartscheduler.domain.model.TaskKind
import com.example.smartscheduler.ui.theme.AppTheme
import com.example.smartscheduler.ui.theme.TaskPalette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// ============================================================================
// Icons ที่เลือกได้
// ============================================================================

/** รองเท้าวิ่ง (Material ไม่มีไอคอนรองเท้า จึงวาดเอง ขนาด 24×24 รูเชือกเป็นช่องว่าง) */
private val Sneaker: ImageVector by lazy {
    ImageVector.Builder(name = "Sneaker", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = PathParser().parsePathString(
                "M2.5 15.5V9.6c0-.6.5-1.1 1.1-1.1h1.6c.5 0 .9.3 1.1.7l.5 1.1c.3.6.9 1 1.6 1h.4c.6 0 1.1-.3 1.4-.8l1.2-2c.3-.5.9-.7 1.4-.4l7.6 4.1c1.4.7 2.3 2.1 2.3 3.7v-.4zM2.5 16.8h19v1.1c0 .7-.6 1.3-1.3 1.3H3.8c-.7 0-1.3-.6-1.3-1.3v-1.1zM11.9 10.7L13.1 9.3L13.9 9.9L12.7 11.3zM14.0 11.8L15.2 10.4L16.0 11.0L14.8 12.4zM16.1 12.9L17.3 11.5L18.1 12.1L16.9 13.5z"
            ).toNodes(),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(Color.Black)
        )
        .build()
}

object TaskIcons {
    data class Item(val key: String, val label: String, val vector: ImageVector)

    val all = listOf(
        Item("school", "เรียน", Icons.Filled.School),
        Item("book", "อ่านหนังสือ", Icons.Filled.AutoStories),
        Item("science", "วิทยาศาสตร์", Icons.Filled.Science),
        Item("calculate", "คำนวณ", Icons.Filled.Calculate),
        Item("translate", "ภาษา", Icons.Filled.Translate),
        Item("computer", "คอมพิวเตอร์", Icons.Filled.Computer),
        Item("chip", "อิเล็กทรอนิกส์", Icons.Filled.Memory),
        Item("electric", "ไฟฟ้า", Icons.Filled.ElectricalServices),
        Item("build", "ช่าง", Icons.Filled.Build),
        Item("engineering", "งานปฏิบัติ", Icons.Filled.Engineering),
        Item("work", "ทำงาน", Icons.Filled.Work),
        Item("laptop", "งานคอม", Icons.Filled.Laptop),
        Item("event", "นัดหมาย", Icons.Filled.Event),
        Item("groups", "ประชุม/กลุ่ม", Icons.Filled.Groups),
        Item("call", "โทร", Icons.Filled.Call),
        Item("check", "สิ่งที่ต้องทำ", Icons.Filled.Checklist),
        Item("coffee", "พัก", Icons.Filled.Coffee),
        Item("food", "กินข้าว", Icons.Filled.Restaurant),
        Item("sleep", "นอน", Icons.Filled.Bedtime),
        Item("mind", "ทำสมาธิ", Icons.Filled.SelfImprovement),
        Item("gym", "ออกกำลังกาย", Icons.Filled.FitnessCenter),
        Item("run", "วิ่ง", Icons.AutoMirrored.Filled.DirectionsRun),
        Item("shoe", "รองเท้าวิ่ง", Sneaker),
        Item("walk", "เดิน", Icons.AutoMirrored.Filled.DirectionsWalk),
        Item("soccer", "กีฬา", Icons.Filled.SportsSoccer),
        Item("game", "เล่นเกม", Icons.Filled.SportsEsports),
        Item("music", "ดนตรี", Icons.Filled.MusicNote),
        Item("art", "ศิลปะ", Icons.Filled.Palette),
        Item("movie", "ดูหนัง", Icons.Filled.Movie),
        Item("home", "งานบ้าน", Icons.Filled.Home),
        Item("clean", "ทำความสะอาด", Icons.Filled.CleaningServices),
        Item("shop", "ซื้อของ", Icons.Filled.ShoppingCart),
        Item("money", "การเงิน", Icons.Filled.Payments),
        Item("car", "เดินทาง", Icons.Filled.DirectionsCar),
        Item("bus", "รถโดยสาร", Icons.Filled.DirectionsBus),
        Item("health", "สุขภาพ", Icons.Filled.MedicalServices),
        Item("pets", "สัตว์เลี้ยง", Icons.Filled.Pets),
        Item("alarm", "เตือน", Icons.Filled.Alarm),
        Item("flag", "เป้าหมาย", Icons.Filled.Flag),
        Item("star", "สำคัญ", Icons.Filled.Star),
        Item("heart", "ครอบครัว/คนรัก", Icons.Filled.Favorite)
    )

    fun find(key: String?): Item? = key?.let { k -> all.firstOrNull { it.key == k } }
}

// ============================================================================
// หมวดหมู่: ข้อมูลทั้งแอป + ViewModel
// ============================================================================

/** หมวดหมู่ทั้งหมดของผู้ใช้ (ตั้งที่ AppNavGraph) ใช้หาสี/ไอคอน/ชื่อของกิจกรรม */
val LocalCategories = compositionLocalOf<Map<Long, CategoryEntity>> { emptyMap() }

@HiltViewModel
class CategoryViewModel @Inject constructor(
    private val repo: CategoryRepository,
    private val user: CurrentUser
) : ViewModel() {
    val categories: StateFlow<List<CategoryEntity>> = repo.observe(user.uid)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun save(category: CategoryEntity, onSaved: (Long) -> Unit = {}) {
        viewModelScope.launch {
            onSaved(repo.save(category.copy(ownerUid = user.uid), categories.value.size))
        }
    }

    fun delete(categoryId: Long) {
        viewModelScope.launch { repo.delete(categoryId) }
    }
}

@Composable
private fun category(id: Long?): CategoryEntity? = id?.let { LocalCategories.current[it] }

/** สีหลัก: สีที่เลือกเอง > สีของหมวด > สีตามประเภท */
@Composable
fun taskAccent(kind: TaskKind, color: String?, categoryId: Long? = null): Color =
    TaskPalette.resolve(color ?: category(categoryId)?.color, AppTheme.colors.dark)?.first ?: kind.color()

/** สีพื้นอ่อน คู่กับ [taskAccent] */
@Composable
fun taskFill(kind: TaskKind, color: String?, categoryId: Long? = null): Color =
    TaskPalette.resolve(color ?: category(categoryId)?.color, AppTheme.colors.dark)?.second ?: kind.bg()

/** ไอคอน: ที่เลือกเอง > ของหมวด > ตามประเภท */
@Composable
fun taskIcon(kind: TaskKind, icon: String?, categoryId: Long? = null): ImageVector =
    (TaskIcons.find(icon) ?: TaskIcons.find(category(categoryId)?.icon))?.vector ?: kind.icon()

/** ชื่อหมวด (หรือชื่อประเภท) */
@Composable
fun taskLabel(kind: TaskKind, categoryId: Long? = null): String = category(categoryId)?.name ?: kind.label

@Composable
fun ScheduleTaskEntity.accent(): Color = taskAccent(TaskKind.from(kind), color, categoryId)

@Composable
fun ScheduleTaskEntity.fill(): Color = taskFill(TaskKind.from(kind), color, categoryId)

@Composable
fun ScheduleTaskEntity.iconVector(): ImageVector = taskIcon(TaskKind.from(kind), icon, categoryId)

@Composable
fun ScheduleTaskEntity.categoryLabel(): String = taskLabel(TaskKind.from(kind), categoryId)

// ============================================================================
// เลือกหมวดในหน้าต่างแก้กิจกรรม
// ============================================================================

/**
 * ชิปหมวด: 4 ประเภทพื้นฐาน + หมวดที่สร้างเอง + ปุ่ม "หมวดใหม่"
 * เลือกหมวดที่สร้างเองแล้วมีปุ่ม "แก้หมวดนี้" ใต้ชิป
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryChips(kind: TaskKind, categoryId: Long?, onPick: (TaskKind, Long?) -> Unit) {
    val vm: CategoryViewModel = hiltViewModel()
    val categories by vm.categories.collectAsState()
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("หมวด", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            TaskKind.values().forEach { k ->
                CategoryChip(
                    label = k.label, icon = k.icon(), accent = k.color(),
                    selected = categoryId == null && kind == k,
                    onClick = { onPick(k, null) }
                )
            }
            categories.forEach { cat ->
                val k = TaskKind.from(cat.kind)
                CategoryChip(
                    label = cat.name, icon = taskIcon(k, null, cat.categoryId), accent = taskAccent(k, null, cat.categoryId),
                    selected = categoryId == cat.categoryId,
                    onClick = { onPick(k, cat.categoryId) }
                )
            }
            AssistChip(
                onClick = { creating = true },
                label = { Text("หมวดใหม่") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }
        categories.firstOrNull { it.categoryId == categoryId }?.let { cat ->
            TextButton(onClick = { editing = cat }) { Text("แก้หมวด \"${cat.name}\"") }
        }
    }

    if (creating) {
        CategoryDialog(
            initial = null,
            onSave = { c ->
                vm.save(c) { id -> onPick(TaskKind.from(c.kind), id) }
                creating = false
            },
            onDismiss = { creating = false }
        )
    }
    editing?.let { cat ->
        CategoryDialog(
            initial = cat,
            onSave = { c -> vm.save(c); onPick(TaskKind.from(c.kind), c.categoryId); editing = null },
            onDelete = { vm.delete(cat.categoryId); onPick(TaskKind.from(cat.kind), null); editing = null },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun CategoryChip(label: String, icon: ImageVector, accent: Color, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = {
            Icon(
                if (selected) Icons.Filled.Check else icon, contentDescription = null,
                tint = if (selected) AppTheme.colors.ink else accent, modifier = Modifier.size(18.dp)
            )
        },
        modifier = Modifier.heightIn(min = 48.dp)
    )
}

// ============================================================================
// สร้าง / แก้หมวด
// ============================================================================

@Composable
fun CategoryDialog(
    initial: CategoryEntity?,
    onSave: (CategoryEntity) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val c = AppTheme.colors
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var kind by remember { mutableStateOf(TaskKind.from(initial?.kind ?: TaskKind.TASK.code)) }
    var color by remember { mutableStateOf(initial?.color) }
    var icon by remember { mutableStateOf(initial?.icon) }
    var askDelete by remember { mutableStateOf(false) }
    val kinds = TaskKind.values().toList()
    val accent = taskAccent(kind, color)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(if (initial == null) "หมวดใหม่" else "แก้หมวด") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // ตัวอย่างหน้าตา
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(taskFill(kind, color)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(taskIcon(kind, icon), contentDescription = null, tint = accent)
                    Spacer(Modifier.width(10.dp))
                    Text(name.ifBlank { "ชื่อหมวด" }, style = MaterialTheme.typography.titleSmall, color = c.ink)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(24) },
                    label = { Text("ชื่อหมวด") },
                    placeholder = { Text("เช่น อ่านหนังสือ ออกกำลังกาย") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("นับเป็น", style = MaterialTheme.typography.titleSmall)
                SegmentedToggle(
                    options = kinds.map { it.label },
                    selectedIndex = kinds.indexOf(kind),
                    onSelect = { kind = kinds[it] }
                )
                Hint("ใช้นับเวลาทำงาน/พักในหน้าภาพรวม")
                Text("สี", style = MaterialTheme.typography.titleSmall)
                ColorPicker(kind = kind, selected = color, onSelect = { color = it })
                Text("ไอคอน", style = MaterialTheme.typography.titleSmall)
                IconPicker(kind = kind, categoryId = null, selected = icon, accent = accent, onSelect = { icon = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        (initial ?: CategoryEntity(ownerUid = "", name = "")).copy(
                            name = name.trim(), kind = kind.code, color = color, icon = icon
                        )
                    )
                }
            ) { Text("บันทึก") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = { askDelete = true }) { Text("ลบหมวด", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("ยกเลิก") }
            }
        }
    )

    if (askDelete && onDelete != null) {
        ConfirmDeleteDialog(
            title = "ลบหมวด \"${initial?.name}\"",
            message = "กิจกรรมในหมวดนี้ไม่หาย แค่กลับไปใช้สีและไอคอนตามประเภท",
            confirmText = "ลบหมวด",
            onConfirm = { askDelete = false; onDelete() },
            onDismiss = { askDelete = false }
        )
    }
}

// ============================================================================
// เลือกไอคอน
// ============================================================================

/**
 * วงแรก = ตามหมวด/ประเภท แสดง 11 ไอคอนแรก กด "ดูทั้งหมด" เพื่อขยาย
 * ตัวที่เลือกมีพื้นสีและกรอบ ทุกปุ่มสูง 48dp มีชื่อให้โปรแกรมอ่านหน้าจอ
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconPicker(kind: TaskKind, categoryId: Long?, selected: String?, accent: Color, onSelect: (String?) -> Unit) {
    val c = AppTheme.colors
    var expanded by remember { mutableStateOf(selected != null && TaskIcons.all.indexOfFirst { it.key == selected } >= 11) }
    val shown = if (expanded) TaskIcons.all else TaskIcons.all.take(11)
    val fill = taskFill(kind, null, categoryId)

    Column {
        FlowRow(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconCell(
                vector = taskIcon(kind, null, categoryId), label = "ตาม${taskLabel(kind, categoryId)}",
                selected = selected == null, accent = accent, fill = fill, onClick = { onSelect(null) }
            )
            shown.forEach { item ->
                IconCell(
                    vector = item.vector, label = item.label, selected = selected == item.key,
                    accent = accent, fill = fill, onClick = { onSelect(item.key) }
                )
            }
        }
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "ย่อ" else "ดูทั้งหมด ${TaskIcons.all.size} แบบ", color = c.ink)
        }
    }
}

@Composable
private fun IconCell(vector: ImageVector, label: String, selected: Boolean, accent: Color, fill: Color, onClick: () -> Unit) {
    val c = AppTheme.colors
    // ตัวที่เลือก: กรอบรอบนอก + ไอคอนเป็นสีที่เลือก (ไม่ทาสีพื้น)
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .border(2.dp, if (selected) c.ink else Color.Transparent, CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "ไอคอน$label" },
        contentAlignment = Alignment.Center
    ) {
        Icon(vector, contentDescription = null, tint = if (selected) accent else c.muted, modifier = Modifier.size(22.dp))
    }
}

// ============================================================================
// จัดการหมวด (หน้ากิจวัตร)
// ============================================================================

/** รายการหมวดที่สร้างเอง แตะเพื่อแก้ มีปุ่มเพิ่มหมวด */
@Composable
fun CategoriesSection() {
    val vm: CategoryViewModel = hiltViewModel()
    val categories by vm.categories.collectAsState()
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    val c = AppTheme.colors

    SectionTitle("หมวดหมู่กิจกรรม", Modifier.padding(top = 12.dp)) {
        TextButton(onClick = { creating = true }) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("เพิ่มหมวด")
        }
    }
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
                        .heightIn(min = 56.dp)
                        .clip(MaterialTheme.shapes.small)
                        .selectable(selected = false, role = Role.Button, onClick = { editing = cat })
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(taskFill(k, null, cat.categoryId)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(taskIcon(k, null, cat.categoryId), contentDescription = null, tint = taskAccent(k, null, cat.categoryId), modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cat.name, style = MaterialTheme.typography.bodyLarge)
                        Hint("นับเป็น${k.label}")
                    }
                    Icon(Icons.Filled.ChevronRight, contentDescription = "แก้หมวด ${cat.name}", tint = c.muted)
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
