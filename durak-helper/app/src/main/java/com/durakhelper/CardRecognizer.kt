package com.durakhelper

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Распознавание карт с экрана через AI Vision API.
 * Поддерживает GigaChat (загрузка файла + attachments) и OpenAI (base64 inline).
 */
class CardRecognizer(
    private val apiType: AiHelper.ApiType,
    private val credentials: String
) {
    companion object {
        private const val TAG = "CardRecognizer"

        private val SUIT_MAP = mapOf(
            "♠" to Suit.SPADES, "♥" to Suit.HEARTS,
            "♦" to Suit.DIAMONDS, "♣" to Suit.CLUBS,
            "пики" to Suit.SPADES, "черви" to Suit.HEARTS,
            "бубны" to Suit.DIAMONDS, "трефы" to Suit.CLUBS
        )

        private val RANK_MAP = mapOf(
            "6" to Rank.SIX, "7" to Rank.SEVEN, "8" to Rank.EIGHT,
            "9" to Rank.NINE, "10" to Rank.TEN,
            "В" to Rank.JACK, "J" to Rank.JACK,
            "Д" to Rank.QUEEN, "Q" to Rank.QUEEN,
            "К" to Rank.KING, "K" to Rank.KING,
            "Т" to Rank.ACE, "A" to Rank.ACE, "T" to Rank.ACE
        )
    }

    @Volatile
    private var isProcessing = false

    private var gigaChatAccessToken: String? = null
    private var tokenExpiry: Long = 0

    private val client: OkHttpClient by lazy {
        if (apiType == AiHelper.ApiType.GIGACHAT) {
            buildUnsafeClient()
        } else {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }

    /** Результат распознавания экрана. */
    data class RecognitionResult(
        val myCards: Set<Card>,
        val tableCards: Set<Card>,
        val trumpSuit: Suit?,
        val deckCount: Int?,
        val rawResponse: String,
        val isBusy: Boolean = false,
        val isError: Boolean = false,
        val gameStatus: String? = null
    )

    /** Распознать карты на скриншоте через AI Vision. */
    fun recognizeCards(bitmap: Bitmap, callback: (RecognitionResult) -> Unit) {
        if (isProcessing) {
            callback(RecognitionResult(
                emptySet(), emptySet(), null, null,
                "Идёт обработка...", isBusy = true
            ))
            return
        }

        isProcessing = true
        Thread {
            try {
                val result = when (apiType) {
                    AiHelper.ApiType.GIGACHAT -> analyzeWithGigaChat(bitmap)
                    AiHelper.ApiType.OPENAI -> analyzeWithOpenAI(bitmap)
                }
                callback(result)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка распознавания", e)
                callback(RecognitionResult(
                    emptySet(), emptySet(), null, null,
                    "Ошибка: ${e.message}", isError = true
                ))
            } finally {
                isProcessing = false
            }
        }.start()
    }

    private val analysisPrompt = """Ты анализируешь скриншот мобильной карточной игры "Дурак онлайн" (com.rstgames.durak).

РАСПОЛОЖЕНИЕ ЭЛЕМЕНТОВ НА ЭКРАНЕ (сверху вниз):
1. ВЕРХ (0-15% экрана): аватары противников с рубашками их карт
2. ЛЕВЫЙ КРАЙ (примерно 30-50% высоты): КОЗЫРНАЯ КАРТА — маленькая открытая карта, лежащая боком. Рядом ЧИСЛО — количество карт в колоде
3. ЦЕНТР (30-70% высоты): СТОЛ — карты текущего раунда. Атакующая карта слева, бьющая карта сверху справа (под углом). Пар может быть несколько (до 6)
4. НИЗ (75-95% высоты): МОИ КАРТЫ — крупные карты веером, открытые лицом вверх. Это главная зона
5. САМЫЙ НИЗ: панель с кнопками (Беру/Бито/Пас/Ваш ход) и аватар игрока

КАРТЫ В ЭТОЙ ИГРЕ:
- Номиналы русские: 6, 7, 8, 9, 10, В (валет/jack), Д (дама/queen), К (король/king), Т (туз/ace)
- Масти по символам: ♠ (пики/чёрные), ♥ (черви/красные), ♦ (бубны/красные), ♣ (трефы/чёрные)
- На картинках карт номинал в верхнем левом углу, масть сразу под ним
- Чёрные масти (♠♣) — чёрный текст/символы, красные масти (♥♦) — красный текст/символы

КАК ОПРЕДЕЛИТЬ КОЗЫРЬ:
- Маленькая открытая карта на ЛЕВОМ краю экрана (обычно лежит боком/под углом)
- Масть этой карты = козырная масть
- Число рядом = количество карт в колоде

ВАЖНО:
- Внимательно различай В (валет) и Д (дама) и К (король) — они все картинки с людьми, но буква в углу разная
- 10 — единственный двузначный номинал
- Если стол пустой (нет карт в центре) — table_cards: []
- Считай КАЖДУЮ карту в моей руке отдельно

ОТВЕТЬ СТРОГО ОДНОЙ СТРОКОЙ JSON без markdown, без пояснений:
{"my_cards":["6♠","В♥"],"table_cards":["8♣","10♦"],"trump":"♠","deck_count":12,"status":"ваш_ход"}

Поле status — текст кнопки внизу: "ваш_ход", "беру", "бито", "пас" или null.""".trim()

    // ---------- GigaChat Vision ----------

    private fun ensureGigaChatToken() {
        if (gigaChatAccessToken != null && System.currentTimeMillis() < tokenExpiry) return

        val tokenRequest = Request.Builder()
            .url("https://ngw.devices.sberbank.ru:9443/api/v2/oauth")
            .post("scope=GIGACHAT_API_PERS".toRequestBody(
                "application/x-www-form-urlencoded".toMediaType()
            ))
            .addHeader("Authorization", "Basic $credentials")
            .addHeader("RqUID", java.util.UUID.randomUUID().toString())
            .build()

        val response = client.newCall(tokenRequest).execute()
        val body = response.body?.string() ?: throw Exception("Пустой ответ при получении токена")
        val json = JSONObject(body)

        if (!json.has("access_token")) {
            throw Exception("Ошибка авторизации GigaChat: $body")
        }

        gigaChatAccessToken = json.getString("access_token")
        tokenExpiry = System.currentTimeMillis() + 25 * 60 * 1000
    }

    private fun analyzeWithGigaChat(bitmap: Bitmap): RecognitionResult {
        ensureGigaChatToken()
        val token = gigaChatAccessToken ?: throw Exception("Нет токена GigaChat")

        // 1. Загрузить изображение в хранилище GigaChat
        val jpegBytes = bitmapToJpegBytes(bitmap)
        val fileBody = jpegBytes.toRequestBody("image/jpeg".toMediaType())
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "screenshot.jpg", fileBody)
            .addFormDataPart("purpose", "general")
            .build()

        val uploadRequest = Request.Builder()
            .url("https://gigachat.devices.sberbank.ru/api/v1/files")
            .post(multipart)
            .addHeader("Authorization", "Bearer $token")
            .build()

        val uploadResponse = client.newCall(uploadRequest).execute()
        val uploadBody = uploadResponse.body?.string()
            ?: throw Exception("Пустой ответ при загрузке файла")
        val uploadJson = JSONObject(uploadBody)

        if (!uploadJson.has("id")) {
            throw Exception("Ошибка загрузки: $uploadBody")
        }
        val fileId = uploadJson.getString("id")

        // 2. Запросить анализ изображения
        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", analysisPrompt)
                put("attachments", JSONArray().apply { put(fileId) })
            })
        }

        val body = JSONObject().apply {
            put("model", "GigaChat-Pro")
            put("messages", messagesArray)
            put("temperature", 0.1)
            put("stream", false)
        }

        val chatRequest = Request.Builder()
            .url("https://gigachat.devices.sberbank.ru/api/v1/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $token")
            .build()

        val chatResponse = client.newCall(chatRequest).execute()
        val chatBody = chatResponse.body?.string()
            ?: throw Exception("Пустой ответ от GigaChat")

        val chatJson = JSONObject(chatBody)
        if (!chatJson.has("choices")) {
            throw Exception("Ошибка GigaChat: $chatBody")
        }

        val content = chatJson.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")

        return parseAiResponse(content)
    }

    // ---------- OpenAI Vision ----------

    private fun analyzeWithOpenAI(bitmap: Bitmap): RecognitionResult {
        val base64 = bitmapToBase64(bitmap)

        val contentArray = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", analysisPrompt)
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$base64")
                    put("detail", "high")
                })
            })
        }

        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", contentArray)
            })
        }

        val body = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", messagesArray)
            put("max_tokens", 500)
            put("temperature", 0.1)
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $credentials")
            .build()

        val response = client.newCall(request).execute()
        val responseStr = response.body?.string() ?: throw Exception("Пустой ответ OpenAI")
        val json = JSONObject(responseStr)

        if (!json.has("choices")) {
            throw Exception("Ошибка OpenAI: $responseStr")
        }

        val content = json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")

        return parseAiResponse(content)
    }

    // ---------- Парсинг ответа AI ----------

    private fun parseAiResponse(content: String): RecognitionResult {
        try {
            val jsonStr = content
                .replace("```json", "").replace("```", "")
                .trim()

            val json = JSONObject(jsonStr)

            val myCards = parseCardArray(json.optJSONArray("my_cards"))
            val tableCards = parseCardArray(json.optJSONArray("table_cards"))

            val trumpStr = json.optString("trump", "").trim()
            val trumpSuit = if (trumpStr.isNotEmpty() && trumpStr != "null") {
                SUIT_MAP[trumpStr] ?: SUIT_MAP[trumpStr.lowercase()]
            } else null

            val deckCount = if (json.has("deck_count") && !json.isNull("deck_count")) {
                json.optInt("deck_count", -1).let { if (it >= 0) it else null }
            } else null

            val gameStatus = json.optString("status", "").let {
                if (it.isNotEmpty() && it != "null") it else null
            }

            Log.d(TAG, "Распознано: мои=${myCards.size}, стол=${tableCards.size}, " +
                    "козырь=$trumpSuit, колода=$deckCount, статус=$gameStatus")

            return RecognitionResult(myCards, tableCards, trumpSuit, deckCount, content,
                gameStatus = gameStatus)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка парсинга: $content", e)
            return RecognitionResult(
                emptySet(), emptySet(), null, null,
                "Ошибка парсинга: ${e.message}\n$content"
            )
        }
    }

    private fun parseCardArray(array: JSONArray?): Set<Card> {
        if (array == null) return emptySet()
        val cards = mutableSetOf<Card>()

        for (i in 0 until array.length()) {
            val cardStr = array.getString(i).trim()
            val card = parseCardString(cardStr)
            if (card != null) {
                cards.add(card)
            } else {
                Log.w(TAG, "Не удалось распознать карту: $cardStr")
            }
        }

        return cards
    }

    private fun parseCardString(str: String): Card? {
        if (str.length < 2) return null

        val suitChar = str.last().toString()
        val rankStr = str.dropLast(1).trim()

        val suit = SUIT_MAP[suitChar] ?: return null
        val rank = RANK_MAP[rankStr] ?: return null

        return Card(rank, suit)
    }

    // ---------- Утилиты ----------

    private fun bitmapToJpegBytes(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        return stream.toByteArray()
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        return Base64.encodeToString(bitmapToJpegBytes(bitmap), Base64.NO_WRAP)
    }

    private fun buildUnsafeClient(): OkHttpClient {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(
                chain: Array<java.security.cert.X509Certificate>, authType: String
            ) {}
            override fun checkServerTrusted(
                chain: Array<java.security.cert.X509Certificate>, authType: String
            ) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun close() { /* ресурсов для освобождения нет */ }
}
