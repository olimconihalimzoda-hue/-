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

    /** Справочник общих кодов SAE (P0xxx, U0xxx) и HV/EV. Остальное — «описание не найдено». */
    private val known = mapOf(
        "P0A80" to "Требуется замена тяговой батареи / деградация",
        "P0A7F" to "Деградация тяговой батареи",
        "P0AA6" to "Нарушение изоляции высоковольтной системы",
        "P0AFA" to "Низкое напряжение высоковольтной батареи",
        "P0A94" to "Производительность DC/DC-преобразователя",
        "U0100" to "Потеряна связь с ЭБУ двигателя/силовой установки",
        "P0A1F" to "Неисправность блока управления батареей",
        "P0AC0" to "Датчик тока высоковольтной батареи: цепь",
        "P0A78" to "Инвертор тягового мотора: производительность",
        "P0100" to "Датчик массового расхода воздуха (MAF): цепь",
        "P0101" to "Датчик MAF: диапазон или производительность",
        "P0102" to "Датчик MAF: низкий сигнал",
        "P0103" to "Датчик MAF: высокий сигнал",
        "P0110" to "Датчик температуры воздуха на впуске: цепь",
        "P0115" to "Датчик температуры охлаждающей жидкости: цепь",
        "P0116" to "Датчик температуры охлаждающей жидкости: диапазон",
        "P0117" to "Датчик температуры охлаждающей жидкости: низкий сигнал",
        "P0118" to "Датчик температуры охлаждающей жидкости: высокий сигнал",
        "P0120" to "Датчик положения дросселя: цепь",
        "P0130" to "Кислородный датчик (банк 1, датчик 1): цепь",
        "P0133" to "Кислородный датчик (банк 1, датчик 1): медленный отклик",
        "P0135" to "Подогрев кислородного датчика (банк 1, датчик 1): цепь",
        "P0171" to "Смесь слишком бедная (банк 1)",
        "P0172" to "Смесь слишком богатая (банк 1)",
        "P0174" to "Смесь слишком бедная (банк 2)",
        "P0175" to "Смесь слишком богатая (банк 2)",
        "P0201" to "Форсунка цилиндра 1: цепь",
        "P0202" to "Форсунка цилиндра 2: цепь",
        "P0203" to "Форсунка цилиндра 3: цепь",
        "P0204" to "Форсунка цилиндра 4: цепь",
        "P0300" to "Случайные или множественные пропуски зажигания",
        "P0301" to "Пропуски зажигания в цилиндре 1",
        "P0302" to "Пропуски зажигания в цилиндре 2",
        "P0303" to "Пропуски зажигания в цилиндре 3",
        "P0304" to "Пропуски зажигания в цилиндре 4",
        "P0325" to "Датчик детонации: цепь",
        "P0335" to "Датчик положения коленвала: цепь",
        "P0340" to "Датчик положения распредвала: цепь",
        "P0401" to "Рециркуляция выхлопных газов (EGR): недостаточный поток",
        "P0420" to "Эффективность катализатора ниже порога (банк 1)",
        "P0430" to "Эффективность катализатора ниже порога (банк 2)",
        "P0440" to "Система улавливания паров топлива (EVAP): неисправность",
        "P0442" to "Система EVAP: малая утечка",
        "P0455" to "Система EVAP: большая утечка",
        "P0500" to "Датчик скорости автомобиля",
        "P0505" to "Система управления холостым ходом",
        "P0562" to "Низкое напряжение бортсети",
        "P0563" to "Высокое напряжение бортсети",
        "P0600" to "Ошибка последовательной связи между блоками",
        "P0606" to "Неисправность процессора блока управления",
        "P0700" to "Система управления коробкой передач",
        "U0121" to "Потеряна связь с блоком ABS",
        "U0140" to "Потеряна связь с блоком кузовной электроники",
        "U0155" to "Потеряна связь с панелью приборов",
    )

    fun describe(code: String): String = known[code] ?: when {
        code.startsWith("P1") || code.startsWith("P3") -> "Описание не найдено (код производителя, смотрите документацию марки)"
        else -> "Описание не найдено"
    }

    /** Битовая маска поддерживаемых PID: ответ на 0100/0120/0140 (41 xx m1 m2 m3 m4). Возвращает номера PID. */
    fun supportedPids(base: Int, bytes: IntArray): List<Int> {
        if (bytes.size < 6) return emptyList()
        val mask = (bytes[2].toLong() shl 24) or (bytes[3].toLong() shl 16) or (bytes[4].toLong() shl 8) or bytes[5].toLong()
        return (0 until 32).filter { (mask shr (31 - it)) and 1L == 1L }.map { base + it + 1 }
    }
}
