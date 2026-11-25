package com.evdiag.transport

import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin

/**
 * Виртуальный ELM327 для отладки без автомобиля. Данные ВЫМЫШЛЕННЫЕ.
 *
 * Отвечает на:
 *  - AT-команды (ATZ, ATSH, остальные → OK)
 *  - OBD-II (заголовок 7DF): 010D, 0142, 0146, 015B, 03, 07, 0A, 04, 0902 (VIN)
 *  - «BMS» (заголовок 7E4, ответ с 7EC): 220101 (демо-батарея, 34 байта, многокадровый ответ),
 *    1902FF (UDS-ошибки)
 * Остальное → NO DATA.
 *
 * Раскладка ответа 220101 (индексы от начала ответа):
 *   0..2  62 01 01
 *   3     SOC * 2          4  SOH, %
 *   5..6  напряжение пака * 10 (В)   7..8  ток * 10 (А), со знаком
 *   9     температура + 40           10..33  12 ячеек, мВ (u16)
 */
class SimulatorTransport(private val clock: () -> Long = { System.currentTimeMillis() }) : Transport {
    private var header = "7DF"
    private var pending = ""
    private var cleared = false
    private val t0 = clock()

    override suspend fun open() {}

    override suspend fun write(text: String) {
        pending = respond(text.trim().uppercase().replace(" ", ""))
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String {
        delay(15)
        val r = pending
        pending = ""
        return r
    }

    override fun close() {}

    private fun sec() = (clock() - t0) / 1000.0
    private fun soc() = 70 + (sin(sec() / 30) * 5).toInt()

    private fun respond(cmd: String): String {
        if (cmd.startsWith("ATZ")) { header = "7DF"; return "ELM327 v1.5 (simulator)" }
        if (cmd.startsWith("ATSH")) { header = cmd.substring(4); return "OK" }
        if (cmd == "ATI") return "ELM327 v1.5 (simulator)"
        if (cmd == "ATRV") return "12.6V"
        if (cmd.startsWith("ATDP")) return "ISO 15765-4 (CAN 11/500)"
        if (cmd.startsWith("AT")) return "OK"
        if (cmd.length < 2 || cmd.length % 2 != 0 || !cmd.all { it in "0123456789ABCDEF" }) return "?"
        val req = cmd.chunked(2).map { it.toInt(16) }
        return when (header) {
            "7DF", "7E0" -> obd(req)
            "7E4" -> bms(req)
            else -> "NO DATA"
        }
    }

    private fun obd(req: List<Int>): String = when (req[0]) {
        0x01 -> {
            val pid = req.getOrNull(1) ?: return "NO DATA"
            val data = when (pid) {
                0x00 -> intArrayOf(0x00, 0x08, 0x00, 0x00) // поддерживается только PID 0D
                0x0D -> intArrayOf((abs(sin(sec() / 5)) * 60).toInt())
                0x42 -> { val mv = 12600 + (sin(sec()) * 50).toInt(); intArrayOf(mv shr 8, mv and 0xFF) }
                0x46 -> intArrayOf(60) // 20 °C
                0x5B -> intArrayOf(soc() * 255 / 100)
                else -> return "NO DATA"
            }
            frames("7E8", intArrayOf(0x41, pid) + data)
        }
        0x03 -> frames("7E8", if (cleared) intArrayOf(0x43, 0x00) else intArrayOf(0x43, 0x02, 0x0A, 0x80, 0xC1, 0x00))
        0x07 -> frames("7E8", intArrayOf(0x47, 0x00))
        0x0A -> frames("7E8", intArrayOf(0x4A, 0x00))
        0x04 -> { cleared = true; frames("7E8", intArrayOf(0x44)) }
        0x09 -> if (req.getOrNull(1) == 0x02) {
            frames("7E8", intArrayOf(0x49, 0x02, 0x01) + VIN.map { it.code }.toIntArray())
        } else "NO DATA"
        else -> "NO DATA"
    }

    private fun bms(req: List<Int>): String = when {
        req == listOf(0x22, 0x01, 0x01) -> frames("7EC", batteryPayload())
        req == listOf(0x19, 0x02, 0xFF) ->
            frames("7EC", if (cleared) intArrayOf(0x59, 0x02, 0xFF) else intArrayOf(0x59, 0x02, 0xFF, 0x0A, 0x80, 0x00, 0x09))
        else -> "NO DATA"
    }

    private fun batteryPayload(): IntArray {
        val packV = 3500 + (sin(sec() / 7) * 10).toInt()
        val cur = (sin(sec() / 4) * 300).toInt() and 0xFFFF
        val head = intArrayOf(
            0x62, 0x01, 0x01, soc() * 2, 91,
            packV shr 8, packV and 0xFF, cur shr 8, cur and 0xFF, 65,
        )
        val cells = (0 until 12).flatMap { i ->
            val mv = 3700 + i * 2 + (sin(sec() + i) * 3).toInt()
            listOf(mv shr 8, mv and 0xFF)
        }.toIntArray()
        return head + cells
    }

    /** Кодирует полезную нагрузку в кадры ISO-TP в формате вывода ELM327 при ATH1. */
    fun frames(ecu: String, p: IntArray): String {
        val lines = mutableListOf<List<Int>>()
        if (p.size <= 7) {
            lines += listOf(p.size) + p.toList()
        } else {
            lines += listOf(0x10 or (p.size shr 8), p.size and 0xFF) + p.take(6)
            var n = 1
            p.drop(6).chunked(7).forEach { lines += listOf(0x20 or (n and 0xF)) + it; n++ }
        }
        return lines.joinToString("\r") { l ->
            val padded = (l + List(8 - l.size) { 0 }).take(8)
            "$ecu " + padded.joinToString(" ") { "%02X".format(it and 0xFF) }
        }
    }

    companion object {
        const val VIN = "SIMEV00000000DEMO"
    }
}
