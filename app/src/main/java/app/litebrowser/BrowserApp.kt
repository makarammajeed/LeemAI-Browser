package app.litebrowser

import android.app.Application
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class BrowserApp : Application() {
    lateinit var runtime: GeckoRuntime
    lateinit var store: Store

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        val blocking = ContentBlocking.Settings.Builder()
            .enhancedTrackingProtectionLevel(
                if (store.strict) ContentBlocking.EtpLevel.STRICT else ContentBlocking.EtpLevel.DEFAULT
            )
            .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
            .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
            .build()
        val settings = GeckoRuntimeSettings.Builder()
            .contentBlocking(blocking)
            .javaScriptEnabled(true)
            .remoteDebuggingEnabled(false)
            .aboutConfigEnabled(false)
            .preferredColorScheme(store.colorScheme())
            .build()
        runtime = GeckoRuntime.create(this, settings)
    }
}
