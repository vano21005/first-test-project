package com.durakhelper

/**
 * Состояние игры: отслеживание карт и подсказки.
 * Поддерживает автоматическое обновление из AI Vision и ручное управление.
 */
class GameState(
    val playerCount: Int,
    var trumpSuit: Suit
) {
    /** Карты игрока (мои). */
    val myCards = mutableSetOf<Card>()

    /** Карты на столе (текущий раунд). */
    val tableCards = mutableSetOf<Card>()

    /** Сброшенные карты (бито). */
    val discardedCards = mutableSetOf<Card>()

    /** Карты, которые забрал противник. */
    private val knownOpponentCards = mutableSetOf<Card>()

    /** Все 36 карт в колоде. */
    private val fullDeck = Card.fullDeck().toSet()

    /** Карты в колоде (определяется AI или вычисляется). */
    var deckCount: Int? = null

    /** Последний определённый статус игры (ваш_ход, бито, пас, беру). */
    var lastGameStatus: String? = null

    /** Карты, которые ещё не были видны. */
    val unknownCards: Set<Card>
        get() = fullDeck - myCards - tableCards - discardedCards - knownOpponentCards

    /** Количество карт в колоде. */
    val remainingInDeck: Int
        get() {
            if (deckCount != null) return deckCount!!
            val knownCards = myCards.size + tableCards.size +
                    discardedCards.size + knownOpponentCards.size
            return (36 - knownCards).coerceAtLeast(0)
        }

    /**
     * Обновить состояние из результата AI Vision сканирования.
     * Автоматически определяет бито (стол очистился).
     */
    fun updateFromScan(
        newMyCards: Set<Card>,
        newTableCards: Set<Card>,
        detectedTrump: Suit?,
        detectedDeckCount: Int?,
        gameStatus: String? = null
    ) {
        lastGameStatus = gameStatus
        // Авто-бито: если стол был не пуст, а теперь пуст
        if (tableCards.isNotEmpty() && newTableCards.isEmpty()) {
            val cardsLeftTable = tableCards.toSet()
            val cardsAddedToHand = newMyCards - myCards
            val discarded = cardsLeftTable - cardsAddedToHand
            if (discarded.isNotEmpty()) {
                discardedCards.addAll(discarded)
            }
        }

        // Обновить видимое состояние
        myCards.clear()
        myCards.addAll(newMyCards)
        tableCards.clear()
        tableCards.addAll(newTableCards)

        // Убрать видимые карты из knownOpponentCards (если мы их видим — они не у противника)
        knownOpponentCards.removeAll(newMyCards)
        knownOpponentCards.removeAll(newTableCards)

        // Козырь
        if (detectedTrump != null) {
            trumpSuit = detectedTrump
        }

        // Колода
        if (detectedDeckCount != null) {
            deckCount = detectedDeckCount
        }
    }

    /** Добавить карту в мою руку (ручной режим). */
    fun addMyCard(card: Card) {
        myCards.add(card)
        tableCards.remove(card)
        discardedCards.remove(card)
        knownOpponentCards.remove(card)
    }

    /** Убрать карту из руки. */
    fun removeMyCard(card: Card) {
        myCards.remove(card)
    }

    /** Положить карту на стол (ручной режим). */
    fun addTableCard(card: Card) {
        tableCards.add(card)
        myCards.remove(card)
        discardedCards.remove(card)
        knownOpponentCards.remove(card)
    }

    /** Отбой — все карты со стола уходят в сброс. */
    fun discardTable() {
        discardedCards.addAll(tableCards)
        tableCards.clear()
    }

    /** Забрать карты со стола (противник забирает). */
    fun takeTableCards() {
        knownOpponentCards.addAll(tableCards)
        tableCards.clear()
    }

    /** Сброс состояния для новой игры. */
    fun reset() {
        myCards.clear()
        tableCards.clear()
        discardedCards.clear()
        knownOpponentCards.clear()
        deckCount = null
        lastGameStatus = null
    }

    // ---------- Подсказки ----------

    fun suggestAttack(): List<Card> {
        return myCards.sortedWith(
            compareBy<Card> { if (it.suit == trumpSuit) 1 else 0 }
                .thenBy { it.rank.value }
        )
    }

    fun suggestDefense(attackCard: Card): List<Card> {
        return myCards
            .filter { it.canBeat(attackCard, trumpSuit) }
            .sortedWith(
                compareBy<Card> { if (it.suit == trumpSuit) 1 else 0 }
                    .thenBy { it.rank.value }
            )
    }

    fun suggestThrowIn(): List<Card> {
        val tableRanks = tableCards.map { it.rank }.toSet()
        return myCards
            .filter { it.rank in tableRanks }
            .sortedWith(
                compareBy<Card> { if (it.suit == trumpSuit) 1 else 0 }
                    .thenBy { it.rank.value }
            )
    }

    fun getStats(): GameStats {
        val unknownTrumps = unknownCards.count { it.suit == trumpSuit }
        val myTrumps = myCards.count { it.suit == trumpSuit }
        val discardedTrumps = discardedCards.count { it.suit == trumpSuit }

        return GameStats(
            totalCards = 36,
            myCardsCount = myCards.size,
            tableCardsCount = tableCards.size,
            discardedCount = discardedCards.size,
            remainingInDeck = remainingInDeck,
            remainingUnknown = unknownCards.size,
            myTrumps = myTrumps,
            unknownTrumps = unknownTrumps,
            discardedTrumps = discardedTrumps
        )
    }

    fun getAdvice(): String {
        val stats = getStats()
        val advice = StringBuilder()

        // Атака: ходи самой мелкой некозырной
        if (tableCards.isEmpty() && myCards.isNotEmpty()) {
            val attack = suggestAttack()
            if (attack.isNotEmpty()) {
                advice.appendLine("Ходи: ${attack.first().displayName}")
            }
        }

        // Подкинуть
        if (tableCards.isNotEmpty()) {
            val throwIns = suggestThrowIn()
            if (throwIns.isNotEmpty()) {
                advice.appendLine("Подкинь: ${throwIns.joinToString(" ") { it.displayName }}")
            }
        }

        // Козыри
        if (stats.unknownTrumps == 0 && stats.myTrumps > 0) {
            advice.appendLine("У противников нет козырей!")
        } else if (stats.myTrumps == 0 && stats.unknownTrumps > 0) {
            advice.appendLine("Нет козырей \u2014 осторожно!")
        } else if (stats.myTrumps >= 3) {
            advice.appendLine("Козырей: ${stats.myTrumps} \u2014 играй смело")
        }

        if (stats.remainingInDeck <= 4 && stats.remainingInDeck > 0) {
            advice.appendLine("Колода: ${stats.remainingInDeck} \u2014 считай!")
        }

        if (stats.remainingUnknown <= 6 && stats.remainingUnknown > 0) {
            advice.appendLine("Неизвестных: ${stats.remainingUnknown}")
        }

        val highTrumpsInUnknown = unknownCards.count {
            it.suit == trumpSuit && it.rank.value >= Rank.QUEEN.value
        }
        if (highTrumpsInUnknown > 0) {
            advice.appendLine("Старших козырей не видно: $highTrumpsInUnknown")
        }

        if (myCards.size <= 3 && stats.remainingInDeck == 0) {
            advice.appendLine("Финал! Аккуратно!")
        }

        if (advice.isEmpty()) {
            advice.appendLine("Бей мелкими, береги козыри")
        }

        return advice.toString().trim()
    }
}

data class GameStats(
    val totalCards: Int,
    val myCardsCount: Int,
    val tableCardsCount: Int,
    val discardedCount: Int,
    val remainingInDeck: Int,
    val remainingUnknown: Int,
    val myTrumps: Int,
    val unknownTrumps: Int,
    val discardedTrumps: Int
)
