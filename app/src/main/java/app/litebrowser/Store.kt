package app.litebrowser

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntimeSettings

data class Entry(val title: String, val url: String, val time: Long = 0L)

val ENGINES = linkedMapOf(
    "DuckDuckGo" to "https://duckduckgo.com/?q=%s",
    "Google" to "https://www.google.com/search?q=%s",
    "Bing" to "https://www.bing.com/search?q=%s",
    "Startpage" to "https://www.startpage.com/do/search?q=%s"
)

class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("lite_browser", Context.MODE_PRIVATE)

    var engine: String
        get() = p.getString("engine", "DuckDuckGo") ?: "DuckDuckGo"
        set(v) { p.edit().putString("engine", v).apply() }
    var theme: Int                       // 0 = system, 1 = light, 2 = dark
        get() = p.getInt("theme", 0)
        set(v) { p.edit().putInt("theme", v).apply() }
    var strict: Boolean
        get() = p.getBoolean("strict", false)
        set(v) { p.edit().putBoolean("strict", v).apply() }
    var httpsOnly: Boolean
        get() = p.getBoolean("https_only", true)
        set(v) { p.edit().putBoolean("https_only", v).apply() }
    var clearOnExit: Boolean
        get() = p.getBoolean("clear_on_exit", false)
        set(v) { p.edit().putBoolean("clear_on_exit", v).apply() }
    var selectedTab: Int
        get() = p.getInt("sel_tab", 0)
        set(v) { p.edit().putInt("sel_tab", v).apply() }

    fun colorScheme(): Int = when (theme) {
        1 -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
        2 -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
        else -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
    }

    fun load(key: String): MutableList<Entry> = try {
        val a = JSONArray(p.getString(key, "[]"))
        val out = ArrayList<Entry>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out.add(Entry(o.optString("t"), o.getString("u"), o.optLong("d")))
        }
        out
    } catch (e: Exception) {
        ArrayList()
    }

    fun save(key: String, list: List<Entry>) {
        val a = JSONArray()
        for (e in list) a.put(JSONObject().put("t", e.title).put("u", e.url).put("d", e.time))
        p.edit().putString(key, a.toString()).apply()
    }

    fun addHistory(title: String, url: String) {
        val h = load("history")
        if (h.isNotEmpty() && h.last().url == url) h.removeAt(h.size - 1)
        h.add(Entry(title.ifBlank { url }, url, System.currentTimeMillis()))
        while (h.size > 500) h.removeAt(0)
        save("history", h)
    }

    fun isBookmarked(url: String) = load("bookmarks").any { it.url == url }

    /** returns true when the page was added, false when it was removed */
    fun toggleBookmark(title: String, url: String): Boolean {
        val b = load("bookmarks")
        val had = b.removeAll { it.url == url }
        if (!had) b.add(0, Entry(title.ifBlank { url }, url, System.currentTimeMillis()))
        save("bookmarks", b)
        return !had
    }
}

private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")

fun hostOf(url: String): String = try { Uri.parse(url).host ?: "" } catch (e: Exception) { "" }

fun isLocalHost(h: String) =
    h == "localhost" || h.endsWith(".local") || h.startsWith("127.") || h.startsWith("192.168.") ||
        h.startsWith("10.") || Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\..*").matches(h)

/** "youtube.com" -> https://youtube.com | "cats" -> search | full link -> unchanged */
fun smartUrl(raw: String, engine: String): String {
    val t = raw.trim()
    if (t.isEmpty()) return ""
    if (SCHEME.matches(t) || t.startsWith("about:") || t.startsWith("data:")) return t
    if (!t.contains(' ') && (t.contains('.') || t.startsWith("localhost"))) return "https://$t"
    return (ENGINES[engine] ?: ENGINES.getValue("DuckDuckGo")).replace("%s", Uri.encode(t))
}
