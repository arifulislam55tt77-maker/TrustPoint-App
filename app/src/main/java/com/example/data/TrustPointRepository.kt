package com.example.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrustPointRepository(context: Context) {
    private val db = AppDatabase.getInstance(context.applicationContext)
    private val savedDao = db.savedAccountDao()
    private val metaDao = db.metaAccountDao()
    private val otpDao = db.otpHistoryDao()
    private val stateDao = db.automationStateDao()
    private val logDao = db.executionLogDao()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val savedAccountsFlow: Flow<List<SavedAccountEntity>> = savedDao.observeAll()
    val metaAccountsFlow: Flow<List<MetaAccountEntity>> = metaDao.observeAll()
    val otpHistoryFlow: Flow<List<OtpHistoryEntity>> = otpDao.observeRecent()
    val automationStatesFlow: Flow<List<AutomationStateEntity>> = stateDao.observeAll()
    val executionLogsFlow: Flow<List<ExecutionLogEntity>> = logDao.observeRecent()

    fun saveAccountAsync(account: SavedAccountEntity) {
        ioScope.launch {
            try {
                savedDao.upsert(account)
            } catch (_: Exception) {}
        }
    }

    fun updateAccountLiveAsync(uid: String, isLive: Boolean) {
        ioScope.launch {
            try {
                savedDao.updateLiveStatus(uid, isLive)
            } catch (_: Exception) {}
        }
    }

    suspend fun getAllSavedAccountsSync(): List<SavedAccountEntity> {
        return try {
            savedDao.getAll()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun deleteAccountAsync(uid: String) {
        ioScope.launch {
            try {
                savedDao.deleteByUid(uid)
            } catch (_: Exception) {}
        }
    }

    fun clearSavedAccountsAsync() {
        ioScope.launch {
            try {
                savedDao.clearAll()
            } catch (_: Exception) {}
        }
    }

    fun syncMetaPoolAsync(list: List<MetaAccountEntity>) {
        ioScope.launch {
            try {
                metaDao.clearAll()
                if (list.isNotEmpty()) {
                    metaDao.upsertAll(list)
                }
            } catch (_: Exception) {}
        }
    }

    fun updateMetaStatusAsync(phone: String, status: String) {
        ioScope.launch {
            try {
                metaDao.updateStatus(phone, status)
            } catch (_: Exception) {}
        }
    }

    suspend fun recordOtpIfUnique(number: String, otp: String, country: String, source: String = "panel"): Boolean {
        return try {
            val windowMs = System.currentTimeMillis() - 60_000L
            val existing = otpDao.countRecentDuplicate(number, otp, windowMs)
            if (existing > 0) return false
            otpDao.insert(
                OtpHistoryEntity(
                    number = number,
                    otp = otp,
                    country = country,
                    source = source,
                    timestamp = System.currentTimeMillis()
                )
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getOtpHistoryJson(): String {
        return try {
            val list = otpDao.getRecent()
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
            val arr = JSONArray()
            list.forEach { item ->
                arr.put(JSONObject().apply {
                    put("otp", item.otp)
                    put("number", item.number)
                    put("country", item.country)
                    put("source", item.source)
                    put("time", fmt.format(Date(item.timestamp)))
                    put("timestamp", item.timestamp)
                })
            }
            arr.toString()
        } catch (_: Exception) {
            "[]"
        }
    }

    fun clearOtpHistoryAsync() {
        ioScope.launch {
            try {
                otpDao.clearAll()
            } catch (_: Exception) {}
        }
    }

    fun saveAutomationStateAsync(key: String, enabled: Boolean, running: Boolean, message: String) {
        ioScope.launch {
            try {
                stateDao.upsert(
                    AutomationStateEntity(
                        automationKey = key,
                        isEnabled = enabled,
                        isRunning = running,
                        statusMessage = message,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } catch (_: Exception) {}
        }
    }

    suspend fun getAutomationState(key: String): AutomationStateEntity? {
        return try {
            stateDao.getByKey(key)
        } catch (_: Exception) {
            null
        }
    }

    fun log(tag: String, level: String, message: String) {
        ioScope.launch {
            try {
                logDao.insert(
                    ExecutionLogEntity(
                        tag = tag,
                        level = level,
                        message = message,
                        timestamp = System.currentTimeMillis()
                    )
                )
                logDao.pruneOld()
            } catch (_: Exception) {}
        }
    }

    suspend fun getExecutionLogsJson(): String {
        return try {
            val logs = logDao.getRecent()
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
            val arr = JSONArray()
            logs.forEach { l ->
                arr.put(JSONObject().apply {
                    put("id", l.id)
                    put("tag", l.tag)
                    put("level", l.level)
                    put("message", l.message)
                    put("time", fmt.format(Date(l.timestamp)))
                    put("timestamp", l.timestamp)
                })
            }
            arr.toString()
        } catch (_: Exception) {
            "[]"
        }
    }

    fun clearLogsAsync() {
        ioScope.launch {
            try {
                logDao.clearAll()
            } catch (_: Exception) {}
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: TrustPointRepository? = null

        fun getInstance(context: Context): TrustPointRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TrustPointRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
