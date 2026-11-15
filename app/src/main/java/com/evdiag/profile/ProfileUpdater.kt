package com.evdiag.profile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Скачивает профили автомобилей по адресу вида https://.../profiles/
 * В этой папке должен лежать index.json: {"files": ["kia_niro.json", "byd_atto3.json"]}
 * Можно использовать репозиторий GitHub (raw.githubusercontent.com).
 */
object ProfileUpdater {
    private val safeName = Regex("[A-Za-z0-9_.-]{1,60}\\.json")

    suspend fun update(ctx: Context, baseUrl: String): Int = withContext(Dispatchers.IO) {
        val trimmed = baseUrl.trim()
        require(trimmed.startsWith("https://")) { "Адрес должен начинаться с https://" }
        val base = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        val index = JSONObject(get(base + "index.json"))
        val files = index.getJSONArray("files")
        val dir = ProfileRepository.userDir(ctx)
        var count = 0
        for (i in 0 until files.length()) {
            val name = files.getString(i)
            require(safeName.matches(name)) { "Недопустимое имя файла: $name" }
            val text = get(base + name)
            val o = JSONObject(text)
            require(o.has("id") && o.has("name")) { "В $name нет id или name" }
            File(dir, name).writeText(text)
            count++
        }
        count
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}: $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
