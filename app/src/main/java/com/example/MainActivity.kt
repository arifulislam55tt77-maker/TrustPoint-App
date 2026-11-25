package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ProfileStore
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewFeature
import com.example.data.MetaAccountEntity
import com.example.data.SavedAccountEntity
import com.example.data.TrustPointRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class MainActivity : AppCompatActivity(), StexApiService.PanelCallback {

    internal lateinit var mainWebView: WebView
    internal lateinit var nativeWebView: WebView
    internal lateinit var browserWebView: WebView
    internal lateinit var webInterface: WebInterface
    internal var filePathCallback: ValueCallback<Array<Uri>>? = null
    internal lateinit var pickVisualMediaLauncher: ActivityResultLauncher<PickVisualMediaRequest>

    internal fun hasBrowserWebView(): Boolean = ::browserWebView.isInitialized
    internal fun hasPickVisualMediaLauncher(): Boolean = ::pickVisualMediaLauncher.isInitialized

    private var nativeTgFab: View? = null

    internal val mainHandler = Handler(Looper.getMainLooper())
    @Volatile internal var isBrowserModeOpen = false
    @Volatile internal var floatingBubbleEnabled = true
    @Volatile internal var isActivityVisible = false
    @Volatile internal var lastBubbleTriggerAt = 0L
    @Volatile internal var isPickingFileOrPhoto = false
    @Volatile internal var isRequestingSystemPermission = false

    private val mobileUserAgents = listOf(
        "Mozilla/5.0 (Linux; Android 14; SM-S928B Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/126.0.6478.122 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro Build/AP2A.240605.024; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/125.0.6422.165 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 13; SM-A546E Build/TP1A.220624.014; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0.6367.179 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; CPH2581 Build/UKQ1.230924.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/126.0.6478.71 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 13; 22101316G Build/TP1A.220624.014; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/123.0.6312.118 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; V2309 Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/125.0.6422.113 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230901.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/122.0.6261.119 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; SM-G998B Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/127.0.6533.64 Mobile Safari/537.36"
    )

    internal var mobileUserAgent = mobileUserAgents.random()
    internal val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    internal var isDesktopMode = false
    internal var darkModeEnabled = true
    internal var lastUrl: String = ""

    @Volatile var lastSavedUid: String = ""
    @Volatile internal var lastSavedAt: Long = 0L
    @Volatile internal var nativeWebSuppressed = false
    @Volatile internal var uiOverlayActive: Boolean = false
    @Volatile internal var telegramPopupLocked: Boolean = false
    @Volatile internal var backButtonBlocked: Boolean = false
    @Volatile internal var pendingTelegramUrl: String = "https://web.telegram.org/a/"
    @Volatile internal var telegramTaskEnabled: Boolean = true
    @Volatile internal var telegramPopupAlreadyLoaded: Boolean = false

    @Volatile var autoRegActive: Boolean = false
    @Volatile var allocatedNumber: String = ""
    @Volatile var savedPassword: String = ""
    @Volatile var autoNumberEnabled: Boolean = false
    @Volatile var current2FAKey: String = ""

    @Volatile var lastGeneratedUsername: String = ""
    @Volatile var isMetaModeEnabled: Boolean = false
    lateinit var igAutomation: IgAutomation

    internal fun markIgAutomationComplete() {
        if (::igAutomation.isInitialized) {
            igAutomation.markComplete()
        }
    }

    @Volatile internal var activePageName: String = "insta"

    @Volatile var customFirstName: String = ""
    @Volatile var customLastName: String = ""
    @Volatile var customPassword: String = ""
    @Volatile var useCustomData: Boolean = false

    @Volatile var sessionActive: Boolean = false
    @Volatile var sessionPhone: String = ""
    @Volatile var sessionPassword: String = ""

    @Volatile internal var cachedUid: String = ""
    @Volatile internal var cachedCookies: String = ""

    @Volatile internal var page1ProxyEnabled = false
    @Volatile internal var page2ProxyEnabled = false
    @Volatile internal var proxyActuallyActive = false
    @Volatile internal var isProxyToggling = false
    @Volatile internal var proxyConnectionFailed = false
    @Volatile internal var isUserTriggeredProxy = false

    @Volatile internal var currentProxyIp = ""
    @Volatile internal var currentProxyPort = ""
    @Volatile internal var currentProxyUser = ""
    @Volatile internal var currentProxyPass = ""
    internal val proxyExecutor: Executor = Executor { runnable ->
        if (!isFinishing && !isDestroyed) {
            try { mainHandler.post(runnable) } catch (_: Exception) {}
        }
    }

    @Volatile internal var autoLoginEnabled = false

    @Volatile internal var pendingUploadUri: Uri? = null
    @Volatile internal var uploadConsumed: Boolean = false
    @Volatile internal var uploadSession: Boolean = false
    internal var uploadTimeoutRunnable: Runnable? = null
    internal var nativeFilePathCallback: ValueCallback<Array<Uri>>? = null

    internal var darkScriptHandler: ScriptHandler? = null

    @Volatile internal var automationSoundMuted: Boolean = false
    @Volatile internal var automationAutoMuteEnabled: Boolean = true

    @Volatile internal var currentMail: String = ""
    @Volatile internal var currentMailPassword: String = ""
    @Volatile internal var currentMailStatus: String = "idle"
    @Volatile internal var currentOtp: String = ""
    @Volatile internal var currentNumber: String = ""
    @Volatile internal var currentCountry: String = ""
    @Volatile internal var activeBoxSource: String = "none"

    @Volatile private var lastOtpReceived: String = ""
    @Volatile private var lastOtpTime: Long = 0L

    @Volatile internal var currentMailFullKey: String = ""
    @Volatile internal var savedHotmailKey: String = ""

    internal var htmlIntegrityCheckRunnable: Runnable? = null
    @Volatile internal var htmlBootReady = false
    @Volatile internal var nativeBootReady = false
    @Volatile internal var bootRevealDone = false

    lateinit var liveChecker: LiveUidChecker
    internal lateinit var hotmailPool: HotmailPool

    private lateinit var repository: TrustPointRepository

    private val copyBtnInjector = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            try {
                if (!uiOverlayActive && !telegramPopupLocked && ::nativeWebView.isInitialized) {
                    val u = nativeWebView.url ?: ""
                    if (u.contains("instagram.com")) {
                        injectFloatingButtons()
                    }
                }
                updateNativeTgFabVisibility()
            } catch (_: Exception) {}
            if (!isFinishing && !isDestroyed) {
                try { mainHandler.postDelayed(this, 2500) } catch (_: Exception) {}
            }
        }
    }

    companion object {
        internal const val TAG = "TrustPoint"
        internal const val DEFAULT_TG_LOGIN_URL = "https://web.telegram.org/a/"
        internal const val FILE_CHOOSER_REQUEST_CODE = 1001
        internal const val NATIVE_FILE_CHOOSER_REQUEST_CODE = 3004
        internal const val NOTIF_PERMISSION_REQUEST = 2002
        internal const val STORAGE_PERMISSION_REQUEST = 2003
        internal const val OVERLAY_PERMISSION_REQUEST_CODE = 101
        internal const val BATTERY_OPTIMIZATION_REQUEST_CODE = 102
        internal const val OTP_CHANNEL_ID = "ir_otp_channel"
        internal const val OTP_CHANNEL_NAME = "OTP Notifications"

        internal const val HOME_URL = "https://www.instagram.com/"
        internal const val INSTA_LOGIN_URL = "https://www.instagram.com/accounts/login/?mtn"

        internal const val THEME_PREFS = "ir_theme_prefs"
        internal const val KEY_DARK_MODE = "dark_mode"
        internal const val PW_PREFS = "ir_pw_prefs"
        internal const val KEY_PW = "autofill_password"
        internal const val AUTONUM_PREFS = "ir_autonum_prefs"
        internal const val KEY_AUTONUM = "auto_number_enabled"
        internal const val RANGE_PREFS = "ir_range_prefs"
        internal const val KEY_SAVED_RANGE = "saved_range_json"
        internal const val SHEET_STORE_PREFS = "ir_sheet_store_v2"
        internal const val KEY_SHEET_JSON = "sheet_json"
        internal const val CUR_MAIL_PREFS = "ir_current_meta_v1"
        internal const val KEY_CUR_MAIL = "cur_mail"
        internal const val KEY_CUR_PWD = "cur_pwd"
        internal const val KEY_CUR_STATUS = "cur_status"
        internal const val KEY_CUR_OTP = "cur_otp"
        internal const val KEY_CUR_NUMBER = "cur_number"
        internal const val KEY_CUR_COUNTRY = "cur_country"
        internal const val KEY_CUR_FULL_KEY = "cur_full_key"
        internal const val KEY_CUR_BOX_SOURCE = "cur_box_source"
        internal const val KEY_CUR_2FA = "cur_2fa"

        internal const val SHEET_MODE_PREFS = "ir_sheet_mode_v1"
        internal const val KEY_SHEET_MODE = "current_sheet_mode"

        internal const val PROXY_PREFS = "ir_proxy_prefs"
        internal const val KEY_PROXY_IP = "proxy_ip"
        internal const val KEY_PROXY_PORT = "proxy_port"
        internal const val KEY_PROXY_USER = "proxy_user"
        internal const val KEY_PROXY_PASS = "proxy_pass"
        internal const val KEY_PAGE1_PROXY = "page1_proxy_enabled"
        internal const val KEY_PAGE2_PROXY = "page2_proxy_enabled"
        internal const val KEY_PROXY_FAILED = "proxy_failed"

        internal const val PREFS_NAME = "ir_prefs"
        internal const val KEY_P2_MODE = "p2_mode"

        internal const val NAME_MODE_PREFS = "ir_name_mode_prefs"
        internal const val KEY_NAME_MODE = "name_mode"

        internal const val META_POOL_PREFS = "ir_meta_pool_v1"
        internal const val KEY_META_POOL_JSON = "meta_pool_json"
        internal const val SESSION_PREFS = "ir_session_v1"
        internal const val KEY_SESSION_PHONE = "session_phone"
        internal const val KEY_SESSION_PWD = "session_pwd"
        internal const val KEY_SESSION_ACTIVE = "session_active"

        internal const val API_KEY_PREFS = "ir_api_keys"

        internal const val PAGE_STATE_PREFS = "tp_page_state"
        internal const val KEY_ACTIVE_PAGE = "active_page"

        internal const val RECOVERY_PREFS = "ir_recovery_v1"
        internal const val KEY_RECOVERY_JSON = "recovery_json"
        internal const val RECOVERY_TTL_MS = 72L * 3600_000L

        internal const val BG_PREFS = "ir_bg_prefs"
        internal const val KEY_BATTERY_ASKED = "battery_asked_v2"
        internal const val KEY_OVERLAY_ASKED = "overlay_asked_v1"
        internal const val KEY_BUBBLE_ENABLED = "floating_bubble_enabled"

        private val ALLOWED_IG_COOKIE_KEYS = setOf(
            "datr", "ig_did", "dpr", "wd", "mid", "csrftoken",
            "ds_user_id", "sessionid", "rur", "ig_nrcb", "shbid", "shbts"
        )

        private val ORDERED_IG_COOKIE_KEYS = listOf(
            "datr", "ig_did", "dpr", "wd", "mid", "csrftoken",
            "ds_user_id", "sessionid", "rur", "ig_nrcb", "shbid", "shbts"
        )
    }

    internal fun dpToPx(dp: Float): Int =
        try { (dp * resources.displayMetrics.density).toInt().coerceAtLeast(1) }
        catch (_: Exception) { dp.toInt().coerceAtLeast(1) }

    internal fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

    internal fun quote(s: String): String = "\"" + esc(s) + "\""

    internal fun toast(msg: String) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                try { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
            }
        }
    }

    internal fun copyToClipboard(text: String, label: String = "Copied") {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("TrustPoint", text))
            toast(label)
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════
    //  OVERLAY / BACKGROUND AUTOMATION CONTROL ENTRYPOINTS
    // ═══════════════════════════════════════════════════════════
    fun startAutomationFromOverlay() {
        if (!AutomationRuntimeState.state.value.igAutoEnabled) {
            toast("⚠️ Automation OFF আছে — আগে ON করুন")
            return
        }
        val phone = currentNumber.ifBlank { allocatedNumber }
        val pass = currentMailPassword.ifBlank { savedPassword }
        if (phone.isNotBlank() && pass.isNotBlank()) {
            startIgAutomation(phone, pass)
        } else {
            val freshJson = getFreshMetaNative()
            if (freshJson != "{}") {
                try {
                    val obj = JSONObject(freshJson)
                    val mPhone = obj.optString("phone")
                    val mPass = obj.optString("password")
                    val m2fa = obj.optString("twofa")
                    if (m2fa.isNotBlank()) current2FAKey = m2fa
                    if (mPhone.isNotBlank() && mPass.isNotBlank()) {
                        startIgAutomation(mPhone, mPass)
                        return
                    }
                } catch (_: Exception) {}
            }
            toast("⚠️ Set Meta বা Number/Password সেট করুন")
        }
    }

    fun stopAutomationFromOverlay() {
        autoRegActive = false
        StexApiService.stopOtpPolling()
        OtpPollingService.stopSafely(this)
        if (::igAutomation.isInitialized) {
            igAutomation.stopAutomation("Stopped from control")
        }
        toast("⏹️ Automation stopped")
    }

    // ═══════════════════════════════════════════════════════════
    //  FLOATING BUBBLE STATE & BACKGROUND KEEP-ALIVE
    // ═══════════════════════════════════════════════════════════
    internal fun restoreFloatingBubblePref() {
        try {
            floatingBubbleEnabled = getSharedPreferences(BG_PREFS, MODE_PRIVATE)
                .getBoolean(KEY_BUBBLE_ENABLED, true)
        } catch (_: Exception) {
            floatingBubbleEnabled = true
        }
        AutomationRuntimeState.update(this) { it.copy(floatingBubbleEnabled = floatingBubbleEnabled) }
    }

    fun setFloatingBubbleEnabled(enabled: Boolean) {
        floatingBubbleEnabled = enabled
        try {
            getSharedPreferences(BG_PREFS, MODE_PRIVATE)
                .edit().putBoolean(KEY_BUBBLE_ENABLED, enabled).apply()
        } catch (_: Exception) {}

        AutomationRuntimeState.update(this) { it.copy(floatingBubbleEnabled = enabled) }
        AutomationRuntimeState.logExecution(this, TAG, "INFO", "Floating Bubble ${if (enabled) "ON" else "OFF"}")

        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            try {
                if (!enabled) {
                    // Hide floating bubbles cleanly while keeping background service active in Active apps
                    try {
                        val hideIntent = Intent(this, FloatingBubbleService::class.java).apply {
                            action = FloatingBubbleService.ACTION_HIDE_OVERLAY
                        }
                        if (AutomationRuntimeState.state.value.backgroundServiceRunning) {
                            startService(hideIntent)
                        }
                    } catch (_: Exception) {}
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                        requestOverlayPermissionNow()
                    } else {
                        startBubbleServiceOnly()
                    }
                }
                toast("Floating Bubble: " + if (enabled) "ON" else "OFF")
            } catch (_: Throwable) {}
        }
    }

    fun isFloatingBubbleEnabled(): Boolean = floatingBubbleEnabled

    internal fun startKeepAliveBackgroundService(): Boolean {
        if (isFinishing || isDestroyed) return false
        return try {
            val intent = Intent(this, FloatingBubbleService::class.java).apply {
                action = if (floatingBubbleEnabled && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this@MainActivity))) {
                    FloatingBubbleService.ACTION_SHOW_OVERLAY
                } else {
                    FloatingBubbleService.ACTION_REFRESH
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "startKeepAliveBackgroundService deferred: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    internal fun startBubbleServiceOnly(): Boolean {
        if (!floatingBubbleEnabled || isFinishing || isDestroyed) return false
        if (isPickingFileOrPhoto || isRequestingSystemPermission) return false
        if (!isActivityVisible) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return false
        }
        val now = System.currentTimeMillis()
        if (now - lastBubbleTriggerAt < 700L) return true
        lastBubbleTriggerAt = now
        return try {
            val intent = Intent(this, FloatingBubbleService::class.java).apply {
                action = FloatingBubbleService.ACTION_SHOW_OVERLAY
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "startBubbleServiceOnly blocked safely: ${t.javaClass.simpleName}: ${t.message}")
            AutomationRuntimeState.logExecution(
                this,
                TAG,
                "WARN",
                "FG bubble start deferred: ${t.message ?: t.javaClass.simpleName}"
            )
            false
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  TELEGRAM OVERLAY, LOCK & ZERO-REFRESH RESTORE
    // ═══════════════════════════════════════════════════════════
    internal fun setTelegramPopupLockState(locked: Boolean) {
        telegramPopupLocked = locked
        backButtonBlocked = locked
        if (locked) {
            uiOverlayActive = true
            nativeWebSuppressed = true
            try {
                if (::nativeWebView.isInitialized) nativeWebView.visibility = View.INVISIBLE
            } catch (_: Exception) {}
        }
        updateNativeTgFabVisibility()
    }

    internal fun ensureTelegramFloatingFab(): View? {
        try {
            if (isFinishing || isDestroyed || !::mainWebView.isInitialized) return null
            val parent = mainWebView.parent as? ViewGroup ?: return null

            if (nativeTgFab == null) {
                val fab = FrameLayout(this).apply {
                    setBackgroundResource(android.R.color.transparent)
                    contentDescription = "Telegram Login"
                }

                val circle = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    colors = intArrayOf(
                        Color.parseColor("#38bdf8"),
                        Color.parseColor("#0284c7")
                    )
                    gradientType = GradientDrawable.LINEAR_GRADIENT
                    setStroke(dpToPx(2f), Color.WHITE)
                }
                fab.background = circle
                fab.elevation = dpToPx(8f).toFloat()

                val icon = TextView(this).apply {
                    text = "✈"
                    setTextColor(Color.WHITE)
                    textSize = 20f
                    gravity = Gravity.CENTER
                }
                fab.addView(
                    icon,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )

                val sizePx = dpToPx(42f)
                val marginPx = dpToPx(14f)
                val bottomPx = dpToPx(68f)

                val lp = FrameLayout.LayoutParams(sizePx, sizePx).apply {
                    leftMargin = marginPx
                    bottomMargin = bottomPx
                    gravity = Gravity.BOTTOM or Gravity.START
                }

                fab.layoutParams = lp
                fab.setOnClickListener {
                    openTelegramOverlayInHtml(DEFAULT_TG_LOGIN_URL)
                }

                parent.addView(fab)
                nativeTgFab = fab
            }

            nativeTgFab?.bringToFront()
            updateNativeTgFabVisibility()
            return nativeTgFab
        } catch (e: Exception) {
            Log.e(TAG, "ensureTelegramFloatingFab: ${e.message}")
            return null
        }
    }

    internal fun bringTelegramFabToFront() {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            try {
                val fab = nativeTgFab ?: ensureTelegramFloatingFab()
                fab?.bringToFront()
            } catch (_: Exception) {}
        }
    }

    internal fun updateNativeTgFabVisibility() {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            try {
                val fab = nativeTgFab ?: ensureTelegramFloatingFab() ?: return@runOnUiThread
                val shouldShow = !uiOverlayActive && !telegramPopupLocked && !isBrowserModeOpen
                fab.visibility = if (shouldShow) View.VISIBLE else View.GONE
                fab.alpha = if (telegramTaskEnabled) 1.0f else 0.5f
                if (shouldShow) fab.bringToFront()
            } catch (_: Exception) {}
        }
    }

    internal fun enforceTelegramButtonOnRight() {
        try {
            if (isFinishing || isDestroyed || !::mainWebView.isInitialized) return
            val js = "(function(){try{var fab=document.getElementById('tgFixedFab');if(fab){fab.style.setProperty('display','none','important');}}catch(e){}})();"
            mainWebView.evaluateJavascript(js, null)
        } catch (_: Exception) {}
    }

    internal fun openTelegramOverlayInHtml(targetUrl: String? = null) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            try {
                val resolvedUrl = if (!targetUrl.isNullOrBlank() && targetUrl.contains("telegram.org")) {
                    targetUrl.trim()
                } else if (pendingTelegramUrl.isNotBlank() && pendingTelegramUrl.contains("telegram.org")) {
                    pendingTelegramUrl.trim()
                } else {
                    DEFAULT_TG_LOGIN_URL
                }
                pendingTelegramUrl = resolvedUrl
                uiOverlayActive = true
                nativeWebSuppressed = true
                if (::nativeWebView.isInitialized) nativeWebView.visibility = View.INVISIBLE
                updateNativeTgFabVisibility()

                if (::mainWebView.isInitialized) {
                    mainWebView.evaluateJavascript(
                        "(function(){try{" +
                        "if(window.TP&&typeof window.TP.openTelegramPopup==='function'){window.TP.openTelegramPopup('${esc(resolvedUrl)}');return;}" +
                        "var s=document.getElementById('tgSheetBackdrop');if(s){s.classList.add('open');}" +
                        "}catch(e){}})();",
                        null
                    )
                }

                telegramPopupAlreadyLoaded = true
                loadTelegramPopupUrl(resolvedUrl)
            } catch (e: Exception) {
                Log.e(TAG, "openTelegramOverlayInHtml error: ${e.message}")
            }
        }
    }

    internal fun closeTelegramOverlayAndRestoreInsta() {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            try {
                try { CookieManager.getInstance().flush() } catch (_: Exception) {}
                uiOverlayActive = false
                telegramPopupLocked = false
                backButtonBlocked = false
                nativeWebSuppressed = false
                hideTelegramPopupWebViewIfClosed()

                if (!isBrowserModeOpen && ::nativeWebView.isInitialized) {
                    nativeWebView.visibility = View.VISIBLE
                    nativeWebView.translationX = 0f
                    nativeWebView.translationY = 0f
                }
                bringTelegramFabToFront()
            } catch (_: Exception) {}
        }
    }

    internal fun triggerHtmlTelegramTaskHook() {
        try {
            if (isFinishing || isDestroyed || !::mainWebView.isInitialized) return
            val js = "(function(){try{if(window.TP){if(typeof window.TP.triggerTelegramBotTask==='function'){window.TP.triggerTelegramBotTask();}else if(typeof window.TP.startBotTaskInTelegram==='function'){window.TP.startBotTaskInTelegram();}}}catch(e){}})();"
            mainWebView.evaluateJavascript(js, null)
        } catch (_: Exception) {}
    }

    internal fun rotateUserAgent(): String {
        val nextUa = mobileUserAgents.filter { it != mobileUserAgent }.randomOrNull()
            ?: mobileUserAgents.random()
        mobileUserAgent = nextUa
        try {
            if (::nativeWebView.isInitialized) {
                nativeWebView.settings.userAgentString = if (isDesktopMode) desktopUserAgent else mobileUserAgent
            }
            if (::browserWebView.isInitialized) {
                browserWebView.settings.userAgentString = if (isDesktopMode) desktopUserAgent else mobileUserAgent
            }
        } catch (_: Exception) {}
        return nextUa
    }

    internal fun setActivePageInternal(@Suppress("UNUSED_PARAMETER") page: String) {
        activePageName = "insta"
        try {
            getSharedPreferences(PAGE_STATE_PREFS, MODE_PRIVATE)
                .edit().putString(KEY_ACTIVE_PAGE, activePageName).apply()
        } catch (_: Exception) {}
    }

    internal fun getActivePageInternal(): String = activePageName

    internal fun restoreActivePage() {
        activePageName = "insta"
    }

    internal fun handleNativePageFinished(url: String) {
        if (isFinishing || isDestroyed) return
        mainHandler.post {
            if (isFinishing || isDestroyed) return@post
            try {
                if (url.isNotBlank() && url != "about:blank") lastUrl = url
                if (activePageName != "insta") {
                    activePageName = "insta"
                    try {
                        getSharedPreferences(PAGE_STATE_PREFS, MODE_PRIVATE)
                            .edit().putString(KEY_ACTIVE_PAGE, "insta").apply()
                    } catch (_: Exception) {}
                }
                updateNativeTgFabVisibility()
            } catch (_: Exception) {}
        }
    }

    fun showFloatingBubble(minimizeApp: Boolean = true) {
        if (isFinishing || isDestroyed) return
        if (!floatingBubbleEnabled && minimizeApp) {
            try { moveTaskToBack(true) } catch (_: Exception) {}
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            if (isActivityVisible) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
                    toast("Please allow 'Display over other apps' permission")
                } catch (_: Exception) {}
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && minimizeApp && isActivityVisible) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                    val prefs = getSharedPreferences(BG_PREFS, MODE_PRIVATE)
                    if (!prefs.getBoolean(KEY_BATTERY_ASKED, false)) {
                        prefs.edit().putBoolean(KEY_BATTERY_ASKED, true).apply()
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            intent.data = Uri.parse("package:$packageName")
                            @Suppress("DEPRECATION")
                            startActivityForResult(intent, BATTERY_OPTIMIZATION_REQUEST_CODE)
                            toast("Please allow background running")
                            return
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        if (minimizeApp) {
            startBubbleAndMinimize()
        } else {
            startBubbleServiceOnly()
        }
    }

    private fun startBubbleAndMinimize() {
        try {
            startBubbleServiceOnly()
            moveTaskToBack(true)
        } catch (e: Exception) {
            toast("Bubble failed: ${e.message}")
        }
    }

    internal fun injectFloatingButtons() {
        try {
            if (isFinishing || isDestroyed || !::nativeWebView.isInitialized) return
            val js = "(function(){try{var oldBtn=document.getElementById('__ir_copy_user_btn');if(oldBtn&&oldBtn.parentNode)oldBtn.parentNode.removeChild(oldBtn);}catch(e){}})();"
            nativeWebView.evaluateJavascript(js, null)
        } catch (_: Exception) {}
    }

    fun copyCurrentUsername() {
        if (lastGeneratedUsername.isNotBlank()) {
            copyToClipboard(lastGeneratedUsername, "Username: $lastGeneratedUsername")
            return
        }
        try {
            if (!::nativeWebView.isInitialized) return
            val js = "(function(){try{var i=document.querySelector('input[name=\"username\"]')" +
                "||document.querySelector('input[name=\"email\"]')" +
                "||document.querySelector('input[autocomplete=\"username\"]');" +
                "return i?(i.value||''):'';}catch(e){return '';}})();"
            nativeWebView.evaluateJavascript(js) { result ->
                val u = (result ?: "").trim().trim('"')
                if (u.isNotBlank()) {
                    lastGeneratedUsername = u
                    copyToClipboard(u, "Username: $u")
                } else {
                    toast("Username empty")
                }
            }
        } catch (_: Exception) {}
    }

    fun fetchLastGeneratedUsername(): String = lastGeneratedUsername

    internal fun clearLoginFields() {
        try {
            if (!::nativeWebView.isInitialized) return
            val js = "(function(){try{var u=document.querySelector('input[name=\"username\"]');var p=document.querySelector('input[name=\"password\"]');if(u)u.value='';if(p)p.value='';}catch(e){}})();"
            nativeWebView.evaluateJavascript(js, null)
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════
    //  META POOL (WITH ROOM PERSISTENCE + DUPLICATE PREVENTION)
    // ═══════════════════════════════════════════════════════════
    internal fun getMetaPool(): MutableList<MetaAccount> {
        return try {
            val raw = getSharedPreferences(META_POOL_PREFS, MODE_PRIVATE)
                .getString(KEY_META_POOL_JSON, "[]") ?: "[]"
            val arr = JSONArray(raw)
            val list = mutableListOf<MetaAccount>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(MetaAccount(
                    phone = o.optString("phone", ""),
                    password = o.optString("password", ""),
                    twofa = o.optString("twofa", ""),
                    status = o.optString("status", "fresh"),
                    addedAt = o.optLong("addedAt", 0L)
                ))
            }
            list
        } catch (_: Exception) { mutableListOf() }
    }

    internal fun saveMetaPool(list: MutableList<MetaAccount>) {
        try {
            val arr = JSONArray()
            val entities = mutableListOf<MetaAccountEntity>()
            for (m in list) {
                arr.put(JSONObject().apply {
                    put("phone", m.phone)
                    put("password", m.password)
                    put("twofa", m.twofa)
                    put("status", m.status)
                    put("addedAt", m.addedAt)
                })
                entities.add(MetaAccountEntity(m.phone, m.password, m.twofa, m.status, m.addedAt))
            }
            getSharedPreferences(META_POOL_PREFS, MODE_PRIVATE)
                .edit().putString(KEY_META_POOL_JSON, arr.toString()).apply()
            if (::repository.isInitialized) {
                repository.syncMetaPoolAsync(entities)
            }
        } catch (_: Exception) {}
    }

    fun addMetaPoolNative(text: String): String {
        return try {
            val pool = getMetaPool()
            text.lines().forEach { line ->
                val parts = line.trim().split(Regex("[\\s,|]+")).filter { it.isNotBlank() }
                if (parts.size < 2) return@forEach
                val phone = parts[0]
                if (pool.any { it.phone == phone }) return@forEach
                pool.add(MetaAccount(
                    phone = phone,
                    password = parts[1],
                    twofa = if (parts.size >= 3) parts[2] else "",
                    status = "fresh",
                    addedAt = System.currentTimeMillis()
                ))
            }
            saveMetaPool(pool)
            getMetaStatsNative()
        } catch (_: Exception) { getMetaStatsNative() }
    }

    fun saveMetaPoolJsonNative(jsonStr: String): Boolean {
        return try {
            val arr = JSONArray(jsonStr)
            val pool = mutableListOf<MetaAccount>()
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val phone = o.optString("phone", "").trim()
                if (phone.isBlank() || !seen.add(phone)) continue
                pool.add(MetaAccount(
                    phone = phone,
                    password = o.optString("password", ""),
                    twofa = o.optString("twofa", ""),
                    status = o.optString("status", "fresh"),
                    addedAt = o.optLong("addedAt", System.currentTimeMillis())
                ))
            }
            saveMetaPool(pool)
            true
        } catch (_: Exception) { false }
    }

    fun getFreshMetaNative(): String {
        return try {
            val pool = getMetaPool()
            val fresh = pool.firstOrNull { it.status == "fresh" } ?: return "{}"
            fresh.status = "used"
            saveMetaPool(pool)
            currentNumber = fresh.phone
            currentMailPassword = fresh.password
            if (fresh.password.isNotBlank()) savePassword(fresh.password)
            current2FAKey = fresh.twofa
            currentMail = ""
            activeBoxSource = "meta"
            saveCurrentMail()
            AutomationRuntimeState.update(this) {
                it.copy(currentNumber = fresh.phone, current2FAKey = fresh.twofa)
            }
            JSONObject().apply {
                put("phone", fresh.phone)
                put("password", fresh.password)
                put("twofa", fresh.twofa)
            }.toString()
        } catch (_: Exception) { "{}" }
    }

    fun peekFreshMetaNative(): String {
        return try {
            val fresh = getMetaPool().firstOrNull { it.status == "fresh" } ?: return "{}"
            JSONObject().apply {
                put("phone", fresh.phone)
                put("password", fresh.password)
                put("twofa", fresh.twofa)
            }.toString()
        } catch (_: Exception) { "{}" }
    }

    fun getMetaStatsNative(): String {
        return try {
            val pool = getMetaPool()
            JSONObject().apply {
                put("total", pool.size)
                put("fresh", pool.count { it.status == "fresh" })
                put("used", pool.count { it.status == "used" })
                put("broken", pool.count { it.status == "broken" })
            }.toString()
        } catch (_: Exception) { "{\"total\":0,\"fresh\":0,\"used\":0,\"broken\":0}" }
    }

    fun getMetaListNative(): String {
        return try {
            val arr = JSONArray()
            for (m in getMetaPool()) {
                arr.put(JSONObject().apply {
                    put("phone", m.phone)
                    put("password", m.password)
                    put("twofa", m.twofa)
                    put("status", m.status)
                    put("addedAt", m.addedAt)
                })
            }
            arr.toString()
        } catch (_: Exception) { "[]" }
    }

    fun markMetaBrokenNative(phone: String): Boolean {
        return try {
            val p = getMetaPool()
            p.firstOrNull { it.phone == phone }?.status = "broken"
            saveMetaPool(p)
            true
        } catch (_: Exception) { false }
    }

    fun clearMetaPoolNative(): Boolean = try { saveMetaPool(mutableListOf()); true } catch (_: Exception) { false }

    fun resetMetaPoolNative(): Boolean {
        return try {
            val p = getMetaPool()
            p.forEach { it.status = "fresh" }
            saveMetaPool(p)
            true
        } catch (_: Exception) { false }
    }

    fun removeMetaNative(phone: String): Boolean {
        return try {
            val p = getMetaPool()
            val r = p.removeAll { it.phone == phone }
            saveMetaPool(p)
            r
        } catch (_: Exception) { false }
    }

    fun markMetaUsedNative(phone: String): Boolean {
        return try {
            val p = getMetaPool()
            p.firstOrNull { it.phone == phone }?.status = "used"
            saveMetaPool(p)
            true
        } catch (_: Exception) { false }
    }

    fun getMeta2FANative(phone: String): String =
        try { getMetaPool().firstOrNull { it.phone == phone }?.twofa ?: "" } catch (_: Exception) { "" }

    fun saveMeta2FANative(phone: String, secret: String): Boolean {
        return try {
            val p = getMetaPool()
            val acc = p.firstOrNull { it.phone == phone } ?: return false
            acc.twofa = secret
            saveMetaPool(p)
            if (currentNumber == phone) { current2FAKey = secret; saveCurrentMail() }
            true
        } catch (_: Exception) { false }
    }

    // ═══════════════════════════════════════════════════════════
    //  INSTAGRAM AUTOMATION
    // ═══════════════════════════════════════════════════════════
    private fun prepareNativeWebForAutomation() {
        try {
            if (isFinishing || isDestroyed || !::nativeWebView.isInitialized) return
            uiOverlayActive = false
            telegramPopupLocked = false
            backButtonBlocked = false
            nativeWebSuppressed = false
            nativeWebView.visibility = View.VISIBLE
            nativeWebView.translationX = 0f
            nativeWebView.translationY = 0f
            nativeWebView.bringToFront()
            updateNativeTgFabVisibility()
        } catch (_: Exception) {}
    }

    fun startIgAutomation(phone: String, pass: String) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            try {
                currentNumber = phone
                if (pass.isNotBlank()) {
                    currentMailPassword = pass
                    savePassword(pass)
                }
                if (current2FAKey.isBlank() && phone.isNotBlank()) {
                    val pool2fa = getMeta2FANative(phone)
                    if (pool2fa.isNotBlank()) current2FAKey = pool2fa
                }
                saveCurrentMail()
                autoRegActive = true
                prepareNativeWebForAutomation()
                ensureInstagramLoginPage()
                if (::igAutomation.isInitialized) {
                    igAutomation.startAutomation(phone, pass, current2FAKey)
                }
                fillInstagramLogin(phone, pass)
                toast("▶️ IG login: $phone")
            } catch (e: Exception) {
                Log.e(TAG, "startIgAutomation error: ${e.message}")
            }
        }
    }

    fun startIgAutomationCustom(name: String, pass: String) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            try {
                customFirstName = name.trim()
                customLastName = ""
                customPassword = pass
                useCustomData = true
                if (pass.isNotBlank()) {
                    currentMailPassword = pass
                    savePassword(pass)
                }
                var phone = currentNumber.ifBlank { allocatedNumber }
                if (phone.isBlank()) {
                    val freshJson = getFreshMetaNative()
                    if (freshJson != "{}") {
                        val obj = JSONObject(freshJson)
                        phone = obj.optString("phone", "")
                        val m2fa = obj.optString("twofa", "")
                        if (m2fa.isNotBlank()) current2FAKey = m2fa
                    }
                }
                if (phone.isNotBlank()) currentNumber = phone
                saveCurrentMail()
                autoRegActive = true
                prepareNativeWebForAutomation()
                ensureInstagramLoginPage()
                if (::igAutomation.isInitialized) {
                    igAutomation.startAutomation(phone, pass, current2FAKey)
                }
                if (phone.isNotBlank() && pass.isNotBlank()) {
                    fillInstagramLogin(phone, pass)
                }
                toast("▶️ Custom IG Automation started")
                AutomationRuntimeState.logExecution(this, TAG, "INFO", "Custom IG automation started ($phone)")
            } catch (e: Exception) {
                Log.e(TAG, "startIgAutomationCustom error: ${e.message}")
            }
        }
    }

    fun markMetaAccountBroken(phone: String, reason: String) {
        val clean = phone.trim()
        if (clean.isEmpty()) return
        try {
            markMetaBrokenNative(clean)
            if (::repository.isInitialized) {
                repository.updateMetaStatusAsync(clean, "broken")
            }
            if (currentNumber == clean) {
                currentNumber = ""
                saveCurrentMail()
            }
            AutomationRuntimeState.logExecution(this, TAG, "WARN", "Meta account broken ($reason): $clean")
        } catch (_: Exception) {}
    }

    fun onAccountMarkedUsed(reason: String) {
        try {
            val phone = currentNumber.ifBlank { allocatedNumber }.trim()
            if (phone.isNotBlank()) {
                markMetaUsedNative(phone)
                if (::repository.isInitialized) {
                    repository.updateMetaStatusAsync(phone, "used")
                }
                if (currentNumber == phone) {
                    currentNumber = ""
                    saveCurrentMail()
                }
            }
            AutomationRuntimeState.logExecution(this, TAG, "INFO", "Account marked used ($reason): $phone")
        } catch (_: Exception) {}
    }

    fun onAutomationStopped(reason: String) {
        autoRegActive = false
        try { StexApiService.stopOtpPolling() } catch (_: Exception) {}
        try { OtpPollingService.stopSafely(this) } catch (_: Exception) {}
        AutomationRuntimeState.update(this) { it.copy(igAutoRunning = false, igStepText = reason) }
        AutomationRuntimeState.logExecution(this, TAG, "INFO", "Automation stopped: $reason")
        FloatingBubbleService.refreshOverlayIfRunning(this)
    }

    fun onIgAutomationComplete() {
        try {
            if (::webInterface.isInitialized) {
                webInterface.onIgAutomationComplete()
            }
        } catch (_: Exception) {}
    }

    internal fun ensureInstagramLoginPage() {
        try {
            if (isFinishing || isDestroyed || !::nativeWebView.isInitialized) return
            val url = nativeWebView.url ?: ""
            if (url.isBlank() || url == "about:blank" || !url.contains("instagram.com")) {
                nativeWebView.loadUrl(INSTA_LOGIN_URL)
            }
        } catch (_: Exception) {}
    }

    private fun fillInstagramLogin(phone: String, pass: String) {
        if (isFinishing || isDestroyed || !::nativeWebView.isInitialized) return
        try {
            if (pass.isNotBlank()) {
                currentMailPassword = pass
                savePassword(pass)
            }
            val sPhone = phone.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
            val sPass = pass.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
            val js = """
                (function(){
                    try{
                        var u=document.querySelector('input[name="username"]')||document.querySelector('input[name="email"]')||document.querySelector('input[type="text"]');
                        var p=document.querySelector('input[name="password"]')||document.querySelector('input[type="password"]');
                        var s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
                        if(u){s.call(u,'$sPhone');u.dispatchEvent(new Event('input',{bubbles:true}));u.dispatchEvent(new Event('change',{bubbles:true}));}
                        if(p){s.call(p,'$sPass');p.dispatchEvent(new Event('input',{bubbles:true}));p.dispatchEvent(new Event('change',{bubbles:true}));}
                    }catch(e){}
                })();
            """.trimIndent()
            mainHandler.postDelayed({
                if (!isFinishing && !isDestroyed && ::nativeWebView.isInitialized) {
                    try { nativeWebView.evaluateJavascript(js, null) } catch (_: Exception) {}
                }
            }, 400)
            mainHandler.postDelayed({
                if (!isFinishing && !isDestroyed && ::nativeWebView.isInitialized) {
                    try { nativeWebView.evaluateJavascript(js, null) } catch (_: Exception) {}
                }
            }, 1800)
        } catch (_: Exception) {}
    }

    fun getInstagramCookiesPublic(): String {
        return try {
            val cm = CookieManager.getInstance()
            try { cm.flush() } catch (_: Exception) {}

            val igDomains = listOf(
                "https://www.instagram.com/",
                "https://www.instagram.com",
                "https://instagram.com/",
                "https://i.instagram.com/",
                "https://m.instagram.com/",
                "https://accountscenter.instagram.com/"
            )

            val cookieMap = LinkedHashMap<String, String>()
            for (domain in igDomains) {
                val raw = cm.getCookie(domain) ?: continue
                raw.split(";").forEach { part ->
                    val trimmed = part.trim()
                    val eqIdx = trimmed.indexOf('=')
                    if (eqIdx > 0) {
                        val key = trimmed.substring(0, eqIdx).trim()
                        val value = trimmed.substring(eqIdx + 1).trim()
                        if (
                            key in ALLOWED_IG_COOKIE_KEYS &&
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
            for (k in ORDERED_IG_COOKIE_KEYS) {
                cookieMap[k]?.let { ordered[k] = it }
            }

            ordered.entries.joinToString("; ") { "${it.key}=${it.value}" }
        } catch (e: Exception) {
            Log.e(TAG, "getInstagramCookiesPublic error: ${e.message}")
            ""
        }
    }

    fun clearSessionStatePublic() {
        sessionActive = false; sessionPhone = ""; sessionPassword = ""
        try {
            getSharedPreferences(SESSION_PREFS, MODE_PRIVATE).edit()
                .remove(KEY_SESSION_PHONE).remove(KEY_SESSION_PWD).remove(KEY_SESSION_ACTIVE).apply()
        } catch (_: Exception) {}
    }

    fun getCurrentPhone(): String = currentNumber.ifBlank { allocatedNumber }

    fun updateAutoNumberEnabled(enabled: Boolean) {
        autoNumberEnabled = enabled
        try { getSharedPreferences(AUTONUM_PREFS, MODE_PRIVATE).edit().putBoolean(KEY_AUTONUM, enabled).apply() }
        catch (_: Exception) {}
    }

    fun readAutofillPassword(): String = currentMailPassword.ifBlank { savedPassword }
    fun flushCookiesPublic() { try { CookieManager.getInstance().flush() } catch (_: Exception) {} }

    fun copyCurrentCookiesInternal(): String {
        return try {
            val ck = getInstagramCookiesPublic()
            if (ck.isBlank()) {
                toast("কোনো Instagram Cookies পাওয়া যায়নি")
                return ""
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Instagram Cookies", ck))
            runOnUiThread {
                if (!isFinishing && !isDestroyed && ::mainWebView.isInitialized) {
                    val sCk = esc(ck)
                    mainWebView.evaluateJavascript(
                        "(function(){try{var c=document.getElementById('webCookies');if(c)c.value='$sCk';if(window.TP&&window.TP.setCookiesData)window.TP.setCookiesData('$sCk');}catch(e){}})();",
                        null
                    )
                }
            }
            if (ck.contains("sessionid=")) {
                toast("✓ IG Cookies Copied!")
            } else {
                toast("⚠ sessionid পাওয়া যায়নি — আগে IG লগইন করুন")
            }
            ck
        } catch (_: Exception) {
            ""
        }
    }

    fun quickLoginInstagramFromOverlay(username: String, passwordOverride: String = "") {
        val cleanUser = username.trim()
        if (cleanUser.isBlank()) {
            toast("⚠️ Username দিন")
            return
        }
        val savedDefaultPass = readAutofillPassword()
        val password = passwordOverride.trim().ifBlank { savedDefaultPass }
        if (password.isBlank()) {
            toast("⚠️ Password লিখুন বা অ্যাপে সেভ করুন")
            AutomationRuntimeState.logExecution(this, TAG, "WARN", "Quick login blocked: password not set")
            return
        }

        lastGeneratedUsername = cleanUser
        currentMailPassword = password
        if (savedDefaultPass.isBlank()) {
            savePassword(password)
        }
        saveCurrentMail()
        AutomationRuntimeState.update(this) { it.copy(lastUsername = cleanUser, igStepText = "Quick login: $cleanUser") }
        AutomationRuntimeState.logExecution(this, TAG, "INFO", "Overlay quick login initiated for $cleanUser")

        runOnUiThread {
            if (isFinishing || isDestroyed || !::nativeWebView.isInitialized) return@runOnUiThread
            try {
                val targetWv = if (isBrowserModeOpen && ::browserWebView.isInitialized) browserWebView else nativeWebView
                try {
                    targetWv.onResume()
                    targetWv.resumeTimers()
                } catch (_: Exception) {}

                val curUrl = targetWv.url ?: ""
                val existingCk = getInstagramCookiesPublic()
                // If WebView is currently logged into an old session, clear IG-only cookies cleanly so login page renders
                if (existingCk.contains("sessionid=") || curUrl.isBlank() || curUrl == "about:blank" || !curUrl.contains("/accounts/login")) {
                    clearInstagramCookiesOnly()
                    targetWv.loadUrl(INSTA_LOGIN_URL)
                }

                val sUser = esc(cleanUser)
                val sPass = esc(password)

                var attempts = 0
                var redirectedLogin = false
                fun pollAndSubmitLogin() {
                    if (isFinishing || isDestroyed) return
                    attempts++
                    try {
                        targetWv.onResume()
                        targetWv.resumeTimers()
                    } catch (_: Exception) {}

                    val js = """
                        (function(){
                            try {
                                function isVis(el) {
                                    if (!el) return false;
                                    var r = el.getBoundingClientRect();
                                    return r && r.width > 30 && r.height > 14;
                                }
                                function setReactVal(el, val) {
                                    el.focus();
                                    var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                                    if (setter) setter.call(el, val); else el.value = val;
                                    if (el._valueTracker && typeof el._valueTracker.setValue === 'function') {
                                        el._valueTracker.setValue('');
                                    }
                                    el.dispatchEvent(new Event('input', {bubbles:true}));
                                    el.dispatchEvent(new Event('change', {bubbles:true}));
                                    el.dispatchEvent(new KeyboardEvent('keyup', {bubbles:true, key:'a'}));
                                }
                                function realClick(el) {
                                    var target = el.closest ? (el.closest('button, [role="button"]') || el) : el;
                                    target.removeAttribute('disabled');
                                    target.setAttribute('aria-disabled', 'false');
                                    if (typeof target.focus === 'function') target.focus();
                                    var r = target.getBoundingClientRect();
                                    var cx = r.left + r.width / 2;
                                    var cy = r.top + r.height / 2;
                                    var opts = {bubbles:true, cancelable:true, view:window, clientX:cx, clientY:cy};
                                    if (window.PointerEvent) target.dispatchEvent(new PointerEvent('pointerdown', opts));
                                    target.dispatchEvent(new MouseEvent('mousedown', opts));
                                    if (window.PointerEvent) target.dispatchEvent(new PointerEvent('pointerup', opts));
                                    target.dispatchEvent(new MouseEvent('mouseup', opts));
                                    target.dispatchEvent(new MouseEvent('click', opts));
                                    if (typeof target.click === 'function') target.click();
                                }
                                var u = document.querySelector('input[name="username"]')
                                     || document.querySelector('input[name="email"]')
                                     || document.querySelector('input[autocomplete="username"]')
                                     || document.querySelector('input[aria-label*="username" i]')
                                     || document.querySelector('input[aria-label*="phone" i]');
                                var p = document.querySelector('input[name="password"]')
                                     || document.querySelector('input[type="password"]');
                                if (!u || !p || !isVis(u) || !isVis(p)) return 'WAIT';
                                setReactVal(u, "$sUser");
                                setReactVal(p, "$sPass");
                                setTimeout(function(){
                                    try {
                                        var pRect = p.getBoundingClientRect();
                                        var scope = p.closest ? (p.closest('form') || document) : document;
                                        var banned = /language|english|বাংলা|hindi|español|français|deutsch|select|show|hide|forgot|create|sign up|new account|facebook|cookies|terms|privacy|help|meta|get app/;
                                        function findBtn(root) {
                                            var all = root.querySelectorAll('button, div[role="button"], span[role="button"], a[role="button"], input[type="submit"]');
                                            var exactMatch = null;
                                            var submitMatch = null;
                                            for (var i = 0; i < all.length; i++) {
                                                var el = all[i];
                                                var r = el.getBoundingClientRect();
                                                if (!r || r.width < 50 || r.height < 22) continue;
                                                // CRITICAL: must be at or below password input (never top-bar Language selector!)
                                                if (r.top < pRect.top - 4) continue;
                                                var txt = (el.innerText || el.textContent || el.getAttribute('aria-label') || el.value || '').replace(/\s+/g, ' ').trim().toLowerCase();
                                                if (!txt || banned.test(txt)) continue;
                                                if (txt === 'log in' || txt === 'login' || txt === 'sign in' || txt === 'লগ ইন' || txt === 'লগ ইন করুন') {
                                                    exactMatch = el;
                                                    break;
                                                }
                                                if ((el.tagName === 'BUTTON' || el.tagName === 'INPUT') && el.type === 'submit' && r.top >= pRect.bottom - 6 && !submitMatch) {
                                                    submitMatch = el;
                                                }
                                            }
                                            return exactMatch || submitMatch;
                                        }
                                        var btn = findBtn(scope) || (scope !== document ? findBtn(document) : null);
                                        if (btn) {
                                            realClick(btn);
                                        } else if (scope && scope.tagName === 'FORM' && typeof scope.requestSubmit === 'function') {
                                            scope.requestSubmit();
                                        }
                                    } catch(e) {}
                                }, 360);
                                return 'SUBMITTED';
                            } catch(e) { return 'ERR'; }
                        })();
                    """.trimIndent()
                    try {
                        targetWv.evaluateJavascript(js) { res ->
                            val clean = (res ?: "").trim().trim('"')
                            if (clean == "SUBMITTED") {
                                toast("🔐 IG Login submitted: $cleanUser")
                                AutomationRuntimeState.logExecution(this@MainActivity, TAG, "SUCCESS", "IG quick login submitted ($cleanUser)")
                            } else if (attempts < 28) {
                                val uNow = targetWv.url ?: ""
                                if (attempts == 5 && !redirectedLogin && !uNow.contains("/accounts/login")) {
                                    redirectedLogin = true
                                    clearInstagramCookiesOnly()
                                    targetWv.loadUrl(INSTA_LOGIN_URL)
                                }
                                mainHandler.postDelayed({ pollAndSubmitLogin() }, 650)
                            } else {
                                toast("⚠️ IG Login ফর্ম পাওয়া যায়নি")
                            }
                        }
                    } catch (_: Exception) {}
                }
                mainHandler.postDelayed({ pollAndSubmitLogin() }, 650)
            } catch (e: Exception) {
                Log.e(TAG, "quickLoginInstagramFromOverlay error: ${e.message}")
            }
        }
    }

    fun submitTwoFactorFromOverlay(twoFaInput: String) {
        val raw = twoFaInput.trim()
        if (raw.isBlank()) {
            toast("⚠️ 2FA Key দিন")
            return
        }
        val code = if (Regex("^\\d{6}$").matches(raw)) {
            raw
        } else {
            current2FAKey = raw
            saveCurrentMail()
            AutomationRuntimeState.update(this) { it.copy(current2FAKey = raw) }
            generateTotp(raw)
        }
        if (code.isBlank()) {
            toast("⚠️ অবৈধ Authentication Key")
            return
        }
        copyToClipboard(code, "2FA Code: $code")
        pushOtpToHtml(code)
        AutomationRuntimeState.logExecution(this, TAG, "INFO", "Overlay 2FA code submitting: $code")

        runOnUiThread {
            if (isFinishing || isDestroyed || !::nativeWebView.isInitialized) return@runOnUiThread
            val targetWv = if (isBrowserModeOpen && ::browserWebView.isInitialized) browserWebView else nativeWebView
            try {
                targetWv.onResume()
                targetWv.resumeTimers()
            } catch (_: Exception) {}

            var attempts = 0
            fun handlePost2faPrompts() {
                if (isFinishing || isDestroyed) return
                val dismissJs = """
                    (function(){
                        try {
                            var stillOnCode = document.querySelector('input[name="verificationCode"], input[autocomplete="one-time-code"], input[name="security_code"]');
                            if (stillOnCode) {
                                var sr = stillOnCode.getBoundingClientRect();
                                if (sr && sr.width > 30 && sr.height > 14) return 'ON_CODE';
                            }
                            var all = document.querySelectorAll('button, div[role="button"], span[role="button"]');
                            for (var i = 0; i < all.length; i++) {
                                var r = all[i].getBoundingClientRect();
                                if (!r || r.width < 45 || r.height < 22) continue;
                                var txt = (all[i].innerText || all[i].textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
                                if (txt === 'trust this device' || txt === 'trust device' || txt === 'not now' || txt === 'save info' || txt === 'skip' || txt === 'continue' || txt === 'চালিয়ে যান' || txt === 'এখন নয়') {
                                    all[i].click();
                                    return 'CLICKED:' + txt;
                                }
                            }
                        } catch(e){}
                        return 'NONE';
                    })();
                """.trimIndent()
                try {
                    targetWv.onResume()
                    targetWv.evaluateJavascript(dismissJs, null)
                } catch (_: Exception) {}
                flushCookiesPublic()
                pushLiveCredsToHtml()
            }

            fun pollAndSubmit2Fa() {
                if (isFinishing || isDestroyed) return
                attempts++
                try {
                    targetWv.onResume()
                    targetWv.resumeTimers()
                } catch (_: Exception) {}

                val activeCode = if (Regex("^\\d{6}$").matches(raw)) raw else generateTotp(raw).ifBlank { code }
                val js = """
                    (function(){
                        try {
                            function isVis(el) {
                                if (!el) return false;
                                var r = el.getBoundingClientRect();
                                return r && r.width > 30 && r.height > 14;
                            }
                            function setReactVal(el, val) {
                                el.focus();
                                var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                                if (setter) setter.call(el, val); else el.value = val;
                                if (el._valueTracker && typeof el._valueTracker.setValue === 'function') {
                                    el._valueTracker.setValue('');
                                }
                                el.dispatchEvent(new Event('input', {bubbles:true}));
                                el.dispatchEvent(new Event('change', {bubbles:true}));
                                el.dispatchEvent(new KeyboardEvent('keyup', {bubbles:true, key:'0'}));
                            }
                            function realClick(el) {
                                var target = el.closest ? (el.closest('button, [role="button"]') || el) : el;
                                target.removeAttribute('disabled');
                                target.setAttribute('aria-disabled', 'false');
                                if (typeof target.focus === 'function') target.focus();
                                var r = target.getBoundingClientRect();
                                var cx = r.left + r.width / 2;
                                var cy = r.top + r.height / 2;
                                var opts = {bubbles:true, cancelable:true, view:window, clientX:cx, clientY:cy};
                                if (window.PointerEvent) target.dispatchEvent(new PointerEvent('pointerdown', opts));
                                target.dispatchEvent(new MouseEvent('mousedown', opts));
                                if (window.PointerEvent) target.dispatchEvent(new PointerEvent('pointerup', opts));
                                target.dispatchEvent(new MouseEvent('mouseup', opts));
                                target.dispatchEvent(new MouseEvent('click', opts));
                                if (typeof target.click === 'function') target.click();
                            }
                            var candidates = [
                                document.querySelector('input[name="verificationCode"]'),
                                document.querySelector('input[name="code"]'),
                                document.querySelector('input[name="security_code"]'),
                                document.querySelector('input[autocomplete="one-time-code"]'),
                                document.querySelector('input[inputmode="numeric"]'),
                                document.querySelector('input[aria-label*="code" i]'),
                                document.querySelector('input[aria-label*="verification" i]'),
                                document.querySelector('input[placeholder*="code" i]'),
                                document.querySelector('input[type="tel"]'),
                                document.querySelector('input[type="number"]')
                            ];
                            var inp = null;
                            for (var k = 0; k < candidates.length; k++) {
                                if (isVis(candidates[k])) { inp = candidates[k]; break; }
                            }
                            if (!inp) {
                                var hasPass = isVis(document.querySelector('input[name="password"], input[type="password"]'));
                                var bodyTxt = (document.body && document.body.innerText ? document.body.innerText : '').toLowerCase();
                                if (!hasPass && /two-factor|authentication|security code|verification code|6-digit|অথেনটিকেশন|কোড/.test(bodyTxt)) {
                                    var textInps = document.querySelectorAll('input[type="text"], input:not([type])');
                                    for (var m = 0; m < textInps.length; m++) {
                                        if (isVis(textInps[m])) { inp = textInps[m]; break; }
                                    }
                                }
                            }
                            if (!inp) return 'WAIT';
                            setReactVal(inp, "$activeCode");
                            setTimeout(function(){
                                try {
                                    var inpRect = inp.getBoundingClientRect();
                                    var scope = inp.closest ? (inp.closest('form') || document) : document;
                                    var banned = /resend|send again|try another|another way|more options|language|english|বাংলা|back|help|learn more|can't|cookies|terms|privacy/;
                                    function findBtn(root) {
                                        var all = root.querySelectorAll('button, div[role="button"], span[role="button"], a[role="button"], input[type="submit"]');
                                        var exactMatch = null;
                                        var submitMatch = null;
                                        for (var i = 0; i < all.length; i++) {
                                            var el = all[i];
                                            var r = el.getBoundingClientRect();
                                            if (!r || r.width < 48 || r.height < 22) continue;
                                            // Must be at or below the 2FA code input
                                            if (r.top < inpRect.top - 4) continue;
                                            var txt = (el.innerText || el.textContent || el.getAttribute('aria-label') || el.value || '').replace(/\s+/g, ' ').trim().toLowerCase();
                                            if (!txt || banned.test(txt)) continue;
                                            if (txt === 'continue' || txt === 'confirm' || txt === 'verify' || txt === 'submit' || txt === 'next' || txt === 'চালিয়ে যান' || txt === 'নিশ্চিত করুন' || txt === 'ভেরিফাই' || txt === 'সাবমিট') {
                                                exactMatch = el;
                                                break;
                                            }
                                            if ((el.tagName === 'BUTTON' || el.tagName === 'INPUT') && el.type === 'submit' && r.top >= inpRect.bottom - 6 && !submitMatch) {
                                                submitMatch = el;
                                            }
                                        }
                                        return exactMatch || submitMatch;
                                    }
                                    var btn = findBtn(scope) || (scope !== document ? findBtn(document) : null);
                                    if (btn) {
                                        realClick(btn);
                                    } else if (scope && scope.tagName === 'FORM' && typeof scope.requestSubmit === 'function') {
                                        scope.requestSubmit();
                                    }
                                } catch(e) {}
                            }, 360);
                            return 'SUBMITTED';
                        } catch(e) { return 'ERR'; }
                    })();
                """.trimIndent()
                try {
                    targetWv.evaluateJavascript(js) { res ->
                        val status = (res ?: "").trim().trim('"')
                        if (status == "SUBMITTED") {
                            toast("✅ 2FA Confirmed: $activeCode")
                            AutomationRuntimeState.update(this@MainActivity) { it.copy(igStepText = "2FA Verified ($activeCode)") }
                            AutomationRuntimeState.logExecution(this@MainActivity, TAG, "SUCCESS", "2FA code submitted ($activeCode)")
                            mainHandler.postDelayed({ handlePost2faPrompts() }, 2000)
                            mainHandler.postDelayed({ handlePost2faPrompts() }, 4200)
                            mainHandler.postDelayed({ handlePost2faPrompts() }, 6800)
                        } else if (attempts < 40) {
                            mainHandler.postDelayed({ pollAndSubmit2Fa() }, 650)
                        } else {
                            toast("⚠️ 2FA ঘর পাওয়া যায়নি (Code Copied: $activeCode)")
                        }
                    }
                } catch (_: Exception) {}
            }
            mainHandler.postDelayed({ pollAndSubmit2Fa() }, 550)
        }
    }

    internal fun resolveLoggedInUsernameFromCookies(cookies: String): String {
        if (!cookies.contains("sessionid=")) return ""
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url("https://i.instagram.com/api/v1/accounts/current_user/?edit=true")
                .header("Cookie", cookies)
                .header("User-Agent", mobileUserAgent)
                .header("X-IG-App-ID", "936619743392459")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                val body = resp.body?.string().orEmpty()
                JSONObject(body).optJSONObject("user")?.optString("username").orEmpty().trim()
            }
        } catch (_: Exception) {
            ""
        }
    }

    internal fun resolveAndUpdateUsernameAsync(savedKey: String, cookies: String) {
        val looksNumericOrPhone = savedKey.isNotBlank() && savedKey.all { it.isDigit() || it == '+' }
        if (!looksNumericOrPhone || !cookies.contains("sessionid=")) return
        lifecycleScope.launch(Dispatchers.IO) {
            val realHandle = resolveLoggedInUsernameFromCookies(cookies)
            if (realHandle.isNotBlank() && realHandle != savedKey) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        lastGeneratedUsername = realHandle
                        AutomationRuntimeState.update(this@MainActivity) { it.copy(lastUsername = realHandle) }
                        val arr = getSheetArray()
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            if (obj.optString("uid") == savedKey || obj.optString("username") == savedKey) {
                                obj.put("uid", realHandle)
                                obj.put("username", realHandle)
                                break
                            }
                        }
                        saveSheetArray(arr)
                        syncSheetDataToHtml()
                        if (::mainWebView.isInitialized) {
                            mainWebView.evaluateJavascript(
                                "(function(){try{if(window.TP&&window.TP.setGeneratedUsername)window.TP.setGeneratedUsername('${esc(realHandle)}');}catch(e){}})();",
                                null
                            )
                        }
                    }
                }
            }
        }
    }

    fun saveCurrentAccountFromOverlay(): String {
        return try {
            flushCookiesPublic()
            val liveCookies = getInstagramCookiesPublic()
            val cookieUid = Regex("ds_user_id=(\\d+)").find(liveCookies)?.groupValues?.getOrNull(1).orEmpty()
            val snap = AutomationRuntimeState.state.value
            val candidateUser = lastGeneratedUsername.trim()
                .ifBlank { snap.lastUsername.trim() }
            val resolvedUser = candidateUser
                .ifBlank { cookieUid }
                .ifBlank { currentNumber.trim() }
                .ifBlank { currentMail.trim() }
            if (resolvedUser.isBlank()) {
                return ""
            }
            val resolvedPass = currentMailPassword.trim().ifBlank { readAutofillPassword().trim() }
            val resolvedTwoFa = current2FAKey.trim()
                .ifBlank { snap.current2FAKey.trim() }
                .ifBlank { if (currentNumber.isNotBlank()) getMeta2FANative(currentNumber) else "" }
            val resolvedHotmail = currentMail.trim()
            val ok = saveToSheet(resolvedUser, resolvedPass, liveCookies, resolvedHotmail, resolvedTwoFa)
            if (ok) {
                runOnUiThread { pushLiveCredsToHtml() }
                AutomationRuntimeState.logExecution(this, TAG, "SUCCESS", "Overlay Save added account to Sheet: $resolvedUser")
                resolvedUser
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun clearWebFromOverlay() {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            try {
                try {
                    if (::nativeWebView.isInitialized) {
                        nativeWebView.onResume()
                        nativeWebView.resumeTimers()
                    }
                    if (::browserWebView.isInitialized) {
                        browserWebView.onResume()
                        browserWebView.resumeTimers()
                    }
                } catch (_: Exception) {}

                current2FAKey = ""
                AutomationRuntimeState.update(this) {
                    it.copy(
                        lastUsername = "",
                        currentOtp = "",
                        current2FAKey = "",
                        igStepText = "Web cleared (Ready)"
                    )
                }
                clearWebInstant()
                mainHandler.postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        try {
                            if (::nativeWebView.isInitialized) {
                                nativeWebView.onResume()
                                nativeWebView.resumeTimers()
                            }
                            if (::browserWebView.isInitialized) {
                                browserWebView.onResume()
                                browserWebView.resumeTimers()
                            }
                        } catch (_: Exception) {}
                    }
                }, 400)
                AutomationRuntimeState.logExecution(this, TAG, "INFO", "Overlay Clear Web executed (TG safe)")
            } catch (e: Exception) {
                Log.e(TAG, "clearWebFromOverlay error: ${e.message}")
            }
        }
    }

    fun generate2FACodeFromKey(): String {
        val code = generateTotp(current2FAKey)
        if (code.isNotBlank()) copyToClipboard(code, "2FA: $code") else toast("No 2FA key set")
        return code
    }

    fun generate2FACodeFromKeyInput(rawKey: String): String {
        val clean = rawKey.trim()
        if (clean.isBlank()) return ""
        current2FAKey = clean
        saveCurrentMail()
        AutomationRuntimeState.update(this) { it.copy(current2FAKey = clean) }
        return generateTotp(clean)
    }

    fun startTwoFactorAutomation() {
        try {
            if (!::nativeWebView.isInitialized) return
            val code = generateTotp(current2FAKey)
            if (code.isBlank()) { toast("No 2FA key"); return }
            val js = """
                (function(){
                    try {
                        var inp = document.querySelector('input[name="verificationCode"]')
                               || document.querySelector('input[name="code"]')
                               || document.querySelector('input[autocomplete="one-time-code"]')
                               || document.querySelector('input[name="security_code"]')
                               || document.querySelector('input[type="tel"]');
                        if (inp) {
                            var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
                            setter.call(inp, '$code');
                            inp.dispatchEvent(new Event('input', {bubbles:true}));
                            inp.dispatchEvent(new Event('change', {bubbles:true}));
                        }
                    } catch(e){}
                })();
            """.trimIndent()
            mainHandler.postDelayed({
                if (!isFinishing && !isDestroyed && ::nativeWebView.isInitialized) {
                    try { nativeWebView.evaluateJavascript(js, null) } catch (_: Exception) {}
                }
            }, 800)
            mainHandler.postDelayed({
                if (!isFinishing && !isDestroyed && ::nativeWebView.isInitialized) {
                    try { nativeWebView.evaluateJavascript(js, null) } catch (_: Exception) {}
                }
            }, 2000)
            copyToClipboard(code, "2FA Code: $code")
        } catch (_: Exception) {}
    }

    fun setNameModePublic(mode: String) {
        try { getSharedPreferences(NAME_MODE_PREFS, MODE_PRIVATE).edit().putString(KEY_NAME_MODE, mode).apply() }
        catch (_: Exception) {}
    }

    private fun clearDomainCookies(cm: CookieManager, urls: List<String>) {
        urls.forEach { baseUrl ->
            try {
                val raw = cm.getCookie(baseUrl) ?: return@forEach
                raw.split(";").forEach { part ->
                    val name = part.substringBefore("=").trim()
                    if (name.isNotEmpty()) {
                        cm.setCookie(baseUrl, "$name=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/")
                        cm.setCookie(baseUrl, "$name=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/; domain=.instagram.com")
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun wipeStorageAndTargetWebViews(newUa: String) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                try { ProfileStore.getInstance().getProfile("page2_profile")?.cookieManager?.removeAllCookies(null) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        try {
            val cm = CookieManager.getInstance()
            clearDomainCookies(
                cm,
                listOf(
                    "https://www.instagram.com", "https://instagram.com",
                    "https://i.instagram.com", "https://m.instagram.com",
                    "https://accountscenter.instagram.com"
                )
            )
            cm.flush()
        } catch (_: Exception) {}

        val clearStorageJs = """
            (function(){
                try {
                    var h = (location.hostname || '').toLowerCase();
                    if (h.indexOf('telegram.org') !== -1) return;
                    if (window.localStorage) window.localStorage.clear();
                    if (window.sessionStorage) window.sessionStorage.clear();
                } catch(e){}
            })();
        """.trimIndent()

        try {
            if (::nativeWebView.isInitialized) {
                nativeWebView.stopLoading()
                val cur = (nativeWebView.url ?: "").lowercase()
                if (!cur.contains("telegram.org")) {
                    nativeWebView.evaluateJavascript(clearStorageJs, null)
                    nativeWebView.clearCache(true)
                    nativeWebView.clearFormData()
                    nativeWebView.clearHistory()
                    nativeWebView.settings.userAgentString = if (isDesktopMode) desktopUserAgent else newUa
                    nativeWebView.loadUrl("about:blank")
                }
            }
        } catch (_: Exception) {}

        try {
            if (::browserWebView.isInitialized) {
                browserWebView.stopLoading()
                val cur = (browserWebView.url ?: "").lowercase()
                if (!cur.contains("telegram.org")) {
                    browserWebView.evaluateJavascript(clearStorageJs, null)
                    browserWebView.clearCache(true)
                    browserWebView.clearFormData()
                    browserWebView.clearHistory()
                    browserWebView.settings.userAgentString = if (isDesktopMode) desktopUserAgent else newUa
                    if (isBrowserModeOpen) browserWebView.loadUrl(INSTA_LOGIN_URL)
                }
            }
        } catch (_: Exception) {}
    }

    fun clearTelegramSession() {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            try {
                val cm = CookieManager.getInstance()
                clearDomainCookies(
                    cm,
                    listOf(
                        "https://web.telegram.org", "https://telegram.org",
                        "https://venus.web.telegram.org", "https://pluto.web.telegram.org",
                        "https://aurora.web.telegram.org", "https://vesta.web.telegram.org",
                        "https://flora.web.telegram.org"
                    )
                )
                cm.flush()
                telegramPopupAlreadyLoaded = false
                toast("🧹 Telegram session cleared")
            } catch (_: Exception) {}
        }
    }

    fun clearWebInstant() {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            try {
                autoRegActive = false
                try { if (::igAutomation.isInitialized) igAutomation.stopAutomation("Web cleared") } catch (_: Exception) {}
                try { if (::igAutomation.isInitialized) igAutomation.forceReset() } catch (_: Exception) {}
                try { StexApiService.stopOtpPolling() } catch (_: Exception) {}

                clearSessionStatePublic()
                lastGeneratedUsername = ""
                currentOtp = ""
                lastUrl = INSTA_LOGIN_URL

                performClearWebAction(INSTA_LOGIN_URL)
                AutomationRuntimeState.logExecution(this, TAG, "INFO", "IG web cleared & Telegram preserved")
            } catch (e: Exception) {
                Log.e(TAG, "clearWebInstant error: ${e.message}")
            }
        }
    }

    fun lockProtectedText() {}

    fun saveFileToDownloadsImpl(filename: String, mimeType: String, data: String): Boolean {
        return saveBinaryFileToDownloadsImpl(filename, mimeType, data.toByteArray(StandardCharsets.UTF_8))
    }

    fun saveBinaryFileToDownloadsImpl(filename: String, mimeType: String, bytes: ByteArray): Boolean {
        return try {
            val safeName = filename.trim().ifBlank { "trustpoint_${System.currentTimeMillis()}.bin" }
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val safeMime = mimeType.trim().ifBlank { "application/octet-stream" }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, safeMime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out -> out.write(bytes); out.flush() }
                    cv.clear()
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, cv, null, null)
                    toast("✓ Downloaded: $safeName")
                    true
                } else {
                    val fallbackDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                    val f = File(fallbackDir, safeName)
                    FileOutputStream(f).use { it.write(bytes); it.flush() }
                    toast("✓ Saved: ${f.absolutePath}")
                    true
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, safeName)
                FileOutputStream(f).use { out -> out.write(bytes); out.flush() }
                toast("✓ Downloaded: ${f.name}")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}")
            toast("Download failed: ${e.message}")
            false
        }
    }

    fun isBlockedExternalScheme(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val blocked = listOf("intent://", "market://", "whatsapp://", "tg://", "tel:", "sms:", "mailto:")
        return blocked.any { url.startsWith(it) }
    }

    internal fun generateTotp(base32Key: String): String {
        try {
            val otpAuthSecret = Regex("secret=([A-Za-z2-7]+)", RegexOption.IGNORE_CASE).find(base32Key)?.groupValues?.getOrNull(1)
            val cleaned = (otpAuthSecret ?: base32Key).uppercase(Locale.US).replace(Regex("[^A-Z2-7]"), "")
            if (cleaned.isEmpty()) return ""
            val keyBytes = base32Decode(cleaned)
            if (keyBytes.isEmpty()) return ""
            val timeStep = (System.currentTimeMillis() / 1000L) / 30L
            val buffer = ByteBuffer.allocate(8).putLong(timeStep).array()
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(keyBytes, "HmacSHA1"))
            val hash = mac.doFinal(buffer)
            val offset = hash[hash.size - 1].toInt() and 0x0F
            val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                    ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                    ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                    (hash[offset + 3].toInt() and 0xFF)
            return String.format(Locale.US, "%06d", binary % 1000000)
        } catch (_: Exception) { return "" }
    }

    private fun base32Decode(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0; var value = 0
        val out = ByteArrayOutputStream()
        for (c in input) {
            val idx = alphabet.indexOf(c); if (idx < 0) continue
            value = (value shl 5) or idx
            bits += 5
            if (bits >= 8) { out.write((value ushr (bits - 8)) and 0xFF); bits -= 8 }
        }
        return out.toByteArray()
    }

    internal fun saveCurrentMail() {
        try {
            getSharedPreferences(CUR_MAIL_PREFS, MODE_PRIVATE).edit()
                .putString(KEY_CUR_MAIL, currentMail).putString(KEY_CUR_PWD, currentMailPassword)
                .putString(KEY_CUR_STATUS, currentMailStatus).putString(KEY_CUR_OTP, currentOtp)
                .putString(KEY_CUR_NUMBER, currentNumber).putString(KEY_CUR_COUNTRY, currentCountry)
                .putString(KEY_CUR_FULL_KEY, currentMailFullKey).putString(KEY_CUR_BOX_SOURCE, activeBoxSource)
                .putString(KEY_CUR_2FA, current2FAKey).apply()
            AutomationRuntimeState.update(this) {
                it.copy(
                    currentNumber = currentNumber,
                    currentCountry = currentCountry,
                    currentOtp = currentOtp,
                    current2FAKey = current2FAKey
                )
            }
        } catch (_: Exception) {}
    }

    internal fun restoreCurrentMail() {
        try {
            val p = getSharedPreferences(CUR_MAIL_PREFS, MODE_PRIVATE)
            currentMail = p.getString(KEY_CUR_MAIL, "") ?: ""
            currentMailPassword = p.getString(KEY_CUR_PWD, "") ?: ""
            currentMailStatus = p.getString(KEY_CUR_STATUS, "idle") ?: "idle"
            currentOtp = p.getString(KEY_CUR_OTP, "") ?: ""
            currentNumber = p.getString(KEY_CUR_NUMBER, "") ?: ""
            currentCountry = p.getString(KEY_CUR_COUNTRY, "") ?: ""
            currentMailFullKey = p.getString(KEY_CUR_FULL_KEY, "") ?: ""
            activeBoxSource = p.getString(KEY_CUR_BOX_SOURCE, "") ?: ""
            current2FAKey = p.getString(KEY_CUR_2FA, "") ?: ""
            AutomationRuntimeState.update(this) {
                it.copy(
                    currentNumber = currentNumber,
                    currentCountry = currentCountry,
                    currentOtp = currentOtp,
                    current2FAKey = current2FAKey
                )
            }
        } catch (_: Exception) {}
    }

    internal fun loadSavedPassword() {
        try { savedPassword = getSharedPreferences(PW_PREFS, MODE_PRIVATE).getString(KEY_PW, "") ?: "" }
        catch (_: Exception) {}
    }

    internal fun savePassword(pwd: String) {
        savedPassword = pwd
        try { getSharedPreferences(PW_PREFS, MODE_PRIVATE).edit().putString(KEY_PW, pwd).apply() }
        catch (_: Exception) {}
    }

    internal fun getSavedRange(): String = try {
        val v = getSharedPreferences(RANGE_PREFS, MODE_PRIVATE).getString(KEY_SAVED_RANGE, "") ?: ""
        if (v.isBlank()) "{}" else v
    } catch (_: Exception) { "{}" }

    internal fun saveSelectedRange(json: String) {
        try {
            getSharedPreferences(RANGE_PREFS, MODE_PRIVATE).edit().putString(KEY_SAVED_RANGE, json).apply()
            val obj = JSONObject(json)
            val rid = obj.optString("rid", "")
            val range = obj.optString("range", "")
            val label = obj.optString("label", "")
            if (rid.isNotBlank() || range.isNotBlank()) {
                StexApiService.saveSelectedRange(rid, range, label)
            }
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════
    //  SHEET & ROOM PERSISTENCE
    // ═══════════════════════════════════════════════════════════
    internal fun getSheetArray(): JSONArray = try {
        val raw = getSharedPreferences(SHEET_STORE_PREFS, MODE_PRIVATE).getString(KEY_SHEET_JSON, "[]") ?: "[]"
        val arr = JSONArray(raw)
        if (arr.length() == 0 && ::repository.isInitialized) {
            val dbList = runBlocking(Dispatchers.IO) { repository.getAllSavedAccountsSync() }
            if (dbList.isNotEmpty()) {
                val rebuilt = JSONArray()
                dbList.forEach { entity ->
                    rebuilt.put(JSONObject().apply {
                        put("uid", entity.uid)
                        put("username", entity.uid)
                        put("pass", entity.password)
                        put("cookies", entity.cookies)
                        put("hotmail", entity.hotmail)
                        put("twofa", entity.twofa)
                        put("number", entity.number)
                        put("live", entity.isLive)
                        put("savedAt", entity.savedAt)
                    })
                }
                saveSheetArray(rebuilt)
                return rebuilt
            }
        }
        arr
    } catch (_: Exception) { JSONArray() }

    internal fun saveSheetArray(arr: JSONArray) {
        try { getSharedPreferences(SHEET_STORE_PREFS, MODE_PRIVATE).edit().putString(KEY_SHEET_JSON, arr.toString()).apply() }
        catch (_: Exception) {}
    }

    fun getSheetJson(): String = getSheetArray().toString()
    fun getSheetCount(): Int = getSheetArray().length()

    fun saveToSheet(uid: String, pass: String, cookies: String, hotmail: String, twofa: String): Boolean {
        try {
            val u = uid.trim(); if (u.isEmpty()) return false
            val finalPass = pass.trim().ifBlank { currentMailPassword.ifBlank { savedPassword } }
            val finalTwofa = twofa.trim().ifBlank {
                current2FAKey.ifBlank { if (currentNumber.isNotBlank()) getMeta2FANative(currentNumber) else "" }
            }
            val finalCookies = cookies.trim().ifBlank { getInstagramCookiesPublic() }

            val arr = getSheetArray()
            var updated = false
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optString("uid") == u || obj.optString("username") == u) {
                    obj.put("username", u)
                    if (finalPass.isNotBlank()) obj.put("pass", finalPass)
                    if (finalCookies.isNotBlank()) obj.put("cookies", finalCookies)
                    if (hotmail.isNotBlank()) obj.put("hotmail", hotmail)
                    if (finalTwofa.isNotBlank()) obj.put("twofa", finalTwofa)
                    obj.put("live", true)
                    obj.put("savedAt", System.currentTimeMillis())
                    updated = true
                    break
                }
            }
            if (!updated) {
                arr.put(JSONObject().apply {
                    put("uid", u)
                    put("username", u)
                    put("pass", finalPass)
                    put("cookies", finalCookies)
                    put("hotmail", hotmail)
                    put("twofa", finalTwofa)
                    put("number", currentNumber)
                    put("live", true)
                    put("savedAt", System.currentTimeMillis())
                })
            }
            saveSheetArray(arr); lastSavedUid = u; lastSavedAt = System.currentTimeMillis()
            if (::repository.isInitialized) {
                repository.saveAccountAsync(
                    SavedAccountEntity(
                        uid = u,
                        password = finalPass,
                        cookies = finalCookies,
                        hotmail = hotmail,
                        twofa = finalTwofa,
                        number = currentNumber,
                        isLive = true,
                        savedAt = System.currentTimeMillis()
                    )
                )
                repository.log(TAG, "SUCCESS", "Saved account $u permanently")
            }
            runOnUiThread { syncSheetDataToHtml() }
            resolveAndUpdateUsernameAsync(u, finalCookies)
            return true
        } catch (_: Exception) { return false }
    }

    fun clearSheetAll(): Boolean {
        try {
            val arr = getSheetArray()
            if (arr.length() > 0) moveToRecovery(arr.toString())
            saveSheetArray(JSONArray())
            if (::repository.isInitialized) repository.clearSavedAccountsAsync()
            return true
        } catch (_: Exception) { return false }
    }

    fun undoLastSave(uid: String): Boolean {
        try {
            val arr = getSheetArray(); val out = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optString("uid") != uid.trim()) out.put(obj)
            }
            saveSheetArray(out)
            if (::repository.isInitialized) repository.deleteAccountAsync(uid.trim())
            return true
        } catch (_: Exception) { return false }
    }

    fun exportSheetToFile(fileName: String, mode: String): Boolean {
        return try {
            val arr = getSheetArray()
            if (arr.length() == 0) { toast("No data to export"); return false }
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val uname = o.optString("username").ifBlank { o.optString("uid") }
                val pass = o.optString("pass")
                val ck = o.optString("cookies")
                val tfa = o.optString("twofa")
                val parts = mutableListOf(uname, pass)
                when (mode) {
                    "1" -> if (ck.isNotBlank()) parts.add(ck)
                    "2", "4" -> if (tfa.isNotBlank()) parts.add(tfa)
                    else -> {
                        if (ck.isNotBlank()) parts.add(ck)
                        if (tfa.isNotBlank()) parts.add(tfa)
                    }
                }
                sb.appendLine(parts.joinToString(" "))
            }
            val finalName = fileName.trim().ifBlank { "trustpoint_export_${System.currentTimeMillis()}.txt" }
            saveFileToDownloadsImpl(finalName, "text/plain", sb.toString())
        } catch (e: Exception) {
            toast("Export failed: ${e.message}")
            false
        }
    }

    fun exportSheetAsXLSX(modeOverride: Int = 0): Boolean {
        return try {
            val arr = getSheetArray()
            if (arr.length() == 0) {
                toast("কোনো সেভ করা ডেটা নেই")
                return false
            }
            val activeMode = if (modeOverride in 1..5) modeOverride else getCurrentSheetModePublic()
            val fmt = java.text.SimpleDateFormat("dd-MM-yy", Locale.US)
            val dateStr = fmt.format(java.util.Date())

            // No header row: Row 1 starts directly with account 1.
            // Col B (Password) is narrower; Col A (Username), Col C, Col D have identical widths.
            val colWidths = when (activeMode) {
                1, 2, 4 -> listOf(22.0, 12.0, 22.0)
                else -> listOf(22.0, 12.0, 22.0, 22.0)
            }
            val rows = mutableListOf<List<String>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val uname = o.optString("username").ifBlank { o.optString("uid") }
                val pass = o.optString("pass")
                val ck = o.optString("cookies")
                val tfa = o.optString("twofa")
                val row = when (activeMode) {
                    1 -> listOf(uname, pass, ck)
                    2, 4 -> listOf(uname, pass, tfa)
                    else -> listOf(uname, pass, ck, tfa)
                }
                rows.add(row)
            }
            val modeLabel = when (activeMode) {
                1 -> "IG_Cookies"
                2, 4 -> "IG_2FA"
                else -> "IG_Cookies_2FA"
            }
            val xlsxBytes = XlsxExporter.buildWorkbookBytes(
                sheetName = "TrustPoint",
                headers = emptyList(),
                rows = rows,
                colWidths = colWidths
            )
            saveBinaryFileToDownloadsImpl(
                "${arr.length()}p_${modeLabel}_${dateStr}.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsxBytes
            )
        } catch (e: Exception) {
            toast("XLSX Export failed: ${e.message}")
            false
        }
    }

    fun exportBackupAsXLSX(json: String): Boolean {
        return try {
            val arr = JSONArray(json)
            if (arr.length() == 0) {
                toast("কোনো ব্যাকআপ ডেটা নেই")
                return false
            }
            val rows = mutableListOf<List<String>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                rows.add(
                    listOf(
                        o.optString("username").ifBlank { o.optString("uid") },
                        o.optString("pass"),
                        o.optString("cookies"),
                        o.optString("twofa")
                    )
                )
            }
            val xlsxBytes = XlsxExporter.buildWorkbookBytes(
                sheetName = "Backup",
                headers = emptyList(),
                rows = rows,
                colWidths = listOf(22.0, 12.0, 22.0, 22.0)
            )
            saveBinaryFileToDownloadsImpl(
                "TrustPoint_Backup_${System.currentTimeMillis()}.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsxBytes
            )
        } catch (e: Exception) {
            toast("Backup failed: ${e.message}")
            false
        }
    }

    fun moveToRecovery(jsonData: String): Boolean {
        return try {
            val prefs = getSharedPreferences(RECOVERY_PREFS, MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(KEY_RECOVERY_JSON, "[]") ?: "[]")
            val now = System.currentTimeMillis()
            val cleaned = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (now - o.optLong("deletedAt", 0L) < RECOVERY_TTL_MS) cleaned.put(o)
            }
            cleaned.put(JSONObject().put("data", jsonData).put("deletedAt", now))
            prefs.edit().putString(KEY_RECOVERY_JSON, cleaned.toString()).apply()
            true
        } catch (_: Exception) { false }
    }

    fun getRecoveryList(): String {
        return try {
            val prefs = getSharedPreferences(RECOVERY_PREFS, MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(KEY_RECOVERY_JSON, "[]") ?: "[]")
            val now = System.currentTimeMillis()
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val age = now - o.optLong("deletedAt", 0L)
                if (age < RECOVERY_TTL_MS) {
                    o.put("hoursLeft", (RECOVERY_TTL_MS - age) / 3600_000)
                    out.put(o)
                }
            }
            out.toString()
        } catch (_: Exception) { "[]" }
    }

    fun purgeExpiredRecovery() {
        try {
            val prefs = getSharedPreferences(RECOVERY_PREFS, MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(KEY_RECOVERY_JSON, "[]") ?: "[]")
            val now = System.currentTimeMillis()
            val cleaned = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (now - o.optLong("deletedAt", 0L) < RECOVERY_TTL_MS) cleaned.put(o)
            }
            prefs.edit().putString(KEY_RECOVERY_JSON, cleaned.toString()).apply()
        } catch (_: Exception) {}
    }

    fun restoreRecoveryItem(idx: Int): Boolean {
        return try {
            val prefs = getSharedPreferences(RECOVERY_PREFS, MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(KEY_RECOVERY_JSON, "[]") ?: "[]")
            if (idx < 0 || idx >= arr.length()) return false
            val item = arr.optJSONObject(idx) ?: return false
            val dataStr = item.optString("data", "")
            if (dataStr.isNotBlank()) {
                val restoreArr = try { JSONArray(dataStr) } catch (_: Exception) { JSONArray() }
                val existing = getSheetArray()
                for (i in 0 until restoreArr.length()) {
                    existing.put(restoreArr.get(i))
                }
                saveSheetArray(existing)
            }
            arr.remove(idx)
            prefs.edit().putString(KEY_RECOVERY_JSON, arr.toString()).apply()
            toast("✓ Restored from recovery")
            true
        } catch (_: Exception) { false }
    }

    fun getUidFromCookiesPublic(): String {
        return try {
            val ck = getInstagramCookiesPublic()
            Regex("ds_user_id=(\\d+)").find(ck)?.groupValues?.get(1) ?: ""
        } catch (_: Exception) { "" }
    }

    fun loginWithCookies(cookies: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            try {
                // Clear only previous Instagram cookies so Telegram stays untouched
                clearInstagramCookiesOnly()
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                val targetUrls = listOf(
                    "https://www.instagram.com",
                    "https://instagram.com",
                    "https://i.instagram.com",
                    "https://m.instagram.com"
                )
                for (p in cookies.split(";")) {
                    val kv = p.split("=", limit = 2)
                    if (kv.size == 2) {
                        val k = kv[0].trim()
                        val v = kv[1].trim()
                        if (k.isNotEmpty() && v.isNotEmpty()) {
                            targetUrls.forEach { domainUrl ->
                                cm.setCookie(domainUrl, "$k=$v; path=/")
                            }
                            cm.setCookie("https://www.instagram.com", "$k=$v; domain=.instagram.com; path=/")
                        }
                    }
                }
                cm.flush()
                prepareNativeWebForAutomation()
                lastUrl = HOME_URL
                if (::nativeWebView.isInitialized) {
                    try {
                        cm.setAcceptThirdPartyCookies(nativeWebView, true)
                        nativeWebView.onResume()
                        nativeWebView.resumeTimers()
                    } catch (_: Exception) {}
                    nativeWebView.loadUrl(HOME_URL)
                }
                mainHandler.postDelayed({ pushLiveCredsToHtml() }, 1400)
                toast("✓ Logging in with saved account...")
            } catch (e: Exception) {
                Log.e(TAG, "loginWithCookies error: ${e.message}")
            }
        }
    }

    internal fun updateProxyConnectionStatus(failed: Boolean) {
        proxyConnectionFailed = failed
        try { getSharedPreferences(PROXY_PREFS, MODE_PRIVATE).edit().putBoolean(KEY_PROXY_FAILED, failed).apply() }
        catch (_: Exception) {}
    }

    fun togglePage1Proxy(enabled: Boolean) {
        if (isProxyToggling) return
        if (enabled && (currentProxyIp.isBlank() || currentProxyPort.isBlank())) {
            page1ProxyEnabled = false
            page2ProxyEnabled = false
            proxyActuallyActive = false
            toast("⚠️ আগে Menu → Proxy Set থেকে IP ও Port সেট করুন")
            updateProxyBtnUI(false, "আগে Proxy IP/Port সেট করুন")
            return
        }
        isProxyToggling = true
        updateProxyConnectionStatus(false)
        page1ProxyEnabled = enabled; page2ProxyEnabled = enabled
        try {
            getSharedPreferences(PROXY_PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_PAGE1_PROXY, enabled).putBoolean(KEY_PAGE2_PROXY, enabled).apply()
        } catch (_: Exception) {}
        applyActiveProxy {
            updateProxyBtnUI(proxyActuallyActive)
        }
    }

    fun togglePage2Proxy(enabled: Boolean) {
        if (isProxyToggling) return
        if (enabled && (currentProxyIp.isBlank() || currentProxyPort.isBlank())) {
            page1ProxyEnabled = false
            page2ProxyEnabled = false
            proxyActuallyActive = false
            toast("⚠️ আগে Menu → Proxy Set থেকে IP ও Port সেট করুন")
            updateProxyBtnUI(false, "আগে Proxy IP/Port সেট করুন")
            return
        }
        isProxyToggling = true
        updateProxyConnectionStatus(false)
        page2ProxyEnabled = enabled; page1ProxyEnabled = enabled
        try {
            getSharedPreferences(PROXY_PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_PAGE2_PROXY, enabled).putBoolean(KEY_PAGE1_PROXY, enabled).apply()
        } catch (_: Exception) {}
        applyActiveProxy {
            updateProxyBtnUI(proxyActuallyActive)
        }
    }

    internal fun applyActiveProxy(onComplete: (() -> Unit)? = null) {
        val should = if (isBrowserModeOpen) page2ProxyEnabled else page1ProxyEnabled
        if (!should) { clearProxyOverride { runOnUiThread { onComplete?.invoke() } }; isProxyToggling = false; return }
        if (proxyConnectionFailed || currentProxyIp.isBlank() || currentProxyPort.isBlank()) {
            proxyActuallyActive = false
            isProxyToggling = false
            runOnUiThread {
                updateProxyBtnUI(false)
                onComplete?.invoke()
            }
            return
        }
        applyProxyOverride(onComplete)
    }

    internal fun applyProxyOverride(onComplete: (() -> Unit)? = null) {
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                isProxyToggling = false
                toast("❌ Proxy Override এই WebView-তে সাপোর্টেড নয়")
                runOnUiThread {
                    updateProxyBtnUI(false)
                    onComplete?.invoke()
                }
                return
            }
            val cleanHost = currentProxyIp
                .removePrefix("http://")
                .removePrefix("https://")
                .removePrefix("socks5://")
                .removePrefix("socks4://")
                .trim()
            val rule = if (currentProxyIp.lowercase().startsWith("socks")) {
                "socks5://${cleanHost}:${currentProxyPort}"
            } else {
                "http://${cleanHost}:${currentProxyPort}"
            }
            val config = ProxyConfig.Builder().addProxyRule(rule)
                .addBypassRule("127.0.0.1").addBypassRule("localhost").addBypassRule("<local>").build()
            ProxyController.getInstance().setProxyOverride(config, proxyExecutor) {
                proxyActuallyActive = true; isProxyToggling = false
                updateProxyConnectionStatus(false)
                AutomationRuntimeState.update(this) { it.copy(proxyActive = true) }
                AutomationRuntimeState.logExecution(this, "Proxy", "SUCCESS", "Proxy ON ($cleanHost:$currentProxyPort)")
                runOnUiThread {
                    updateProxyBtnUI(true, "Proxy ON ($cleanHost)")
                    onComplete?.invoke()
                }
            }
        } catch (e: Throwable) {
            proxyActuallyActive = false
            isProxyToggling = false
            runOnUiThread {
                updateProxyBtnUI(false, "Proxy Error")
                onComplete?.invoke()
            }
        }
    }

    internal fun clearProxyOverride(onDone: (() -> Unit)? = null) {
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                proxyActuallyActive = false; isProxyToggling = false
                AutomationRuntimeState.update(this) { it.copy(proxyActive = false) }
                runOnUiThread {
                    updateProxyBtnUI(false)
                    onDone?.invoke()
                }
                return
            }
            ProxyController.getInstance().clearProxyOverride(proxyExecutor) {
                proxyActuallyActive = false; isProxyToggling = false
                AutomationRuntimeState.update(this) { it.copy(proxyActive = false) }
                AutomationRuntimeState.logExecution(this, "Proxy", "INFO", "Proxy turned OFF")
                runOnUiThread {
                    updateProxyBtnUI(false, "Proxy OFF")
                    onDone?.invoke()
                }
            }
        } catch (_: Throwable) {
            proxyActuallyActive = false; isProxyToggling = false
            runOnUiThread {
                updateProxyBtnUI(false)
                onDone?.invoke()
            }
        }
    }

    fun setBrowserProxy(ip: String, port: String, user: String, pass: String) {
        currentProxyIp = ip.trim(); currentProxyPort = port.trim()
        currentProxyUser = user.trim(); currentProxyPass = pass
        try {
            getSharedPreferences(PROXY_PREFS, MODE_PRIVATE).edit()
                .putString(KEY_PROXY_IP, currentProxyIp).putString(KEY_PROXY_PORT, currentProxyPort)
                .putString(KEY_PROXY_USER, currentProxyUser).putString(KEY_PROXY_PASS, currentProxyPass).apply()
        } catch (_: Exception) {}
        AutomationRuntimeState.logExecution(this, "Proxy", "INFO", "Proxy credentials saved (${currentProxyIp}:${currentProxyPort})")
    }

    internal fun createOtpChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(OTP_CHANNEL_ID, OTP_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.createNotificationChannel(ch)
            }
        } catch (_: Exception) {}
    }

    internal fun showOtpNotification(otp: String, info: String, silent: Boolean = false) {
        if (otp.isBlank() || isFinishing || isDestroyed) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            }
            val i = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val p = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder = NotificationCompat.Builder(this, OTP_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("OTP: $otp").setContentText(info).setAutoCancel(true)
                .setContentIntent(p).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
            if (silent) { builder.setSilent(true); builder.setPriority(NotificationCompat.PRIORITY_LOW) }
            NotificationManagerCompat.from(this).notify(((info + otp).hashCode() and 0x7FFFFFFF), builder.build())
        } catch (_: Exception) {}
    }

    override fun onLog(message: String) {
        Log.d("StexApi", message)
        AutomationRuntimeState.logExecution(this, "StexApi", "INFO", message)
    }

    internal fun fetchRangesFromPanel() {
        try {
            if (!StexApiService.hasApiKey()) {
                onRangesFailed("API Key missing (using default ranges)", "")
                return
            }
            lifecycleScope.launch {
                try {
                    val r = StexApiService.getLiveAccess()
                    if (r.isSuccess) onRangesLoaded(r.getOrNull() ?: emptyList())
                    else onRangesFailed(r.exceptionOrNull()?.message ?: "Unknown", "")
                } catch (e: Exception) {
                    onRangesFailed(e.message ?: "Unknown", "")
                }
            }
        } catch (_: Exception) {}
    }

    override fun onRangesLoaded(ranges: List<StexApiService.RangeInfo>) {
        if (isFinishing || isDestroyed || !::mainWebView.isInitialized) return
        runOnUiThread {
            if (isFinishing || isDestroyed || !::mainWebView.isInitialized) return@runOnUiThread
            try {
                val top20 = ranges.take(20)
                val json = buildString {
                    append("[")
                    top20.forEachIndexed { i, r ->
                        if (i > 0) append(",")
                        append("{\"rid\":\"").append(esc(r.rid)).append("\",")
                        append("\"range\":\"").append(esc(r.range)).append("\",")
                        append("\"label\":\"").append(esc(r.label)).append("\",")
                        append("\"country\":\"").append(esc(r.country)).append("\"}")
                    }
                    append("]")
                }
                mainWebView.evaluateJavascript(
                    "(function(){if(window.TP&&window.TP.onRangesLoaded)window.TP.onRangesLoaded(${quote(json)});})();", null
                )
            } catch (_: Exception) {}
        }
    }

    override fun onRangesFailed(error: String, rawResponse: String) {
        if (isFinishing || isDestroyed || !::mainWebView.isInitialized) return
        runOnUiThread {
            if (isFinishing || isDestroyed || !::mainWebView.isInitialized) return@runOnUiThread
            try {
                val safeErr = JSONObject.quote(error)
                mainWebView.evaluateJavascript(
                    "(function(){try{if(window.TP&&window.TP.onRangesFailed)window.TP.onRangesFailed($safeErr,'');}catch(e){}})();", null
                )
            } catch (_: Exception) {}
        }
    }

    internal fun requestNumberFromPanel(rid: String) {
        try {
            if (!StexApiService.hasApiKey()) {
                toast("⚠️ Auto & BG থেকে Panel API Key সেট করুন")
                onNumberFailed("API Key missing")
                return
            }
            val realRid = StexApiService.resolveRid(rid.ifBlank { StexApiService.getSelectedRid() })
            if (realRid.isBlank()) {
                toast("⚠️ আগে Range সিলেক্ট করুন")
                onNumberFailed("No range selected")
                return
            }
            lifecycleScope.launch {
                try {
                    val r = StexApiService.allocateNumber(realRid)
                    if (r.isSuccess) {
                        val n = r.getOrNull()!!
                        onNumberAllocated(n.number, realRid, n.country, n.operator)
                    } else onNumberFailed(r.exceptionOrNull()?.message ?: "Unknown")
                } catch (e: Exception) {
                    onNumberFailed(e.message ?: "Unknown")
                }
            }
        } catch (_: Exception) {}
    }

    override fun onNumberAllocated(number: String, rid: String, country: String, operator: String) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            try {
                copyToClipboard(number, "Number Copied: $number")
                allocatedNumber = number; currentNumber = number; currentCountry = country
                currentOtp = ""; activeBoxSource = "number"
                if (currentMailPassword.isBlank() && savedPassword.isNotBlank()) {
                    currentMailPassword = savedPassword
                }
                saveCurrentMail()
                pushNumberToHtml(number, country)
                clearOtpOnHtml()
                val curUrl = if (::nativeWebView.isInitialized) nativeWebView.url.orEmpty() else ""
                val inSignup = ::igAutomation.isInitialized && igAutomation.isSignupMode()
                if (!inSignup && curUrl.contains("/accounts/login")) {
                    fillInstagramLogin(number, currentMailPassword)
                }
                StexApiService.startOtpPolling(number, this)
                FloatingBubbleService.refreshOverlayIfRunning(this)
                if (isActivityVisible) {
                    val hasNotifPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    if (hasNotifPerm) {
                        OtpPollingService.startSafely(this, number)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    override fun onNumberFailed(error: String) { runOnUiThread { toast("Number: $error") } }

    override fun onOtpReceived(otp: String, number: String) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            try {
                val now = System.currentTimeMillis()
                if (otp == lastOtpReceived && now - lastOtpTime < 15000) return@runOnUiThread
                lastOtpReceived = otp; lastOtpTime = now
                currentOtp = otp; saveCurrentMail()
                copyToClipboard(otp, "OTP: $otp")
                showOtpNotification(otp, number)
                pushOtpToHtml(otp)
                if (::igAutomation.isInitialized) {
                    igAutomation.fillSmsOtp(otp)
                }
                FloatingBubbleService.refreshOverlayIfRunning(this)
                OtpPollingService.stopSafely(this)
            } catch (_: Exception) {}
        }
    }

    override fun onOtpTimeout() {
        OtpPollingService.stopSafely(this)
        runOnUiThread { toast("OTP timeout") }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pickVisualMediaLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            try {
                deliverActiveFileChooserResult(uri?.let { arrayOf(it) })
            } catch (t: Throwable) {
                Log.e(TAG, "PickVisualMedia result error: ${t.message}")
                deliverActiveFileChooserResult(null)
            }
        }

        try {
            repository = TrustPointRepository.getInstance(this)
        } catch (t: Throwable) {
            Log.e(TAG, "Repository init error: ${t.message}")
        }
        AutomationRuntimeState.attachActivity(this)

        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.flush()
        } catch (_: Throwable) {}
        try { TelegramBotActivity.attachHostActivity(this) } catch (_: Throwable) {}

        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
            window.decorView.setBackgroundColor(Color.BLACK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.statusBarColor = Color.BLACK
                window.navigationBarColor = Color.BLACK
            }
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        } catch (_: Throwable) {}

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
            clipToPadding = true
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            mainHandler.post {
                if (!isFinishing && !isDestroyed && ::mainWebView.isInitialized) {
                    try {
                        mainWebView.evaluateJavascript("(function(){try{window.dispatchEvent(new Event('resize'));}catch(e){}})();", null)
                    } catch (_: Exception) {}
                }
            }
            WindowInsetsCompat.CONSUMED
        }

        try {
            mainWebView = WebView(this).apply { setBackgroundColor(Color.BLACK) }
            root.addView(mainWebView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            nativeWebView = WebView(this).apply {
                setBackgroundColor(Color.BLACK)
                visibility = View.GONE
            }
            root.addView(nativeWebView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        } catch (t: Throwable) {
            Log.e(TAG, "WebView allocation error: ${t.message}")
            toast("⚠️ WebView failed to initialize")
        }

        try { window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING) } catch (_: Throwable) {}

        darkModeEnabled = try {
            getSharedPreferences(THEME_PREFS, MODE_PRIVATE).getBoolean(KEY_DARK_MODE, true)
        } catch (_: Throwable) { true }
        autoNumberEnabled = try {
            getSharedPreferences(AUTONUM_PREFS, MODE_PRIVATE).getBoolean(KEY_AUTONUM, false)
        } catch (_: Throwable) { false }
        restoreFloatingBubblePref()

        try {
            if (::nativeWebView.isInitialized) {
                nativeWebView.visibility = View.INVISIBLE
                nativeWebSuppressed = true
            }
        } catch (_: Throwable) {}

        restoreActivePage()

        try { hotmailPool = HotmailPool(this) } catch (_: Throwable) {}

        try {
            if (::mainWebView.isInitialized) initMainWebView()
            if (::nativeWebView.isInitialized) initNativeWebView()
        } catch (t: Throwable) {
            Log.e(TAG, "WebView init error: ${t.message}")
        }

        ensureTelegramFloatingFab()
        startOverlayPolling()

        if (::nativeWebView.isInitialized) {
            igAutomation = IgAutomation(this, nativeWebView, mainHandler)
        }

        try { liveChecker = LiveUidChecker(this) } catch (_: Throwable) {}

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                try {
                    if (telegramPopupLocked || backButtonBlocked) {
                        toast("🔒 Telegram Locked! Unlock first")
                        return
                    }
                    if (uiOverlayActive) {
                        closeTelegramOverlayAndRestoreInsta()
                        return
                    }
                    if (::nativeWebView.isInitialized && nativeWebView.visibility == View.VISIBLE && nativeWebView.canGoBack()) {
                        nativeWebView.goBack()
                    } else {
                        showFloatingBubble(minimizeApp = true)
                    }
                } catch (_: Exception) {}
            }
        })

        mainHandler.post {
            if (isFinishing || isDestroyed) return@post
            try {
                StexApiService.init(this)
                createOtpChannel()
                loadSavedPassword()
                restoreCurrentMail()
                purgeExpiredRecovery()
                try {
                    val pp = getSharedPreferences(PROXY_PREFS, MODE_PRIVATE)
                    currentProxyIp = pp.getString(KEY_PROXY_IP, "") ?: ""
                    currentProxyPort = pp.getString(KEY_PROXY_PORT, "") ?: ""
                    currentProxyUser = pp.getString(KEY_PROXY_USER, "") ?: ""
                    currentProxyPass = pp.getString(KEY_PROXY_PASS, "") ?: ""
                    page1ProxyEnabled = pp.getBoolean(KEY_PAGE1_PROXY, false)
                    page2ProxyEnabled = pp.getBoolean(KEY_PAGE2_PROXY, false)
                    proxyConnectionFailed = pp.getBoolean(KEY_PROXY_FAILED, false)
                    mainHandler.postDelayed({
                        if (!isFinishing && !isDestroyed) {
                            pushProxyValuesToHtml()
                            if (page1ProxyEnabled && currentProxyIp.isNotBlank() && currentProxyPort.isNotBlank() && !proxyConnectionFailed) {
                                applyActiveProxy { updateProxyBtnUI(proxyActuallyActive) }
                            }
                        }
                    }, 700)
                } catch (_: Throwable) {}
                mainHandler.postDelayed({ if (!isFinishing && !isDestroyed) requestAllPermissionsIfNeeded() }, 600)
                mainHandler.postDelayed({ if (!isFinishing && !isDestroyed && isActivityVisible) startKeepAliveBackgroundService() }, 700)
                mainHandler.postDelayed(copyBtnInjector, 1500)
                mainHandler.postDelayed({ if (!isFinishing && !isDestroyed) forceRevealBoot() }, 2500)
                val quickUser = intent?.getStringExtra("extra_quick_login_user").orEmpty()
                if (quickUser.isNotBlank()) {
                    val quickPass = intent?.getStringExtra("extra_quick_login_pass").orEmpty()
                    mainHandler.postDelayed({
                        if (!isFinishing && !isDestroyed) quickLoginInstagramFromOverlay(quickUser, quickPass)
                    }, 900)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "deferred onCreate error: ${e.message}")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("extra_start_auto_on_resume", false)) {
            mainHandler.postDelayed({
                if (!isFinishing && !isDestroyed) startAutomationFromOverlay()
            }, 400)
        }
        val quickUser = intent.getStringExtra("extra_quick_login_user").orEmpty()
        if (quickUser.isNotBlank()) {
            val quickPass = intent.getStringExtra("extra_quick_login_pass").orEmpty()
            mainHandler.postDelayed({
                if (!isFinishing && !isDestroyed) quickLoginInstagramFromOverlay(quickUser, quickPass)
            }, 450)
        }
    }

    override fun onStart() {
        super.onStart()
        isActivityVisible = true
        try {
            if (::mainWebView.isInitialized) mainWebView.onResume()
            if (::nativeWebView.isInitialized) nativeWebView.onResume()
            if (::browserWebView.isInitialized) browserWebView.onResume()
        } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        isActivityVisible = true
        isRequestingSystemPermission = false
        updateNativeTgFabVisibility()
        if (!isPickingFileOrPhoto && !AutomationRuntimeState.state.value.backgroundServiceRunning) {
            mainHandler.postDelayed({
                if (isActivityVisible && !isFinishing && !isDestroyed && !isPickingFileOrPhoto && !isRequestingSystemPermission) {
                    startKeepAliveBackgroundService()
                }
            }, 350)
        }
    }

    override fun onPause() {
        try { CookieManager.getInstance().flush() } catch (_: Throwable) {}
        super.onPause()
    }

    override fun onUserLeaveHint() {
        try { CookieManager.getInstance().flush() } catch (_: Throwable) {}
        super.onUserLeaveHint()
    }

    override fun onStop() {
        try { CookieManager.getInstance().flush() } catch (_: Throwable) {}
        isActivityVisible = false
        super.onStop()
    }

    internal fun pushLiveCredsToHtml() {
        try {
            if (isFinishing || isDestroyed || !::mainWebView.isInitialized) return
            val uid = getUidFromCookiesPublic()
            val ck = getInstagramCookiesPublic()
            val sUid = esc(uid)
            val sCk = esc(ck)
            val js = "(function(){try{var u=document.getElementById('webUid');if(u)u.value='$sUid';" +
                    "var c=document.getElementById('webCookies');if(c)c.value='$sCk';" +
                    "if(window.TP&&window.TP.setCookiesData)window.TP.setCookiesData('$sCk');}catch(e){}})();"
            mainWebView.evaluateJavascript(js, null)
        } catch (_: Exception) {}
    }

    internal fun getPermissionStatusJson(): String {
        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val batteryOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(packageName) == true
        } else true
        val overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val bgRunning = AutomationRuntimeState.state.value.backgroundServiceRunning
        return JSONObject().apply {
            put("notifications", notifOk)
            put("battery", batteryOk)
            put("overlay", overlayOk)
            put("bgRunning", bgRunning)
            put("bubbleEnabled", floatingBubbleEnabled)
        }.toString()
    }

    internal fun requestNotificationPermissionNow() {
        if (isFinishing || isDestroyed) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    isRequestingSystemPermission = true
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIF_PERMISSION_REQUEST)
                    return
                }
            }
            toast("✓ Notification permission already granted")
        } catch (t: Throwable) {
            isRequestingSystemPermission = false
            try {
                val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(i)
            } catch (_: Throwable) {}
        }
    }

    internal fun requestBatteryOptimizationNow() {
        if (isFinishing || isDestroyed || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && pm.isIgnoringBatteryOptimizations(packageName)) {
                toast("✓ Background Battery Unrestricted চালু আছে")
                return
            }
            isRequestingSystemPermission = true
            val directIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            if (directIntent.resolveActivity(packageManager) != null) {
                @Suppress("DEPRECATION")
                startActivityForResult(directIntent, BATTERY_OPTIMIZATION_REQUEST_CODE)
                toast("ব্যাকগ্রাউন্ডে চলার জন্য 'Allow' দিন")
            } else {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(fallback)
                toast("TrustPoint-এর জন্য Unrestricted সিলেক্ট করুন")
            }
        } catch (_: Throwable) {
            isRequestingSystemPermission = false
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) {
                toast("Settings থেকে Battery Unrestricted দিন")
            }
        }
    }

    internal fun requestOverlayPermissionNow() {
        if (isFinishing || isDestroyed || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            if (Settings.canDrawOverlays(this)) {
                toast("✓ Overlay permission already granted")
                startBubbleServiceOnly()
                return
            }
            isRequestingSystemPermission = true
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            @Suppress("DEPRECATION")
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
            toast("ফ্লোটিং বাবলের জন্য 'Display over other apps' অন করুন")
        } catch (_: Throwable) {
            isRequestingSystemPermission = false
            toast("Settings → Display over other apps অন করুন")
        }
    }

    internal fun startBackgroundServiceFromUserAction() {
        if (isFinishing || isDestroyed) return
        floatingBubbleEnabled = true
        try {
            getSharedPreferences(BG_PREFS, MODE_PRIVATE).edit().putBoolean(KEY_BUBBLE_ENABLED, true).apply()
        } catch (_: Exception) {}
        AutomationRuntimeState.update(this) { it.copy(floatingBubbleEnabled = true) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermissionNow()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            requestOverlayPermissionNow()
            return
        }
        val ok = startBubbleServiceOnly()
        toast(if (ok) "✓ Background & Floating Service Active" else "⚠️ Permission দিয়ে আবার চেষ্টা করুন")
    }

    internal fun requestAllPermissionsIfNeeded() {
        if (isFinishing || isDestroyed) return
        try {
            val perms = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    perms.add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            if (perms.isNotEmpty()) {
                val prefs = getSharedPreferences(BG_PREFS, MODE_PRIVATE)
                if (!prefs.getBoolean("notif_asked_once", false)) {
                    prefs.edit().putBoolean("notif_asked_once", true).apply()
                    isRequestingSystemPermission = true
                    ActivityCompat.requestPermissions(this, perms.toTypedArray(), NOTIF_PERMISSION_REQUEST)
                }
                return
            }
        } catch (e: Throwable) {
            isRequestingSystemPermission = false
            Log.e(TAG, "requestAllPermissionsIfNeeded error: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        isRequestingSystemPermission = false
        if (requestCode == NOTIF_PERMISSION_REQUEST) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            if (granted && floatingBubbleEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startBubbleServiceOnly()
            }
        }
    }

    private fun extractUrisFromIntent(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        return try {
            val clip = data.clipData
            if (clip != null && clip.itemCount > 0) {
                val list = mutableListOf<Uri>()
                for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let { list.add(it) }
                if (list.isNotEmpty()) list.toTypedArray() else null
            } else {
                data.data?.let { arrayOf(it) }
            }
        } catch (_: Exception) { null }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        isRequestingSystemPermission = false
        try {
            if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                    isActivityVisible = true
                    startBubbleServiceOnly()
                    toast("✓ Overlay & Background permission active")
                } else {
                    toast("Overlay permission denied")
                }
                return
            }
            if (requestCode == BATTERY_OPTIMIZATION_REQUEST_CODE) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm?.isIgnoringBatteryOptimizations(packageName) == true) {
                    toast("✓ Background Battery Unrestricted Active")
                }
                return
            }
            if (requestCode == FILE_CHOOSER_REQUEST_CODE || requestCode == NATIVE_FILE_CHOOSER_REQUEST_CODE) {
                val results = extractUrisFromIntent(resultCode, data)
                deliverActiveFileChooserResult(results)
                return
            }
        } catch (_: Throwable) {
            deliverActiveFileChooserResult(null)
        }
    }

    override fun onDestroy() {
        try { CookieManager.getInstance().flush() } catch (_: Exception) {}
        isActivityVisible = false
        try { filePathCallback?.onReceiveValue(null) } catch (_: Exception) {}
        try { nativeFilePathCallback?.onReceiveValue(null) } catch (_: Exception) {}
        filePathCallback = null
        nativeFilePathCallback = null
        try { htmlIntegrityCheckRunnable?.let { mainHandler.removeCallbacks(it) } } catch (_: Exception) {}
        try { mainHandler.removeCallbacks(copyBtnInjector) } catch (_: Exception) {}
        try { mainHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
        try { if (::liveChecker.isInitialized) liveChecker.destroy() } catch (_: Exception) {}
        try { resetTelegramPopupWebViewRef() } catch (_: Exception) {}
        try { clearProxyOverride() } catch (_: Exception) {}
        super.onDestroy()
    }

    inner class SheetBridge {
        @JavascriptInterface fun saveToSheet(uid: String, pass: String, cookies: String, hotmail: String, twofa: String): Boolean =
            this@MainActivity.saveToSheet(uid, pass, cookies, hotmail, twofa)
        @JavascriptInterface fun getSheetJson(): String = this@MainActivity.getSheetJson()
        @JavascriptInterface fun getSavedAccounts(): String = this@MainActivity.getSheetJson()
        @JavascriptInterface fun getSavedSheetData(): String = this@MainActivity.getSheetJson()
        @JavascriptInterface fun getSheetCount(): Int = this@MainActivity.getSheetCount()
        @JavascriptInterface fun exportSheetAsXLSX(): Boolean {
            return this@MainActivity.exportSheetAsXLSX(0)
        }
        @JavascriptInterface fun exportSheetAsXLSXWithMode(mode: Int): Boolean {
            return this@MainActivity.exportSheetAsXLSX(mode)
        }
        @JavascriptInterface fun exportBackupAsXLSX(json: String) { this@MainActivity.runOnUiThread { this@MainActivity.exportBackupAsXLSX(json) } }
        @JavascriptInterface fun clearSheetAll(): Boolean = this@MainActivity.clearSheetAll()
        @JavascriptInterface fun clearSheetData(): Boolean = this@MainActivity.clearSheetAll()
        @JavascriptInterface fun undoLastSave(uid: String): Boolean = this@MainActivity.undoLastSave(uid)
        @JavascriptInterface fun deleteSavedAccount(uid: String): Boolean = this@MainActivity.undoLastSave(uid)
        @JavascriptInterface fun getLastSavedUid(): String = this@MainActivity.lastSavedUid
        @JavascriptInterface fun getSheetMode(): Int = this@MainActivity.getCurrentSheetModePublic()
        @JavascriptInterface fun setSheetMode(mode: Int) { this@MainActivity.setCurrentSheetMode(mode) }
        @JavascriptInterface fun getUidFromCookies(): String = this@MainActivity.getUidFromCookiesPublic()
        @JavascriptInterface fun getCookies(): String = this@MainActivity.getInstagramCookiesPublic()
        @JavascriptInterface fun getSavedPassword(): String = this@MainActivity.currentMailPassword.ifBlank { this@MainActivity.savedPassword }
        @JavascriptInterface fun savePassword(pwd: String) {
            if (pwd.isNotBlank()) this@MainActivity.currentMailPassword = pwd
            this@MainActivity.savePassword(pwd)
        }
        @JavascriptInterface fun getLastGeneratedUsername(): String = this@MainActivity.fetchLastGeneratedUsername()
        @JavascriptInterface fun exportSheetToFile(fileName: String, mode: String): Boolean =
            this@MainActivity.exportSheetToFile(fileName, mode)
        @JavascriptInterface fun moveToRecovery(json: String): Boolean = this@MainActivity.moveToRecovery(json)
        @JavascriptInterface fun getRecoveryList(): String = this@MainActivity.getRecoveryList()
        @JavascriptInterface fun purgeExpiredRecovery() { this@MainActivity.purgeExpiredRecovery() }
        @JavascriptInterface fun restoreRecoveryItem(idx: Int): Boolean = this@MainActivity.restoreRecoveryItem(idx)
        @JavascriptInterface fun recoverSheetData(): Boolean = this@MainActivity.restoreRecoveryItem(0)

        @JavascriptInterface fun saveFileToDownloadsImpl(filename: String, mimeType: String, data: String): Boolean =
            this@MainActivity.saveFileToDownloadsImpl(filename, mimeType, data)
        @JavascriptInterface fun saveFileToDownloads(filename: String, mimeType: String, data: String): Boolean =
            this@MainActivity.saveFileToDownloadsImpl(filename, mimeType, data)
        @JavascriptInterface fun downloadFile(filename: String, data: String): Boolean =
            this@MainActivity.saveFileToDownloadsImpl(filename, "text/plain", data)
        @JavascriptInterface fun downloadTxt(filename: String, data: String): Boolean =
            this@MainActivity.saveFileToDownloadsImpl(filename, "text/plain", data)

        @JavascriptInterface fun addMetaPool(text: String): String = this@MainActivity.addMetaPoolNative(text)
        @JavascriptInterface fun saveMetaPool(text: String): String = this@MainActivity.addMetaPoolNative(text)
        @JavascriptInterface fun getMeta(): String = this@MainActivity.getFreshMetaNative()
        @JavascriptInterface fun peekFreshMeta(): String = this@MainActivity.peekFreshMetaNative()
        @JavascriptInterface fun getMetaStats(): String = this@MainActivity.getMetaStatsNative()
        @JavascriptInterface fun getMetaList(): String = this@MainActivity.getMetaListNative()
        @JavascriptInterface fun clearMetaPool(): Boolean = this@MainActivity.clearMetaPoolNative()
        @JavascriptInterface fun resetMetaPool(): Boolean = this@MainActivity.resetMetaPoolNative()
        @JavascriptInterface fun removeMeta(phone: String): Boolean = this@MainActivity.removeMetaNative(phone)
        @JavascriptInterface fun saveMeta2FA(phone: String, secret: String): Boolean = this@MainActivity.saveMeta2FANative(phone, secret)
        @JavascriptInterface fun getMeta2FA(phone: String): String = this@MainActivity.getMeta2FANative(phone)
        @JavascriptInterface fun getCurrentMetaPhone(): String = this@MainActivity.currentNumber
        @JavascriptInterface fun getCurrentMetaPassword(): String = this@MainActivity.currentMailPassword
        @JavascriptInterface fun getCurrentMeta2FA(): String = this@MainActivity.current2FAKey

        @JavascriptInterface fun getMetaListNative(): String = this@MainActivity.getMetaListNative()
        @JavascriptInterface fun getMetaStatsNative(): String = this@MainActivity.getMetaStatsNative()
        @JavascriptInterface fun addMetaPoolNative(text: String): String = this@MainActivity.addMetaPoolNative(text)
        @JavascriptInterface fun saveMetaPoolJsonNative(json: String): Boolean = this@MainActivity.saveMetaPoolJsonNative(json)
        @JavascriptInterface fun clearMetaPoolNative(): Boolean = this@MainActivity.clearMetaPoolNative()
        @JavascriptInterface fun resetMetaPoolNative(): Boolean = this@MainActivity.resetMetaPoolNative()
        @JavascriptInterface fun markMetaBrokenNative(phone: String): Boolean = this@MainActivity.markMetaBrokenNative(phone)
        @JavascriptInterface fun markMetaUsedNative(phone: String): Boolean = this@MainActivity.markMetaUsedNative(phone)

        @JavascriptInterface fun getManualNumber(rid: String) { this@MainActivity.runOnUiThread { this@MainActivity.requestNumberFromPanel(rid) } }
        @JavascriptInterface fun loadRanges() { this@MainActivity.runOnUiThread { this@MainActivity.fetchRangesFromPanel() } }
        @JavascriptInterface fun getSavedRange(): String = this@MainActivity.getSavedRange()
        @JavascriptInterface fun saveSelectedRange(json: String) { this@MainActivity.runOnUiThread { this@MainActivity.saveSelectedRange(json) } }
        @JavascriptInterface fun saveSelectedPanel(panel: String) { StexApiService.setSelectedPanel(panel) }
        @JavascriptInterface fun getSelectedPanel(): String = StexApiService.getSelectedPanel()
        @JavascriptInterface fun openTelegramBotActivity() { this@MainActivity.runOnUiThread { TelegramBotActivity.open(this@MainActivity) } }

        @JavascriptInterface fun copyToClipboard(text: String) { this@MainActivity.copyToClipboard(text, "Copied") }
        @JavascriptInterface fun copyCurrentUsername() { this@MainActivity.runOnUiThread { this@MainActivity.copyCurrentUsername() } }
        @JavascriptInterface fun setUiOverlay(active: Boolean) { this@MainActivity.webInterface.setUiOverlay(active) }
        @JavascriptInterface fun setTelegramLocked(locked: Boolean) {
            this@MainActivity.runOnUiThread {
                if (!this@MainActivity.isFinishing && !this@MainActivity.isDestroyed) {
                    this@MainActivity.setTelegramPopupLockState(locked)
                }
            }
        }
        @JavascriptInterface fun setTelegramTaskEnabled(enabled: Boolean) {
            this@MainActivity.telegramTaskEnabled = enabled
            this@MainActivity.updateNativeTgFabVisibility()
        }
        @JavascriptInterface fun setTelegramWebUrl(url: String) {
            if (url.isNotBlank()) this@MainActivity.pendingTelegramUrl = url.trim()
        }
        @JavascriptInterface fun loadTelegramPopupUrl(url: String): Boolean =
            this@MainActivity.loadTelegramPopupUrl(url.ifBlank { DEFAULT_TG_LOGIN_URL })
        @JavascriptInterface fun onTelegramTaskTriggered() {
            this@MainActivity.runOnUiThread { this@MainActivity.openTelegramOverlayInHtml(DEFAULT_TG_LOGIN_URL) }
        }
        @JavascriptInterface fun onTelegramTaskTriggered(url: String) {
            this@MainActivity.runOnUiThread { this@MainActivity.openTelegramOverlayInHtml(url.ifBlank { DEFAULT_TG_LOGIN_URL }) }
        }
        @JavascriptInterface fun submitTelegram2FA(key: String, code: String) {
            this@MainActivity.current2FAKey = key.trim()
            this@MainActivity.saveCurrentMail()
            this@MainActivity.copyToClipboard(code, "2FA Submitted: $code")
        }
        @JavascriptInterface fun hideTelegramPopupIfClosed() { this@MainActivity.closeTelegramOverlayAndRestoreInsta() }
        @JavascriptInterface fun setBackButtonBlocked(blocked: Boolean) {
            this@MainActivity.backButtonBlocked = blocked || this@MainActivity.telegramPopupLocked
        }
        @JavascriptInterface fun openTelegramBotWindow() { this@MainActivity.openTelegramOverlayInHtml(DEFAULT_TG_LOGIN_URL) }
        @JavascriptInterface fun openTelegramInPopup(url: String): Boolean {
            this@MainActivity.openTelegramOverlayInHtml(url.ifBlank { DEFAULT_TG_LOGIN_URL }); return true
        }
        @JavascriptInterface fun clearTelegramSession() { this@MainActivity.clearTelegramSession() }

        @JavascriptInterface fun setFloatingBubbleEnabled(enabled: Boolean) { this@MainActivity.setFloatingBubbleEnabled(enabled) }
        @JavascriptInterface fun isFloatingBubbleEnabled(): Boolean = this@MainActivity.isFloatingBubbleEnabled()
        @JavascriptInterface fun showFloatingBubble() { this@MainActivity.runOnUiThread { this@MainActivity.showFloatingBubble(minimizeApp = true) } }

        @JavascriptInterface fun hideNativeWeb() { this@MainActivity.webInterface.hideNativeWeb() }
        @JavascriptInterface fun showNativeWeb() { this@MainActivity.webInterface.showNativeWeb() }
        @JavascriptInterface fun setNativeWebBounds(left: Float, top: Float, width: Float, height: Float) {
            this@MainActivity.webInterface.setNativeWebBounds(left, top, width, height)
        }
        @JavascriptInterface fun loadInNativeWeb(url: String) { this@MainActivity.webInterface.loadInNativeWeb(url) }
        @JavascriptInterface fun reloadNativeWeb() { this@MainActivity.webInterface.reloadNativeWeb() }
        @JavascriptInterface fun clearWebInstant() { this@MainActivity.clearWebInstant() }

        @JavascriptInterface fun loginWithCookies(ck: String) { this@MainActivity.loginWithCookies(ck) }
        @JavascriptInterface fun setCookies(ck: String) { this@MainActivity.loginWithCookies(ck) }
        @JavascriptInterface fun applyDarkMode(on: Boolean) { this@MainActivity.webInterface.applyDarkMode(on) }
        @JavascriptInterface fun isDarkMode(): Boolean = this@MainActivity.darkModeEnabled

        @JavascriptInterface fun get2FAKey(): String = this@MainActivity.current2FAKey
        @JavascriptInterface fun set2FAKey(key: String) {
            this@MainActivity.current2FAKey = key.trim()
            this@MainActivity.saveCurrentMail()
        }
        @JavascriptInterface fun generate2FACodeFromKey(): String = this@MainActivity.generate2FACodeFromKey()
        @JavascriptInterface fun generate2FACodeFromKeyInput(rawKey: String): String =
            this@MainActivity.generate2FACodeFromKeyInput(rawKey)
        @JavascriptInterface fun startTwoFactorAutomation() {
            this@MainActivity.runOnUiThread { this@MainActivity.startTwoFactorAutomation() }
        }
        @JavascriptInterface fun stopTwoFactorAutomationPublic() {}
        @JavascriptInterface fun stopDongvanOtpPoll() {}

        @JavascriptInterface fun setBrowserProxy(ip: String, port: String, user: String, pass: String) {
            this@MainActivity.runOnUiThread { this@MainActivity.setBrowserProxy(ip, port, user, pass) }
        }
        @JavascriptInterface fun testProxy(ip: String, port: String, user: String, pass: String) {
            this@MainActivity.runProxyTestAsync(ip, port, user, pass)
        }
        @JavascriptInterface fun togglePage1Proxy(enabled: Boolean) {
            this@MainActivity.runOnUiThread { this@MainActivity.togglePage1Proxy(enabled) }
        }
        @JavascriptInterface fun togglePage2Proxy(enabled: Boolean) {
            this@MainActivity.runOnUiThread { this@MainActivity.togglePage2Proxy(enabled) }
        }
        @JavascriptInterface fun setProxy(enable: Boolean) {
            this@MainActivity.runOnUiThread { this@MainActivity.togglePage1Proxy(enable) }
        }

        @JavascriptInterface fun startIgAutomation(phone: String, pass: String) {
            this@MainActivity.startIgAutomation(phone, pass)
        }
        @JavascriptInterface fun startIgAutomationWith2FA(phone: String, pass: String, twofa: String) {
            this@MainActivity.current2FAKey = twofa
            if (pass.isNotBlank()) {
                this@MainActivity.currentMailPassword = pass
                this@MainActivity.savePassword(pass)
            }
            this@MainActivity.saveCurrentMail()
            this@MainActivity.startIgAutomation(phone, pass)
        }
        @JavascriptInterface fun loginWithAccount(json: String): Boolean {
            return try {
                val o = JSONObject(json)
                val ck = o.optString("cookies").trim()
                val userOrUid = o.optString("username").ifBlank { o.optString("uid") }.trim()
                val pwd = o.optString("pass").ifBlank { o.optString("password") }.trim()
                val tfa = o.optString("twofa").trim()
                if (userOrUid.isNotBlank()) {
                    this@MainActivity.lastGeneratedUsername = userOrUid
                    AutomationRuntimeState.update(this@MainActivity) { it.copy(lastUsername = userOrUid) }
                }
                if (pwd.isNotBlank()) {
                    this@MainActivity.currentMailPassword = pwd
                    this@MainActivity.savePassword(pwd)
                }
                if (tfa.isNotBlank()) {
                    this@MainActivity.current2FAKey = tfa
                }
                this@MainActivity.saveCurrentMail()

                if (ck.contains("sessionid=")) {
                    this@MainActivity.loginWithCookies(ck)
                    true
                } else if (userOrUid.isNotBlank() && pwd.isNotBlank()) {
                    this@MainActivity.runOnUiThread {
                        this@MainActivity.prepareNativeWebForAutomation()
                        this@MainActivity.quickLoginInstagramFromOverlay(userOrUid, pwd)
                        if (tfa.isNotBlank()) {
                            this@MainActivity.mainHandler.postDelayed({
                                if (!this@MainActivity.isFinishing && !this@MainActivity.isDestroyed) {
                                    this@MainActivity.submitTwoFactorFromOverlay(tfa)
                                }
                            }, 3200)
                        }
                    }
                    true
                } else {
                    false
                }
            } catch (_: Exception) { false }
        }

        @JavascriptInterface fun toggleMetaMode(enabled: Boolean) {
            this@MainActivity.isMetaModeEnabled = enabled
            this@MainActivity.runOnUiThread {
                toast("Meta Mode: " + if (enabled) "ON" else "OFF")
                if (enabled) this@MainActivity.clearLoginFields()
            }
        }
        @JavascriptInterface fun setNameMode(mode: String) { this@MainActivity.setNameModePublic(mode) }

        @JavascriptInterface fun startCreateIgProcess() {
            this@MainActivity.runOnUiThread {
                if (this@MainActivity.isFinishing || this@MainActivity.isDestroyed) return@runOnUiThread
                try {
                    try { if (::igAutomation.isInitialized) igAutomation.forceReset() } catch (_: Exception) {}
                    this@MainActivity.prepareNativeWebForAutomation()

                    // 1) Meta pool priority when Meta mode is ON or Auto Number is OFF
                    if (this@MainActivity.isMetaModeEnabled || !this@MainActivity.autoNumberEnabled) {
                        val metaJson = this@MainActivity.getFreshMetaNative()
                        if (metaJson.trim() != "{}" && metaJson.trim().isNotEmpty()) {
                            val obj = JSONObject(metaJson)
                            val phone = obj.optString("phone", "")
                            val pass = obj.optString("password", "")
                            val twofa = obj.optString("twofa", "")
                            if (phone.isNotBlank() && pass.isNotBlank()) {
                                this@MainActivity.ensureInstagramLoginPage()
                                this@MainActivity.currentNumber = phone
                                this@MainActivity.currentMailPassword = pass
                                this@MainActivity.savePassword(pass)
                                this@MainActivity.current2FAKey = twofa
                                this@MainActivity.saveCurrentMail()
                                this@MainActivity.autoRegActive = true
                                if (::igAutomation.isInitialized) igAutomation.startAutomation(phone, pass, twofa)
                                fillInstagramLogin(phone, pass)
                                toast("▶️ Meta automation: $phone")
                                return@runOnUiThread
                            }
                        }
                    }

                    // Ensure password exists (generate clean strong default password if user hasn't saved one)
                    val activePass = this@MainActivity.savedPassword.ifBlank {
                        this@MainActivity.currentMailPassword.ifBlank {
                            "Tp@${(100000..999999).random()}Ab"
                        }
                    }
                    this@MainActivity.currentMailPassword = activePass
                    this@MainActivity.savePassword(activePass)

                    // 2) Auto Number ON -> allocate number from SMS Panel & run IG signup automation
                    if (this@MainActivity.autoNumberEnabled) {
                        if (!StexApiService.hasApiKey()) {
                            toast("⚠️ Auto & BG থেকে Panel API Key সেট করুন")
                            return@runOnUiThread
                        }
                        val rid = StexApiService.getSelectedRid().ifBlank { "23769" }
                        toast("🔢 নাম্বার নেওয়া হচ্ছে (Range: $rid)...")
                        this@MainActivity.lifecycleScope.launch {
                            val res = StexApiService.allocateNumber(rid)
                            if (res.isSuccess) {
                                val allocated = res.getOrNull()!!
                                this@MainActivity.onNumberAllocated(allocated.number, allocated.rid, allocated.country, allocated.operator)
                                this@MainActivity.runOnUiThread {
                                    if (!this@MainActivity.isFinishing && !this@MainActivity.isDestroyed) {
                                        if (::nativeWebView.isInitialized) {
                                            nativeWebView.loadUrl("https://www.instagram.com/accounts/emailsignup/")
                                        }
                                        this@MainActivity.autoRegActive = true
                                        if (::igAutomation.isInitialized) {
                                            igAutomation.startSignupAutomation(allocated.number, activePass)
                                        }
                                    }
                                }
                            } else {
                                this@MainActivity.onNumberFailed(res.exceptionOrNull()?.message ?: "Allocation failed")
                            }
                        }
                        return@runOnUiThread
                    }

                    // 3) Manual number already allocated -> run IG signup automation
                    val existingNum = this@MainActivity.currentNumber.ifBlank { this@MainActivity.allocatedNumber }.trim()
                    if (existingNum.isNotBlank()) {
                        this@MainActivity.saveCurrentMail()
                        if (::nativeWebView.isInitialized) {
                            nativeWebView.loadUrl("https://www.instagram.com/accounts/emailsignup/")
                        }
                        if (StexApiService.hasApiKey()) {
                            StexApiService.startOtpPolling(existingNum, this@MainActivity)
                        }
                        this@MainActivity.autoRegActive = true
                        if (::igAutomation.isInitialized) {
                            igAutomation.startSignupAutomation(existingNum, activePass)
                        }
                        toast("▶️ IG Signup automation: $existingNum")
                        return@runOnUiThread
                    }

                    // 4) Fallback to Meta pool if present
                    val fallbackMeta = this@MainActivity.getFreshMetaNative()
                    if (fallbackMeta.trim() != "{}" && fallbackMeta.trim().isNotEmpty()) {
                        val obj = JSONObject(fallbackMeta)
                        val phone = obj.optString("phone", "")
                        val pass = obj.optString("password", "")
                        val twofa = obj.optString("twofa", "")
                        if (phone.isNotBlank() && pass.isNotBlank()) {
                            this@MainActivity.ensureInstagramLoginPage()
                            this@MainActivity.currentNumber = phone
                            this@MainActivity.currentMailPassword = pass
                            this@MainActivity.savePassword(pass)
                            this@MainActivity.current2FAKey = twofa
                            this@MainActivity.saveCurrentMail()
                            this@MainActivity.autoRegActive = true
                            if (::igAutomation.isInitialized) igAutomation.startAutomation(phone, pass, twofa)
                            fillInstagramLogin(phone, pass)
                            toast("▶️ Meta automation: $phone")
                            return@runOnUiThread
                        }
                    }

                    toast("⚠️ Auto Num ON করুন অথবা GET NUM / Set Meta দিন")
                } catch (e: Exception) {
                    Log.e(TAG, "startCreateIgProcess error: ${e.message}")
                    toast("Automation start error")
                }
            }
        }

        @JavascriptInterface fun setActivePage(page: String) { this@MainActivity.setActivePageInternal(page) }
        @JavascriptInterface fun getActivePage(): String = this@MainActivity.getActivePageInternal()
        @JavascriptInterface fun copyCurrentCookies(): String = this@MainActivity.copyCurrentCookiesInternal()
        @JavascriptInterface fun copyCookies(): String = this@MainActivity.copyCurrentCookiesInternal()
        @JavascriptInterface fun stopAutomation() { this@MainActivity.runOnUiThread { this@MainActivity.stopAutomationFromOverlay() } }

        @JavascriptInterface fun saveHotmailPool(text: String): String {
            return try {
                if (!::hotmailPool.isInitialized) return """{"added":0,"fresh":0,"total":0}"""
                runBlocking(Dispatchers.IO) {
                    val current = hotmailPool.addAndCheckFreshOnly(text)
                    """{"added":${current.addedFresh},"fresh":${current.poolFresh},"total":${current.poolTotal}}"""
                }
            } catch (_: Exception) { """{"added":0,"fresh":0,"total":0}""" }
        }
        @JavascriptInterface fun getHotmailStats(): String =
            try { if (::hotmailPool.isInitialized) hotmailPool.getStatsJson() else "{}" } catch (_: Exception) { "{}" }
        @JavascriptInterface fun clearHotmailPool(): Boolean =
            try { if (::hotmailPool.isInitialized) hotmailPool.clearAll() else false } catch (_: Exception) { false }

        @JavascriptInterface fun getOtpHistory(): String {
            return try {
                runBlocking { repository.getOtpHistoryJson() }
            } catch (_: Exception) { "[]" }
        }
        @JavascriptInterface fun clearOtpHistory() {
            if (::repository.isInitialized) repository.clearOtpHistoryAsync()
        }

        @JavascriptInterface fun getMail() {
            try {
                if (!::hotmailPool.isInitialized) { toast("Mail pool unavailable"); return }
                val mail = hotmailPool.getInstantFreshMail()
                if (mail.isNotEmpty()) {
                    val email = mail.split("|").getOrNull(0) ?: ""
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && ::mainWebView.isInitialized) {
                            mainWebView.evaluateJavascript("window.TP&&window.TP.onEmailOnly&&window.TP.onEmailOnly('${esc(email)}')", null)
                        }
                        toast("Got Mail: $email")
                    }
                } else toast("No fresh mail found")
            } catch (_: Exception) { toast("Mail fetch failed") }
        }
    }

    inner class BrowserBridge {
        @JavascriptInterface fun open(mode: String, lang: String, ua: String) { openBrowserMode() }
        @JavascriptInterface fun close() { closeBrowserMode() }
        @JavascriptInterface fun clear() { this@MainActivity.clearWebInstant() }
        @JavascriptInterface fun reload() { reloadNativeWeb() }
        @JavascriptInterface fun setProxy(ip: String, port: String, user: String, pass: String) {
            this@MainActivity.runOnUiThread { this@MainActivity.setBrowserProxy(ip, port, user, pass) }
        }
        @JavascriptInterface fun toggleProxy(enabled: Boolean) {
            this@MainActivity.runOnUiThread { this@MainActivity.togglePage2Proxy(enabled) }
        }
        @JavascriptInterface fun getProxyIp(): String = currentProxyIp
        @JavascriptInterface fun getProxyPort(): String = currentProxyPort
        @JavascriptInterface fun getProxyUser(): String = currentProxyUser
        @JavascriptInterface fun getProxyPass(): String = currentProxyPass
        @JavascriptInterface fun isProxyEnabled(): Boolean = page2ProxyEnabled
        @JavascriptInterface fun isProxyActive(): Boolean = proxyActuallyActive
        @JavascriptInterface fun setAutoLogin(enabled: Boolean) { this@MainActivity.autoLoginEnabled = enabled }
    }

    fun setCurrentSheetMode(mode: Int) {
        val safe = if (mode < 1 || mode > 5) 1 else mode
        try { getSharedPreferences(SHEET_MODE_PREFS, MODE_PRIVATE).edit().putInt(KEY_SHEET_MODE, safe).apply() }
        catch (_: Exception) {}
    }

    fun getCurrentSheetModePublic(): Int {
        return try {
            getSharedPreferences(SHEET_MODE_PREFS, MODE_PRIVATE).getInt(KEY_SHEET_MODE, 1).let { if (it < 1 || it > 5) 1 else it }
        } catch (_: Exception) { 1 }
    }
    internal fun startSavedAccountsLiveCheck(): Boolean {
        val arr = getSheetArray()
        if (arr.length() == 0) {
            toast("কোনো সেভ করা অ্যাকাউন্ট পাওয়া যায়নি")
            return false
        }
        val targets = mutableListOf<LiveAccountTarget>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val uid = o.optString("uid").trim()
            val uname = o.optString("username").ifBlank { uid }.trim()
            val ck = o.optString("cookies").trim()
            if (uid.isNotBlank() || uname.isNotBlank()) {
                targets.add(LiveAccountTarget(uid = uid.ifBlank { uname }, username = uname, cookies = ck))
            }
        }
        if (targets.isEmpty()) {
            toast("কোনো সেভ করা অ্যাকাউন্ট পাওয়া যায়নি")
            return false
        }

        runOnUiThread { toast("📡 Live Checking ${targets.size} accounts...") }

        liveChecker.checkAccounts(targets) { results ->
            val updatedArr = getSheetArray()
            val resultByKey = results.associateBy { it.uid }
            for (i in 0 until updatedArr.length()) {
                val o = updatedArr.optJSONObject(i) ?: continue
                val key = o.optString("uid").ifBlank { o.optString("username") }.trim()
                val res = resultByKey[key] ?: continue
                o.put("live", res.live)
                o.put("liveReason", res.reason)
                if (res.resolvedUsername.isNotBlank()) {
                    o.put("username", res.resolvedUsername)
                }
                if (::repository.isInitialized) {
                    repository.updateAccountLiveAsync(o.optString("uid").ifBlank { key }, res.live)
                }
            }
            saveSheetArray(updatedArr)

            val liveCount = results.count { it.live }
            val deadCount = results.size - liveCount
            val payload = JSONObject().apply {
                put("total", results.size)
                put("live", liveCount)
                put("dead", deadCount)
                put("items", JSONArray().apply {
                    results.forEach { r ->
                        put(JSONObject().apply {
                            put("uid", r.uid)
                            put("username", r.resolvedUsername.ifBlank { r.username })
                            put("live", r.live)
                            put("reason", r.reason)
                        })
                    }
                })
            }.toString()

            runOnUiThread {
                syncSheetDataToHtml()
                if (!isFinishing && !isDestroyed && ::mainWebView.isInitialized) {
                    mainWebView.evaluateJavascript(
                        "(function(){try{if(window.TP&&typeof window.TP.onLiveCheckFinished==='function'){window.TP.onLiveCheckFinished(${quote(payload)});}}catch(e){}})();",
                        null
                    )
                }
                toast("✅ Check Done: 🟢 $liveCount Live • 🔴 $deadCount Dead")
            }
        }
        return true
    }
}

data class MetaAccount(
    val phone: String,
    var password: String,
    var twofa: String,
    var status: String,
    var addedAt: Long
)

data class LiveAccountTarget(
    val uid: String,
    val username: String,
    val cookies: String
)

data class LiveAccountResult(
    val uid: String,
    val username: String,
    val live: Boolean,
    val reason: String,
    val resolvedUsername: String = ""
)

class LiveUidChecker(private val ctx: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun destroy() {}

    fun checkAllUidsManually(uids: List<String>, callback: (Map<String, Boolean>) -> Unit) {
        val targets = uids.filter { it.isNotBlank() }.map { LiveAccountTarget(it.trim(), it.trim(), "") }
        checkAccounts(targets) { results ->
            val out = LinkedHashMap<String, Boolean>()
            results.forEach { out[it.uid] = it.live }
            callback(out)
        }
    }

    fun checkAccounts(targets: List<LiveAccountTarget>, callback: (List<LiveAccountResult>) -> Unit) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val out = mutableListOf<LiveAccountResult>()
            for (t in targets) {
                out.add(checkSingleAccount(t))
            }
            val liveCount = out.count { it.live }
            val deadCount = out.size - liveCount
            AutomationRuntimeState.logExecution(
                ctx,
                "LiveChecker",
                "INFO",
                "Checked ${out.size} accounts: $liveCount live, $deadCount dead"
            )
            withContext(Dispatchers.Main) {
                callback(out)
            }
        }
    }

    private fun checkSingleAccount(target: LiveAccountTarget): LiveAccountResult {
        val ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        var resolvedUser = ""
        val cookieHeader = target.cookies.trim()

        // Step 1: Verify logged-in cookie session directly against Instagram API when sessionid exists
        if (cookieHeader.contains("sessionid=")) {
            try {
                val req = Request.Builder()
                    .url("https://i.instagram.com/api/v1/accounts/current_user/?edit=true")
                    .header("Cookie", cookieHeader)
                    .header("User-Agent", ua)
                    .header("X-IG-App-ID", "936619743392459")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.code == 200 && body.contains("\"user\"")) {
                        val parsedUser = try {
                            JSONObject(body).optJSONObject("user")?.optString("username").orEmpty()
                        } catch (_: Exception) { "" }
                        return LiveAccountResult(
                            uid = target.uid,
                            username = target.username,
                            live = true,
                            reason = "Active Session",
                            resolvedUsername = parsedUser
                        )
                    }
                    val low = body.lowercase(Locale.US)
                    if (low.contains("challenge_required") || low.contains("checkpoint_required")) {
                        return LiveAccountResult(target.uid, target.username, false, "Checkpoint / Suspended")
                    }
                    if (low.contains("disabled") || low.contains("user_not_found")) {
                        return LiveAccountResult(target.uid, target.username, false, "Disabled / Dead")
                    }
                }
            } catch (_: Exception) {}
        }

        // Step 2: Determine handle or numeric ds_user_id for profile lookup
        var handle = target.username.trim().ifBlank { target.uid.trim() }.removePrefix("@")
        val numericIdFromCookie = Regex("ds_user_id=(\\d+)").find(cookieHeader)?.groupValues?.getOrNull(1).orEmpty()
        val isNumericHandle = handle.all { it.isDigit() } && handle.isNotEmpty()
        val numericId = if (isNumericHandle) handle else numericIdFromCookie

        if (numericId.isNotBlank() && isNumericHandle) {
            try {
                val req = Request.Builder()
                    .url("https://i.instagram.com/api/v1/users/$numericId/info/")
                    .apply { if (cookieHeader.isNotBlank()) header("Cookie", cookieHeader) }
                    .header("User-Agent", ua)
                    .header("X-IG-App-ID", "936619743392459")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.code == 200 && body.contains("\"user\"")) {
                        val uObj = try { JSONObject(body).optJSONObject("user") } catch (_: Exception) { null }
                        val uname = uObj?.optString("username").orEmpty()
                        if (uname.isNotBlank()) resolvedUser = uname
                        return LiveAccountResult(target.uid, target.username, true, "Profile Live", resolvedUser)
                    } else if (resp.code == 404 || body.contains("User not found", ignoreCase = true)) {
                        return LiveAccountResult(target.uid, target.username, false, "Account Not Found")
                    }
                }
            } catch (_: Exception) {}
        }

        if (handle.isNotBlank() && !isNumericHandle) {
            // Step 3: Public web_profile_info API check
            try {
                val req = Request.Builder()
                    .url("https://www.instagram.com/api/v1/users/web_profile_info/?username=$handle")
                    .header("User-Agent", ua)
                    .header("X-IG-App-ID", "936619743392459")
                    .apply { if (cookieHeader.isNotBlank()) header("Cookie", cookieHeader) }
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.code == 200 && body.contains("\"id\"")) {
                        return LiveAccountResult(target.uid, target.username, true, "Profile Live", handle)
                    } else if (resp.code == 404 || body.contains("User not found", ignoreCase = true)) {
                        return LiveAccountResult(target.uid, target.username, false, "Banned / Not Found")
                    }
                }
            } catch (_: Exception) {}

            // Step 4: Profile HTML verification (distinguishes real profile vs login wall vs 404 page)
            try {
                val req = Request.Builder()
                    .url("https://www.instagram.com/$handle/")
                    .header("User-Agent", ua)
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    val html = resp.body?.string().orEmpty()
                    if (resp.code == 404 ||
                        html.contains("Sorry, this page isn&#39;t available", ignoreCase = true) ||
                        html.contains("Sorry, this page isn't available", ignoreCase = true) ||
                        html.contains("Page Not Found", ignoreCase = true)
                    ) {
                        return LiveAccountResult(target.uid, target.username, false, "Dead / Unavailable")
                    }
                    if (resp.code == 200 && (
                            html.contains("profilePage_") ||
                            html.contains("\"username\":\"$handle\"", ignoreCase = true) ||
                            (html.contains("og:title") && html.contains(handle, ignoreCase = true))
                        )
                    ) {
                        return LiveAccountResult(target.uid, target.username, true, "Profile Live", handle)
                    }
                }
            } catch (_: Exception) {}
        }

        return LiveAccountResult(
            uid = target.uid,
            username = target.username,
            live = false,
            reason = if (cookieHeader.contains("sessionid=")) "Session Expired / Dead" else "Unreachable / Dead",
            resolvedUsername = resolvedUser
        )
    }
}
