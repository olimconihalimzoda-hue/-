package com.evdiag.obd

import com.evdiag.transport.Transport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeoutException

/** Тонкая обёртка над ELM327: AT-команды, выбор ЭБУ, запрос и разбор ответа. */
class Elm327(private val transport: Transport) {
    var log: (String) -> Unit = {}
    private val mutex = Mutex()
    private var selected: String? = null

    /** protocol: "6" = ISO 15765-4 CAN 11bit/500k (большинство EV), "7" = 29bit/500k, "0" = авто. */
    suspend fun init(protocol: String = "6") {
        try {
            transport.open()
        } catch (e: Exception) {
            throw IllegalStateException(
                "Не удалось подключиться к адаптеру. Проверьте, что он вставлен в разъём, зажигание включено, " +
                    "а телефон подключён к Wi-Fi адаптера (или адаптер спарен по Bluetooth). (${e.message})",
                e,
            )
        }
        try {
            raw("ATZ", 5000)
        } catch (e: TimeoutException) {
            // некоторые адаптеры долго стартуют: одна повторная попытка
            raw("ATZ", 6000)
        }
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH1", "ATCAF1", "ATAT1", "ATSP$protocol")) {
            raw(c, 2000)
        }
        selected = null
    }

    suspend fun raw(cmd: String, timeoutMs: Long = 3000): String = mutex.withLock {
        // любая смена заголовка/протокола делает кэш выбранного блока недействительным
        if (cmd.startsWith("ATSH") || cmd.startsWith("ATCRA") || cmd.startsWith("ATAR") ||
            cmd.startsWith("ATZ") || cmd.startsWith("ATD") || cmd.startsWith("ATSP")
        ) {
            selected = null
        }
        log("> $cmd")
        transport.write(cmd + "\r")
        val text = try {
            transport.readUntilPrompt(timeoutMs)
        } catch (e: TimeoutException) {
            // пытаемся «выровнять» адаптер, чтобы следующий ответ не склеился с этим
            runCatching {
                transport.write("\r")
                transport.readUntilPrompt(1500)
            }
            log("< TIMEOUT")
            throw e
        }
        val cleaned = text
            .replace('\r', '\n')
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != cmd }
            .joinToString("\n")
        log("< $cleaned")
        cleaned
    }

    /** Выбор ЭБУ: tx — заголовок запроса (напр. 7E4), rx — фильтр ответа (напр. 7EC) или null. */
    suspend fun selectEcu(tx: String, rx: String?) {
        val key = "$tx/$rx"
        if (selected == key) return
        raw("ATSH$tx")
        if (rx != null) raw("ATCRA$rx") else raw("ATAR")
        selected = key
    }

    /** Отправить запрос (hex без пробелов) и вернуть разобранные ответы. */
    suspend fun query(cmd: String, timeoutMs: Long = 4000, retries: Int = 1): List<Reply> {
        var attempt = 0
        while (true) {
            try {
                return IsoTp.parse(raw(cmd, timeoutMs))
            } catch (e: TimeoutException) {
                // повторяем только безопасные запросы чтения; сброс ошибок (04) вызывается с retries = 0
                if (attempt >= retries) throw e
                attempt++
                log("! повтор запроса $cmd (попытка ${attempt + 1})")
            }
        }
    }

    fun close() = transport.close()
}
