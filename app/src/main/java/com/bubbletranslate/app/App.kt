package com.bubbletranslate.app

import android.app.Application
import com.bubbletranslate.app.api.GoogleTranslateClient
import com.bubbletranslate.app.api.KimiApiClient
import com.bubbletranslate.app.util.ScreenshotManager

class App : Application() {

    val kimiApiClient = KimiApiClient()
    val googleTranslateClient = GoogleTranslateClient()
    val screenshotManager by lazy { ScreenshotManager(this) }

    // ==================== 远程翻译（Kimi LLM）配置 ====================
    var apiKey: String = ""
    var apiBaseUrl: String = "https://api.moonshot.cn/v1"
    var model: String = "kimi-k2.6"

    // ==================== 翻译模式配置 ====================
    /** 用户选择的翻译模式：local（Google 免费翻译）或 remote（Kimi LLM） */
    var translationMode: String = TRANSLATION_MODE_REMOTE
    /** 目标语言（用于 Google 本地翻译） */
    var targetLanguage: String = "zh-CN"

    // ==================== UI 配置 ====================
    var bubbleAlpha: Int = 80  // 0-100, default 80%

    // Hand-off from MainActivity to FloatingBubbleService.
    // Stored here (in-process, no IPC serialization) instead of Intent
    // extras because the Binder token inside the MediaProjection data
    // Intent can be lost during Intent→Parcel→AMS→Parcel→Intent round-trip.
    var pendingResultCode: Int = 0
    var pendingResultData: android.content.Intent? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Enable vector drawable inflation from Service contexts (LifecycleService
        // uses LayoutInflater which can't resolve android:src for vector XML
        // drawables without this flag)
        androidx.appcompat.app.AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)

        val prefs = getSharedPreferences("bubble_translate", MODE_PRIVATE)
        // 远程 LLM 配置
        apiKey = prefs.getString("api_key", "") ?: ""
        apiBaseUrl = prefs.getString("api_base_url", "https://api.moonshot.cn/v1") ?: "https://api.moonshot.cn/v1"
        model = prefs.getString("model", "kimi-k2.6") ?: "kimi-k2.6"
        // 翻译模式配置
        translationMode = prefs.getString("translation_mode", TRANSLATION_MODE_REMOTE) ?: TRANSLATION_MODE_REMOTE
        targetLanguage = prefs.getString("target_language", "zh-CN") ?: "zh-CN"
        // UI 配置
        bubbleAlpha = prefs.getInt("bubble_alpha", 80)
    }

    // ==================== 持久化方法 ====================

    fun saveApiKey(key: String) {
        apiKey = key
        getSharedPreferences("bubble_translate", MODE_PRIVATE)
            .edit()
            .putString("api_key", key)
            .apply()
    }

    fun saveApiBaseUrl(url: String) {
        apiBaseUrl = url
        getSharedPreferences("bubble_translate", MODE_PRIVATE)
            .edit()
            .putString("api_base_url", url)
            .apply()
    }

    fun saveModel(model: String) {
        this.model = model
        getSharedPreferences("bubble_translate", MODE_PRIVATE)
            .edit()
            .putString("model", model)
            .apply()
    }

    fun saveTranslationMode(mode: String) {
        translationMode = mode
        getSharedPreferences("bubble_translate", MODE_PRIVATE)
            .edit()
            .putString("translation_mode", mode)
            .apply()
    }

    fun saveTargetLanguage(lang: String) {
        targetLanguage = lang
        getSharedPreferences("bubble_translate", MODE_PRIVATE)
            .edit()
            .putString("target_language", lang)
            .apply()
    }

    fun saveBubbleAlpha(alpha: Int) {
        bubbleAlpha = alpha
        getSharedPreferences("bubble_translate", MODE_PRIVATE)
            .edit()
            .putInt("bubble_alpha", alpha)
            .apply()
    }

    // ==================== 工具方法 ====================

    /**
     * 获取实际生效的翻译模式
     *
     * 规则：
     * 1. 用户选择了 local → 直接 local
     * 2. 用户选择了 remote 但未配置 API Key → 自动降级 local
     * 3. 用户选择了 remote 且已配置 API Key → remote
     */
    fun getEffectiveMode(): String {
        if (translationMode == TRANSLATION_MODE_LOCAL) {
            return TRANSLATION_MODE_LOCAL
        }
        // remote 模式但未配置 Key → 自动降级
        if (apiKey.isBlank()) {
            return TRANSLATION_MODE_LOCAL
        }
        return TRANSLATION_MODE_REMOTE
    }

    /**
     * 当前是否是本地翻译模式
     */
    fun isLocalMode(): Boolean = getEffectiveMode() == TRANSLATION_MODE_LOCAL

    /**
     * 当前是否是远程 LLM 翻译模式
     */
    fun isRemoteMode(): Boolean = getEffectiveMode() == TRANSLATION_MODE_REMOTE

    companion object {
        lateinit var instance: App
            private set

        // 翻译模式常量
        const val TRANSLATION_MODE_LOCAL = "local"
        const val TRANSLATION_MODE_REMOTE = "remote"
    }
}
