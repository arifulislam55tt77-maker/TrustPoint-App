package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

class FloatingBubbleService : Service() {

    companion object {
        private const val TAG = "FloatingBubbleService"
        private const val CHANNEL_ID = "tp_bg_service_channel"
        private const val NOTIFICATION_ID = 7701

        const val ACTION_REFRESH = "com.example.action.REFRESH_OVERLAY"
        const val ACTION_SHOW_OVERLAY = "com.example.action.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.example.action.HIDE_OVERLAY"
        const val ACTION_TOGGLE_AUTOMATION = "com.example.action.TOGGLE_AUTOMATION"
        const val ACTION_STOP_SERVICE = "com.example.action.STOP_BUBBLE_SERVICE"

        @Volatile
        private var activeServiceRef: WeakReference<FloatingBubbleService>? = null

        fun refreshOverlayIfRunning(context: Context) {
            try {
                val srv = activeServiceRef?.get() ?: return
                val snap = AutomationRuntimeState.state.value
                srv.serviceScope.launch {
                    srv.updateNotification(snap)
                    srv.updateOverlayViews(snap)
                }
            } catch (_: Throwable) {}
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateObserverJob: Job? = null

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var arrowToggleBtn: TextView? = null
    private var verticalMenuView: View? = null
    private var verticalMenuParams: WindowManager.LayoutParams? = null
    private var inputDialogView: View? = null

    private var bubbleStatusDot: View? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var isVerticalMenuExpanded = false
    private var foregroundStarted = false
    private var lastSubmittedUsername: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeServiceRef = WeakReference(this)
        createServiceChannel()
        foregroundStarted = startAsForegroundSafely(AutomationRuntimeState.state.value)
        if (!foregroundStarted) {
            AutomationRuntimeState.update(this) { it.copy(backgroundServiceRunning = false) }
            stopSelf()
            return
        }
        AutomationRuntimeState.update(this) { it.copy(backgroundServiceRunning = true) }
        AutomationRuntimeState.logExecution(this, TAG, "INFO", "Background foreground service started")

        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        observeRuntimeState()
        ensureOverlayAttached()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) {
            foregroundStarted = startAsForegroundSafely(AutomationRuntimeState.state.value)
            if (!foregroundStarted) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                AutomationRuntimeState.update(this) {
                    it.copy(floatingBubbleEnabled = false, backgroundServiceRunning = false)
                }
                AutomationRuntimeState.logExecution(this, TAG, "INFO", "Floating & background service stopped by user")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_HIDE_OVERLAY -> {
                hideVerticalMenu()
                dismissInputDialog()
                removeOverlayViews()
                AutomationRuntimeState.update(this) {
                    it.copy(floatingBubbleEnabled = false, backgroundServiceRunning = true)
                }
                updateNotification(AutomationRuntimeState.state.value)
                return START_STICKY
            }
            ACTION_SHOW_OVERLAY -> {
                try {
                    getSharedPreferences("ir_bg_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("floating_bubble_enabled", true).apply()
                } catch (_: Exception) {}
                AutomationRuntimeState.update(this) {
                    it.copy(floatingBubbleEnabled = true, backgroundServiceRunning = true)
                }
                ensureOverlayAttached()
                updateNotification(AutomationRuntimeState.state.value)
                return START_STICKY
            }
            ACTION_TOGGLE_AUTOMATION -> {
                val snap = AutomationRuntimeState.state.value
                if (snap.igAutoRunning) {
                    AutomationRuntimeState.requestStopFromOverlay(this)
                } else {
                    AutomationRuntimeState.requestStartFromOverlay(this)
                }
            }
            ACTION_REFRESH -> {
                updateNotification(AutomationRuntimeState.state.value)
                updateOverlayViews(AutomationRuntimeState.state.value)
            }
        }

        ensureOverlayAttached()
        return START_STICKY
    }

    override fun onDestroy() {
        if (activeServiceRef?.get() === this) {
            activeServiceRef = null
        }
        stateObserverJob?.cancel()
        serviceScope.cancel()
        removeOverlayViews()
        releaseWakeLock()
        AutomationRuntimeState.update(this) { it.copy(backgroundServiceRunning = false) }
        super.onDestroy()
    }

    private fun observeRuntimeState() {
        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            AutomationRuntimeState.state.collectLatest { snap ->
                updateNotification(snap)
                updateOverlayViews(snap)
                manageWakeLock(snap.igAutoRunning || snap.otpPollingActive)
            }
        }
    }

    private fun createServiceChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TrustPoint Background & Floating",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps TrustPoint automation, OTP polling, and floating controls active"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(snap: AutomationRuntimeState.Snapshot): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 100, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = ACTION_TOGGLE_AUTOMATION
        }
        val togglePending = PendingIntent.getService(
            this, 101, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPending = PendingIntent.getService(
            this, 102, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val autoStateLabel = when {
            !snap.igAutoEnabled -> "OFF"
            snap.igAutoRunning -> "Running"
            snap.otpPollingActive -> "Polling OTP"
            else -> "Ready"
        }
        val numText = snap.currentNumber.ifBlank { "-" }
        val otpText = snap.currentOtp.ifBlank { "-" }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("TrustPoint BG • Auto: $autoStateLabel")
            .setContentText("Num: $numText • OTP: $otpText • ${snap.igStepText}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(
                0,
                if (snap.igAutoRunning) "Stop Auto" else "Start Auto",
                togglePending
            )
            .addAction(0, "Stop BG", stopPending)
            .build()
    }

    private fun startAsForegroundSafely(snap: AutomationRuntimeState.Snapshot): Boolean {
        return try {
            val notification = buildNotification(snap)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed safely: ${t.javaClass.simpleName}: ${t.message}")
            AutomationRuntimeState.logExecution(
                this,
                TAG,
                "ERROR",
                "FG service blocked by system: ${t.message ?: t.javaClass.simpleName}"
            )
            false
        }
    }

    private fun updateNotification(snap: AutomationRuntimeState.Snapshot) {
        try {
            if (!foregroundStarted) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    return
                }
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.notify(NOTIFICATION_ID, buildNotification(snap))
        } catch (_: Throwable) {}
    }

    @SuppressLint("WakelockTimeout")
    private fun manageWakeLock(keepAwake: Boolean) {
        try {
            if (keepAwake) {
                if (wakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrustPoint:AutomationWakeLock")
                }
                if (wakeLock?.isHeld == false) {
                    wakeLock?.acquire(10 * 60 * 1000L)
                }
            } else {
                releaseWakeLock()
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    private fun canOverlay(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    private fun overlayLayoutType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun readClipboardText(): String {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
            val clip = cm.primaryClip ?: return ""
            if (clip.itemCount > 0) clip.getItemAt(0)?.coerceToText(this)?.toString().orEmpty().trim() else ""
        } catch (_: Exception) {
            ""
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureOverlayAttached() {
        val bubblePrefEnabled = try {
            getSharedPreferences("ir_bg_prefs", Context.MODE_PRIVATE)
                .getBoolean("floating_bubble_enabled", true)
        } catch (_: Exception) {
            AutomationRuntimeState.state.value.floatingBubbleEnabled
        }
        if (!bubblePrefEnabled || !AutomationRuntimeState.state.value.floatingBubbleEnabled) {
            removeOverlayViews()
            return
        }
        if (!canOverlay()) return
        val wm = windowManager ?: return
        if (bubbleView != null) return

        try {
            val bubbleSize = dp(52f)
            val rootCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }

            // Main circular TP badge
            val circleBox = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(bubbleSize, bubbleSize)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    colors = intArrayOf(Color.parseColor("#0F172A"), Color.parseColor("#1E1B4B"))
                    gradientType = GradientDrawable.LINEAR_GRADIENT
                    setStroke(dp(2f), Color.parseColor("#38BDF8"))
                }
                elevation = dp(8f).toFloat()
            }

            val label = TextView(this).apply {
                text = "TP"
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            circleBox.addView(
                label,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )

            val dotSize = dp(11f)
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#22C55E"))
                    setStroke(dp(1.5f), Color.parseColor("#0B1220"))
                }
            }
            circleBox.addView(
                dot,
                FrameLayout.LayoutParams(dotSize, dotSize).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = dp(3f)
                    marginEnd = dp(3f)
                }
            )
            bubbleStatusDot = dot

            // Attached downward arrow button directly under the TP bubble
            val arrowBtn = TextView(this).apply {
                text = "▾"
                setTextColor(Color.parseColor("#E0F2FE"))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                contentDescription = "Toggle Floating Buttons"
                background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(Color.parseColor("#0F172A"))
                    setStroke(dp(1.5f), Color.parseColor("#38BDF8"))
                }
                elevation = dp(6f).toFloat()
                layoutParams = LinearLayout.LayoutParams(dp(44f), dp(22f)).apply {
                    topMargin = dp(3f)
                }
                setOnClickListener { toggleVerticalMenu() }
            }
            arrowToggleBtn = arrowBtn

            rootCol.addView(circleBox)
            rootCol.addView(arrowBtn)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayLayoutType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(14f)
                y = dp(220f)
            }

            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var moved = false

            circleBox.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (abs(dx) > 10 || abs(dy) > 10) moved = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        try { wm.updateViewLayout(rootCol, params) } catch (_: Exception) {}
                        if (isVerticalMenuExpanded) repositionVerticalMenu()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            openMainApp()
                        } else {
                            val screenW = resources.displayMetrics.widthPixels
                            params.x = if (params.x + bubbleSize / 2 < screenW / 2) dp(12f) else screenW - bubbleSize - dp(12f)
                            try { wm.updateViewLayout(rootCol, params) } catch (_: Exception) {}
                            if (isVerticalMenuExpanded) repositionVerticalMenu()
                        }
                        true
                    }
                    else -> false
                }
            }

            wm.addView(rootCol, params)
            bubbleView = rootCol
            bubbleParams = params
            updateOverlayViews(AutomationRuntimeState.state.value)
        } catch (e: Exception) {
            Log.e(TAG, "ensureOverlayAttached error: ${e.message}")
        }
    }

    private fun toggleVerticalMenu() {
        if (!canOverlay()) return
        if (isVerticalMenuExpanded) hideVerticalMenu() else showVerticalMenu()
    }

    private fun showVerticalMenu() {
        val wm = windowManager ?: return
        if (verticalMenuView != null) {
            verticalMenuView?.visibility = View.VISIBLE
            isVerticalMenuExpanded = true
            arrowToggleBtn?.text = "▴"
            repositionVerticalMenu()
            updateOverlayViews(AutomationRuntimeState.state.value)
            return
        }

        val menuCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Color.parseColor("#F00B1220"))
                setStroke(dp(1.5f), Color.parseColor("#38BDF8"))
            }
            elevation = dp(10f).toFloat()
        }

        fun makeActionBtn(label: String, bgHex: String, strokeHex: String, topGapDp: Float = 0f, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 11.5f
                isAllCaps = false
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(10f), 0, dp(10f), 0)
                background = GradientDrawable().apply {
                    cornerRadius = dp(8f).toFloat()
                    setColor(Color.parseColor(bgHex))
                    setStroke(dp(1f), Color.parseColor(strokeHex))
                }
                layoutParams = LinearLayout.LayoutParams(dp(118f), dp(38f)).apply {
                    if (topGapDp > 0f) topMargin = dp(topGapDp)
                }
                setOnClickListener { onClick() }
            }
        }

        // Button 1: Login (Username/Number + Password -> 2FA Key)
        val b1 = makeActionBtn("🔑 Login", "#1D4ED8", "#60A5FA", 0f) {
            hideVerticalMenu()
            showUsernameLoginDialog()
        }

        // Button 2: Copy IG Cookies (pure Instagram cookies only)
        val b2 = makeActionBtn("🍪 Cookies", "#92400E", "#FBBF24", 6f) {
            hideVerticalMenu()
            val ck = AutomationRuntimeState.requestCopyIgCookiesFromOverlay(this@FloatingBubbleService)
            if (ck.isBlank()) {
                Toast.makeText(this@FloatingBubbleService, "⚠️ কোনো Instagram Cookies পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
            } else if (!ck.contains("sessionid=")) {
                Toast.makeText(this@FloatingBubbleService, "⚠ sessionid পাওয়া যায়নি — আগে IG লগইন করুন", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@FloatingBubbleService, "✓ IG Cookies Copied!", Toast.LENGTH_SHORT).show()
            }
        }

        // Button 3: Save logged-in account directly into Sheet & DB
        val b3 = makeActionBtn("💾 Save", "#15803D", "#4ADE80", 6f) {
            hideVerticalMenu()
            val savedUser = AutomationRuntimeState.requestSaveAccountFromOverlay(this@FloatingBubbleService)
            if (savedUser.isBlank()) {
                Toast.makeText(this@FloatingBubbleService, "⚠️ সেভ করার মতো কোনো অ্যাকাউন্ট পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@FloatingBubbleService, "✓ Sheet-এ সেভ হয়েছে: $savedUser", Toast.LENGTH_SHORT).show()
            }
        }

        // Button 4: Clear Web (wipes IG login session cleanly for next floating login, keeps Telegram safe)
        val b4 = makeActionBtn("🧹 Clear Web", "#B91C1C", "#FCA5A5", 6f) {
            hideVerticalMenu()
            lastSubmittedUsername = ""
            AutomationRuntimeState.requestClearWebFromOverlay(this@FloatingBubbleService)
            Toast.makeText(this@FloatingBubbleService, "🧹 Web Cleared! নতুন লগইন করতে পারেন", Toast.LENGTH_SHORT).show()
        }

        // Button 5 (bottom-most): Close & remove all floating buttons
        val b5 = makeActionBtn("✕ Close", "#7F1D1D", "#F87171", 6f) {
            closeAndRemoveFloatingService()
        }

        menuCol.addView(b1)
        menuCol.addView(b2)
        menuCol.addView(b3)
        menuCol.addView(b4)
        menuCol.addView(b5)

        val mParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            wm.addView(menuCol, mParams)
            verticalMenuView = menuCol
            verticalMenuParams = mParams
            isVerticalMenuExpanded = true
            arrowToggleBtn?.text = "▴"
            repositionVerticalMenu()
            updateOverlayViews(AutomationRuntimeState.state.value)
        } catch (e: Exception) {
            Log.e(TAG, "showVerticalMenu error: ${e.message}")
        }
    }

    private fun openMainApp() {
        hideVerticalMenu()
        dismissInputDialog()
        try {
            val i = Intent(this@FloatingBubbleService, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(i)
        } catch (e: Exception) {
            Log.e(TAG, "openMainApp error: ${e.message}")
        }
    }

    private fun closeAndRemoveFloatingService() {
        hideVerticalMenu()
        dismissInputDialog()
        try {
            getSharedPreferences("ir_bg_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("floating_bubble_enabled", false).apply()
        } catch (_: Exception) {}
        AutomationRuntimeState.update(this) {
            it.copy(floatingBubbleEnabled = false, backgroundServiceRunning = true)
        }
        AutomationRuntimeState.logExecution(this, TAG, "INFO", "Floating bubbles closed via ✕ button (BG keep-alive active)")
        removeOverlayViews()
        updateNotification(AutomationRuntimeState.state.value)
    }

    private fun repositionVerticalMenu() {
        val wm = windowManager ?: return
        val menu = verticalMenuView ?: return
        val mParams = verticalMenuParams ?: return
        val bParams = bubbleParams ?: return
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val menuW = dp(132f)
        val menuH = dp(236f)

        val targetX = if (bParams.x + menuW + dp(12f) <= screenW) {
            bParams.x
        } else {
            (screenW - menuW - dp(12f)).coerceAtLeast(dp(8f))
        }
        val belowY = bParams.y + dp(82f)
        val targetY = if (belowY + menuH <= screenH - dp(20f)) {
            belowY
        } else {
            (bParams.y - menuH - dp(8f)).coerceAtLeast(dp(24f))
        }

        mParams.x = targetX
        mParams.y = targetY
        try { wm.updateViewLayout(menu, mParams) } catch (_: Exception) {}
    }

    private fun hideVerticalMenu() {
        isVerticalMenuExpanded = false
        verticalMenuView?.visibility = View.GONE
        arrowToggleBtn?.text = "▾"
    }

    private fun dismissInputDialog() {
        val wm = windowManager ?: return
        inputDialogView?.let { v ->
            try {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(v.windowToken, 0)
            } catch (_: Exception) {}
            try { wm.removeView(v) } catch (_: Exception) {}
        }
        inputDialogView = null
    }

    private fun showUsernameLoginDialog() {
        if (!canOverlay()) return
        val wm = windowManager ?: return
        dismissInputDialog()

        val savedPass = AutomationRuntimeState.getActivityOrNull()?.readAutofillPassword().orEmpty()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(Color.parseColor("#F50B1220"))
                setStroke(dp(1.8f), Color.parseColor("#38BDF8"))
            }
            elevation = dp(16f).toFloat()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "🔑 Instagram Login"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#FCA5A5"))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10f), dp(4f), dp(4f), dp(4f))
            setOnClickListener { dismissInputDialog() }
        }
        header.addView(title)
        header.addView(closeBtn)
        card.addView(header)

        val userEdit = EditText(this).apply {
            hint = "Username / Number লিখুন..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 14f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(Color.parseColor("#162033"))
                setStroke(dp(1f), Color.parseColor("#475569"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10f) }
        }
        card.addView(userEdit)

        val passEdit = EditText(this).apply {
            hint = "Password লিখুন..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 13.5f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(Color.parseColor("#162033"))
                setStroke(dp(1f), Color.parseColor("#475569"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f) }
            // Prefill saved app password ONCE on dialog open; user can freely edit/replace it
            if (savedPass.isNotBlank()) setText(savedPass)
        }
        card.addView(passEdit)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12f) }
        }

        val pasteBtn = Button(this).apply {
            text = "📋 Paste"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dp(9f).toFloat()
                setColor(Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(42f), 0.9f).apply { marginEnd = dp(8f) }
            setOnClickListener {
                val clip = readClipboardText()
                if (clip.isNotBlank()) {
                    if (passEdit.hasFocus()) {
                        passEdit.setText(clip)
                        passEdit.setSelection(clip.length)
                    } else {
                        userEdit.setText(clip)
                        userEdit.setSelection(clip.length)
                    }
                } else {
                    Toast.makeText(this@FloatingBubbleService, "ক্লিপবোর্ড খালি", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val loginBtn = Button(this).apply {
            text = "🔐 Login"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dp(9f).toFloat()
                setColor(Color.parseColor("#16A34A"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(42f), 1.3f)
            setOnClickListener {
                val u = userEdit.text?.toString()?.trim().orEmpty()
                val p = passEdit.text?.toString()?.trim().orEmpty()
                if (u.isBlank()) {
                    Toast.makeText(this@FloatingBubbleService, "⚠️ Username লিখুন", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (p.isBlank()) {
                    Toast.makeText(this@FloatingBubbleService, "⚠️ Password লিখুন", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                lastSubmittedUsername = u
                AutomationRuntimeState.requestQuickLoginFromOverlay(this@FloatingBubbleService, u, p)
                dismissInputDialog()
                showTwoFactorKeyDialog(u)
            }
        }

        btnRow.addView(pasteBtn)
        btnRow.addView(loginBtn)
        card.addView(btnRow)

        val dParams = WindowManager.LayoutParams(
            dp(296f),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }

        try {
            wm.addView(card, dParams)
            inputDialogView = card
            userEdit.post {
                userEdit.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(userEdit, InputMethodManager.SHOW_IMPLICIT)
            }
        } catch (e: Exception) {
            Log.e(TAG, "showUsernameLoginDialog error: ${e.message}")
        }
    }

    private fun showTwoFactorKeyDialog(username: String) {
        if (!canOverlay()) return
        val wm = windowManager ?: return
        dismissInputDialog()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(Color.parseColor("#F50B1220"))
                setStroke(dp(1.8f), Color.parseColor("#FBBF24"))
            }
            elevation = dp(16f).toFloat()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "🛡️ 2FA Authentication Key"
            setTextColor(Color.parseColor("#FDE68A"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#FCA5A5"))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10f), dp(4f), dp(4f), dp(4f))
            setOnClickListener { dismissInputDialog() }
        }
        header.addView(title)
        header.addView(closeBtn)
        card.addView(header)

        val userSub = TextView(this).apply {
            text = "Account: ${username.ifBlank { lastSubmittedUsername.ifBlank { "-" } }}"
            setTextColor(Color.parseColor("#93C5FD"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4f) }
        }
        card.addView(userSub)

        val liveCodeTv = TextView(this).apply {
            text = "2FA Key দিন — অটো ৬ ডিজিট কোড বসবে"
            setTextColor(Color.parseColor("#86EFAC"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4f) }
        }
        card.addView(liveCodeTv)

        val keyEdit = EditText(this).apply {
            hint = "2FA Key বা 6-digit কোড লিখুন..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 13.5f
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(Color.parseColor("#162033"))
                setStroke(dp(1f), Color.parseColor("#475569"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f) }

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val v = s?.toString()?.trim().orEmpty()
                    if (Regex("^\\d{6}$").matches(v)) {
                        liveCodeTv.text = "Code: $v"
                    } else {
                        val gen = generateTotpCode(v)
                        liveCodeTv.text = if (gen.isNotBlank()) "Live Code: $gen" else "2FA Key দিন — অটো ৬ ডিজিট কোড বসবে"
                    }
                }
            })
        }
        card.addView(keyEdit)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12f) }
        }

        val pasteBtn = Button(this).apply {
            text = "📋 Paste"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dp(9f).toFloat()
                setColor(Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(42f), 0.9f).apply { marginEnd = dp(8f) }
            setOnClickListener {
                val clip = readClipboardText()
                if (clip.isNotBlank()) {
                    keyEdit.setText(clip)
                    keyEdit.setSelection(clip.length)
                } else {
                    Toast.makeText(this@FloatingBubbleService, "ক্লিপবোর্ড খালি", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val submitBtn = Button(this).apply {
            text = "✅ Continue"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dp(9f).toFloat()
                setColor(Color.parseColor("#0284C7"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(42f), 1.4f)
            setOnClickListener {
                val k = keyEdit.text?.toString()?.trim().orEmpty()
                if (k.isBlank()) {
                    Toast.makeText(this@FloatingBubbleService, "⚠️ Authentication Key লিখুন", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                AutomationRuntimeState.requestSubmitTwoFactorFromOverlay(this@FloatingBubbleService, k)
                dismissInputDialog()
                Toast.makeText(this@FloatingBubbleService, "✅ 6-digit কোড দিয়ে Continue ক্লিক হচ্ছে...", Toast.LENGTH_SHORT).show()
            }
        }

        btnRow.addView(pasteBtn)
        btnRow.addView(submitBtn)
        card.addView(btnRow)

        val dParams = WindowManager.LayoutParams(
            dp(296f),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }

        try {
            wm.addView(card, dParams)
            inputDialogView = card
            keyEdit.post {
                keyEdit.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(keyEdit, InputMethodManager.SHOW_IMPLICIT)
            }
        } catch (e: Exception) {
            Log.e(TAG, "showTwoFactorKeyDialog error: ${e.message}")
        }
    }

    private fun removeOverlayViews() {
        val wm = windowManager ?: return
        try { bubbleView?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { verticalMenuView?.let { wm.removeView(it) } } catch (_: Exception) {}
        dismissInputDialog()
        bubbleView = null
        verticalMenuView = null
    }

    private fun updateOverlayViews(snap: AutomationRuntimeState.Snapshot) {
        val dotColor = when {
            !snap.igAutoEnabled -> Color.parseColor("#EF4444")
            snap.igAutoRunning -> Color.parseColor("#22C55E")
            snap.otpPollingActive -> Color.parseColor("#F59E0B")
            else -> Color.parseColor("#38BDF8")
        }
        (bubbleStatusDot?.background as? GradientDrawable)?.setColor(dotColor)
    }

    private fun generateTotpCode(base32Key: String): String {
        return try {
            val otpAuthSecret = Regex("secret=([A-Za-z2-7]+)", RegexOption.IGNORE_CASE).find(base32Key)?.groupValues?.getOrNull(1)
            val cleaned = (otpAuthSecret ?: base32Key).uppercase(Locale.US).replace(Regex("[^A-Z2-7]"), "")
            if (cleaned.isEmpty()) return ""
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            var bits = 0
            var value = 0
            val out = java.io.ByteArrayOutputStream()
            for (c in cleaned) {
                val idx = alphabet.indexOf(c)
                if (idx < 0) continue
                value = (value shl 5) or idx
                bits += 5
                if (bits >= 8) {
                    out.write((value ushr (bits - 8)) and 0xFF)
                    bits -= 8
                }
            }
            val keyBytes = out.toByteArray()
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
            String.format(Locale.US, "%06d", binary % 1000000)
        } catch (_: Exception) {
            ""
        }
    }
}
