package com.bubbletranslate.app.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Google 免费翻译 API 客户端
 *
 * 使用 translate.googleapis.com 的公开接口，无需 API Key。
 * 仅按文本翻译，不处理图片。
 * 适用于文本已提取后的翻译步骤。
 */
class GoogleTranslateClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "GTClient"
        private const val API_URL = "https://translate.googleapis.com/translate_a/single"

        // Google Translate 支持的语言代码映射
        private val LANG_MAP = mapOf(
            "zh-CN" to "zh-CN",
            "zh-TW" to "zh-TW",
            "en" to "en",
            "ja" to "ja",
            "ko" to "ko",
            "fr" to "fr",
            "de" to "de",
            "es" to "es",
            "pt" to "pt",
            "ru" to "ru",
            "ar" to "ar",
            "th" to "th",
            "vi" to "vi",
            "id" to "id",
            "ms" to "ms",
            "tl" to "tl",
            "hi" to "hi",
            "bn" to "bn",
            "it" to "it",
            "nl" to "nl",
            "pl" to "pl",
            "tr" to "tr",
            "uk" to "uk"
        )
    }

    /**
     * 翻译文本
     *
     * @param text 要翻译的文本（非空）
     * @param targetLanguage 目标语言代码，默认 zh-CN
     * @return 翻译后的文本
     * @throws GoogleTranslateException 翻译失败时抛出
     */
    suspend fun translateText(
        text: String,
        targetLanguage: String = "zh-CN"
    ): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            throw GoogleTranslateException("翻译文本不能为空")
        }

        val target = LANG_MAP[targetLanguage] ?: "zh-CN"
        val encoded = URLEncoder.encode(text, "UTF-8")
        val url = "$API_URL?client=gtx&sl=auto&tl=$target&dt=t&q=$encoded"

        Log.d(TAG, "translateText: text(length=${text.length}), target=$target")

        try {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                throw GoogleTranslateException(
                    "Google 翻译服务返回错误: HTTP ${response.code}",
                    response.code
                )
            }

            val rawBody = response.body?.string() ?: throw GoogleTranslateException("响应体为空")
            Log.d(TAG, "translateText: raw response=${rawBody.take(200)}")

            // 解析 Google Translate API 返回的 JSON 格式
            // 格式: [[["翻译结果","原文",null,null,1]], ...]
            val json = JSONArray(rawBody)
            if (json.length() == 0) {
                throw GoogleTranslateException("翻译结果解析失败：空数组")
            }

            val sentences = json.optJSONArray(0)
                ?: throw GoogleTranslateException("翻译结果解析失败：缺少句子数组")

            val parts = mutableListOf<String>()
            for (i in 0 until sentences.length()) {
                val sentence = sentences.optJSONArray(i)
                if (sentence != null && sentence.length() > 0) {
                    val translated = sentence.optString(0, "")
                    if (translated.isNotEmpty()) {
                        parts.add(translated)
                    }
                }
            }

            if (parts.isEmpty()) {
                throw GoogleTranslateException("翻译结果为空")
            }

            return@withContext parts.joinToString("").trim()
        } catch (e: GoogleTranslateException) {
            throw e
        } catch (e: IOException) {
            Log.e(TAG, "translateText: Network error", e)
            throw GoogleTranslateException("网络错误：无法连接到 Google 翻译服务，请检查网络连接")
        } catch (e: Exception) {
            Log.e(TAG, "translateText: Error", e)
            throw GoogleTranslateException("翻译失败: ${e.message}")
        }
    }
}

/**
 * Google Translate 客户端自定义异常
 */
class GoogleTranslateException(
    message: String,
    val httpCode: Int = 0
) : Exception(message)
