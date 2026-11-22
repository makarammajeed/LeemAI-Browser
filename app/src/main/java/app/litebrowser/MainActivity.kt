package app.litebrowser

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.io.File

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val PRIVATE_BAR = 0xFF3B2A5C.toInt()

private class Pal(
    val bg: Int, val bar: Int, val pill: Int, val txt: Int, val sub: Int,
    val acc: Int, val line: Int, val good: Int, val bad: Int
)

private val LIGHT = Pal(0xFFFFFFFF.toInt(), 0xFFF6F7F9.toInt(), 0xFFE9ECEF.toInt(), 0xFF1B1F23.toInt(),
    0xFF6B7280.toInt(), 0xFF2563EB.toInt(), 0xFFD9DEE5.toInt(), 0xFF16A34A.toInt(), 0xFFDC2626.toInt())
private val DARK = Pal(0xFF121417.toInt(), 0xFF1C1F24.toInt(), 0xFF2A2E35.toInt(), 0xFFE8EAED.toInt(),
    0xFF9AA0A6.toInt(), 0xFF8AB4F8.toInt(), 0xFF2F343B.toInt(), 0xFF81C995.toInt(), 0xFFF28B82.toInt())

private val DEFAULT_TILES = listOf(
    "Wikipedia" to "https://www.wikipedia.org",
    "YouTube" to "https://www.youtube.com",
    "GitHub" to "https://github.com",
    "Google" to "https://www.google.com"
)
private val TILE_COLORS = intArrayOf(0xFF2563EB.toInt(), 0xFF7C3AED.toInt(), 0xFF059669.toInt(),
    0xFFDB2777.toInt(), 0xFFEA580C.toInt(), 0xFF0891B2.toInt())

class Tab(val session: GeckoSession, val isPrivate: Boolean) {
    var title = ""
    var url = ""
    var progress = 0
    var loading = false
    var secure = false
    var canBack = false
    var canFwd = false
    var home = true
    var desktop = false
    var lazyUrl: String? = null
    var opener: Tab? = null
}

private class RowAdapter<T>(var items: List<T>, val bind: (T) -> View) : BaseAdapter() {
    override fun getCount() = items.size
    override fun getItem(position: Int): Any = items[position] as Any
    override fun getItemId(position: Int) = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View = bind(items[position])
}

class MainActivity : Activity() {
    private lateinit var app: BrowserApp
    private val store: Store get() = app.store
    private val runtime get() = app.runtime
    private val handler = Handler(Looper.getMainLooper())
    private val tabs = ArrayList<Tab>()
    private var cur: Tab? = null
    private val allowedHttp = HashSet<String>()
    private var P = LIGHT
    private var fullscreen = false
    private var exiting = false

    private lateinit var root: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var progressHolder: FrameLayout
    private lateinit var progressFill: View
    private lateinit var sepLine: View
    private lateinit var bottomBar: LinearLayout
    private lateinit var geckoView: GeckoView
    private lateinit var startPage: View
    private lateinit var tilesBox: LinearLayout
    private lateinit var statusLine: TextView
    private lateinit var address: EditText
    private lateinit var lockIcon: ImageView
    private lateinit var starBtn: ImageView
    private lateinit var reloadBtn: ImageView
    private lateinit var backBtn: ImageView
    private lateinit var fwdBtn: ImageView
    private lateinit var tabsCount: TextView
    private var uiBuilt = false

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as BrowserApp
        buildUi()
        val saved = store.load("tabs")
        for (e in saved.take(8)) {
            val t = newTab(e.url, false, false, true)
            t.title = e.title
        }
        val external = intent?.dataString
        if (external != null) {
            newTab(external)
        } else if (tabs.isEmpty()) {
            newTab()
        } else {
            select(tabs[store.selectedTab.coerceIn(0, tabs.size - 1)])
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.dataString?.let { newTab(it) }
    }

    override fun onPause() {
        super.onPause()
        if (!exiting) saveTabs()
    }

    override fun onDestroy() {
        super.onDestroy()
        for (t in tabs) try { t.session.close() } catch (e: Exception) { }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (store.theme == 0) applyTheme()
    }

    override fun onBackPressed() {
        val t = cur
        when {
            fullscreen -> t?.session?.exitFullScreen()
            address.hasFocus() -> { address.clearFocus(); hideKeyboard() }
            t == null -> super.onBackPressed()
            t.canBack && !t.home -> t.session.goBack()
            t.opener != null && tabs.contains(t.opener) -> closeTab(t)
            else -> moveTaskToBack(true)
        }
    }

    private fun saveTabs() {
        val keep = tabs.filter { !it.isPrivate && !it.home }
        store.save("tabs", keep.map { Entry(it.title, it.lazyUrl ?: it.url) })
        store.selectedTab = keep.indexOf(cur).coerceAtLeast(0)
    }

    // ------------------------------------------------------------------ small helpers
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun imm() = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private fun hideKeyboard() { imm().hideSoftInputFromWindow(address.windowToken, 0) }

    private fun isDark() = when (store.theme) {
        1 -> false
        2 -> true
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun dialogTheme() =
        if (isDark()) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert

    private fun rect(color: Int, radius: Int, strokeW: Int = 0, strokeC: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (strokeW > 0) setStroke(dp(strokeW), strokeC)
    }

    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun ripple(borderless: Boolean): Drawable? {
        val tv = TypedValue()
        theme.resolveAttribute(
            if (borderless) android.R.attr.selectableItemBackgroundBorderless else android.R.attr.selectableItemBackground,
            tv, true
        )
        return getDrawable(tv.resourceId)
    }

    private fun iconBtn(res: Int, desc: String, tint: Int = P.txt, pad: Int = 11, click: () -> Unit): ImageView =
        ImageView(this).apply {
            setImageResource(res)
            setColorFilter(tint)
            contentDescription = desc
            setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
            background = ripple(true)
            setOnClickListener { click() }
        }

    private fun applySystemUi() {
        window.statusBarColor = P.bar
        window.navigationBarColor = P.bar
        var f = View.SYSTEM_UI_FLAG_VISIBLE
        if (!isDark()) {
            f = f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = f
    }

    // ------------------------------------------------------------------ main UI
    private fun buildUi() {
        P = if (isDark()) DARK else LIGHT
        applySystemUi()
        fullscreen = false

        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(P.bg) }

        // address pill
        lockIcon = ImageView(this).apply { setPadding(dp(12), dp(14), dp(6), dp(14)); setImageResource(R.drawable.ic_search); setColorFilter(P.sub) }
        address = EditText(this).apply {
            background = null
            isSingleLine = true
            textSize = 15f
            setTextColor(P.txt)
            setHintTextColor(P.sub)
            hint = "Search or type address"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(dp(4), 0, dp(4), 0)
            setOnFocusChangeListener { _, has ->
                val t = cur
                if (has) {
                    setText(if (t != null && !t.home && !t.url.startsWith("data:")) t.url else "")
                    post { selectAll() }
                } else {
                    updateAddressText()
                }
            }
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) { go(text.toString()); true } else false
            }
        }
        starBtn = iconBtn(R.drawable.ic_star_border, "Bookmark this page", P.sub, 10) { toggleBookmark() }
        reloadBtn = iconBtn(R.drawable.ic_refresh, "Reload", P.txt, 10) {
            cur?.let { if (it.loading) it.session.stop() else it.session.reload() }
        }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rect(P.pill, 24)
        }
        pill.addView(lockIcon, LinearLayout.LayoutParams(dp(38), dp(46)))
        pill.addView(address, LinearLayout.LayoutParams(0, dp(46), 1f))
        pill.addView(starBtn, LinearLayout.LayoutParams(dp(42), dp(46)))
        pill.addView(reloadBtn, LinearLayout.LayoutParams(dp(42), dp(46)))

        topBar = LinearLayout(this).apply { setPadding(dp(10), dp(6), dp(10), dp(6)); setBackgroundColor(P.bar) }
        topBar.addView(pill, LinearLayout.LayoutParams(MATCH, dp(46)))

        progressHolder = FrameLayout(this).apply { setBackgroundColor(P.bar) }
        progressFill = View(this).apply { setBackgroundColor(P.acc); pivotX = 0f; scaleX = 0f; visibility = View.INVISIBLE }
        progressHolder.addView(progressFill, FrameLayout.LayoutParams(MATCH, MATCH))

        // content area
        val content = FrameLayout(this)
        geckoView = GeckoView(this)
        content.addView(geckoView, FrameLayout.LayoutParams(MATCH, MATCH))
        startPage = buildStartPage()
        content.addView(startPage, FrameLayout.LayoutParams(MATCH, MATCH))

        // bottom bar
        sepLine = View(this).apply { setBackgroundColor(P.line) }
        bottomBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(P.bar) }
        backBtn = iconBtn(R.drawable.ic_back, "Back", P.txt, 14) { cur?.let { if (it.canBack) it.session.goBack() } }
        fwdBtn = iconBtn(R.drawable.ic_forward, "Forward", P.txt, 14) { cur?.let { if (it.canFwd) it.session.goForward() } }
        val homeBtn = iconBtn(R.drawable.ic_home, "Home", P.txt, 14) { cur?.let { it.home = true; updateUi() } }
        tabsCount = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(P.txt)
            background = rect(Color.TRANSPARENT, 6, 2, P.txt)
        }
        val tabsBtn = FrameLayout(this).apply {
            background = ripple(true)
            contentDescription = "Tabs"
            setOnClickListener { showTabs() }
        }
        tabsBtn.addView(tabsCount, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        val menuBtn = iconBtn(R.drawable.ic_menu, "Menu", P.txt, 14) { showMenu() }
        for (v in listOf<View>(backBtn, fwdBtn, homeBtn, tabsBtn, menuBtn)) {
            bottomBar.addView(v, LinearLayout.LayoutParams(0, dp(54), 1f))
        }

        root.addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(progressHolder, LinearLayout.LayoutParams(MATCH, dp(3)))
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(sepLine, LinearLayout.LayoutParams(MATCH, dp(1)))
        root.addView(bottomBar, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)
        uiBuilt = true
    }

    private fun applyTheme() {
        try { geckoView.releaseSession() } catch (e: Exception) { }
        buildUi()
        cur?.let { select(it) }
    }

    private fun buildStartPage(): View {
        val sv = ScrollView(this).apply { setBackgroundColor(P.bg); isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(56), dp(20), dp(24))
        }
        val logo = SpannableString("Lite Browser").apply {
            setSpan(ForegroundColorSpan(P.acc), 5, 12, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        col.addView(TextView(this).apply { text = logo; textSize = 32f; typeface = Typeface.DEFAULT_BOLD; setTextColor(P.txt) })
        col.addView(TextView(this).apply {
            text = "Fast  \u00B7  Private  \u00B7  Yours"
            textSize = 13f; setTextColor(P.sub); setPadding(0, dp(4), 0, dp(24))
        })
        val search = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rect(P.pill, 26)
            isClickable = true
            setOnClickListener { focusAddress() }
        }
        search.addView(ImageView(this).apply { setImageResource(R.drawable.ic_search); setColorFilter(P.sub); setPadding(dp(16), 0, dp(8), 0) },
            LinearLayout.LayoutParams(dp(48), dp(52)))
        search.addView(TextView(this).apply { text = "Search or type address"; textSize = 15f; setTextColor(P.sub) })
        col.addView(search, LinearLayout.LayoutParams(MATCH, dp(52)))
        tilesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(28), 0, 0) }
        col.addView(tilesBox, LinearLayout.LayoutParams(MATCH, WRAP))
        statusLine = TextView(this).apply { textSize = 12f; setTextColor(P.sub); gravity = Gravity.CENTER; setPadding(0, dp(28), 0, 0) }
        col.addView(statusLine, LinearLayout.LayoutParams(MATCH, WRAP))
        sv.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        return sv
    }

    private fun tile(title: String, url: String): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            background = ripple(false)
            isClickable = true
            setOnClickListener { openInCurrent(url) }
        }
        val letter = (hostOf(url).removePrefix("www.").firstOrNull() ?: title.firstOrNull() ?: '\u2022').uppercaseChar().toString()
        c.addView(TextView(this).apply {
            text = letter; gravity = Gravity.CENTER; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = oval(TILE_COLORS[(url.hashCode() and 0x7fffffff) % TILE_COLORS.size])
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        c.addView(TextView(this).apply {
            text = title; textSize = 11f; setTextColor(P.txt); maxLines = 1
            ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
        }, LinearLayout.LayoutParams(dp(72), WRAP))
        return c
    }

    private fun fillTiles() {
        tilesBox.removeAllViews()
        val items = ArrayList<Pair<String, String>>()
        for (b in store.load("bookmarks").take(8)) items.add(b.title to b.url)
        for (d in DEFAULT_TILES) if (items.size < 4 && items.none { it.second == d.second }) items.add(d)
        for (row in items.chunked(4)) {
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
            for (p in row) r.addView(tile(p.first, p.second), LinearLayout.LayoutParams(0, WRAP, 1f))
            repeat(4 - row.size) { r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            tilesBox.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        statusLine.text = "Tracking protection: ${if (store.strict) "Strict" else "Standard"}   \u2022   " +
            "HTTPS-only: ${if (store.httpsOnly) "On" else "Off"}"
    }

    // ------------------------------------------------------------------ UI state
    private fun displayUrl(u: String): String {
        if (u.startsWith("data:")) return ""
        val h = hostOf(u)
        return if (h.isEmpty()) u else h.removePrefix("www.")
    }

    private fun updateAddressText() {
        val t = cur ?: return
        if (address.hasFocus()) return
        address.setText(if (t.home) "" else displayUrl(t.url))
    }

    private fun updateProgress(force: Boolean) {
        val t = cur ?: return
        val show = t.loading || force
        if (show) {
            progressFill.visibility = View.VISIBLE
            progressFill.animate().scaleX(t.progress / 100f).setDuration(150).start()
        } else {
            progressFill.animate().cancel()
            progressFill.scaleX = 0f
            progressFill.visibility = View.INVISIBLE
        }
    }

    private fun updateUi() {
        val t = cur ?: return
        startPage.visibility = if (t.home) View.VISIBLE else View.GONE
        geckoView.visibility = if (t.home) View.INVISIBLE else View.VISIBLE
        if (t.home) fillTiles()
        updateAddressText()
        topBar.setBackgroundColor(if (t.isPrivate) PRIVATE_BAR else P.bar)
        val web = t.url.startsWith("http")
        lockIcon.setImageResource(if (t.home || !web) R.drawable.ic_search else R.drawable.ic_lock)
        lockIcon.setColorFilter(if (t.home || !web) P.sub else if (t.secure) P.good else P.bad)
        reloadBtn.setImageResource(if (t.loading) R.drawable.ic_close else R.drawable.ic_refresh)
        val marked = !t.home && web && store.isBookmarked(t.url)
        starBtn.setImageResource(if (marked) R.drawable.ic_star else R.drawable.ic_star_border)
        starBtn.setColorFilter(if (marked) P.acc else P.sub)
        backBtn.alpha = if (t.canBack) 1f else 0.35f
        fwdBtn.alpha = if (t.canFwd) 1f else 0.35f
        tabsCount.text = tabs.size.toString()
        updateProgress(false)
    }

    private fun focusAddress() {
        address.requestFocus()
        address.post { address.selectAll() }
        imm().showSoftInput(address, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun go(text: String) {
        val t = cur ?: return
        val u = smartUrl(text, store.engine)
        if (u.isEmpty()) return
        t.home = false
        t.url = u
        t.lazyUrl = null
        t.session.loadUri(u)
        address.clearFocus()
        hideKeyboard()
        geckoView.requestFocus()
        updateUi()
    }

    private fun openInCurrent(url: String) {
        val t = cur ?: newTab()
        t.home = false
        t.url = url
        t.lazyUrl = null
        t.session.loadUri(url)
        updateUi()
    }

    // ------------------------------------------------------------------ tabs
    private fun makeSession(priv: Boolean): GeckoSession =
        GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(priv).build())

    private fun newTab(url: String? = null, priv: Boolean = false, select: Boolean = true, lazy: Boolean = false): Tab {
        val t = Tab(makeSession(priv), priv)
        wire(t)
        t.session.open(runtime)
        tabs.add(t)
        if (url != null) {
            t.home = false
            t.url = url
            if (lazy) t.lazyUrl = url else t.session.loadUri(url)
        }
        if (select) select(t) else t.session.setActive(false)
        return t
    }

    private fun select(t: Tab) {
        val old = cur
        if (old != null && old !== t) try { old.session.setActive(false) } catch (e: Exception) { }
        cur = t
        t.lazyUrl?.let { t.session.loadUri(it); t.lazyUrl = null }
        geckoView.setSession(t.session)
        t.session.setActive(true)
        if (t.isPrivate) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        updateUi()
    }

    private fun closeTab(t: Tab) {
        val i = tabs.indexOf(t)
        if (i < 0) return
        val wasCurrent = cur === t
        if (wasCurrent) try { geckoView.releaseSession() } catch (e: Exception) { }
        try { t.session.setActive(false); t.session.close() } catch (e: Exception) { }
        tabs.removeAt(i)
        if (tabs.isEmpty()) { cur = null; newTab(); return }
        if (wasCurrent) {
            val o = t.opener
            select(if (o != null && tabs.contains(o)) o else tabs[minOf(i, tabs.size - 1)])
        } else updateUi()
    }

    private fun attachWhenOpen(t: Tab, tries: Int = 0) {
        if (!tabs.contains(t)) return
        if (t.session.isOpen) { select(t); return }
        if (tries < 60) handler.postDelayed({ attachWhenOpen(t, tries + 1) }, 50) else tabs.remove(t)
    }

    private fun recover(t: Tab) {
        handler.post {
            try {
                t.session.open(runtime)
                if (t === cur) geckoView.setSession(t.session)
                if (t.url.isNotEmpty()) t.session.loadUri(t.url)
                toast("Tab crashed - reloading")
            } catch (e: Exception) { }
        }
    }

    private fun toggleDesktop(t: Tab) {
        t.desktop = !t.desktop
        t.session.settings.setUserAgentMode(
            if (t.desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
        t.session.settings.setViewportMode(
            if (t.desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
        t.session.reload()
        toast(if (t.desktop) "Desktop site" else "Mobile site")
    }

    private fun toggleBookmark() {
        val t = cur ?: return
        if (t.home || !t.url.startsWith("http")) return
        val added = store.toggleBookmark(t.title, t.url)
        toast(if (added) "Bookmark added" else "Bookmark removed")
        updateUi()
    }

    private fun share(url: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        startActivity(Intent.createChooser(i, "Share link"))
    }

    private fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", text))
        toast("Copied")
    }

    // ------------------------------------------------------------------ Gecko delegates
    private fun wire(t: Tab) {
        val s = t.session

        s.setProgressDelegate(object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                t.loading = true; t.progress = 5
                if (t === cur) updateUi()
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                t.loading = false; t.progress = 100
                val u = t.url
                if (success && !t.isPrivate && (u.startsWith("http://") || u.startsWith("https://"))) store.addHistory(t.title, u)
                if (t === cur) {
                    updateProgress(true)
                    handler.postDelayed({ if (t === cur) updateUi() }, 250)
                }
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                t.progress = progress
                if (t === cur) updateProgress(false)
            }

            override fun onSecurityChange(session: GeckoSession, securityInfo: GeckoSession.ProgressDelegate.SecurityInformation) {
                t.secure = securityInfo.isSecure
                if (t === cur) updateUi()
            }
        })

        s.setNavigationDelegate(object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession, url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean
            ) {
                if (url != null && url != "about:blank") { t.url = url; t.home = false }
                if (t === cur) updateUi()
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                t.canBack = canGoBack
                if (t === cur) updateUi()
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                t.canFwd = canGoForward
                if (t === cur) updateUi()
            }

            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                val u = request.uri
                val deny = GeckoResult.fromValue(AllowOrDeny.DENY)
                if (u.startsWith("lite://allow-http/")) {
                    val target = Uri.decode(u.removePrefix("lite://allow-http/"))
                    allowedHttp.add(hostOf(target))
                    handler.post { session.loadUri(target) }
                    return deny
                }
                val scheme = (Uri.parse(u).scheme ?: "").lowercase()
                if (scheme == "http") {
                    val host = hostOf(u)
                    if (store.httpsOnly && !isLocalHost(host) && !allowedHttp.contains(host)) {
                        handler.post { session.loadUri("https://" + u.removePrefix("http://")) }
                        return deny
                    }
                    return null
                }
                if (scheme.isEmpty() || scheme in setOf("https", "about", "data", "blob", "javascript", "resource", "view-source", "moz-extension")) return null
                try {
                    val i = if (scheme == "intent") Intent.parseUri(u, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(u))
                    i.addCategory(Intent.CATEGORY_BROWSABLE)
                    i.component = null
                    i.selector = null
                    startActivity(i)
                } catch (e: Exception) {
                    toast("No app can open this link")
                }
                return deny
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                val parent = tabs.firstOrNull { it.session === session }
                val nt = Tab(makeSession(parent?.isPrivate ?: false), parent?.isPrivate ?: false)
                nt.home = false
                nt.opener = parent
                wire(nt)
                tabs.add(nt)
                attachWhenOpen(nt)
                return GeckoResult.fromValue(nt.session)
            }

            override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? =
                GeckoResult.fromValue(errorPage(uri))
        })

        s.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                t.title = title ?: ""
            }

            override fun onCloseRequest(session: GeckoSession) {
                handler.post { closeTab(t) }
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                setFullscreen(fullScreen)
            }

            override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: GeckoSession.ContentDelegate.ContextElement) {
                val link = element.linkUri
                val img = if (element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE) element.srcUri else null
                if (link != null || img != null) showContext(link, img)
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                saveDownload(response)
            }

            override fun onCrash(session: GeckoSession) { recover(t) }
            override fun onKill(session: GeckoSession) { recover(t) }
        })
    }

    private fun errorPage(uri: String?): String {
        val u = uri ?: ""
        val dark = isDark()
        val bg = if (dark) "#121417" else "#ffffff"
        val fg = if (dark) "#e8eaed" else "#1b1f23"
        val acc = if (dark) "#8ab4f8" else "#2563eb"
        val safe = TextUtils.htmlEncode(u)
        val http = if (store.httpsOnly && u.startsWith("https://")) "http://" + u.removePrefix("https://") else null
        val extra = if (http != null)
            "<p><a style='color:#dc2626' href='lite://allow-http/${Uri.encode(http)}'>Try the insecure http:// version</a></p>" else ""
        val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<style>body{margin:0;background:$bg;color:$fg;font-family:sans-serif}.w{padding:64px 24px;max-width:520px;margin:auto}" +
            "p{opacity:.8;word-break:break-all}.b{display:inline-block;margin-top:12px;padding:12px 22px;border-radius:24px;" +
            "background:$acc;color:#fff;text-decoration:none}</style></head><body><div class='w'><h1>Can\u2019t open this page</h1>" +
            "<p>$safe</p><p>Check the address and your connection, then try again.</p>" +
            "<a class='b' href='$safe'>Try again</a>$extra</div></body></html>"
        return "data:text/html;charset=utf-8," + Uri.encode(html)
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        val v = if (on) View.GONE else View.VISIBLE
        topBar.visibility = v; progressHolder.visibility = v; sepLine.visibility = v; bottomBar.visibility = v
        if (on) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else applySystemUi()
    }

    private fun showContext(link: String?, img: String?) {
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        if (link != null) {
            labels.add("Open in new tab"); acts.add { newTab(link) }
            labels.add("Open in private tab"); acts.add { newTab(link, true) }
            labels.add("Copy link"); acts.add { copy(link) }
            labels.add("Share link"); acts.add { share(link) }
        }
        if (img != null) {
            labels.add("Open image in new tab"); acts.add { newTab(img) }
            labels.add("Copy image address"); acts.add { copy(img) }
        }
        AlertDialog.Builder(this, dialogTheme())
            .setItems(labels.toTypedArray()) { d, i -> d.dismiss(); acts[i]() }
            .show()
    }

    // ------------------------------------------------------------------ downloads
    private fun downloadsDir(): File = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(filesDir, "downloads")

    private fun saveDownload(r: WebResponse) {
        val body = r.body ?: return
        val cd = r.headers.entries.firstOrNull { it.key.equals("content-disposition", true) }?.value
        var name: String? = null
        if (cd != null) {
            Regex("filename\\*=(?:UTF-8|utf-8)''([^;]+)").find(cd)?.let { name = Uri.decode(it.groupValues[1]) }
            if (name == null) Regex("filename=\"?([^\";]+)\"?").find(cd)?.let { name = it.groupValues[1] }
        }
        if (name == null) name = Uri.parse(r.uri).lastPathSegment
        val clean = (name ?: "download").trim().ifEmpty { "download" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        toast("Downloading $clean")
        Thread {
            try {
                val dir = downloadsDir().apply { mkdirs() }
                val dot = clean.lastIndexOf('.')
                val base = if (dot > 0) clean.substring(0, dot) else clean
                val ext = if (dot > 0) clean.substring(dot) else ""
                var f = File(dir, clean)
                var n = 1
                while (f.exists()) { f = File(dir, "$base($n)$ext"); n++ }
                body.use { inp -> f.outputStream().use { out -> inp.copyTo(out) } }
                runOnUiThread { toast("Saved: ${f.name}  (Menu > Downloads)") }
            } catch (e: Exception) {
                runOnUiThread { toast("Download failed") }
            }
        }.start()
    }

    private fun openFile(f: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("No app can open this file")
        }
    }

    // ------------------------------------------------------------------ dialogs
    private fun sheet(build: (LinearLayout, Dialog) -> Unit) {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(14))
            val r = dp(22).toFloat()
            background = GradientDrawable().apply { setColor(P.bar); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        }
        build(col, d)
        d.setContentView(ScrollView(this).apply { addView(col) })
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(MATCH, WRAP)
            setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        d.show()
    }

    private fun sheetRow(icon: Int, label: String, sub: String? = null, trailing: View? = null, onClick: () -> Unit): View {
        val r = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(13), dp(22), dp(13))
            background = ripple(false)
            isClickable = true
            setOnClickListener { onClick() }
        }
        r.addView(ImageView(this).apply { setImageResource(icon); setColorFilter(P.txt) }, LinearLayout.LayoutParams(dp(24), dp(24)))
        val tx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(8), 0) }
        tx.addView(TextView(this).apply { text = label; textSize = 15f; setTextColor(P.txt) })
        if (sub != null) tx.addView(TextView(this).apply { text = sub; textSize = 12f; setTextColor(P.sub) })
        r.addView(tx, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (trailing != null) r.addView(trailing)
        return r
    }

    private fun tickView(on: Boolean): View =
        TextView(this).apply { text = if (on) "\u2713" else ""; textSize = 18f; setTextColor(P.acc); typeface = Typeface.DEFAULT_BOLD }

    private fun switchView(on: Boolean) = Switch(this).apply { isChecked = on; isClickable = false }

    private fun showMenu() {
        val t = cur ?: return
        sheet { col, d ->
            fun item(icon: Int, label: String, trailing: View? = null, act: () -> Unit) =
                col.addView(sheetRow(icon, label, null, trailing) { d.dismiss(); act() })
            val marked = !t.home && t.url.startsWith("http") && store.isBookmarked(t.url)
            item(R.drawable.ic_add, "New tab") { newTab() }
            item(R.drawable.ic_shield, "New private tab") { newTab(null, true) }
            item(if (marked) R.drawable.ic_star else R.drawable.ic_star_border, if (marked) "Remove bookmark" else "Add bookmark") { toggleBookmark() }
            item(R.drawable.ic_star, "Bookmarks") { showBookmarks() }
            item(R.drawable.ic_history, "History") { showHistory() }
            item(R.drawable.ic_download, "Downloads") { showDownloads() }
            item(R.drawable.ic_share, "Share page") { if (!t.home && t.url.startsWith("http")) share(t.url) }
            item(R.drawable.ic_desktop, "Desktop site", tickView(t.desktop)) { toggleDesktop(t) }
            item(R.drawable.ic_settings, "Settings") { showSettings() }
            item(R.drawable.ic_exit, "Exit") { exitApp() }
        }
    }

    private fun showSettings() {
        val names = arrayOf("System", "Light", "Dark")
        sheet { col, d ->
            col.addView(sheetRow(R.drawable.ic_settings, "Theme", names[store.theme.coerceIn(0, 2)]) {
                d.dismiss()
                AlertDialog.Builder(this, dialogTheme()).setTitle("Theme")
                    .setSingleChoiceItems(names, store.theme) { dlg, i ->
                        store.theme = i
                        runtime.settings.setPreferredColorScheme(store.colorScheme())
                        dlg.dismiss()
                        applyTheme()
                    }.show()
            })
            col.addView(sheetRow(R.drawable.ic_search, "Search engine", store.engine) {
                d.dismiss()
                val list = ENGINES.keys.toTypedArray()
                AlertDialog.Builder(this, dialogTheme()).setTitle("Search engine")
                    .setSingleChoiceItems(list, list.indexOf(store.engine)) { dlg, i -> store.engine = list[i]; dlg.dismiss() }.show()
            })
            val s1 = switchView(store.strict)
            col.addView(sheetRow(R.drawable.ic_shield, "Strict tracking protection", "Blocks more trackers; a few sites may break", s1) {
                store.strict = !store.strict
                s1.isChecked = store.strict
                runtime.settings.contentBlocking.setEnhancedTrackingProtectionLevel(
                    if (store.strict) ContentBlocking.EtpLevel.STRICT else ContentBlocking.EtpLevel.DEFAULT)
                toast("Applies to pages you open next")
            })
            val s2 = switchView(store.httpsOnly)
            col.addView(sheetRow(R.drawable.ic_lock, "HTTPS-only mode", "Upgrades http:// links to https://", s2) {
                store.httpsOnly = !store.httpsOnly
                s2.isChecked = store.httpsOnly
            })
            val s3 = switchView(store.clearOnExit)
            col.addView(sheetRow(R.drawable.ic_delete, "Clear data on Exit", "Wipes history, cookies and cache when you tap Exit", s3) {
                store.clearOnExit = !store.clearOnExit
                s3.isChecked = store.clearOnExit
            })
            col.addView(sheetRow(R.drawable.ic_delete, "Clear browsing data now") { d.dismiss(); confirmClear() })
            col.addView(sheetRow(R.drawable.ic_home, "Make default browser") {
                d.dismiss()
                try { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) } catch (e: Exception) { toast("Open Android Settings > Apps > Default apps") }
            })
        }
    }

    private fun clearAll() {
        runtime.storageController.clearData(StorageController.ClearFlags.ALL)
        store.save("history", emptyList())
        store.save("tabs", emptyList())
    }

    private fun confirmClear() {
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("Clear browsing data?")
            .setMessage("This deletes history, cookies, cache and site data. You will be signed out of websites.")
            .setPositiveButton("Clear") { _, _ -> clearAll(); toast("Cleared") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exitApp() {
        exiting = true
        saveTabs()
        if (store.clearOnExit) clearAll()
        finishAndRemoveTask()
        handler.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 500)
    }

    private fun fullScreenDialog(): Pair<Dialog, LinearLayout> {
        val d = Dialog(this, android.R.style.Theme_Material_NoActionBar)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(P.bg) }
        d.setContentView(col)
        d.window?.setBackgroundDrawable(ColorDrawable(P.bg))
        d.window?.statusBarColor = P.bar
        d.window?.navigationBarColor = P.bar
        return d to col
    }

    private fun header(d: Dialog, title: String, vararg extra: View): LinearLayout {
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(P.bar); setPadding(dp(6), dp(4), dp(8), dp(4)) }
        head.addView(iconBtn(R.drawable.ic_back, "Back") { d.dismiss() })
        head.addView(TextView(this).apply {
            text = title; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(P.txt); setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        for (v in extra) head.addView(v)
        return head
    }

    private fun showTabs() {
        val (d, col) = fullScreenDialog()
        val newPriv = iconBtn(R.drawable.ic_shield, "New private tab") { d.dismiss(); newTab(null, true) }
        val newNormal = iconBtn(R.drawable.ic_add, "New tab") { d.dismiss(); newTab() }
        val head = header(d, "${tabs.size} tabs", newPriv, newNormal)
        val titleView = head.getChildAt(1) as TextView
        val lv = ListView(this).apply {
            divider = null; dividerHeight = 0; clipToPadding = false
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val adapter = RowAdapter(tabs.toList()) { t ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(4), dp(10))
                background = rect(P.pill, 14, if (t === cur) 2 else 0, P.acc)
                isClickable = true
                setOnClickListener { d.dismiss(); select(t) }
            }
            val ch = (hostOf(t.url).removePrefix("www.").firstOrNull() ?: '\u2022').uppercaseChar().toString()
            row.addView(TextView(this).apply {
                text = if (t.isPrivate) "P" else ch; gravity = Gravity.CENTER; textSize = 16f
                typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                background = oval(if (t.isPrivate) PRIVATE_BAR else TILE_COLORS[(t.url.hashCode() and 0x7fffffff) % TILE_COLORS.size])
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            val tx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(4), 0) }
            tx.addView(TextView(this).apply {
                text = if (t.home) "New tab" else t.title.ifBlank { displayUrl(t.url).ifBlank { "Loading\u2026" } }
                textSize = 15f; setTextColor(P.txt); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            tx.addView(TextView(this).apply {
                text = if (t.isPrivate) "Private" else if (t.home) "Start page" else displayUrl(t.url)
                textSize = 12f; setTextColor(P.sub); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(tx, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(iconBtn(R.drawable.ic_close, "Close tab", P.sub, 10) {
                closeTab(t)
                refreshTabs(lv, titleView)
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            val wrap = FrameLayout(this).apply { setPadding(0, dp(4), 0, dp(4)) }
            wrap.addView(row)
            wrap
        }
        lv.adapter = adapter
        col.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(lv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        d.show()
    }

    @Suppress("UNCHECKED_CAST")
    private fun refreshTabs(lv: ListView, titleView: TextView) {
        val a = lv.adapter as RowAdapter<Tab>
        a.items = tabs.toList()
        a.notifyDataSetChanged()
        titleView.text = "${tabs.size} tabs"
    }

    private fun showList(
        title: String, items: () -> List<Entry>, onClick: (Entry) -> Unit,
        onDelete: ((Entry) -> Unit)?, clearLabel: String? = null, onClear: (() -> Unit)? = null
    ) {
        val (d, col) = fullScreenDialog()
        var refresh: () -> Unit = {}
        val extra = ArrayList<View>()
        if (clearLabel != null && onClear != null) {
            extra.add(TextView(this).apply {
                text = clearLabel; textSize = 14f; setTextColor(P.acc); setPadding(dp(12), dp(10), dp(12), dp(10))
                background = ripple(false)
                setOnClickListener { onClear(); refresh() }
            })
        }
        val head = header(d, title, *extra.toTypedArray())
        val empty = TextView(this).apply { text = "Nothing here yet"; textSize = 14f; setTextColor(P.sub); gravity = Gravity.CENTER; setPadding(0, dp(48), 0, 0) }
        val adapter = RowAdapter(items()) { e ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(12), dp(20), dp(12))
                background = ripple(false)
                isClickable = true
                setOnClickListener { d.dismiss(); onClick(e) }
                if (onDelete != null) setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity, dialogTheme()).setTitle("Remove this item?")
                        .setPositiveButton("Remove") { _, _ -> onDelete(e); refresh() }
                        .setNegativeButton("Cancel", null).show()
                    true
                }
            }
            row.addView(TextView(this).apply {
                text = e.title.ifBlank { e.url }; textSize = 15f; setTextColor(P.txt); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(TextView(this).apply {
                text = e.url; textSize = 12f; setTextColor(P.sub); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            row
        }
        val lv = ListView(this).apply { divider = null; dividerHeight = 0; this.adapter = adapter }
        refresh = {
            adapter.items = items()
            adapter.notifyDataSetChanged()
            empty.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
        }
        refresh()
        col.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(empty, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(lv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        d.show()
    }

    private fun showBookmarks() = showList("Bookmarks", { store.load("bookmarks") }, { openInCurrent(it.url) }, { e ->
        store.save("bookmarks", store.load("bookmarks").filter { it.url != e.url })
    })

    private fun showHistory() = showList("History", { store.load("history").reversed() }, { openInCurrent(it.url) }, { e ->
        store.save("history", store.load("history").filter { it.url != e.url })
    }, "Clear", { store.save("history", emptyList()) })

    private fun showDownloads() = showList("Downloads", {
        (downloadsDir().listFiles() ?: emptyArray()).sortedByDescending { it.lastModified() }
            .map { Entry(it.name, it.absolutePath, it.lastModified()) }
    }, { openFile(File(it.url)) }, { File(it.url).delete() })
}
