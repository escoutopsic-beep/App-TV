package cc.framextv.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * FrameXTV TV Pro v0.5.0 — Otimizado para TCL Google TV / Android TV (2 GB RAM).
 * - Navegação D-pad espacial instantânea (sem ponteiro/rato).
 * - Bloqueio rigoroso de terceiros, anúncios, pop-ups, overlays e spam.
 * - Controlador de Player nativo para TV (HUD OSD + D-pad Esquerda/Direita para recuar/avançar,
 *   OK para Play/Pause, Cima para Ecrã Inteiro, Baixo para Barra de Tempo).
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
    private var mainFrameFailed = false
    private var lastBackPressTime = 0L
    private var isPlayerModeActive = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideHudRunnable = Runnable {
        playerHud.visibility = View.GONE
    }

    private val homeUrl = "https://framextv.cc/"
    private val allowedMainHosts = setOf("framextv.cc", "www.framextv.cc")

    // Bloqueio rigoroso de anúncios, trackers, pop-unders, redirects de spam e scripts de terceiros maliciosos
    private val blockedThirdPartyHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "google-analytics.com", "googletagmanager.com", "facebook.net", "facebook.com",
        "popads.net", "popcash.net", "propellerads.com", "adsterra.com",
        "exoclick.com", "juicyads.com", "trafficjunky.com", "hilltopads.net",
        "clickadu.com", "admaven.com", "monetag.com", "galaksion.com",
        "taboola.com", "outbrain.com", "criteo.com", "pubmatic.com",
        "rubiconproject.com", "openx.net", "adnxs.com", "smartadserver.com",
        "yandex.ru", "mc.yandex.ru", "hotjar.com", "clarity.ms", "sentry.io",
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

    private val blockedKeywords = listOf(
        "ads.", "adserver", "adservice", "popunder", "pop-under", "popup",
        "tracker", "tracking", "telemetry", "beacon", "pixel.", "banner",
        "affiliate", "casino", "betting", "click.", "redirect", "promo-ad"
    )

    private val emptyResponseBytes = ByteArray(0)

    companion object {
        private const val PREFS_NAME = "framextv_tcl_prefs"
        private const val KEY_FAVORITES = "local_favorites_json"
        private const val KEY_LAST_URL = "last_visited_url"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on_always"
        private const val KEY_TEXT_ZOOM = "text_zoom_percent"
        private const val KEY_RESUME_LAST = "resume_last_page"
        private const val KEY_STRICT_SHIELD = "strict_third_party_shield"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        hideSystemUi()
        applyScreenWakePolicy(isPlayingVideo = false)

        root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(8, 9, 11))
        }

        loadingIndicator = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            visibility = View.GONE
            progressDrawable.setColorFilter(Color.rgb(214, 173, 85), android.graphics.PorterDuff.Mode.SRC_IN)
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

        val initialUrl = if (prefs.getBoolean(KEY_RESUME_LAST, false)) {
            prefs.getString(KEY_LAST_URL, homeUrl) ?: homeUrl
        } else {
            homeUrl
        }

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(initialUrl)
        }
        webView.requestFocus(View.FOCUS_DOWN)
    }

    private fun setupPlayerHud() {
        val bg = GradientDrawable().apply {
            setColor(Color.argb(230, 12, 14, 18))
            cornerRadius = 16f
            setStroke(2, Color.rgb(214, 173, 85))
        }

        playerHud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(32, 22, 32, 22)
            visibility = View.GONE
            isFocusable = false
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        playerHudTitle = TextView(this).apply {
            setTextColor(Color.rgb(214, 173, 85))
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            text = "Player TV"
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }

        playerHudTime = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            text = ""
        }

        topRow.addView(playerHudTitle)
        topRow.addView(playerHudTime)

        playerHudProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressDrawable.setColorFilter(Color.rgb(214, 173, 85), android.graphics.PorterDuff.Mode.SRC_IN)
            layoutParams = LinearLayout.LayoutParams(-1, 10).apply {
                setMargins(0, 14, 0, 12)
            }
        }

        val hints = TextView(this).apply {
            setTextColor(Color.rgb(190, 195, 205))
            textSize = 14f
            text = "OK: Play/Pausa   •   ◀ / ▶: -15s / +15s   •   ▲: Ecrã Inteiro   •   VOLTAR: Sair do Player"
        }

        playerHud.addView(topRow)
        playerHud.addView(playerHudProgress)
        playerHud.addView(hints)
    }

    private fun showPlayerHud(actionLabel: String, currentSec: Double = -1.0, durationSec: Double = -1.0) {
        playerHudTitle.text = actionLabel
        if (currentSec >= 0 && durationSec > 0) {
            playerHudTime.text = "${formatTime(currentSec)} / ${formatTime(durationSec)}"
            val ratio = ((currentSec / durationSec) * 1000.0).toInt().coerceIn(0, 1000)
            playerHudProgress.progress = ratio
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
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(this).apply {
        setBackgroundColor(Color.rgb(8, 9, 11))
        isFocusable = true
        isFocusableInTouchMode = true
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        isScrollbarFadingEnabled = true
        scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true
            blockNetworkImage = false
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
            setNeedInitialFocus(true)
            offscreenPreRaster = true
            textZoom = prefs.getInt(KEY_TEXT_ZOOM, 110)
            userAgentString = "$userAgentString FrameXTV-TCL/0.5 AndroidTV"
        }

        val webViewRef = this
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // Bloqueia cookies de rastreamento de terceiros para maior privacidade e menos spam
            setAcceptThirdPartyCookies(webViewRef, false)
        }

        webChromeClient = object : WebChromeClient() {
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
                applyScreenWakePolicy(isPlayingVideo = true)
                this@MainActivity.webView.visibility = View.GONE
                root.addView(view, root.indexOfChild(playerHud), FrameLayout.LayoutParams(-1, -1))
                view.requestFocus()
                hideSystemUi()
                showPlayerHud("Modo Cinema (Ecrã Inteiro)")
            }

            override fun onHideCustomView() = exitCustomView()

            override fun onPermissionRequest(request: PermissionRequest?) {
                val resources = request?.resources ?: return
                val allowedResources = resources.filter {
                    it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                }.toTypedArray()

                if (allowedResources.isNotEmpty()) {
                    request.grant(allowedResources)
                } else {
                    request.deny()
                }
            }
        }

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (prefs.getBoolean(KEY_STRICT_SHIELD, true)) {
                    val uri = request.url
                    val host = uri.host?.lowercase() ?: ""
                    val fullUrl = uri.toString().lowercase()

                    if (isBlockedThirdParty(host, fullUrl)) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(emptyResponseBytes))
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                // Bloqueia esquemas externos (intent://, market://, tel://, mailto:, http://)
                if (uri.scheme != "https") return true

                val host = uri.host?.lowercase() ?: ""
                val fullUrl = uri.toString().lowercase()

                if (prefs.getBoolean(KEY_STRICT_SHIELD, true) && isBlockedThirdParty(host, fullUrl)) {
                    return true
                }

                // Sub-frames (embeds de vídeo) só carregam se não forem spam/ads
                if (!request.isForMainFrame) return false

                // Navegação principal estritamente bloqueada fora do FrameXTV
                val isAllowedMainHost = host in allowedMainHosts ||
                        allowedMainHosts.any { host.endsWith(".$it") }

                if (!isAllowedMainHost) {
                    return true
                }
                return false
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    mainFrameFailed = true
                    loadingIndicator.visibility = View.GONE
                    showMessage(
                        "Sem ligação ao FrameXTV.\n\n" +
                        "Verifique o Wi-Fi ou cabo de rede da sua TCL e pressione OK.\n\n" +
                        "Código: ${error.errorCode}"
                    )
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                root.removeView(webView)
                webView.destroy()
                webView = createWebView()
                root.addView(webView, 0, FrameLayout.LayoutParams(-1, -1))
                showMessage("Memória da TV otimizada.\n\nPressione OK para continuar.")
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                loadingIndicator.visibility = View.GONE
                injectAgileTvAndPlayerEngine()
                mainFrameFailed = false
                messagePanel.visibility = View.GONE
                isPlayerModeActive = false

                if (url.startsWith("https://framextv.cc") || url.startsWith("https://www.framextv.cc")) {
                    prefs.edit().putString(KEY_LAST_URL, url).apply()
                }
            }
        }
    }

    private fun isBlockedThirdParty(host: String, fullUrl: String): Boolean {
        if (host.isEmpty()) return false
        // Nunca bloqueia o próprio domínio FrameXTV
        if (host == "framextv.cc" || host.endsWith(".framextv.cc")) return false

        for (blocked in blockedThirdPartyHosts) {
            if (host == blocked || host.endsWith(".$blocked")) return true
        }
        for (kw in blockedKeywords) {
            if (host.contains(kw) || fullUrl.contains("/ads/") || fullUrl.contains("popunder") || fullUrl.contains("adserver")) {
                return true
            }
        }
        return false
    }

    private fun injectAgileTvAndPlayerEngine() {
        val shieldActive = prefs.getBoolean(KEY_STRICT_SHIELD, true)
        val spamCss = if (shieldActive) {
            """
            [id*="ad-"], [id*="ads-"], [class*="ad-banner"], [class*="adsbox"], [class*="popup"],
            [class*="overlay-ad"], [class*="sponsor"], [class*="promo-modal"],
            iframe[src*="ads"], iframe[src*="doubleclick"], iframe[src*="pop"], iframe[src*="bet"],
            ins.adsbygoogle, a[target="_blank"][rel*="nofollow"] {
                display: none !important;
                visibility: hidden !important;
                width: 0 !important;
                height: 0 !important;
                pointer-events: none !important;
            }
            """
        } else ""

        val js = """
          (function(){
            if(window.__framextvProTv) return;
            window.__framextvProTv = true;

            // 1. Bloqueio total de pop-ups, alertas intrusivos e redirecionamentos de terceiros
            window.open = function() { return null; };
            window.alert = function() {};
            window.confirm = function() { return false; };
            window.prompt = function() { return null; };

            // 2. CSS ultra-leve para GPU da TV (sem sombras, blurs ou animações lentas) + foco D-pad nítido
            var style = document.createElement('style');
            style.textContent = `
              html, body { background: #08090b !important; overflow-x: hidden !important; }
              *, *::before, *::after {
                animation-duration: 0.001ms !important;
                animation-iteration-count: 1 !important;
                transition-duration: 0.001ms !important;
                scroll-behavior: auto !important;
                backdrop-filter: none !important;
                -webkit-backdrop-filter: none !important;
                box-shadow: none !important;
                text-shadow: none !important;
              }
              a:focus, button:focus, [role=button]:focus, input:focus, select:focus, [tabindex]:focus {
                outline: 4px solid #d6ad55 !important;
                outline-offset: 3px !important;
                border-radius: 6px !important;
                background-color: rgba(214, 173, 85, 0.14) !important;
              }
              video {
                max-width: 100% !important;
                transform: translateZ(0);
                will-change: transform;
              }
              $spamCss
            `;
            document.documentElement.appendChild(style);

            // 3. Motor de Navegação Espacial D-pad Rápida (sem ponteiro)
            function getFocusableElements() {
              var all = Array.from(document.querySelectorAll(
                'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), [role="button"], [tabindex="0"], .card, article'
              ));
              return all.filter(function(el) {
                var r = el.getBoundingClientRect();
                return r.width > 10 && r.height > 10 && el.offsetParent !== null;
              });
            }

            function prepareFocusables() {
              document.querySelectorAll('a[href], button, [role="button"], [onclick], .card, article').forEach(function(el) {
                if (!el.hasAttribute('tabindex')) el.setAttribute('tabindex', '0');
                // Remove target="_blank" para impedir tentativas de abrir novas abas de spam
                if (el.tagName === 'A' && el.getAttribute('target') === '_blank') {
                  el.removeAttribute('target');
                }
              });
              if (!document.activeElement || document.activeElement === document.body) {
                var first = getFocusableElements()[0];
                if (first) first.focus({ preventScroll: true });
              }
            }

            window.__tvMoveFocus = function(dir) {
              var active = document.activeElement;
              var list = getFocusableElements();
              if (!list.length) return false;
              if (!active || active === document.body || list.indexOf(active) === -1) {
                list[0].focus();
                list[0].scrollIntoView({ block: 'center', inline: 'center' });
                return true;
              }

              var cur = active.getBoundingClientRect();
              var cx = cur.left + cur.width / 2;
              var cy = cur.top + cur.height / 2;
              var best = null;
              var bestScore = Infinity;

              for (var i = 0; i < list.length; i++) {
                var el = list[i];
                if (el === active) continue;
                var r = el.getBoundingClientRect();
                var ex = r.left + r.width / 2;
                var ey = r.top + r.height / 2;
                var dx = ex - cx;
                var dy = ey - cy;

                var valid = false;
                var primary = 0;
                var secondary = 0;

                if (dir === 'RIGHT' && dx > 8) { valid = true; primary = dx; secondary = Math.abs(dy) * 2.4; }
                else if (dir === 'LEFT' && dx < -8) { valid = true; primary = -dx; secondary = Math.abs(dy) * 2.4; }
                else if (dir === 'DOWN' && dy > 8) { valid = true; primary = dy; secondary = Math.abs(dx) * 2.0; }
                else if (dir === 'UP' && dy < -8) { valid = true; primary = -dy; secondary = Math.abs(dx) * 2.0; }

                if (valid) {
                  var score = primary + secondary;
                  if (score < bestScore) {
                    bestScore = score;
                    best = el;
                  }
                }
              }

              if (best) {
                best.focus({ preventScroll: true });
                best.scrollIntoView({ block: 'center', inline: 'nearest' });
                return true;
              } else {
                // Scroll suave instantâneo se chegou ao limite do ecrã
                if (dir === 'DOWN') window.scrollBy(0, 320);
                if (dir === 'UP') window.scrollBy(0, -320);
                return false;
              }
            };

            // 4. Otimização dedicada do Player HTML5
            function tunePlayer() {
              var videos = document.querySelectorAll('video');
              videos.forEach(function(v) {
                if (!v.__tvPlayerTuned) {
                  v.__tvPlayerTuned = true;
                  v.setAttribute('playsinline', '');
                  v.setAttribute('preload', 'auto');
                  v.controls = true;
                  v.addEventListener('play', function() { window.__hasActiveVideo = true; });
                  v.addEventListener('playing', function() { window.__hasActiveVideo = true; });
                }
              });
              return videos.length > 0;
            }

            window.__tvPlayerAction = function(cmd, val) {
              var v = document.querySelector('video');
              if (!v) return JSON.stringify({ ok: false });
              if (cmd === 'TOGGLE') {
                if (v.paused) v.play(); else v.pause();
              } else if (cmd === 'PLAY') {
                v.play();
              } else if (cmd === 'PAUSE') {
                v.pause();
              } else if (cmd === 'SEEK') {
                var target = Math.max(0, Math.min(v.duration || 99999, v.currentTime + val));
                v.currentTime = target;
              } else if (cmd === 'FULLSCREEN') {
                if (document.fullscreenElement) {
                  document.exitFullscreen();
                } else if (v.requestFullscreen) {
                  v.requestFullscreen();
                } else if (v.webkitEnterFullscreen) {
                  v.webkitEnterFullscreen();
                }
              }
              return JSON.stringify({
                ok: true,
                paused: v.paused,
                cur: v.currentTime || 0,
                dur: isFinite(v.duration) ? v.duration : -1
              });
            };

            prepareFocusables();
            tunePlayer();

            var debounceTimer = null;
            var observer = new MutationObserver(function() {
              if (debounceTimer) return;
              debounceTimer = setTimeout(function() {
                debounceTimer = null;
                prepareFocusables();
                tunePlayer();
              }, 250);
            });
            observer.observe(document.body, { childList: true, subtree: true });
          })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (messagePanel.visibility == View.VISIBLE) {
            return super.dispatchKeyEvent(event)
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_BOOKMARK -> {
                    showAppMenu()
                    return true
                }
                KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> {
                    togglePlayerControlMode()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    sendPlayerCommand("TOGGLE", 0, "Play / Pausa")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    sendPlayerCommand("PLAY", 0, "Reproduzir")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    sendPlayerCommand("PAUSE", 0, "Pausado")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    sendPlayerCommand("SEEK", 15, "Avançar +15s")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    sendPlayerCommand("SEEK", -15, "Recuar -15s")
                    return true
                }
            }

            // Se estiver em Modo Cinema (fullscreen) ou Modo Player ativado, D-pad controla diretamente o vídeo com agilidade
            if (customView != null || isPlayerModeActive) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        sendPlayerCommand("TOGGLE", 0, "Play / Pausa")
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val step = if (event.repeatCount > 2) 30 else 15
                        sendPlayerCommand("SEEK", step, "Avançar +${step}s")
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        val step = if (event.repeatCount > 2) -30 else -15
                        sendPlayerCommand("SEEK", step, "Recuar ${step}s")
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        sendPlayerCommand("FULLSCREEN", 0, "Alternar Ecrã Inteiro")
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        sendPlayerCommand("STATUS", 0, "Estado da Reprodução")
                        return true
                    }
                }
            } else {
                // Navegação D-pad espacial rápida pela interface do catálogo
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        webView.evaluateJavascript("window.__tvMoveFocus && window.__tvMoveFocus('UP')", null)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        webView.evaluateJavascript("window.__tvMoveFocus && window.__tvMoveFocus('DOWN')", null)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        webView.evaluateJavascript("window.__tvMoveFocus && window.__tvMoveFocus('LEFT')", null)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        webView.evaluateJavascript("window.__tvMoveFocus && window.__tvMoveFocus('RIGHT')", null)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (event.repeatCount == 0) {
                            webView.evaluateJavascript(
                                "(function(){var el=document.activeElement;if(el&&el!==document.body){el.click();setTimeout(function(){if(document.querySelector('video')){window.__hasActiveVideo=true;}},600);}})()",
                                null
                            )
                        }
                        return true
                    }
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun togglePlayerControlMode() {
        isPlayerModeActive = !isPlayerModeActive
        if (isPlayerModeActive) {
            sendPlayerCommand("STATUS", 0, "Controlo do Player Ativo")
        } else {
            playerHud.visibility = View.GONE
            Toast.makeText(this, "Modo Navegação de Catálogo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendPlayerCommand(cmd: String, valSec: Int, defaultLabel: String) {
        webView.evaluateJavascript("window.__tvPlayerAction ? window.__tvPlayerAction('$cmd', $valSec) : '{\"ok\":false}'") { raw ->
            try {
                val cleaned = raw?.trim('"')?.replace("\\\"", "\"") ?: return@evaluateJavascript
                val json = JSONObject(cleaned)
                if (json.optBoolean("ok", false)) {
                    val paused = json.optBoolean("paused", false)
                    val cur = json.optDouble("cur", -1.0)
                    val dur = json.optDouble("dur", -1.0)
                    val title = when (cmd) {
                        "TOGGLE" -> if (paused) "Pausado" else "A Reproduzir"
                        "PLAY" -> "A Reproduzir"
                        "PAUSE" -> "Pausado"
                        else -> defaultLabel
                    }
                    showPlayerHud(title, cur, dur)
                } else {
                    showPlayerHud(defaultLabel)
                }
            } catch (_: Exception) {
                showPlayerHud(defaultLabel)
            }
        }
    }

    @Deprecated("Compatibilidade Android TV")
    override fun onBackPressed() {
        when {
            customView != null -> exitCustomView()
            isPlayerModeActive -> {
                isPlayerModeActive = false
                playerHud.visibility = View.GONE
                Toast.makeText(this, "Voltou à navegação do catálogo", Toast.LENGTH_SHORT).show()
            }
            messagePanel.visibility == View.VISIBLE -> {
                messagePanel.visibility = View.GONE
                webView.requestFocus()
            }
            ::webView.isInitialized && webView.canGoBack() -> webView.goBack()
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < 2000L) {
                    finish()
                } else {
                    lastBackPressTime = now
                    Toast.makeText(this, "Pressione VOLTAR novamente para sair", Toast.LENGTH_SHORT).show()
                }
            }
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
        applyScreenWakePolicy(isPlayingVideo = false)
        hideSystemUi()
        webView.requestFocus()
    }

    private fun showAppMenu() {
        val playerLabel = if (isPlayerModeActive) "Voltar à Navegação do Catálogo" else "Controlar Player de Vídeo (D-pad)"
        val options = arrayOf(
            playerLabel,
            "Colocar Vídeo em Ecrã Inteiro",
            "Início (FrameXTV)",
            "Guardar nos Favoritos",
            "Favoritos Guardados",
            "Configurações e Escudo Anti-Spam",
            "Recarregar Página",
            "Limpar Cache e Libertar RAM"
        )
        AlertDialog.Builder(this)
            .setTitle("FrameXTV TV Pro — TCL")
            .setItems(options) { _, index ->
                when (index) {
                    0 -> togglePlayerControlMode()
                    1 -> {
                        isPlayerModeActive = true
                        sendPlayerCommand("FULLSCREEN", 0, "Ecrã Inteiro")
                    }
                    2 -> {
                        isPlayerModeActive = false
                        webView.loadUrl(homeUrl)
                    }
                    3 -> saveCurrentPageToFavorites()
                    4 -> showFavoritesDialog()
                    5 -> showSettingsDialog()
                    6 -> webView.reload()
                    7 -> {
                        webView.clearCache(true)
                        System.gc()
                        Toast.makeText(this, "Memória RAM e cache libertados.", Toast.LENGTH_SHORT).show()
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
            val item = list.getJSONObject(i)
            if (item.optString("url") != currentUrl) {
                filtered.put(item)
            }
        }
        filtered.put(JSONObject().apply {
            put("title", currentTitle)
            put("url", currentUrl)
        })
        prefs.edit().putString(KEY_FAVORITES, filtered.toString()).apply()
        Toast.makeText(this, "Favorito guardado: $currentTitle", Toast.LENGTH_SHORT).show()
    }

    private fun showFavoritesDialog() {
        val list = getFavoritesList()
        if (list.length() == 0) {
            Toast.makeText(this, "Sem favoritos guardados.", Toast.LENGTH_SHORT).show()
            return
        }

        val titles = Array(list.length()) { i -> list.getJSONObject(i).optString("title") }
        val urls = Array(list.length()) { i -> list.getJSONObject(i).optString("url") }

        AlertDialog.Builder(this)
            .setTitle("Favoritos")
            .setItems(titles) { _, index -> webView.loadUrl(urls[index]) }
            .setNeutralButton("Limpar") { _, _ ->
                prefs.edit().remove(KEY_FAVORITES).apply()
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun getFavoritesList(): JSONArray {
        val raw = prefs.getString(KEY_FAVORITES, "[]") ?: "[]"
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private fun showSettingsDialog() {
        val shield = prefs.getBoolean(KEY_STRICT_SHIELD, true)
        val currentZoom = prefs.getInt(KEY_TEXT_ZOOM, 110)
        val keepScreen = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        val resumeLast = prefs.getBoolean(KEY_RESUME_LAST, false)

        val items = arrayOf(
            "Escudo Anti-Spam, Ads e Terceiros: ${if (shield) "ATIVADO" else "Desativado"}",
            "Tamanho do texto: $currentZoom%",
            "Ecrã sempre ligado: ${if (keepScreen) "Ativado" else "Desativado"}",
            "Retomar última página ao abrir: ${if (resumeLast) "Ativado" else "Desativado"}"
        )

        AlertDialog.Builder(this)
            .setTitle("Otimização & Segurança")
            .setItems(items) { _, index ->
                when (index) {
                    0 -> {
                        val next = !shield
                        prefs.edit().putBoolean(KEY_STRICT_SHIELD, next).apply()
                        Toast.makeText(this, "Escudo Anti-Spam: ${if (next) "ATIVADO" else "Desativado"}", Toast.LENGTH_SHORT).show()
                        webView.reload()
                    }
                    1 -> {
                        val nextZoom = when (currentZoom) { 100 -> 110; 110 -> 125; else -> 100 }
                        prefs.edit().putInt(KEY_TEXT_ZOOM, nextZoom).apply()
                        webView.settings.textZoom = nextZoom
                    }
                    2 -> {
                        val next = !keepScreen
                        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, next).apply()
                        applyScreenWakePolicy(isPlayingVideo = customView != null)
                    }
                    3 -> {
                        val next = !resumeLast
                        prefs.edit().putBoolean(KEY_RESUME_LAST, next).apply()
                    }
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun applyScreenWakePolicy(isPlayingVideo: Boolean) {
        val alwaysOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        if (isPlayingVideo || alwaysOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun retryPage() {
        messagePanel.visibility = View.GONE
        if (mainFrameFailed || webView.url.isNullOrBlank()) webView.loadUrl(homeUrl) else webView.reload()
        webView.requestFocus()
    }

    private fun showMessage(text: String) {
        messagePanel.text = "$text\n\n[ Pressione OK ]"
        messagePanel.visibility = View.VISIBLE
        messagePanel.requestFocus()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && ::webView.isInitialized) {
            webView.clearCache(false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        if (customView != null) exitCustomView()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }
}
