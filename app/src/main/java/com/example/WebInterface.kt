package com.example

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.example.data.TrustPointRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Primary JS bridge exposed as `window.Android` on mainWebView.
 */
class WebInterface(
    private val activity: MainActivity,
    private val mainWebView: WebView
) {
    @JavascriptInterface
    fun copyToClipboard(text: String, msg: String? = "Copied") {
        activity.copyToClipboard(text, msg ?: "Copied")
    }

    @JavascriptInterface
    fun getClipboardText(): String {
        return try {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
            val clip = cm.primaryClip ?: return ""
            if (clip.itemCount > 0) clip.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty() else ""
        } catch (_: Exception) {
            ""
        }
    }

    @JavascriptInterface
    fun readClipboard(): String = getClipboardText()

    @JavascriptInterface
    fun openExternalUrl(url: String) {
        activity.runOnUiThread {
            try {
                if (url.isNotBlank()) {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            } catch (_: Exception) {
                activity.toast("Cannot open URL")
            }
        }
    }

    @JavascriptInterface
    fun loadInNativeWeb(url: String) {
        activity.runOnUiThread { activity.loadInNativeWeb(url) }
    }

    @JavascriptInterface
    fun reloadNativeWeb() {
        activity.runOnUiThread { activity.reloadNativeWeb() }
    }

    @JavascriptInterface
    fun showNativeWeb() {
        activity.runOnUiThread { activity.showNativeWeb() }
    }

    @JavascriptInterface
    fun hideNativeWeb() {
        activity.runOnUiThread { activity.hideNativeWeb() }
    }

    @JavascriptInterface
    fun setNativeWebBounds(left: Float, top: Float, width: Float, height: Float) {
        activity.runOnUiThread { activity.setNativeWebBounds(left, top, width, height) }
    }

    @JavascriptInterface
    fun setUiOverlay(active: Boolean) {
        activity.runOnUiThread { activity.setUiOverlayActive(active) }
    }

    @JavascriptInterface
    fun setUserAgent(uaLabel: String) {
        activity.runOnUiThread {
            val uaString = when (uaLabel) {
                "Windows (Chrome)" -> activity.desktopUserAgent
                "Windows (IE 11)" -> "Mozilla/5.0 (Windows NT 10.0; Trident/7.0; rv:11.0) like Gecko"
                "macOS" -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                "iPhone" -> "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
                "iPad" -> "Mozilla/5.0 (iPad; CPU OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
                "Android (Tablet)" -> "Mozilla/5.0 (Linux; Android 14; SM-X910) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Safari/537.36"
                "Symbian" -> "Mozilla/5.0 (SymbianOS/9.4; Series60/5.0 Nokia5800d-1/60.0.003; Profile/MIDP-2.1 Configuration/CLDC-1.1) AppleWebKit/525 (KHTML, like Gecko) Version/3.0 BrowserNG/7.3.1.33"
                "Android (Phone)" -> activity.mobileUserAgent
                else -> activity.rotateUserAgent()
            }
            activity.isDesktopMode = uaLabel.contains("Windows") || uaLabel.contains("macOS")
            activity.mobileUserAgent = uaString
            try {
                if (activity.nativeWebViewOrNull() != null) {
                    activity.nativeWebView.settings.userAgentString = uaString
                    activity.nativeWebView.reload()
                }
            } catch (_: Exception) {}
        }
    }

    @JavascriptInterface
    fun applyDarkMode(enabled: Boolean) {
        activity.runOnUiThread { activity.setNativeDarkMode(enabled) }
    }

    @JavascriptInterface
    fun setAutoNumber(enabled: Boolean) {
        activity.updateAutoNumberEnabled(enabled)
        AutomationRuntimeState.logExecution(activity, "AutoNumber", "INFO", "Auto Number ${if (enabled) "ON" else "OFF"}")
    }

    @JavascriptInterface
    fun setHotmail(mail: String) {
        activity.currentMail = mail.trim()
        activity.saveCurrentMail()
    }

    @JavascriptInterface
    fun startLiveCheck(): Boolean {
        return try {
            activity.startSavedAccountsLiveCheck()
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun onUsernameGenerated(username: String) {
        activity.lastGeneratedUsername = username
        AutomationRuntimeState.update(activity) { it.copy(lastUsername = username) }
        activity.copyToClipboard(username, "Username Copied: $username")
    }

    @JavascriptInterface
    fun onPasswordGenerated(password: String) {
        if (password.isNotBlank()) {
            activity.currentMailPassword = password
            activity.savePassword(password)
            activity.saveCurrentMail()
        }
    }

    @JavascriptInterface
    fun onIgAutomationComplete() {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            try {
                activity.flushCookiesPublic()
                activity.mainHandler.postDelayed({
                    if (activity.isFinishing || activity.isDestroyed) return@postDelayed
                    val pass = activity.currentMailPassword.ifBlank { activity.savedPassword }
                    val twofa = activity.current2FAKey
                    val cookies = activity.getInstagramCookiesPublic()
                    val cookieUid = Regex("ds_user_id=(\\d+)").find(cookies)?.groupValues?.getOrNull(1).orEmpty()
                    val username = activity.lastGeneratedUsername.trim()
                        .ifBlank { cookieUid }
                        .ifBlank { activity.currentNumber.trim() }

                    if (cookies.isNotBlank()) {
                        val sCk = activity.esc(cookies)
                        mainWebView.evaluateJavascript(
                            "(function(){try{if(window.TP&&window.TP.setCookiesData)window.TP.setCookiesData('$sCk');}catch(e){}})();",
                            null
                        )
                    }
                    TelegramBotActivity.notifyAccountReadyForSubmission(cookies, twofa)
                    if (username.isNotBlank()) {
                        activity.saveToSheet(username, pass, cookies, activity.currentMail, twofa)
                        activity.toast("✅ Account Saved: $username")
                    }
                }, 800)
                activity.markIgAutomationComplete()
            } catch (_: Exception) {}
        }
    }

    // Floating & Background Process Controls
    @JavascriptInterface
    fun setFloatingBubbleEnabled(enabled: Boolean) {
        activity.setFloatingBubbleEnabled(enabled)
    }

    @JavascriptInterface
    fun isFloatingBubbleEnabled(): Boolean = activity.isFloatingBubbleEnabled()

    @JavascriptInterface
    fun showFloatingBubble() {
        activity.runOnUiThread { activity.showFloatingBubble(minimizeApp = true) }
    }

    @JavascriptInterface
    fun toggleBackgroundBubbleOverlay() {
        activity.runOnUiThread {
            val next = !activity.isFloatingBubbleEnabled()
            activity.setFloatingBubbleEnabled(next)
            if (next) {
                activity.showFloatingBubble(minimizeApp = false)
            }
        }
    }

    // Automation Start/Stop/ON-OFF + Logs + API Config
    @JavascriptInterface
    fun setAutomationMasterEnabled(enabled: Boolean) {
        AutomationRuntimeState.requestToggleAutomationEnabled(activity, enabled)
    }

    @JavascriptInterface
    fun isAutomationMasterEnabled(): Boolean = AutomationRuntimeState.state.value.igAutoEnabled

    @JavascriptInterface
    fun startAutomationControl() {
        activity.runOnUiThread { activity.startAutomationFromOverlay() }
    }

    @JavascriptInterface
    fun stopAutomationControl() {
        activity.runOnUiThread { activity.stopAutomationFromOverlay() }
    }

    @JavascriptInterface
    fun getAutomationStatusJson(): String {
        val snap = AutomationRuntimeState.state.value
        return JSONObject().apply {
            put("igAutoEnabled", snap.igAutoEnabled)
            put("igAutoRunning", snap.igAutoRunning)
            put("igStepText", snap.igStepText)
            put("otpPollingActive", snap.otpPollingActive)
            put("floatingBubbleEnabled", snap.floatingBubbleEnabled)
            put("backgroundServiceRunning", snap.backgroundServiceRunning)
            put("proxyActive", activity.proxyActuallyActive)
            put("currentNumber", snap.currentNumber)
            put("currentOtp", snap.currentOtp)
        }.toString()
    }

    @JavascriptInterface
    fun getExecutionLogsJson(): String {
        return try {
            runBlocking {
                TrustPointRepository.getInstance(activity).getExecutionLogsJson()
            }
        } catch (_: Exception) {
            "[]"
        }
    }

    @JavascriptInterface
    fun clearExecutionLogs() {
        TrustPointRepository.getInstance(activity).clearLogsAsync()
    }

    @JavascriptInterface
    fun savePanelApiConfig(baseUrl: String, apiKey: String) {
        StexApiService.saveApiConfig(baseUrl, apiKey)
        activity.runOnUiThread { activity.toast("✓ Panel API config saved") }
    }

    @JavascriptInterface
    fun getPanelApiConfigJson(): String {
        return JSONObject().apply {
            put("baseUrl", StexApiService.getBaseUrl())
            put("apiKey", StexApiService.getApiKey())
        }.toString()
    }

    @JavascriptInterface
    fun getPermissionStatusJson(): String = activity.getPermissionStatusJson()

    @JavascriptInterface
    fun requestNotificationPermission() {
        activity.runOnUiThread { activity.requestNotificationPermissionNow() }
    }

    @JavascriptInterface
    fun requestBatteryOptimization() {
        activity.runOnUiThread { activity.requestBatteryOptimizationNow() }
    }

    @JavascriptInterface
    fun requestOverlayPermission() {
        activity.runOnUiThread { activity.requestOverlayPermissionNow() }
    }

    @JavascriptInterface
    fun startBackgroundService() {
        activity.runOnUiThread { activity.startBackgroundServiceFromUserAction() }
    }

    @JavascriptInterface
    fun openTelegramLogin() {
        activity.runOnUiThread { activity.openTelegramOverlayInHtml(MainActivity.DEFAULT_TG_LOGIN_URL) }
    }

    @JavascriptInterface
    fun openTelegramBotActivity() {
        activity.runOnUiThread { TelegramBotActivity.open(activity) }
    }

    @JavascriptInterface
    fun saveSelectedPanel(panel: String) {
        StexApiService.setSelectedPanel(panel)
    }

    @JavascriptInterface
    fun getSelectedPanel(): String = StexApiService.getSelectedPanel()

    @JavascriptInterface
    fun saveHotmailPool(text: String): String {
        return try {
            runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                val res = activity.hotmailPool.addAndCheckFreshOnly(text)
                """{"added":${res.addedFresh},"fresh":${res.poolFresh},"total":${res.poolTotal}}"""
            }
        } catch (_: Exception) {
            """{"added":0,"fresh":0,"total":0}"""
        }
    }

    @JavascriptInterface
    fun getHotmailStats(): String {
        return try {
            activity.hotmailPool.getStatsJson()
        } catch (_: Exception) {
            "{}"
        }
    }

    @JavascriptInterface
    fun clearHotmailPool(): Boolean {
        return try {
            activity.hotmailPool.clearAll()
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun fetchHotmailOtp(email: String): String {
        return try {
            runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                activity.hotmailPool.fetchOtp(email).otp
            }
        } catch (_: Exception) {
            ""
        }
    }

    @JavascriptInterface
    fun notifyAutoDone() {
        onIgAutomationComplete()
    }

    @JavascriptInterface
    fun prepareBuiltInProfilePhoto(): Boolean {
        val uri = activity.prepareBuiltInAvatarUri(null) ?: return false
        activity.pendingUploadUri = uri
        activity.uploadConsumed = false
        activity.uploadSession = true
        activity.runOnUiThread { activity.toast("✓ Built-in profile photo ready") }
        return true
    }
}
