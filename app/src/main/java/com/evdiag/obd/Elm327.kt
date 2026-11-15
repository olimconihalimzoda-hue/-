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
        transport.open()
        raw("ATZ", 4000)
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
    suspend fun query(cmd: String, timeoutMs: Long = 4000): List<Reply> =
        IsoTp.parse(raw(cmd, timeoutMs))

    fun close() = transport.close()
}
