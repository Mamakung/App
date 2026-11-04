package com.example.smartscheduler.update

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.example.smartscheduler.ui.components.Hint
import com.example.smartscheduler.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

// ============================================================================
// ViewModel
// ============================================================================

sealed interface UpdateStep {
    data object Idle : UpdateStep
    data object Checking : UpdateStep
    data object UpToDate : UpdateStep
    data class Available(val update: AppUpdate) : UpdateStep
    data class Downloading(val update: AppUpdate, val progress: Float) : UpdateStep
    data class NeedsPermission(val update: AppUpdate, val file: File) : UpdateStep
    data class ReadyToInstall(val update: AppUpdate, val file: File) : UpdateStep
    data class Failed(val message: String, val update: AppUpdate?) : UpdateStep
}

data class UpdateUiState(
    val step: UpdateStep = UpdateStep.Idle,
    val dialogVisible: Boolean = false,
    val currentVersion: String = ""
)

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val repo: UpdateRepository
) : ViewModel() {

    private val _state = MutableStateFlow(UpdateUiState(currentVersion = repo.currentVersionName))
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var checkedOnLaunch = false

    /** เช็กเงียบๆ ตอนเปิดแอป จะแสดงหน้าต่างเฉพาะเมื่อมีเวอร์ชันใหม่ */
    fun checkOnLaunch() {
        if (checkedOnLaunch) return
        checkedOnLaunch = true
        check(showWhenNothing = false)
    }

    /** ผู้ใช้กด "ตรวจหาอัปเดต" เอง: แสดงผลทุกกรณี */
    fun checkManually() = check(showWhenNothing = true)

    private fun check(showWhenNothing: Boolean) {
        if (_state.value.step is UpdateStep.Downloading) return
        if (showWhenNothing) _state.update { it.copy(step = UpdateStep.Checking, dialogVisible = true) }
        viewModelScope.launch {
            val result = runCatching { repo.checkForUpdate() }
            _state.update { s ->
                result.fold(
                    onSuccess = { update ->
                        when {
                            update != null -> s.copy(step = UpdateStep.Available(update), dialogVisible = true)
                            showWhenNothing -> s.copy(step = UpdateStep.UpToDate)
                            else -> s
                        }
                    },
                    onFailure = { e ->
                        val message = if (e is NoReleaseYetException)
                            "ยังไม่มีเวอร์ชันที่ปล่อยไว้บน GitHub (repo ต้องเป็น public และมี Release อย่างน้อยหนึ่งรายการ)"
                        else "ตรวจหาอัปเดตไม่ได้ ตรวจอินเทอร์เน็ตแล้วลองอีกครั้ง"
                        if (showWhenNothing) s.copy(step = UpdateStep.Failed(message, null)) else s
                    }
                )
            }
        }
    }

    /** เปิดหน้าต่างจากการแตะการแจ้งเตือน */
    fun openFromNotification() = check(showWhenNothing = true)

    fun startDownload(update: AppUpdate) {
        _state.update { it.copy(step = UpdateStep.Downloading(update, 0f)) }
        viewModelScope.launch {
            runCatching {
                repo.download(update) { p -> _state.update { it.copy(step = UpdateStep.Downloading(update, p)) } }
            }.onSuccess { file ->
                _state.update {
                    it.copy(step = if (repo.canInstall()) UpdateStep.ReadyToInstall(update, file) else UpdateStep.NeedsPermission(update, file))
                }
            }.onFailure {
                _state.update { s -> s.copy(step = UpdateStep.Failed("ดาวน์โหลดไม่สำเร็จ ลองใหม่อีกครั้ง", update)) }
            }
        }
    }

    /** หลังผู้ใช้กลับมาจากหน้าตั้งค่าอนุญาตติดตั้ง */
    fun recheckPermission() {
        val s = _state.value.step
        if (s is UpdateStep.NeedsPermission && repo.canInstall()) {
            _state.update { it.copy(step = UpdateStep.ReadyToInstall(s.update, s.file)) }
        }
    }

    fun install(context: Context, file: File) = context.startActivity(repo.installIntent(file))
    fun openPermissionSettings(context: Context) = context.startActivity(repo.installPermissionIntent())

    fun dismiss() {
        _state.update {
            // ถ้ากำลังดาวน์โหลดอยู่ ให้โหลดต่อเบื้องหลัง แค่ซ่อนหน้าต่าง
            if (it.step is UpdateStep.Downloading) it.copy(dialogVisible = false)
            else it.copy(dialogVisible = false, step = UpdateStep.Idle)
        }
    }

    fun show() = _state.update { it.copy(dialogVisible = true) }
}

// ============================================================================
// UI
// ============================================================================

/**
 * วางไว้ที่ระดับบนสุดของแอป: เช็กอัปเดตตอนเปิด และแสดงหน้าต่างอัปเดตเมื่อจำเป็น
 */
@Composable
fun UpdateHost(viewModel: UpdateViewModel, openedFromNotification: Boolean) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        if (openedFromNotification) viewModel.openFromNotification() else viewModel.checkOnLaunch()
    }
    // กลับมาจากหน้าตั้งค่า "ติดตั้งแอปที่ไม่รู้จัก" → เช็กสิทธิ์อีกครั้ง
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.recheckPermission() }
    }

    if (!state.dialogVisible) return
    val step = state.step
    val c = AppTheme.colors

    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        containerColor = c.surface,
        title = {
            Text(
                when (step) {
                    is UpdateStep.Checking -> "กำลังตรวจหาอัปเดต"
                    is UpdateStep.UpToDate -> "เป็นเวอร์ชันล่าสุดแล้ว"
                    is UpdateStep.Available -> "มีเวอร์ชันใหม่ ${step.update.versionName}"
                    is UpdateStep.Downloading -> "กำลังดาวน์โหลด ${step.update.versionName}"
                    is UpdateStep.NeedsPermission -> "อนุญาตให้ติดตั้งอัปเดต"
                    is UpdateStep.ReadyToInstall -> "พร้อมติดตั้ง ${step.update.versionName}"
                    is UpdateStep.Failed -> "อัปเดตไม่สำเร็จ"
                    UpdateStep.Idle -> ""
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (step) {
                    is UpdateStep.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.ink, trackColor = c.line)
                    is UpdateStep.UpToDate -> Text("ตอนนี้ใช้เวอร์ชัน ${state.currentVersion}")
                    is UpdateStep.Available -> {
                        Hint("ตอนนี้ใช้ ${state.currentVersion}${sizeText(step.update.sizeBytes)}")
                        if (step.update.notes.isNotBlank()) {
                            Text(
                                step.update.notes,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                            )
                        }
                        Hint("ข้อมูลตารางและหุ้นที่ติดตามยังอยู่ครบหลังอัปเดต")
                    }
                    is UpdateStep.Downloading -> {
                        LinearProgressIndicator(
                            progress = { step.progress },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = c.ink,
                            trackColor = c.line
                        )
                        Hint("${(step.progress * 100).toInt()}%  ปิดหน้าต่างนี้ได้ จะโหลดต่อเบื้องหลัง")
                    }
                    is UpdateStep.NeedsPermission -> Text(
                        "Android ต้องให้คุณอนุญาตแอปนี้ติดตั้งอัปเดตเองก่อน แตะ \"เปิดการตั้งค่า\" แล้วเปิดสวิตช์ \"อนุญาตจากแหล่งที่มานี้\" จากนั้นกลับมาที่แอป"
                    )
                    is UpdateStep.ReadyToInstall -> Text("แตะ \"ติดตั้ง\" แล้วกด \"อัปเดต\" ในหน้าถัดไปของ Android")
                    is UpdateStep.Failed -> Text(step.message)
                    UpdateStep.Idle -> Unit
                }
            }
        },
        confirmButton = {
            when (step) {
                is UpdateStep.Available -> TextButton(onClick = { viewModel.startDownload(step.update) }) { Text("อัปเดตเลย") }
                is UpdateStep.NeedsPermission -> TextButton(onClick = { viewModel.openPermissionSettings(context) }) { Text("เปิดการตั้งค่า") }
                is UpdateStep.ReadyToInstall -> TextButton(onClick = { viewModel.install(context, step.file) }) { Text("ติดตั้ง") }
                is UpdateStep.Failed -> step.update?.let { u ->
                    TextButton(onClick = { viewModel.startDownload(u) }) { Text("ลองอีกครั้ง") }
                } ?: TextButton(onClick = viewModel::checkManually) { Text("ลองอีกครั้ง") }
                else -> TextButton(onClick = viewModel::dismiss) { Text("ปิด") }
            }
        },
        dismissButton = {
            when (step) {
                is UpdateStep.Available, is UpdateStep.NeedsPermission, is UpdateStep.ReadyToInstall, is UpdateStep.Failed ->
                    TextButton(onClick = viewModel::dismiss) { Text("ไว้ทีหลัง") }
                is UpdateStep.Downloading -> TextButton(onClick = viewModel::dismiss) { Text("ซ่อน") }
                else -> Unit
            }
        }
    )
}

private fun sizeText(bytes: Long): String =
    if (bytes <= 0) "" else "  ขนาด %.1f MB".format(bytes / 1_048_576.0)

/** แถว "เวอร์ชันแอป" พร้อมปุ่มตรวจหาอัปเดต ใช้ในหน้ากิจวัตร */
@Composable
fun AppVersionRow(viewModel: UpdateViewModel) {
    val state by viewModel.state.collectAsState()
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("เวอร์ชันแอป", style = MaterialTheme.typography.bodyLarge)
            Hint(state.currentVersion)
        }
        if (state.step is UpdateStep.Downloading && !state.dialogVisible) {
            TextButton(onClick = viewModel::show) { Text("ดูการดาวน์โหลด") }
        } else {
            OutlinedButton(onClick = viewModel::checkManually, shape = MaterialTheme.shapes.small) { Text("ตรวจหาอัปเดต") }
        }
    }
}

/** ใช้ UpdateViewModel ตัวเดียวกันทั้งแอป (ผูกกับ Activity) */
@Composable
fun rememberAppUpdateViewModel(): UpdateViewModel {
    val activity = LocalContext.current as androidx.activity.ComponentActivity
    return hiltViewModel(activity)
}
