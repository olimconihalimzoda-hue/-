package com.evdiag.obd

import com.evdiag.profile.CommandGuard
import com.evdiag.profile.VehicleProfile
import java.util.concurrent.TimeoutException

/**
 * Мастер сбора данных: опрашивает автомобиль ТОЛЬКО на чтение и складывает ответы в текстовый лог.
 * По такому логу можно составить профиль батареи для новой модели.
 */
object Collector {
    class Step(val label: String, val commands: List<String>, val timeoutMs: Long = 4000)

    fun plan(profiles: List<VehicleProfile>, restoreProtocol: String): List<Step> {
        val steps = mutableListOf<Step>()
        steps += Step("Адаптер", listOf("ATI", "AT@1", "ATRV", "ATDP"))
        steps += Step("Автоопределение протокола", listOf("ATSP0", "0100"), 9000)
        steps += Step("Поддерживаемые параметры", listOf("ATSH7DF", "ATAR", "0100", "0120", "0140", "0160", "0180", "01A0"))
        steps += Step("Основные параметры", listOf("0105", "010C", "010D", "0142", "0146", "015B"))
        steps += Step("VIN и коды ошибок", listOf("0902", "03", "07", "0A"))
        for (i in 0..7) {
            val tx = "7E$i"
            val rx = "7E" + (8 + i).toString(16).uppercase()
            steps += Step("Блок $tx", listOf("ATSH$tx", "ATCRA$rx", "1902FF", "22F190", "22F187", "22F189"))
        }

        val seen = HashSet<String>()
        for (p in profiles) {
            if (p.id == "obd2_standard" || p.id == "demo_ev") continue
            for (ecu in p.ecus) {
                val rx = ecu.rx ?: continue
                val cmds = mutableListOf("ATSH${ecu.tx}", "ATCRA$rx")
                if (seen.add("${ecu.tx}/1902FF")) cmds += "1902FF"
                for (r in p.requests.filter { it.ecu == ecu.id }) {
                    if (!CommandGuard.isSafeRequest(r.cmd)) continue
                    if (!seen.add("${ecu.tx}/${r.cmd}")) continue
                    for (pre in r.pre) if (CommandGuard.isSafePre(pre)) cmds += pre
                    cmds += r.cmd
                }
                if (cmds.size > 2) steps += Step("${p.name}: ${ecu.name}", cmds)
            }
        }
        steps += Step("Завершение", listOf("ATSH7DF", "ATAR", "ATSP$restoreProtocol"))
        return steps
    }

    suspend fun run(
        elm: Elm327,
        profiles: List<VehicleProfile>,
        restoreProtocol: String,
        out: StringBuilder,
        onProgress: (done: Int, total: Int, label: String) -> Unit,
    ) {
        val steps = plan(profiles, restoreProtocol)
        val total = steps.sumOf { it.commands.size }
        var done = 0
        for (s in steps) {
            out.append("\n## ").append(s.label).append('\n')
            for (cmd in s.commands) {
                onProgress(done, total, s.label)
                out.append("> ").append(cmd).append('\n')
                val reply = try {
                    elm.raw(cmd, s.timeoutMs)
                } catch (e: TimeoutException) {
                    "TIMEOUT"
                }
                out.append(reply.lines().joinToString("\n") { "< $it" }).append('\n')
                done++
            }
        }
        onProgress(total, total, "Готово")
    }
}
