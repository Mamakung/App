package com.example.smartscheduler.ui.components

import android.app.Activity
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.smartscheduler.settings.SoundLibrary
import com.example.smartscheduler.ui.theme.AppTheme

/**
 * เลือกเสียงแจ้งเตือน/ปลุก
 *  - ของแอป (5 เสียง) แตะ ▶ ฟังตัวอย่าง
 *  - ไฟล์ของฉัน (เฉพาะเสียงปลุก): เพิ่มไฟล์ / แก้ชื่อ / ลบ
 *  - เสียงในโทรศัพท์ (ตัวเลือกเสียงของระบบ)
 * [allowDefault] = มีตัวเลือก "ตามตั้งค่า" (ใช้ตอนตั้งเสียงเฉพาะกิจกรรม) ส่ง null กลับ
 */
@Composable
fun SoundPickerDialog(
    alarm: Boolean,
    selected: String?,
    allowDefault: Boolean = false,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val c = AppTheme.colors
    val context = LocalContext.current
    val lib = remember { SoundLibrary(context) }
    var chosen by remember { mutableStateOf(selected) }
    var customs by remember { mutableStateOf(lib.customSounds()) }
    var phoneKey by remember { mutableStateOf(selected?.takeIf { it.startsWith("uri:") }) }
    var menuFor by remember { mutableStateOf<SoundLibrary.Custom?>(null) }
    var renaming by remember { mutableStateOf<SoundLibrary.Custom?>(null) }

    // ---------- ฟังตัวอย่าง ----------
    var playing by remember { mutableStateOf<String?>(null) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    fun stopPreview() { runCatching { player.value?.stop() }; player.value?.release(); player.value = null; playing = null }
    fun preview(key: String) {
        if (playing == key) { stopPreview(); return }
        stopPreview()
        val uri = lib.uriOf(key) ?: return
        runCatching {
            player.value = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                setDataSource(context, uri)
                setOnCompletionListener { stopPreview() }
                prepare(); start()
            }
            playing = key
        }.onFailure { Toast.makeText(context, "เล่นเสียงนี้ไม่ได้", Toast.LENGTH_SHORT).show() }
    }
    DisposableEffect(Unit) { onDispose { stopPreview() } }

    // ---------- เพิ่มไฟล์ / เสียงในโทรศัพท์ ----------
    val addFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val key = lib.addCustom(uri)
            if (key == null) Toast.makeText(context, "ไฟล์นี้เล่นไม่ได้ ลองไฟล์ mp3, m4a, ogg หรือ wav", Toast.LENGTH_LONG).show()
            else { customs = lib.customSounds(); chosen = key }
        }
    }
    val phonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = r.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) { phoneKey = "uri:$uri"; chosen = phoneKey }
        }
    }

    AlertDialog(
        onDismissRequest = { stopPreview(); onDismiss() },
        containerColor = c.surface,
        title = { Text(if (alarm) "เสียงปลุก" else "เสียงแจ้งเตือน") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (allowDefault) {
                    SoundRow("ตามตั้งค่า", "ใช้เสียงที่ตั้งไว้ในหน้าตั้งค่า", chosen == null, playing = false, onPick = { chosen = null }, onPlay = null)
                }
                Hint("ของแอป", Modifier.padding(top = 4.dp, bottom = 2.dp))
                lib.appSounds(alarm).forEach { s ->
                    SoundRow(s.label, s.detail, chosen == s.key, playing == s.key, onPick = { chosen = s.key }, onPlay = { preview(s.key) })
                }
                if (alarm) {
                    Hint("ไฟล์ของฉัน", Modifier.padding(top = 12.dp, bottom = 2.dp))
                    customs.forEach { f ->
                        val key = "file:${f.id}"
                        val secs = f.durationMs / 1000
                        SoundRow(
                            f.name, "%d:%02d".format(secs / 60, secs % 60), chosen == key, playing == key,
                            onPick = { chosen = key }, onPlay = { preview(key) },
                            trailing = {
                                Box {
                                    IconButton(onClick = { menuFor = f }) { Icon(Icons.Filled.MoreVert, contentDescription = "ตัวเลือกของ ${f.name}", tint = c.muted) }
                                    DropdownMenu(expanded = menuFor == f, onDismissRequest = { menuFor = null }) {
                                        DropdownMenuItem(text = { Text("แก้ชื่อ") }, onClick = { renaming = f; menuFor = null })
                                        DropdownMenuItem(
                                            text = { Text("ลบ", color = MaterialTheme.colorScheme.error) },
                                            onClick = {
                                                if (playing == key) stopPreview()
                                                lib.delete(f.id); customs = lib.customSounds()
                                                if (chosen == key) chosen = if (allowDefault) null else SoundLibrary.ALARM_SOUNDS.first().key
                                                menuFor = null
                                            }
                                        )
                                    }
                                }
                            }
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) {
                            addFile.launch(arrayOf("audio/*"))
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = c.ink)
                        Spacer(Modifier.width(12.dp))
                        Text("เพิ่มไฟล์เสียง", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Hint("mp3 m4a ogg wav")
                    }
                }
                // เสียงในโทรศัพท์
                phoneKey?.let { k ->
                    SoundRow(lib.labelOf(k), "เสียงในโทรศัพท์", chosen == k, playing == k, onPick = { chosen = k }, onPlay = { preview(k) })
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 4.dp).clickable(role = Role.Button) {
                        stopPreview()
                        val type = if (alarm) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION
                        phonePicker.launch(
                            Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, type)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, if (alarm) "เสียงปลุก" else "เสียงแจ้งเตือน")
                        )
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.PhoneAndroid, contentDescription = null, tint = c.ink)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("เสียงในโทรศัพท์", style = MaterialTheme.typography.bodyLarge)
                        Hint(if (alarm) "เลือกจากเสียงปลุกของเครื่อง" else "เลือกจากเสียงแจ้งเตือนของเครื่อง")
                    }
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = c.muted)
                }
            }
        },
        confirmButton = { TextButton(onClick = { stopPreview(); onSelect(chosen) }) { Text("ใช้เสียงนี้") } },
        dismissButton = { TextButton(onClick = { stopPreview(); onDismiss() }) { Text("ยกเลิก") } }
    )

    renaming?.let { f ->
        var name by remember(f) { mutableStateOf(f.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = c.surface,
            title = { Text("แก้ชื่อเสียง") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, label = { Text("ชื่อ") }) },
            confirmButton = { TextButton(onClick = { lib.rename(f.id, name); customs = lib.customSounds(); renaming = null }) { Text("บันทึก") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("ยกเลิก") } }
        )
    }
}

@Composable
private fun SoundRow(
    label: String,
    detail: String,
    selected: Boolean,
    playing: Boolean,
    onPick: () -> Unit,
    onPlay: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null
) {
    val c = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onPick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Hint(if (playing) "กำลังเล่น…" else detail)
        }
        if (onPlay != null) {
            IconButton(onClick = onPlay, modifier = Modifier.semantics { contentDescription = if (playing) "หยุดฟัง $label" else "ฟัง $label" }) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null, tint = c.ink)
            }
        }
        trailing?.invoke()
    }
}
