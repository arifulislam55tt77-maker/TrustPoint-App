package com.example

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

private object WebState {
    @Volatile var clearOverlayView: View? = null
    @Volatile var clearOverlayActive: Boolean = false
    @Volatile var clearInProgress: Boolean = false
    @Volatile var fileChooserOpening: Boolean = false
    @Volatile var overlayPollingRunning: Boolean = false
    @Volatile var browserBoundsRetryCount: Int = 0
    @Volatile var browserBoundsNotFound: Boolean = false
    @Volatile var telegramPopupWebView: WebView? = null
    @Volatile var telegramPopupOpen: Boolean = false
    @Volatile var telegramPopupLoadedOnce: Boolean = false
    @Volatile var telegramLocked: Boolean = false
    @Volatile var telegramCurrentUrl: String = "https://web.telegram.org/a/"
    @Volatile var telegramFallbackAttempted: Boolean = false
    @Volatile var telegramSessionEstablished: Boolean = false
}

private fun MainActivity.isActivityAlive(): Boolean {
    return try {
        !isFinishing && !isDestroyed
    } catch (_: Exception) {
        false
    }
}

internal fun MainActivity.mainWebViewOrNull(): WebView? {
    return try {
        mainWebView
    } catch (_: UninitializedPropertyAccessException) {
        null
    } catch (_: Throwable) {
        null
    }
}

internal fun MainActivity.nativeWebViewOrNull(): WebView? {
    return try {
        nativeWebView
    } catch (_: UninitializedPropertyAccessException) {
        null
    } catch (_: Throwable) {
        null
    }
}

private fun MainActivity.safeEvalJs(
    webView: WebView?,
    script: String,
    callback: ValueCallback<String>? = null
) {
    if (!isActivityAlive() || webView == null) return
    val runOnUi = {
        if (isActivityAlive()) {
            try {
                webView.evaluateJavascript(script, callback)
            } catch (_: Exception) {}
        }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) {
        runOnUi()
    } else {
        try {
            runOnUiThread(runOnUi)
        } catch (_: Exception) {}
    }
}

private fun escapeForSingleQuotedJs(input: String): String {
    return input
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("</script>", "<\\/script>", ignoreCase = true)
}

internal fun MainActivity.launchSafeFileChooser(
    callback: ValueCallback<Array<Uri>>?,
    params: WebChromeClient.FileChooserParams?,
    isMainWebView: Boolean
): Boolean {
    if (!isActivityAlive()) {
        try { callback?.onReceiveValue(null) } catch (_: Throwable) {}
        return false
    }
    showSafeProfileUploadChooser(callback, params, isMainWebView)
    return true
}

private val nonTelegramCookieDomains = listOf(
    "https://www.instagram.com",
    "https://instagram.com",
    "https://i.instagram.com",
    "https://m.instagram.com",
    "https://accountscenter.instagram.com"
)

internal fun MainActivity.clearInstagramCookiesOnly() {
    val cm = try { CookieManager.getInstance() } catch (_: Exception) { null }
    clearNonTelegramCookies(cm)
}

private fun clearNonTelegramCookies(cm: CookieManager?) {
    if (cm == null) return
    nonTelegramCookieDomains.forEach { baseUrl ->
        if (baseUrl.contains("telegram.org", ignoreCase = true)) return@forEach
        try {
            val raw = cm.getCookie(baseUrl) ?: return@forEach
            raw.split(";").forEach { part ->
                val name = part.substringBefore("=").trim()
                if (name.isNotEmpty()) {
                    cm.setCookie(baseUrl, "$name=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/")
                    cm.setCookie(baseUrl, "$name=; max-age=0; path=/")
                    cm.setCookie(baseUrl, "$name=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/; domain=.instagram.com")
                }
            }
        } catch (_: Exception) {}
    }
    try { cm.flush() } catch (_: Exception) {}
}

private fun buildSafeClearStorageJs(): String = """
    (function(){
        try {
            var h = (location.hostname || '').toLowerCase();
            if (h.indexOf('telegram.org') !== -1) return;
            try { if (window.localStorage) window.localStorage.clear(); } catch(e){}
            try { if (window.sessionStorage) window.sessionStorage.clear(); } catch(e){}
            try {
                if (window.indexedDB && typeof window.indexedDB.databases === 'function') {
                    window.indexedDB.databases().then(function(dbs){
                        if (!dbs) return;
                        dbs.forEach(function(db){
                            if (db && db.name) {
                                try { window.indexedDB.deleteDatabase(db.name); } catch(e){}
                            }
                        });
                    }).catch(function(){});
                }
            } catch(e){}
            try {
                if (window.caches && typeof window.caches.keys === 'function') {
                    window.caches.keys().then(function(names){
                        names.forEach(function(n){
                            try { window.caches.delete(n); } catch(e){}
                        });
                    }).catch(function(){});
                }
            } catch(e){}
        } catch(e){}
    })();
""".trimIndent()

internal fun MainActivity.performClearWebAction(targetUrl: String? = null) {
    runOnUiThread {
        if (!isActivityAlive()) return@runOnUiThread
        try {
            WebState.clearInProgress = true
            isProxyToggling = false
            proxyConnectionFailed = false

            val tgUrl = try { WebState.telegramPopupWebView?.url } catch (_: Exception) { null }
            if (!tgUrl.isNullOrBlank() && tgUrl.contains("telegram.org")) {
                persistTelegramSessionState(tgUrl)
            } else {
                try { CookieManager.getInstance().flush() } catch (_: Exception) {}
            }

            showClearOverlay()

            val cm = try { CookieManager.getInstance() } catch (_: Exception) { null }
            clearNonTelegramCookies(cm)

            val nativeWv = nativeWebViewOrNull()
            // Preserve Telegram WebStorage/IndexedDB completely — never wipe global WebStorage.

            val nextUa = try { rotateUserAgent() } catch (_: Exception) { mobileUserAgent }
            val effectiveUa = if (isDesktopMode) desktopUserAgent else nextUa

            if (nativeWv != null) {
                try {
                    val cur = (nativeWv.url ?: "").lowercase()
                    if (!cur.contains("telegram.org")) {
                        nativeWv.stopLoading()
                        safeEvalJs(nativeWv, buildSafeClearStorageJs())
                        nativeWv.clearCache(true)
                        nativeWv.clearFormData()
                        nativeWv.clearHistory()
                        nativeWv.clearSslPreferences()
                        nativeWv.settings.userAgentString = effectiveUa
                        nativeWv.loadUrl("about:blank")
                    }
                } catch (_: Exception) {}
            }

            val oldProfile = getPage2ProfileName()
            if (isBrowserWebViewReady()) {
                try {
                    val curB = (browserWebView.url ?: "").lowercase()
                    if (!curB.contains("telegram.org")) {
                        browserWebView.stopLoading()
                        safeEvalJs(browserWebView, buildSafeClearStorageJs())
                        browserWebView.clearCache(true)
                        browserWebView.clearFormData()
                        browserWebView.clearHistory()
                        browserWebView.clearSslPreferences()
                        browserWebView.settings.userAgentString = effectiveUa
                    }
                } catch (_: Exception) {}
            }

            clearBrowserCookies(oldProfile)
            regeneratePage2ProfileName()

            val mainWv = mainWebViewOrNull()
            if (mainWv != null) {
                safeEvalJs(
                    mainWv,
                    "(function(){try{" +
                    "var u=document.getElementById('webUid');if(u)u.value='';" +
                    "var c=document.getElementById('webCookies');if(c)c.value='';" +
                    "if(window.TP&&typeof window.TP.setCookiesData==='function')window.TP.setCookiesData('');" +
                    "if(window.TP&&typeof window.TP.onWebCleared==='function')window.TP.onWebCleared();" +
                    "}catch(e){}})();"
                )
            }

            mainHandler.postDelayed({
                if (!isActivityAlive()) return@postDelayed
                try {
                    clearNonTelegramCookies(try { CookieManager.getInstance() } catch (_: Exception) { null })
                    val destUrl = if (!targetUrl.isNullOrBlank()) targetUrl else MainActivity.INSTA_LOGIN_URL
                    lastUrl = destUrl

                    val freshNativeWv = nativeWebViewOrNull()
                    if (!WebState.telegramPopupOpen && !uiOverlayActive && !telegramPopupLocked) {
                        if (isBrowserModeOpen && isBrowserWebViewReady()) {
                            browserWebView.loadUrl(getPage2DefaultUrl())
                        } else if (freshNativeWv != null) {
                            nativeWebSuppressed = false
                            freshNativeWv.visibility = View.VISIBLE
                            freshNativeWv.translationX = 0f
                            freshNativeWv.translationY = 0f
                            freshNativeWv.loadUrl(destUrl)
                        }
                    }

                    hideClearOverlay()
                    WebState.clearInProgress = false
                    bringTelegramFabToFront()
                    toast("✓ Web cleared (TG safe)")
                } catch (e: Exception) {
                    hideClearOverlay()
                    WebState.clearInProgress = false
                    Log.e(MainActivity.TAG, "performClearWebAction reload error: ${e.message}")
                }
            }, 280)
        } catch (e: Exception) {
            hideClearOverlay()
            WebState.clearInProgress = false
            Log.e(MainActivity.TAG, "performClearWebAction error: ${e.message}")
            toast("Clear failed: ${e.message}")
        }
    }
}

private val allowedInstagramCookieKeys = setOf(
    "sessionid", "ds_user_id", "csrftoken", "mid", "ig_did",
    "rur", "datr", "ig_nrcb", "wd", "dpr", "shbid", "shbts"
)

private val orderedInstagramCookieKeys = listOf(
    "sessionid", "ds_user_id", "csrftoken", "mid", "ig_did",
    "rur", "datr", "ig_nrcb", "wd", "dpr", "shbid", "shbts"
)

internal fun MainActivity.getSingleInstagramAccountCookies(): String {
    return try {
        val cm = CookieManager.getInstance()
        try { cm.flush() } catch (_: Exception) {}

        val nativeWv = nativeWebViewOrNull()
        val activeUrl = try {
            when {
                isBrowserModeOpen && isBrowserWebViewReady() -> browserWebView.url ?: ""
                nativeWv != null -> nativeWv.url ?: ""
                else -> ""
            }
        } catch (_: Exception) { "" }

        val igTargetUrls = buildList {
            if (activeUrl.contains("instagram.com", ignoreCase = true) && !activeUrl.contains("telegram.org", ignoreCase = true)) {
                add(activeUrl)
            }
            add("https://www.instagram.com/")
            add("https://www.instagram.com")
            add("https://instagram.com/")
            add("https://i.instagram.com/")
            add("https://m.instagram.com/")
            add("https://accountscenter.instagram.com/")
        }.distinct()

        val cookieMap = LinkedHashMap<String, String>()

        igTargetUrls.forEach { targetUrl ->
            val raw = cm.getCookie(targetUrl) ?: return@forEach
            raw.split(";").forEach { part ->
                val trimmed = part.trim()
                val eqIdx = trimmed.indexOf('=')
                if (eqIdx > 0) {
                    val key = trimmed.substring(0, eqIdx).trim()
                    val value = trimmed.substring(eqIdx + 1).trim()
                    if (
                        key in allowedInstagramCookieKeys &&
                        value.isNotEmpty() &&
                        value != "\"\"" &&
                        !value.equals("null", ignoreCase = true) &&
                        !value.equals("undefined", ignoreCase = true)
                    ) {
                        cookieMap[key] = value
                    }
                }
            }
        }

        if (cookieMap.isEmpty()) return ""

        val ordered = LinkedHashMap<String, String>()
        orderedInstagramCookieKeys.forEach { k ->
            cookieMap[k]?.let { ordered[k] = it }
        }

        ordered.entries.joinToString("; ") { "${it.key}=${it.value}" }
    } catch (e: Exception) {
        Log.e(MainActivity.TAG, "getSingleInstagramAccountCookies error: ${e.message}")
        ""
    }
}

internal fun MainActivity.copySingleInstagramCookiesToClipboard(): String {
    val cookies = getSingleInstagramAccountCookies()
    runOnUiThread {
        if (!isActivityAlive()) return@runOnUiThread
        try {
            if (cookies.isBlank()) {
                toast("Instagram কুকি পাওয়া যায়নি — আগে অ্যাকাউন্ট লগইন করুন")
                return@runOnUiThread
            }

            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("ig_cookies", cookies)
            clipboard?.setPrimaryClip(clip)

            val escaped = escapeForSingleQuotedJs(cookies)

            val mainWv = mainWebViewOrNull()
            if (mainWv != null) {
                safeEvalJs(
                    mainWv,
                    "(function(){try{" +
                    "var c=document.getElementById('webCookies');if(c){c.value='$escaped';c.dispatchEvent(new Event('input',{bubbles:true}));}" +
                    "if(window.TP&&typeof window.TP.setCookiesData==='function'){window.TP.setCookiesData('$escaped');}" +
                    "if(window.TP&&typeof window.TP.onCookiesCopied==='function'){window.TP.onCookiesCopied('$escaped');}" +
                    "}catch(e){}})();"
                )
            }

            if (cookies.contains("sessionid=")) {
                toast("✓ Instagram কুকি কপি হয়েছে")
            } else {
                toast("⚠ sessionid পাওয়া যায়নি — Instagram লগইন সম্পন্ন করে আবার চাপুন")
            }
        } catch (e: Exception) {
            Log.e(MainActivity.TAG, "copySingleInstagramCookiesToClipboard error: ${e.message}")
            toast("কুকি কপি ব্যর্থ: ${e.message}")
        }
    }
    return cookies
}

internal fun MainActivity.persistTelegramSessionState(url: String? = null) {
    try {
        if (!url.isNullOrBlank() && url.contains("telegram.org") && !url.startsWith("about:")) {
            WebState.telegramCurrentUrl = url
            WebState.telegramSessionEstablished = true
        }
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("tg_last_url", WebState.telegramCurrentUrl)
            .putBoolean("tg_session_established", WebState.telegramSessionEstablished)
            .apply()
        try { CookieManager.getInstance().flush() } catch (_: Exception) {}
    } catch (_: Exception) {}
}

internal fun MainActivity.restoreTelegramSessionState() {
    try {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("tg_last_url", null)
        if (!savedUrl.isNullOrBlank() && savedUrl.contains("telegram.org")) {
            WebState.telegramCurrentUrl = savedUrl
        }
        WebState.telegramSessionEstablished = prefs.getBoolean("tg_session_established", false)
    } catch (_: Exception) {}
}

private fun normalizeTelegramTargetUrl(url: String): String {
    val clean = url.trim()
    if (clean.isBlank()) return "https://web.telegram.org/a/"
    if (
        clean == "https://web.telegram.org" ||
        clean == "https://web.telegram.org/" ||
        clean == "http://web.telegram.org/"
    ) {
        return "https://web.telegram.org/a/"
    }
    return clean
}

private fun alternateTelegramUrl(currentUrl: String): String? {
    return when {
        currentUrl.contains("web.telegram.org/a") -> currentUrl.replace("web.telegram.org/a", "web.telegram.org/k")
        currentUrl.contains("web.telegram.org/k") -> currentUrl.replace("web.telegram.org/k", "web.telegram.org/a")
        currentUrl.contains("web.telegram.org/z") -> currentUrl.replace("web.telegram.org/z", "web.telegram.org/k")
        else -> null
    }
}

private fun MainActivity.tryTelegramFallbackIfNeeded(failingUrl: String?, reasonCode: Int, reasonText: String) {
    val target = failingUrl ?: WebState.telegramCurrentUrl
    if (!target.contains("telegram.org")) return
    if (WebState.telegramFallbackAttempted) {
        Log.w(MainActivity.TAG, "TG_POPUP load failed after fallback: code=$reasonCode desc=$reasonText url=$target")
        return
    }
    val altUrl = alternateTelegramUrl(target)
    if (!altUrl.isNullOrBlank() && altUrl != target) {
        WebState.telegramFallbackAttempted = true
        WebState.telegramCurrentUrl = altUrl
        mainHandler.postDelayed({
            if (!isActivityAlive()) return@postDelayed
            try {
                if (WebState.telegramPopupOpen) {
                    WebState.telegramPopupWebView?.loadUrl(altUrl)
                }
            } catch (_: Exception) {}
        }, 250)
    }
}

internal fun MainActivity.syncTelegramFabBridge() {
    try {
        restoreTelegramSessionState()
        enforceTelegramButtonOnRight()
        updateNativeTgFabVisibility()
    } catch (_: Exception) {}
}

internal fun MainActivity.openTelegramOverlayFromNative() {
    runOnUiThread {
        if (!isActivityAlive()) return@runOnUiThread
        try {
            restoreTelegramSessionState()
            WebState.telegramPopupOpen = true
            uiOverlayActive = true
            nativeWebSuppressed = true
            hideNativeWeb()
            val mainWv = mainWebViewOrNull()
            if (mainWv != null) {
                safeEvalJs(
                    mainWv,
                    "(function(){try{" +
                    "if(window.TP&&typeof window.TP.openTelegramPopup==='function'){window.TP.openTelegramPopup();return;}" +
                    "var s=document.getElementById('tgSheetBackdrop');if(s){s.classList.add('open');}" +
                    "var p=document.getElementById('tgPopupBackdrop');if(p){p.classList.add('open');}" +
                    "}catch(e){}})();"
                )
            }
            loadTelegramPopupUrl(WebState.telegramCurrentUrl)
        } catch (_: Exception) {}
    }
}

@SuppressLint("SetJavaScriptEnabled")
internal fun MainActivity.ensureTelegramPopupWebView(): WebView? {
    if (!isActivityAlive()) return null
    return try {
        restoreTelegramSessionState()
        var wv = WebState.telegramPopupWebView
        if (wv == null) {
            wv = WebView(this)
            val s = wv.settings
            s.javaScriptEnabled = true
            s.javaScriptCanOpenWindowsAutomatically = true
            s.domStorageEnabled = true
            s.databaseEnabled = true
            s.setSupportMultipleWindows(false)
            s.allowFileAccess = true
            s.allowContentAccess = true
            s.loadWithOverviewMode = true
            s.useWideViewPort = true
            s.setSupportZoom(true)
            s.builtInZoomControls = false
            s.displayZoomControls = false
            s.cacheMode = WebSettings.LOAD_DEFAULT
            s.mediaPlaybackRequiresUserGesture = true
            s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            s.userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                try {
                    WebViewCompat.setProfile(wv, "tp_telegram_persistent_profile")
                } catch (_: Throwable) {}
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    s.isAlgorithmicDarkeningAllowed = true
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    s.forceDark = WebSettings.FORCE_DARK_ON
                }
            } catch (_: Throwable) {}

            val cm = try { CookieManager.getInstance() } catch (_: Exception) { null }
            cm?.setAcceptCookie(true)
            cm?.setAcceptThirdPartyCookies(wv, true)
            try { cm?.flush() } catch (_: Exception) {}

            wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            wv.setBackgroundColor(Color.parseColor("#0e1621"))
            wv.overScrollMode = View.OVER_SCROLL_NEVER
            wv.visibility = View.GONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                wv.translationZ = 50f
                wv.elevation = 12f
            }

            wv.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean = true

                override fun onShowFileChooser(
                    w: WebView?,
                    cb: ValueCallback<Array<Uri>>?,
                    p: FileChooserParams?
                ): Boolean {
                    return this@ensureTelegramPopupWebView.launchSafeFileChooser(cb, p, isMainWebView = false)
                }
            }

            wv.webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView?, u: String?, f: Bitmap?) {
                    super.onPageStarted(v, u, f)
                    if (!u.isNullOrBlank() && u.contains("telegram.org")) {
                        WebState.telegramCurrentUrl = u
                    }
                    v?.setBackgroundColor(Color.parseColor("#0e1621"))
                }

                override fun onPageFinished(v: WebView?, u: String?) {
                    super.onPageFinished(v, u)
                    v?.setBackgroundColor(Color.parseColor("#0e1621"))
                    WebState.telegramPopupLoadedOnce = true
                    if (!u.isNullOrBlank() && u.contains("telegram.org")) {
                        this@ensureTelegramPopupWebView.persistTelegramSessionState(u)
                    } else {
                        try { CookieManager.getInstance().flush() } catch (_: Exception) {}
                    }
                    if (WebState.telegramPopupOpen) {
                        sendTelegramPopupBounds()
                    }
                }

                override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                    val u = r?.url?.toString() ?: return false
                    if (u.startsWith("http://") || u.startsWith("https://")) return false
                    if (u.startsWith("tg://")) {
                        try {
                            val decoded = Uri.decode(u)
                            if (decoded.contains("web.telegram.org")) {
                                val idx = decoded.indexOf("https://")
                                if (idx >= 0) {
                                    v?.loadUrl(decoded.substring(idx))
                                    return true
                                }
                            }
                        } catch (_: Exception) {}
                        return true
                    }
                    return true
                }

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(v: WebView?, u: String?): Boolean {
                    if (u.isNullOrBlank()) return false
                    return !(u.startsWith("http://") || u.startsWith("https://"))
                }

                override fun onReceivedError(v: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(v, request, error)
                    if (request?.isForMainFrame == true) {
                        val desc = error?.description?.toString() ?: ""
                        val code = error?.errorCode ?: -1
                        val reqUrl = request.url?.toString() ?: WebState.telegramCurrentUrl
                        this@ensureTelegramPopupWebView.tryTelegramFallbackIfNeeded(reqUrl, code, desc)
                    }
                }

                @Suppress("DEPRECATION")
                override fun onReceivedError(v: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    super.onReceivedError(v, errorCode, description, failingUrl)
                    this@ensureTelegramPopupWebView.tryTelegramFallbackIfNeeded(failingUrl, errorCode, description ?: "")
                }

                override fun onReceivedHttpError(v: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    super.onReceivedHttpError(v, request, errorResponse)
                    if (request?.isForMainFrame == true) {
                        val status = errorResponse?.statusCode ?: -1
                        val reqUrl = request.url?.toString() ?: WebState.telegramCurrentUrl
                        if (status == 403 || status == 404 || status == 429 || status >= 500) {
                            this@ensureTelegramPopupWebView.tryTelegramFallbackIfNeeded(reqUrl, status, "HTTP_$status")
                        }
                    }
                }

                override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: SslError?) {
                    h?.proceed()
                }
            }

            registerAntiBotScript(wv)

            val parent = mainWebViewOrNull()?.parent as? ViewGroup
                ?: findViewById<ViewGroup>(android.R.id.content)
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            wv.layoutParams = lp
            parent?.addView(wv)
            WebState.telegramPopupWebView = wv
            bringTelegramFabToFront()
        }
        wv
    } catch (t: Throwable) {
        Log.e(MainActivity.TAG, "ensureTelegramPopupWebView error: ${t.message}")
        null
    }
}

internal fun MainActivity.resetTelegramPopupWebViewRef() {
    try {
        val wv = WebState.telegramPopupWebView
        WebState.telegramPopupWebView = null
        WebState.telegramPopupOpen = false
        WebState.telegramPopupLoadedOnce = false
        if (wv != null) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.destroy()
        }
    } catch (_: Throwable) {}
}

internal fun MainActivity.loadTelegramPopupUrl(url: String): Boolean {
    restoreTelegramSessionState()
    val preferredUrl = if (WebState.telegramSessionEstablished && url.trim() == "https://web.telegram.org/a/") {
        WebState.telegramCurrentUrl
    } else {
        url
    }
    val target = normalizeTelegramTargetUrl(preferredUrl)
    return try {
        runOnUiThread {
            if (!isActivityAlive()) return@runOnUiThread
            try {
                val wv = ensureTelegramPopupWebView() ?: return@runOnUiThread
                WebState.telegramPopupOpen = true
                uiOverlayActive = true
                nativeWebSuppressed = true
                hideNativeWeb()
                if (isBrowserModeOpen && isBrowserWebViewReady()) {
                    this.browserWebView.visibility = View.GONE
                }
                wv.visibility = View.VISIBLE
                wv.bringToFront()
                bringTelegramFabToFront()

                val currentLoadedUrl = wv.url ?: ""
                val alreadyHasTelegramSessionPage = WebState.telegramPopupLoadedOnce &&
                    currentLoadedUrl.contains("web.telegram.org") &&
                    currentLoadedUrl != "about:blank"

                val shouldLoadNewUrl = !alreadyHasTelegramSessionPage && currentLoadedUrl != target

                if (shouldLoadNewUrl) {
                    WebState.telegramCurrentUrl = target
                    WebState.telegramFallbackAttempted = false
                    wv.loadUrl(target)
                } else if (currentLoadedUrl.isBlank() && WebState.telegramCurrentUrl.contains("web.telegram.org")) {
                    WebState.telegramFallbackAttempted = false
                    wv.loadUrl(WebState.telegramCurrentUrl)
                }

                val delays = longArrayOf(80, 250, 600, 1200)
                delays.forEach { d ->
                    mainHandler.postDelayed({ if (isActivityAlive()) sendTelegramPopupBounds() }, d)
                }
            } catch (t: Throwable) {
                Log.e(MainActivity.TAG, "loadTelegramPopupUrl UI error: ${t.message}")
            }
        }
        true
    } catch (_: Throwable) {
        false
    }
}

internal fun MainActivity.applyTelegramPopupLockedState(locked: Boolean) {
    WebState.telegramLocked = locked
    telegramPopupLocked = locked
    backButtonBlocked = locked
    if (locked) {
        uiOverlayActive = true
        nativeWebSuppressed = true
    }
    updateNativeTgFabVisibility()
}

internal fun MainActivity.hideTelegramPopupWebViewIfClosed() {
    runOnUiThread {
        if (!isActivityAlive()) return@runOnUiThread
        try {
            val activeTgUrl = WebState.telegramPopupWebView?.url
            if (!activeTgUrl.isNullOrBlank() && activeTgUrl.contains("telegram.org")) {
                persistTelegramSessionState(activeTgUrl)
            } else {
                try { CookieManager.getInstance().flush() } catch (_: Exception) {}
            }

            val mainWv = mainWebViewOrNull() ?: return@runOnUiThread
            safeEvalJs(
                mainWv,
                "(function(){try{" +
                "var s=document.getElementById('tgSheetBackdrop');" +
                "var p=document.getElementById('tgPopupBackdrop');" +
                "var openS=(s&&s.classList&&s.classList.contains('open'));" +
                "var openP=(p&&p.classList&&p.classList.contains('open'));" +
                "return (openS||openP)?'1':'0';}catch(e){return '0';}})();"
            ) { res ->
                if (!isActivityAlive()) return@safeEvalJs
                try {
                    val isOpen = res != null && res.contains("1")
                    WebState.telegramPopupOpen = isOpen
                    val wv = WebState.telegramPopupWebView
                    if (!isOpen) {
                        wv?.visibility = View.GONE
                        uiOverlayActive = false
                        telegramPopupLocked = false
                        backButtonBlocked = false
                        nativeWebSuppressed = false
                        if (!isBrowserModeOpen && !WebState.clearInProgress) {
                            showNativeWeb()
                        }
                    } else {
                        wv?.visibility = View.VISIBLE
                        wv?.bringToFront()
                        sendTelegramPopupBounds()
                    }
                    bringTelegramFabToFront()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}

internal fun MainActivity.sendTelegramPopupBounds() {
    if (!WebState.telegramPopupOpen || !isActivityAlive()) return
    val wv = WebState.telegramPopupWebView ?: return
    val mainWv = mainWebViewOrNull() ?: return
    try {
        val js = "(function(){" +
            "try{" +
            "var b=document.querySelector('#tgSheetBackdrop .tg-sheet-body')||document.querySelector('.tg-sheet-body')||" +
            "document.querySelector('#tgPopupBackdrop .tg-win-body')||document.querySelector('.tg-win-body');" +
            "var hdr=document.querySelector('#tgSheetBackdrop .tg-sheet-header')||document.querySelector('.tg-sheet-header')||" +
            "document.querySelector('#tgPopupBackdrop .tg-win-header')||document.querySelector('.tg-win-header');" +
            "var vw=document.documentElement.clientWidth||window.innerWidth||1;" +
            "var vh=document.documentElement.clientHeight||window.innerHeight||1;" +
            "if(b){" +
            "  var r=b.getBoundingClientRect();" +
            "  var hr=hdr?hdr.getBoundingClientRect():null;" +
            "  var minTop=hr?Math.max(r.top,hr.bottom+2):r.top;" +
            "  if(r.width>=10&&r.height>=10){" +
            "    return JSON.stringify({ok:true,left:Math.max(0,r.left)/vw,top:Math.max(0,minTop)/vh,width:Math.max(0,r.width)/vw,height:Math.max(0,r.bottom-minTop)/vh});" +
            "  }" +
            "}" +
            "return JSON.stringify({ok:true,left:0.03,top:0.25,width:0.94,height:0.60});" +
            "}catch(e){return JSON.stringify({ok:true,left:0.03,top:0.25,width:0.94,height:0.60});}" +
            "})();"
        safeEvalJs(mainWv, js) { result ->
            if (!isActivityAlive()) return@safeEvalJs
            try {
                val raw = (result ?: "").trim()
                val cleaned = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                    raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                } else raw
                if (cleaned.contains("\"ok\":true")) {
                    val json = JSONObject(cleaned)
                    val l = json.optDouble("left", 0.03).toFloat()
                    val t = json.optDouble("top", 0.25).toFloat()
                    val ww = json.optDouble("width", 0.94).toFloat()
                    val hh = json.optDouble("height", 0.60).toFloat()

                    val parent = wv.parent as? ViewGroup ?: return@safeEvalJs
                    val pw = parent.width
                    val ph = parent.height
                    if (pw <= 0 || ph <= 0) return@safeEvalJs

                    val safeTopPx = (t * ph).toInt().coerceAtLeast(dpToPx(64f))
                    val maxBottomPx = (ph - dpToPx(24f)).coerceAtLeast(safeTopPx + dpToPx(120f))
                    val targetBottomPx = ((t + hh) * ph).toInt().coerceAtMost(maxBottomPx)
                    val finalHeightPx = (targetBottomPx - safeTopPx).coerceAtLeast(dpToPx(120f))

                    val lp = FrameLayout.LayoutParams(
                        (ww * pw).toInt().coerceAtLeast(1),
                        finalHeightPx
                    )
                    lp.leftMargin = (l * pw).toInt().coerceAtLeast(0)
                    lp.topMargin = safeTopPx
                    wv.layoutParams = lp
                    wv.visibility = View.VISIBLE
                    wv.bringToFront()
                    wv.requestLayout()
                    bringTelegramFabToFront()
                }
            } catch (_: Exception) {}
        }
    } catch (_: Exception) {}
}

internal fun MainActivity.isBrowserWebViewReady(): Boolean {
    return try {
        hasBrowserWebView()
    } catch (_: Throwable) { false }
}

internal fun MainActivity.getPage2ProfileName(): String {
    return try {
        getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getString("p2_profile_name", "page2_profile") ?: "page2_profile"
    } catch (_: Exception) { "page2_profile" }
}

internal fun MainActivity.regeneratePage2ProfileName() {
    try {
        val newName = "page2_profile_" + System.currentTimeMillis()
        getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("p2_profile_name", newName).apply()
    } catch (_: Exception) {}
}

internal fun MainActivity.handleProxyError(errorCode: Int, description: String) {
    Log.d(MainActivity.TAG, "Proxy error (browser): code=$errorCode desc=$description")
    try {
        if (proxyActuallyActive) {
            val reason = when {
                description.contains("authentication", true) -> "Authentication failed"
                description.contains("connection refused", true) -> "Proxy unreachable"
                description.contains("timed out", true) -> "Connection timeout"
                else -> description
            }
            updateProxyBtnUI(false, reason)
        }
    } catch (_: Exception) {}
}

internal fun MainActivity.updateProxyBtnUI(active: Boolean, message: String = "") {
    val mainWv = mainWebViewOrNull() ?: return
    try {
        val safeMsg = escapeForSingleQuotedJs(message)
        val js = "(function(){try{if(window.TP&&window.TP.updateProxyBtn)" +
                "window.TP.updateProxyBtn($active,'$safeMsg');}catch(e){}})();"
        safeEvalJs(mainWv, js)
    } catch (_: Exception) {}
}

internal fun MainActivity.injectRangeProtection() {
    val mainWv = mainWebViewOrNull() ?: return
    safeEvalJs(
        mainWv,
        "(function(){try{if(window.__tpRangeProtected)return;window.__tpRangeProtected=true;}catch(e){}})();"
    )
}

internal fun MainActivity.injectButtonHooks() {
    val mainWv = mainWebViewOrNull() ?: return
    safeEvalJs(
        mainWv,
        "(function(){try{if(window.__tpButtonHooked)return;window.__tpButtonHooked=true;}catch(e){}})();"
    )
}

internal fun MainActivity.injectOtpHistoryManager() {
    val mainWv = mainWebViewOrNull() ?: return
    safeEvalJs(
        mainWv,
        "(function(){try{if(window.__tpOtpHistoryInit)return;window.__tpOtpHistoryInit=true;}catch(e){}})();"
    )
}

internal fun MainActivity.injectMediaMute(enabled: Boolean) {
    val nativeWv = nativeWebViewOrNull()
    if (!enabled || nativeWv == null) return
    safeEvalJs(
        nativeWv,
        "(function(){try{document.querySelectorAll('video,audio').forEach(function(m){try{m.muted=true;m.volume=0;}catch(e){}});}catch(e){}})();"
    )
}

internal fun MainActivity.injectAutofill(password: String) {
    val nativeWv = nativeWebViewOrNull()
    if (password.isBlank() || nativeWv == null) return
    val esc = escapeForSingleQuotedJs(password)
    safeEvalJs(
        nativeWv,
        "(function(){try{" +
        "var p=document.querySelector('input[name=\"password\"]')||document.querySelector('input[type=\"password\"]');" +
        "if(p){var s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;" +
        "s.call(p,'$esc');p.dispatchEvent(new Event('input',{bubbles:true}));p.dispatchEvent(new Event('change',{bubbles:true}));}" +
        "}catch(e){}})();"
    )
}

internal fun MainActivity.injectUploadClickerNow() {
    val nativeWv = nativeWebViewOrNull() ?: return
    safeEvalJs(nativeWv, "(function(){try{var el=document.querySelector('input[type=\"file\"]');if(el){el.click();}}catch(e){}})();")
}

internal fun MainActivity.injectRegistrationAutomation() {
    val nativeWv = nativeWebViewOrNull() ?: return
    safeEvalJs(nativeWv, "(function(){try{var f=document.querySelector('input[name=\"email\"]')||document.querySelector('input[name=\"username\"]');if(f&&!window.__tpAutoFilled){window.__tpAutoFilled=true;}}catch(e){}})();")
}

internal fun MainActivity.injectAutoSkipViewAndOk() {
    val nativeWv = nativeWebViewOrNull() ?: return
    safeEvalJs(
        nativeWv,
        "(function(){try{var btns=document.querySelectorAll('button,div[role=\"button\"]');for(var i=0;i<btns.length;i++){var t=(btns[i].innerText||'').toLowerCase().trim();if(t==='not now'||t==='skip'||t==='ok'||t==='later'){try{btns[i].click();return;}catch(e){}}}}catch(e){}})();"
    )
}

internal fun MainActivity.injectAccountMarkingSystem() {
    val nativeWv = nativeWebViewOrNull() ?: return
    safeEvalJs(nativeWv, "(function(){try{if(window.__tpAccountMarked)return;window.__tpAccountMarked=true;}catch(e){}})();")
}

internal fun MainActivity.pushMailStatusToHtml(
    mail: String, status: String, otp: String, number: String, country: String
) {
    val mainWv = mainWebViewOrNull() ?: return
    val js = "(function(){try{if(window.TP&&window.TP.onMailStatus)" +
            "window.TP.onMailStatus('${escapeForSingleQuotedJs(mail)}','${escapeForSingleQuotedJs(status)}','${escapeForSingleQuotedJs(otp)}'," +
            "'${escapeForSingleQuotedJs(number)}','${escapeForSingleQuotedJs(country)}');}catch(e){}})();"
    safeEvalJs(mainWv, js)
}

internal fun MainActivity.pushEmailOnlyToHtml(mail: String) {
    val mainWv = mainWebViewOrNull() ?: return
    val esc = escapeForSingleQuotedJs(mail)
    val js = "(function(){try{if(window.TP&&window.TP.onEmailOnly)" +
            "window.TP.onEmailOnly('$esc');}catch(e){}})();"
    safeEvalJs(mainWv, js)
}

internal fun MainActivity.pushAutoLoginDataToPage2() {
    if (currentNumber.isBlank() || currentMailPassword.isBlank()) return
    if (!isBrowserWebViewReady()) return
    val js = "(function(){try{if(window.tpAutoLoginData)" +
            "window.tpAutoLoginData({phone:'${escapeForSingleQuotedJs(currentNumber)}'," +
            "pass:'${escapeForSingleQuotedJs(currentMailPassword)}'," +
            "twofa:'${escapeForSingleQuotedJs(current2FAKey)}'});}catch(e){}})();"
    safeEvalJs(browserWebView, js)
}

@SuppressLint("SetJavaScriptEnabled")
internal fun MainActivity.initMainWebView() {
    val mainWv = mainWebViewOrNull() ?: return
    restoreTelegramSessionState()
    val s = mainWv.settings
    s.javaScriptEnabled = true
    s.javaScriptCanOpenWindowsAutomatically = true
    s.domStorageEnabled = true
    s.databaseEnabled = true
    s.setSupportMultipleWindows(true)
    s.allowFileAccess = true
    s.allowContentAccess = true
    @Suppress("DEPRECATION") s.allowFileAccessFromFileURLs = true
    @Suppress("DEPRECATION") s.allowUniversalAccessFromFileURLs = true
    s.loadWithOverviewMode = false
    s.useWideViewPort = false
    s.cacheMode = WebSettings.LOAD_DEFAULT
    s.userAgentString = mobileUserAgent
    s.mediaPlaybackRequiresUserGesture = false

    mainWv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    mainWv.isHorizontalScrollBarEnabled = false
    mainWv.isVerticalScrollBarEnabled = false
    mainWv.overScrollMode = View.OVER_SCROLL_NEVER

    mainWv.setBackgroundColor(Color.BLACK)
    try { findViewById<View>(android.R.id.content)?.setBackgroundColor(Color.BLACK) } catch (_: Exception) {}
    try { mainWv.setSoundEffectsEnabled(false) } catch (_: Exception) {}

    webInterface = WebInterface(this, mainWv)
    mainWv.addJavascriptInterface(webInterface, "Android")
    mainWv.addJavascriptInterface(SheetBridge(), "SheetAPI")
    mainWv.addJavascriptInterface(BrowserBridge(), "BrowserAPI")

    mainWv.webChromeClient = object : WebChromeClient() {
        override fun onShowFileChooser(
            w: WebView?,
            cb: ValueCallback<Array<Uri>>?,
            p: FileChooserParams?
        ): Boolean {
            return this@initMainWebView.launchSafeFileChooser(cb, p, isMainWebView = true)
        }

        override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
            Log.d(MainActivity.TAG, "HTML[${msg.messageLevel()}]: ${msg.message()}")
            return true
        }

        override fun onCreateWindow(
            view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
        ): Boolean {
            return try {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val tempWebView = WebView(this@initMainWebView)
                tempWebView.setBackgroundColor(Color.BLACK)
                tempWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                        val u = r?.url?.toString() ?: return false
                        try { this@initMainWebView.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (_: Exception) {}
                        return true
                    }
                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(v: WebView?, u: String?): Boolean {
                        if (u.isNullOrBlank()) return false
                        try { this@initMainWebView.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (_: Exception) {}
                        return true
                    }
                }
                transport.webView = tempWebView
                resultMsg.sendToTarget()
                true
            } catch (_: Exception) { false }
        }
    }

    mainWv.webViewClient = object : WebViewClient() {
        override fun onPageStarted(v: WebView?, u: String?, f: Bitmap?) {
            super.onPageStarted(v, u, f)
            if (this@initMainWebView.isBlockedExternalScheme(u)) { try { v?.stopLoading() } catch (_: Exception) {}; return }
            v?.setBackgroundColor(Color.BLACK)
        }
        override fun onPageFinished(v: WebView?, u: String?) {
            super.onPageFinished(v, u)
            if (!this@initMainWebView.isActivityAlive()) return
            v?.setBackgroundColor(Color.BLACK)
            this@initMainWebView.htmlBootReady = true
            this@initMainWebView.tryRevealBoot()
            this@initMainWebView.bringTelegramFabToFront()
            this@initMainWebView.mainHandler.postDelayed({ this@initMainWebView.fixSnackbarStyle() }, 150)
            this@initMainWebView.mainHandler.postDelayed({ this@initMainWebView.injectRangeProtection() }, 200)
            this@initMainWebView.mainHandler.postDelayed({ this@initMainWebView.pushLiveCredsToHtml() }, 400)
            this@initMainWebView.mainHandler.postDelayed({ this@initMainWebView.injectButtonHooks() }, 300)
            this@initMainWebView.mainHandler.postDelayed({ try { this@initMainWebView.injectOtpHistoryManager() } catch (_: Exception) {} }, 500)
            this@initMainWebView.mainHandler.postDelayed({
                try {
                    if (this@initMainWebView.currentMail.isNotEmpty() && this@initMainWebView.activeBoxSource == "mail")
                        this@initMainWebView.pushMailStatusToHtml(this@initMainWebView.currentMail, this@initMainWebView.currentMailStatus, this@initMainWebView.currentOtp, this@initMainWebView.currentNumber, this@initMainWebView.currentCountry)
                    else if (this@initMainWebView.currentNumber.isNotEmpty() && this@initMainWebView.activeBoxSource == "number")
                        this@initMainWebView.pushMailStatusToHtml("", "idle", "", this@initMainWebView.currentNumber, this@initMainWebView.currentCountry)
                    if (this@initMainWebView.currentMail.isNotEmpty()) this@initMainWebView.pushEmailOnlyToHtml(this@initMainWebView.currentMail)
                } catch (_: Exception) {}
            }, 350)
            this@initMainWebView.mainHandler.postDelayed({ try { this@initMainWebView.lockProtectedText() } catch (_: Exception) {} }, 1200)
            this@initMainWebView.mainHandler.postDelayed({ try { this@initMainWebView.updateProxyBtnUI(this@initMainWebView.proxyActuallyActive) } catch (_: Exception) {} }, 450)
        }
        override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean =
            this@initMainWebView.isBlockedExternalScheme(r?.url?.toString())
    }

    mainWv.loadUrl("file:///android_asset/InstaRaj_design.html?v=" + System.currentTimeMillis())
    syncTelegramFabBridge()
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
internal fun MainActivity.initNativeWebView() {
    val nativeWv = nativeWebViewOrNull() ?: return
    val s = nativeWv.settings
    s.javaScriptEnabled = true
    s.javaScriptCanOpenWindowsAutomatically = true
    s.domStorageEnabled = true
    s.databaseEnabled = true
    s.setSupportMultipleWindows(false)
    s.allowFileAccess = true
    s.allowContentAccess = true
    @Suppress("DEPRECATION") s.allowFileAccessFromFileURLs = true
    @Suppress("DEPRECATION") s.allowUniversalAccessFromFileURLs = true

    s.loadWithOverviewMode = true
    s.useWideViewPort = true
    s.setSupportZoom(true)
    s.builtInZoomControls = false
    s.displayZoomControls = false
    s.cacheMode = WebSettings.LOAD_DEFAULT
    s.userAgentString = mobileUserAgent
    s.mediaPlaybackRequiresUserGesture = true
    s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    s.textZoom = 100

    applyForceDark(true)
    nativeWv.overScrollMode = View.OVER_SCROLL_NEVER
    nativeWv.setBackgroundColor(Color.BLACK)

    try { nativeWv.setSoundEffectsEnabled(false) } catch (_: Exception) {}
    try { nativeWv.isHapticFeedbackEnabled = false } catch (_: Exception) {}

    val cm = try { CookieManager.getInstance() } catch (_: Exception) { null }
    cm?.setAcceptCookie(true)
    cm?.setAcceptThirdPartyCookies(nativeWv, true)

    nativeWv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    nativeWv.isHorizontalScrollBarEnabled = false
    nativeWv.isVerticalScrollBarEnabled = false
    nativeWv.isClickable = true
    nativeWv.isEnabled = true
    nativeWv.isFocusable = true
    nativeWv.isFocusableInTouchMode = true
    nativeWv.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

    nativeWv.setOnTouchListener { _, e ->
        if (e.action == MotionEvent.ACTION_DOWN) {
            safeEvalJs(nativeWv, "(function(){try{window.__tpUserActive=Date.now();}catch(e){}})();")
            bringTelegramFabToFront()
        }
        false
    }

    nativeWv.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
            Log.d(MainActivity.TAG, "NATIVE[${msg.messageLevel()}]: ${msg.message()}")
            return true
        }
        override fun onCreateWindow(v: WebView?, i: Boolean, g: Boolean, m: Message?): Boolean = false

        override fun onShowFileChooser(
            w: WebView?,
            cb: ValueCallback<Array<Uri>>?,
            p: FileChooserParams?
        ): Boolean {
            val pending = this@initNativeWebView.pendingUploadUri
            if (pending != null && !this@initNativeWebView.uploadConsumed && this@initNativeWebView.uploadSession) {
                return try {
                    this@initNativeWebView.uploadConsumed = true
                    this@initNativeWebView.uploadSession = false
                    cb?.onReceiveValue(arrayOf(pending))
                    this@initNativeWebView.pendingUploadUri = null
                    this@initNativeWebView.uploadTimeoutRunnable?.let { this@initNativeWebView.mainHandler.removeCallbacks(it) }
                    this@initNativeWebView.uploadTimeoutRunnable = null
                    true
                } catch (_: Exception) {
                    try { cb?.onReceiveValue(null) } catch (_: Exception) {}
                    this@initNativeWebView.pendingUploadUri = null
                    this@initNativeWebView.uploadSession = false
                    false
                }
            }
            return this@initNativeWebView.launchSafeFileChooser(cb, p, isMainWebView = false)
        }
    }

    nativeWv.webViewClient = object : WebViewClient() {
        override fun onPageStarted(v: WebView?, u: String?, f: Bitmap?) {
            super.onPageStarted(v, u, f)
            if (this@initNativeWebView.isBlockedExternalScheme(u)) { try { v?.stopLoading() } catch (_: Exception) {}; return }
            v?.setBackgroundColor(Color.BLACK)
            this@initNativeWebView.bringTelegramFabToFront()

            val onInstagram = u != null && (u.contains("instagram.com") || u.contains("cdninstagram.com"))
            if (this@initNativeWebView.automationSoundMuted || (this@initNativeWebView.autoRegActive && onInstagram)) {
                safeEvalJs(
                    v,
                    "(function(){try{" +
                    "if(HTMLMediaElement&&!HTMLMediaElement.prototype.__tpOrigPlay){" +
                    "HTMLMediaElement.prototype.__tpOrigPlay=HTMLMediaElement.prototype.play;" +
                    "HTMLMediaElement.prototype.play=function(){try{this.muted=true;this.volume=0;this.pause();}catch(e){}return Promise.resolve();};" +
                    "}" +
                    "}catch(e){}})();"
                )
                try { this@initNativeWebView.injectMediaMute(true) } catch (_: Exception) {}
            }
        }

        override fun onPageCommitVisible(v: WebView?, u: String?) {
            super.onPageCommitVisible(v, u)
            if (WebState.clearOverlayActive && !WebState.clearInProgress) this@initNativeWebView.hideClearOverlay()
            this@initNativeWebView.bringTelegramFabToFront()

            if (this@initNativeWebView.savedPassword.isNotEmpty()) {
                this@initNativeWebView.injectAutofill(this@initNativeWebView.savedPassword)
            }

            val onInstagram = u != null && (u.contains("instagram.com") || u.contains("cdninstagram.com"))
            if (this@initNativeWebView.automationSoundMuted || (this@initNativeWebView.autoRegActive && onInstagram)) {
                this@initNativeWebView.injectMediaMute(true)
            }
            if (this@initNativeWebView.uploadSession && !this@initNativeWebView.uploadConsumed && this@initNativeWebView.pendingUploadUri != null) {
                this@initNativeWebView.mainHandler.postDelayed({ this@initNativeWebView.injectUploadClickerNow() }, 450)
            }
        }

        override fun onPageFinished(v: WebView?, u: String?) {
            super.onPageFinished(v, u)
            if (!this@initNativeWebView.isActivityAlive()) return
            v?.setBackgroundColor(Color.BLACK)

            this@initNativeWebView.nativeBootReady = true
            this@initNativeWebView.tryRevealBoot()
            this@initNativeWebView.bringTelegramFabToFront()
            if (WebState.clearOverlayActive && !WebState.clearInProgress) this@initNativeWebView.hideClearOverlay()
            try { CookieManager.getInstance().flush() } catch (_: Exception) {}
            if (u != null) this@initNativeWebView.handleNativePageFinished(u)

            if (this@initNativeWebView.savedPassword.isNotEmpty()) {
                this@initNativeWebView.injectAutofill(this@initNativeWebView.savedPassword)
            }

            val onInstagram = u != null && (u.contains("instagram.com") || u.contains("cdninstagram.com"))
            if (this@initNativeWebView.automationSoundMuted || (this@initNativeWebView.autoRegActive && onInstagram)) {
                this@initNativeWebView.injectMediaMute(true)
            }
            try { v?.isFocusable = true; v?.isFocusableInTouchMode = true; v?.isClickable = true; v?.isEnabled = true } catch (_: Exception) {}

            val onSignup = u != null && (
                u.contains("/accounts/emailsignup") ||
                u.contains("/accounts/signup") ||
                u.contains("/accounts/register") ||
                u.contains("/challenge/")
            )
            if (this@initNativeWebView.autoRegActive && onSignup) {
                this@initNativeWebView.injectRegistrationAutomation()
            }
            if (this@initNativeWebView.uploadSession && !this@initNativeWebView.uploadConsumed && this@initNativeWebView.pendingUploadUri != null) {
                this@initNativeWebView.mainHandler.postDelayed({ this@initNativeWebView.injectUploadClickerNow() }, 700)
            }
            if (onInstagram && !onSignup) {
                this@initNativeWebView.mainHandler.postDelayed({ this@initNativeWebView.pushLiveCredsToHtml() }, 400)
                this@initNativeWebView.mainHandler.postDelayed({ this@initNativeWebView.pushLiveCredsToHtml() }, 1800)
            }
            this@initNativeWebView.mainHandler.postDelayed({ try { this@initNativeWebView.injectAutoSkipViewAndOk() } catch (_: Exception) {} }, 400)
            this@initNativeWebView.mainHandler.postDelayed({ try { this@initNativeWebView.injectAutoSkipViewAndOk() } catch (_: Exception) {} }, 2200)
            this@initNativeWebView.mainHandler.postDelayed({ try { this@initNativeWebView.injectAccountMarkingSystem() } catch (_: Exception) {} }, 500)

            try {
                if (u != null && u.contains("accountscenter.instagram.com") && u.contains("two_factor")) {
                    this@initNativeWebView.mainHandler.postDelayed({
                        try { this@initNativeWebView.startTwoFactorAutomation() } catch (_: Exception) {}
                    }, 2200)
                }
            } catch (_: Exception) {}
        }

        override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
            val u = r?.url?.toString() ?: return false
            if (this@initNativeWebView.isBlockedExternalScheme(u)) return true
            return !isAllowedInstagramDomain(u)
        }

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(v: WebView?, u: String?): Boolean {
            if (u.isNullOrBlank()) return false
            if (this@initNativeWebView.isBlockedExternalScheme(u)) return true
            return !isAllowedInstagramDomain(u)
        }

        override fun onReceivedError(v: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(v, request, error)
            if (request?.isForMainFrame == true && error != null) {
                this@initNativeWebView.handleProxyErrorMain(error.errorCode, error.description?.toString() ?: "")
            }
        }

        override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
            if (this@initNativeWebView.currentProxyUser.isNotBlank()) {
                handler?.proceed(this@initNativeWebView.currentProxyUser, this@initNativeWebView.currentProxyPass)
            } else {
                super.onReceivedHttpAuthRequest(view, handler, host, realm)
            }
        }

        override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: SslError?) { h?.proceed() }
    }

    nativeWv.addJavascriptInterface(NativeAutomationBridge(this), "Android")
    registerDarkModeDocumentStartScript()
    registerAntiBotScript(nativeWv)
    syncTelegramFabBridge()
}

private fun isAllowedInstagramDomain(u: String): Boolean {
    return u.contains("instagram.com") ||
           u.contains("cdninstagram.com") ||
           u.contains("accountscenter.instagram.com") ||
           u.contains("help.instagram.com") ||
           u.contains("google.com") ||
           u.contains("gstatic.com")
}

internal fun MainActivity.buildAntiBotJs(): String = """
    (function(){
        try {
            try {
                Object.defineProperty(navigator, 'webdriver', { get: function() { return false; }, configurable: true });
                try { delete Object.getPrototypeOf(navigator).webdriver; } catch(e) {}
            } catch(e) {}
            try {
                Object.defineProperty(navigator, 'languages', { get: function() { return ['en-US', 'en']; } });
                Object.defineProperty(navigator, 'language', { get: function() { return 'en-US'; } });
                Object.defineProperty(navigator, 'maxTouchPoints', { get: function() { return 5; } });
                Object.defineProperty(navigator, 'hardwareConcurrency', { get: function() { return 8; } });
                Object.defineProperty(navigator, 'deviceMemory', { get: function() { return 8; } });
                Object.defineProperty(navigator, 'platform', { get: function() { return 'Linux armv8l'; } });
            } catch(e) {}
        } catch(e) {}
    })();
""".trimIndent()

internal fun MainActivity.registerAntiBotScript(webView: WebView) {
    try {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        WebViewCompat.addDocumentStartJavaScript(webView, buildAntiBotJs(), setOf("*"))
    } catch (e: Throwable) {
        Log.e(MainActivity.TAG, "AntiBot Script Error: ${e.message}")
    }
}

@SuppressLint("SetJavaScriptEnabled")
internal fun MainActivity.initBrowserWebView(forceRecreate: Boolean = false) {
    if (!isActivityAlive()) return
    if (!forceRecreate && isBrowserWebViewReady()) return

    if (forceRecreate && isBrowserWebViewReady()) {
        try {
            val parent = browserWebView.parent as? ViewGroup
            parent?.removeView(browserWebView)
            browserWebView.removeAllViews()
            browserWebView.destroy()
        } catch (_: Exception) {}
    }

    browserWebView = WebView(this)
    val s = browserWebView.settings
    s.javaScriptEnabled = true
    s.domStorageEnabled = true
    s.databaseEnabled = true
    s.setSupportMultipleWindows(true)
    s.allowFileAccess = true
    s.allowContentAccess = true
    s.loadWithOverviewMode = true
    s.useWideViewPort = true
    s.setSupportZoom(true)
    s.builtInZoomControls = false
    s.displayZoomControls = false
    s.mediaPlaybackRequiresUserGesture = true
    s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    s.cacheMode = WebSettings.LOAD_DEFAULT
    s.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    s.javaScriptCanOpenWindowsAutomatically = true

    if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
        try {
            WebViewCompat.setProfile(this.browserWebView, getPage2ProfileName())
        } catch (_: Exception) {}
    }

    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            s.isAlgorithmicDarkeningAllowed = true
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            s.forceDark = WebSettings.FORCE_DARK_ON
        }
    } catch (_: Throwable) {}

    browserWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    browserWebView.setBackgroundColor(Color.BLACK)

    val cm = try { CookieManager.getInstance() } catch (_: Exception) { null }
    cm?.setAcceptCookie(true)
    cm?.setAcceptThirdPartyCookies(browserWebView, true)

    browserWebView.webChromeClient = object : WebChromeClient() {
        override fun onShowFileChooser(
            w: WebView?, cb: ValueCallback<Array<Uri>>?, p: FileChooserParams?
        ): Boolean = this@initBrowserWebView.launchSafeFileChooser(cb, p, isMainWebView = false)
    }

    browserWebView.webViewClient = object : WebViewClient() {
        override fun onPageStarted(v: WebView?, u: String?, f: Bitmap?) {
            super.onPageStarted(v, u, f)
            if (this@initBrowserWebView.isBlockedExternalScheme(u)) { try { v?.stopLoading() } catch (_: Exception) {}; return }
            v?.setBackgroundColor(Color.BLACK)
        }
        override fun onPageFinished(v: WebView?, u: String?) {
            super.onPageFinished(v, u)
            if (!this@initBrowserWebView.isActivityAlive()) return
            v?.setBackgroundColor(Color.BLACK)
            this@initBrowserWebView.pushAutoLoginDataToPage2()
            if (this@initBrowserWebView.isBrowserModeOpen) this@initBrowserWebView.sendBrowserWebBounds()
            this@initBrowserWebView.mainHandler.postDelayed({ try { this@initBrowserWebView.updateProxyBtnUI(this@initBrowserWebView.proxyActuallyActive) } catch (_: Exception) {} }, 300)
        }

        override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
            val u = r?.url?.toString() ?: return false
            if (u.startsWith("http://") || u.startsWith("https://")) return false
            return true
        }

        override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: SslError?) { h?.proceed() }

        override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
            if (this@initBrowserWebView.currentProxyUser.isNotBlank()) {
                handler?.proceed(this@initBrowserWebView.currentProxyUser, this@initBrowserWebView.currentProxyPass)
            } else {
                handler?.cancel()
            }
        }

        override fun onReceivedError(v: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(v, request, error)
            if (request?.isForMainFrame == true && error != null) {
                this@initBrowserWebView.handleProxyError(error.errorCode, error.description?.toString() ?: "")
            }
        }
    }

    registerAntiBotScript(browserWebView)

    try {
        val parent = mainWebViewOrNull()?.parent as? ViewGroup
        val target = parent ?: findViewById<ViewGroup>(android.R.id.content)
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        browserWebView.layoutParams = params
        browserWebView.visibility = View.GONE
        target?.addView(browserWebView)
        bringTelegramFabToFront()
    } catch (_: Exception) {}
}

internal fun MainActivity.openBrowserMode() {
    runOnUiThread {
        if (!isActivityAlive()) return@runOnUiThread
        try {
            if (!isBrowserWebViewReady()) initBrowserWebView()
            isBrowserModeOpen = true
            hideNativeWeb()
            this.browserWebView.visibility = View.VISIBLE
            this.browserWebView.setBackgroundColor(Color.BLACK)
            bringTelegramFabToFront()
            loadPage2UrlIfNeeded()
        } catch (_: Exception) {}
    }
}

internal fun MainActivity.getPage2DefaultUrl(): String {
    val mode = try {
        getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(MainActivity.KEY_P2_MODE, "login") ?: "login"
    } catch (_: Exception) { "login" }
    return if (mode == "login")
        "https://www.instagram.com/accounts/login/?source=auth_switcher"
    else
        "https://www.instagram.com/accounts/password/reset/"
}

internal fun MainActivity.loadPage2UrlIfNeeded() {
    if (!isBrowserModeOpen || !isBrowserWebViewReady()) return
    if (this.browserWebView.url.isNullOrEmpty()) {
        this.browserWebView.loadUrl(getPage2DefaultUrl())
    } else {
        pushAutoLoginDataToPage2()
    }
}

internal fun MainActivity.closeBrowserMode() {
    runOnUiThread {
        if (!isActivityAlive()) return@runOnUiThread
        isBrowserModeOpen = false
        if (isBrowserWebViewReady()) this.browserWebView.visibility = View.GONE
        if (!uiOverlayActive && !telegramPopupLocked) showNativeWeb()
        bringTelegramFabToFront()
    }
}

internal fun MainActivity.clearBrowserCookies(oldProfileName: String) {
    if (oldProfileName.isBlank() || oldProfileName == "tp_telegram_persistent_profile") return
    if (Looper.myLooper() != Looper.getMainLooper()) {
        try { runOnUiThread { clearBrowserCookies(oldProfileName) } } catch (_: Exception) {}
        return
    }
    try {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try { ProfileStore.getInstance().getProfile(oldProfileName)?.cookieManager?.removeAllCookies(null) } catch (_: Exception) {}
            try { ProfileStore.getInstance().deleteProfile(oldProfileName) } catch (_: Exception) {}
        }
    } catch (_: Exception) {}
}

internal fun MainActivity.setBrowserWebBounds(leftF: Float, topF: Float, widthF: Float, heightF: Float) {
    try {
        if (!isBrowserWebViewReady() || !isActivityAlive()) return
        val parent = this.browserWebView.parent as? View ?: return
        val w = parent.width
        val h = parent.height
        if (w <= 0 || h <= 0) return

        var l = leftF; var t = topF; var ww = widthF; var hh = heightF
        if (l < 0) l = 0f; if (t < 0) t = 0f
        if (l + ww > 1f) ww = 1f - l; if (t + hh > 1f) hh = 1f - t
        if (ww <= 0f || hh <= 0f) return

        val params = FrameLayout.LayoutParams((ww * w).toInt().coerceAtLeast(1), (hh * h).toInt().coerceAtLeast(1))
        params.leftMargin = (l * w).toInt().coerceAtLeast(0)
        params.topMargin = (t * h).toInt().coerceAtLeast(0)
        this.browserWebView.layoutParams = params
        this.browserWebView.requestLayout()
        bringTelegramFabToFront()
    } catch (_: Exception) {}
}

internal fun MainActivity.sendBrowserWebBounds() {
    val mainWv = mainWebViewOrNull() ?: return
    if (WebState.browserBoundsNotFound || !isActivityAlive()) return
    try {
        val js = "(function(){try{var el=document.getElementById('brWebArea')||document.getElementById('webArea');if(!el)return JSON.stringify({ok:false,reason:'no-el'});var r=el.getBoundingClientRect();var vh=document.documentElement.clientHeight||window.innerHeight||1;var vw=document.documentElement.clientWidth||window.innerWidth||1;if(r.width<5||r.height<5)return JSON.stringify({ok:false});return JSON.stringify({ok:true,left:Math.max(0,r.left)/vw,top:Math.max(0,r.top)/vh,width:Math.max(0,r.width)/vw,height:Math.max(0,r.height)/vh});}catch(e){return JSON.stringify({ok:false});}})();"
        safeEvalJs(mainWv, js) { result ->
            if (!isActivityAlive()) return@safeEvalJs
            try {
                val raw = (result ?: "").trim().let {
                    if (it.startsWith("\"") && it.endsWith("\"")) it.substring(1, it.length - 1).replace("\\\"", "\"").replace("\\\\", "\\") else it
                }
                if (raw.contains("\"ok\":true")) {
                    val json = JSONObject(raw)
                    setBrowserWebBounds(
                        json.optDouble("left", 0.0).toFloat(),
                        json.optDouble("top", 0.0).toFloat(),
                        json.optDouble("width", 1.0).toFloat(),
                        json.optDouble("height", 1.0).toFloat()
                    )
                }
            } catch (_: Exception) {}
        }
    } catch (_: Exception) {}
}

internal fun MainActivity.setNativeWebBounds(leftF: Float, topF: Float, widthF: Float, heightF: Float) {
    val nativeWv = nativeWebViewOrNull() ?: return
    try {
        if (!isActivityAlive()) return
        if (uiOverlayActive || telegramPopupLocked || WebState.telegramPopupOpen) {
            nativeWv.visibility = View.INVISIBLE
            return
        }
        val mainWv = mainWebViewOrNull()
        val w = mainWv?.width ?: 0
        val h = mainWv?.height ?: 0
        if (w <= 0 || h <= 0) return
        try { (nativeWv.parent as? ViewGroup)?.let { it.clipChildren = true; it.clipToPadding = true } } catch (_: Exception) {}
        val params = FrameLayout.LayoutParams((widthF * w).toInt().coerceAtLeast(1), (heightF * h).toInt().coerceAtLeast(1))
        params.leftMargin = (leftF * w).toInt().coerceAtLeast(0)
        params.topMargin = (topF * h).toInt().coerceAtLeast(0)
        nativeWv.layoutParams = params
        if (!nativeWebSuppressed && !WebState.clearInProgress && nativeWv.visibility != View.VISIBLE) {
            nativeWv.visibility = View.VISIBLE
            nativeWv.isFocusable = true; nativeWv.isFocusableInTouchMode = true
            nativeWv.isClickable = true; nativeWv.isEnabled = true
        }
        bringTelegramFabToFront()
    } catch (_: Exception) {}
}

internal fun MainActivity.loadInNativeWeb(url: String) {
    val nativeWv = nativeWebViewOrNull() ?: return
    if (url.isBlank() || !isActivityAlive()) return
    if (isBlockedExternalScheme(url)) return
    if (url.contains("telegram.org")) {
        openTelegramOverlayInHtml(url)
        return
    }
    try {
        lastUrl = url
        uiOverlayActive = false
        telegramPopupLocked = false
        backButtonBlocked = false
        WebState.telegramPopupOpen = false
        try { WebState.telegramPopupWebView?.visibility = View.GONE } catch (_: Exception) {}

        nativeWebSuppressed = false
        nativeWv.setBackgroundColor(Color.BLACK)
        nativeWv.visibility = View.VISIBLE
        nativeWv.isFocusable = true; nativeWv.isFocusableInTouchMode = true
        nativeWv.isClickable = true; nativeWv.isEnabled = true
        nativeWv.translationX = 0f; nativeWv.translationY = 0f
        nativeWv.loadUrl(url)
        bringTelegramFabToFront()
    } catch (_: Exception) {}
}

internal fun MainActivity.reloadNativeWeb() {
    val nativeWv = nativeWebViewOrNull() ?: return
    if (!isActivityAlive()) return
    try {
        nativeWv.setBackgroundColor(Color.BLACK)
        if (darkModeEnabled) injectDarkModeCss(true)
        if (nativeWv.url.isNullOrEmpty()) nativeWv.loadUrl(if (lastUrl.isNotEmpty()) lastUrl else MainActivity.HOME_URL)
        else nativeWv.reload()
        bringTelegramFabToFront()
    } catch (_: Exception) {}
}

internal fun MainActivity.hideNativeWeb() {
    val nativeWv = nativeWebViewOrNull() ?: return
    try {
        nativeWebSuppressed = true
        try { nativeWv.animate().cancel() } catch (_: Exception) {}
        if (nativeWv.visibility == View.VISIBLE) {
            nativeWv.visibility = View.INVISIBLE
            mainHandler.postDelayed({
                val curNativeWv = nativeWebViewOrNull()
                if (nativeWebSuppressed && isActivityAlive() && curNativeWv != null) {
                    curNativeWv.visibility = View.GONE
                }
            }, 150)
        } else {
            nativeWv.visibility = View.GONE
        }
        nativeWv.isFocusable = false
        nativeWv.isFocusableInTouchMode = false
        nativeWv.isClickable = false
        bringTelegramFabToFront()
    } catch (_: Exception) {}
}

internal fun MainActivity.showNativeWeb() {
    val nativeWv = nativeWebViewOrNull() ?: return
    try {
        if (!isActivityAlive()) return
        if (WebState.telegramPopupOpen || uiOverlayActive || telegramPopupLocked || WebState.clearInProgress) return
        nativeWebSuppressed = false
        nativeWv.visibility = View.VISIBLE
        nativeWv.translationX = 0f
        nativeWv.translationY = 0f
        nativeWv.isFocusable = true
        nativeWv.isFocusableInTouchMode = true
        nativeWv.isClickable = true
        nativeWv.isEnabled = true
        bringTelegramFabToFront()
    } catch (_: Exception) {}
}

internal fun MainActivity.setUiOverlayActive(active: Boolean) {
    if (!isActivityAlive()) return
    uiOverlayActive = active || telegramPopupLocked
    if (uiOverlayActive) {
        try { mainWebViewOrNull()?.setBackgroundColor(Color.BLACK) } catch (_: Exception) {}
        hideNativeWeb()
        if (isBrowserModeOpen) {
            try { if (isBrowserWebViewReady()) this.browserWebView.visibility = View.GONE } catch (_: Exception) {}
        }
        if (WebState.telegramPopupOpen) sendTelegramPopupBounds()
    } else {
        WebState.telegramPopupOpen = false
        try { WebState.telegramPopupWebView?.visibility = View.GONE } catch (_: Exception) {}
        if (!isBrowserModeOpen && !WebState.clearInProgress) showNativeWeb()
        if (isBrowserModeOpen) {
            try { if (isBrowserWebViewReady()) this.browserWebView.visibility = View.VISIBLE } catch (_: Exception) {}
        }
    }
    bringTelegramFabToFront()
}

internal fun MainActivity.startOverlayPolling() {
    if (WebState.overlayPollingRunning) return
    WebState.overlayPollingRunning = true
    mainHandler.post(object : Runnable {
        override fun run() {
            if (!isActivityAlive()) {
                WebState.overlayPollingRunning = false
                return
            }
            try {
                val mainWv = mainWebViewOrNull()
                val nativeWv = nativeWebViewOrNull()
                if (WebState.clearInProgress || WebState.fileChooserOpening || mainWv == null || nativeWv == null) {
                    mainHandler.postDelayed(this, 350)
                    return
                }
                safeEvalJs(
                    mainWv,
                    "(function(){try{var ov=document.getElementById('overlay');var dr=document.getElementById('drawer');" +
                    "var mb=document.getElementById('modalBackdrop');" +
                    "var ua=document.getElementById('uaModalBackdrop');" +
                    "var tgSheet=document.getElementById('tgSheetBackdrop');" +
                    "var tgOpen=(tgSheet&&tgSheet.classList&&tgSheet.classList.contains('open'))?1:0;" +
                    "var anyOpen=((ov&&ov.classList&&ov.classList.contains('open'))||" +
                    "(dr&&dr.classList&&dr.classList.contains('open'))||" +
                    "(mb&&mb.classList&&mb.classList.contains('open'))||" +
                    "(ua&&ua.classList&&ua.classList.contains('open'))||" +
                    "(tgOpen===1))?1:0;" +
                    "return anyOpen+':'+tgOpen;}catch(e){return '0:0';}})();"
                ) { result ->
                    if (!isActivityAlive() || WebState.clearInProgress) return@safeEvalJs
                    try {
                        val curNativeWv = nativeWebViewOrNull() ?: return@safeEvalJs
                        val raw = (result ?: "0:0").replace("\"", "").trim()
                        val parts = raw.split(":")
                        val overlayOpen = parts.getOrNull(0) == "1" || telegramPopupLocked
                        val tgOpen = parts.getOrNull(1) == "1"
                        uiOverlayActive = overlayOpen
                        WebState.telegramPopupOpen = tgOpen
                        val tgWv = WebState.telegramPopupWebView
                        if (tgWv != null) {
                            if (tgOpen) {
                                if (tgWv.visibility != View.VISIBLE) {
                                    tgWv.visibility = View.VISIBLE
                                    tgWv.bringToFront()
                                }
                                sendTelegramPopupBounds()
                            } else if (tgWv.visibility != View.GONE) {
                                tgWv.visibility = View.GONE
                            }
                        }

                        if (overlayOpen) {
                            nativeWebSuppressed = true
                            if (curNativeWv.visibility == View.VISIBLE) {
                                curNativeWv.visibility = View.INVISIBLE
                                mainHandler.postDelayed({
                                    val delayedNativeWv = nativeWebViewOrNull()
                                    if (nativeWebSuppressed && isActivityAlive() && delayedNativeWv != null) {
                                        delayedNativeWv.visibility = View.GONE
                                    }
                                }, 150)
                            } else if (curNativeWv.visibility != View.GONE) {
                                curNativeWv.visibility = View.GONE
                            }
                            curNativeWv.isFocusable = false
                            curNativeWv.isFocusableInTouchMode = false
                            curNativeWv.isClickable = false
                        } else if (!isBrowserModeOpen) {
                            nativeWebSuppressed = false
                            if (curNativeWv.visibility != View.VISIBLE) {
                                curNativeWv.visibility = View.VISIBLE
                                curNativeWv.translationX = 0f; curNativeWv.translationY = 0f
                                curNativeWv.isFocusable = true
                                curNativeWv.isFocusableInTouchMode = true
                                curNativeWv.isClickable = true
                                curNativeWv.isEnabled = true
                            }
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            if (isActivityAlive()) {
                mainHandler.postDelayed(this, 350)
            } else {
                WebState.overlayPollingRunning = false
            }
        }
    })
}

internal fun MainActivity.showClearOverlay() {
    val nativeWv = nativeWebViewOrNull() ?: return
    try {
        if (!isActivityAlive()) return
        val parent = nativeWv.parent as? ViewGroup ?: return
        if (WebState.clearOverlayView != null) return
        val v = View(this).apply { setBackgroundColor(Color.BLACK) }
        val origLp = nativeWv.layoutParams
        val lp: ViewGroup.LayoutParams = when (origLp) {
            is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(origLp.width, origLp.height).apply {
                leftMargin = origLp.leftMargin
                topMargin = origLp.topMargin
            }
            else -> ViewGroup.LayoutParams(
                origLp?.width ?: ViewGroup.LayoutParams.MATCH_PARENT,
                origLp?.height ?: ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        parent.addView(v, lp)
        WebState.clearOverlayView = v
        WebState.clearOverlayActive = true
        bringTelegramFabToFront()
    } catch (_: Exception) {}
}

internal fun MainActivity.hideClearOverlay() {
    try {
        WebState.clearOverlayView?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        WebState.clearOverlayView = null
        WebState.clearOverlayActive = false
        bringTelegramFabToFront()
    } catch (_: Exception) {}
}

internal fun MainActivity.tryRevealBoot() {
    if (bootRevealDone || !isActivityAlive()) return
    if (!htmlBootReady) return
    bootRevealDone = true
    try {
        val nativeWv = nativeWebViewOrNull()
        if (!uiOverlayActive && !telegramPopupLocked && !WebState.telegramPopupOpen && nativeWv != null) {
            nativeWebSuppressed = false
            nativeWv.visibility = View.VISIBLE
            nativeWv.translationX = 0f
            nativeWv.translationY = 0f
        }
        bringTelegramFabToFront()
    } catch (_: Exception) {}
    val mainWv = mainWebViewOrNull()
    if (mainWv != null) {
        safeEvalJs(mainWv, "try{if(window.hideBootSplash)window.hideBootSplash();}catch(e){}")
    }
}

internal fun MainActivity.forceRevealBoot() {
    if (bootRevealDone || !isActivityAlive()) return
    bootRevealDone = true
    try {
        val nativeWv = nativeWebViewOrNull()
        if (!uiOverlayActive && !telegramPopupLocked && !WebState.telegramPopupOpen && nativeWv != null) {
            nativeWebSuppressed = false
            nativeWv.visibility = View.VISIBLE
            nativeWv.translationX = 0f
            nativeWv.translationY = 0f
        }
        bringTelegramFabToFront()
    } catch (_: Exception) {}
    val mainWv = mainWebViewOrNull()
    if (mainWv != null) {
        safeEvalJs(mainWv, "try{if(window.hideBootSplash)window.hideBootSplash();}catch(e){}")
    }
}

internal fun MainActivity.setNativeDarkMode(enabled: Boolean) {
    if (!isActivityAlive()) return
    darkModeEnabled = enabled
    try { getSharedPreferences(MainActivity.THEME_PREFS, Context.MODE_PRIVATE).edit().putBoolean(MainActivity.KEY_DARK_MODE, enabled).apply() } catch (_: Exception) {}
    applyForceDark(enabled)
    applyThemeColors(enabled)
    registerDarkModeDocumentStartScript()
    val nativeWv = nativeWebViewOrNull()
    try { nativeWv?.setBackgroundColor(Color.BLACK) } catch (_: Exception) {}
    if (enabled && nativeWv != null) safeEvalJs(nativeWv, earlyDarkJs())
    bringTelegramFabToFront()
}

internal fun MainActivity.applyThemeColors(dark: Boolean) {
    val webColor = Color.BLACK
    try {
        mainWebViewOrNull()?.setBackgroundColor(webColor)
        nativeWebViewOrNull()?.setBackgroundColor(webColor)
        if (isBrowserWebViewReady()) this.browserWebView.setBackgroundColor(webColor)
        findViewById<View>(android.R.id.content)?.setBackgroundColor(Color.BLACK)
        window.decorView.setBackgroundColor(Color.BLACK)
    } catch (_: Exception) {}
}

@Suppress("DEPRECATION")
internal fun MainActivity.applyForceDark(enabled: Boolean) {
    val nativeWv = nativeWebViewOrNull() ?: return
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try { nativeWv.settings.isAlgorithmicDarkeningAllowed = enabled } catch (_: Throwable) {}
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            nativeWv.settings.forceDark = if (enabled) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
        }
    } catch (_: Throwable) {}
}

internal fun MainActivity.registerDarkModeDocumentStartScript() {
    val nativeWv = nativeWebViewOrNull() ?: return
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
    try {
        try { darkScriptHandler?.remove() } catch (_: Exception) {}
        darkScriptHandler = null
        val shouldDark = darkModeEnabled
        val js = "(function(){try{if(!$shouldDark)return;var m=document.querySelector('meta[name=\"color-scheme\"]');if(!m){m=document.createElement('meta');m.name='color-scheme';(document.head||document.documentElement).appendChild(m);}m.content='dark';}catch(e){}})();"
        darkScriptHandler = WebViewCompat.addDocumentStartJavaScript(nativeWv, js, setOf("*"))
    } catch (_: Exception) {}
}

internal fun MainActivity.earlyDarkJs(): String =
    "(function(){try{if(document.getElementById('tp-early-dark-style'))return;var s=document.createElement('style');s.id='tp-early-dark-style';s.textContent='html,body{background:#000!important;color:#e5e7eb!important;}';(document.head||document.documentElement).appendChild(s);}catch(e){}})();"

internal fun MainActivity.injectDarkModeCss(enabled: Boolean) {
    val nativeWv = nativeWebViewOrNull() ?: return
    if (!enabled) {
        safeEvalJs(nativeWv, "(function(){try{var s=document.getElementById('tp-dark-style');if(s&&s.parentNode)s.parentNode.removeChild(s);}catch(e){}})();")
        return
    }
    safeEvalJs(nativeWv, "(function(){try{if(document.getElementById('tp-dark-style'))return;var s=document.createElement('style');s.id='tp-dark-style';s.textContent='html,body{background:#000!important;color:#e5e7eb!important;}';(document.head||document.documentElement).appendChild(s);}catch(e){}})();")
}

internal fun MainActivity.fixSnackbarStyle() {
    val mainWv = mainWebViewOrNull() ?: return
    safeEvalJs(mainWv, "(function(){try{if(window.__tpSnackbarFixed)return;window.__tpSnackbarFixed=true;var s=document.createElement('style');s.id='tp-snackbar-style';s.textContent='.snackbar,.toast,#toast{z-index:2147483647!important;}';(document.head||document.documentElement).appendChild(s);}catch(e){}})();")
}
