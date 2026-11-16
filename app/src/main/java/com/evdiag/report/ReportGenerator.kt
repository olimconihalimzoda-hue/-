package com.evdiag.report

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.evdiag.domain.Level
import com.evdiag.domain.VerdictResult
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class ReportData(
    val reportId: String,
    val ts: Long,
    val inspector: String,
    val company: String,
    val phone: String,
    val logoPath: String?,
    val vehicle: String,
    val profileVerified: Boolean,
    val vin: String?,
    val mileage: String,
    val client: String,
    val verdict: VerdictResult,
    val soh: Double?,
    val indicators: List<Pair<String, String>>,
    val cells: List<Double>,
    val dtcLines: List<String>,
    val demo: Boolean = false,
)

object ReportGenerator {
    private const val W = 595
    private const val H = 842
    private const val M = 36f
    private const val BOTTOM = 56f

    private fun levelColor(l: Level): Int = when (l) {
        Level.GREEN -> Color.rgb(31, 182, 122)
        Level.YELLOW -> Color.rgb(233, 168, 28)
        Level.RED -> Color.rgb(224, 80, 79)
        Level.GRAY -> Color.rgb(127, 146, 155)
    }

    private fun paint(size: Float, color: Int, bold: Boolean = false): Paint = Paint().apply {
        isAntiAlias = true
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun wrap(text: String, p: Paint, width: Float): List<String> {
        val out = mutableListOf<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            val n = p.breakText(rest, true, width, null).coerceAtLeast(1)
            var cut = n
            if (n < rest.length) {
                val sp = rest.lastIndexOf(' ', n)
                if (sp > 0) cut = sp
            }
            out += rest.substring(0, cut).trimEnd()
            rest = rest.substring(cut).trimStart()
        }
        return out
    }

    private fun sha8(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)

    private fun drawQr(c: Canvas, text: String, x: Float, y: Float, size: Float) {
        try {
            val hints = mapOf<EncodeHintType, Any>(EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8")
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
            val n = m.width
            val cell = size / n
            val white = Paint().apply { color = Color.WHITE }
            val black = Paint().apply { color = Color.BLACK }
            c.drawRect(x - 4f, y - 4f, x + size + 4f, y + size + 4f, white)
            for (i in 0 until n) {
                for (j in 0 until m.height) {
                    if (m.get(i, j)) c.drawRect(x + i * cell, y + j * cell, x + (i + 1) * cell, y + (j + 1) * cell, black)
                }
            }
        } catch (e: Exception) {
            // QR не критичен для отчёта
        }
    }

    fun create(ctx: Context, d: ReportData): File {
        val doc = PdfDocument()
        val pw = W - 2 * M
        val dark = Color.rgb(14, 30, 37)
        val gray = Color.rgb(100, 120, 130)

        val title = paint(20f, dark, true)
        val head = paint(14f, dark, true)
        val body = paint(11f, dark)
        val small = paint(9f, gray)
        val label = paint(9f, gray)
        val value = paint(12f, dark, true)

        var page: PdfDocument.Page? = null
        var pageNo = 0
        var y = 0f
        val dateStr = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(d.ts))

        fun startPage() {
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M
        }

        fun endPage() {
            val c = page!!.canvas
            c.drawLine(M, H - 40f, W - M, H - 40f, Paint().apply { color = Color.rgb(210, 220, 224); strokeWidth = 1f })
            c.drawText("Отчёт носит справочный характер и не заменяет диагностику в сервисе.", M, H - 26f, small)
            c.drawText("${d.reportId} · стр. $pageNo", W - M - 120f, H - 14f, small)
            doc.finishPage(page!!)
        }

        fun ensure(h: Float) {
            if (y + h > H - BOTTOM) {
                endPage()
                startPage()
            }
        }

        fun text(t: String, p: Paint, gap: Float = 15f) {
            for (ln in wrap(t, p, pw)) {
                ensure(gap)
                page!!.canvas.drawText(ln, M, y + p.textSize, p)
                y += gap
            }
        }

        fun section(name: String) {
            ensure(34f)
            y += 10f
            page!!.canvas.drawText(name, M, y + head.textSize, head)
            y += 24f
        }

        startPage()
        val c0 = page!!.canvas

        // ---- шапка ----
        c0.drawRect(0f, 0f, W.toFloat(), 96f, Paint().apply { color = Color.rgb(11, 31, 42) })
        var tx = M
        val logo = d.logoPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        if (logo != null) {
            c0.drawBitmap(logo, null, RectF(M, 18f, M + 60f, 78f), Paint().apply { isFilterBitmap = true })
            tx = M + 74f
        }
        val brand = d.company.ifBlank { d.inspector }.ifBlank { "EV Diag" }
        c0.drawText(brand, tx, 42f, paint(18f, Color.WHITE, true))
        var by = 60f
        if (d.company.isNotBlank() && d.inspector.isNotBlank()) {
            c0.drawText(d.inspector, tx, by, paint(11f, Color.rgb(190, 210, 218)))
            by += 15f
        }
        if (d.phone.isNotBlank()) c0.drawText(d.phone, tx, by, paint(11f, Color.rgb(190, 210, 218)))
        c0.drawText("EV Diag", W - M - 52f, 30f, paint(11f, Color.rgb(46, 230, 166), true))
        y = 96f + 22f

        // ---- заголовок и QR ----
        val qrText = "EVDIAG|${d.reportId}|${d.vin ?: "-"}|$dateStr|${d.verdict.level.name}|SOH=${d.soh?.let { "%.0f".format(Locale.US, it) } ?: "-"}"
        val qrFull = qrText + "|" + sha8(qrText + "evdiag")
        val qrSize = 74f
        drawQr(c0, qrFull, W - M - qrSize, y, qrSize)
        page!!.canvas.drawText("Проверка электромобиля", M, y + 20f, title)
        page!!.canvas.drawText("Отчёт № ${d.reportId} от $dateStr", M, y + 40f, paint(11f, gray))
        page!!.canvas.drawText("QR содержит данные отчёта и контрольную сумму.", M, y + 58f, small)
        y += qrSize + 18f

        // ---- данные автомобиля ----
        fun row(k: String, v: String) {
            if (v.isBlank()) return
            ensure(16f)
            page!!.canvas.drawText(k, M, y + 10f, label)
            page!!.canvas.drawText(v, M + 110f, y + 11f, value)
            y += 16f
        }
        row("Автомобиль", d.vehicle)
        row("VIN", d.vin ?: "не определён")
        row("Пробег", if (d.mileage.isBlank()) "" else d.mileage + " км")
        row("Клиент", d.client)
        if (d.demo) {
            y += 6f
            ensure(34f)
            val cd = page!!.canvas
            cd.drawRoundRect(RectF(M, y, W - M, y + 28f), 8f, 8f, Paint().apply { color = Color.rgb(224, 80, 79) })
            cd.drawText(
                "ДЕМО-ДАННЫЕ: получено в симуляторе, не является проверкой реального автомобиля",
                M + 12f, y + 18f, paint(10f, Color.WHITE, true),
            )
            y += 34f
        }
        if (!d.profileVerified) {
            y += 4f
            text("Внимание: профиль автомобиля не проверен на реальной машине, расширенные данные могут быть неточными.", paint(10f, Color.rgb(176, 122, 0)))
        }

        // ---- итог (светофор) ----
        y += 10f
        val lc = levelColor(d.verdict.level)
        val reasonLines = d.verdict.reasons.flatMap { wrap("• $it", body, pw - 100f) }
        val boxH = 50f + reasonLines.size * 15f + 8f
        ensure(boxH + 6f)
        run {
            val c = page!!.canvas
            val fill = Paint().apply { color = Color.argb(34, Color.red(lc), Color.green(lc), Color.blue(lc)) }
            c.drawRoundRect(RectF(M, y, W - M, y + boxH), 14f, 14f, fill)
            c.drawRoundRect(RectF(M, y, M + 6f, y + boxH), 3f, 3f, Paint().apply { color = lc })
            c.drawText("Итог проверки", M + 18f, y + 20f, paint(9f, gray))
            c.drawText(d.verdict.title, M + 18f, y + 40f, paint(17f, lc, true))
            // три огня светофора
            val lights = listOf(Level.RED, Level.YELLOW, Level.GREEN)
            for ((i, lv) in lights.withIndex()) {
                val active = lv == d.verdict.level
                val col = levelColor(lv)
                val p = Paint().apply {
                    isAntiAlias = true
                    color = if (active) col else Color.argb(50, Color.red(col), Color.green(col), Color.blue(col))
                }
                c.drawCircle(W - M - 24f - (2 - i) * 24f, y + 28f, 9f, p)
            }
            var ly = y + 62f
            for (ln in reasonLines) {
                c.drawText(ln, M + 18f, ly, body)
                ly += 15f
            }
        }
        y += boxH + 8f

        // ---- показатели ----
        if (d.indicators.isNotEmpty()) {
            section("Показатели")
            var col = 0
            for ((k, v) in d.indicators) {
                if (col == 0) ensure(32f)
                val x = M + col * (pw / 2f)
                page!!.canvas.drawText(k, x, y + 9f, label)
                page!!.canvas.drawText(v, x, y + 26f, value)
                col++
                if (col == 2) {
                    col = 0
                    y += 32f
                }
            }
            if (col != 0) y += 32f
        }

        // ---- диаграмма ячеек ----
        if (d.cells.isNotEmpty()) {
            section("Напряжения ячеек, В")
            ensure(104f)
            val c = page!!.canvas
            val n = d.cells.size
            val mn = d.cells.min()
            val mx = d.cells.max()
            val median = d.cells.sorted()[n / 2]
            val lo = mn - 0.02
            val span = ((mx + 0.005) - lo).coerceAtLeast(0.001)
            val chartH = 64f
            val gap = if (n > 60) 0.6f else 2f
            val bw = (pw - gap * (n - 1)) / n
            for ((i, v) in d.cells.withIndex()) {
                val h = (((v - lo) / span).toFloat()).coerceIn(0.05f, 1f) * chartH
                val dev = abs(v - median) * 1000.0
                val col = when {
                    dev <= 30.0 -> Color.rgb(31, 182, 122)
                    dev <= 80.0 -> Color.rgb(233, 168, 28)
                    else -> Color.rgb(224, 80, 79)
                }
                val x = M + i * (bw + gap)
                c.drawRect(x, y + chartH - h, x + bw, y + chartH, Paint().apply { color = col })
            }
            y += chartH + 14f
            c.drawText(
                "ячеек: $n · мин %.3f · макс %.3f · разброс %.0f мВ".format(Locale.US, mn, mx, (mx - mn) * 1000.0),
                M, y, small,
            )
            y += 12f
        }

        // ---- ошибки ----
        section("Коды неисправностей")
        if (d.dtcLines.isEmpty()) text("Ошибок не обнаружено или чтение не выполнялось.", body)
        else d.dtcLines.forEach { text("• $it", body) }

        endPage()
        val dir = ctx.getExternalFilesDir("reports") ?: ctx.filesDir
        dir.mkdirs()
        val out = File(dir, "evdiag-${d.reportId}.pdf")
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }
}
