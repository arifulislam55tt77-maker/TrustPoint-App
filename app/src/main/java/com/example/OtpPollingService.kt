package com.example

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class OtpPollingService : Service() {

    companion object {
        private const val TAG = "OtpPollingService"
        private const val CHANNEL_ID = "tp_otp_polling_channel"
        private const val NOTIFICATION_ID = 7702

        const val ACTION_START = "com.example.action.START_OTP_POLLING"
        const val ACTION_STOP = "com.example.action.STOP_OTP_POLLING"
        const val EXTRA_NUMBER = "extra_number"

        fun startSafely(context: Context, number: String) {
            try {
                val intent = Intent(context, OtpPollingService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_NUMBER, number)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "OtpPollingService start blocked safely: ${t.message}")
            }
        }

        fun stopSafely(context: Context) {
            try {
                context.stopService(Intent(context, OtpPollingService::class.java))
            } catch (_: Throwable) {}
        }
    }

    private var foregroundStarted = false
    private var activeNumber = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        foregroundStarted = startAsForegroundSafely("Waiting for OTP...")
        if (!foregroundStarted) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) {
            foregroundStarted = startAsForegroundSafely("Waiting for OTP...")
            if (!foregroundStarted) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        when (intent?.action) {
            ACTION_STOP -> {
                StexApiService.stopOtpPolling()
                AutomationRuntimeState.logExecution(this, TAG, "INFO", "OTP polling service stopped")
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                activeNumber = intent?.getStringExtra(EXTRA_NUMBER).orEmpty()
                updateNotification(
                    if (activeNumber.isNotBlank()) "Polling OTP for $activeNumber" else "OTP polling active"
                )
            }
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OTP Polling Status",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 201, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, OtpPollingService::class.java).apply { action = ACTION_STOP }
        val pendingStop = PendingIntent.getService(
            this, 202, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("TrustPoint OTP Monitor")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingOpen)
            .addAction(0, "Stop", pendingStop)
            .build()
    }

    private fun startAsForegroundSafely(text: String): Boolean {
        return try {
            val notification = buildNotification(text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "startAsForegroundSafely failed: ${t.message}")
            false
        }
    }

    private fun updateNotification(text: String) {
        try {
            if (!foregroundStarted) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Throwable) {}
    }
}
