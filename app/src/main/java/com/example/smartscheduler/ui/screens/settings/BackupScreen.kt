package com.example.smartscheduler.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartscheduler.data.backup.BackupManager
import com.example.smartscheduler.ui.components.Hint
import com.example.smartscheduler.ui.components.Panel
import com.example.smartscheduler.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

sealed class BackupStep {
    data object Idle : BackupStep()
    data class Working(val label: String, val progress: Float?) : BackupStep()
    data class Exported(val size: Long) : BackupStep()
    data class Confirm(val meta: BackupManager.Meta) : BackupStep()
    data class Merged(val added: BackupManager.Counts) : BackupStep()
    data object Replaced : BackupStep()
    data class Failed(val message: String) : BackupStep()
}

@HiltViewModel
class BackupViewModel @Inject constructor(private val backup: BackupManager) : ViewModel() {
    private val _step = MutableStateFlow<BackupStep>(BackupStep.Idle)
    val step: StateFlow<BackupStep> = _step.asStateFlow()
    private val _last = MutableStateFlow(backup.lastBackupAt to backup.lastBackupSize)
    val last: StateFlow<Pair<Long, Long>> = _last.asStateFlow()
    private val _remind = MutableStateFlow(backup.remind)
    val remind: StateFlow<Boolean> = _remind.asStateFlow()

    fun suggestedName() = backup.suggestedName()
    fun setRemind(v: Boolean) { backup.remind = v; _remind.value = v }
    fun dismiss() { _step.value = BackupStep.Idle }

    fun export(uri: Uri) = viewModelScope.launch {
        _step.value = BackupStep.Working("กำลังสำรองข้อมูล", 0f)
        backup.export(uri) { _step.value = BackupStep.Working("กำลังสำรองข้อมูล", it) }
            .onSuccess { size -> _last.value = backup.lastBackupAt to size; _step.value = BackupStep.Exported(size) }
            .onFailure { _step.value = BackupStep.Failed("สำรองไม่สำเร็จ ลองเลือกที่เก็บอื่น") }
    }

    fun open(uri: Uri) = viewModelScope.launch {
        _step.value = BackupStep.Working("กำลังอ่านไฟล์", null)
        backup.open(uri)
            .onSuccess { meta ->
                _step.value = if (meta.dbVersion > backup.currentDbVersion())
                    BackupStep.Failed("ไฟล์นี้มาจากแอปรุ่นใหม่กว่า อัปเดตแอปก่อนแล้วลองอีกครั้ง")
                else BackupStep.Confirm(meta)
            }
            .onFailure { _step.value = BackupStep.Failed("ไฟล์นี้ไม่ใช่ไฟล์สำรองของแอป หรือไฟล์เสีย") }
    }

    fun restore(replace: Boolean) = viewModelScope.launch {
        _step.value = BackupStep.Working("กำลังกู้คืน", null)
        if (replace) backup.replaceAll()
            .onSuccess { _step.value = BackupStep.Replaced }
            .onFailure { _step.value = BackupStep.Failed("กู้คืนไม่สำเร็จ") }
        else backup.merge()
            .onSuccess { _step.value = BackupStep.Merged(it) }
            .onFailure { _step.value = BackupStep.Failed("กู้คืนไม่สำเร็จ") }
    }

    fun restart() = backup.restartApp()
}

private fun sizeText(b: Long) = when {
    b >= 1_000_000 -> "%.1f MB".format(b / 1_000_000.0)
    b >= 1_000 -> "${b / 1_000} KB"
    else -> "$b B"
}

private fun dateText(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val m = com.example.smartscheduler.domain.model.Recurrence.monthLabelsTh[d.monthValue - 1]
    return "%d %s %d %02d:%02d".format(d.dayOfMonth, m, d.year + 543, d.hour, d.minute)
}

@Composable
fun BackupScreen(onBack: () -> Unit, viewModel: BackupViewModel = hiltViewModel()) {
    val c = AppTheme.colors
    val step by viewModel.step.collectAsState()
    val last by viewModel.last.collectAsState()
    val remind by viewModel.remind.collectAsState()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.export(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.open(uri)
    }

    LazyColumn(
        Modifier.fillMaxSize().background(c.ground),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "กลับ") }
                Text("สำรองและกู้คืน", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Panel(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                ActionRow(Icons.Filled.Upload, "สำรองข้อมูลเป็นไฟล์", "ปฏิทิน กิจวัตร หมวด สมุด PDF และตั้งค่า") {
                    exportLauncher.launch(viewModel.suggestedName())
                }
                HorizontalDivider(color = c.line)
                ActionRow(Icons.Filled.Download, "กู้คืนจากไฟล์", "เลือกไฟล์สำรอง (.zip) ที่เคยสำรองไว้") {
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Panel(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.History, contentDescription = null, tint = c.ink)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("สำรองล่าสุด", style = MaterialTheme.typography.bodyLarge)
                        Text(if (last.first == 0L) "ยังไม่เคยสำรอง" else "${dateText(last.first)} · ${sizeText(last.second)}", style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                }
                HorizontalDivider(color = c.line)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Switch) { viewModel.setRemind(!remind) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.NotificationsActive, contentDescription = null, tint = c.ink)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("เตือนให้สำรองทุกสัปดาห์", style = MaterialTheme.typography.bodyLarge)
                        Text("แจ้งเตือนเมื่อไม่ได้สำรองเกิน 7 วัน", style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                    Switch(checked = remind, onCheckedChange = null)
                }
            }
            Hint(
                "เก็บไฟล์สำรองไว้ใน Google Drive หรือส่งเข้าไลน์ตัวเองก็ได้ ถ้าเปลี่ยนเครื่องหรือลบแอป ใช้ไฟล์นี้กู้คืนได้ทั้งหมด",
                Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp)
            )
        }
    }

    when (val s = step) {
        is BackupStep.Working -> AlertDialog(
            onDismissRequest = {}, containerColor = c.surface,
            title = { Text(s.label) },
            text = {
                Column {
                    if (s.progress != null) {
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                        Text("${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.padding(top = 6.dp))
                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Hint("อย่าเพิ่งปิดแอป", Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {}
        )
        is BackupStep.Exported -> InfoDialog("สำรองเสร็จแล้ว", "ไฟล์ขนาด ${sizeText(s.size)} เก็บไว้ในที่ที่เลือกแล้ว", viewModel::dismiss)
        is BackupStep.Failed -> InfoDialog("ทำไม่สำเร็จ", s.message, viewModel::dismiss)
        is BackupStep.Merged -> InfoDialog(
            "กู้คืนเสร็จแล้ว",
            "เพิ่มกิจกรรม ${s.added.tasks} · บันทึก ${s.added.notes} · PDF ${s.added.pdfs} · โฟลเดอร์ ${s.added.folders} · หมวด ${s.added.categories}\nรายการที่มีอยู่แล้วไม่ถูกเพิ่มซ้ำ",
            viewModel::dismiss
        )
        is BackupStep.Replaced -> AlertDialog(
            onDismissRequest = {}, containerColor = c.surface,
            title = { Text("กู้คืนเสร็จแล้ว") },
            text = { Text("ต้องเริ่มแอปใหม่เพื่อใช้ข้อมูลจากไฟล์") },
            confirmButton = { TextButton(onClick = viewModel::restart) { Text("เริ่มแอปใหม่") } }
        )
        is BackupStep.Confirm -> RestoreDialog(s.meta, onRestore = viewModel::restore, onDismiss = viewModel::dismiss)
        BackupStep.Idle -> {}
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = c.ink)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = c.muted)
    }
}

@Composable
private fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = AppTheme.colors.surface,
        title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ตกลง") } }
    )
}

@Composable
private fun RestoreDialog(meta: BackupManager.Meta, onRestore: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    var replace by remember { mutableStateOf(false) }
    var sure by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.surface,
        title = { Text("กู้คืนข้อมูล") },
        text = {
            Column {
                Text(
                    "ไฟล์จาก ${dateText(meta.created)} · กิจกรรม ${meta.counts.tasks} · บันทึก ${meta.counts.notes} · PDF ${meta.counts.pdfs}",
                    style = MaterialTheme.typography.bodySmall, color = c.muted
                )
                Spacer(Modifier.height(10.dp))
                RestoreOption("รวมกับข้อมูลที่มี", "ของเดิมยังอยู่ เพิ่มของในไฟล์เข้าไป ของที่ซ้ำไม่เพิ่ม (ไม่รวมตั้งค่า)", !replace) { replace = false; sure = false }
                Spacer(Modifier.height(8.dp))
                RestoreOption("แทนที่ทั้งหมด", "ลบข้อมูลในเครื่องตอนนี้ แล้วใช้ของในไฟล์ทั้งหมดรวมตั้งค่า (ย้อนกลับไม่ได้)", replace) { replace = true }
                if (replace) {
                    Row(Modifier.padding(top = 8.dp).clickable { sure = !sure }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = sure, onCheckedChange = { sure = it })
                        Text("เข้าใจแล้ว ข้อมูลในเครื่องตอนนี้จะหายทั้งหมด", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRestore(replace) }, enabled = !replace || sure) {
                Text("กู้คืน", color = if (replace && sure) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    )
}

@Composable
private fun RestoreOption(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) c.ink else c.line, RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = c.muted)
    }
}
