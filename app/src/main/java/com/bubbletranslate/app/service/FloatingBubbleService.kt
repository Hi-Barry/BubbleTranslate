package com.bubbletranslate.app.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.bubbletranslate.app.App
import com.bubbletranslate.app.MainActivity
import com.bubbletranslate.app.R
import com.bubbletranslate.app.api.GoogleTranslateException
import com.bubbletranslate.app.service.BubbleView.State
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import android.util.Log

/**
 * Manages a single [BubbleView] window that morphs through three states:
 *
 *   IDLE (small bubble)  ⇄  SELECT (full-screen selection)  →  TRANSLATE (panel)
 *                           ↑                                     │
 *                           └────────── cancel ────────────────────┘
 *
 * Only one window overlay exists at any time.  State transitions that
 * require different [WindowManager.LayoutParams.flags] use a remove +
 * re-add cycle; size-only changes use [WindowManager.updateViewLayout].
 */
class FloatingBubbleService : LifecycleService() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var isTranslating = false

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val crashLogFile by lazy { java.io.File(filesDir, "crash_log.txt") }

    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        val msg = "CoroutineExceptionHandler: ${e.javaClass.name}: ${e.message}\n${e.stackTraceToString()}"
        Log.e("BT", msg)
        try { crashLogFile.appendText(
            "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $msg\n\n"
        ) } catch (_: Exception) {}
        mainHandler.post {
            transitionToTranslate(stateText = "Error: ${e.message}", isStreaming = false)
        }
    }
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null

    private var displayManager: DisplayManager? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                mainHandler.post { onScreenRotated() }
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "bubble_translate_channel"
        const val NOTIFICATION_ID = 1

        var instance: FloatingBubbleService? = null
            private set

        const val ACTION_STOP = "com.bubbletranslate.app.STOP"
    }

    // ── Lifecycle ────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        displayManager?.registerDisplayListener(displayListener, mainHandler)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            onStartCommandInternal(intent, flags, startId)
        } catch (t: Throwable) {
            Log.e("BT", "💥 onStartCommand CRASHED: ${t.javaClass.name}: ${t.message}", t)
            try {
                crashLogFile.appendText(
                    "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} "
                            + "onStartCommand CRASH: ${t.stackTraceToString()}\n\n"
                )
            } catch (_: Exception) {}
            // Try to call startForeground anyway — prevents ANR kill
            try { startForegroundCompat(NOTIFICATION_ID, buildNotification()) } catch (_: Exception) {}
            START_NOT_STICKY
        }
    }

    private fun onStartCommandInternal(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("BT", "onStartCommand intent=$intent action=${intent?.action} flags=$flags")

        if (intent == null) {
            // System restarted the service after killing it (START_STICKY).
            // MediaProjection tokens are tied to the process and are lost —
            // there's no way to recover.  Kill the zombie service so the
            // user gets a clear signal to reopen the app and tap Start.
            Log.w("BT", "onStartCommand with null intent — service was restarted by system. "
                    + "Stopping zombie service (MediaProjection lost).")
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_STOP) {
            stopService()
            return START_NOT_STICKY
        }

        Log.d("BT", "onStartCommand: calling startForeground...")
        startForegroundCompat(NOTIFICATION_ID, buildNotification())
        Log.d("BT", "onStartCommand: ✅ startForeground done")

        // MediaProjection result is handed off via the Application singleton
        // (in-process memory, no IPC serialization) to avoid Binder token
        // loss during Intent→Parcel→AMS→Parcel→Intent round-trip.
        // MUST be called AFTER startForeground() — some OEM ROMs enforce
        // foreground service type checks inside getMediaProjection().
        val resultCode = App.instance.pendingResultCode
        val data = App.instance.pendingResultData
        App.instance.pendingResultCode = 0       // consume once
        App.instance.pendingResultData = null

        Log.d("BT", "onStartCommand: resultCode=$resultCode (RESULT_OK=${Activity.RESULT_OK}, RESULT_CANCELED=${Activity.RESULT_CANCELED}), data=${if (data != null) "present" else "NULL"}")

        if (resultCode == Activity.RESULT_OK && data != null) {
            Log.d("BT", "onStartCommand: ✅ resultCode OK, creating MediaProjection...")
            try {
                val mp = mediaProjectionManager?.getMediaProjection(resultCode, data)
                mediaProjection = mp
                App.instance.screenshotManager.setMediaProjection(mp)
                Log.d("BT", "onStartCommand: ✅ MediaProjection created: ${if (mp != null) "success" else "NULL"}")
            } catch (e: Exception) {
                Log.e("BT", "onStartCommand: ❌ getMediaProjection() threw: ${e.javaClass.name}: ${e.message}", e)
            }
        } else {
            Log.w("BT", "No MediaProjection data in App singleton — captureScreen will fail")
        }

        Log.d("BT", "onStartCommand: calling showBubble...")
        showBubble()
        Log.d("BT", "onStartCommand: ✅ showBubble done")
        return START_NOT_STICKY  // don't auto-restart — MediaProjection would be lost
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        instance = null
        serviceScope.cancel()
        removeWindow()
        displayManager?.unregisterDisplayListener(displayListener)
        App.instance.screenshotManager.cleanup()
        mediaProjection?.stop()
        super.onDestroy()
    }

    // ── Notification ─────────────────────────────────────────────

    /**
     * Wrapper around [startForeground] that declares the correct
     * service type for Android 14+ (U+), which requires
     * [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION].
     * Falls back to the no-type overload on older SDKs.
     */
    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(id, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bubble Translate",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the translation bubble"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.status_running))
            .setSmallIcon(R.drawable.ic_bubble)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop_service), pendingStop)
            .setContentIntent(pendingOpen)
            .build()
    }

    // ── Window management ────────────────────────────────────────

    /**
     * Common layout flags for SELECT and TRANSLATE states.
     * IDLE uses a different set ([idleFlags]).
     */
    private val overlayFlags: Int
        get() = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

    private val idleFlags: Int
        get() = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private val windowType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private val dm: DisplayMetrics get() = resources.displayMetrics

    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    /** Called by [displayListener] when the screen rotates. */
    private fun onScreenRotated() {
        val view = bubbleView ?: return
        val newW = dm.widthPixels
        val newH = dm.heightPixels
        val statusBarH = getStatusBarHeight()

        when (view.bubbleState) {
            State.IDLE -> {
                val bubbleSize = (56 * dm.density).toInt()
                view.storedX = view.storedX.coerceIn(0, (newW - bubbleSize).coerceAtLeast(0))
                view.storedY = view.storedY.coerceIn(statusBarH, (newH - bubbleSize).coerceAtLeast(statusBarH))
                bubbleParams?.apply {
                    x = view.storedX
                    y = view.storedY
                    try { windowManager.updateViewLayout(view, this) } catch (_: Exception) {}
                }
            }
            State.TRANSLATE -> {
                val panelWidth = (newW * 0.88).toInt()
                view.panelX = view.panelX.coerceIn(0, (newW - panelWidth).coerceAtLeast(0))
                view.panelY = view.panelY.coerceIn(statusBarH, (newH - 100).coerceAtLeast(statusBarH))
                bubbleParams?.apply {
                    width = panelWidth
                    x = view.panelX
                    y = view.panelY
                    try { windowManager.updateViewLayout(view, this) } catch (_: Exception) {
                        // Fallback: re-add with new params if update fails
                        try {
                            windowManager.removeView(view)
                            windowManager.addView(view, this)
                        } catch (_: Exception) {}
                    }
                }
            }
            State.SELECT -> { /* full-screen, no adjustment needed */ }
        }
    }

    /** Create and show the bubble in IDLE state. */
    private fun showBubble() {
        if (bubbleView != null) return

        val view = BubbleView(this)
        bubbleView = view
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType, idleFlags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = view.storedX
            y = view.storedY
        }

        // ── callbacks ────────────────────────────────────────────
        view.onPositionRequest = { x, y ->
            val statusBarH = getStatusBarHeight()
            val bubbleSize = (56 * dm.density).toInt()
            val clampedX = x.coerceIn(0, (dm.widthPixels - bubbleSize).coerceAtLeast(0))
            val clampedY = y.coerceIn(statusBarH, (dm.heightPixels - bubbleSize).coerceAtLeast(statusBarH))
            view.storedX = clampedX
            view.storedY = clampedY
            bubbleParams?.apply {
                this.x = clampedX
                this.y = clampedY
                try { windowManager.updateViewLayout(view, this) } catch (_: Exception) {}
            }
        }

        view.onBubbleTap = {
            if (!isTranslating) {
                Log.d("BT", "Bubble tapped → transition to SELECT")
                transitionToSelect()
            }
        }

        view.onSelectionComplete = { left, top, right, bottom ->
            val sw = kotlin.math.abs(right - left)
            val sh = kotlin.math.abs(bottom - top)
            if (sw > 20 && sh > 20) {
                Log.d("BT", "Selection complete ($left,$top)-($right,$bottom)")
                processSelection(
                    kotlin.math.min(left, right).toInt(),
                    kotlin.math.min(top, bottom).toInt(),
                    kotlin.math.max(left, right).toInt(),
                    kotlin.math.max(top, bottom).toInt()
                )
            } else {
                Log.d("BT", "Selection too small, back to IDLE")
                transitionToIdle()
            }
        }

        view.onSelectionCancel = {
            Log.d("BT", "Selection cancelled")
            transitionToIdle()
        }

        view.onDismissToIdle = {
            Log.d("BT", "Dismiss to IDLE")
            transitionToIdle()
        }

        view.onPanelDrag = { x, y ->
            val statusBarH = getStatusBarHeight()
            val panelW = (dm.widthPixels * 0.88).toInt()
            val clampedX = x.coerceIn(0, (dm.widthPixels - panelW).coerceAtLeast(0))
            val clampedY = y.coerceIn(statusBarH, (dm.heightPixels - 100).coerceAtLeast(statusBarH))
            view.panelX = clampedX
            view.panelY = clampedY
            bubbleParams?.apply {
                this.x = clampedX
                this.y = clampedY
                try { windowManager.updateViewLayout(view, this) } catch (_: Exception) {}
            }
        }

        windowManager.addView(view, bubbleParams)
        Log.d("BT", "BubbleView added (IDLE)")
    }

    /** Remove the bubbleView from the window manager. */
    private fun removeWindow() {
        bubbleView?.let { v ->
            try { windowManager.removeView(v) } catch (_: Exception) {}
        }
        bubbleView = null
        bubbleParams = null
    }

    /**
     * Re-add the same [bubbleView] with new [LayoutParams].
     * Necessary when flags change (IDLE ⇄ overlay states).
     */
    private fun reAddWindow(params: WindowManager.LayoutParams) {
        val view = bubbleView ?: return
        try { windowManager.removeView(view) } catch (_: Exception) {}
        bubbleParams = params
        windowManager.addView(view, params)
    }

    // ── State transitions ────────────────────────────────────────

    private fun transitionToSelect() {
        val view = bubbleView ?: return
        // Save current position before going full-screen
        view.storedX = bubbleParams?.x ?: view.storedX
        view.storedY = bubbleParams?.y ?: view.storedY

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType, overlayFlags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }

        view.setState(State.SELECT)
        reAddWindow(params)
    }

    private fun transitionToTranslate(stateText: String, isStreaming: Boolean) {
        val view = bubbleView ?: return

        val panelWidth = (dm.widthPixels * 0.88).toInt()

        // Initialize panel position to center-upper on first show
        if (view.panelX == 0 && view.panelY == 0) {
            view.panelX = (dm.widthPixels - panelWidth) / 2
            view.panelY = dm.heightPixels / 4
        }

        val params = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType, overlayFlags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = view.panelX
            y = view.panelY
        }

        view.setPanelText(stateText)
        view.setPanelTitleText(
            if (isStreaming) getString(R.string.translating)
            else getString(R.string.translation_result)
        )
        view.setState(State.TRANSLATE)

        // SELECT and TRANSLATE share flags → updateViewLayout is sufficient
        bubbleParams = params
        try { windowManager.updateViewLayout(view, params) } catch (e: Exception) {
            // Fallback: remove + re-add
            reAddWindow(params)
        }
    }

    private fun transitionToIdle() {
        val view = bubbleView ?: return
        view.setState(State.IDLE)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType, idleFlags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = view.storedX
            y = view.storedY
        }

        reAddWindow(params)
    }

    // ── Translation pipeline ─────────────────────────────────────

    private fun processSelection(left: Int, top: Int, right: Int, bottom: Int) {
        if (isTranslating) return
        isTranslating = true

        // Post to the View's message queue so the selection drawing
        // is fully composited before we capture the screenshot.
        // Capture FIRST, then show "translating…" — this prevents
        // the popup from appearing in the screenshot sent to the LLM.
        bubbleView?.post {
            serviceScope.launch {
            try {
                val fullBitmap = App.instance.screenshotManager.captureScreen()
                if (fullBitmap == null) {
                    transitionToError(
                        "Screen capture failed: MediaProjection not available.\n\n"
                                + "Please open the app and tap Start to re-enable screen capture."
                    )
                    isTranslating = false
                    return@launch
                }

                val cl = left.coerceIn(0, fullBitmap.width - 1)
                val ct = top.coerceIn(0, fullBitmap.height - 1)
                val cr = right.coerceIn(1, fullBitmap.width)
                val cb = bottom.coerceIn(1, fullBitmap.height)
                if (cr <= cl || cb <= ct) {
                    fullBitmap.recycle()
                    transitionToError("Invalid selection area.")
                    isTranslating = false
                    return@launch
                }

                val cropped = try {
                    Bitmap.createBitmap(fullBitmap, cl, ct, cr - cl, cb - ct)
                } catch (e: Exception) {
                    fullBitmap.recycle()
                    transitionToError("Failed to crop image.")
                    isTranslating = false
                    return@launch
                }
                fullBitmap.recycle()

                // ── Determine effective translation mode ──
                val isLocal = App.instance.isLocalMode()

                if (!isLocal) {
                    // ==================== 远程模式：Kimi LLM ====================
                    val apiKey = App.instance.apiKey
                    if (apiKey.isBlank()) {
                        transitionToError(getString(R.string.error_api_key))
                        isTranslating = false
                        return@launch
                    }

                    transitionToTranslate(
                        stateText = getString(R.string.translating),
                        isStreaming = true
                    )

                    App.instance.kimiApiClient.translateImage(
                        apiKey = apiKey,
                        bitmap = cropped,
                        onChunk = { chunk ->
                            mainHandler.post { appendTranslationContent(chunk) }
                        },
                        onComplete = {
                            mainHandler.post {
                                cropped.recycle()
                                markTranslationComplete()
                                isTranslating = false
                            }
                        },
                        onError = { error ->
                            mainHandler.post {
                                cropped.recycle()
                                updateTranslationContent(error)
                                isTranslating = false
                            }
                        }
                    )
                } else {
                    // ==================== 本地模式：OCR + Google 翻译 ====================
                    transitionToTranslate(
                        stateText = getString(R.string.translating),
                        isStreaming = true
                    )

                    mainHandler.post { appendTranslationContent("🔍 Recognizing text…\n") }

                    try {
                        // Step 1: ML Kit OCR 文字识别（后台线程执行）
                        val recognizedText = withContext(Dispatchers.IO) {
                            val inputImage = InputImage.fromBitmap(cropped, 0)
                            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                            val ocrResult = Tasks.await(recognizer.process(inputImage), 15, java.util.concurrent.TimeUnit.SECONDS)
                            recognizer.close()
                            ocrResult.text.trim()
                        }

                        if (recognizedText.isBlank()) {
                            mainHandler.post {
                                cropped.recycle()
                                transitionToError(getString(R.string.no_text_found))
                                isTranslating = false
                            }
                            return@launch
                        }

                        mainHandler.post {
                            appendTranslationContent("📝 $recognizedText\n\n")
                            appendTranslationContent("🌐 Translating via Google…")
                        }

                        // Step 2: Google Translate 翻译
                        val targetLang = App.instance.targetLanguage
                        val translation = App.instance.googleTranslateClient.translateText(
                            text = recognizedText,
                            targetLanguage = targetLang
                        )

                        mainHandler.post {
                            cropped.recycle()
                            transitionToTranslate(
                                stateText = translation,
                                isStreaming = false
                            )
                            markTranslationComplete()
                            isTranslating = false
                        }
                    } catch (e: com.bubbletranslate.app.api.GoogleTranslateException) {
                        mainHandler.post {
                            cropped.recycle()
                            updateTranslationContent(e.message ?: "Google translation failed")
                            isTranslating = false
                        }
                    } catch (e: Exception) {
                        Log.e("BT", "Local translation error", e)
                        mainHandler.post {
                            cropped.recycle()
                            updateTranslationContent("Local translation: ${e.message}")
                            isTranslating = false
                        }
                    }
                }
            } catch (t: Throwable) {
                val msg = "processSelection crash: ${t.javaClass.name}: ${t.message}\n${t.stackTraceToString()}"
                Log.e("BT", msg)
                try { crashLogFile.appendText(
                    "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $msg\n\n"
                ) } catch (_: Exception) {}
                updateTranslationContent("Error: ${t.message}")
                isTranslating = false
            }
        }  // serviceScope.launch
    }  // bubbleView?.post
    }

    /** Show error text in the TRANSLATE state panel. */
    private fun transitionToError(text: String) {
        transitionToTranslate(
            stateText = text,
            isStreaming = false
        )
    }

    // ── Translation text helpers ─────────────────────────────────

    private fun appendTranslationContent(chunk: String) {
        val view = bubbleView ?: return
        view.setPanelTitleText(getString(R.string.translation_result))
        view.appendPanelText(chunk)
    }

    private fun updateTranslationContent(text: String) {
        val view = bubbleView ?: return
        view.setPanelTitleText(getString(R.string.translation_result))
        view.setPanelText(text)
    }

    private fun markTranslationComplete() {
        val view = bubbleView ?: return
        view.setPanelTitleText(getString(R.string.translation_result))
    }

    // ── Public helpers ───────────────────────────────────────────

    fun stopService() {
        removeWindow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun hasMediaProjection(): Boolean = mediaProjection != null

    /** Update bubble opacity from App pref. Call from MainActivity when slider changes. */
    fun updateBubbleAlpha() {
        bubbleView?.updateBubbleAlpha()
    }
}
