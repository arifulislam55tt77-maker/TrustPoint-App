package com.example

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

// ═══════════════════════════════════════════════════════════════════════
//  SAFE PROFILE PHOTO & FILE UPLOAD (Built-in Avatars + Photo Picker)
// ═══════════════════════════════════════════════════════════════════════

private val BUILT_IN_AVATARS = intArrayOf(
    R.drawable.img_avatar_01,
    R.drawable.img_avatar_02,
    R.drawable.img_avatar_03,
    R.drawable.img_avatar_04,
    R.drawable.img_avatar_05
)

internal fun MainActivity.prepareBuiltInAvatarUri(index: Int? = null): Uri? {
    return try {
        val chosenIndex = index?.takeIf { it in BUILT_IN_AVATARS.indices }
            ?: BUILT_IN_AVATARS.indices.random()
        val resId = BUILT_IN_AVATARS[chosenIndex]

        val cacheDir = File(this.cacheDir, "upload_cache").apply { if (!exists()) mkdirs() }
        // Clean up older temporary upload files (> 10)
        cacheDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach {
            try { it.delete() } catch (_: Exception) {}
        }

        val outFile = File(cacheDir, "profile_avatar_${System.currentTimeMillis()}.jpg")
        val bmp = BitmapFactory.decodeResource(resources, resId)
        if (bmp != null) {
            FileOutputStream(outFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                fos.flush()
            }
            bmp.recycle()
        } else {
            resources.openRawResource(resId).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
        }
        FileProvider.getUriForFile(this, "${packageName}.fileprovider", outFile)
    } catch (e: Throwable) {
        Log.e(MainActivity.TAG, "prepareBuiltInAvatarUri error: ${e.message}")
        null
    }
}

internal fun MainActivity.copyContentUriToUploadCache(srcUri: Uri): Uri {
    return try {
        if (srcUri.scheme == "content" && srcUri.authority == "${packageName}.fileprovider") {
            return srcUri
        }
        val cacheDir = File(this.cacheDir, "upload_cache").apply { if (!exists()) mkdirs() }
        val mime = try { contentResolver.getType(srcUri).orEmpty() } catch (_: Exception) { "" }
        val ext = when {
            mime.contains("png", ignoreCase = true) -> "png"
            mime.contains("webp", ignoreCase = true) -> "webp"
            mime.contains("mp4", ignoreCase = true) -> "mp4"
            else -> "jpg"
        }
        val destFile = File(cacheDir, "picked_${System.currentTimeMillis()}.$ext")
        val copied = contentResolver.openInputStream(srcUri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
                output.flush()
            }
            destFile.length() > 0L
        } ?: false

        if (copied) {
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", destFile)
        } else {
            srcUri
        }
    } catch (e: Throwable) {
        Log.w(MainActivity.TAG, "copyContentUriToUploadCache fallback to original uri: ${e.message}")
        srcUri
    }
}

internal fun MainActivity.deliverActiveFileChooserResult(uris: Array<Uri>?) {
    isPickingFileOrPhoto = false
    val safeUris = uris?.map { copyContentUriToUploadCache(it) }?.toTypedArray()

    val mainCb = filePathCallback
    filePathCallback = null
    if (mainCb != null) {
        try { mainCb.onReceiveValue(safeUris) } catch (_: Throwable) {}
    }

    val nativeCb = nativeFilePathCallback
    nativeFilePathCallback = null
    if (nativeCb != null) {
        try { nativeCb.onReceiveValue(safeUris) } catch (_: Throwable) {}
    }
}

internal fun MainActivity.launchSystemPhotoPickerOrFiles(
    params: WebChromeClient.FileChooserParams?,
    isMainWebView: Boolean
) {
    if (isFinishing || isDestroyed) {
        deliverActiveFileChooserResult(null)
        return
    }
    try {
        isPickingFileOrPhoto = true
        val acceptTypes = params?.acceptTypes
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?.toTypedArray()
            ?: emptyArray()

        val wantsOnlyImages = acceptTypes.isEmpty() || acceptTypes.all {
            it.startsWith("image/") || it.equals(".jpg", true) || it.equals(".jpeg", true) ||
                it.equals(".png", true) || it.equals(".webp", true) || it == "*/*"
        }

        if (wantsOnlyImages && hasPickVisualMediaLauncher()) {
            try {
                pickVisualMediaLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
                return
            } catch (e: Throwable) {
                Log.w(MainActivity.TAG, "PickVisualMedia fallback: ${e.message}")
            }
        }

        val primaryMime = when {
            acceptTypes.isEmpty() -> "image/*"
            acceptTypes.size == 1 && acceptTypes[0].contains("/") -> acceptTypes[0]
            else -> "*/*"
        }

        val getContentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = primaryMime
            if (acceptTypes.size > 1) {
                putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes)
            }
            if (params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(getContentIntent, "Select Image").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val reqCode = if (isMainWebView) {
            MainActivity.FILE_CHOOSER_REQUEST_CODE
        } else {
            MainActivity.NATIVE_FILE_CHOOSER_REQUEST_CODE
        }

        @Suppress("DEPRECATION")
        startActivityForResult(chooser, reqCode)
    } catch (e: Throwable) {
        Log.e(MainActivity.TAG, "launchSystemPhotoPickerOrFiles error: ${e.message}")
        toast("❌ ফটো পিকার খোলা যায়নি")
        deliverActiveFileChooserResult(null)
    }
}

internal fun MainActivity.showSafeProfileUploadChooser(
    callback: ValueCallback<Array<Uri>>?,
    params: WebChromeClient.FileChooserParams?,
    isMainWebView: Boolean
) {
    if (isFinishing || isDestroyed) {
        try { callback?.onReceiveValue(null) } catch (_: Throwable) {}
        return
    }

    // Cancel any stale pending callback before binding the new one
    try {
        if (isMainWebView) {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback
        } else {
            nativeFilePathCallback?.onReceiveValue(null)
            nativeFilePathCallback = callback
        }
    } catch (_: Throwable) {}

    val acceptTypes = params?.acceptTypes?.map { it.trim().lowercase() } ?: emptyList()
    val isImageRequest = acceptTypes.isEmpty() || acceptTypes.any {
        it.contains("image") || it == "*/*" || it.contains("jpg") || it.contains("png")
    }

    // Non-image files skip built-in avatar dialog
    if (!isImageRequest) {
        launchSystemPhotoPickerOrFiles(params, isMainWebView)
        return
    }

    try {
        var resolved = false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(18f), dpToPx(16f), dpToPx(18f), dpToPx(14f))
            setBackgroundColor(Color.parseColor("#111827"))
        }

        val title = TextView(this).apply {
            text = "প্রোফাইল পিকচার আপলোড"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        }
        val sub = TextView(this).apply {
            text = "বিল্ট-ইন প্রোফাইল ছবি সিলেক্ট করুন অথবা গ্যালারি থেকে নিন:"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12.5f
            setPadding(0, dpToPx(4f), 0, dpToPx(12f))
        }
        root.addView(title)
        root.addView(sub)

        var dialogRef: AlertDialog? = null

        val avatarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(12f) }
        }

        BUILT_IN_AVATARS.forEachIndexed { idx, resId ->
            val img = ImageView(this).apply {
                setImageResource(resId)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Built-in profile picture ${idx + 1}"
                val size = dpToPx(54f)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = if (idx < BUILT_IN_AVATARS.lastIndex) dpToPx(6f) else 0
                }
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(10f).toFloat()
                    setStroke(dpToPx(2f), Color.parseColor("#38BDF8"))
                }
                setPadding(dpToPx(2f), dpToPx(2f), dpToPx(2f), dpToPx(2f))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    clipToOutline = true
                }
                setOnClickListener {
                    if (resolved) return@setOnClickListener
                    resolved = true
                    val uri = prepareBuiltInAvatarUri(idx)
                    dialogRef?.dismiss()
                    if (uri != null) {
                        AutomationRuntimeState.logExecution(
                            this@showSafeProfileUploadChooser,
                            "ProfileUpload",
                            "SUCCESS",
                            "Selected built-in profile photo #${idx + 1}"
                        )
                        deliverActiveFileChooserResult(arrayOf(uri))
                    } else {
                        deliverActiveFileChooserResult(null)
                    }
                }
            }
            avatarRow.addView(img)
        }
        root.addView(avatarRow)

        fun makeActionBtn(label: String, bgHex: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 13.5f
                isAllCaps = false
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(10f).toFloat()
                    setColor(Color.parseColor(bgHex))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(46f)
                ).apply { topMargin = dpToPx(8f) }
                setOnClickListener { onClick() }
            }
        }

        val randomBtn = makeActionBtn("🎲 Random Built-in Photo (1-Tap)", "#15803D") {
            if (resolved) return@makeActionBtn
            resolved = true
            val uri = prepareBuiltInAvatarUri(null)
            dialogRef?.dismiss()
            if (uri != null) {
                AutomationRuntimeState.logExecution(
                    this, "ProfileUpload", "SUCCESS", "Selected random built-in profile photo"
                )
                deliverActiveFileChooserResult(arrayOf(uri))
            } else {
                deliverActiveFileChooserResult(null)
            }
        }

        val galleryBtn = makeActionBtn("🖼️ Device Gallery (Photo Picker)", "#1D4ED8") {
            if (resolved) return@makeActionBtn
            resolved = true
            dialogRef?.dismiss()
            launchSystemPhotoPickerOrFiles(params, isMainWebView)
        }

        val cancelBtn = makeActionBtn("✕ Cancel", "#374151") {
            if (resolved) return@makeActionBtn
            resolved = true
            dialogRef?.dismiss()
            deliverActiveFileChooserResult(null)
        }

        root.addView(randomBtn)
        root.addView(galleryBtn)
        root.addView(cancelBtn)

        val dlg = AlertDialog.Builder(this)
            .setView(root)
            .setCancelable(true)
            .setOnCancelListener {
                if (!resolved) {
                    resolved = true
                    deliverActiveFileChooserResult(null)
                }
            }
            .create()
        dialogRef = dlg
        dlg.show()
    } catch (e: Throwable) {
        Log.e(MainActivity.TAG, "showSafeProfileUploadChooser error: ${e.message}")
        val fallbackUri = prepareBuiltInAvatarUri(null)
        if (fallbackUri != null) {
            deliverActiveFileChooserResult(arrayOf(fallbackUri))
        } else {
            deliverActiveFileChooserResult(null)
        }
    }
}
