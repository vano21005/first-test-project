package com.durakhelper

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Распознавание карт с экрана через ML Kit OCR + анализ цвета.
 *
 * Карты в игре «Дурак онлайн» имеют подписи:
 *   6, 7, 8, 9, 10, В, Д, К, Т (номинал)
 *   и символы мастей ♠ ♥ ♦ ♣
 *
 * Нижняя часть экрана (~30%) — карты игрока.
 * Центр экрана (~30-60%) — карты на столе.
 */
class CardRecognizer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Маппинг текста в номинал. */
    private val rankMap = mapOf(
        "6" to Rank.SIX,
        "7" to Rank.SEVEN,
        "8" to Rank.EIGHT,
        "9" to Rank.NINE,
        "10" to Rank.TEN,
        "В" to Rank.JACK, "B" to Rank.JACK, "J" to Rank.JACK,
        "Д" to Rank.QUEEN, "Q" to Rank.QUEEN, "D" to Rank.QUEEN,
        "К" to Rank.KING, "K" to Rank.KING,
        "Т" to Rank.ACE, "T" to Rank.ACE, "A" to Rank.ACE
    )

    /** Маппинг символов мастей. */
    private val suitSymbolMap = mapOf(
        "\u2660" to Suit.SPADES,   // ♠
        "\u2663" to Suit.CLUBS,    // ♣
        "\u2665" to Suit.HEARTS,   // ♥
        "\u2666" to Suit.DIAMONDS  // ♦
    )

    /**
     * Результат распознавания экрана.
     */
    data class RecognitionResult(
        val myCards: Set<Card>,
        val tableCards: Set<Card>,
        val rawTexts: List<String>
    )

    /**
     * Распознать карты на скриншоте.
     * @param bitmap скриншот экрана
     * @param callback результат распознавания
     */
    fun recognizeCards(bitmap: Bitmap, callback: (RecognitionResult) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val height = bitmap.height

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val myCards = mutableSetOf<Card>()
                val tableCards = mutableSetOf<Card>()
                val rawTexts = mutableListOf<String>()

                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        for (element in line.elements) {
                            val text = element.text.trim().uppercase()
                            rawTexts.add(text)

                            val rank = rankMap[text] ?: continue
                            val boundingBox = element.boundingBox ?: continue

                            // Определить масть по цвету пикселей рядом с текстом
                            val suit = detectSuitByColor(
                                bitmap,
                                boundingBox.centerX(),
                                boundingBox.centerY()
                            )

                            val card = Card(rank, suit)

                            // Нижние 35% экрана — мои карты
                            // Центр (25%-65%) — карты на столе
                            val relativeY = boundingBox.centerY().toFloat() / height
                            when {
                                relativeY > 0.65f -> myCards.add(card)
                                relativeY in 0.25f..0.65f -> tableCards.add(card)
                            }
                        }
                    }
                }

                callback(RecognitionResult(myCards, tableCards, rawTexts))
            }
            .addOnFailureListener {
                callback(RecognitionResult(emptySet(), emptySet(), listOf("Ошибка: ${it.message}")))
            }
    }

    /**
     * Определить масть по доминирующему цвету вокруг текста.
     * Красный → Черви или Бубны
     * Чёрный → Пики или Трефы
     */
    private fun detectSuitByColor(bitmap: Bitmap, cx: Int, cy: Int): Suit {
        val radius = 15
        var redCount = 0
        var blackCount = 0

        val startX = (cx - radius).coerceAtLeast(0)
        val endX = (cx + radius).coerceAtMost(bitmap.width - 1)
        val startY = (cy - radius).coerceAtLeast(0)
        val endY = (cy + radius).coerceAtMost(bitmap.height - 1)

        for (x in startX..endX step 3) {
            for (y in startY..endY step 3) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                if (r > 150 && g < 100 && b < 100) {
                    redCount++
                } else if (r < 80 && g < 80 && b < 80) {
                    blackCount++
                }
            }
        }

        // Красные масти: Черви / Бубны (не различаем точно — берём Черви по умолчанию)
        // Чёрные масти: Пики / Трефы (берём Пики по умолчанию)
        // Более точное определение потребует распознавания символа масти
        return if (redCount > blackCount) Suit.HEARTS else Suit.SPADES
    }

    /** Освободить ресурсы. */
    fun close() {
        recognizer.close()
    }
}
