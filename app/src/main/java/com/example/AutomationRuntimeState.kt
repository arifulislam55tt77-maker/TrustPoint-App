package com.example

import android.content.Context
import com.example.data.TrustPointRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.lang.ref.WeakReference

/**
 * Shared runtime state synchronizing MainActivity, IgAutomation, StexApiService,
 * Room database, and FloatingBubbleService overlay controls.
 */
object AutomationRuntimeState {

    data class Snapshot(
        val igAutoEnabled: Boolean = true,
        val igAutoRunning: Boolean = false,
        val igStepText: String = "Idle",
        val otpPollingActive: Boolean = false,
        val floatingBubbleEnabled: Boolean = true,
        val backgroundServiceRunning: Boolean = false,
        val proxyActive: Boolean = false,
        val currentNumber: String = "",
        val currentCountry: String = "",
        val currentOtp: String = "",
        val current2FAKey: String = "",
        val lastUsername: String = "",
        val lastUpdatedAt: Long = System.currentTimeMillis()
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    @Volatile
    private var hostActivityRef: WeakReference<MainActivity>? = null

    fun attachActivity(activity: MainActivity) {
        hostActivityRef = WeakReference(activity)
    }

    fun getActivityOrNull(): MainActivity? = hostActivityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    fun update(context: Context? = null, transform: (Snapshot) -> Snapshot) {
        _state.update { current ->
            transform(current).copy(lastUpdatedAt = System.currentTimeMillis())
        }
        val snap = _state.value
        context?.let { ctx ->
            val repo = TrustPointRepository.getInstance(ctx)
            repo.saveAutomationStateAsync(
                key = "ig_automation",
                enabled = snap.igAutoEnabled,
                running = snap.igAutoRunning,
                message = snap.igStepText
            )
            repo.saveAutomationStateAsync(
                key = "floating_bubble",
                enabled = snap.floatingBubbleEnabled,
                running = snap.backgroundServiceRunning,
                message = if (snap.backgroundServiceRunning) "Background Active" else "Stopped"
            )
        }
    }

    fun logExecution(context: Context?, tag: String, level: String, message: String) {
        context?.let {
            TrustPointRepository.getInstance(it).log(tag, level, message)
        }
    }

    fun requestStartFromOverlay(context: Context) {
        val act = getActivityOrNull()
        if (act != null) {
            act.runOnUiThread {
                if (!act.isFinishing && !act.isDestroyed) {
                    act.startAutomationFromOverlay()
                }
            }
        } else {
            logExecution(context, "FloatingBubble", "WARN", "App UI not attached; opening TrustPoint to run WebView automation")
            update(context) { it.copy(igStepText = "Opening app for WebView…") }
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("extra_start_auto_on_resume", true)
            }
            if (launch != null) {
                try { context.startActivity(launch) } catch (_: Exception) {}
            }
        }
    }

    fun requestStopFromOverlay(context: Context) {
        val act = getActivityOrNull()
        if (act != null) {
            act.runOnUiThread {
                if (!act.isFinishing && !act.isDestroyed) {
                    act.stopAutomationFromOverlay()
                }
            }
        } else {
            StexApiService.stopOtpPolling()
            update(context) { it.copy(igAutoRunning = false, otpPollingActive = false, igStepText = "Stopped") }
            logExecution(context, "FloatingBubble", "INFO", "Automation stopped from overlay")
        }
    }

    fun requestToggleAutomationEnabled(context: Context, enabled: Boolean) {
        update(context) {
            it.copy(
                igAutoEnabled = enabled,
                igAutoRunning = if (!enabled) false else it.igAutoRunning,
                igStepText = if (!enabled) "Automation OFF" else "Ready (ON)"
            )
        }
        if (!enabled) {
            requestStopFromOverlay(context)
        }
        logExecution(context, "AutoControl", "INFO", "Automation master switch set to ${if (enabled) "ON" else "OFF"}")
    }

    fun requestQuickLoginFromOverlay(context: Context, username: String, passwordOverride: String = "") {
        val cleanUser = username.trim()
        if (cleanUser.isBlank()) return
        val act = getActivityOrNull()
        if (act != null) {
            act.runOnUiThread {
                if (!act.isFinishing && !act.isDestroyed) {
                    act.quickLoginInstagramFromOverlay(cleanUser, passwordOverride)
                }
            }
        } else {
            logExecution(context, "FloatingBubble", "WARN", "Host activity inactive; opening TrustPoint for quick login")
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("extra_quick_login_user", cleanUser)
                putExtra("extra_quick_login_pass", passwordOverride)
            }
            if (launch != null) {
                try { context.startActivity(launch) } catch (_: Exception) {}
            }
        }
    }

    fun requestSubmitTwoFactorFromOverlay(context: Context, twoFaInput: String) {
        val clean = twoFaInput.trim()
        if (clean.isBlank()) return
        val act = getActivityOrNull()
        if (act != null) {
            act.runOnUiThread {
                if (!act.isFinishing && !act.isDestroyed) {
                    act.submitTwoFactorFromOverlay(clean)
                }
            }
        } else {
            logExecution(context, "FloatingBubble", "WARN", "Host activity inactive during 2FA submit")
        }
    }

    fun requestCopyIgCookiesFromOverlay(context: Context): String {
        val act = getActivityOrNull()
        if (act != null) {
            return if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                act.copyCurrentCookiesInternal()
            } else {
                val ck = act.getInstagramCookiesPublic()
                act.runOnUiThread {
                    if (!act.isFinishing && !act.isDestroyed) {
                        act.copyCurrentCookiesInternal()
                    }
                }
                ck
            }
        }
        return try {
            val cm = android.webkit.CookieManager.getInstance()
            try { cm.flush() } catch (_: Exception) {}
            val allowed = setOf(
                "sessionid", "ds_user_id", "csrftoken", "mid", "ig_did",
                "rur", "datr", "ig_nrcb", "wd", "dpr", "shbid", "shbts"
            )
            val domains = listOf(
                "https://www.instagram.com/",
                "https://instagram.com/",
                "https://i.instagram.com/",
                "https://m.instagram.com/",
                "https://accountscenter.instagram.com/"
            )
            val map = LinkedHashMap<String, String>()
            for (d in domains) {
                val raw = cm.getCookie(d) ?: continue
                raw.split(";").forEach { part ->
                    val idx = part.indexOf('=')
                    if (idx > 0) {
                        val k = part.substring(0, idx).trim()
                        val v = part.substring(idx + 1).trim()
                        if (k in allowed && v.isNotEmpty() && v != "\"\"") {
                            map[k] = v
                        }
                    }
                }
            }
            val ck = map.entries.joinToString("; ") { "${it.key}=${it.value}" }
            if (ck.isNotBlank()) {
                val clipMgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clipMgr?.setPrimaryClip(android.content.ClipData.newPlainText("Instagram Cookies", ck))
            }
            ck
        } catch (_: Exception) {
            ""
        }
    }

    fun requestSaveAccountFromOverlay(context: Context): String {
        val act = getActivityOrNull()
        if (act != null) {
            return act.saveCurrentAccountFromOverlay()
        }
        return try {
            val cm = android.webkit.CookieManager.getInstance()
            try { cm.flush() } catch (_: Exception) {}
            val allowed = setOf(
                "sessionid", "ds_user_id", "csrftoken", "mid", "ig_did",
                "rur", "datr", "ig_nrcb", "wd", "dpr", "shbid", "shbts"
            )
            val domains = listOf(
                "https://www.instagram.com/",
                "https://instagram.com/",
                "https://i.instagram.com/",
                "https://m.instagram.com/",
                "https://accountscenter.instagram.com/"
            )
            val map = LinkedHashMap<String, String>()
            for (d in domains) {
                val raw = cm.getCookie(d) ?: continue
                raw.split(";").forEach { part ->
                    val idx = part.indexOf('=')
                    if (idx > 0) {
                        val k = part.substring(0, idx).trim()
                        val v = part.substring(idx + 1).trim()
                        if (k in allowed && v.isNotEmpty() && v != "\"\"") {
                            map[k] = v
                        }
                    }
                }
            }
            val cookies = map.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val cookieUid = map["ds_user_id"].orEmpty()
            val snap = _state.value
            val resolvedUser = snap.lastUsername.trim()
                .ifBlank { cookieUid }
                .ifBlank { snap.currentNumber.trim() }
            if (resolvedUser.isBlank()) return ""

            val pw = context.getSharedPreferences(MainActivity.PW_PREFS, Context.MODE_PRIVATE)
                .getString(MainActivity.KEY_PW, "").orEmpty()
            val twofa = snap.current2FAKey.trim()
            val now = System.currentTimeMillis()

            val sheetPrefs = context.getSharedPreferences(MainActivity.SHEET_STORE_PREFS, Context.MODE_PRIVATE)
            val arr = try {
                org.json.JSONArray(sheetPrefs.getString(MainActivity.KEY_SHEET_JSON, "[]") ?: "[]")
            } catch (_: Exception) {
                org.json.JSONArray()
            }
            var updated = false
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optString("uid") == resolvedUser || obj.optString("username") == resolvedUser) {
                    obj.put("username", resolvedUser)
                    if (pw.isNotBlank()) obj.put("pass", pw)
                    if (cookies.isNotBlank()) obj.put("cookies", cookies)
                    if (twofa.isNotBlank()) obj.put("twofa", twofa)
                    obj.put("live", true)
                    obj.put("savedAt", now)
                    updated = true
                    break
                }
            }
            if (!updated) {
                arr.put(org.json.JSONObject().apply {
                    put("uid", resolvedUser)
                    put("username", resolvedUser)
                    put("pass", pw)
                    put("cookies", cookies)
                    put("hotmail", "")
                    put("twofa", twofa)
                    put("number", snap.currentNumber.trim())
                    put("live", true)
                    put("savedAt", now)
                })
            }
            sheetPrefs.edit().putString("sheet_json", arr.toString()).apply()

            TrustPointRepository.getInstance(context).saveAccountAsync(
                com.example.data.SavedAccountEntity(
                    uid = resolvedUser,
                    password = pw,
                    cookies = cookies,
                    hotmail = "",
                    twofa = twofa,
                    number = snap.currentNumber.trim(),
                    isLive = true,
                    savedAt = now
                )
            )
            logExecution(context, "FloatingBubble", "SUCCESS", "Overlay Save added account to Sheet: $resolvedUser")
            resolvedUser
        } catch (_: Exception) {
            ""
        }
    }

    fun requestClearWebFromOverlay(context: Context) {
        val act = getActivityOrNull()
        if (act != null) {
            act.runOnUiThread {
                if (!act.isFinishing && !act.isDestroyed) {
                    act.clearWebFromOverlay()
                }
            }
            return
        }
        try {
            val cm = android.webkit.CookieManager.getInstance()
            val igUrls = listOf(
                "https://www.instagram.com",
                "https://instagram.com",
                "https://i.instagram.com",
                "https://m.instagram.com",
                "https://accountscenter.instagram.com"
            )
            igUrls.forEach { url ->
                val raw = cm.getCookie(url) ?: return@forEach
                raw.split(";").forEach { part ->
                    val name = part.substringBefore("=").trim()
                    if (name.isNotEmpty()) {
                        cm.setCookie(url, "$name=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/")
                        cm.setCookie(url, "$name=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/; domain=.instagram.com")
                    }
                }
            }
            cm.flush()
            update(context) {
                it.copy(
                    lastUsername = "",
                    currentOtp = "",
                    current2FAKey = "",
                    igStepText = "Web cleared (Ready)"
                )
            }
            logExecution(context, "FloatingBubble", "INFO", "Overlay Clear Web cleared IG cookies (background)")
        } catch (_: Exception) {}
    }
}
