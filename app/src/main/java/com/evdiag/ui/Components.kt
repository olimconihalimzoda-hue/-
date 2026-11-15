package com.evdiag.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evdiag.domain.Level
import com.evdiag.domain.VerdictResult
import kotlin.math.abs

fun levelColor(l: Level): Color = when (l) {
    Level.GREEN -> Color(0xFF1FB67A)
    Level.YELLOW -> Color(0xFFE9A81C)
    Level.RED -> Color(0xFFE0504F)
    Level.GRAY -> Color(0xFF7F929B)
}

@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
fun HeaderBanner(title: String, subtitle: String) {
    val dark = Color(0xFF062B22)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)))
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = dark, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = dark.copy(alpha = 0.8f), fontSize = 13.sp)
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
fun InfoText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun Pill(text: String, color: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun TileGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in items.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((l, v) in row) StatTile(l, v, Modifier.weight(1f))
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun ActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
) {
    if (filled) {
        Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = RoundedCornerShape(14.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = RoundedCornerShape(14.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text)
        }
    }
}

/** Круговая шкала заряда. */
@Composable
fun BatteryGauge(percent: Double?, modifier: Modifier = Modifier.size(180.dp), caption: String = "заряд") {
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val main = when {
        percent == null -> MaterialTheme.colorScheme.outline
        percent < 20.0 -> Color(0xFFE0504F)
        percent < 40.0 -> Color(0xFFE9A81C)
        else -> MaterialTheme.colorScheme.primary
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val stroke = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round)
            drawArc(track, 135f, 270f, false, style = stroke)
            if (percent != null) {
                val sweep = 270f * (percent.coerceIn(0.0, 100.0) / 100.0).toFloat()
                drawArc(main, 135f, sweep, false, style = stroke)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (percent == null) "—" else "%.0f%%".format(percent),
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Столбики напряжений ячеек: цвет показывает отклонение от медианы. */
@Composable
fun CellBars(cells: List<Double>, modifier: Modifier = Modifier) {
    if (cells.isEmpty()) return
    val median = cells.sorted()[cells.size / 2]
    val mn = cells.min()
    val mx = cells.max()
    val okColor = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(110.dp)) {
        val n = cells.size
        val gap = if (n > 60) 1.dp.toPx() else 3.dp.toPx()
        val bw = (size.width - gap * (n - 1)) / n
        val lo = mn - 0.02
        val span = ((mx + 0.005) - lo).coerceAtLeast(0.001)
        cells.forEachIndexed { i, v ->
            val h = (((v - lo) / span).toFloat()).coerceIn(0.05f, 1f) * size.height
            val dev = abs(v - median) * 1000.0
            val color = when {
                dev <= 30.0 -> okColor
                dev <= 80.0 -> Color(0xFFE9A81C)
                else -> Color(0xFFE0504F)
            }
            drawRoundRect(color, Offset(i * (bw + gap), size.height - h), Size(bw, h), CornerRadius(2.dp.toPx()))
        }
    }
}

/** Светофор: три «огня» и расшифровка. */
@Composable
fun VerdictCard(v: VerdictResult) {
    val c = levelColor(v.level)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = c.copy(alpha = 0.16f)),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (lv in listOf(Level.RED, Level.YELLOW, Level.GREEN)) {
                        val active = lv == v.level
                        Box(
                            Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(levelColor(lv).copy(alpha = if (active) 1f else 0.2f)),
                        )
                    }
                }
                Column {
                    Text("Итог проверки", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v.title, style = MaterialTheme.typography.headlineSmall, color = c, fontWeight = FontWeight.Bold)
                }
            }
            for (r in v.reasons) Text("• $r", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** График живого параметра. */
@Composable
fun LineChart(data: List<Float>) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(80.dp)) {
        if (data.size < 2) return@Canvas
        val mn = data.min()
        val mx = data.max()
        val span = if (mx - mn < 1e-6f) 1f else mx - mn
        val path = Path()
        data.forEachIndexed { i, v ->
            val x = size.width * i / (data.size - 1)
            val y = size.height - (v - mn) / span * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3f, cap = StrokeCap.Round))
    }
}
