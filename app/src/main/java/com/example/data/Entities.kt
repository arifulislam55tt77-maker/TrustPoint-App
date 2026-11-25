package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "saved_accounts")
data class SavedAccountEntity(
    @PrimaryKey val uid: String,
    val password: String,
    val cookies: String,
    val hotmail: String,
    val twofa: String,
    val number: String,
    val isLive: Boolean = true,
    val savedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "meta_accounts")
data class MetaAccountEntity(
    @PrimaryKey val phone: String,
    val password: String,
    val twofa: String,
    val status: String = "fresh", // fresh, used, broken
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "otp_history",
    indices = [Index(value = ["number", "otp"], unique = false)]
)
data class OtpHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val otp: String,
    val country: String = "",
    val source: String = "panel",
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "automation_states")
data class AutomationStateEntity(
    @PrimaryKey val automationKey: String, // e.g., "ig_automation", "otp_polling", "floating_bubble"
    val isEnabled: Boolean = true,
    val isRunning: Boolean = false,
    val statusMessage: String = "Idle",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "execution_logs")
data class ExecutionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tag: String,
    val level: String, // INFO, WARN, ERROR, SUCCESS
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)
