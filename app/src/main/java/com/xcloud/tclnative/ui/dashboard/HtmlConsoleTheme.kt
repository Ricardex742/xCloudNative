package com.xcloud.tclnative.ui.dashboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.PowerManager
import android.view.KeyEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.xcloud.tclnative.catalog.*
import com.xcloud.tclnative.settings.*
import org.json.JSONArray
import org.json.JSONObject

/** Only UI data enters the local document. Auth tokens, service endpoints and session input never do. */
internal object HtmlConsoleData {
    const val WINDOW=8
    fun snapshot(cards: List<CatalogCard>,selected: String?,tab: LibraryTab,profile: XboxProfile,
                 quota: CloudPlaytime,favorites: Set<String>,settings: PlaybackSettings,gameMenu: Boolean=false): JSONObject {
        val index=cards.indexOfFirst { it.key==selected }.coerceAtLeast(0)
        val start=(index-1).coerceAtLeast(0)
        fun image(url: String?,w: Int,h: Int): String {
            if(url==null || !url.startsWith("https://",true)) return ""
            return ArtworkRequest.create(url,w,h).url
        }
        val items=cards.drop(start).take(WINDOW).map { card ->
            JSONObject().put("id",card.key).put("name",card.name)
                .put("image",if(gameMenu) "" else image(card.tileUrl ?: card.posterUrl,256,256))
                .put("hero",if(gameMenu) "" else image(card.game?.heroUrl ?: card.product?.heroUrl,1024,576))
                .put("genre","Xbox Cloud Gaming").put("favorite",card.game?.titleId?.let(favorites::contains)==true)
        }
        return JSONObject().put("games",JSONArray(items)).put("selected",(index-start).coerceAtLeast(0))
            .put("total",cards.size).put("hasPrevious",start>0).put("hasNext",start+items.size<cards.size)
            .put("tab",when(tab) { LibraryTab.RECENT -> "recent";LibraryTab.FAVORITES -> "favorites";else -> "all" })
            .put("profile",JSONObject().put("name",profile.displayName).put("gamertag",profile.gamertag)
                .put("plan",profile.subscription?.let { if(it=="FREE") "FREE" else "GAME PASS $it" } ?: "—"))
            .put("quota",JSONObject().put("label",quota.label).put("unlimited",quota.status==CloudPlaytimeStatus.UNLIMITED))
            .put("settings",JSONObject().put("resolution",settings.forSession().resolution.label)
                .put("video",settings.videoMode.label).put("touch",settings.touchMode.label)
                .put("image",ImageProfiles.selected(settings).label).put("audio",settings.audioMode.label))
            .put("inGame",gameMenu)
    }
}

/** The supplied HTML files are loaded verbatim; a separate adapter connects their controls to Kotlin. */
@SuppressLint("SetJavaScriptEnabled")
internal class HtmlConsoleTheme(context: Context,val theme: UiTheme,private val action: (String,String) -> Unit): WebView(context) {
    private var ready=false
    private var closed=false
    private var pending: JSONObject?=null
    private var modal=false
    private var presented=true
    private val store=AppearanceSettingsStore(context)
    private val asset="file:///android_asset/${requireNotNull(theme.htmlAsset)}"
    private val bridge=object {
        @JavascriptInterface fun post(name: String,value: String) {
            if(name !in ACTIONS || value.length>512) return
            this@HtmlConsoleTheme.post {
                if(!closed) {
                    if(name=="modal") modal=value=="true"
                    else action(name,value)
                }
            }
        }
    }
    init {
        visibility=View.INVISIBLE;setBackgroundColor(Color.TRANSPARENT)
        isFocusable=true;isFocusableInTouchMode=true
        settings.javaScriptEnabled=true
        settings.domStorageEnabled=false
        settings.allowFileAccess=false;settings.allowContentAccess=false
        settings.javaScriptCanOpenWindowsAutomatically=false
        settings.mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.mediaPlaybackRequiresUserGesture=true
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this,false)
        if(Build.VERSION.SDK_INT>=26) settings.safeBrowsingEnabled=true
        addJavascriptInterface(bridge,"NativeTheme")
        webViewClient=object: WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest)=true
            @Deprecated("Deprecated in Java") override fun shouldOverrideUrlLoading(view: WebView,url: String)=true
            override fun onPageFinished(view: WebView,url: String) {
                if(closed || url!=asset) return
                val adapter=context.assets.open("themes/native-adapter.js").bufferedReader().use { it.readText() }
                evaluateJavascript("window.nativeTheme=${JSONObject.quote(theme.name.lowercase())};\n$adapter\n;Boolean(window.nativeAdapterReady)") { result ->
                    if(!closed && result=="true") { ready=true;flush();visibility=View.VISIBLE;requestFocus() }
                }
            }
        }
        loadUrl(asset)
    }
    fun update(value: JSONObject) { pending=value;flush() }
    private fun flush() {
        if(!ready || closed) return
        val value=pending ?: return
        value.put("night",store.htmlNight(theme))
        val power=(context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode==true
        val constrained=DevicePerformancePolicy.read(context).constrainedMemory
        val enabled=store.motion()!=BackgroundMotion.OFF && (store.motion()==BackgroundMotion.ON || (!power && !constrained))
        value.put("motion",enabled && presented)
        val json=value.toString().replace("\u2028","\\u2028").replace("\u2029","\\u2029")
        evaluateJavascript("window.nativeReceive($json)",null)
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus);presented=hasWindowFocus;flush() }
    fun controller(event: KeyEvent): Boolean {
        val key=when(event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft";KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
            KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp";KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
            KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_DPAD_CENTER -> "Enter"
            KeyEvent.KEYCODE_BUTTON_B,KeyEvent.KEYCODE_BACK -> if(modal || pending?.optBoolean("inGame")==true) "Escape" else return false
            KeyEvent.KEYCODE_BUTTON_Y -> "y";KeyEvent.KEYCODE_BUTTON_X -> "x";KeyEvent.KEYCODE_BUTTON_START -> "m"
            else -> return false
        }
        if(event.action==KeyEvent.ACTION_DOWN && ready && !closed) evaluateJavascript("window.nativeKey(${JSONObject.quote(key)})",null)
        return true
    }
    override fun dispatchKeyEvent(event: KeyEvent)=controller(event) || super.dispatchKeyEvent(event)
    fun close() {
        if(closed) return
        closed=true;ready=false;pending=null
        stopLoading();removeJavascriptInterface("NativeTheme");loadUrl("about:blank");removeAllViews();destroy()
    }
    companion object {
        private val ACTIONS=setOf("select","play","category","favorite","search","settings","friends","shortcut","night","motion","modal","move","resume","end","hud","statistics","audio","video","microphone","touch","xbox")
    }
}
