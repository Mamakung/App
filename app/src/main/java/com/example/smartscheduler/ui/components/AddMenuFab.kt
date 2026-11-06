package com.example.smartscheduler.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartscheduler.settings.FabStyle
import com.example.smartscheduler.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** หนึ่งตัวเลือกในเมนูปุ่ม + */
data class AddMenuAction(
    val label: String,
    val tint: @Composable () -> Color,
    val icon: @Composable (Color) -> Unit,
    val onClick: () -> Unit
)

private val FabSize = 56.dp
private val ItemSize = 48.dp
private val Edge = 20.dp
private const val HoldMillis = 400L

/**
 * ปุ่ม + มุมขวาล่าง กดทีเดียวหรือกดค้างแล้วกางเมนูไอคอนออกมา
 *  - แตะ: เปิด/ปิดเมนู แล้วแตะไอคอนที่ต้องการ
 *  - กดค้าง: มีวงวิ่งรอบปุ่ม ครบแล้วเมนูกาง ลากนิ้วไปที่ไอคอน (ขยาย + ขึ้นชื่อ) ปล่อยเพื่อเลือก
 *  - [style] RADIAL = วงโค้งรอบปุ่ม (ขึ้นบน → ซ้าย), VERTICAL = เรียงขึ้นไปเหนือปุ่ม
 * วางใน Box เต็มจอ (ใช้พื้นที่ทั้งหมดเป็นฉากมืดตอนเปิด)
 */
@Composable
fun BoxScope.AddMenuFab(style: FabStyle, actions: List<AddMenuAction>) {
    val c = AppTheme.colors
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf<Int?>(null) }
    var pressed by remember { mutableStateOf(false) }
    val hold = remember { Animatable(0f) }

    // ความคืบหน้าของแต่ละไอคอน: เปิดแบบเด้ง (ไล่ทีละอัน) ปิดเร็วกว่าเปิด
    val progress = remember(actions.size) { List(actions.size) { Animatable(0f) } }
    LaunchedEffect(expanded) {
        progress.forEachIndexed { i, a ->
            launch {
                if (expanded) {
                    delay(i * 45L)
                    a.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 420f))
                } else {
                    a.animateTo(0f, tween(160, easing = FastOutLinearInEasing))
                }
            }
        }
        if (!expanded) hovered = null
    }
    val open by animateFloatAsState(if (expanded) 1f else 0f, tween(if (expanded) 220 else 160), label = "open")
    val pressScale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(110), label = "press")

    BackHandler(enabled = expanded) { expanded = false }

    // ตำแหน่งจุดกึ่งกลางของแต่ละไอคอน เทียบกับกึ่งกลางปุ่ม +
    val targets: List<Pair<Dp, Dp>> = remember(style, actions.size) {
        val n = actions.size
        when (style) {
            FabStyle.RADIAL -> List(n) { i ->
                val r = 92f
                val deg = if (n == 1) 135.0 else 90.0 + 90.0 * i / (n - 1)
                val rad = Math.toRadians(deg)
                (r * cos(rad)).toFloat().dp to (-r * sin(rad)).toFloat().dp
            }
            FabStyle.VERTICAL -> List(n) { i -> 0.dp to -(FabSize / 2 + 16.dp + ItemSize / 2 + (ItemSize + 14.dp) * i) }
        }
    }

    // ฉากมืด
    if (open > 0f) {
        Box(
            Modifier
                .matchParentSize()
                .background(c.ground.copy(alpha = 0.72f * open))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { expanded = false }
                .semantics { contentDescription = "ปิดเมนู" }
        )
    }

    // ไอคอนตัวเลือก (วาดก่อนปุ่ม + ให้อยู่ใต้ปุ่มตอนหุบ)
    actions.forEachIndexed { i, action ->
        val p = progress[i].value
        if (p > 0.001f) {
            val (dx, dy) = targets[i]
            val isHot = hovered == i
            val hotScale by animateFloatAsState(if (isHot) 1.14f else 1f, spring(stiffness = 600f), label = "hot$i")
            val tint = action.tint()
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = Edge + (FabSize - ItemSize) / 2, bottom = Edge + (FabSize - ItemSize) / 2)
                    .offset { IntOffset((dx * p).roundToPx(), (dy * p).roundToPx()) }
                    .graphicsLayer {
                        val s = (0.4f + 0.6f * p) * hotScale
                        scaleX = s; scaleY = s
                        alpha = p.coerceIn(0f, 1f)
                    }
                    .size(ItemSize)
                    .clip(CircleShape)
                    .background(c.surface)
                    .border(if (isHot) 2.dp else 1.5.dp, if (isHot) c.ink else c.line, CircleShape)
                    .clickable(enabled = expanded) { expanded = false; action.onClick() }
                    .semantics { contentDescription = action.label; role = Role.Button },
                contentAlignment = Alignment.Center
            ) { action.icon(tint) }

            // ชื่อ: ขึ้นเฉพาะตอนลากนิ้วผ่าน (กดค้าง)
            if (isHot) {
                val labelModifier = when (style) {
                    FabStyle.VERTICAL -> Modifier.padding(end = Edge + FabSize + 16.dp, bottom = Edge + (FabSize - 34.dp) / 2)
                        .offset { IntOffset(0, dy.roundToPx()) }
                    FabStyle.RADIAL -> Modifier.padding(end = Edge + (FabSize - ItemSize) / 2, bottom = Edge + FabSize + 4.dp)
                        .offset { IntOffset(dx.roundToPx(), (dy - 6.dp).roundToPx()) }
                }
                Text(
                    action.label,
                    color = c.ground,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .then(labelModifier)
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.ink)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }

    // ปุ่ม +
    val fabCenterPx = with(density) { (FabSize / 2).toPx() }
    val hitRadius = with(density) { 36.dp.toPx() }
    fun itemAt(pos: Offset): Int? {
        targets.forEachIndexed { i, (dx, dy) ->
            val cx = fabCenterPx + with(density) { dx.toPx() }
            val cy = fabCenterPx + with(density) { dy.toPx() }
            if (hypot(pos.x - cx, pos.y - cy) < hitRadius) return i
        }
        return null
    }
    val corner by animateDpAsState(if (expanded) FabSize / 2 else 18.dp, tween(220), label = "corner")
    Box(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(Edge)
            .size(FabSize)
            .scale(pressScale)
    ) {
        // วงวิ่งรอบปุ่มตอนกดค้าง
        if (hold.value > 0f) {
            Canvas(Modifier.align(Alignment.Center).requiredSize(FabSize + 10.dp)) {
                drawArc(
                    color = c.ink, startAngle = -90f, sweepAngle = 360f * hold.value, useCenter = false,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(corner))
                .background(c.ink)
                .pointerInput(actions.size, style) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pressed = true
                        val wasOpen = expanded
                        val ring = scope.launch {
                            delay(90)
                            if (!wasOpen) hold.animateTo(1f, tween((HoldMillis - 90).toInt(), easing = LinearEasing))
                        }
                        // รอว่าปล่อยก่อนครบเวลากดค้างหรือไม่
                        val up = withTimeoutOrNull(HoldMillis) {
                            var ev = awaitPointerEvent()
                            while (ev.changes.none { it.changedToUp() }) ev = awaitPointerEvent()
                            ev
                        }
                        if (up != null || wasOpen) {
                            // แตะ: สลับเปิด/ปิด
                            if (up == null) { // ถือค้างตอนเมนูเปิดอยู่ รอปล่อย
                                var ev = awaitPointerEvent()
                                while (ev.changes.none { it.changedToUp() }) ev = awaitPointerEvent()
                            }
                            ring.cancel(); scope.launch { hold.snapTo(0f) }
                            pressed = false
                            expanded = !wasOpen
                        } else {
                            // กดค้าง: เปิดเมนู ลากไปเลือก ปล่อยเพื่อยืนยัน
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch { hold.snapTo(0f) }
                            expanded = true
                            var last = down.position
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: break
                                last = ch.position
                                val h = itemAt(last)
                                if (h != hovered) {
                                    if (h != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    hovered = h
                                }
                                if (ch.positionChange() != Offset.Zero) ch.consume()
                                if (ch.changedToUp()) break
                            }
                            pressed = false
                            val pick = itemAt(last)
                            hovered = null
                            if (pick != null) {
                                expanded = false
                                actions[pick].onClick()
                            }
                        }
                    }
                }
                .semantics {
                    contentDescription = "เพิ่ม"
                    role = Role.Button
                    stateDescription = if (expanded) "เมนูเปิดอยู่" else "เมนูปิดอยู่"
                    onClick(label = if (expanded) "ปิดเมนู" else "เปิดเมนู") { expanded = !expanded; true }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Add, contentDescription = null, tint = c.ground,
                modifier = Modifier.size(28.dp).rotate(135f * open)
            )
        }
    }
}

// ============================================================================
// ไอคอนของเมนู
// ============================================================================

/** ปฏิทินที่มีเลขวันที่อยู่ข้างใน (เพิ่มลงวันที่เลือก) */
@Composable
fun DayNumberIcon(day: Int, tint: Color) {
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.CalendarToday, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
        Text(
            "$day",
            color = tint,
            fontWeight = FontWeight.SemiBold,
            fontSize = if (day >= 10) 10.sp else 11.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/** ปฏิทินซ้อน 2 ใบ (เพิ่มหลายวัน) */
val StackedCalendars: ImageVector by lazy {
    fun nodes(d: String) = PathParser().parsePathString(d).toNodes()
    val stroke = SolidColor(Color.Black)
    ImageVector.Builder("StackedCalendars", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            nodes("M7.5 8V5.5a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-2M7.5 7.5h13"),
            fill = null, stroke = stroke, strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
        )
        .addPath(
            nodes("M5 8h9.5a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2zM3 12h13.5"),
            fill = null, stroke = stroke, strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
        )
        .addPath(
            nodes("M5.5 14.2h2.4v2.2h-2.4zM8.6 14.2h2.4v2.2h-2.4zM11.7 14.2h2.4v2.2h-2.4zM5.5 17.4h2.4v2.2h-2.4zM8.6 17.4h2.4v2.2h-2.4z"),
            fill = SolidColor(Color.Black)
        )
        .build()
}

/** ภาพย่อของแต่ละรูปแบบ ใช้ในหน้าตั้งค่า */
@Composable
fun FabStylePreview(style: FabStyle, accent: Color, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Canvas(modifier.size(72.dp)) {
        val fab = Offset(size.width - 16.dp.toPx(), size.height - 16.dp.toPx())
        val dots = when (style) {
            FabStyle.RADIAL -> listOf(Offset(fab.x, fab.y - 36.dp.toPx()), Offset(fab.x - 36.dp.toPx(), fab.y))
            FabStyle.VERTICAL -> listOf(Offset(fab.x, fab.y - 26.dp.toPx()), Offset(fab.x, fab.y - 48.dp.toPx()))
        }
        if (style == FabStyle.RADIAL) {
            drawArc(
                color = c.line, startAngle = 180f, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(fab.x - 36.dp.toPx(), fab.y - 36.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(72.dp.toPx(), 72.dp.toPx()),
                style = Stroke(1.5.dp.toPx())
            )
        }
        dots.forEach { drawCircle(accent, radius = 8.dp.toPx(), center = it) }
        drawRoundRect(
            c.ink, topLeft = Offset(fab.x - 11.dp.toPx(), fab.y - 11.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(22.dp.toPx(), 22.dp.toPx()),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx())
        )
    }
}
