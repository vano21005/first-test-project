package com.durakhelper

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Помощник с AI-интеграцией (GigaChat или OpenAI).
 * Опционально: работает только если задан API-ключ.
 */
class AiHelper(
    private val apiType: ApiType,
    private val apiKey: String
) {
    enum class ApiType { GIGACHAT, OPENAI }

    private val client: OkHttpClient by lazy {
        if (apiType == ApiType.GIGACHAT) {
            buildUnsafeClient()
        } else {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }

    /** Получить совет от AI на основе текущего состояния игры. */
    fun getAdvice(gameState: GameState, callback: (String) -> Unit) {
        Thread {
            try {
                val prompt = buildPrompt(gameState)
                val response = when (apiType) {
                    ApiType.GIGACHAT -> callGigaChat(prompt)
                    ApiType.OPENAI -> callOpenAI(prompt)
                }
                callback(response)
            } catch (e: Exception) {
                callback("Ошибка AI: ${e.message}")
            }
        }.start()
    }

    private fun buildPrompt(gameState: GameState): String {
        val stats = gameState.getStats()
        val myCardsStr = gameState.myCards.joinToString(", ") { it.displayName }
        val tableCardsStr = if (gameState.tableCards.isEmpty()) "пусто"
            else gameState.tableCards.joinToString(", ") { it.displayName }
        val trumpStr = gameState.trumpSuit.displayName

        return """
            Ты — эксперт по карточной игре "Дурак" (36 карт). Помоги выиграть.
            
            Козырь: $trumpStr
            Мои карты: $myCardsStr
            На столе: $tableCardsStr
            Сброшено карт: ${stats.discardedCount}
            Неизвестных карт: ${stats.remainingUnknown}
            Моих козырей: ${stats.myTrumps}
            Козырей у противников (макс.): ${stats.unknownTrumps}
            
            Дай краткий совет: какой картой лучше ходить/отбиваться и почему.
            Ответ на русском, кратко (2-3 предложения).
        """.trimIndent()
    }

    private fun callGigaChat(prompt: String): String {
        // Получение токена доступа
        val tokenRequest = Request.Builder()
            .url("https://ngw.devices.sberbank.ru:9443/api/v2/oauth")
            .post(
                "scope=GIGACHAT_API_PERS"
                    .toRequestBody("application/x-www-form-urlencoded".toMediaType())
            )
            .addHeader("Authorization", "Basic $apiKey")
            .addHeader("RqUID", java.util.UUID.randomUUID().toString())
            .build()

        val tokenResponse = client.newCall(tokenRequest).execute()
        val tokenJson = JSONObject(tokenResponse.body?.string() ?: "")
        val accessToken = tokenJson.getString("access_token")

        // Запрос к GigaChat
        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val body = JSONObject().apply {
            put("model", "GigaChat-2-Lite")
            put("messages", messagesArray)
        }

        val chatRequest = Request.Builder()
            .url("https://gigachat.devices.sberbank.ru/api/v1/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        val chatResponse = client.newCall(chatRequest).execute()
        val chatJson = JSONObject(chatResponse.body?.string() ?: "")
        return chatJson
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }

    private fun callOpenAI(prompt: String): String {
        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val body = JSONObject().apply {
            put("model", "gpt-3.5-turbo")
            put("messages", messagesArray)
            put("max_tokens", 200)
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        val response = client.newCall(request).execute()
        val json = JSONObject(response.body?.string() ?: "")
        return json
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }

    /** OkHttp-клиент без проверки SSL (для GigaChat). */
    private fun buildUnsafeClient(): OkHttpClient {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(
                chain: Array<java.security.cert.X509Certificate>, authType: String
            ) {}
            override fun checkServerTrusted(
                chain: Array<java.security.cert.X509Certificate>, authType: String
            ) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
                arrayOf()
        })

        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
