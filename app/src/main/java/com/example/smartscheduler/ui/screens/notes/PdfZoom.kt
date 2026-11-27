package com.example.smartscheduler.ui.screens.notes

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * ซูมของหน้าอ่าน PDF: จอ = offset + scale × เนื้อหา (ขยายจากมุมซ้ายบน)
 * ซูมเข้าตรงกลางระหว่างนิ้ว, ลากได้ทุกทิศ, ปล่อยแล้วลื่นต่อ
 * [onOverflowY] = ลากขึ้นลงเกินขอบ ส่งต่อให้รายการเลื่อน (โหมดเลื่อนแนวตั้ง) หน่วยเป็นพิกเซลของเนื้อหา
 */
internal class PdfZoomState(private val onOverflowY: ((Float) -> Unit)? = null) {
    var scale by mutableFloatStateOf(1f)
    var ox by mutableFloatStateOf(0f)
    var oy by mutableFloatStateOf(0f)
    /** ขนาดตอนหยุดซูม ใช้เลือกความคมของภาพ (ไม่วาดใหม่ระหว่างขยับนิ้ว) */
    var settled by mutableFloatStateOf(1f)
    var w = 0f
    var h = 0f
    var job: Job? = null

    val zoomed get() = scale > 1.01f
    private fun minX(s: Float = scale) = -(s - 1f) * w
    private fun minY(s: Float = scale) = -(s - 1f) * h

    fun zoomBy(z: Float, c: Offset) {
        val ns = (scale * z).coerceIn(1f, MAX)
        val k = ns / scale
        ox = (c.x - (c.x - ox) * k).coerceIn(minX(ns), 0f)
        oy = (c.y - (c.y - oy) * k).coerceIn(minY(ns), 0f)
        scale = ns
    }

    /** ขยับ คืนส่วนที่ใช้ไม่หมดเพราะชนขอบ */
    fun panBy(d: Offset): Offset {
        val nx = ox + d.x
        ox = nx.coerceIn(minX(), 0f)
        val ny = oy + d.y
        oy = ny.coerceIn(minY(), 0f)
        var restY = ny - oy
        if (restY != 0f && onOverflowY != null) { onOverflowY.invoke(-restY / scale); restY = 0f }
        return Offset(nx - ox, restY)
    }

    /** ชนขอบซ้าย/ขวาแล้ว และยังลากออกไปทางนั้น */
    fun atEdge(dx: Float) = (dx < 0 && ox <= minX() + 0.5f) || (dx > 0 && ox >= -0.5f)

    fun reset() { job?.cancel(); scale = 1f; ox = 0f; oy = 0f; settled = 1f }

    /** แตะ 2 ครั้ง: ขยายตรงจุดที่แตะ / กลับขนาดปกติ แบบนุ่มๆ */
    fun toggleAt(p: Offset, scope: CoroutineScope) {
        job?.cancel()
        val s0 = scale; val x0 = ox; val y0 = oy
        val target = if (zoomed) 1f else DOUBLE_TAP
        val k = target / s0
        val ex = (p.x - (p.x - x0) * k).coerceIn(minX(target), 0f)
        val ey = (p.y - (p.y - y0) * k).coerceIn(minY(target), 0f)
        job = scope.launch {
            animate(0f, 1f, animationSpec = tween(260)) { v, _ ->
                scale = s0 + (target - s0) * v; ox = x0 + (ex - x0) * v; oy = y0 + (ey - y0) * v
            }
            settled = scale
        }
    }

    companion object {
        const val MAX = 4f
        const val DOUBLE_TAP = 2.5f
    }
}

internal fun Modifier.pdfZoomLayer(z: PdfZoomState) = graphicsLayer(
    scaleX = z.scale, scaleY = z.scale, translationX = z.ox, translationY = z.oy,
    transformOrigin = TransformOrigin(0f, 0f)
)

/**
 * ท่าทางซูม: 2 นิ้ว = ซูม+ลาก, 1 นิ้วตอนซูมอยู่ = ลาก (ปล่อยแล้วลื่นต่อ), ไม่ได้ซูม 1 นิ้ว = ปล่อยให้รายการ/การพลิกหน้าทำงาน
 * [passEdges] = โหมดพลิกหน้า: ลากชนขอบซ้าย/ขวาแล้ว ปล่อยให้พลิกไปหน้าถัดไป
 */
internal fun Modifier.pdfZoomGestures(z: PdfZoomState, scope: CoroutineScope, passEdges: Boolean, pass: PointerEventPass) =
    pointerInput(z, passEdges) {
        val decay = exponentialDecay<Float>(frictionMultiplier = 1.6f)
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
            z.job?.cancel()
            z.w = size.width.toFloat(); z.h = size.height.toFloat()
            val vt = VelocityTracker()
            vt.addPosition(down.uptimeMillis, down.position)
            var pinched = false
            var panned = false
            do {
                val ev = awaitPointerEvent(pass)
                val pressed = ev.changes.filter { it.pressed }
                if (pressed.size >= 2) {
                    pinched = true
                    z.zoomBy(ev.calculateZoom(), ev.calculateCentroid(useCurrent = true))
                    z.panBy(ev.calculatePan())
                    ev.changes.forEach { it.consume() }
                } else if (pressed.size == 1 && z.zoomed) {
                    val ch = pressed[0]
                    vt.addPosition(ch.uptimeMillis, ch.position)
                    val d = ch.positionChange()
                    if (passEdges && !panned && abs(d.x) > abs(d.y) && z.atEdge(d.x)) {
                        // ไม่กิน: ให้พลิกหน้าต่อ
                    } else if (d != Offset.Zero) {
                        panned = true
                        z.panBy(d); ch.consume()
                    }
                }
            } while (ev.changes.any { it.pressed })

            if (z.scale < 1.03f) { z.reset(); return@awaitEachGesture }
            z.settled = z.scale
            if (pinched || !panned) return@awaitEachGesture
            val v = vt.calculateVelocity()
            z.job = scope.launch {
                launch {
                    var last = 0f
                    AnimationState(0f, v.x).animateDecay(decay) {
                        val d = value - last; last = value
                        if (z.panBy(Offset(d, 0f)).x != 0f) cancelAnimation()
                    }
                }
                launch {
                    var last = 0f
                    AnimationState(0f, v.y).animateDecay(decay) {
                        val d = value - last; last = value
                        if (z.panBy(Offset(0f, d)).y != 0f) cancelAnimation()
                    }
                }
            }
        }
    }
