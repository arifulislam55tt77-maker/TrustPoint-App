package com.example

import android.util.Base64
import android.util.Log
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

// ═══════════════════════════════════════════════════════════════
//  PROXY HELPERS — used by Proxy Set / Proxy ON buttons
// ═══════════════════════════════════════════════════════════════

private fun MainActivity.isFeatureActivityAlive(): Boolean {
    return try {
        !isFinishing && !isDestroyed
    } catch (_: Exception) {
        false
    }
}

private fun MainActivity.evalMainJsSafe(script: String) {
    if (!isFeatureActivityAlive()) return
    try {
        mainWebView.evaluateJavascript(script, null)
    } catch (_: UninitializedPropertyAccessException) {
    } catch (e: Exception) {
        Log.e(MainActivity.TAG, "evalMainJsSafe error: ${e.message}")
    }
}

private fun escapeForJs(raw: String): String {
    return raw
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "")
}

private data class ParsedProxy(
    val type: Proxy.Type,
    val host: String,
    val port: Int
)

private fun parseProxyEndpoint(rawIp: String, rawPort: String): ParsedProxy? {
    var cleanHost = rawIp.trim()
    var cleanPortStr = rawPort.trim()
    val lowerHost = cleanHost.lowercase()

    val proxyType = when {
        lowerHost.startsWith("socks5://") || lowerHost.startsWith("socks4://") -> Proxy.Type.SOCKS
        else -> Proxy.Type.HTTP
    }

    cleanHost = cleanHost
        .removePrefix("http://")
        .removePrefix("https://")
        .removePrefix("socks5://")
        .removePrefix("socks4://")
        .trim()

    if (cleanHost.contains(":") && cleanPortStr.isBlank()) {
        val parts = cleanHost.split(":", limit = 2)
        cleanHost = parts[0].trim()
        cleanPortStr = parts.getOrNull(1)?.trim() ?: ""
    }

    val portInt = cleanPortStr.toIntOrNull() ?: return null
    if (cleanHost.isBlank() || portInt !in 1..65535) return null
    return ParsedProxy(proxyType, cleanHost, portInt)
}

internal suspend fun MainActivity.testProxyCredentials(
    ip: String, port: String, user: String, pass: String
): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    var conn: HttpURLConnection? = null
    try {
        val parsed = parseProxyEndpoint(ip, port)
            ?: return@withContext Pair(false, "Invalid IP or port")

        val proxyAddr = InetSocketAddress(parsed.host, parsed.port)
        val javaProxy = Proxy(parsed.type, proxyAddr)
        val url = URL("http://www.gstatic.com/generate_204")
        conn = (url.openConnection(javaProxy) as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 7000
            instanceFollowRedirects = false
            requestMethod = "GET"
            useCaches = false
            setRequestProperty("User-Agent", mobileUserAgent)
            if (parsed.type == Proxy.Type.HTTP && user.isNotBlank()) {
                val auth = "$user:$pass"
                val encoded = Base64.encodeToString(auth.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                setRequestProperty("Proxy-Authorization", "Basic $encoded")
            }
        }

        conn.connect()
        val code = conn.responseCode
        try { conn.inputStream?.close() } catch (_: Exception) {}
        try { conn.errorStream?.close() } catch (_: Exception) {}

        Log.d(MainActivity.TAG, "Proxy test HTTP $code (${parsed.host}:${parsed.port})")
        if (code in 200..399) {
            AutomationRuntimeState.logExecution(this@testProxyCredentials, "Proxy", "SUCCESS", "Proxy OK: HTTP $code (${parsed.host}:${parsed.port})")
            Pair(true, "HTTP $code")
        } else {
            AutomationRuntimeState.logExecution(this@testProxyCredentials, "Proxy", "WARN", "Proxy HTTP $code")
            Pair(false, "HTTP $code")
        }
    } catch (e: Exception) {
        Log.e(MainActivity.TAG, "Proxy test failed: ${e.message}")
        AutomationRuntimeState.logExecution(this@testProxyCredentials, "Proxy", "ERROR", "Proxy failed: ${e.message}")
        Pair(false, e.message ?: "Connection failed")
    } finally {
        try { conn?.disconnect() } catch (_: Exception) {}
    }
}

internal fun MainActivity.runProxyTestAsync(
    ip: String, port: String, user: String, pass: String
) {
    if (!isFeatureActivityAlive()) return
    try { setBrowserProxy(ip, port, user, pass) } catch (_: Exception) {}

    try {
        lifecycleScope.launch {
            if (!isFeatureActivityAlive()) return@launch
            val (ok, msg) = testProxyCredentials(ip, port, user, pass)
            if (!isFeatureActivityAlive()) return@launch
            runOnUiThread {
                if (!isFeatureActivityAlive()) return@runOnUiThread
                try { updateProxyConnectionStatus(!ok) } catch (_: Exception) {}
                if (ok) toast("✓ Proxy OK: $msg")
                else toast("❌ Proxy: $msg")
                try {
                    val safeMsg = escapeForJs(msg).take(200)
                    evalMainJsSafe(
                        "(function(){try{" +
                        "if(window.TP&&typeof window.TP.onProxyTest==='function')window.TP.onProxyTest($ok,'$safeMsg');" +
                        "}catch(e){}})();"
                    )
                } catch (_: Exception) {}
            }
        }
    } catch (e: Exception) {
        Log.e(MainActivity.TAG, "runProxyTestAsync launch failed: ${e.message}")
        if (isFeatureActivityAlive()) {
            toast("❌ Proxy test error: ${e.message}")
        }
    }
}

internal fun MainActivity.pushProxyValuesToHtml() {
    if (!isFeatureActivityAlive()) return
    try {
        val sIp = escapeForJs(currentProxyIp)
        val sPort = escapeForJs(currentProxyPort)
        val sUser = escapeForJs(currentProxyUser)
        val sPass = escapeForJs(currentProxyPass)
        val js = "(function(){try{" +
            "var ids=[['brProxyIp','proxyIp','$sIp'],['brProxyPort','proxyPort','$sPort'],['brProxyUser','proxyUser','$sUser'],['brProxyPass','proxyPass','$sPass']];" +
            "for(var i=0;i<ids.length;i++){" +
            "var a=document.getElementById(ids[i][0]);if(a)a.value=ids[i][2];" +
            "var b=document.getElementById(ids[i][1]);if(b)b.value=ids[i][2];" +
            "}" +
            "try{localStorage.setItem('tp_proxy_ip','$sIp');localStorage.setItem('tp_proxy_port','$sPort');" +
            "localStorage.setItem('tp_proxy_user','$sUser');localStorage.setItem('tp_proxy_pass','$sPass');}catch(e){}" +
            "}catch(e){}})();"
        evalMainJsSafe(js)
    } catch (_: Exception) {}
}

internal fun MainActivity.handleProxyErrorMain(errorCode: Int, description: String) {
    if (!isFeatureActivityAlive() || !proxyActuallyActive) return
    val d = description.lowercase()
    val isProxyIssue = d.contains("proxy") || d.contains("tunnel") ||
            d.contains("connection refused") || d.contains("unable to resolve host") ||
            d.contains("authentication") || d.contains("407") ||
            d.contains("timed out") || d.contains("failed to connect") ||
            errorCode == -2 || errorCode == -6 || errorCode == -8

    if (!isProxyIssue) return

    val reason = when {
        d.contains("authentication") || d.contains("407") -> "Username/Password wrong"
        d.contains("connection refused") -> "Proxy server unreachable"
        d.contains("unable to resolve host") -> "Host resolve failed"
        d.contains("timed out") -> "Connection timeout"
        else -> description.ifBlank { "Proxy connection failed" }
    }

    toast("❌ Proxy: $reason")
    AutomationRuntimeState.logExecution(this, "Proxy", "ERROR", "Proxy disconnected: $reason")
    proxyActuallyActive = false
    isProxyToggling = false
    updateProxyConnectionStatus(true)
    clearProxyOverride {
        if (!isFeatureActivityAlive()) return@clearProxyOverride
        runOnUiThread {
            if (!isFeatureActivityAlive()) return@runOnUiThread
            try {
                val safeReason = JSONObject.quote(reason)
                evalMainJsSafe(
                    "(function(){try{" +
                    "var b=document.getElementById('pageBtn');" +
                    "if(b){b.textContent='🌐 Proxy ON';b.style.background='linear-gradient(180deg,#4a3218,#281808)';}" +
                    "if(window.TP&&typeof window.TP.showProxyFailed==='function')window.TP.showProxyFailed($safeReason);" +
                    "}catch(e){}})();"
                )
            } catch (_: Exception) {}
        }
    }
}

internal fun MainActivity.pushTwoFaKeyToHtml(twofaKey: String) {
    if (!isFeatureActivityAlive()) return
    try {
        val clean = twofaKey.trim()
        if (clean.isNotBlank()) {
            current2FAKey = clean
            saveCurrentMail()
        }
        val sKey = escapeForJs(clean)
        evalMainJsSafe(
            "(function(){try{" +
            "var el=document.getElementById('twofaKey')||document.getElementById('disp2FA')||document.getElementById('twofaInput');" +
            "if(el){if('value' in el){el.value='$sKey';}else{el.textContent='$sKey'||'2FA';}}" +
            "try{localStorage.setItem('tp_2fa_key','$sKey');}catch(e){}" +
            "if(window.TP&&typeof window.TP.set2FAKey==='function')window.TP.set2FAKey('$sKey');" +
            "}catch(e){}})();"
        )
    } catch (_: Exception) {}
}

internal fun MainActivity.syncSheetDataToHtml() {
    if (!isFeatureActivityAlive()) return
    try {
        val sheetJson = getSheetJson()
        val sJson = escapeForJs(sheetJson)
        evalMainJsSafe(
            "(function(){try{" +
            "try{localStorage.setItem('tp_sheet_backup','$sJson');}catch(e){}" +
            "if(window.TP&&typeof window.TP.onSheetSynced==='function')window.TP.onSheetSynced('$sJson');" +
            "}catch(e){}})();"
        )
    } catch (_: Exception) {}
}

internal fun MainActivity.pushOtpToHtml(otp: String) {
    if (otp.isBlank() || !isFeatureActivityAlive()) return
    try {
        val s = escapeForJs(otp)
        evalMainJsSafe(
            "(function(){try{" +
            "var el=document.getElementById('dispOtp');" +
            "if(el){el.textContent='$s';" +
            "el.style.background='linear-gradient(180deg,#065f46,#047857)';" +
            "el.style.borderColor='#10b981';" +
            "el.style.color='#dcfce7';}" +
            "if(window.TP&&typeof window.TP.setOtp==='function')window.TP.setOtp('$s');" +
            "}catch(e){}})();"
        )
    } catch (_: Exception) {}
}

internal fun MainActivity.clearOtpOnHtml() {
    if (!isFeatureActivityAlive()) return
    try {
        evalMainJsSafe(
            "(function(){try{" +
            "var el=document.getElementById('dispOtp');" +
            "if(el){el.textContent='OTP';" +
            "el.style.background='linear-gradient(180deg,#7f1d1d,#450a0a)';" +
            "el.style.borderColor='#ef4444';" +
            "el.style.color='#fecaca';}" +
            "if(window.TP&&typeof window.TP.setOtp==='function')window.TP.setOtp('');" +
            "}catch(e){}})();"
        )
    } catch (_: Exception) {}
}

internal fun MainActivity.pushNumberToHtml(number: String, country: String) {
    if (!isFeatureActivityAlive()) return
    try {
        val sn = escapeForJs(number)
        val sc = escapeForJs(country)
        evalMainJsSafe(
            "(function(){try{" +
            "var el=document.getElementById('dispNumber');" +
            "if(el)el.textContent='Number: ' + ('$sn' || '-');" +
            "var ec=document.getElementById('dispCountry');" +
            "if(ec)ec.textContent='Country: ' + ('$sc' || '-');" +
            "if(window.TP&&typeof window.TP.setNumber==='function')window.TP.setNumber('$sn','$sc');" +
            "}catch(e){}})();"
        )
    } catch (_: Exception) {}
}

internal fun MainActivity.clearStatusOnHtml() {
    if (!isFeatureActivityAlive()) return
    try {
        evalMainJsSafe(
            "(function(){try{" +
            "var el=document.getElementById('dispNumber');if(el)el.textContent='Number: -';" +
            "var ec=document.getElementById('dispCountry');if(ec)ec.textContent='Country: -';" +
            "var eo=document.getElementById('dispOtp');" +
            "if(eo){eo.textContent='OTP';" +
            "eo.style.background='linear-gradient(180deg,#7f1d1d,#450a0a)';" +
            "eo.style.borderColor='#ef4444';eo.style.color='#fecaca';}" +
            "if(window.TP&&typeof window.TP.setNumber==='function')window.TP.setNumber('','');" +
            "if(window.TP&&typeof window.TP.setOtp==='function')window.TP.setOtp('');" +
            "}catch(e){}})();"
        )
    } catch (_: Exception) {}
}
