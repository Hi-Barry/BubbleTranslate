package com.bubbletranslate.app

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bubbletranslate.app.databinding.ActivityMainBinding
import com.bubbletranslate.app.service.FloatingBubbleService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isServiceRunning = false

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // After returning from overlay permission settings, check if granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            // Defer launch to the next main-thread message so it happens AFTER
            // onResume() completes.  Calling launch() inside an Activity Result
            // callback causes the deferred launch to execute post-onResume,
            // which silently drops the result on many AndroidX versions.
            binding.root.post { requestMediaProjection() }
        } else {
            updateUiFromServiceState()
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startServiceWithMediaProjection(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Screen capture permission required for translation", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.apiKeyInput.setText(App.instance.apiKey)
        binding.apiBaseUrlInput.setText(App.instance.apiBaseUrl)
        binding.modelInput.setText(App.instance.model)

        // ==================== 翻译模式切换 ====================
        initTranslationModeUI()

        // Bubble opacity
        val alpha = App.instance.bubbleAlpha
        binding.opacitySeekBar.progress = maxOf(0, alpha - 10)
        binding.opacityValueText.text = "${alpha}%"
        binding.opacitySeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + 10
                binding.opacityValueText.text = "${value}%"
                if (fromUser) {
                    App.instance.saveBubbleAlpha(value)
                    FloatingBubbleService.instance?.updateBubbleAlpha()
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.toggleButton.setOnClickListener {
            if (isServiceRunning) {
                stopTranslationService()
            } else {
                startTranslationFlow()
            }
        }

        updateUiFromServiceState()
    }

    override fun onResume() {
        super.onResume()
        // Defer the instance check to let any pending service-creation
        // message (posted by startForegroundService Binder IPC) run first.
        // See updateUiFromServiceState() doc for the race-condition details.
        binding.root.post { updateUiFromServiceState() }
    }

    /**
     * Sync [isServiceRunning] from the service singleton and update the UI.
     *
     * IMPORTANT: Do NOT call this directly from onResume() — use onResume()
     * which posts it.  The reason is a message-queue race condition:
     *
     *   startForegroundService() is a Binder IPC that merely *posts* the
     *   service-creation message to the main-thread queue.  It returns
     *   BEFORE the service's onCreate() runs.  The main-thread message
     *   queue is: [callback] → [onResume] → [onCreate].  If we check
     *   instance in onResume, it's still null — the service hasn't been
     *   created yet.  Posting the check gives onCreate a chance to run
     *   first.
     */
    private fun updateUiFromServiceState() {
        isServiceRunning = FloatingBubbleService.instance != null
        applyUiState()
    }

    /**
     * Apply the current [isServiceRunning] state to all UI elements.
     * Pure UI — does NOT re-read the service instance.
     */
    private fun applyUiState() {
        if (isServiceRunning) {
            binding.toggleButton.text = getString(R.string.stop_service)
            binding.toggleButton.backgroundTintList = getColorStateList(android.R.color.holo_red_dark)
            (binding.statusDot.background as? GradientDrawable)?.setColor(0xFF4CAF50.toInt())
            binding.statusText.text = getString(R.string.status_running)
        } else {
            binding.toggleButton.text = getString(R.string.start_service)
            binding.toggleButton.backgroundTintList = getColorStateList(R.color.primary)
            (binding.statusDot.background as? GradientDrawable)?.setColor(0xFFE0E0E0.toInt())
            binding.statusText.text = getString(R.string.status_stopped)
        }
    }

    /**
     * 初始化翻译模式切换 UI
     */
    private fun initTranslationModeUI() {
        val isLocal = App.instance.translationMode == App.TRANSLATION_MODE_LOCAL

        // 设置按钮样式（选中态 filled，未选中态 outlined）
        updateModeButtonStyles(isLocal)

        // 更新提示文字
        updateModeHint(isLocal, App.instance.apiKey)

        binding.modeLocalBtn.setOnClickListener {
            if (App.instance.translationMode == App.TRANSLATION_MODE_LOCAL) return@setOnClickListener
            App.instance.saveTranslationMode(App.TRANSLATION_MODE_LOCAL)
            updateModeButtonStyles(true)
            updateModeHint(true, App.instance.apiKey)
        }

        binding.modeRemoteBtn.setOnClickListener {
            if (App.instance.translationMode == App.TRANSLATION_MODE_REMOTE) return@setOnClickListener
            App.instance.saveTranslationMode(App.TRANSLATION_MODE_REMOTE)
            updateModeButtonStyles(false)
            updateModeHint(false, App.instance.apiKey)
        }
    }

    /**
     * 更新模式按钮的填充状态
     */
    private fun updateModeButtonStyles(isLocal: Boolean) {
        if (isLocal) {
            binding.modeLocalBtn.setBackgroundColor(getColor(R.color.primary))
            binding.modeLocalBtn.setTextColor(getColor(android.R.color.white))
            binding.modeRemoteBtn.setBackgroundColor(0x00000000) // transparent
            binding.modeRemoteBtn.setTextColor(getColor(R.color.text_primary))
        } else {
            binding.modeRemoteBtn.setBackgroundColor(getColor(R.color.primary))
            binding.modeRemoteBtn.setTextColor(getColor(android.R.color.white))
            binding.modeLocalBtn.setBackgroundColor(0x00000000) // transparent
            binding.modeLocalBtn.setTextColor(getColor(R.color.text_primary))
        }
    }

    /**
     * 更新模式提示文字
     */
    private fun updateModeHint(isLocal: Boolean, apiKey: String) {
        binding.modeHintText.text = if (isLocal) {
            getString(R.string.mode_hint_local)
        } else if (apiKey.isBlank()) {
            getString(R.string.mode_hint_remote_no_key)
        } else {
            getString(R.string.mode_hint_remote_with_key)
        }
    }

    private fun startTranslationFlow() {
        // 本地模式：不需要 API Key
        val isLocalMode = App.instance.isLocalMode()

        if (!isLocalMode) {
            // 远程模式：检查 API Key
            val apiKey = binding.apiKeyInput.text?.toString()?.trim() ?: ""
            if (apiKey.isBlank()) {
                Toast.makeText(this, R.string.error_api_key, Toast.LENGTH_LONG).show()
                return
            }
            App.instance.saveApiKey(apiKey)
        }

        // 保存 API Key（即使在本地模式也保存用户可能输入的内容）
        val apiKey = binding.apiKeyInput.text?.toString()?.trim() ?: ""
        if (apiKey.isNotBlank()) App.instance.saveApiKey(apiKey)

        val baseUrl = binding.apiBaseUrlInput.text?.toString()?.trim() ?: ""
        if (baseUrl.isNotBlank()) App.instance.saveApiBaseUrl(baseUrl)

        val model = binding.modelInput.text?.toString()?.trim() ?: ""
        if (model.isNotBlank()) App.instance.saveModel(model)

        // Check overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            Toast.makeText(this, R.string.permission_overlay_required, Toast.LENGTH_LONG).show()
            return
        }

        // Request media projection
        requestMediaProjection()
    }

    private fun requestMediaProjection() {
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(intent)
    }

    private fun startServiceWithMediaProjection(resultCode: Int, data: Intent) {
        // Hand off resultCode and data via the Application singleton (in-process,
        // no IPC serialization).  Passing them through Intent extras loses the
        // Binder token during the Intent→Parcel→AMS→Parcel→Intent round-trip.
        App.instance.pendingResultCode = resultCode
        App.instance.pendingResultData = data

        val intent = Intent(this, FloatingBubbleService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("BT", "Failed to start foreground service: ${e.message}", e)
            App.instance.pendingResultData = null
            updateUiFromServiceState()
            Toast.makeText(this, "Failed to start service: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        // startForegroundService() returns before the service's onCreate()
        // runs — the service-creation is posted to the main-thread message
        // queue.  Post our state check after it so FloatingBubbleService.instance
        // has been set.
        binding.root.post {
            updateUiFromServiceState()
            if (isServiceRunning) {
                Toast.makeText(this, "Bubble activated! Switch to any app and tap the bubble.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopTranslationService() {
        FloatingBubbleService.instance?.stopService()
        isServiceRunning = false
        applyUiState()
    }
}
