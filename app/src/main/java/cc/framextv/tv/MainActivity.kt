package cc.framextv.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * FrameXTV TV Pro — cliente leve para TCL Google TV / Android TV (2 GB RAM).
 *
 * - Navegação D-pad espacial (motor em assets/tv_engine.js), com OK nativo em links,
 *   botões, campos de texto e players embutidos.
 * - Escudo anti-anúncios/rastreadores por domínio (sem falsos positivos em CDNs).
 * - Controle de player pelo controle remoto + HUD, tela cheia com gesto real.
 * - Escala da página e tamanho do texto ajustáveis.
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var messagePanel: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var playerHud: LinearLayout
    private lateinit var playerHudTitle: TextView
    private lateinit var playerHudTime: TextView
    private lateinit var playerHudProgress: ProgressBar
    private lateinit var prefs: SharedPreferences

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var documentStartScript: ScriptHandler? = null
    private var engineSource: String = ""

    private var mainFrameFailed = false
    private var retryUrl: String? = null
    private var lastBackPressTime = 0L
    private var isPlayerModeActive = false
    private var inlineVideoPlaying = false
    private val consumedKeyDowns = HashSet<Int>()

    /** Lidos na thread de rede do WebView, por isso voláteis. */
    @Volatile private var shieldEnabled = true
    @Volatile private var focusKind = FOCUS_NONE

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideHudRunnable = Runnable { playerHud.visibility = View.GONE }

    companion object {
        private const val APP_VERSION = "0.6.0"
        private const val HOME_URL = "https://framextv.cc/"
        private val ENGINE_ORIGINS = setOf("https://framextv.cc", "https://www.framextv.cc")

        private const val PREFS_NAME = "framextv_tcl_prefs"
        private const val KEY_FAVORITES = "local_favorites_json"
        private const val KEY_LAST_URL = "last_visited_url"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on_always"
        private const val KEY_TEXT_ZOOM = "text_zoom_percent"
        private const val KEY_PAGE_SCALE = "page_scale_percent"
        private const val KEY_RESUME_LAST = "resume_last_page"
        private const val KEY_STRICT_SHIELD = "strict_third_party_shield"

        private const val DEFAULT_TEXT_ZOOM = 100
        private const val DEFAULT_PAGE_SCALE = 100
        private val TEXT_ZOOM_STEPS = intArrayOf(90, 100, 110, 125)
        private val PAGE_SCALE_STEPS = intArrayOf(100, 90, 80, 75, 67)

        private const val FOCUS_NONE = 0
        private const val FOCUS_TEXT = 1
        private const val FOCUS_NATIVE = 2
        private const val FOCUS_CUSTOM = 3

        private val BG = Color.rgb(8, 9, 11)
        private val GOLD = Color.rgb(214, 173, 85)

        /** Domínios de anúncios/rastreio/apostas. Casamento exato ou por subdomínio. */
        private val BLOCKED_HOSTS: Set<String> = hashSetOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "google-analytics.com", "googletagmanager.com", "googletagservices.com",
            "facebook.net", "facebook.com", "connect.facebook.net",
            "popads.net", "popcash.net", "propellerads.com", "adsterra.com",
            "exoclick.com", "juicyads.com", "trafficjunky.com", "hilltopads.net",
            "clickadu.com", "admaven.com", "monetag.com", "galaksion.com",
            "taboola.com", "outbrain.com", "criteo.com", "criteo.net", "pubmatic.com",
            "rubiconproject.com", "openx.net", "adnxs.com", "smartadserver.com",
            "yandex.ru", "hotjar.com", "clarity.ms", "sentry.io",
            "mgid.com", "revcontent.com", "adroll.com", "bidswitch.net",
            "casalemedia.com", "contextweb.com", "lijit.com", "sharethrough.com",
            "sovrn.com", "spotxchange.com", "teads.tv", "undertone.com",
            "yieldmo.com", "zedo.com", "adform.net", "advertising.com",
            "amazon-adsystem.com", "appnexus.com", "bluekai.com", "crwdcntrl.net",
            "demdex.net", "everesttech.net", "krxd.net", "mathtag.com",
            "moatads.com", "quantserve.com", "scorecardresearch.com", "turn.com",
            "acint.net", "adcolony.com", "admob.com", "applovin.com", "chartboost.com",
            "inmobi.com", "ironsrc.com", "mintegral.com", "mopub.com", "unityads.unity3d.com",
            "vungle.com", "bet365.com", "1xbet.com", "betano.com", "blaze.com", "sportingbet.com"
        )

        /** Rótulos de host que, isolados, indicam servidor de anúncio (ex.: ads.x.com). */
        private val BLOCKED_HOST_LABELS: Set<String> = hashSetOf(
            "ad", "ads", "adserver", "adservice", "adservices", "adserving", "popunder",
            "popads", "tracker", "tracking", "telemetry", "beacon", "pixel", "affiliate",
            "affiliates", "casino", "betting", "doubleclick"
        )

        private val EMPTY_BYTES = ByteArray(0)
    }

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        shieldEnabled = prefs.getBoolean(KEY_STRICT_SHIELD, true)
        engineSource = try {
            assets.open("tv_engine.js").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            ""
        }
        hideSystemUi()

        root = FrameLayout(this).apply { setBackgroundColor(BG) }

        loadingIndicator = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(GOLD)
        }

        messagePanel = TextView(this).apply {
            setBackgroundColor(Color.argb(245, 8, 9, 11))
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(80, 48, 80, 48)
            visibility = View.GONE
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { retryPage() }
        }

        setupPlayerHud()

        webView = createWebView()
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        root.addView(loadingIndicator, FrameLayout.LayoutParams(-1, 6, Gravity.TOP))
        root.addView(playerHud, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(48, 0, 48, 36)
        })
        root.addView(messagePanel, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        updateScreenWake()

        val initialUrl = if (prefs.getBoolean(KEY_RESUME_LAST, false)) {
            prefs.getString(KEY_LAST_URL, HOME_URL) ?: HOME_URL
        } else {
            HOME_URL
        }

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(initialUrl)
        }
        webView.requestFocus(View.FOCUS_DOWN)
    }

    override fun onPause() {
        if (::webView.isInitialized) {
            webView.evaluateJavascript("window.__fxtv&&window.__fxtv.pauseAll()", null)
            webView.onPause()
            webView.pauseTimers()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.resumeTimers()
            webView.onResume()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && ::webView.isInitialized) {
            webView.clearCache(false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        if (customView != null) exitCustomView()
        if (::webView.isInitialized) {
            documentStartScript?.remove()
            documentStartScript = null
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            root.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ WebView

    /** Ponte JS → app. Só recebe estados simples (tipo de foco, vídeo tocando). */
    inner class TvBridge {
        @JavascriptInterface
        fun focusKind(kind: String?) {
            this@MainActivity.focusKind = when (kind) {
                "text" -> FOCUS_TEXT
                "native" -> FOCUS_NATIVE
                "custom" -> FOCUS_CUSTOM
                else -> FOCUS_NONE
            }
        }

        @JavascriptInterface
        fun playing(isPlaying: Boolean) {
            runOnUiThread {
                inlineVideoPlaying = isPlaying
                updateScreenWake()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView(): WebView {
        val wv = WebView(this)
        wv.setBackgroundColor(BG)
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.overScrollMode = View.OVER_SCROLL_NEVER

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            builtInZoomControls = false
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            // Pré-rasterizar fora da tela gasta muita RAM de GPU em 4K; desligado em TVs de 2 GB.
            offscreenPreRaster = false
            textZoom = prefs.getInt(KEY_TEXT_ZOOM, DEFAULT_TEXT_ZOOM)
            userAgentString = "$userAgentString FrameXTV-TCL/$APP_VERSION AndroidTV"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // Players embutidos (iframes de outros domínios) costumam precisar de cookies próprios;
            // domínios de anúncio já são cortados na rede pelo escudo.
            setAcceptThirdPartyCookies(wv, true)
        }

        wv.addJavascriptInterface(TvBridge(), "FrameXTVBridge")
        documentStartScript = null
        installDocumentStartEngine(wv)

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress in 1..99) {
                    loadingIndicator.visibility = View.VISIBLE
                    loadingIndicator.progress = newProgress
                } else {
                    loadingIndicator.visibility = View.GONE
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                isPlayerModeActive = true
                webView.visibility = View.GONE
                root.addView(view, root.indexOfChild(playerHud), FrameLayout.LayoutParams(-1, -1))
                view.requestFocus()
                hideSystemUi()
                updateScreenWake()
                showPlayerHud("Tela cheia")
            }

            override fun onHideCustomView() = exitCustomView()

            override fun onPermissionRequest(request: PermissionRequest?) {
                val resources = request?.resources ?: return
                val allowed = resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }.toTypedArray()
                if (allowed.isNotEmpty()) request.grant(allowed) else request.deny()
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (shieldEnabled && isBlockedUrl(request.url)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(EMPTY_BYTES))
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                // Esquemas externos (intent://, market://, tel:, http://) nunca saem do app.
                if (uri.scheme != "https") return true
                if (shieldEnabled && isBlockedUrl(uri)) return true
                // Sub-frames (players embutidos) carregam se não forem anúncio.
                if (!request.isForMainFrame) return false
                // A navegação principal fica restrita ao FrameXTV.
                return !isOwnHost(uri.host?.lowercase() ?: "")
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                mainFrameFailed = false
                focusKind = FOCUS_NONE
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (!request.isForMainFrame) return
                mainFrameFailed = true
                retryUrl = request.url.toString()
                loadingIndicator.visibility = View.GONE
                showMessage(
                    "Sem conexão com o FrameXTV.\n\n" +
                        "Verifique o Wi-Fi ou o cabo de rede da TV e pressione OK para tentar de novo.\n\n" +
                        "Código: ${error.errorCode}"
                )
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                recoverFromRendererCrash(view)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                loadingIndicator.visibility = View.GONE
                if (mainFrameFailed) return
                messagePanel.visibility = View.GONE
                if (customView == null) isPlayerModeActive = false
                injectEngine(view)
                if (isOwnHost(Uri.parse(url).host?.lowercase() ?: "")) {
                    prefs.edit().putString(KEY_LAST_URL, url).apply()
                }
            }
        }
        return wv
    }

    private fun recoverFromRendererCrash(dead: WebView) {
        val lastUrl = dead.url?.takeIf { it.startsWith("https://") }
            ?: prefs.getString(KEY_LAST_URL, HOME_URL) ?: HOME_URL

        customView?.let { root.removeView(it) }
        customView = null
        customViewCallback = null
        isPlayerModeActive = false
        inlineVideoPlaying = false
        playerHud.visibility = View.GONE

        documentStartScript = null
        root.removeView(dead)
        dead.destroy()

        webView = createWebView()
        root.addView(webView, 0, FrameLayout.LayoutParams(-1, -1))
        retryUrl = lastUrl
        mainFrameFailed = true
        updateScreenWake()
        showMessage("A TV ficou sem memória e a página foi fechada.\n\nPressione OK para recarregar.")
    }

    // ------------------------------------------------------------------ motor JS

    private fun engineConfigJs(): String {
        val scale = prefs.getInt(KEY_PAGE_SCALE, DEFAULT_PAGE_SCALE)
        return "window.__FXTV_CFG={scale:$scale,shield:$shieldEnabled};\n"
    }

    /** Injeta o motor antes dos scripts do site (sem "piscar" anúncios nem relayout de escala). */
    private fun installDocumentStartEngine(target: WebView) {
        if (engineSource.isEmpty()) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        try {
            documentStartScript?.remove()
            documentStartScript = WebViewCompat.addDocumentStartJavaScript(
                target, engineConfigJs() + engineSource, ENGINE_ORIGINS
            )
        } catch (_: Exception) {
            documentStartScript = null
        }
    }

    /** Fallback e reforço: garante o motor e aplica a configuração atual na página aberta. */
    private fun injectEngine(target: WebView) {
        if (engineSource.isEmpty()) return
        target.evaluateJavascript(engineConfigJs() + engineSource, null)
    }

    private fun refreshEngineConfig() {
        installDocumentStartEngine(webView)
        injectEngine(webView)
    }

    // ------------------------------------------------------------------ escudo

    private fun isOwnHost(host: String): Boolean =
        host == "framextv.cc" || host.endsWith(".framextv.cc")

    private fun isBlockedUrl(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (host.isEmpty() || isOwnHost(host)) return false

        var suffix = host
        while (true) {
            if (suffix in BLOCKED_HOSTS) return true
            val dot = suffix.indexOf('.')
            if (dot < 0) break
            suffix = suffix.substring(dot + 1)
        }
        for (label in host.split('.')) {
            if (label in BLOCKED_HOST_LABELS || label.startsWith("ads-") || label.startsWith("adserv")) return true
        }
        val path = uri.encodedPath?.lowercase() ?: return false
        return path.contains("/ads/") || path.contains("popunder") || path.contains("/adserver")
    }

    // ------------------------------------------------------------------ teclas

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (messagePanel.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)

        val code = event.keyCode
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (handleKeyDown(event)) {
                    consumedKeyDowns.add(code)
                    return true
                }
                consumedKeyDowns.remove(code)
            }
            KeyEvent.ACTION_UP -> {
                // Engole o "soltar" das teclas cujo "apertar" já tratamos.
                if (consumedKeyDowns.remove(code)) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleKeyDown(event: KeyEvent): Boolean {
        val code = event.keyCode
        val first = event.repeatCount == 0

        when (code) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_BOOKMARK -> {
                if (first) showAppMenu()
                return true
            }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> {
                if (first) togglePlayerControlMode()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (first) sendPlayerCommand("TOGGLE", 0, "Play / Pausa", code)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (first) sendPlayerCommand("PLAY", 0, "Reproduzindo", code)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (first) sendPlayerCommand("PAUSE", 0, "Pausado", code)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekFromKey(event, forward = true)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekFromKey(event, forward = false)
                return true
            }
        }

        if (customView != null || isPlayerModeActive) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (first) sendPlayerCommand("TOGGLE", 0, "Play / Pausa", code)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { seekFromKey(event, forward = true); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { seekFromKey(event, forward = false); return true }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (first) toggleFullscreen()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (first) sendPlayerCommand("STATUS", 0, "Reprodução", 0)
                    return true
                }
            }
            return false
        }

        // Navegação do catálogo.
        val kind = focusKind
        return when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> { moveFocus("UP"); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { moveFocus("DOWN"); true }
            // Em campo de texto, ◀ ▶ movem o cursor (nativo).
            KeyEvent.KEYCODE_DPAD_LEFT -> if (kind == FOCUS_TEXT) false else { moveFocus("LEFT"); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (kind == FOCUS_TEXT) false else { moveFocus("RIGHT"); true }
            // Links, botões, campos e players recebem o OK nativo (gesto real: abre teclado, seletor, play).
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (kind == FOCUS_CUSTOM) {
                    if (first) webView.evaluateJavascript("window.__fxtv&&window.__fxtv.activate()", null)
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    private fun moveFocus(dir: String) {
        webView.evaluateJavascript("window.__fxtv&&window.__fxtv.move('$dir')", null)
    }

    private fun seekFromKey(event: KeyEvent, forward: Boolean) {
        // Ao segurar a tecla: um salto a cada 3 repetições, de 30 s.
        if (event.repeatCount > 0 && event.repeatCount % 3 != 0) return
        val step = if (event.repeatCount > 2) 30 else 15
        val delta = if (forward) step else -step
        val label = if (forward) "Avançar +${step}s" else "Voltar -${step}s"
        sendPlayerCommand("SEEK", delta, label, event.keyCode)
    }

    // ------------------------------------------------------------------ player

    private fun parseJs(raw: String?): Any? {
        if (raw == null || raw == "null") return null
        return try {
            JSONTokener(raw).nextValue()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Envia um comando ao vídeo da página. Se não houver vídeo acessível (player num iframe de
     * outro domínio), repassa a tecla original ao player como evento nativo.
     */
    private fun sendPlayerCommand(cmd: String, value: Int, label: String, fallbackKey: Int) {
        webView.evaluateJavascript("window.__fxtv?window.__fxtv.player('$cmd',$value):null") { raw ->
            val res = parseJs(raw) as? JSONObject
            if (res != null && res.optBoolean("ok", false)) {
                val paused = res.optBoolean("paused", false)
                val title = when (cmd) {
                    "TOGGLE", "PLAY", "PAUSE", "STATUS" -> if (paused) "Pausado" else "Reproduzindo"
                    else -> label
                }
                showPlayerHud(title, res.optDouble("cur", -1.0), res.optDouble("dur", -1.0))
            } else {
                if (fallbackKey != 0) sendNativeKey(customView ?: webView, fallbackKey)
                showPlayerHud(label)
            }
        }
    }

    private fun sendNativeKey(target: View, keyCode: Int) {
        target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    /** Tela cheia exige gesto real: o JS marca o alvo e uma tecla nativa (F11) dispara o pedido. */
    private fun toggleFullscreen() {
        if (customView != null) {
            exitCustomView()
            return
        }
        webView.evaluateJavascript("window.__fxtv?window.__fxtv.armFullscreen():'none'") { raw ->
            when (parseJs(raw) as? String) {
                "armed" -> sendNativeKey(webView, KeyEvent.KEYCODE_F11)
                "exited" -> showPlayerHud("Saiu da tela cheia")
                else -> Toast.makeText(this, "Nenhum vídeo nesta página.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun togglePlayerControlMode() {
        isPlayerModeActive = !isPlayerModeActive
        if (isPlayerModeActive) {
            sendPlayerCommand("STATUS", 0, "Controle do player ativo", 0)
        } else {
            playerHud.visibility = View.GONE
            Toast.makeText(this, "Modo navegação do catálogo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exitCustomView() {
        val view = customView ?: return
        root.removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        isPlayerModeActive = false
        playerHud.visibility = View.GONE
        webView.visibility = View.VISIBLE
        updateScreenWake()
        hideSystemUi()
        webView.requestFocus()
    }

    private fun setupPlayerHud() {
        playerHud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(230, 12, 14, 18))
                cornerRadius = 16f
                setStroke(2, GOLD)
            }
            setPadding(32, 22, 32, 22)
            visibility = View.GONE
            isFocusable = false
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playerHudTitle = TextView(this).apply {
            setTextColor(GOLD)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        playerHudTime = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        topRow.addView(playerHudTitle)
        topRow.addView(playerHudTime)

        playerHudProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(GOLD)
            layoutParams = LinearLayout.LayoutParams(-1, 10).apply { setMargins(0, 14, 0, 12) }
        }

        val hints = TextView(this).apply {
            setTextColor(Color.rgb(190, 195, 205))
            textSize = 14f
            text = "OK: play/pausa  •  ◀ ▶: -15s / +15s (segure: 30s)  •  ▲: tela cheia  •  VOLTAR: sair"
        }

        playerHud.addView(topRow)
        playerHud.addView(playerHudProgress)
        playerHud.addView(hints)
    }

    private fun showPlayerHud(title: String, currentSec: Double = -1.0, durationSec: Double = -1.0) {
        playerHudTitle.text = title
        if (currentSec >= 0 && durationSec > 0) {
            playerHudTime.text = "${formatTime(currentSec)} / ${formatTime(durationSec)}"
            playerHudProgress.progress = ((currentSec / durationSec) * 1000.0).toInt().coerceIn(0, 1000)
            playerHudProgress.visibility = View.VISIBLE
        } else {
            playerHudTime.text = ""
            playerHudProgress.visibility = View.GONE
        }
        playerHud.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideHudRunnable)
        uiHandler.postDelayed(hideHudRunnable, 3200L)
    }

    private fun formatTime(totalSeconds: Double): String {
        val secs = totalSeconds.toInt().coerceAtLeast(0)
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    // ------------------------------------------------------------------ voltar

    @Deprecated("Compatibilidade Android TV")
    override fun onBackPressed() {
        when {
            messagePanel.visibility == View.VISIBLE -> {
                messagePanel.visibility = View.GONE
                webView.requestFocus()
            }
            customView != null -> exitCustomView()
            isPlayerModeActive -> {
                isPlayerModeActive = false
                playerHud.visibility = View.GONE
                Toast.makeText(this, "Voltou à navegação do catálogo", Toast.LENGTH_SHORT).show()
            }
            webView.canGoBack() -> webView.goBack()
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < 2000L) {
                    finish()
                } else {
                    lastBackPressTime = now
                    Toast.makeText(this, "Pressione VOLTAR de novo para sair", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ------------------------------------------------------------------ menus

    private fun dialogBuilder() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun showAppMenu() {
        val playerLabel = if (isPlayerModeActive) "Voltar à navegação do catálogo" else "Controlar o player (D-pad)"
        val options = arrayOf(
            playerLabel,
            "Vídeo em tela cheia",
            "Início (FrameXTV)",
            "Salvar nos favoritos",
            "Favoritos",
            "Configurações",
            "Recarregar página",
            "Limpar cache"
        )
        dialogBuilder()
            .setTitle("FrameXTV TV Pro $APP_VERSION")
            .setItems(options) { _, index ->
                when (index) {
                    0 -> togglePlayerControlMode()
                    1 -> toggleFullscreen()
                    2 -> {
                        isPlayerModeActive = false
                        webView.loadUrl(HOME_URL)
                    }
                    3 -> saveCurrentPageToFavorites()
                    4 -> showFavoritesDialog()
                    5 -> showSettingsDialog()
                    6 -> webView.reload()
                    7 -> {
                        webView.clearCache(true)
                        Toast.makeText(this, "Cache limpo.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun saveCurrentPageToFavorites() {
        val currentUrl = webView.url ?: return
        val currentTitle = webView.title?.takeIf { it.isNotBlank() } ?: currentUrl

        val list = getFavoritesList()
        val filtered = JSONArray()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            if (item.optString("url") != currentUrl) filtered.put(item)
        }
        filtered.put(JSONObject().apply {
            put("title", currentTitle)
            put("url", currentUrl)
        })
        prefs.edit().putString(KEY_FAVORITES, filtered.toString()).apply()
        Toast.makeText(this, "Favorito salvo: $currentTitle", Toast.LENGTH_SHORT).show()
    }

    private fun showFavoritesDialog() {
        val list = getFavoritesList()
        if (list.length() == 0) {
            Toast.makeText(this, "Nenhum favorito salvo.", Toast.LENGTH_SHORT).show()
            return
        }
        val titles = Array(list.length()) { i -> list.optJSONObject(i)?.optString("title") ?: "" }
        val urls = Array(list.length()) { i -> list.optJSONObject(i)?.optString("url") ?: "" }

        dialogBuilder()
            .setTitle("Favoritos")
            .setItems(titles) { _, index -> urls[index].takeIf { it.isNotEmpty() }?.let { webView.loadUrl(it) } }
            .setNeutralButton("Apagar todos") { _, _ ->
                prefs.edit().remove(KEY_FAVORITES).apply()
                Toast.makeText(this, "Favoritos apagados.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun getFavoritesList(): JSONArray {
        val raw = prefs.getString(KEY_FAVORITES, "[]") ?: "[]"
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private fun nextStep(steps: IntArray, current: Int): Int {
        val idx = steps.indexOf(current)
        return steps[(idx + 1) % steps.size]
    }

    private fun showSettingsDialog() {
        val shield = shieldEnabled
        val pageScale = prefs.getInt(KEY_PAGE_SCALE, DEFAULT_PAGE_SCALE)
        val textZoom = prefs.getInt(KEY_TEXT_ZOOM, DEFAULT_TEXT_ZOOM)
        val keepScreen = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        val resumeLast = prefs.getBoolean(KEY_RESUME_LAST, false)

        val items = arrayOf(
            "Escala da página: $pageScale%",
            "Tamanho do texto: $textZoom%",
            "Bloqueio de anúncios e rastreadores: ${if (shield) "LIGADO" else "desligado"}",
            "Tela sempre ligada: ${if (keepScreen) "ligada" else "desligada"}",
            "Abrir na última página: ${if (resumeLast) "sim" else "não"}"
        )

        dialogBuilder()
            .setTitle("Configurações")
            .setItems(items) { _, index ->
                when (index) {
                    0 -> {
                        val next = nextStep(PAGE_SCALE_STEPS, pageScale)
                        prefs.edit().putInt(KEY_PAGE_SCALE, next).apply()
                        refreshEngineConfig()
                        Toast.makeText(this, "Escala da página: $next%", Toast.LENGTH_SHORT).show()
                        showSettingsDialog()
                    }
                    1 -> {
                        val next = nextStep(TEXT_ZOOM_STEPS, textZoom)
                        prefs.edit().putInt(KEY_TEXT_ZOOM, next).apply()
                        webView.settings.textZoom = next
                        showSettingsDialog()
                    }
                    2 -> {
                        shieldEnabled = !shield
                        prefs.edit().putBoolean(KEY_STRICT_SHIELD, shieldEnabled).apply()
                        installDocumentStartEngine(webView)
                        webView.reload()
                    }
                    3 -> {
                        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, !keepScreen).apply()
                        updateScreenWake()
                    }
                    4 -> prefs.edit().putBoolean(KEY_RESUME_LAST, !resumeLast).apply()
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    // ------------------------------------------------------------------ utilidades

    private fun updateScreenWake() {
        val keepOn = customView != null || inlineVideoPlaying || prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun retryPage() {
        messagePanel.visibility = View.GONE
        val target = retryUrl
        retryUrl = null
        when {
            target != null -> webView.loadUrl(target)
            webView.url.isNullOrBlank() -> webView.loadUrl(HOME_URL)
            else -> webView.reload()
        }
        webView.requestFocus()
    }

    private fun showMessage(text: String) {
        messagePanel.text = "$text\n\n[ Pressione OK ]"
        messagePanel.visibility = View.VISIBLE
        messagePanel.requestFocus()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }
}
