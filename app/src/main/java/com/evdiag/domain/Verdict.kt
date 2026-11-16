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
        var batteryBad = false
        val bad = mutableListOf<String>()
        val good = mutableListOf<String>()
        val info = mutableListOf<String>()

        if (soh != null) {
            when {
                soh >= 90.0 -> good += "Износ батареи небольшой: SOH ${f(soh)}%."
                soh >= 80.0 -> { sev = maxOf(sev, 1); bad += "Заметный износ батареи: SOH ${f(soh)}%." }
                else -> { sev = 2; batteryBad = true; bad += "Сильный износ батареи: SOH ${f(soh)}% (ниже 80%)." }
            }
        }
        if (spreadMv != null) {
            when {
                spreadMv <= 30.0 -> good += "Ячейки сбалансированы: разброс ${f(spreadMv)} мВ."
                spreadMv <= 80.0 -> { sev = maxOf(sev, 1); bad += "Разброс напряжений ячеек ${f(spreadMv)} мВ: возможен дисбаланс." }
                else -> { sev = 2; batteryBad = true; bad += "Разброс напряжений ячеек ${f(spreadMv)} мВ: вероятна слабая ячейка или модуль." }
            }
        }
        val hv = dtcCodes.map { it.substringBefore('-') }.distinct().filter { it.startsWith("P0A") }
        val other = dtcCodes.map { it.substringBefore('-') }.distinct().filter { !it.startsWith("P0A") }
        if (hv.isNotEmpty()) {
            sev = 2
            bad += "Ошибки высоковольтной системы: ${hv.joinToString(", ")}."
        }
        if (other.isNotEmpty()) {
            sev = maxOf(sev, 1)
            bad += "Есть коды ошибок: ${other.joinToString(", ")}."
        }
        if (!dtcRead) info += "Коды ошибок не читались."
        if (soh == null && spreadMv == null) {
            info += "Данных батареи (SOH, ячейки) для этой модели нет: нужен профиль. Используйте «Сбор данных»."
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
            Level.RED -> if (hv.isNotEmpty() && !batteryBad) "Ошибки высоковольтной системы" else "Серьёзные замечания"
            Level.GRAY -> "Недостаточно данных"
        }
        // сначала проблемы, затем хорошие показатели, затем пояснения
        return VerdictResult(level, title, bad + good + info)
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
