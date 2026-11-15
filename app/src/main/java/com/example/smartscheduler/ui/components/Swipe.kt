package com.example.smartscheduler.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * ปัดซ้าย/ขวา: [onSwipe] (true = ปัดซ้าย = ไปข้างหน้า)
 * วางไว้นอก verticalScroll / ตัวจับกดค้าง: ถ้าข้างในใช้นิ้วไปก่อน (เลื่อนขึ้นลง, ลากเลือกวัน) จะไม่นับเป็นปัด
 * เมื่อจับได้ว่าเป็นปัดแนวนอน จะกินการแตะไว้ ไม่ให้แตะโดนของข้างใต้
 */
fun Modifier.horizontalSwipe(enabled: Boolean = true, onSwipe: (forward: Boolean) -> Unit): Modifier = composed {
    val cb by rememberUpdatedState(onSwipe)
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        val slop = viewConfiguration.touchSlop
        val distance = 56.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var dx = 0f
            var dy = 0f
            var claimed = false
            while (true) {
                val ev = awaitPointerEvent()
                if (ev.changes.size > 1) break
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!claimed && ch.isConsumed) break
                if (!ch.pressed) {
                    if (claimed && abs(dx) > distance) cb(dx < 0)
                    break
                }
                val d = ch.positionChange()
                dx += d.x; dy += d.y
                if (!claimed) {
                    if (abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.5f) claimed = true
                    else if (abs(dy) > slop * 2) break
                }
                if (claimed) ch.consume()
            }
        }
    }
}
