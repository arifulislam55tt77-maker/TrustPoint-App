package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

data class AccountCredential(
    val pass: String,
    val phone: String,
    val twoFa: String = "",
    val rawLine: String = ""
)

class IgAutomation(
    private val activity: MainActivity,
    private val webView: WebView,
    private val handler: Handler
) {

    @Volatile private var autoRunning = false
    private val paused = AtomicBoolean(false)
    @Volatile private var automationStartTime = 0L
    @Volatile private var timeoutRunnable: Runnable? = null
    @Volatile private var lastExtractedUsername = ""
    @Volatile private var currentPhone = ""
    @Volatile private var currentPass = ""
    @Volatile private var currentTwoFa = ""
    @Volatile private var consecutiveNoProfileAccounts = 0
    @Volatile private var isSignupRun = false

    private val usedAccounts = mutableSetOf<String>()
    private val accountQueue = ArrayDeque<AccountCredential>()

    companion object {
        private const val TAG = "IgAutomation"

        // ফরমেট উদাহরণ: "Shakil21 +8801780536279 4ESQFBWZHEQZYVBK"
        fun parseAccountFormat(line: String): AccountCredential? {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return null

            val tokens = trimmed
                .split(Regex("[\\s|:;]+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            if (tokens.size < 2) return null

            val phoneIndex = tokens.indexOfFirst { tok ->
                val digitsOnly = tok.replace(Regex("[^0-9]"), "")
                tok.startsWith("+") || (digitsOnly.length >= 8 && tok.matches(Regex("^[+0-9()\\-\\s]+$")))
            }

            if (phoneIndex == -1) return null

            val phone = tokens[phoneIndex]
            val pass = when {
                phoneIndex > 0 -> tokens.subList(0, phoneIndex).joinToString(" ")
                tokens.size > 1 -> tokens[1]
                else -> ""
            }

            if (pass.isEmpty()) return null

            val remaining = tokens.filterIndexed { idx, _ ->
                idx != phoneIndex && !(phoneIndex == 0 && idx == 1)
            }
            val twoFa = remaining.joinToString(" ").trim()

            return AccountCredential(
                pass = pass,
                phone = phone,
                twoFa = twoFa,
                rawLine = trimmed
            )
        }

        fun parseAccountList(bulkText: String): List<AccountCredential> {
            return bulkText
                .lineSequence()
                .mapNotNull { parseAccountFormat(it) }
                .toList()
        }
    }

    init {
        try {
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.addJavascriptInterface(AutomationBridge(), "AndroidBridge")
        } catch (_: Exception) {}
    }

    fun isRunning(): Boolean = autoRunning

    fun isPaused(): Boolean = paused.get()

    fun isSignupMode(): Boolean = isSignupRun

    fun isAccountUsed(phone: String): Boolean = usedAccounts.contains(phone.trim())

    fun setAccountQueue(bulkOrLines: String, autoStartFirst: Boolean = true) {
        accountQueue.clear()
        val parsed = parseAccountList(bulkOrLines)
        for (cred in parsed) {
            if (!usedAccounts.contains(cred.phone.trim())) {
                accountQueue.addLast(cred)
            }
        }
        logDebug("Queue loaded: ${accountQueue.size} accounts")
        if (autoStartFirst && accountQueue.isNotEmpty()) {
            startNextFromQueue("queue_init")
        }
    }

    fun startAutomationFromRawLine(rawLine: String): Boolean {
        val cred = parseAccountFormat(rawLine)
        if (cred == null) {
            activity.toast("❌ ফরমেট সঠিক নয়: Password Phone [2FA] দিন")
            return false
        }
        startAutomation(cred.phone, cred.pass, cred.twoFa)
        return true
    }

    fun startNextFromQueue(reason: String = "next"): Boolean {
        while (accountQueue.isNotEmpty()) {
            val nextCred = accountQueue.removeFirst()
            if (!usedAccounts.contains(nextCred.phone.trim())) {
                forceReset()
                startAutomation(nextCred.phone, nextCred.pass, nextCred.twoFa)
                return true
            }
        }
        logDebug("No more accounts in queue ($reason)")
        return false
    }

    fun markAccountUsed(phone: String = currentPhone, reason: String = "used") {
        val clean = phone.trim()
        if (clean.isNotEmpty()) {
            usedAccounts.add(clean)
        }
        if (!isSignupRun) {
            try {
                if (activity.currentNumber == phone) {
                    activity.currentNumber = ""
                }
            } catch (_: Exception) {}
        }
        logDebug("Account marked USED ($reason): $clean")
        AutomationRuntimeState.logExecution(activity, TAG, "INFO", "Account marked used ($reason): $clean")
    }

    fun markAccountBroken(phone: String = currentPhone, reason: String = "login_failed_3_tries") {
        val clean = phone.trim()
        if (clean.isNotEmpty()) {
            usedAccounts.add(clean)
        }
        if (!isSignupRun) {
            try {
                activity.runOnUiThread {
                    try {
                        activity.markMetaBrokenNative(clean)
                    } catch (_: Exception) {}
                    try {
                        activity.markMetaAccountBroken(clean, reason)
                    } catch (_: Exception) {}
                    try {
                        if (activity.currentNumber == phone) {
                            activity.currentNumber = ""
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        logDebug("Account marked BROKEN ($reason): $clean")
        AutomationRuntimeState.logExecution(activity, TAG, "ERROR", "Account marked broken ($reason): $clean")
    }

    fun fillTwoFactorCode(code: String) {
        val cleanCode = code.trim()
        if (cleanCode.isEmpty()) return
        val js = """
            (function(){
                try {
                    var inp = document.querySelector('input[name="verificationCode"]')
                           || document.querySelector('input[name="code"]')
                           || document.querySelector('input[autocomplete="one-time-code"]')
                           || document.querySelector('input[type="tel"]')
                           || document.querySelector('input[type="number"]');
                    if (inp) {
                        var proto = Object.getPrototypeOf(inp);
                        var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                        inp.focus();
                        if (desc && desc.set) desc.set.call(inp, '${escapeJs(cleanCode)}');
                        else inp.value = '${escapeJs(cleanCode)}';
                        inp.dispatchEvent(new Event('input', { bubbles: true }));
                        inp.dispatchEvent(new Event('change', { bubbles: true }));
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        handler.postDelayed({
            try { webView.evaluateJavascript(js, null) } catch (_: Exception) {}
        }, 500)
        handler.postDelayed({
            try { webView.evaluateJavascript(js, null) } catch (_: Exception) {}
        }, 1600)
    }

    fun fillSmsOtp(otp: String) {
        val cleanOtp = otp.trim()
        if (cleanOtp.isEmpty()) return
        val js = """
            (function(){
                try {
                    var inp = document.querySelector('input[name="confirmationCode"]')
                           || document.querySelector('input[name="verificationCode"]')
                           || document.querySelector('input[name="code"]')
                           || document.querySelector('input[autocomplete="one-time-code"]')
                           || document.querySelector('input[inputmode="numeric"]')
                           || document.querySelector('input[type="tel"]')
                           || document.querySelector('input[type="number"]');
                    if (inp) {
                        var proto = Object.getPrototypeOf(inp);
                        var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                        inp.focus();
                        if (desc && desc.set) desc.set.call(inp, '${escapeJs(cleanOtp)}');
                        else inp.value = '${escapeJs(cleanOtp)}';
                        if (inp._valueTracker && typeof inp._valueTracker.setValue === 'function') {
                            inp._valueTracker.setValue('');
                        }
                        inp.dispatchEvent(new Event('input', { bubbles: true }));
                        inp.dispatchEvent(new Event('change', { bubbles: true }));
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        handler.post {
            try { webView.evaluateJavascript(js, null) } catch (_: Exception) {}
        }
        handler.postDelayed({
            try { webView.evaluateJavascript(js, null) } catch (_: Exception) {}
        }, 700)
    }

    fun pauseAutomation() {
        if (!autoRunning) return
        paused.set(true)
        try {
            webView.evaluateJavascript("window.__igAutoPaused=true;", null)
        } catch (_: Exception) {}
        AutomationRuntimeState.update(activity) { it.copy(igStepText = "Paused") }
        AutomationRuntimeState.logExecution(activity, TAG, "INFO", "IG Automation paused")
        FloatingBubbleService.refreshOverlayIfRunning(activity)
    }

    fun resumeAutomation() {
        if (!autoRunning) return
        paused.set(false)
        try {
            webView.evaluateJavascript("window.__igAutoPaused=false;", null)
        } catch (_: Exception) {}
        AutomationRuntimeState.update(activity) { it.copy(igStepText = "Running ($currentPhone)") }
        AutomationRuntimeState.logExecution(activity, TAG, "INFO", "IG Automation resumed")
        FloatingBubbleService.refreshOverlayIfRunning(activity)
    }

    fun forceReset() {
        autoRunning = false
        isSignupRun = false
        paused.set(false)
        automationStartTime = 0L
        timeoutRunnable?.let { try { handler.removeCallbacks(it) } catch (_: Exception) {} }
        timeoutRunnable = null
        try { activity.autoRegActive = false } catch (_: Exception) {}
    }

    fun markComplete() {
        markAccountUsed(currentPhone, "completed_flow")
        consecutiveNoProfileAccounts = 0
        forceReset()
        AutomationRuntimeState.update(activity) {
            it.copy(igAutoRunning = false, igStepText = "Completed ✓")
        }
        AutomationRuntimeState.logExecution(activity, TAG, "SUCCESS", "IG Automation marked complete")
        FloatingBubbleService.refreshOverlayIfRunning(activity)
    }

    fun stopAutomation(reason: String = "Stopped by user") {
        val wasRunning = autoRunning
        autoRunning = false
        paused.set(false)
        try {
            webView.evaluateJavascript(
                "(function(){try{window.__igAutoRunning=false;window.__igAutoPaused=false;if(window.__igAutoInterval)clearInterval(window.__igAutoInterval);}catch(e){}})();",
                null
            )
        } catch (_: Exception) {}
        timeoutRunnable?.let { try { handler.removeCallbacks(it) } catch (_: Exception) {} }
        timeoutRunnable = null
        try { activity.autoRegActive = false } catch (_: Exception) {}

        AutomationRuntimeState.update(activity) {
            it.copy(
                igAutoRunning = false,
                igStepText = if (it.igAutoEnabled) "Idle ($reason)" else "Automation OFF"
            )
        }
        if (wasRunning) {
            AutomationRuntimeState.logExecution(activity, TAG, "INFO", "IG Automation stopped: $reason")
        }
        FloatingBubbleService.refreshOverlayIfRunning(activity)
    }

    private fun syncCredentialsToActivity(phone: String, pass: String, twofa: String) {
        try {
            activity.runOnUiThread {
                try {
                    if (phone.isNotBlank()) activity.currentNumber = phone
                    if (pass.isNotBlank()) {
                        activity.currentMailPassword = pass
                        activity.savePassword(pass)
                    }
                    val resolved2fa = if (twofa.isNotBlank()) {
                        twofa
                    } else if (phone.isNotBlank()) {
                        activity.getMeta2FANative(phone).ifBlank { activity.current2FAKey }
                    } else {
                        activity.current2FAKey
                    }
                    if (resolved2fa.isNotBlank()) {
                        activity.current2FAKey = resolved2fa
                    }
                    activity.saveCurrentMail()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    fun startSignupAutomation(phone: String, pass: String) {
        startAutomationInternal(phone = phone, pass = pass, twofa = "", signupMode = true)
    }

    fun startAutomation(phone: String, pass: String, twofa: String = "") {
        startAutomationInternal(phone = phone, pass = pass, twofa = twofa, signupMode = false)
    }

    private fun startAutomationInternal(phone: String, pass: String, twofa: String = "", signupMode: Boolean = false) {
        try {
            if (!AutomationRuntimeState.state.value.igAutoEnabled) {
                activity.toast("⚠️ Automation OFF — আগে ON করুন")
                AutomationRuntimeState.logExecution(activity, TAG, "WARN", "Start blocked: Master switch is OFF")
                return
            }

            val parsedLine = if (pass.isBlank() && phone.trim().contains(" ")) {
                parseAccountFormat(phone)
            } else null

            val resolvedPhone = (parsedLine?.phone ?: phone).trim()
            val resolvedPass = (parsedLine?.pass ?: pass).trim()
            val resolvedTwoFa = (if (twofa.isNotBlank()) twofa else parsedLine?.twoFa ?: "").trim()

            if (resolvedPhone.isEmpty() || resolvedPass.isEmpty()) {
                activity.toast("❌ নাম্বার বা পাসওয়ার্ড পাওয়া যায়নি")
                AutomationRuntimeState.logExecution(activity, TAG, "ERROR", "Missing phone/username or password")
                return
            }

            if (autoRunning) {
                val elapsed = System.currentTimeMillis() - automationStartTime
                if (elapsed > 60000L) {
                    forceReset()
                } else {
                    activity.toast("⚠️ Automation running (${elapsed / 1000}s) — Stop চাপুন")
                    return
                }
            }

            if (usedAccounts.contains(resolvedPhone)) {
                activity.toast("⚠️ এই নম্বরটি ইতিমধ্যে Used হিসেবে মার্ক করা আছে: $resolvedPhone")
                AutomationRuntimeState.logExecution(activity, TAG, "WARN", "Duplicate run skipped (already used): $resolvedPhone")
                requestNextAccountFromHost("already_used")
                return
            }

            autoRunning = true
            isSignupRun = signupMode
            paused.set(false)
            automationStartTime = System.currentTimeMillis()
            currentPhone = resolvedPhone
            currentPass = resolvedPass
            currentTwoFa = resolvedTwoFa
            activity.autoRegActive = true
            activity.lastGeneratedUsername = ""
            lastExtractedUsername = ""

            syncCredentialsToActivity(resolvedPhone, resolvedPass, resolvedTwoFa)

            AutomationRuntimeState.update(activity) {
                it.copy(
                    igAutoRunning = true,
                    igStepText = if (signupMode) "Signup ($resolvedPhone)" else "Running ($resolvedPhone)",
                    currentNumber = resolvedPhone,
                    current2FAKey = resolvedTwoFa.ifBlank { it.current2FAKey }
                )
            }
            AutomationRuntimeState.logExecution(
                activity,
                TAG,
                "INFO",
                if (signupMode) "Started IG Signup Automation for $resolvedPhone" else "Started IG Automation for $resolvedPhone"
            )
            FloatingBubbleService.refreshOverlayIfRunning(activity)

            try {
                activity.runOnUiThread {
                    try {
                        if (!activity.uiOverlayActive && !activity.telegramPopupLocked) {
                            activity.nativeWebView.visibility = View.VISIBLE
                            activity.nativeWebSuppressed = false
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}

            val runId = System.currentTimeMillis().toString()
            val namesArray = generateRandomNames()
            val preferredName = if (activity.useCustomData && activity.customFirstName.isNotBlank()) {
                val full = listOf(activity.customFirstName.trim(), activity.customLastName.trim())
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                escapeJs(full)
            } else {
                ""
            }
            val js = buildAutomationJs(
                escapeJs(resolvedPhone),
                escapeJs(resolvedPass),
                namesArray,
                runId,
                preferredName,
                signupMode
            )

            activity.toast(if (signupMode) "▶️ Starting IG Signup: $resolvedPhone" else "▶️ Starting IG Automation: $resolvedPhone")

            val injectDelays = longArrayOf(
                0, 120, 350, 700, 1200, 2000, 3200, 5000, 8000, 12000, 18000, 26000
            )
            injectDelays.forEach { delay ->
                handler.postDelayed({
                    if (autoRunning && AutomationRuntimeState.state.value.igAutoEnabled) {
                        try { webView.evaluateJavascript(js, null) } catch (_: Exception) {}
                    }
                }, delay)
            }

            timeoutRunnable?.let { try { handler.removeCallbacks(it) } catch (_: Exception) {} }
            timeoutRunnable = Runnable {
                if (autoRunning) {
                    stopAutomation("Automation timeout")
                    activity.toast("⏹️ Automation timeout.")
                }
            }
            handler.postDelayed(timeoutRunnable!!, 300000)
        } catch (e: Exception) {
            autoRunning = false
            AutomationRuntimeState.update(activity) { it.copy(igAutoRunning = false, igStepText = "Error") }
            AutomationRuntimeState.logExecution(activity, TAG, "ERROR", "Automation start error: ${e.message}")
            activity.toast("Automation error: ${e.message}")
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        if (text.isBlank()) return
        try {
            activity.runOnUiThread {
                try {
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(label, text)
                    clipboard.setPrimaryClip(clip)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun applyUsernameToHostAndUi(rawUsername: String) {
        val clean = rawUsername.trim()
        if (clean.isEmpty() || clean == lastExtractedUsername) return
        lastExtractedUsername = clean
        copyToClipboard("IG Username", clean)
        syncCredentialsToActivity(currentPhone, currentPass, currentTwoFa)

        AutomationRuntimeState.update(activity) {
            it.copy(lastUsername = clean, igStepText = "Username: $clean")
        }
        AutomationRuntimeState.logExecution(activity, TAG, "INFO", "Username captured: $clean")

        try {
            activity.runOnUiThread {
                try {
                    activity.lastGeneratedUsername = clean
                    activity.webInterface.onUsernameGenerated(clean)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun requestNextAccountFromHost(reason: String) {
        try {
            activity.runOnUiThread {
                try {
                    if (accountQueue.isNotEmpty()) {
                        handler.postDelayed({ startNextFromQueue(reason) }, 700)
                        return@runOnUiThread
                    }
                    activity.onAutomationStopped(reason)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun logDebug(msg: String) {
        try {
            Log.d(TAG, msg)
        } catch (_: Exception) {}
    }

    private inner class AutomationBridge {

        @JavascriptInterface
        fun onUsernameGenerated(username: String) {
            applyUsernameToHostAndUi(username)
        }

        @JavascriptInterface
        fun onUserIdSelected(userId: String) {
            applyUsernameToHostAndUi(userId)
        }

        @JavascriptInterface
        fun onAccountMarkedUsed(reason: String) {
            markAccountUsed(currentPhone, reason)
            consecutiveNoProfileAccounts = 0
            try {
                activity.runOnUiThread {
                    try { activity.onAccountMarkedUsed(reason) } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun onAccountMarkedBroken(reason: String) {
            markAccountBroken(currentPhone, reason)
            stopAutomation("Marked broken: $reason")
            try {
                activity.runOnUiThread {
                    activity.toast("🔴 Account marked broken ($reason): $currentPhone")
                }
            } catch (_: Exception) {}
            requestNextAccountFromHost(reason)
        }

        @JavascriptInterface
        fun onAccountSkip(reason: String, isNoProfile: Boolean) {
            markAccountUsed(currentPhone, "skip_$reason")
            if (isNoProfile) {
                consecutiveNoProfileAccounts += 1
            } else {
                consecutiveNoProfileAccounts = 0
            }
            stopAutomation("Skipped: $reason")
            try {
                activity.runOnUiThread {
                    activity.toast("⚠️ অ্যাকাউন্ট স্কিপ ($reason) — পরবর্তী অ্যাকাউন্ট নেওয়া হচ্ছে")
                }
            } catch (_: Exception) {}
            requestNextAccountFromHost(reason)
        }

        @JavascriptInterface
        fun onAutomationStopped(reason: String) {
            stopAutomation(reason)
            try {
                activity.runOnUiThread {
                    try { activity.onAutomationStopped(reason) } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun onIgAutomationComplete() {
            syncCredentialsToActivity(currentPhone, currentPass, currentTwoFa)
            try {
                activity.runOnUiThread {
                    try { activity.onIgAutomationComplete() } catch (_: Exception) {}
                    if (accountQueue.isNotEmpty()) {
                        handler.postDelayed({ startNextFromQueue("completed") }, 800)
                    }
                }
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun notifyAutoDone() {
            syncCredentialsToActivity(currentPhone, currentPass, currentTwoFa)
            try {
                activity.runOnUiThread {
                    try { activity.onIgAutomationComplete() } catch (_: Exception) {}
                    if (accountQueue.isNotEmpty()) {
                        handler.postDelayed({ startNextFromQueue("done") }, 800)
                    }
                }
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun showToast(msg: String) {
            try {
                activity.runOnUiThread {
                    activity.toast(msg)
                }
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun getCurrentSmsOtp(): String {
            return AutomationRuntimeState.state.value.currentOtp.trim()
        }
    }

    private fun escapeJs(s: String): String =
        s.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")

    private fun buildAutomationJs(
        phone: String,
        pass: String,
        namesArray: String,
        runId: String,
        preferredName: String = "",
        isSignup: Boolean = false
    ): String {
        return """
(function(){
    if (window.__igAutoRunning && window.__igRunId === '$runId') return;
    if (window.__igAutoInterval) { try { clearInterval(window.__igAutoInterval); } catch(e) {} }
    window.__igAutoRunning = true;
    window.__igAutoPaused = false;
    window.__igRunId = '$runId';

    var PHONE = '$phone';
    var PASS = '$pass';
    var PREFERRED_NAME = '$preferredName';
    var NAMES = $namesArray;
    var RUN_ID = '$runId';
    var IS_SIGNUP = ${if (isSignup) "true" else "false"};
    var MAX_TRIES = 3;

    var lastActionAt = 0;
    var lastUsernameSent = '';
    var cantFindTries = 0;
    var noProfileTries = 0;
    var twoFaTries = 0;
    var loginFailCount = 0;
    var loginClickedOnce = false;
    var lastLoginClickAt = 0;
    var profileCardClicked = false;
    var accountUsedMarked = false;
    var finishedRun = false;

    var selectedNameForRun = PREFERRED_NAME || '';
    var nameFilledAt = 0;
    var nameClickedDone = false;
    var signupPhoneSubmitted = false;
    var signupOtpFilled = '';
    var signupPassSubmitted = false;
    var signupBirthdaySubmitted = false;
    var signupRedirectTried = false;
    var usernameCopiedDone = false;
    var usernameNextClicked = false;
    var allowCreateClicked = false;
    var agreeClickedDone = false;
    var postAgreeReached = false;
    var clickedSkipSignature = '';
    var lastPageSnapshot = '';
    var pageQuietSince = 0;

    function logEvent(msg) { try { console.log('[IG-AUTO ' + RUN_ID + '] ' + msg); } catch(e) {} }

    function callBridge(fnName, arg1, arg2) {
        try {
            if (window.AndroidBridge && typeof window.AndroidBridge[fnName] === 'function') {
                if (typeof arg2 !== 'undefined') window.AndroidBridge[fnName](arg1, arg2);
                else if (typeof arg1 !== 'undefined') window.AndroidBridge[fnName](arg1);
                else window.AndroidBridge[fnName]();
            }
        } catch (e) {}
        try {
            if (window.Android && typeof window.Android[fnName] === 'function') {
                if (typeof arg2 !== 'undefined') window.Android[fnName](arg1, arg2);
                else if (typeof arg1 !== 'undefined') window.Android[fnName](arg1);
                else window.Android[fnName]();
            }
        } catch (e) {}
    }

    function isVisible(el) {
        if (!el) return false;
        try {
            var r = el.getBoundingClientRect();
            if (!r || r.width <= 0 || r.height <= 0) return false;
            var style = window.getComputedStyle(el);
            if (!style || style.display === 'none' || style.visibility === 'hidden' || parseFloat(style.opacity) === 0) return false;
            return true;
        } catch (e) { return false; }
    }

    function setNativeValue(el, value) {
        try {
            el.focus();
            var proto = Object.getPrototypeOf(el);
            var descriptor = Object.getOwnPropertyDescriptor(proto, 'value');
            if (descriptor && descriptor.set) { descriptor.set.call(el, value); } else { el.value = value; }

            el.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true }));
            el.dispatchEvent(new KeyboardEvent('keypress', { bubbles: true }));
            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true }));
            el.dispatchEvent(new Event('change', { bubbles: true }));
            el.dispatchEvent(new Event('blur', { bubbles: true }));
            el.dispatchEvent(new Event('input', { bubbles: true }));
        } catch(e) {
            el.value = value;
            el.dispatchEvent(new Event('input', { bubbles: true }));
        }
    }

    function firePointerClickAt(targetEl, x, y) {
        if (!targetEl) return false;
        try {
            var topEl = document.elementFromPoint(x, y) || targetEl;
            var events = ['pointerover', 'mouseover', 'pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'];
            for (var i = 0; i < events.length; i++) {
                var evName = events[i];
                var ev = (evName.indexOf('pointer') === 0 && typeof PointerEvent !== 'undefined')
                    ? new PointerEvent(evName, { view: window, bubbles: true, cancelable: true, clientX: x, clientY: y, isPrimary: true })
                    : new MouseEvent(evName, { view: window, bubbles: true, cancelable: true, clientX: x, clientY: y });
                topEl.dispatchEvent(ev);
                if (topEl !== targetEl) targetEl.dispatchEvent(ev);
            }
            return true;
        } catch (e) {
            try { targetEl.click(); return true; } catch (_) { return false; }
        }
    }

    function forceClick(el) {
        if (!el) return false;
        try {
            el.scrollIntoView({ block: 'center', inline: 'nearest' });
            var rect = el.getBoundingClientRect();
            if (!rect || rect.width <= 0 || rect.height <= 0) { el.click(); return true; }
            var x = rect.left + rect.width / 2;
            var y = rect.top + rect.height / 2;
            return firePointerClickAt(el, x, y);
        } catch (e) { return false; }
    }

    function clickMarkedProfileArea(cardEl) {
        try {
            var targetContainer = cardEl;
            if (!targetContainer) {
                var cleanPhone = PHONE.replace(/\s+/g, '');
                var allEls = document.querySelectorAll('div, a, li');
                for (var j = 0; j < allEls.length; j++) {
                    var el = allEls[j];
                    var r = el.getBoundingClientRect();
                    if (!r || r.width < 160 || r.height < 36 || r.height > 220) continue;
                    var elTxt = (el.innerText || '').trim();
                    var hasPhone = elTxt.indexOf(PHONE) !== -1 || (cleanPhone && elTxt.indexOf(cleanPhone) !== -1);
                    if (hasPhone && elTxt.length < 240 && elTxt.indexOf('No Instagram profile found') === -1) {
                        targetContainer = el;
                        break;
                    }
                }
            }

            if (targetContainer) {
                targetContainer.scrollIntoView({ block: 'center', inline: 'nearest' });
                var rect = targetContainer.getBoundingClientRect();
                if (rect && rect.width > 0 && rect.height > 0) {
                    var x = rect.right - Math.min(36, Math.max(22, rect.width * 0.12));
                    var y = rect.top + (rect.height / 2);
                    return firePointerClickAt(targetContainer, x, y);
                }
            }

            var w = window.innerWidth || document.documentElement.clientWidth || 1080;
            var h = window.innerHeight || document.documentElement.clientHeight || 1920;
            return firePointerClickAt(document.body, w - 48, Math.floor(h * 0.78));
        } catch (e) { return false; }
    }

    function bodyText() {
        try { return document.body ? (document.body.innerText || '') : ''; } catch (e) { return ''; }
    }

    function findInput(name) {
        try { return document.querySelector('input[name="' + name + '"]'); } catch (e) { return null; }
    }

    function findNameInput(txt) {
        try {
            var byName = document.querySelector('input[name="full_name"], input[name="name"]');
            if (byName && isVisible(byName)) return byName;

            var inputs = document.querySelectorAll('input');
            for (var i = 0; i < inputs.length; i++) {
                var inp = inputs[i];
                if (!isVisible(inp)) continue;
                var type = (inp.getAttribute('type') || 'text').toLowerCase();
                if (type !== 'text' && type !== '') continue;
                var ph = (inp.getAttribute('placeholder') || '').toLowerCase();
                var al = (inp.getAttribute('aria-label') || '').toLowerCase();
                var nm = (inp.getAttribute('name') || '').toLowerCase();
                if (ph.indexOf('name') !== -1 || al.indexOf('name') !== -1 || nm.indexOf('name') !== -1) {
                    return inp;
                }
            }

            if (txt.indexOf("What's your name") !== -1 || txt.indexOf("What is your name") !== -1) {
                for (var j = 0; j < inputs.length; j++) {
                    var cand = inputs[j];
                    if (isVisible(cand)) {
                        var t = (cand.getAttribute('type') || 'text').toLowerCase();
                        if (t === 'text' || t === '') return cand;
                    }
                }
            }
        } catch (e) {}
        return null;
    }

    function findUsernameValue(lowTxt, hasNameInput) {
        try {
            if (hasNameInput || lowTxt.indexOf("what's your name") !== -1 || lowTxt.indexOf("what is your name") !== -1) {
                return '';
            }
            var uInp = document.querySelector('input[name="username"]');
            if (uInp && isVisible(uInp) && uInp.value && uInp.value.trim().length > 0) {
                return uInp.value.trim();
            }
            var inputs = document.querySelectorAll('input');
            for (var i = 0; i < inputs.length; i++) {
                var inp = inputs[i];
                if (!isVisible(inp)) continue;
                var nm = (inp.getAttribute('name') || '').toLowerCase();
                var ph = (inp.getAttribute('placeholder') || '').toLowerCase();
                if (nm.indexOf('name') !== -1 || ph.indexOf('name') !== -1) continue;
                var val = (inp.value || '').trim();
                if (/^[a-zA-Z0-9._]{3,30}$/.test(val)) return val;
            }
            var els = document.querySelectorAll('div, span, p, h1, h2');
            for (var j = 0; j < els.length; j++) {
                var t = (els[j].innerText || '').trim();
                if (/^[a-z0-9._]{4,30}$/.test(t) && t.indexOf('username') === -1 && t.indexOf('instagram') === -1) {
                    return t;
                }
            }
        } catch (e) {}
        return '';
    }

    function copyTextInWebView(str) {
        if (!str) return;
        try {
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(str).catch(function(){});
            }
        } catch (e) {}
        try {
            var ta = document.createElement('textarea');
            ta.value = str;
            ta.style.position = 'fixed';
            ta.style.opacity = '0';
            document.body.appendChild(ta);
            ta.focus();
            ta.select();
            document.execCommand('copy');
            document.body.removeChild(ta);
        } catch (e) {}
    }

    function clickBtnByText(txts) {
        if (typeof txts === 'string') txts = [txts];
        try {
            var els = document.querySelectorAll('button, div[role="button"], span[role="button"], a');
            for (var t = 0; t < txts.length; t++) {
                var wanted = String(txts[t]).toLowerCase().trim();
                for (var i = 0; i < els.length; i++) {
                    var el = els[i];
                    if (!isVisible(el)) continue;
                    if (el.disabled || el.getAttribute('aria-disabled') === 'true') continue;
                    var txt = (el.innerText || el.textContent || el.value || '').trim().toLowerCase();
                    if (txt === wanted || txt.indexOf(wanted) === 0) {
                        return forceClick(el);
                    }
                }
            }
        } catch (e) {}
        return false;
    }

    function clickAllowAndCreateBtn() {
        if (clickBtnByText(['Allow and create', 'allow and create', 'Finish creating', 'Finish'])) {
            return true;
        }
        try {
            var els = document.querySelectorAll('button, div[role="button"], span[role="button"], a, div');
            for (var i = 0; i < els.length; i++) {
                var el = els[i];
                if (!isVisible(el)) continue;
                var r = el.getBoundingClientRect();
                if (!r || r.width < 120 || r.height < 28) continue;
                var t = (el.innerText || el.textContent || '').trim().toLowerCase();
                if (t === 'allow and create' || t.indexOf('allow and create') !== -1) {
                    var target = el.closest('button, div[role="button"], a') || el;
                    return forceClick(target);
                }
            }
        } catch (e) {}
        return false;
    }

    function clickAgreeBtn() {
        if (clickBtnByText(['I agree', 'i agree', 'Agree', 'agree'])) {
            return true;
        }
        try {
            var els = document.querySelectorAll('button, div[role="button"], span[role="button"], a, div');
            for (var i = 0; i < els.length; i++) {
                var el = els[i];
                if (!isVisible(el)) continue;
                var r = el.getBoundingClientRect();
                if (!r || r.width < 100 || r.height < 26) continue;
                var t = (el.innerText || el.textContent || '').trim().toLowerCase();
                if (t === 'i agree' || t === 'agree') {
                    var target = el.closest('button, div[role="button"], a') || el;
                    return forceClick(target);
                }
            }
        } catch (e) {}
        return false;
    }

    function clickSkipOnceOnScreen(pageSig) {
        if (!pageSig || clickedSkipSignature === pageSig) return false;
        try {
            var els = document.querySelectorAll('button, div[role="button"], span[role="button"], a, span, div');
            for (var i = 0; i < els.length; i++) {
                var el = els[i];
                if (!isVisible(el)) continue;
                if (el.disabled || el.getAttribute('aria-disabled') === 'true') continue;
                var t = (el.innerText || el.textContent || '').trim().toLowerCase();
                if (t === 'skip') {
                    var target = el.closest('button, div[role="button"], span[role="button"], a') || el;
                    var ok = forceClick(target);
                    if (ok) {
                        clickedSkipSignature = pageSig;
                        return true;
                    }
                }
            }
        } catch (e) {}
        return false;
    }

    function clickSubmitDirectly() {
        try {
            var btn = document.querySelector('button[type="submit"]') || document.querySelector('input[type="submit"]');
            if (btn && isVisible(btn) && !btn.disabled && btn.getAttribute('aria-disabled') !== 'true') {
                return forceClick(btn);
            }
            var buttons = document.querySelectorAll('button, div[role="button"]');
            for (var i = 0; i < buttons.length; i++) {
                var b = buttons[i];
                if (!isVisible(b) || b.disabled || b.getAttribute('aria-disabled') === 'true') continue;
                var bTxt = (b.innerText || b.textContent || '').trim().toLowerCase();
                if (bTxt === 'continue' || bTxt === 'next' || bTxt === 'log in' || bTxt === 'login') {
                    return forceClick(b);
                }
            }
        } catch (e) {}
        return false;
    }

    function find2FAInput() {
        try {
            return document.querySelector(
                'input[name="verificationCode"], input[name="code"], input[name="security_code"], input[autocomplete="one-time-code"], input[placeholder*="Code"], input[aria-label*="Code"]'
            );
        } catch (e) { return null; }
    }

    function isAuthAppScreen(lowTxt) {
        if (!lowTxt) return false;
        if (
            lowTxt.indexOf('go to your authentication app') !== -1 ||
            lowTxt.indexOf('authentication app') !== -1 ||
            lowTxt.indexOf('two-factor authentication') !== -1 ||
            lowTxt.indexOf('6-digit code') !== -1 ||
            lowTxt.indexOf('try another way') !== -1
        ) {
            return true;
        }
        var codeInp = find2FAInput();
        if (codeInp && isVisible(codeInp) && (lowTxt.indexOf('code') !== -1 || lowTxt.indexOf('authenticate') !== -1 || lowTxt.indexOf('trust this device') !== -1)) {
            return true;
        }
        return false;
    }

    function notifyUsername(u) {
        if (!u || u === lastUsernameSent) return;
        lastUsernameSent = u;
        copyTextInWebView(u);
        callBridge('onUsernameGenerated', String(u));
        callBridge('onUserIdSelected', String(u));
    }

    function markCurrentAccountUsedOnce(reason) {
        if (accountUsedMarked) return;
        accountUsedMarked = true;
        callBridge('onAccountMarkedUsed', String(reason));
    }

    function markCurrentAccountBroken(reason) {
        if (finishedRun) return;
        finishedRun = true;
        window.__igAutoRunning = false;
        if (window.__igAutoInterval) {
            try { clearInterval(window.__igAutoInterval); } catch (e) {}
        }
        callBridge('onAccountMarkedBroken', String(reason));
    }

    function skipCurrentAccount(reason, isNoProfile) {
        if (finishedRun) return;
        finishedRun = true;
        window.__igAutoRunning = false;
        if (window.__igAutoInterval) {
            try { clearInterval(window.__igAutoInterval); } catch (e) {}
        }
        callBridge('onAccountSkip', String(reason), !!isNoProfile);
    }

    function completeRun() {
        if (finishedRun) return;
        finishedRun = true;
        markCurrentAccountUsedOnce('agree_and_post_pages_done');
        window.__igAutoRunning = false;
        if (window.__igAutoInterval) {
            try { clearInterval(window.__igAutoInterval); } catch (e) {}
        }
        callBridge('onIgAutomationComplete');
    }

    logEvent('INJECTED for ' + PHONE);

    window.__igAutoInterval = setInterval(function () {
        if (!window.__igAutoRunning || window.__igRunId !== RUN_ID || finishedRun) {
            try { clearInterval(window.__igAutoInterval); } catch (e) {}
            return;
        }
        if (window.__igAutoPaused) return;

        try {
            var txt = bodyText();
            var lowTxt = txt.toLowerCase();
            var now = Date.now();
            var u = findInput('username');
            var p = findInput('password');
            var nameInput = findNameInput(txt);

            if (IS_SIGNUP) {
                function normalizedSignupPhone() {
                    var p = (PHONE || '').trim();
                    if (!p) return '';
                    return p.charAt(0) === '+' ? p : ('+' + p);
                }

                // 1) If still on login screen, switch to IG signup
                var hasSignupField = !!document.querySelector('input[name="emailOrPhone"], input[name="confirmationCode"], input[name="fullName"]');
                if (!hasSignupField && u && p && (location.pathname || '').indexOf('emailsignup') === -1 && !signupRedirectTried) {
                    if (now - lastActionAt > 800) {
                        signupRedirectTried = true;
                        lastActionAt = now;
                        if (!clickBtnByText(['Sign up', 'Create new account'])) {
                            window.location.href = 'https://www.instagram.com/accounts/emailsignup/';
                        }
                        logEvent('Signup mode: navigating to IG signup');
                    }
                    return;
                }

                // 2) SMS / Confirmation Code screen (wait cleanly for OTP without skipping)
                var otpInp = document.querySelector('input[name="confirmationCode"], input[name="verificationCode"], input[name="code"], input[autocomplete="one-time-code"]');
                var isSmsCodePage = (otpInp && isVisible(otpInp)) ||
                    (lowTxt.indexOf('confirmation code') !== -1 || lowTxt.indexOf('enter the code') !== -1 || lowTxt.indexOf('sms') !== -1) && !p;
                if (isSmsCodePage && otpInp && isVisible(otpInp)) {
                    var smsOtp = '';
                    try {
                        if (window.AndroidBridge && typeof window.AndroidBridge.getCurrentSmsOtp === 'function') {
                            smsOtp = String(window.AndroidBridge.getCurrentSmsOtp() || '').trim();
                        }
                    } catch (e) {}
                    if (smsOtp && /^\d{4,8}$/.test(smsOtp) && signupOtpFilled !== smsOtp && now - lastActionAt > 700) {
                        setNativeValue(otpInp, smsOtp);
                        signupOtpFilled = smsOtp;
                        lastActionAt = now;
                        setTimeout(function () {
                            clickBtnByText(['Next', 'Confirm', 'Submit', 'Continue']) || clickSubmitDirectly();
                        }, 350);
                        logEvent('Signup filled SMS OTP: ' + smsOtp);
                    }
                    return;
                }

                // 3) Combined or Phone signup screen (emailOrPhone)
                var phoneOrEmailInp = document.querySelector('input[name="emailOrPhone"]');
                var fullNameInp = document.querySelector('input[name="fullName"]') || findNameInput(txt);
                if (phoneOrEmailInp && isVisible(phoneOrEmailInp)) {
                    clickBtnByText(['Phone', 'Mobile number']);
                    var sPhone = normalizedSignupPhone();
                    if (sPhone && phoneOrEmailInp.value !== sPhone) {
                        setNativeValue(phoneOrEmailInp, sPhone);
                    }
                    if (fullNameInp && isVisible(fullNameInp)) {
                        if (!selectedNameForRun && Array.isArray(NAMES) && NAMES.length > 0) {
                            selectedNameForRun = NAMES[Math.floor(Math.random() * NAMES.length)];
                        }
                        if (selectedNameForRun && fullNameInp.value !== selectedNameForRun) {
                            setNativeValue(fullNameInp, selectedNameForRun);
                        }
                    }
                    if (u && isVisible(u)) {
                        var sugBtn = document.querySelector('button[aria-label*="refresh" i], span[aria-label*="refresh" i]');
                        if (!u.value && sugBtn) forceClick(sugBtn);
                        var curU = (u.value || '').trim();
                        if (!curU && selectedNameForRun) {
                            curU = selectedNameForRun.toLowerCase().replace(/[^a-z0-9]/g, '') + Math.floor(1000 + Math.random() * 8999);
                            setNativeValue(u, curU);
                        }
                        if (curU) notifyUsername(curU);
                    }
                    if (p && isVisible(p) && p.value !== PASS) {
                        setNativeValue(p, PASS);
                    }
                    if (!signupPhoneSubmitted && now - lastActionAt > 900) {
                        if (clickBtnByText(['Next', 'Sign up', 'Continue', 'Submit']) || clickSubmitDirectly()) {
                            signupPhoneSubmitted = true;
                            lastActionAt = now;
                            logEvent('Submitted IG signup phone/form screen');
                        }
                    }
                    return;
                }

                // 4) Step-by-step Create Password screen
                var passOnly = document.querySelector('input[name="password"], input[name="newPassword"], input[type="password"]');
                if (passOnly && isVisible(passOnly) && !u && (lowTxt.indexOf('password') !== -1 || lowTxt.indexOf('create a password') !== -1)) {
                    if (passOnly.value !== PASS && now - lastActionAt > 400) {
                        setNativeValue(passOnly, PASS);
                        lastActionAt = now;
                        return;
                    }
                    if (!signupPassSubmitted && passOnly.value === PASS && now - lastActionAt > 700) {
                        if (clickBtnByText(['Next', 'Continue', 'Submit']) || clickSubmitDirectly()) {
                            signupPassSubmitted = true;
                            lastActionAt = now;
                            logEvent('Submitted IG signup password');
                        }
                    }
                    return;
                }

                // 5) Step-by-step Birthday screen
                var selects = document.querySelectorAll('select');
                if (selects && selects.length >= 3 && (lowTxt.indexOf('birthday') !== -1 || lowTxt.indexOf('date of birth') !== -1)) {
                    if (!signupBirthdaySubmitted && now - lastActionAt > 700) {
                        try {
                            var mSel = document.querySelector('select[title*="Month" i]') || selects[0];
                            var dSel = document.querySelector('select[title*="Day" i]') || selects[1];
                            var ySel = document.querySelector('select[title*="Year" i]') || selects[2];
                            if (mSel && mSel.options.length > 1) {
                                mSel.value = mSel.options[Math.min(mSel.options.length - 1, Math.floor(Math.random() * 11) + 1)].value;
                                mSel.dispatchEvent(new Event('change', { bubbles: true }));
                            }
                            if (dSel && dSel.options.length > 1) {
                                dSel.value = dSel.options[Math.min(dSel.options.length - 1, Math.floor(Math.random() * 25) + 1)].value;
                                dSel.dispatchEvent(new Event('change', { bubbles: true }));
                            }
                            if (ySel) {
                                var targetYear = String(1998 + Math.floor(Math.random() * 5));
                                for (var yi = 0; yi < ySel.options.length; yi++) {
                                    if (String(ySel.options[yi].value) === targetYear || String(ySel.options[yi].text).indexOf(targetYear) !== -1) {
                                        ySel.value = ySel.options[yi].value;
                                        break;
                                    }
                                }
                                ySel.dispatchEvent(new Event('change', { bubbles: true }));
                            }
                        } catch (e) {}
                        lastActionAt = now;
                        setTimeout(function () {
                            if (clickBtnByText(['Next', 'Continue', 'Submit']) || clickSubmitDirectly()) {
                                signupBirthdaySubmitted = true;
                                logEvent('Submitted IG signup birthday');
                            }
                        }, 450);
                    }
                    return;
                }
            }

            if (isAuthAppScreen(lowTxt)) {
                if (IS_SIGNUP) {
                    // During fresh SMS signup, never treat SMS code prompt as 2FA failure
                    return;
                }
                var live2faCode = '';
                try {
                    if (window.Android && typeof window.Android.get2FACode === 'function') {
                        live2faCode = (window.Android.get2FACode() || '').trim();
                    }
                } catch(e) {}
                var codeEl = find2FAInput();
                if (live2faCode && codeEl && isVisible(codeEl) && twoFaTries < 2) {
                    if (now - lastActionAt > 1400) {
                        setNativeValue(codeEl, live2faCode);
                        twoFaTries += 1;
                        lastActionAt = now;
                        clickBtnByText(['Confirm', 'Submit', 'Next', 'Continue']) || clickSubmitDirectly();
                        logEvent('Filled 2FA code #' + twoFaTries);
                    }
                    return;
                }
                logEvent('Detected Authentication App / 2FA screen without usable code -> skipping account ' + PHONE);
                skipCurrentAccount('authentication_app_2fa_screen', false);
                return;
            }

            if (lowTxt.indexOf('allow and create') !== -1 || lowTxt.indexOf('finish creating your instagram profile') !== -1) {
                if (!accountUsedMarked) {
                    markCurrentAccountUsedOnce('reached_allow_and_create');
                }
                if (!allowCreateClicked && now - lastActionAt > 500) {
                    var didAllowClick = clickAllowAndCreateBtn();
                    if (didAllowClick) {
                        allowCreateClicked = true;
                        lastActionAt = now;
                        logEvent('Clicked Allow and create');
                    }
                }
                return;
            }

            if (lowTxt.indexOf("agree to instagram's terms") !== -1 || lowTxt.indexOf('agree to instagram') !== -1 || lowTxt.indexOf('i agree') !== -1) {
                if (!accountUsedMarked) {
                    markCurrentAccountUsedOnce('reached_i_agree');
                }
                if (!agreeClickedDone && now - lastActionAt > 500) {
                    var didAgreeClick = clickAgreeBtn();
                    if (didAgreeClick) {
                        agreeClickedDone = true;
                        postAgreeReached = true;
                        lastActionAt = now;
                        pageQuietSince = now;
                        logEvent('Clicked I agree');
                    }
                }
                return;
            }

            if (lowTxt.indexOf("can't find account") !== -1 || lowTxt.indexOf('try again') !== -1) {
                if (now - lastActionAt > 900) {
                    if (cantFindTries < MAX_TRIES) {
                        cantFindTries += 1;
                        lastActionAt = now;
                        logEvent('Can\'t find account -> Try again #' + cantFindTries + '/' + MAX_TRIES);
                        var clickedTryAgain = clickBtnByText(['Try again', 'Try Again']);
                        if (clickedTryAgain) {
                            loginClickedOnce = false;
                        }
                    } else if (!IS_SIGNUP) {
                        logEvent('Reached ' + MAX_TRIES + ' tries on Can\'t find account -> mark used & next');
                        skipCurrentAccount('cant_find_account_3_tries', false);
                    }
                }
                return;
            }

            if (lowTxt.indexOf('no instagram profile found') !== -1) {
                var hasRateLimit = lowTxt.indexOf('try again later') !== -1 || lowTxt.indexOf('please wait') !== -1;

                if (now - lastActionAt > 950) {
                    if (!profileCardClicked && !hasRateLimit) {
                        var clickedCard = clickMarkedProfileArea(null);
                        if (clickedCard) {
                            profileCardClicked = true;
                            noProfileTries += 1;
                            lastActionAt = now;
                            logEvent('Clicked profile card #' + noProfileTries);
                        }
                    } else {
                        noProfileTries += 1;
                        lastActionAt = now;
                        if (noProfileTries >= MAX_TRIES && !IS_SIGNUP) {
                            logEvent('No profile / try again later reached ' + MAX_TRIES + ' tries -> next account');
                            skipCurrentAccount('no_profile_or_try_again_later_3_tries', true);
                        } else if (!hasRateLimit) {
                            clickMarkedProfileArea(null);
                        }
                    }
                }
                return;
            }

            if (lowTxt.indexOf('try again later') !== -1) {
                if (now - lastActionAt > 1100) {
                    noProfileTries += 1;
                    lastActionAt = now;
                    if (noProfileTries >= MAX_TRIES && !IS_SIGNUP) {
                        skipCurrentAccount('try_again_later_3_tries', true);
                    }
                }
                return;
            }

            var isNamePage = nameInput && (
                txt.indexOf("What's your name") !== -1 ||
                txt.indexOf("What is your name") !== -1 ||
                txt.indexOf('Enter your full name') !== -1 ||
                txt.indexOf('Add your name') !== -1
            );

            if (isNamePage) {
                if (!selectedNameForRun && Array.isArray(NAMES) && NAMES.length > 0) {
                    selectedNameForRun = NAMES[Math.floor(Math.random() * NAMES.length)];
                }

                if (selectedNameForRun && nameInput.value !== selectedNameForRun && now - lastActionAt > 250) {
                    setNativeValue(nameInput, selectedNameForRun);
                    lastActionAt = now;
                    nameFilledAt = now;
                    logEvent('Set name without copying: ' + selectedNameForRun);
                    return;
                }

                if (!nameClickedDone && nameInput.value && nameInput.value.trim().length > 0) {
                    if (nameFilledAt === 0) nameFilledAt = now;
                    if (now - nameFilledAt >= 500 && now - lastActionAt > 800) {
                        if (clickBtnByText(['Next', 'Continue', 'Submit']) || clickSubmitDirectly()) {
                            nameClickedDone = true;
                            lastActionAt = now;
                            logEvent('Clicked Next on Name page');
                        }
                    }
                }
                return;
            }

            if (lowTxt.indexOf('create a username') !== -1 || (u && !p && lowTxt.indexOf('username') !== -1)) {
                var detectedUser = findUsernameValue(lowTxt, !!nameInput);
                if (detectedUser && !usernameCopiedDone) {
                    notifyUsername(detectedUser);
                    usernameCopiedDone = true;
                    lastActionAt = now;
                    logEvent('Copied & set username: ' + detectedUser);
                    return;
                }

                if (!usernameNextClicked && now - lastActionAt > 750) {
                    if (clickBtnByText(['Next', 'Continue', 'Sign up']) || clickSubmitDirectly()) {
                        usernameNextClicked = true;
                        lastActionAt = now;
                        logEvent('Clicked Next on Username page');
                    }
                }
                return;
            }

            var isLoginPage = !IS_SIGNUP && u && p
                && lowTxt.indexOf('create a password') === -1
                && lowTxt.indexOf('new password') === -1
                && lowTxt.indexOf("what's your name") === -1
                && lowTxt.indexOf('create a username') === -1;

            if (isLoginPage) {
                if (u.value !== PHONE) {
                    setNativeValue(u, PHONE);
                    lastActionAt = now;
                }
                if (p.value !== PASS) {
                    setNativeValue(p, PASS);
                    lastActionAt = now;
                }

                if (loginClickedOnce && (now - lastLoginClickAt >= 3500) && (now - lastActionAt >= 1200)) {
                    loginFailCount += 1;
                    logEvent('Login retry #' + loginFailCount + '/' + MAX_TRIES + ' for ' + PHONE);
                    if (loginFailCount >= MAX_TRIES) {
                        markCurrentAccountBroken('login_failed_3_tries');
                        return;
                    }
                    loginClickedOnce = false;
                }

                if (!loginClickedOnce && now - lastActionAt > 350) {
                    var clickedLogin = clickBtnByText(['Log in', 'Log In', 'Login']) || clickSubmitDirectly();
                    if (clickedLogin) {
                        loginClickedOnce = true;
                        lastLoginClickAt = now;
                        lastActionAt = now;
                        logEvent('Clicked Log in for ' + PHONE);
                    }
                }
                return;
            }

            if (agreeClickedDone || postAgreeReached || allowCreateClicked) {
                postAgreeReached = true;
                var pageSig = lowTxt.replace(/\s+/g, ' ').trim().slice(0, 140);
                if (pageSig !== lastPageSnapshot) {
                    lastPageSnapshot = pageSig;
                    pageQuietSince = now;
                }

                if (/\bskip\b/i.test(txt) && now - lastActionAt > 650) {
                    var didSkip = clickSkipOnceOnScreen(pageSig || ('skip_' + now));
                    if (didSkip) {
                        lastActionAt = now;
                        pageQuietSince = now;
                        logEvent('Clicked Skip once on post-agree page');
                        return;
                    }
                }

                if (lowTxt.indexOf('welcome to instagram') !== -1
                    || lowTxt.indexOf('add profile photo') !== -1
                    || lowTxt.indexOf('edit profile') !== -1) {
                    completeRun();
                    return;
                }

                if (pageQuietSince > 0 && (now - pageQuietSince >= 3500) && (now - lastActionAt >= 2000)) {
                    logEvent('Page settled after agree/skip -> completing run');
                    completeRun();
                }
            }

        } catch (e) {
            logEvent('error: ' + e);
        }
    }, 200);
})();
        """.trimIndent()
    }

    private fun generateRandomNames(): String {
        val maleFirstNames = listOf(
            "Arif", "Rakib", "Shakil", "Jahan", "Sabbir", "Hasan", "Riyad", "Tanvir", "Mehedi", "Sohel",
            "Emon", "Sumon", "Rana", "Raj", "Joy", "Raju", "Nayeem", "Sakib", "Tamim", "Mushfiq",
            "Mahmud", "Fahim", "Nahid", "Rahul", "Sifat", "Faisal", "Mizan", "Masud", "Anik", "Akash",
            "Bappy", "John", "Michael", "David", "James", "Robert", "William", "Richard", "Joseph", "Thomas",
            "Charles", "Daniel", "Matthew", "Anthony", "Mark", "Donald", "Steven", "Paul", "Andrew", "Joshua",
            "Kenneth", "Kevin", "Brian", "George", "Edward", "Ronald", "Timothy", "Jason", "Jeffrey", "Ryan",
            "Jacob", "Gary", "Nicholas", "Eric", "Jonathan", "Stephen", "Larry", "Justin", "Scott", "Brandon",
            "Benjamin", "Samuel", "Gregory", "Alexander", "Frank", "Patrick", "Raymond", "Jack", "Dennis", "Jerry",
            "Tyler", "Aaron", "Jose", "Adam", "Nathan", "Henry", "Douglas", "Zachary", "Peter", "Kyle",
            "Walter", "Ethan", "Jeremy", "Harold", "Keith", "Christian", "Roger", "Noah", "Gerald", "Carl",
            "Terry", "Sean", "Austin", "Arthur", "Lawrence", "Jesse", "Dylan", "Bryan", "Joe", "Jordan",
            "Billy", "Bruce", "Albert", "Willie", "Gabriel", "Logan", "Alan", "Juan", "Wayne", "Roy",
            "Ralph", "Randy", "Eugene", "Vincent", "Russell", "Elijah", "Louis", "Bobby", "Philip", "Johnny",
            "Oleg", "Dmitry", "Sergey", "Andrey", "Alexey", "Maxim", "Ivan", "Mikhail", "Nikolay", "Vladimir",
            "Pavel", "Roman", "Artem", "Denis", "Anton", "Igor", "Yuri", "Vasily", "Egor",
            "Arsen", "Tigran", "Davit", "Gor", "Hayk", "Vahan", "Karen", "Sarkis", "Aram", "Narek",
            "Hovhannes", "Moussa", "Ibrahim", "Amadou", "Ousmane", "Mamadou", "Souleymane", "Abdoulaye", "Boubacar", "Issa",
            "Emmanuel", "Kofi", "Kwame", "Kojo", "Yaw", "Kwabena", "Kwaku"
        )

        val femaleFirstNames = listOf(
            "Jahan", "Akter", "Sultana", "Khatun", "Begum", "Sharma", "Singh", "Kumar", "Das", "Paul",
            "Roy", "Sen", "Gupta", "Verma", "Yadav", "Tiwari", "Pandey", "Mishra", "Joshi", "Kapoor",
            "Malhotra", "Chopra", "Bhat", "Dutta", "Banerjee", "Chatterjee", "Mukherjee", "Ganguly", "Bose", "Ghosh",
            "Chakraborty", "Mary", "Patricia", "Jennifer", "Linda", "Elizabeth", "Barbara", "Susan", "Jessica", "Sarah",
            "Karen", "Nancy", "Lisa", "Betty", "Margaret", "Sandra", "Ashley", "Kimberly", "Emily", "Donna",
            "Michelle", "Carol", "Amanda", "Dorothy", "Melissa", "Deborah", "Stephanie", "Rebecca", "Sharon", "Laura",
            "Cynthia", "Kathleen", "Amy", "Angela", "Shirley", "Anna", "Brenda", "Pamela", "Emma", "Nicole",
            "Helen", "Samantha", "Katherine", "Christine", "Debra", "Rachel", "Carolyn", "Janet", "Catherine", "Maria",
            "Heather", "Diane", "Ruth", "Julie", "Olivia", "Joyce", "Virginia", "Victoria", "Kelly", "Lauren",
            "Christina", "Joan", "Evelyn", "Judith", "Megan", "Andrea", "Cheryl", "Hannah", "Jacqueline", "Martha",
            "Gloria", "Teresa", "Ann", "Sara", "Madison", "Frances", "Kathryn", "Janice", "Jean", "Abigail",
            "Alice", "Julia", "Judy", "Sophia", "Grace", "Denise", "Amber", "Doris", "Marilyn", "Danielle",
            "Beverly", "Isabella", "Theresa", "Diana", "Natalie", "Brittany", "Charlotte", "Marie", "Kayla", "Alexis",
            "Lori", "Olga", "Elena", "Tatiana", "Natalia", "Irina", "Svetlana", "Yulia", "Marina",
            "Ekaterina", "Anastasia", "Galina", "Lyudmila", "Valentina", "Nadezhda", "Oksana", "Vera", "Larisa",
            "Zoya", "Amina", "Fatima", "Khadija", "Mariam", "Zahra", "Aisha", "Halima", "Asma", "Rania",
            "Layla", "Nour", "Salma", "Yasmin", "Nadia", "Samira", "Hana", "Malak", "Farida", "Nawal",
            "Ghada", "Anahit", "Nare", "Gayane", "Lusine", "Karine", "Siranush", "Narine", "Hasmik",
            "Arpine", "Lilit", "Amelia", "Harper", "Ella", "Camila",
            "Luna", "Sofia", "Avery", "Mila", "Aria", "Scarlett", "Penelope", "Chloe"
        )

        val lastNames = listOf(
            "Hossain", "Islam", "Ahmed", "Khan", "Mia", "Sarker", "Chowdhury", "Rahman", "Ali", "Hasan",
            "Uddin", "Akter", "Sultana", "Khatun", "Begum", "Sharma", "Singh", "Kumar", "Das", "Paul",
            "Roy", "Sen", "Gupta", "Verma", "Yadav", "Tiwari", "Pandey", "Mishra", "Joshi", "Kapoor",
            "Malhotra", "Chopra", "Bhat", "Dutta", "Banerjee", "Chatterjee", "Mukherjee", "Ganguly", "Bose", "Ghosh",
            "Chakraborty", "Reza", "Siddique", "Mahmud", "Karim", "Rahim", "Bhuiyan", "Talukder", "Mondal", "Shikder",
            "Hawlader", "Matbor", "Molla", "Saha", "Dhar", "Nath", "Barua", "Sikder", "Smith", "Johnson",
            "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis", "Rodriguez", "Martinez", "Hernandez", "Lopez",
            "Gonzalez", "Wilson", "Anderson", "Thomas", "Taylor", "Moore", "Jackson", "Martin", "Lee", "Perez",
            "Thompson", "White", "Harris", "Sanchez", "Clark", "Ramirez", "Lewis", "Robinson", "Walker", "Young",
            "Allen", "King", "Wright", "Scott", "Torres", "Nguyen", "Hill", "Flores", "Green", "Adams",
            "Nelson", "Baker", "Hall", "Rivera", "Campbell", "Mitchell", "Carter", "Roberts", "Ivanov", "Smirnov",
            "Kuznetsov", "Popov", "Vasiliev", "Petrov", "Sokolov", "Mikhailov", "Novikov", "Fedorov", "Morozov", "Volkov",
            "Alekseev", "Lebedev", "Semenov", "Egorov", "Pavlov", "Kozlov", "Stepanov", "Nikolaev", "Orlov", "Andreev",
            "Makarov", "Nikitin", "Zakharov", "Petrosyan", "Grigoryan", "Sargsyan", "Harutyunyan", "Hakobyan", "Karapetyan", "Gevorgyan",
            "Avetisyan", "Hovhannisyan", "Diallo", "Traore", "Cisse", "Coulibaly", "Keita", "Toure", "Kone",
            "Camara", "Ouedraogo", "Kaboré", "Okafor", "Adebayo", "Oluwaseun", "Ibrahim", "Musa", "Abubakar", "Mensah",
            "Owusu", "Boateng", "Asante", "Appiah", "Osei", "Agyemang", "Addo", "Amoah", "Bailey",
            "Cooper", "Richardson", "Cox", "Howard", "Ward", "Peterson", "Gray",
            "James", "Watson", "Brooks", "Kelly", "Sanders", "Price", "Bennett", "Wood", "Barnes", "Ross",
            "Henderson", "Coleman", "Jenkins", "Perry", "Powell", "Long", "Patterson", "Hughes", "Washington",
            "Butler", "Simmons", "Foster", "Gonzales", "Bryant", "Alexander", "Russell", "Griffin", "Diaz", "Hayes"
        )

        val names = mutableListOf<String>()
        for (f in maleFirstNames) {
            for (l in lastNames) {
                if (f != l) names.add("$f $l")
            }
        }
        for (f in femaleFirstNames) {
            for (l in lastNames) {
                if (f != l) names.add("$f $l")
            }
        }

        val arr = JSONArray()
        names.shuffled().take(2000).forEach { arr.put(it) }
        return arr.toString()
    }
}
