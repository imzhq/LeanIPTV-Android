package com.lean.iptv

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class Kind(val label: String, val catAction: String, val itemAction: String) {
    LIVE("Live TV", "get_live_categories", "get_live_streams"),
    MOVIES("Movies", "get_vod_categories", "get_vod_streams"),
    SERIES("Series", "get_series_categories", "get_series")
}

data class Category(val id: String, val name: String)
data class Item(val id: String, val name: String, val ext: String, val key: String)
data class Episode(val id: String, val season: Int, val number: Int, val title: String, val ext: String)

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

private fun httpGet(url: String, timeoutMs: Int = 25000): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        val code = conn.responseCode
        if (code != 200) throw IOException("HTTP $code")
        return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

// Reads a field whether the provider sent it as text or as a number
private fun JSONObject.str(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = opt(key)?.toString()
    return if (v.isNullOrEmpty()) null else v
}

class Api(host: String, username: String, password: String) {
    private val base: String
    private val u = username.trim()
    private val p = password.trim()

    init {
        var h = host.trim()
        if (!h.lowercase().startsWith("http")) h = "http://$h"
        while (h.endsWith("/")) h = h.dropLast(1)
        base = h
    }

    private fun apiUrl(action: String? = null, extra: List<Pair<String, String>> = emptyList()): String {
        val sb = StringBuilder("$base/player_api.php?username=${enc(u)}&password=${enc(p)}")
        if (action != null) sb.append("&action=").append(enc(action))
        for ((k, v) in extra) sb.append("&").append(k).append("=").append(enc(v))
        return sb.toString()
    }

    suspend fun login(): Boolean = withContext(Dispatchers.IO) {
        val json = JSONObject(httpGet(apiUrl()))
        json.optJSONObject("user_info")?.opt("auth")?.toString() == "1"
    }

    suspend fun categories(kind: Kind): List<Category> {
        val text = withContext(Dispatchers.IO) { httpGet(apiUrl(kind.catAction)) }
        return withContext(Dispatchers.Default) {
            val arr = JSONArray(text)
            val out = ArrayList<Category>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.str("category_id") ?: continue
                out.add(Category(id, o.str("category_name") ?: "Unnamed"))
            }
            out
        }
    }

    suspend fun items(kind: Kind, categoryId: String?): List<Item> {
        val extra = if (categoryId != null) listOf("category_id" to categoryId) else emptyList()
        val text = withContext(Dispatchers.IO) { httpGet(apiUrl(kind.itemAction, extra), 90000) }
        return withContext(Dispatchers.Default) {
            val arr = JSONArray(text)
            val out = ArrayList<Item>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.str("stream_id") ?: o.str("series_id") ?: continue
                val name = o.str("name") ?: "Unknown"
                out.add(Item(id, name, o.str("container_extension") ?: "mp4", name.lowercase()))
            }
            out
        }
    }

    suspend fun episodes(seriesId: String): List<Episode> {
        val text = withContext(Dispatchers.IO) {
            httpGet(apiUrl("get_series_info", listOf("series_id" to seriesId)))
        }
        return withContext(Dispatchers.Default) {
            val root = JSONObject(text)
            val out = ArrayList<Episode>()

            fun add(raw: Any?, seasonHint: Int) {
                val o = raw as? JSONObject ?: return
                val id = o.str("id") ?: return
                val number = o.str("episode_num")?.toDoubleOrNull()?.toInt() ?: 0
                val season = o.str("season")?.toDoubleOrNull()?.toInt() ?: seasonHint
                out.add(
                    Episode(
                        id, season, number,
                        o.str("title") ?: "Episode $number",
                        o.str("container_extension") ?: "mp4"
                    )
                )
            }

            val eps = root.opt("episodes")
            if (eps is JSONObject) {
                val keys = eps.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val arr = eps.optJSONArray(k) ?: continue
                    for (i in 0 until arr.length()) add(arr.opt(i), k.toIntOrNull() ?: 0)
                }
            } else if (eps is JSONArray) {
                for (i in 0 until eps.length()) {
                    val inner = eps.opt(i)
                    if (inner is JSONArray) {
                        for (j in 0 until inner.length()) add(inner.opt(j), i + 1)
                    } else {
                        add(inner, 0)
                    }
                }
            }
            out.sortedWith(compareBy<Episode>({ it.season }, { it.number }))
        }
    }

    // Raw .ts feed for live (lowest delay)
    fun liveUrl(item: Item) = "$base/live/$u/$p/${item.id}.ts"
    fun movieUrl(item: Item) = "$base/movie/$u/$p/${item.id}.${item.ext}"
    fun episodeUrl(ep: Episode) = "$base/series/$u/$p/${ep.id}.${ep.ext}"
}

class AppState(private val prefs: SharedPreferences) {
    var host by mutableStateOf(prefs.getString("host", "") ?: "")
    var username by mutableStateOf(prefs.getString("user", "") ?: "")
    var password by mutableStateOf(prefs.getString("pass", "") ?: "")
    var dataSaver by mutableStateOf(prefs.getBoolean("data_saver", true))

    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    var kind by mutableStateOf(Kind.LIVE)
    var query by mutableStateOf("")

    var items by mutableStateOf<List<Item>>(emptyList())
    var itemsKey by mutableStateOf("")
    var itemsQuery by mutableStateOf("")

    val categories = mutableStateMapOf<Kind, List<Category>>()
    val library = mutableStateMapOf<Kind, List<Item>>()
    val libraryLoading = mutableStateMapOf<Kind, Boolean>()
    var libraryError by mutableStateOf<String?>(null)
    val episodeCache = mutableStateMapOf<String, List<Episode>>()

    var api: Api? = null

    suspend fun login(): Boolean {
        loading = true
        error = null
        try {
            val a = Api(host, username, password)
            if (!a.login()) {
                error = "Login failed. Check the URL, username and password."
                return false
            }
            api = a
            prefs.edit()
                .putString("host", host)
                .putString("user", username)
                .putString("pass", password)
                .apply()
            categories[Kind.LIVE] = a.categories(Kind.LIVE)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Could not connect: ${e.message}"
            return false
        } finally {
            loading = false
        }
    }

    suspend fun loadCategories(k: Kind) {
        val a = api ?: return
        if (categories[k] != null) return
        try {
            categories[k] = a.categories(k)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Categories failed: ${e.message}"
        }
    }

    suspend fun loadItems(k: Kind, cat: Category) {
        val a = api ?: return
        val key = "${k.name}:${cat.id}"
        if (itemsKey == key && items.isNotEmpty()) return
        loading = true
        error = null
        items = emptyList()
        itemsQuery = ""
        try {
            items = a.items(k, cat.id)
            itemsKey = key
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Loading failed: ${e.message}"
        } finally {
            loading = false
        }
    }

    // Downloads a section's whole list once, so the search box can look through all of it
    suspend fun loadLibrary(k: Kind) {
        val a = api ?: return
        if (library[k] != null || libraryLoading[k] == true) return
        libraryLoading[k] = true
        libraryError = null
        try {
            library[k] = a.items(k, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            libraryError = "Could not load the search list: ${e.message}"
        } finally {
            libraryLoading[k] = false
        }
    }

    fun setSaver(v: Boolean) {
        dataSaver = v
        prefs.edit().putBoolean("data_saver", v).apply()
    }

    fun logout() {
        api = null
        categories.clear()
        library.clear()
        episodeCache.clear()
        items = emptyList()
        itemsKey = ""
        query = ""
    }
}

// END OF FILE
