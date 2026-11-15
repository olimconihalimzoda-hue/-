package com.evdiag.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.evdiag.data.AppSettings
import com.evdiag.data.HistoryStore
import com.evdiag.data.InspectionRecord
import com.evdiag.domain.Capacity
import com.evdiag.domain.Verdict
import com.evdiag.domain.VerdictResult
import com.evdiag.obd.Collector
import com.evdiag.obd.Dtc
import com.evdiag.obd.Elm327
import com.evdiag.obd.Reply
import com.evdiag.profile.CommandGuard
import com.evdiag.profile.FieldDef
import com.evdiag.profile.ProfileRepository
import com.evdiag.profile.ProfileUpdater
import com.evdiag.profile.VehicleProfile
import com.evdiag.report.ReportData
import com.evdiag.report.ReportGenerator
import com.evdiag.transport.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class DtcItem(val code: String, val description: String, val source: String, val status: String?)

data class SessionInfo(
    val seconds: Long,
    val socStart: Double?,
    val socNow: Double?,
    val energyKwh: Double,
    val estimate: Capacity.Estimate?,
)

private class Sample(val t: Double, val soc: Double?, val v: Double?, val i: Double?, val temp: Double?)

class DiagViewModel(private val app: Application) : AndroidViewModel(app) {
    private val settings = AppSettings(app)
    private val historyStore = HistoryStore(app)
    private val ioLock = Mutex()

    // ---------- Профили ----------
    var profiles by mutableStateOf(ProfileRepository.loadAll(app))
        private set
    var profile by mutableStateOf(profiles.firstOrNull { it.id == "obd2_standard" } ?: profiles.firstOrNull())
        private set
    var updating by mutableStateOf(false)
        private set

    // ---------- Состояние подключения ----------
    var status by mutableStateOf("Не подключено")
    var connected by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var vin by mutableStateOf<String?>(null)
    var vinHint by mutableStateOf<VehicleProfile?>(null)
    var toast by mutableStateOf<String?>(null)

    // ---------- Данные ----------
    val dtcs = mutableStateListOf<DtcItem>()
    var dtcReadDone by mutableStateOf(false)
    val values = mutableStateMapOf<String, String>()
    val nums = mutableStateMapOf<String, Double>()
    val cells = mutableStateMapOf<String, List<Double>>()
    val series = mutableStateMapOf<String, List<Float>>()
    val logLines = mutableStateListOf<String>()
    var live by mutableStateOf(false)

    // ---------- Проверка и отчёт ----------
    var clientName by mutableStateOf("")
    var mileage by mutableStateOf("")
    var price by mutableStateOf(settings.defaultPrice)
    var inspectionDone by mutableStateOf(false)
    var lastReport by mutableStateOf<File?>(null)
    val records = mutableStateListOf<InspectionRecord>().also { it.addAll(historyStore.load()) }

    // ---------- Мастер сбора данных ----------
    var collecting by mutableStateOf(false)
    var collectProgress by mutableStateOf(0f)
    var collectLabel by mutableStateOf("")
    var lastCollectFile by mutableStateOf<File?>(null)

    // ---------- Запись сессии ----------
    var sessionActive by mutableStateOf(false)
    var sessionInfo by mutableStateOf<SessionInfo?>(null)
    private val samples = mutableListOf<Sample>()

    // ---------- Настройки мастера ----------
    var inspectorName by mutableStateOf(settings.inspector)
    var company by mutableStateOf(settings.company)
    var phone by mutableStateOf(settings.phone)
    var updateUrl by mutableStateOf(settings.updateUrl)
    var logoFile by mutableStateOf<File?>(File(app.filesDir, "logo.png").takeIf { it.exists() })

    private var elm: Elm327? = null
    private var liveJob: Job? = null
    private var sessionJob: Job? = null
    private var collectJob: Job? = null

    private fun addLog(s: String) {
        logLines += s
        if (logLines.size > 2000) logLines.removeRange(0, 500)
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    // ---------- Профили: выбор, обновление, импорт ----------

    fun selectProfile(p: VehicleProfile) {
        stopLive()
        stopSession()
        profile = p
        clearData()
    }

    private fun clearData() {
        values.clear()
        nums.clear()
        cells.clear()
        series.clear()
        dtcs.clear()
        dtcReadDone = false
        inspectionDone = false
        lastReport = null
        sessionInfo = null
    }

    private fun reloadProfiles() {
        val id = profile?.id
        profiles = ProfileRepository.loadAll(app)
        profile = profiles.firstOrNull { it.id == id } ?: profiles.firstOrNull()
    }

    fun updateProfiles() {
        if (updating) return
        viewModelScope.launch {
            updating = true
            try {
                settings.updateUrl = updateUrl
                val n = ProfileUpdater.update(app, updateUrl)
                reloadProfiles()
                toast = "Профилей загружено: $n"
            } catch (ex: Exception) {
                toast = "Не удалось обновить: ${ex.message}"
            } finally {
                updating = false
            }
        }
    }

    fun importProfile(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("не удалось прочитать файл")
                }
                val name = ProfileRepository.importJson(app, text)
                reloadProfiles()
                toast = "Профиль добавлен: $name"
            } catch (ex: Exception) {
                toast = "Не удалось добавить профиль: ${ex.message}"
            }
        }
    }

    // ---------- Подключение ----------

    fun connect(transport: Transport) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            status = "Подключение…"
            try {
                val e = Elm327(transport)
                e.log = ::addLog
                e.init(profile?.protocol ?: "6")
                elm = e
                connected = true
                status = "Подключено"
                readVin()
            } catch (ex: Exception) {
                connected = false
                status = "Ошибка: ${ex.message}"
                runCatching { transport.close() }
            } finally {
                busy = false
            }
        }
    }

    fun disconnect() {
        stopLive()
        stopSession()
        collectJob?.cancel()
        elm?.close()
        elm = null
        connected = false
        status = "Не подключено"
    }

    private suspend fun readVin() {
        val e = elm ?: return
        ioLock.withLock {
            e.selectEcu("7DF", null)
            val r = e.query("0902").filterIsInstance<Reply.Data>().firstOrNull() ?: return
            val chars = r.bytes.drop(3).filter { it in 0x30..0x5A }
            if (chars.size >= 17) {
                val v = chars.takeLast(17).map { it.toChar() }.joinToString("")
                vin = v
                vinHint = profiles.firstOrNull { p -> p.vinPrefixes.any { v.startsWith(it) } }
            }
        }
    }

    // ---------- Ошибки ----------

    private suspend fun readDtcInternal() {
        val e = elm ?: return
        ioLock.withLock {
            val found = mutableListOf<DtcItem>()
            e.selectEcu("7DF", null)
            val modes = listOf("03" to "Сохранённые", "07" to "Ожидающие", "0A" to "Постоянные")
            for ((mode, label) in modes) {
                for (r in e.query(mode)) {
                    if (r is Reply.Data) {
                        Dtc.fromObdMode(r.bytes).forEach {
                            found += DtcItem(it, Dtc.describe(it), "OBD-II · $label · ${r.ecu}", null)
                        }
                    }
                }
            }
            // Расширенный опрос ЭБУ из профиля (UDS 19 02)
            for (ecu in profile?.ecus.orEmpty().filter { it.udsDtc }) {
                e.selectEcu(ecu.tx, ecu.rx)
                for (r in e.query("1902FF")) {
                    if (r is Reply.Data) {
                        Dtc.fromUds(r.bytes).forEach {
                            found += DtcItem(
                                "${it.code}-%02X".format(it.ftb), Dtc.describe(it.code),
                                "UDS · ${ecu.name}", "статус 0x%02X".format(it.status),
                            )
                        }
                    }
                }
            }
            dtcs.clear()
            dtcs.addAll(found)
            dtcReadDone = true
        }
    }

    fun readDtc() = runExclusive { readDtcInternal() }

    fun clearDtc() = runExclusive {
        val e = elm ?: return@runExclusive
        ioLock.withLock {
            e.selectEcu("7DF", null)
            e.query("04")
            dtcs.clear()
            dtcReadDone = false
        }
    }

    // ---------- Значения ----------

    fun numByRole(role: String): Double? {
        val p = profile ?: return null
        val f = p.fields.firstOrNull { it.role == role && nums.containsKey(it.id) } ?: return null
        return nums[f.id]
    }

    fun cellsList(): List<Double> {
        val p = profile ?: return emptyList()
        for (f in p.fields) {
            if (f.role == "cells" || f.group == "cells") {
                val c = cells[f.id]
                if (c != null) return c
            }
        }
        return emptyList()
    }

    fun spreadMv(): Double? {
        val c = cellsList()
        return if (c.size >= 2) (c.max() - c.min()) * 1000.0 else null
    }

    fun verdict(): VerdictResult =
        Verdict.evaluate(numByRole("soh"), spreadMv(), dtcs.map { it.code }, dtcReadDone)

    /** Показатели для карточек и отчёта: поля батареи + разброс ячеек. */
    fun indicators(): List<Pair<String, String>> {
        val p = profile ?: return emptyList()
        val list = mutableListOf<Pair<String, String>>()
        for (f in p.fields) {
            if (f.group == "battery") list += f.name to (values[f.id] ?: "—")
        }
        val s = spreadMv()
        if (s != null) list += "Разброс ячеек" to "%.0f мВ".format(Locale.US, s)
        return list
    }

    // ---------- Опрос ----------

    fun readBattery() = runExclusive {
        val p = profile ?: return@runExclusive
        pollOnce(p.fields.filter { it.group == "battery" || it.group == "cells" })
    }

    fun startLive() {
        if (live || !connected) return
        live = true
        liveJob = viewModelScope.launch {
            val p = profile ?: return@launch
            val fields = p.fields.filter { it.group == "live" }
            var fails = 0
            while (isActive && live) {
                try {
                    pollOnce(fields)
                    fails = 0
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    fails++
                    if (fails >= 3) {
                        status = "Ошибка: ${ex.message}"
                        live = false
                    }
                }
                delay(250)
            }
        }
    }

    fun stopLive() {
        live = false
        liveJob?.cancel()
        liveJob = null
    }

    private suspend fun pollOnce(fields: List<FieldDef>) {
        val e = elm ?: return
        val p = profile ?: return
        ioLock.withLock {
            for ((reqId, group) in fields.groupBy { it.request }) {
                val req = p.requests.firstOrNull { it.id == reqId } ?: continue
                val ecu = p.ecus.firstOrNull { it.id == req.ecu } ?: continue
                e.selectEcu(ecu.tx, ecu.rx)
                req.pre.forEach { e.raw(it) }
                val reply = e.query(req.cmd).filterIsInstance<Reply.Data>().firstOrNull() ?: continue
                for (f in group) {
                    if (f.repeat > 1) {
                        val list = (0 until f.repeat).mapNotNull { f.formula.eval(reply.bytes, it) }
                        if (list.isNotEmpty()) cells[f.id] = list
                    } else {
                        val v = f.formula.eval(reply.bytes) ?: continue
                        nums[f.id] = v
                        values[f.id] = "%.${f.decimals}f %s".format(Locale.US, v, f.unit).trim()
                        series[f.id] = ((series[f.id] ?: emptyList()) + v.toFloat()).takeLast(120)
                    }
                }
            }
        }
    }

    // ---------- Проверка перед покупкой ----------

    fun runInspection() = runExclusive {
        val p = profile ?: return@runExclusive
        readDtcInternal()
        pollOnce(p.fields.filter { it.group == "battery" || it.group == "cells" })
        inspectionDone = true
        lastReport = null
    }

    fun createReport(): File? {
        val p = profile ?: return null
        val v = verdict()
        val now = System.currentTimeMillis()
        val id = "EV-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now)) + "-" +
            UUID.randomUUID().toString().take(4).uppercase()
        val soh = numByRole("soh")
        val spread = spreadMv()
        val dtcLines = dtcs.map { "${it.code} — ${it.description} (${it.source})" }
        val data = ReportData(
            reportId = id,
            ts = now,
            inspector = inspectorName,
            company = company,
            phone = phone,
            logoPath = logoFile?.absolutePath,
            vehicle = p.name,
            profileVerified = p.verified,
            vin = vin,
            mileage = mileage,
            client = clientName,
            verdict = v,
            soh = soh,
            indicators = indicators(),
            cells = cellsList(),
            dtcLines = dtcLines,
        )
        return try {
            val file = ReportGenerator.create(app, data)
            lastReport = file
            val rec = InspectionRecord(
                id = id, ts = now, vin = vin ?: "", profile = p.name, client = clientName,
                mileage = mileage, price = price.replace(',', '.').trim().toDoubleOrNull() ?: 0.0,
                level = v.level.name, soh = soh, spreadMv = spread, dtcCount = dtcs.size,
                pdfPath = file.absolutePath,
            )
            records.add(0, rec)
            historyStore.save(records)
            toast = "Отчёт создан и сохранён в историю"
            file
        } catch (ex: Exception) {
            toast = "Не удалось создать отчёт: ${ex.message}"
            null
        }
    }

    fun deleteRecord(r: InspectionRecord) {
        records.remove(r)
        historyStore.save(records)
    }

    fun exportHistoryCsv(): File {
        val dir = app.getExternalFilesDir("exports") ?: app.filesDir
        dir.mkdirs()
        val f = File(dir, "evdiag-history-${stamp()}.csv")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder("date,client,vin,model,mileage_km,price,verdict,soh,spread_mv,dtc_count\n")
        for (r in records) {
            sb.append(fmt.format(Date(r.ts))).append(',')
                .append(csv(r.client)).append(',').append(csv(r.vin)).append(',').append(csv(r.profile)).append(',')
                .append(csv(r.mileage)).append(',').append(r.price).append(',').append(r.level).append(',')
                .append(r.soh ?: "").append(',').append(r.spreadMv ?: "").append(',').append(r.dtcCount).append('\n')
        }
        f.writeText(sb.toString())
        return f
    }

    private fun csv(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

    // ---------- Мастер сбора данных ----------

    fun collectData() {
        if (busy || !connected) return
        val e = elm ?: return
        busy = true
        collecting = true
        collectProgress = 0f
        collectLabel = "Старт"
        val sb = StringBuilder()
        sb.append("EV Diag: лог сбора данных (только чтение)\n")
        sb.append("Дата: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        sb.append("Профиль в приложении: ").append(profile?.name ?: "-").append('\n')
        sb.append("VIN: ").append(vin ?: "-").append('\n')
        sb.append("Телефон: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        collectJob = viewModelScope.launch {
            try {
                ioLock.withLock {
                    Collector.run(e, profiles, profile?.protocol ?: "6", sb) { done, total, label ->
                        collectProgress = if (total > 0) done.toFloat() / total else 0f
                        collectLabel = label
                    }
                }
                toast = "Сбор данных завершён"
            } catch (ex: CancellationException) {
                toast = "Сбор остановлен"
                throw ex
            } catch (ex: Exception) {
                status = "Ошибка: ${ex.message}"
                sb.append("\n# ОШИБКА: ").append(ex.message).append('\n')
            } finally {
                runCatching {
                    val dir = app.getExternalFilesDir("logs") ?: app.filesDir
                    dir.mkdirs()
                    val f = File(dir, "evdiag-collect-${stamp()}.txt")
                    f.writeText(sb.toString())
                    lastCollectFile = f
                }
                collecting = false
                busy = false
            }
        }
    }

    fun cancelCollect() {
        collectJob?.cancel()
    }

    // ---------- Запись сессии (заряд / поездка) ----------

    fun startSession() {
        if (!connected || sessionActive) return
        val p = profile ?: return
        val roles = setOf("soc", "packV", "current", "temp")
        val fields = p.fields.filter { it.role in roles }
        if (fields.none { it.role == "soc" }) {
            toast = "В профиле нет поля SOC: запись недоступна"
            return
        }
        samples.clear()
        sessionActive = true
        sessionInfo = null
        sessionJob = viewModelScope.launch {
            val t0 = System.nanoTime()
            var last = t0
            var energyWh = 0.0
            var socStart: Double? = null
            while (isActive && sessionActive) {
                if (busy) {
                    delay(500)
                    continue
                }
                try {
                    pollOnce(fields)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    toast = "Запись остановлена: ${ex.message}"
                    sessionActive = false
                    break
                }
                val now = System.nanoTime()
                val dt = (now - last) / 1e9
                last = now
                val soc = numByRole("soc")
                val v = numByRole("packV")
                val i = numByRole("current")
                val temp = numByRole("temp")
                if (soc != null && socStart == null) socStart = soc
                if (v != null && i != null) energyWh += v * i * p.currentSign * dt / 3600.0
                val elapsed = (now - t0) / 1e9
                samples += Sample(elapsed, soc, v, i, temp)
                val est = if (socStart != null && soc != null) {
                    Capacity.estimate(energyWh / 1000.0, socStart, soc, p.nominalKwh)
                } else null
                sessionInfo = SessionInfo(elapsed.toLong(), socStart, soc, energyWh / 1000.0, est)
                delay(1000)
            }
        }
    }

    fun stopSession() {
        sessionActive = false
        sessionJob?.cancel()
        sessionJob = null
    }

    fun exportSessionCsv(): File? {
        if (samples.isEmpty()) return null
        val dir = app.getExternalFilesDir("exports") ?: app.filesDir
        dir.mkdirs()
        val f = File(dir, "evdiag-session-${stamp()}.csv")
        val sb = StringBuilder("t_s,soc,voltage_V,current_A,temp_C\n")
        for (s in samples) {
            sb.append("%.1f".format(Locale.US, s.t)).append(',')
                .append(s.soc ?: "").append(',').append(s.v ?: "").append(',')
                .append(s.i ?: "").append(',').append(s.temp ?: "").append('\n')
        }
        f.writeText(sb.toString())
        return f
    }

    // ---------- Консоль и логи ----------

    fun sendRaw(cmd: String) {
        val c = cmd.trim().uppercase().replace(" ", "")
        if (c.isEmpty()) return
        if (!c.startsWith("AT") && !CommandGuard.isSafeRequest(c)) {
            toast = "Заблокировано: в консоли разрешены только AT-команды и команды чтения"
            return
        }
        runExclusive {
            val e = elm
            if (e != null) {
                ioLock.withLock { e.raw(c, 6000) }
            }
        }
    }

    /** Средний SOH по вашим прошлым проверкам этой модели (если их не меньше трёх). */
    fun modelAverage(): Pair<Double, Int>? {
        val name = profile?.name ?: return null
        val list = records.filter { it.profile == name }.mapNotNull { it.soh }
        return if (list.size >= 3) Pair(list.average(), list.size) else null
    }

    fun saveLog(): File {
        val dir = app.getExternalFilesDir("logs") ?: app.filesDir
        dir.mkdirs()
        val f = File(dir, "evdiag-log-${stamp()}.txt")
        f.writeText(logLines.joinToString("\n"))
        return f
    }

    // ---------- Бренд мастера ----------

    fun saveBrand(name: String, comp: String, ph: String, defPrice: String) {
        inspectorName = name
        company = comp
        phone = ph
        price = defPrice
        settings.inspector = name
        settings.company = comp
        settings.phone = ph
        settings.defaultPrice = defPrice
        toast = "Сохранено"
    }

    fun setLogo(uri: Uri) {
        viewModelScope.launch {
            try {
                val f = withContext(Dispatchers.IO) {
                    val bmp = app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        ?: error("не удалось открыть изображение")
                    val maxSide = 300
                    val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
                    val out = if (scale < 1f) {
                        Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
                    } else bmp
                    val file = File(app.filesDir, "logo.png")
                    file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    file
                }
                logoFile = f
                toast = "Логотип сохранён"
            } catch (ex: Exception) {
                toast = "Не удалось загрузить логотип: ${ex.message}"
            }
        }
    }

    fun clearLogo() {
        File(app.filesDir, "logo.png").delete()
        logoFile = null
    }

    // ---------- Служебное ----------

    private fun runExclusive(block: suspend () -> Unit) {
        if (busy || !connected) return
        viewModelScope.launch {
            busy = true
            try {
                block()
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                status = "Ошибка: ${ex.message}"
            } finally {
                busy = false
            }
        }
    }

    override fun onCleared() {
        disconnect()
    }
}
