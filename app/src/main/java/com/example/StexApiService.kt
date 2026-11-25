package com.example

import android.content.Context
import android.util.Log
import com.example.data.TrustPointRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object StexApiService {

    interface PanelCallback {
        fun onLog(message: String)
        fun onRangesLoaded(ranges: List<RangeInfo>)
        fun onRangesFailed(error: String, rawResponse: String)
        fun onNumberAllocated(number: String, rid: String, country: String, operator: String)
        fun onNumberFailed(error: String)
        fun onOtpReceived(otp: String, number: String)
        fun onOtpTimeout()
    }

    data class RangeInfo(
        val rid: String,
        val range: String,
        val label: String,
        val country: String
    )

    data class NumberInfo(
        val number: String,
        val country: String,
        val operator: String,
        val rid: String = ""
    )

    data class AllocatedNumber(
        val number: String,
        val rid: String,
        val country: String,
        val operator: String
    )

    data class NumberResponse(
        val success: Boolean,
        val numberInfo: NumberInfo? = null,
        val error: String = ""
    )

    data class OtpResponse(
        val found: Boolean,
        val otp: String = "",
        val message: String = ""
    )

    const val PANEL_STEX = "stex"
    const val PANEL_VOLTEX = "voltex"

    private const val STEX_DEFAULT_BASE_URL = "https://stexsms.com/api/v1"
    private const val VOLTEX_DEFAULT_BASE_URL = "https://voltexsms.com/api/v1"

    private const val TAG = "StexApiService"
    private const val PREFS = "ir_api_keys"
    private const val KEY_STEX_API = "stex"
    private const val KEY_VOLTEX_API = "voltex"
    private const val KEY_BASE_URL = "stex_base_url"
    private const val KEY_SELECTED_PANEL = "selected_panel"
    private const val KEY_SELECTED_RID = "selected_rid"

    private const val USED_NUMBERS_PREFS = "ir_used_numbers_24h"
    private const val USED_NUMBER_TTL_MS = 24L * 60L * 60L * 1000L
    private const val MAX_DUPLICATE_RETRIES = 10

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var otpPollJob: Job? = null
    private val isPolling = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastLoadedRanges: List<RangeInfo> = emptyList()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun getSelectedPanel(): String {
        val ctx = appContext ?: return PANEL_STEX
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_PANEL, PANEL_STEX)?.trim()?.lowercase().orEmpty()
        return if (p == PANEL_VOLTEX) PANEL_VOLTEX else PANEL_STEX
    }

    fun setSelectedPanel(panel: String) {
        val ctx = appContext ?: return
        val normalized = if (panel.trim().equals(PANEL_VOLTEX, ignoreCase = true)) PANEL_VOLTEX else PANEL_STEX
        val defaultUrl = if (normalized == PANEL_VOLTEX) VOLTEX_DEFAULT_BASE_URL else STEX_DEFAULT_BASE_URL
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_PANEL, normalized)
            .putString(KEY_BASE_URL, defaultUrl)
            .apply()
        AutomationRuntimeState.logExecution(ctx, TAG, "INFO", "Active SMS panel set to ${normalized.uppercase()}")
    }

    fun hasApiKey(): Boolean {
        val ctx = appContext ?: return false
        return getApiKey().isNotBlank() && getBaseUrl().isNotBlank()
    }

    fun saveApiConfig(baseUrl: String, apiKey: String) {
        val ctx = appContext ?: return
        val cleanUrl = baseUrl.trim().trimEnd('/').ifBlank {
            if (getSelectedPanel() == PANEL_VOLTEX) VOLTEX_DEFAULT_BASE_URL else STEX_DEFAULT_BASE_URL
        }
        val cleanKey = apiKey.trim()
        val panel = when {
            cleanUrl.contains("voltex", ignoreCase = true) -> PANEL_VOLTEX
            cleanUrl.contains("stex", ignoreCase = true) -> PANEL_STEX
            else -> getSelectedPanel()
        }
        val editor = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BASE_URL, cleanUrl)
            .putString(KEY_SELECTED_PANEL, panel)
        if (panel == PANEL_VOLTEX) {
            editor.putString(KEY_VOLTEX_API, cleanKey)
        } else {
            editor.putString(KEY_STEX_API, cleanKey)
        }
        editor.apply()
        AutomationRuntimeState.logExecution(ctx, TAG, "INFO", "Panel API config updated (${panel.uppercase()})")
    }

    fun getBaseUrl(): String {
        val ctx = appContext ?: return ""
        val custom = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BASE_URL, "")?.trim().orEmpty()
        if (custom.isNotEmpty()) return custom
        return if (getSelectedPanel() == PANEL_VOLTEX) VOLTEX_DEFAULT_BASE_URL else STEX_DEFAULT_BASE_URL
    }

    fun getApiKey(): String {
        val ctx = appContext ?: return ""
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val panel = getSelectedPanel()
        val panelKey = if (panel == PANEL_VOLTEX) {
            prefs.getString(KEY_VOLTEX_API, "")?.trim().orEmpty()
        } else {
            prefs.getString(KEY_STEX_API, "")?.trim().orEmpty()
        }
        if (panelKey.isNotEmpty()) return panelKey
        return prefs.getString(KEY_STEX_API, "")?.trim().orEmpty()
            .ifEmpty { prefs.getString(KEY_VOLTEX_API, "")?.trim().orEmpty() }
    }

    fun resolveRid(ridOrRange: String): String {
        val cleanInput = ridOrRange.trim()
        if (cleanInput.isBlank()) return getSelectedRid()
        val cached = lastLoadedRanges
        if (cached.isNotEmpty()) {
            val exactRid = cached.firstOrNull { it.rid.equals(cleanInput, ignoreCase = true) }
            if (exactRid != null) return exactRid.rid
            val normIn = cleanInput.replace(Regex("[^0-9]"), "")
            if (normIn.isNotBlank()) {
                val matchedByRange = cached.firstOrNull {
                    it.range.replace(Regex("[^0-9]"), "") == normIn
                }
                if (matchedByRange != null) return matchedByRange.rid
            }
        }
        return cleanInput
    }

    fun getSelectedRid(): String {
        val ctx = appContext ?: return ""
        val fromKeyPrefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_RID, "")?.trim().orEmpty()
        if (fromKeyPrefs.isNotEmpty()) return fromKeyPrefs

        return try {
            val raw = ctx.getSharedPreferences(MainActivity.RANGE_PREFS, Context.MODE_PRIVATE)
                .getString(MainActivity.KEY_SAVED_RANGE, "") ?: ""
            if (raw.isNotBlank()) JSONObject(raw).optString("rid", "") else ""
        } catch (_: Exception) {
            ""
        }
    }

    fun setSelectedRid(rid: String) {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED_RID, rid.trim()).apply()
    }

    fun saveSelectedRange(rid: String, rangeValue: String, label: String = "") {
        val ctx = appContext ?: return
        setSelectedRid(rid)
        try {
            val json = JSONObject().apply {
                put("rid", rid.trim())
                put("range", rangeValue.trim())
                if (label.isNotBlank()) put("label", label.trim())
            }.toString()
            ctx.getSharedPreferences(MainActivity.RANGE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(MainActivity.KEY_SAVED_RANGE, json).apply()
        } catch (_: Exception) {}
    }

    private fun isStandardStexOrVoltex(baseUrl: String): Boolean {
        val lower = baseUrl.lowercase()
        return lower.contains("stexsms") || lower.contains("voltex") || lower.endsWith("/api/v1")
    }

    private fun buildAuthorizedRequest(url: String, apiKey: String): Request {
        return Request.Builder()
            .url(url)
            .header("mauthapi", apiKey)
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) TrustPoint/1.0")
            .get()
            .build()
    }

    private fun buildAuthorizedPostRequest(url: String, apiKey: String, jsonBody: JSONObject): Request {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonBody.toString().toRequestBody(mediaType)
        return Request.Builder()
            .url(url)
            .header("mauthapi", apiKey)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) TrustPoint/1.0")
            .post(body)
            .build()
    }

    suspend fun getLiveAccess(): Result<List<RangeInfo>> = withContext(Dispatchers.IO) {
        val ctx = appContext
        val baseUrl = getBaseUrl()
        val apiKey = getApiKey()

        if (baseUrl.isBlank() || apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Panel API Key not configured (set in Auto Control)"))
        }

        try {
            val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
            val candidates = if (baseUrl.contains("{key}")) {
                listOf(baseUrl.replace("{key}", encodedKey))
            } else if (isStandardStexOrVoltex(baseUrl)) {
                listOf("$baseUrl/liveaccess", "$baseUrl/getRanges?api_key=$encodedKey")
            } else {
                listOf("$baseUrl/getRanges?api_key=$encodedKey", "$baseUrl/liveaccess")
            }

            var lastError = "Unable to load ranges"
            for (endpoint in candidates) {
                try {
                    val req = buildAuthorizedRequest(endpoint, apiKey)
                    httpClient.newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            lastError = "HTTP ${resp.code}"
                            return@use
                        }
                        val parsed = parseRanges(body)
                        if (parsed.isNotEmpty()) {
                            lastLoadedRanges = parsed
                            AutomationRuntimeState.logExecution(ctx, TAG, "SUCCESS", "Loaded ${parsed.size} ranges from panel")
                            return@withContext Result.success(parsed)
                        } else {
                            lastError = "Empty ranges returned"
                        }
                    }
                } catch (e: Exception) {
                    lastError = e.message ?: "Request failed"
                }
            }

            AutomationRuntimeState.logExecution(ctx, TAG, "ERROR", "getLiveAccess failed: $lastError")
            Result.failure(IllegalStateException(lastError))
        } catch (e: Exception) {
            Log.e(TAG, "getLiveAccess error: ${e.message}")
            AutomationRuntimeState.logExecution(ctx, TAG, "ERROR", "getLiveAccess failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun allocateNumber(rid: String): Result<AllocatedNumber> = withContext(Dispatchers.IO) {
        val ctx = appContext
        val baseUrl = getBaseUrl()
        val apiKey = getApiKey()
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Configure Panel API Key in Auto Control"))
        }

        val effectiveRid = resolveRid(rid)
        if (effectiveRid.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Select range first"))
        }

        try {
            setSelectedRid(effectiveRid)
            val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
            val encodedRid = URLEncoder.encode(effectiveRid, "UTF-8")
            var duplicateSkips = 0

            repeat(MAX_DUPLICATE_RETRIES) { attempt ->
                val resp = requestNumberOnce(baseUrl, apiKey, encodedKey, encodedRid, effectiveRid)
                if (!resp.success || resp.numberInfo == null) {
                    val err = resp.error.ifBlank { "No number returned" }
                    AutomationRuntimeState.logExecution(ctx, TAG, "WARN", "allocateNumber: $err")
                    return@withContext Result.failure(IllegalStateException(err))
                }

                val info = resp.numberInfo
                if (isNumberUsedWithin24h(ctx, info.number)) {
                    duplicateSkips++
                    AutomationRuntimeState.logExecution(
                        ctx,
                        TAG,
                        "WARN",
                        "Duplicate number ${info.number} skipped (attempt ${attempt + 1}/$MAX_DUPLICATE_RETRIES)"
                    )
                    delay(250L)
                } else {
                    markNumberUsed24h(ctx, info.number)
                    AutomationRuntimeState.update(ctx) {
                        it.copy(currentNumber = info.number, currentCountry = info.country, currentOtp = "")
                    }
                    AutomationRuntimeState.logExecution(
                        ctx,
                        TAG,
                        "SUCCESS",
                        "Number allocated: ${info.number} (${info.country})"
                    )
                    return@withContext Result.success(
                        AllocatedNumber(
                            number = info.number,
                            rid = effectiveRid,
                            country = info.country,
                            operator = info.operator
                        )
                    )
                }
            }

            val msg = "Duplicate numbers returned $duplicateSkips times; retry again"
            AutomationRuntimeState.logExecution(ctx, TAG, "WARN", msg)
            Result.failure(IllegalStateException(msg))
        } catch (e: Exception) {
            Log.e(TAG, "allocateNumber error: ${e.message}")
            AutomationRuntimeState.logExecution(ctx, TAG, "ERROR", "allocateNumber failed: ${e.message}")
            Result.failure(e)
        }
    }

    private fun requestNumberOnce(
        baseUrl: String,
        apiKey: String,
        encodedKey: String,
        encodedRid: String,
        rid: String
    ): NumberResponse {
        val candidates: List<Request> = if (isStandardStexOrVoltex(baseUrl)) {
            listOf(
                buildAuthorizedRequest("$baseUrl/getnum?rid=$encodedRid", apiKey),
                buildAuthorizedPostRequest("$baseUrl/getnum", apiKey, JSONObject().put("rid", rid)),
                buildAuthorizedRequest("$baseUrl/getNumber?api_key=$encodedKey&range=$encodedRid&rid=$encodedRid", apiKey)
            )
        } else {
            listOf(
                buildAuthorizedRequest("$baseUrl/getNumber?api_key=$encodedKey&range=$encodedRid&rid=$encodedRid", apiKey),
                buildAuthorizedRequest("$baseUrl/getnum?rid=$encodedRid", apiKey)
            )
        }

        var lastError = "No number returned"
        for (req in candidates) {
            try {
                httpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        lastError = "HTTP ${resp.code}"
                        return@use
                    }
                    val parsed = parseNumberResponse(body, rid)
                    if (parsed.success && parsed.numberInfo != null) return parsed
                    if (parsed.error.isNotBlank()) lastError = parsed.error
                }
            } catch (e: Exception) {
                lastError = e.message ?: "Network error"
            }
        }
        return NumberResponse(success = false, error = lastError)
    }

    fun startOtpPolling(number: String, callback: PanelCallback) {
        stopOtpPolling()
        val ctx = appContext
        val baseUrl = getBaseUrl()
        val apiKey = getApiKey()

        if (baseUrl.isBlank() || apiKey.isBlank()) {
            callback.onLog("OTP polling skipped: API URL/Key not configured")
            return
        }

        isPolling.set(true)
        AutomationRuntimeState.update(ctx) { it.copy(otpPollingActive = true) }
        AutomationRuntimeState.logExecution(ctx, TAG, "INFO", "Started OTP polling for $number")

        otpPollJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            val timeoutMs = 4 * 60 * 1000L
            val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
            val encodedNum = URLEncoder.encode(number, "UTF-8")

            while (isActive && isPolling.get()) {
                if (System.currentTimeMillis() - startedAt > timeoutMs) {
                    isPolling.set(false)
                    AutomationRuntimeState.update(ctx) { it.copy(otpPollingActive = false) }
                    AutomationRuntimeState.logExecution(ctx, TAG, "WARN", "OTP polling timeout for $number")
                    withContext(Dispatchers.Main) { callback.onOtpTimeout() }
                    break
                }

                try {
                    val otpResp = pollOtpOnce(baseUrl, apiKey, encodedKey, encodedNum, number)
                    if (otpResp.found && otpResp.otp.isNotBlank()) {
                        val isUnique = ctx?.let {
                            TrustPointRepository.getInstance(it)
                                .recordOtpIfUnique(number, otpResp.otp, AutomationRuntimeState.state.value.currentCountry)
                        } ?: true

                        if (isUnique) {
                            AutomationRuntimeState.update(ctx) {
                                it.copy(currentOtp = otpResp.otp, otpPollingActive = false)
                            }
                            AutomationRuntimeState.logExecution(ctx, TAG, "SUCCESS", "OTP received for $number: ${otpResp.otp}")
                            isPolling.set(false)
                            withContext(Dispatchers.Main) {
                                callback.onOtpReceived(otpResp.otp, number)
                            }
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "OTP poll attempt error: ${e.message}")
                }

                delay(3000L)
            }
        }
    }

    private fun pollOtpOnce(
        baseUrl: String,
        apiKey: String,
        encodedKey: String,
        encodedNum: String,
        rawNumber: String
    ): OtpResponse {
        val candidates = if (isStandardStexOrVoltex(baseUrl)) {
            listOf(
                buildAuthorizedRequest("$baseUrl/success-otp?number=$encodedNum", apiKey),
                buildAuthorizedRequest("$baseUrl/success-otp", apiKey),
                buildAuthorizedRequest("$baseUrl/getOtp?api_key=$encodedKey&number=$encodedNum", apiKey)
            )
        } else {
            listOf(
                buildAuthorizedRequest("$baseUrl/getOtp?api_key=$encodedKey&number=$encodedNum", apiKey),
                buildAuthorizedRequest("$baseUrl/success-otp?number=$encodedNum", apiKey)
            )
        }

        for (req in candidates) {
            try {
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string().orEmpty()
                    val otp = extractOtpFromBody(body, rawNumber)
                    if (otp.isNotBlank()) {
                        return OtpResponse(found = true, otp = otp, message = body)
                    }
                }
            } catch (_: Exception) {}
        }
        return OtpResponse(found = false)
    }

    fun stopOtpPolling() {
        isPolling.set(false)
        try {
            otpPollJob?.cancel()
        } catch (_: Exception) {}
        otpPollJob = null
        AutomationRuntimeState.update(appContext) { it.copy(otpPollingActive = false) }
    }

    fun parseRanges(body: String): List<RangeInfo> {
        val result = mutableListOf<RangeInfo>()
        val seenRids = HashSet<String>()

        fun extractFromJsonArray(arr: JSONArray) {
            for (i in 0 until arr.length()) {
                val item = arr.opt(i) ?: continue
                when (item) {
                    is JSONObject -> {
                        val nestedRanges = item.optJSONArray("ranges") ?: item.optJSONArray("data")
                        if (nestedRanges != null) {
                            extractFromJsonArray(nestedRanges)
                        }
                        val rid = item.optString("rid")
                            .ifBlank { item.optString("id") }
                            .ifBlank { item.optString("sid") }
                            .trim()
                        val range = item.optString("range")
                            .ifBlank { item.optString("prefix") }
                            .ifBlank { rid }
                            .trim()
                        val label = item.optString("label")
                            .ifBlank { item.optString("title") }
                            .ifBlank { item.optString("name") }
                            .ifBlank { item.optString("country") }
                            .ifBlank { "Service" }
                            .trim()
                        val country = item.optString("country")
                            .ifBlank { label }
                            .trim()
                        if (rid.isNotBlank() && seenRids.add(rid)) {
                            result.add(RangeInfo(rid, range, label, country))
                        }
                    }
                    is JSONArray -> extractFromJsonArray(item)
                }
            }
        }

        try {
            val trimmed = body.trim()
            when {
                trimmed.startsWith("[") -> extractFromJsonArray(JSONArray(trimmed))
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    val directArr = obj.optJSONArray("ranges")
                        ?: obj.optJSONArray("data")
                        ?: obj.optJSONArray("list")
                        ?: obj.optJSONArray("access")
                    if (directArr != null) {
                        extractFromJsonArray(directArr)
                    } else {
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val child = obj.opt(k)
                            when (child) {
                                is JSONArray -> extractFromJsonArray(child)
                                is JSONObject -> {
                                    val subArr = child.optJSONArray("ranges") ?: child.optJSONArray("data")
                                    if (subArr != null) extractFromJsonArray(subArr)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseRanges error: ${e.message}")
        }
        return result
    }

    private fun parseNumberResponse(body: String, rid: String): NumberResponse {
        return try {
            val trimmed = body.trim()
            if (!trimmed.startsWith("{")) {
                val rawDigits = Regex("\\+?\\d{7,15}").find(trimmed)?.value.orEmpty()
                return if (rawDigits.isNotBlank()) {
                    NumberResponse(true, NumberInfo(rawDigits, "Service", "Panel", rid))
                } else {
                    NumberResponse(false, error = "Invalid panel response")
                }
            }
            val root = JSONObject(trimmed)
            val statusFalse = root.has("status") && !root.optBoolean("status", true)
            val successFalse = root.has("success") && !root.optBoolean("success", true)
            val dataObj = root.optJSONObject("data") ?: root
            val number = dataObj.optString("number")
                .ifBlank { dataObj.optString("phone") }
                .ifBlank { dataObj.optString("num") }
                .ifBlank { root.optString("number") }
                .trim()
            if (statusFalse || successFalse || number.isBlank()) {
                val err = root.optString("message")
                    .ifBlank { root.optString("error") }
                    .ifBlank { dataObj.optString("message") }
                    .ifBlank { "No number returned" }
                return NumberResponse(false, error = err)
            }
            val country = dataObj.optString("country")
                .ifBlank { dataObj.optString("label") }
                .ifBlank { root.optString("country") }
                .ifBlank { "Service" }
            val operator = dataObj.optString("operator")
                .ifBlank { root.optString("operator") }
                .ifBlank { getSelectedPanel().uppercase() }
            NumberResponse(true, NumberInfo(number, country, operator, rid))
        } catch (e: Exception) {
            NumberResponse(false, error = e.message ?: "Parse error")
        }
    }

    private fun extractOtpFromBody(body: String, targetNumber: String): String {
        return try {
            val trimmed = body.trim()
            val cleanTarget = targetNumber.replace(Regex("[^0-9]"), "")
            if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                val listArr = obj.optJSONArray("data") ?: obj.optJSONArray("messages") ?: obj.optJSONArray("otps")
                if (listArr != null) {
                    for (i in 0 until listArr.length()) {
                        val item = listArr.optJSONObject(i) ?: continue
                        val itemNum = item.optString("number").ifBlank { item.optString("phone") }.replace(Regex("[^0-9]"), "")
                        if (cleanTarget.isNotBlank() && itemNum.isNotBlank() && !itemNum.endsWith(cleanTarget.takeLast(8))) {
                            continue
                        }
                        val code = item.optString("otp").ifBlank { item.optString("code") }
                        if (code.matches(Regex("\\d{4,8}"))) return code
                        val sms = item.optString("sms").ifBlank { item.optString("message") }
                        val found = Regex("\\b(\\d{4,8})\\b").find(sms)?.groupValues?.get(1).orEmpty()
                        if (found.isNotBlank()) return found
                    }
                }
                val data = obj.optJSONObject("data") ?: obj
                val direct = data.optString("otp").ifBlank { data.optString("code") }
                if (direct.matches(Regex("\\d{4,8}"))) return direct
                val sms = data.optString("sms").ifBlank { data.optString("message") }
                Regex("\\b(\\d{4,8})\\b").find(sms)?.groupValues?.get(1) ?: ""
            } else if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val code = item.optString("otp").ifBlank { item.optString("code") }
                    if (code.matches(Regex("\\d{4,8}"))) return code
                    val sms = item.optString("sms").ifBlank { item.optString("message") }
                    val found = Regex("\\b(\\d{4,8})\\b").find(sms)?.groupValues?.get(1).orEmpty()
                    if (found.isNotBlank()) return found
                }
                ""
            } else {
                Regex("\\b(\\d{4,8})\\b").find(trimmed)?.groupValues?.get(1) ?: ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun isNumberUsedWithin24h(context: Context?, number: String): Boolean {
        val ctx = context ?: return false
        val clean = number.replace(Regex("[^0-9+]"), "").trim()
        if (clean.isBlank()) return false
        return try {
            val prefs = ctx.getSharedPreferences(USED_NUMBERS_PREFS, Context.MODE_PRIVATE)
            val ts = prefs.getLong(clean, 0L)
            if (ts <= 0L) return false
            val valid = (System.currentTimeMillis() - ts) < USED_NUMBER_TTL_MS
            if (!valid) prefs.edit().remove(clean).apply()
            valid
        } catch (_: Exception) {
            false
        }
    }

    private fun markNumberUsed24h(context: Context?, number: String) {
        val ctx = context ?: return
        val clean = number.replace(Regex("[^0-9+]"), "").trim()
        if (clean.isBlank()) return
        try {
            val prefs = ctx.getSharedPreferences(USED_NUMBERS_PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val editor = prefs.edit()
            for ((k, v) in prefs.all) {
                if (v is Long && now - v >= USED_NUMBER_TTL_MS) {
                    editor.remove(k)
                }
            }
            editor.putLong(clean, now).apply()
        } catch (_: Exception) {}
    }
}
