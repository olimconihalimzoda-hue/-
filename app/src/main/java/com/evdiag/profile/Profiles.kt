package com.evdiag.profile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class EcuDef(val id: String, val name: String, val tx: String, val rx: String?, val udsDtc: Boolean)

data class RequestDef(val id: String, val ecu: String, val cmd: String, val pre: List<String>)

/**
 * role — смысловая роль поля, по ней работают светофор, шкала заряда и оценка ёмкости:
 * soc, soh, packV, current, temp, cells, odometer, fastChargeCount, acChargeCount (или пусто).
 */
data class FieldDef(
    val id: String,
    val name: String,
    val unit: String,
    val group: String,       // "battery" | "live" | "cells"
    val request: String,
    val formula: Formula,
    val repeat: Int,         // >1 для массивов (напряжения ячеек)
    val decimals: Int,
    val role: String = "",
)

data class VehicleProfile(
    val id: String,
    val name: String,
    val verified: Boolean,
    val protocol: String,
    val notes: String,
    val vinPrefixes: List<String>,
    val ecus: List<EcuDef>,
    val requests: List<RequestDef>,
    val fields: List<FieldDef>,
    val nominalKwh: Double? = null,
    val currentSign: Int = 1,   // 1: положительный ток = заряд; -1: наоборот
)

/**
 * Защита: из профилей (в том числе скачанных) разрешены только команды ЧТЕНИЯ.
 * Запись, сброс, управление и сервисные сессии (2E, 2F, 31, 10, 27, 11, 04 и т.д.) отбрасываются.
 */
object CommandGuard {
    private val hex = Regex("[0-9A-Fa-f]+")
    private val allowedSids = setOf(0x01, 0x02, 0x03, 0x07, 0x09, 0x0A, 0x19, 0x21, 0x22)
    private val allowedAtPrefixes = listOf("ATSH", "ATFC", "ATCRA", "ATCF", "ATCM", "ATAR", "ATAT", "ATST")

    fun isSafeRequest(cmd: String): Boolean {
        val c = cmd.trim()
        if (c.length < 2 || c.length % 2 != 0 || c.length > 24 || !hex.matches(c)) return false
        return c.substring(0, 2).toInt(16) in allowedSids
    }

    fun isSafePre(cmd: String): Boolean {
        val c = cmd.trim().uppercase()
        return c.length <= 20 && allowedAtPrefixes.any { c.startsWith(it) }
    }
}

object ProfileRepository {
    fun userDir(ctx: Context): File = File(ctx.filesDir, "profiles").also { it.mkdirs() }

    fun loadAll(ctx: Context): List<VehicleProfile> {
        val raw = linkedMapOf<String, JSONObject>()
        for (name in (ctx.assets.list("profiles") ?: emptyArray())) {
            if (!name.endsWith(".json")) continue
            val text = ctx.assets.open("profiles/$name").bufferedReader().use { it.readText() }
            val o = JSONObject(text)
            raw[o.getString("id")] = o
        }
        // скачанные и импортированные профили перекрывают встроенные с тем же id
        for (f in userDir(ctx).listFiles().orEmpty()) {
            if (!f.name.endsWith(".json")) continue
            val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: continue
            if (o.has("id") && o.has("name")) raw[o.getString("id")] = o
        }
        return raw.values
            .filter { !it.optBoolean("hidden", false) }
            .mapNotNull { runCatching { build(it, raw) }.getOrNull() }
            .sortedBy { it.name }
    }

    /** Проверяет и сохраняет профиль из JSON-текста. Возвращает название профиля. */
    fun importJson(ctx: Context, text: String): String {
        val o = JSONObject(text)
        val id = o.getString("id")
        val name = o.getString("name")
        require(Regex("[A-Za-z0-9_-]{1,40}").matches(id)) { "Некорректный id профиля" }
        build(o, mapOf(id to o))
        File(userDir(ctx), "$id.json").writeText(text)
        return name
    }

    private fun build(o: JSONObject, all: Map<String, JSONObject>): VehicleProfile {
        val parent = o.optString("extends").takeIf { it.isNotEmpty() }?.let { all[it] }
        val ecus = mutableListOf<EcuDef>()
        val reqs = mutableListOf<RequestDef>()
        val fields = mutableListOf<FieldDef>()
        for (src in listOfNotNull(parent, o)) {
            src.optJSONArray("ecus").each { e ->
                ecus += EcuDef(
                    e.getString("id"), e.optString("name", e.getString("id")),
                    e.getString("tx"), e.optString("rx").takeIf { it.isNotEmpty() },
                    e.optBoolean("udsDtc", false),
                )
            }
            src.optJSONArray("requests").each { r ->
                val cmd = r.getString("cmd")
                if (!CommandGuard.isSafeRequest(cmd)) return@each
                val pre = mutableListOf<String>()
                r.optJSONArray("pre")?.let { a ->
                    for (k in 0 until a.length()) {
                        val c = a.getString(k)
                        if (CommandGuard.isSafePre(c)) pre += c
                    }
                }
                reqs += RequestDef(r.getString("id"), r.getString("ecu"), cmd, pre)
            }
            src.optJSONArray("fields").each { f ->
                fields += FieldDef(
                    f.getString("id"), f.getString("name"), f.optString("unit"),
                    f.optString("group", "live"), f.getString("request"),
                    Formula(f.getString("expr")), f.optInt("repeat", 1), f.optInt("decimals", 1),
                    f.optString("role", ""),
                )
            }
        }
        val vin = mutableListOf<String>()
        o.optJSONArray("vinPrefixes")?.let { a -> for (k in 0 until a.length()) vin += a.getString(k) }
        val nominal = o.optDouble("nominalKwh", Double.NaN).takeIf { !it.isNaN() }
        return VehicleProfile(
            o.getString("id"), o.getString("name"), o.optBoolean("verified", false),
            o.optString("protocol", "6"), o.optString("notes"), vin, ecus, reqs, fields,
            nominal, o.optInt("currentSign", 1),
        )
    }

    private inline fun JSONArray?.each(block: (JSONObject) -> Unit) {
        if (this == null) return
        for (i in 0 until length()) block(getJSONObject(i))
    }
}
