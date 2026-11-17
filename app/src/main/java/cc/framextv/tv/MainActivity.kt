package cc.framextv.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var messagePanel: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var prefs: SharedPreferences

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var mainFrameFailed = false
    private var lastBackPressTime = 0L

    private val homeUrl = "https://framextv.cc/"
    private val allowedMainHosts = setOf("framextv.cc", "www.framextv.cc")

    companion object {
        private const val PREFS_NAME = "framextv_tcl_prefs"
        private const val KEY_FAVORITES = "local_favorites_json"
        private const val KEY_LAST_URL = "last_visited_url"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on_always"
        private const val KEY_TEXT_ZOOM = "text_zoom_percent"
        private const val KEY_RESUME_LAST = "resume_last_page"
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
            setBackgroundColor(Color.argb(242, 8, 9, 11))
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(80, 48, 80, 48)
            visibility = View.GONE
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { retryPage() }
        }

        webView = createWebView()
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        root.addView(loadingIndicator, FrameLayout.LayoutParams(-1, 8, Gravity.TOP))
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(this).apply {
        setBackgroundColor(Color.rgb(8, 9, 11))
        isFocusable = true
        isFocusableInTouchMode = true
        setLayerType(View.LAYER_TYPE_HARDWARE, null)

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
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
            textZoom = prefs.getInt(KEY_TEXT_ZOOM, 110)
            userAgentString = "$userAgentString FrameXTV-TCL/0.3 AndroidTV"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(this@apply, true)
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
                applyScreenWakePolicy(isPlayingVideo = true)
                this@MainActivity.webView.visibility = View.GONE
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
                view.requestFocus()
                hideSystemUi()
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
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme != "https") return true
                if (!request.isForMainFrame) return false

                val host = uri.host?.lowercase()
                val isAllowedMainHost = host in allowedMainHosts ||
                        allowedMainHosts.any { host?.endsWith(".$it") == true }

                if (!isAllowedMainHost) {
                    Toast.makeText(
                        this@MainActivity,
                        "Redirecionamento externo bloqueado ($host)",
                        Toast.LENGTH_SHORT
                    ).show()
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
                        "Não foi possível carregar o FrameXTV.

" +
                        "Verifique a ligação Wi-Fi ou cabo de rede da sua TCL.
" +
                        "Pressione OK para tentar novamente.

" +
                        "Detalhe técnico: ${error.description} (${error.errorCode})"
                    )
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                root.removeView(webView)
                webView.destroy()
                webView = createWebView()
                root.addView(webView, 0, FrameLayout.LayoutParams(-1, -1))
                showMessage("A memória interna foi otimizada pelo sistema.

Pressione OK para continuar.")
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                loadingIndicator.visibility = View.GONE
                injectTvEnhancements()
                mainFrameFailed = false
                messagePanel.visibility = View.GONE

                if (url.startsWith("https://framextv.cc") || url.startsWith("https://www.framextv.cc")) {
                    prefs.edit().putString(KEY_LAST_URL, url).apply()
                }
            }
        }
    }

    private fun injectTvEnhancements() {
        val js = """
          (function(){
            if(window.__framextvTvEnhanced) return;
            window.__framextvTvEnhanced = true;

            var style = document.createElement('style');
            style.textContent = `
              html, body { background: #08090b !important; }
              * { scroll-behavior: auto !important; }
              a:focus, button:focus, [role=button]:focus, input:focus, select:focus, textarea:focus, [tabindex]:focus {
                outline: 4px solid #d6ad55 !important;
                outline-offset: 4px !important;
                border-radius: 4px;
                transition: none !important;
              }
              button, a, [role=button], input, select { -webkit-tap-highlight-color: transparent; }
              video { max-width: 100%; }
            `;
            document.documentElement.appendChild(style);

            document.addEventListener('focusin', function(e) {
              var el = e.target;
              if (el && el.scrollIntoView) {
                el.scrollIntoView({ block: 'center', inline: 'nearest' });
              }
            }, true);

            function makeCardsFocusable() {
              var candidates = document.querySelectorAll('a, button, [onclick], .card, .item, article');
              candidates.forEach(function(el) {
                if (!el.hasAttribute('tabindex') && el.tagName !== 'A' && el.tagName !== 'BUTTON' && el.tagName !== 'INPUT') {
                  el.setAttribute('tabindex', '0');
                }
              });
            }
            makeCardsFocusable();
            var observer = new MutationObserver(makeCardsFocusable);
            observer.observe(document.body, { childList: true, subtree: true });
          })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_BOOKMARK -> {
                    showAppMenu()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    executeVideoCommand("if(v){v.paused?v.play():v.pause();}")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    executeVideoCommand("if(v)v.play();")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    executeVideoCommand("if(v)v.pause();")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    executeVideoCommand("if(v)v.currentTime += 15;")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    executeVideoCommand("if(v)v.currentTime = Math.max(0, v.currentTime - 15);")
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun executeVideoCommand(jsBody: String) {
        val script = "(function(){var v=document.querySelector('video');$jsBody})()"
        webView.evaluateJavascript(script, null)
    }

    @Deprecated("Compatibilidade Android TV")
    override fun onBackPressed() {
        when {
            customView != null -> exitCustomView()
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
        webView.visibility = View.VISIBLE
        applyScreenWakePolicy(isPlayingVideo = false)
        hideSystemUi()
        webView.requestFocus()
    }

    private fun showAppMenu() {
        val options = arrayOf(
            "Início (FrameXTV)",
            "Adicionar página aos Favoritos",
            "Ver Favoritos guardados",
            "Recarregar página",
            "Configurações da TV",
            "Diagnóstico de rede e memória",
            "Limpar cache local",
            "Sobre"
        )
        AlertDialog.Builder(this)
            .setTitle("Menu FrameXTV — TCL")
            .setItems(options) { _, index ->
                when (index) {
                    0 -> webView.loadUrl(homeUrl)
                    1 -> saveCurrentPageToFavorites()
                    2 -> showFavoritesDialog()
                    3 -> webView.reload()
                    4 -> showSettingsDialog()
                    5 -> showDiagnostics()
                    6 -> {
                        webView.clearCache(true)
                        Toast.makeText(this, "Cache limpo com sucesso.", Toast.LENGTH_SHORT).show()
                    }
                    7 -> showMessage(
                        "FrameXTV para TCL Google TV (v0.3.0)

" +
                        "Cliente leve otimizado para televisores com 2 GB de RAM.
" +
                        "Dica: Use o botão MENU ou definições do comando para abrir este painel a qualquer momento."
                    )
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
        val newEntry = JSONObject().apply {
            put("title", currentTitle)
            put("url", currentUrl)
        }
        filtered.put(newEntry)
        prefs.edit().putString(KEY_FAVORITES, filtered.toString()).apply()
        Toast.makeText(this, "Guardado nos favoritos: $currentTitle", Toast.LENGTH_SHORT).show()
    }

    private fun showFavoritesDialog() {
        val list = getFavoritesList()
        if (list.length() == 0) {
            Toast.makeText(this, "Ainda não tem favoritos guardados.", Toast.LENGTH_SHORT).show()
            return
        }

        val titles = Array(list.length()) { i -> list.getJSONObject(i).optString("title") }
        val urls = Array(list.length()) { i -> list.getJSONObject(i).optString("url") }

        AlertDialog.Builder(this)
            .setTitle("Favoritos Locais")
            .setItems(titles) { _, index ->
                webView.loadUrl(urls[index])
            }
            .setNeutralButton("Limpar Favoritos") { _, _ ->
                prefs.edit().remove(KEY_FAVORITES).apply()
                Toast.makeText(this, "Lista de favoritos limpa.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun getFavoritesList(): JSONArray {
        val raw = prefs.getString(KEY_FAVORITES, "[]") ?: "[]"
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun showSettingsDialog() {
        val currentZoom = prefs.getInt(KEY_TEXT_ZOOM, 110)
        val keepScreen = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        val resumeLast = prefs.getBoolean(KEY_RESUME_LAST, false)

        val items = arrayOf(
            "Tamanho do texto: $currentZoom% (Alternar 100% / 110% / 125%)",
            "Ecrã sempre ligado na navegação: ${if (keepScreen) "Ativado" else "Desativado"}",
            "Continuar da última página ao abrir: ${if (resumeLast) "Ativado" else "Desativado"}"
        )

        AlertDialog.Builder(this)
            .setTitle("Configurações")
            .setItems(items) { _, index ->
                when (index) {
                    0 -> {
                        val nextZoom = when (currentZoom) {
                            100 -> 110
                            110 -> 125
                            else -> 100
                        }
                        prefs.edit().putInt(KEY_TEXT_ZOOM, nextZoom).apply()
                        webView.settings.textZoom = nextZoom
                        Toast.makeText(this, "Escala de texto: $nextZoom%", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        val nextVal = !keepScreen
                        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, nextVal).apply()
                        applyScreenWakePolicy(isPlayingVideo = customView != null)
                        Toast.makeText(this, "Ecrã sempre ligado: ${if (nextVal) "Ativado" else "Desativado"}", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        val nextVal = !resumeLast
                        prefs.edit().putBoolean(KEY_RESUME_LAST, nextVal).apply()
                        Toast.makeText(this, "Retomar última página: ${if (nextVal) "Ativado" else "Desativado"}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showDiagnostics() {
        val runtime = Runtime.getRuntime()
        val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMemMb = runtime.maxMemory() / (1024 * 1024)
        val netStatus = if (hasInternet()) "Conectada (Internet ativa)" else "Sem ligação à Internet"

        showMessage(
            "Diagnóstico do Sistema

" +
            "Rede: $netStatus
" +
            "Memória Java App: ${usedMemMb} MB / ${maxMemMb} MB
" +
            "URL Atual: ${webView.url ?: "Nenhuma"}"
        )
    }

    private fun applyScreenWakePolicy(isPlayingVideo: Boolean) {
        val alwaysOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        if (isPlayingVideo || alwaysOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun hasInternet(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun retryPage() {
        messagePanel.visibility = View.GONE
        if (mainFrameFailed || webView.url.isNullOrBlank()) {
            webView.loadUrl(homeUrl)
        } else {
            webView.reload()
        }
        webView.requestFocus()
    }

    private fun showMessage(text: String) {
        messagePanel.text = "$text

[ Pressione OK ]"
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
