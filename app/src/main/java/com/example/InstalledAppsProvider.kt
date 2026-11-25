package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

object InstalledAppsProvider {

    data class AppTarget(
        val packageName: String,
        val label: String
    )

    private val KNOWN_TARGET_PACKAGES = listOf(
        "com.instagram.android",
        "com.facebook.katana",
        "org.telegram.messenger",
        "com.android.chrome"
    )

    fun getInstalledTargetApps(context: Context): List<AppTarget> {
        val pm = context.packageManager
        val found = mutableListOf<AppTarget>()
        for (pkg in KNOWN_TARGET_PACKAGES) {
            try {
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(pkg, 0)
                }
                val label = pm.getApplicationLabel(appInfo).toString()
                found.add(AppTarget(pkg, label))
            } catch (_: Exception) {
                // Not installed or package visibility restricted
            }
        }
        return found
    }

    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun canResolveUrl(context: Context, url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.resolveActivity(context.packageManager) != null
        } catch (_: Exception) {
            false
        }
    }
}
