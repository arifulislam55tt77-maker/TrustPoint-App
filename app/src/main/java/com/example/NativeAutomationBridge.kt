package com.example

import android.webkit.JavascriptInterface

/**
 * JS bridge exposed as `window.Android` inside nativeWebView for Instagram DOM scripts.
 */
class NativeAutomationBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun onUsernameGenerated(username: String) {
        activity.webInterface.onUsernameGenerated(username)
    }

    @JavascriptInterface
    fun onUserIdSelected(userId: String) {
        activity.webInterface.onUsernameGenerated(userId)
    }

    @JavascriptInterface
    fun onPasswordGenerated(password: String) {
        activity.webInterface.onPasswordGenerated(password)
    }

    @JavascriptInterface
    fun onAccountMarkedUsed(reason: String) {
        activity.runOnUiThread {
            activity.onAccountMarkedUsed(reason)
        }
    }

    @JavascriptInterface
    fun onAccountMarkedBroken(reason: String) {
        activity.runOnUiThread {
            val phone = activity.currentNumber.ifBlank { activity.allocatedNumber }
            if (phone.isNotBlank()) {
                activity.markMetaAccountBroken(phone, reason)
            }
            activity.stopAutomationFromOverlay()
        }
    }

    @JavascriptInterface
    fun onAccountSkip(reason: String, @Suppress("UNUSED_PARAMETER") isNoProfile: Boolean) {
        activity.runOnUiThread {
            activity.onAccountMarkedUsed("skip_$reason")
            activity.stopAutomationFromOverlay()
        }
    }

    @JavascriptInterface
    fun onAutomationStopped(reason: String) {
        activity.runOnUiThread {
            activity.onAutomationStopped(reason)
        }
    }

    @JavascriptInterface
    fun onIgAutomationComplete() {
        activity.webInterface.onIgAutomationComplete()
    }

    @JavascriptInterface
    fun notifyAutoDone() {
        activity.runOnUiThread {
            try {
                activity.markIgAutomationComplete()
            } catch (_: Exception) {}
        }
    }

    @JavascriptInterface
    fun showToast(msg: String) {
        activity.toast(msg)
    }

    @JavascriptInterface
    fun getSavedPassword(): String = activity.readAutofillPassword()

    @JavascriptInterface
    fun getCurrentPhone(): String = activity.getCurrentPhone()

    @JavascriptInterface
    fun get2FACode(): String = activity.generateTotp(activity.current2FAKey)
}
