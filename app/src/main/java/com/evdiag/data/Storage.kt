package com.evdiag.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Настройки мастера (имя, компания, телефон, логотип, цена по умолчанию, адрес профилей). */
class AppSettings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("evdiag", Context.MODE_PRIVATE)

    var inspector: String
        get() = sp.getString("inspector", "") ?: ""
        set(v) { sp.edit().putString("inspector", v).apply() }

    var company: String
        get() = sp.getString("company", "") ?: ""
        set(v) { sp.edit().putString("company", v).apply() }

    var phone: String
        get() = sp.getString("phone", "") ?: ""
        set(v) { sp.edit().putString("phone", v).apply() }

    var defaultPrice: String
        get() = sp.getString("defaultPrice", "") ?: ""
        set(v) { sp.edit().putString("defaultPrice", v).apply() }

    var updateUrl: String
        get() = sp.getString("updateUrl", DEFAULT_UPDATE_URL) ?: DEFAULT_UPDATE_URL
        set(v) { sp.edit().putString("updateUrl", v).apply() }

    companion object {
        const val DEFAULT_UPDATE_URL = "https://raw.githubusercontent.com/olimconihalimzoda-hue/-/main/profiles/"
    }
}

data class InspectionRecord(
    val id: String,
    val ts: Long,
    val vin: String,
    val profile: String,
    val client: String,
    val mileage: String,
    val price: Double,
    val level: String,       // GREEN / YELLOW / RED / GRAY
    val soh: Double?,
    val spreadMv: Double?,
    val dtcCount: Int,
    val pdfPath: String,
)

/** История проверок: простой JSON-файл во внутреннем хранилище приложения. */
class HistoryStore(ctx: Context) {
    private val file = File(ctx.filesDir, "history.json")

    fun load(): List<InspectionRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                InspectionRecord(
                    o.getString("id"), o.getLong("ts"), o.optString("vin"), o.optString("profile"),
                    o.optString("client"), o.optString("mileage"), o.optDouble("price", 0.0),
                    o.optString("level", "GRAY"),
                    if (o.has("soh")) o.getDouble("soh") else null,
                    if (o.has("spreadMv")) o.getDouble("spreadMv") else null,
                    o.optInt("dtcCount", 0), o.optString("pdfPath"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(list: List<InspectionRecord>) {
        val arr = JSONArray()
        for (r in list) {
            val o = JSONObject()
            o.put("id", r.id)
            o.put("ts", r.ts)
            o.put("vin", r.vin)
            o.put("profile", r.profile)
            o.put("client", r.client)
            o.put("mileage", r.mileage)
            o.put("price", r.price)
            o.put("level", r.level)
            if (r.soh != null) o.put("soh", r.soh)
            if (r.spreadMv != null) o.put("spreadMv", r.spreadMv)
            o.put("dtcCount", r.dtcCount)
            o.put("pdfPath", r.pdfPath)
            arr.put(o)
        }
        file.writeText(arr.toString())
    }
}
