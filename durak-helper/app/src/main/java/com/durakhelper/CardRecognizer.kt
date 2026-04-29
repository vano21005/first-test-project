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

    private val handPrompt = """Ты видишь ТОЛЬКО нижнюю часть экрана с МОИМИ картами в игре "Дурак онлайн".

Задача: перечисли только мои карты снизу слева направо.

Правила распознавания:
- Номиналы: 6, 7, 8, 9, 10, В, Д, К, Т
- Сначала определи ЦВЕТ масти: красный -> только ♥ или ♦, чёрный -> только ♠ или ♣
- Потом определи ФОРМУ знака: ♥ сердце, ♦ ромб, ♠ пика с ножкой, ♣ клевер из 3 лепестков
- Не путай В, Д, К: это разные буквы в углу карты
- 10 — двузначное число, не путай с Т
- Игнорируй стол, аватары и кнопки

Примеры из этой игры:
- красная карта с ромбами = ♦
- красная карта с сердцами = ♥
- чёрная карта с клеверами = ♣
- чёрная карта с пиками = ♠

Ответь строго JSON:
{"cards":["10♥","В♥","К♥","9♦","В♦","6♣"]}""".trim()

    private val tablePrompt = """Ты видишь ТОЛЬКО центральную часть стола в игре "Дурак онлайн".

Задача: перечисли все открытые карты на столе. Если карта partially covered, всё равно распознай по видимой букве/масти.

Правила:
- Считывай только карты в центре, не мои карты снизу
- Атакующие и отбивающие карты все входят в массив cards
- Сначала определи цвет масти, потом форму знака
- Если стол пустой, верни пустой массив

Ответь строго JSON:
{"cards":["9♦","9♣"]}""".trim()

    private val metaPrompt = """Ты видишь полный скриншот игры "Дурак онлайн".

Нужно определить только 3 вещи:
1. trump — козырная масть по маленькой открытой карте на ЛЕВОМ краю
2. deck_count — белое число слева рядом с колодой
3. status — надпись на большой кнопке внизу слева: только "ваш_ход", "беру", "бито", "пас" или null

Правила по козырю:
- Сначала определи цвет масти: красный -> ♥/♦, чёрный -> ♠/♣
- Потом форму знака: ♥ сердце, ♦ ромб, ♠ пика, ♣ клевер

Ответь строго JSON:
{"trump":"♣","deck_count":12,"status":"ваш_ход"}""".trim()

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

        val handBitmap = cropBottomHand(bitmap)
        val tableBitmap = cropCenterTable(bitmap)

        try {
            val handJson = JSONObject(callGigaChat(token, handPrompt, handBitmap))
            val tableJson = JSONObject(callGigaChat(token, tablePrompt, tableBitmap))
            val metaJson = JSONObject(callGigaChat(token, metaPrompt, bitmap))
            return buildRecognitionResult(handJson, tableJson, metaJson)
        } finally {
            handBitmap.recycle()
            tableBitmap.recycle()
        }
    }

    // ---------- OpenAI Vision ----------

    private fun analyzeWithOpenAI(bitmap: Bitmap): RecognitionResult {
        val handBitmap = cropBottomHand(bitmap)
        val tableBitmap = cropCenterTable(bitmap)

        try {
            val handJson = JSONObject(callOpenAi(handPrompt, handBitmap))
            val tableJson = JSONObject(callOpenAi(tablePrompt, tableBitmap))
            val metaJson = JSONObject(callOpenAi(metaPrompt, bitmap))
            return buildRecognitionResult(handJson, tableJson, metaJson)
        } finally {
            handBitmap.recycle()
            tableBitmap.recycle()
        }
    }

    private fun callGigaChat(token: String, prompt: String, bitmap: Bitmap): String {
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

        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
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
        val chatBody = chatResponse.body?.string() ?: throw Exception("Пустой ответ от GigaChat")
        val chatJson = JSONObject(chatBody)
        if (!chatJson.has("choices")) {
            throw Exception("Ошибка GigaChat: $chatBody")
        }
        return chatJson.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .replace("```json", "")
            .replace("```", "")
            .trim()
    }

    private fun callOpenAi(prompt: String, bitmap: Bitmap): String {
        val base64 = bitmapToBase64(bitmap)
        val contentArray = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$base64")
                    put("detail", "high")
                })
            })
        }

        val body = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", contentArray)
                })
            })
            put("max_tokens", 300)
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
        return json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .replace("```json", "")
            .replace("```", "")
            .trim()
    }

    private fun buildRecognitionResult(
        handJson: JSONObject,
        tableJson: JSONObject,
        metaJson: JSONObject
    ): RecognitionResult {
        val myCards = parseCardArray(handJson.optJSONArray("cards"))
        val tableCards = parseCardArray(tableJson.optJSONArray("cards"))
        val trumpStr = metaJson.optString("trump", "").trim()
        val trumpSuit = if (trumpStr.isNotEmpty() && trumpStr != "null") {
            SUIT_MAP[trumpStr] ?: SUIT_MAP[trumpStr.lowercase()]
        } else null
        val deckCount = if (metaJson.has("deck_count") && !metaJson.isNull("deck_count")) {
            metaJson.optInt("deck_count", -1).let { if (it >= 0) it else null }
        } else null
        val gameStatus = metaJson.optString("status", "").let {
            if (it.isNotEmpty() && it != "null") it else null
        }
        val raw = "hand=$handJson\ntable=$tableJson\nmeta=$metaJson"
        return RecognitionResult(myCards, tableCards, trumpSuit, deckCount, raw, gameStatus = gameStatus)
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

    private fun cropBottomHand(bitmap: Bitmap): Bitmap {
        val x = (bitmap.width * 0.03f).toInt()
        val y = (bitmap.height * 0.70f).toInt()
        val width = (bitmap.width * 0.94f).toInt()
        val height = (bitmap.height * 0.24f).toInt()
        return Bitmap.createBitmap(bitmap, x, y, width, height)
    }

    private fun cropCenterTable(bitmap: Bitmap): Bitmap {
        val x = (bitmap.width * 0.08f).toInt()
        val y = (bitmap.height * 0.22f).toInt()
        val width = (bitmap.width * 0.84f).toInt()
        val height = (bitmap.height * 0.48f).toInt()
        return Bitmap.createBitmap(bitmap, x, y, width, height)
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
