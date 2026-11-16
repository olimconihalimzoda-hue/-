package com.evdiag.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val PITCH = 0.62f // наклон камеры вниз, радианы

private class Box3(val cx: Float, val cz: Float, val h: Float, val color: Color)

private fun shade(c: Color, f: Float): Color = Color(
    (c.red * f).coerceIn(0f, 1f),
    (c.green * f).coerceIn(0f, 1f),
    (c.blue * f).coerceIn(0f, 1f),
    1f,
)

/**
 * Псевдо-3D вид батарейного пака: ячейки стоят сеткой, высота и цвет столбика — напряжение.
 * Рисуется на Canvas без внешних библиотек. Вращение — жестом по горизонтали; пока пальцем не трогали, вид медленно вращается.
 */
@Composable
fun Pack3D(cells: List<Double>, modifier: Modifier = Modifier) {
    if (cells.isEmpty()) return
    var yaw by remember { mutableFloatStateOf(0.6f) }
    var auto by remember { mutableStateOf(true) }

    LaunchedEffect(auto) {
        var last = 0L
        while (auto) {
            withFrameNanos { t ->
                if (last != 0L) yaw += (t - last) / 1_000_000_000f * 0.35f
                last = t
            }
        }
    }

    val ok = MaterialTheme.colorScheme.primary
    val plate = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val n = cells.size
    val median = cells.sorted()[n / 2]
    val mn = cells.min()
    val mx = cells.max()

    Canvas(
        modifier
            .fillMaxWidth()
            .height(240.dp)
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = { auto = false }) { change, drag ->
                    change.consume()
                    yaw += drag.x * 0.01f
                }
            },
    ) {
        val cols = max(1, ceil(sqrt(n.toDouble())).toInt())
        val rows = max(1, ceil(n.toDouble() / cols).toInt())
        val lo = mn - 0.02
        val span = ((mx + 0.005) - lo).coerceAtLeast(0.001)
        val maxH = max(cols, rows) * 0.9f + 1.5f

        val boxes = ArrayList<Box3>(n)
        for (i in 0 until n) {
            val r = i / cols
            val c = i % cols
            val v = cells[i]
            val frac = (((v - lo) / span).toFloat()).coerceIn(0.1f, 1f)
            val dev = abs(v - median) * 1000.0
            val color = when {
                dev <= 30.0 -> ok
                dev <= 80.0 -> Color(0xFFE9A81C)
                else -> Color(0xFFE0504F)
            }
            boxes += Box3(c - (cols - 1) / 2f, r - (rows - 1) / 2f, 0.3f + (maxH - 0.3f) * frac, color)
        }

        val a = yaw
        val ca = cos(a)
        val sa = sin(a)
        val sp = sin(PITCH)
        val cp = cos(PITCH)
        val extent = max(cols, rows) * 1.45f + maxH * cp
        val scale = min(size.width, size.height) / extent * 1.05f
        val ox = size.width / 2f
        val oy = size.height * 0.72f

        // точка (x, y, z) -> экран; z' > 0 — ближе к зрителю
        fun proj(x: Float, y: Float, z: Float): Offset {
            val xr = x * ca - z * sa
            val zr = x * sa + z * ca
            return Offset(ox + xr * scale, oy - (y * cp - zr * sp) * scale)
        }

        fun face(d: DrawScope, pts: List<Offset>, color: Color) {
            val path = Path()
            path.moveTo(pts[0].x, pts[0].y)
            for (k in 1 until pts.size) path.lineTo(pts[k].x, pts[k].y)
            path.close()
            d.drawPath(path, color)
        }

        // подложка
        val hx = cols / 2f + 0.3f
        val hz = rows / 2f + 0.3f
        face(this, listOf(proj(-hx, 0f, -hz), proj(hx, 0f, -hz), proj(hx, 0f, hz), proj(-hx, 0f, hz)), plate)

        // дальние столбики рисуем первыми
        val order = boxes.sortedBy { it.cx * sa + it.cz * ca }
        val half = 0.4f
        for (b in order) {
            val x0 = b.cx - half
            val x1 = b.cx + half
            val z0 = b.cz - half
            val z1 = b.cz + half
            // боковые грани: нормаль (nx, nz); видна, если повернута к зрителю
            val sides = listOf(
                Triple(0f, 1f, listOf(proj(x0, 0f, z1), proj(x1, 0f, z1), proj(x1, b.h, z1), proj(x0, b.h, z1))),
                Triple(0f, -1f, listOf(proj(x0, 0f, z0), proj(x1, 0f, z0), proj(x1, b.h, z0), proj(x0, b.h, z0))),
                Triple(1f, 0f, listOf(proj(x1, 0f, z0), proj(x1, 0f, z1), proj(x1, b.h, z1), proj(x1, b.h, z0))),
                Triple(-1f, 0f, listOf(proj(x0, 0f, z0), proj(x0, 0f, z1), proj(x0, b.h, z1), proj(x0, b.h, z0))),
            )
            for ((nx, nz, pts) in sides) {
                val nzr = nx * sa + nz * ca
                if (nzr > 0f) face(this, pts, shade(b.color, 0.55f + 0.3f * nzr))
            }
            face(
                this,
                listOf(proj(x0, b.h, z0), proj(x1, b.h, z0), proj(x1, b.h, z1), proj(x0, b.h, z1)),
                shade(b.color, 1.08f),
            )
        }
    }
}
