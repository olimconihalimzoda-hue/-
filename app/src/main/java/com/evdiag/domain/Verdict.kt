package com.evdiag.domain

import java.util.Locale
import kotlin.math.abs

enum class Level { GREEN, YELLOW, RED, GRAY }

data class VerdictResult(val level: Level, val title: String, val reasons: List<String>)

/**
 * «Светофор» состояния батареи. Пороги ориентировочные (эвристика), не заводские нормы.
 *  SOH: >= 90 хорошо, 80..90 внимание, < 80 плохо
 *  Разброс напряжений ячеек: <= 30 мВ хорошо, <= 80 мВ внимание, > 80 мВ плохо
 *  Коды P0Axx (высоковольтная система) — плохо; любые другие коды — внимание
 */
object Verdict {
    private fun f(v: Double, digits: Int = 0): String = String.format(Locale.US, "%.${digits}f", v)

    fun evaluate(soh: Double?, spreadMv: Double?, dtcCodes: List<String>, dtcRead: Boolean): VerdictResult {
        // 0 = хорошо, 1 = внимание, 2 = плохо
        var sev = 0
        val reasons = mutableListOf<String>()

        if (soh != null) {
            when {
                soh >= 90.0 -> reasons += "Износ батареи небольшой: SOH ${f(soh)}%."
                soh >= 80.0 -> { sev = maxOf(sev, 1); reasons += "Заметный износ батареи: SOH ${f(soh)}%." }
                else -> { sev = maxOf(sev, 2); reasons += "Сильный износ батареи: SOH ${f(soh)}% (ниже 80%)." }
            }
        }
        if (spreadMv != null) {
            when {
                spreadMv <= 30.0 -> reasons += "Ячейки сбалансированы: разброс ${f(spreadMv)} мВ."
                spreadMv <= 80.0 -> { sev = maxOf(sev, 1); reasons += "Разброс напряжений ячеек ${f(spreadMv)} мВ: возможен дисбаланс." }
                else -> { sev = maxOf(sev, 2); reasons += "Разброс напряжений ячеек ${f(spreadMv)} мВ: вероятна слабая ячейка или модуль." }
            }
        }
        val hv = dtcCodes.filter { it.startsWith("P0A") }
        val other = dtcCodes.filter { !it.startsWith("P0A") }
        if (hv.isNotEmpty()) {
            sev = 2
            reasons += "Ошибки высоковольтной системы: ${hv.joinToString(", ")}."
        }
        if (other.isNotEmpty()) {
            sev = maxOf(sev, 1)
            reasons += "Есть коды ошибок: ${other.joinToString(", ")}."
        }
        if (!dtcRead) reasons += "Коды ошибок не читались."
        if (soh == null && spreadMv == null) {
            reasons += "Данных батареи (SOH, ячейки) для этой модели нет: нужен профиль. Используйте «Сбор данных»."
        }

        val level = when {
            sev == 2 -> Level.RED
            sev == 1 -> Level.YELLOW
            soh == null && spreadMv == null -> Level.GRAY
            else -> Level.GREEN
        }
        val title = when (level) {
            Level.GREEN -> "Состояние хорошее"
            Level.YELLOW -> "Требует внимания"
            Level.RED -> "Серьёзные замечания"
            Level.GRAY -> "Недостаточно данных"
        }
        return VerdictResult(level, title, reasons)
    }
}

/** Оценка ёмкости батареи по энергии и изменению SOC за сессию. Приблизительная. */
object Capacity {
    class Estimate(val kwh: Double, val sohPercent: Double?)

    fun estimate(energyKwh: Double, socStart: Double, socEnd: Double, nominalKwh: Double?): Estimate? {
        val d = socEnd - socStart
        if (abs(d) < 5.0) return null
        if (energyKwh * d <= 0.0) return null
        val cap = abs(energyKwh) / abs(d) * 100.0
        val soh = if (nominalKwh != null && nominalKwh > 0.0) cap / nominalKwh * 100.0 else null
        return Estimate(cap, soh)
    }
}
