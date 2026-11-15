package com.evdiag.obd

sealed class Reply {
    /** Собранная полезная нагрузка ответа ЭБУ [ecu]; bytes[0] — SID ответа (напр. 0x41, 0x61, 0x62). */
    class Data(val ecu: String, val bytes: IntArray) : Reply()
    class Negative(val ecu: String, val sid: Int, val code: Int) : Reply()
    class Error(val text: String) : Reply()
}

object IsoTp {
    private val HEX = Regex("[0-9A-Fa-f]+")

    /** Разбирает вывод ELM327 при ATH1/ATS0/ATCAF1: строки вида "7EC 10 1A 61 01 ...". */
    fun parse(text: String): List<Reply> {
        val byEcu = linkedMapOf<String, MutableList<IntArray>>()
        val replies = mutableListOf<Reply>()
        val errors = mutableListOf<String>()

        for (line in text.lines()) {
            val tok = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val isFrame = tok.size >= 2 && tok[0].length == 3 &&
                tok.all { it.matches(HEX) } && tok.drop(1).all { it.length == 2 }
            if (!isFrame) {
                if (line.isNotBlank()) errors += line.trim()
                continue
            }
            byEcu.getOrPut(tok[0]) { mutableListOf() } += tok.drop(1).map { it.toInt(16) }.toIntArray()
        }

        for ((ecu, frames) in byEcu) {
            val payload = assemble(frames) ?: continue
            if (payload.size >= 3 && payload[0] == 0x7F) {
                replies += Reply.Negative(ecu, payload[1], payload[2])
            } else if (payload.isNotEmpty()) {
                replies += Reply.Data(ecu, payload)
            }
        }
        if (replies.isEmpty()) {
            val ignore = setOf("SEARCHING...", "BUS INIT: OK", "BUS INIT: ...OK")
            val msg = errors.filter { it !in ignore }.joinToString("; ").ifEmpty { "NO DATA" }
            replies += Reply.Error(msg)
        }
        return replies
    }

    private fun assemble(frames: List<IntArray>): IntArray? {
        if (frames.isEmpty()) return null
        val first = frames[0]
        if (first.isEmpty()) return null
        return when (first[0] shr 4) {
            0 -> {
                val len = first[0] and 0x0F
                first.drop(1).take(len).toIntArray()
            }
            1 -> {
                val len = ((first[0] and 0x0F) shl 8) or first[1]
                val out = ArrayList<Int>(len)
                out += first.drop(2)
                // Последовательные кадры (0x2N) идут в порядке прихода от ELM327
                for (f in frames.drop(1)) if (f.isNotEmpty() && (f[0] shr 4) == 2) out += f.drop(1)
                out.take(len).toIntArray()
            }
            else -> null
        }
    }
}

object Dtc {
    /** 2 байта → код вида P0A80 / U0100 / C1234 / B1000. */
    fun decode(b1: Int, b2: Int): String {
        val type = "PCBU"[(b1 shr 6) and 3]
        val d1 = (b1 shr 4) and 3
        return "%c%d%X%X%X".format(type, d1, b1 and 0xF, (b2 shr 4) and 0xF, b2 and 0xF)
    }

    /** Ответ на режимы 03 / 07 / 0A (SID 0x43 / 0x47 / 0x4A). */
    fun fromObdMode(bytes: IntArray): List<String> {
        if (bytes.isEmpty()) return emptyList()
        var body = bytes.drop(1)
        if (body.size % 2 == 1) body = body.drop(1) // CAN: байт количества кодов
        return body.chunked(2).filter { it.size == 2 && !(it[0] == 0 && it[1] == 0) }
            .map { decode(it[0], it[1]) }
    }

    class UdsDtc(val code: String, val ftb: Int, val status: Int)

    /** Ответ UDS 19 02 (SID 0x59): 59 02 mask {DTC_hi DTC_mid DTC_lo status}*. */
    fun fromUds(bytes: IntArray): List<UdsDtc> {
        if (bytes.size < 3 || bytes[0] != 0x59) return emptyList()
        return bytes.drop(3).chunked(4).filter { it.size == 4 && !(it[0] == 0 && it[1] == 0 && it[2] == 0) }
            .map { UdsDtc(decode(it[0], it[1]), it[2], it[3]) }
    }

    /** Небольшой справочник общих HV/EV-кодов. Остальное — «описание не найдено». */
    private val known = mapOf(
        "P0A80" to "Требуется замена тяговой батареи / деградация",
        "P0A7F" to "Деградация тяговой батареи",
        "P0AA6" to "Нарушение изоляции высоковольтной системы",
        "P0AFA" to "Низкое напряжение высоковольтной батареи",
        "P0A94" to "Производительность DC/DC-преобразователя",
        "U0100" to "Потеряна связь с ЭБУ двигателя/силовой установки",
    )

    fun describe(code: String): String = known[code] ?: "Описание не найдено"
}
