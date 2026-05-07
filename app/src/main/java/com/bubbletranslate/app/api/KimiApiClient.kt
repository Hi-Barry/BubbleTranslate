package com.bubbletranslate.app.api

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.bubbletranslate.app.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class KimiApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)  // shorter — user shouldn't wait 2 min
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Returns the configured API endpoint URL.
     * Uses the base URL from App preferences + /chat/completions path.
     */
    private fun apiUrl(): String {
        val baseUrl = App.instance.apiBaseUrl.trimEnd('/')
        return "$baseUrl/chat/completions"
    }

    suspend fun translateImage(
        apiKey: String,
        bitmap: Bitmap,
        onChunk: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val base64 = bitmapToBase64(bitmap)
            val imageUrl = "data:image/jpeg;base64,$base64"

            val imageContent = JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", imageUrl)
                })
            }

            val textContent = JSONObject().apply {
                put("type", "text")
                put("text", "请识别图片中的所有文字，将其翻译为简体中文。直接输出翻译结果，不要任何解释说明。如果没有文字，回复\"无文字\"。")
            }

            val userContent = JSONArray().apply {
                put(imageContent)
                put(textContent)
            }

            val messages = JSONArray().apply {
                // Kimi K2.6: system content must be a plain string, not an object
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are a professional translator. Recognize all text in the image and translate it to Simplified Chinese. If already Chinese, keep as-is. Output only the translation, no explanations.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userContent)
                })
            }

            val requestBody = JSONObject().apply {
                put("model", App.instance.model)
                put("messages", messages)
                put("stream", false)
                put("max_tokens", 4096)
                put("thinking", JSONObject().apply {
                    put("type", "disabled")
                })
            }

            val url = apiUrl()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody(jsonMediaType))
                .build()

            Log.d("BT", "translateImage: POST $url")
            val response = client.newCall(request).execute()
            Log.d("BT", "translateImage: response code=${response.code}")

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.w("BT", "translateImage: API error ${response.code}: $errorBody")
                onError("API error ${response.code}: $errorBody")
                return@withContext
            }

            val body = response.body ?: run {
                onError("Empty response body")
                return@withContext
            }

            // Parse non-streaming JSON response
            val rawBody = body.string()
            Log.d("BT", "translateImage: raw response length=${rawBody.length}")

            try {
                val json = JSONObject(rawBody)
                val choices = json.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val message = choices.getJSONObject(0).optJSONObject("message")
                    val content = message?.optString("content") ?: ""
                    if (content.isNotEmpty()) {
                        onChunk(content)
                        onComplete()
                    } else {
                        onError("Empty translation content in response")
                    }
                } else {
                    onError("No choices in API response")
                }
            } catch (e: Exception) {
                Log.e("BT", "translateImage: JSON parse error", e)
                onError("Failed to parse API response: ${e.message}")
            }

        } catch (e: IOException) {
            Log.e("BT", "translateImage: Network error", e)
            onError("Network error: ${e.message}")
        } catch (e: Exception) {
            Log.e("BT", "translateImage: Error", e)
            onError("Error: ${e.message}")
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos)
        val bytes = baos.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
