package com.example.smartscheduler.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.example.smartscheduler.ui.theme.AppTheme
import com.example.smartscheduler.ui.theme.TaskPalette

/** สีสำเร็จรูป: ขาว เทา ดำ + 11 สี (เฉดตามโหมดตอนเปิด) */
private fun quickColors(dark: Boolean): List<Pair<String, Int>> =
    listOf(
        "ขาว" to 0xFFFFFFFF.toInt(), "เทาอ่อน" to 0xFFC9CFCC.toInt(), "เทา" to 0xFF8A9490.toInt(),
        "เทาเข้ม" to 0xFF3C3D44.toInt(), "ดำ" to 0xFF000000.toInt()
    ) + TaskPalette.swatches.map { sw -> sw.label to (if (dark) sw.dark.first else sw.light.first).toArgb() }

/**
 * แถวสีแบบย่อ: วงสีปัจจุบัน + ชื่อ แตะแล้วเปิดแผ่น "เลือกสี"
 * [selected] = คีย์สี (null = ใช้ค่าเริ่มต้น [defaultColor] ซึ่งชื่อ [defaultLabel])
 */
@Composable
fun ColorField(
    title: String,
    selected: String?,
    defaultColor: Color,
    defaultLabel: String? = null,
    icon: ImageVector? = null,
    onSelect: (String?) -> Unit
) {
    val c = AppTheme.colors
    var open by remember { mutableStateOf(false) }
    val shown = TaskPalette.resolve(selected, c.dark)?.first ?: defaultColor
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button) { open = true }
            .semantics { contentDescription = "$title เปลี่ยนสี" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(shown).border(1.dp, c.line, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = readableOn(shown), modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (selected == null && defaultLabel != null) defaultLabel else TaskPalette.hexKey(shown.toArgb()),
                style = MaterialTheme.typography.labelMedium, color = c.muted
            )
        }
        if (selected != null && defaultLabel != null) {
            TextButton(onClick = { onSelect(null) }) { Text("ค่าเริ่มต้น") }
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = c.muted)
    }
    if (open) {
        ColorPickerSheet(
            initial = shown.toArgb(),
            onPick = { onSelect(it); open = false },
            onDismiss = { open = false }
        )
    }
}

private fun readableOn(color: Color): Color =
    if (ColorUtils.calculateLuminance(color.toArgb()) > 0.45) Color(0xFF14201C) else Color.White

/**
 * แผ่น "เลือกสี": รหัสสี + วงสำเร็จรูป + กล่องลากเลือก (ความสด/ความสว่าง) + แถบโทนสี
 * คืนค่าเป็นรหัส "#RRGGBB"
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ColorPickerSheet(initial: Int, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    val start = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
    var hexText by remember { mutableStateOf(TaskPalette.hexKey(initial)) }
    var editingHex by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    fun setColor(color: Int) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        // สีเทา/ขาว/ดำ ไม่มีโทน: คงโทนเดิมไว้ ไม่ให้แถบกระโดด
        if (hsv[1] > 0.01f && hsv[2] > 0.01f) hue = hsv[0]
        sat = hsv[1]; value = hsv[2]
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            Text("เลือกสี", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            // รหัสสี
            OutlinedTextField(
                value = if (editingHex) hexText else TaskPalette.hexKey(argb),
                onValueChange = { t ->
                    editingHex = true
                    val clean = ("#" + t.filter { it.isLetterOrDigit() }.uppercase()).take(7)
                    hexText = clean
                    TaskPalette.hexOf(clean)?.let { setColor(it) }
                },
                singleLine = true,
                leadingIcon = { Box(Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(Color(argb)).border(1.dp, c.line, RoundedCornerShape(7.dp))) },
                label = { Text("รหัสสี") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { editingHex = false; focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            // วงสำเร็จรูป
            FlowRow(
                Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                quickColors(c.dark).forEach { (label, q) ->
                    val isSel = (q and 0xFFFFFF) == (argb and 0xFFFFFF)
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .border(2.dp, if (isSel) c.ink else Color.Transparent, CircleShape)
                            .selectable(selected = isSel, role = Role.RadioButton) { editingHex = false; setColor(q) }
                            .semantics { contentDescription = "สี$label" },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(Color(q)).border(1.dp, c.line, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSel) Icon(Icons.Filled.Check, contentDescription = null, tint = readableOn(Color(q)), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // กล่องสี่เหลี่ยม: แนวนอน = ความสด, แนวตั้ง = ความสว่าง
            val pure = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .semantics { contentDescription = "กล่องเลือกความสดและความสว่าง" }
                    .pointerInput(Unit) {
                        fun pick(o: Offset) {
                            editingHex = false
                            sat = (o.x / size.width).coerceIn(0f, 1f)
                            value = 1f - (o.y / size.height).coerceIn(0f, 1f)
                        }
                        detectTapGestures { pick(it) }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(onDragStart = { o ->
                            editingHex = false
                            sat = (o.x / size.width).coerceIn(0f, 1f)
                            value = 1f - (o.y / size.height).coerceIn(0f, 1f)
                        }) { ch, _ ->
                            ch.consume()
                            sat = (ch.position.x / size.width).coerceIn(0f, 1f)
                            value = 1f - (ch.position.y / size.height).coerceIn(0f, 1f)
                        }
                    }
            ) {
                drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val p = Offset(sat * size.width, (1f - value) * size.height)
                drawCircle(Color(argb), radius = 12.dp.toPx(), center = p)
                drawCircle(Color.White, radius = 13.dp.toPx(), center = p, style = Stroke(3.dp.toPx()))
            }
            Spacer(Modifier.height(16.dp))

            // แถบโทนสี
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .semantics { contentDescription = "แถบโทนสี" }
                    .pointerInput(Unit) {
                        detectTapGestures { o -> editingHex = false; hue = (o.x / size.width).coerceIn(0f, 1f) * 360f }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(onDragStart = { o -> editingHex = false; hue = (o.x / size.width).coerceIn(0f, 1f) * 360f }) { ch, _ ->
                            ch.consume()
                            hue = (ch.position.x / size.width).coerceIn(0f, 1f) * 360f
                        }
                    }
            ) {
                val barH = 20.dp.toPx()
                val top = (size.height - barH) / 2
                drawRoundRect(
                    Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }),
                    topLeft = Offset(0f, top), size = androidx.compose.ui.geometry.Size(size.width, barH),
                    cornerRadius = CornerRadius(barH / 2)
                )
                val x = hue / 360f * size.width
                drawCircle(pure, radius = 12.dp.toPx(), center = Offset(x, size.height / 2))
                drawCircle(Color.White, radius = 13.dp.toPx(), center = Offset(x, size.height / 2), style = Stroke(3.dp.toPx()))
            }

            // ตัวอย่างบนพื้นแอป / คำเตือนสีกลืนพื้น
            val contrast = ColorUtils.calculateContrast(argb, c.ground.toArgb())
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (contrast < 1.6) {
                    Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = c.task, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (c.dark) "สีนี้อาจมองไม่ชัดในโหมดมืด" else "สีนี้อาจมองไม่ชัดในโหมดสว่าง",
                        style = MaterialTheme.typography.bodySmall, color = c.task
                    )
                } else {
                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(c.ground), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(Color(argb)))
                    }
                    Spacer(Modifier.width(8.dp))
                    Hint("ตัวอย่างบนพื้นแอป")
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("ยกเลิก") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onPick(TaskPalette.hexKey(argb)) }) { Text("ใช้สีนี้") }
            }
        }
    }
}
