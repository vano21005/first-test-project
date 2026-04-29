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
    private val apiKey: String,
    private var customPromptTemplate: String? = null
) {
    enum class ApiType { GIGACHAT, OPENAI }

    fun setPromptTemplate(template: String?) {
        customPromptTemplate = template
    }

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

    companion object {
        const val DEFAULT_PROMPT_TEMPLATE = "Дурак, 36 карт. Козырь: {trump}. Мои ({my_count}): {my_cards}. Стол: {table}. Бито ({discard_count}): {discarded}. Неизвестные ({unknown_count}): {unknown}. Мои козыри: {my_trumps}, чужие козыри: {opp_trumps}, в колоде: {deck}. Дай чёткий совет 2-3 предложения: чем ходить/отбиваться, что беречь."
    }

    private fun buildGameData(gameState: GameState): Map<String, String> {
        val stats = gameState.getStats()
        fun sorted(cards: Collection<Card>) = cards
            .sortedWith(compareBy<Card> { it.suit.ordinal }.thenBy { it.rank.value })
            .joinToString(", ") { it.displayName }
        return mapOf(
            "{trump}" to "${gameState.trumpSuit.symbol} ${gameState.trumpSuit.displayName}",
            "{my_cards}" to sorted(gameState.myCards),
            "{my_count}" to stats.myCardsCount.toString(),
            "{table}" to if (gameState.tableCards.isEmpty()) "пусто" else sorted(gameState.tableCards),
            "{discarded}" to if (gameState.discardedCards.isEmpty()) "пусто" else sorted(gameState.discardedCards),
            "{discard_count}" to stats.discardedCount.toString(),
            "{unknown}" to sorted(gameState.unknownCards),
            "{unknown_count}" to stats.remainingUnknown.toString(),
            "{my_trumps}" to stats.myTrumps.toString(),
            "{opp_trumps}" to stats.unknownTrumps.toString(),
            "{deck}" to stats.remainingInDeck.toString()
        )
    }

    private fun buildPrompt(gameState: GameState): String {
        val template = customPromptTemplate?.takeIf { it.isNotBlank() } ?: DEFAULT_PROMPT_TEMPLATE
        val data = buildGameData(gameState)
        var result = template
        for ((key, value) in data) {
            result = result.replace(key, value)
        }
        return result
    }

    private fun callGigaChat(prompt: String): String {
        // Получение токена доступа (OAuth)
        val tokenRequest = Request.Builder()
            .url("https://ngw.devices.sberbank.ru:9443/api/v2/oauth")
            .post(
                "scope=GIGACHAT_API_PERS"
                    .toRequestBody("application/x-www-form-urlencoded".toMediaType())
            )
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .addHeader("Accept", "application/json")
            .addHeader("Authorization", "Basic $apiKey")
            .addHeader("RqUID", java.util.UUID.randomUUID().toString())
            .build()

        val tokenResponse = client.newCall(tokenRequest).execute()
        val tokenBody = tokenResponse.body?.string() ?: ""
        if (!tokenResponse.isSuccessful) {
            return "\u041e\u0448\u0438\u0431\u043a\u0430 \u0442\u043e\u043a\u0435\u043d\u0430 (${tokenResponse.code}): $tokenBody"
        }
        val tokenJson = JSONObject(tokenBody)
        if (!tokenJson.has("access_token")) {
            return "\u041d\u0435\u0442 access_token: $tokenBody"
        }
        val accessToken = tokenJson.getString("access_token")

        // Запрос к GigaChat
        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val body = JSONObject().apply {
            put("model", "GigaChat-2")
            put("messages", messagesArray)
            put("n", 1)
            put("stream", false)
            put("max_tokens", 300)
            put("repetition_penalty", 1)
            put("update_interval", 0)
        }

        val chatRequest = Request.Builder()
            .url("https://gigachat.devices.sberbank.ru/api/v1/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        val chatResponse = client.newCall(chatRequest).execute()
        val chatBody = chatResponse.body?.string() ?: ""
        if (!chatResponse.isSuccessful) {
            return "\u041e\u0448\u0438\u0431\u043a\u0430 API (${chatResponse.code}): $chatBody"
        }
        val chatJson = JSONObject(chatBody)
        if (!chatJson.has("choices")) {
            return "\u041e\u0442\u0432\u0435\u0442 \u0431\u0435\u0437 choices: $chatBody"
        }
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
