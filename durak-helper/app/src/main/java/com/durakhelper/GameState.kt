package com.durakhelper

/**
 * Состояние игры: отслеживание карт и подсказки.
 */
class GameState(
    val playerCount: Int,
    val trumpSuit: Suit
) {
    /** Карты игрока (мои). */
    val myCards = mutableSetOf<Card>()

    /** Карты на столе (текущий раунд). */
    val tableCards = mutableSetOf<Card>()

    /** Сброшенные карты (бито). */
    val discardedCards = mutableSetOf<Card>()

    /** Все 36 карт в колоде. */
    private val fullDeck = Card.fullDeck().toSet()

    /** Карты, которые ещё не были видны (не у меня, не на столе, не в сбросе). */
    val unknownCards: Set<Card>
        get() = fullDeck - myCards - tableCards - discardedCards

    /** Количество карт, оставшихся в колоде (приблизительно). */
    val remainingInDeck: Int
        get() {
            val knownCards = myCards.size + tableCards.size + discardedCards.size
            return (36 - knownCards).coerceAtLeast(0)
        }

    /** Добавить карту в мою руку. */
    fun addMyCard(card: Card) {
        myCards.add(card)
        tableCards.remove(card)
        discardedCards.remove(card)
    }

    /** Убрать карту из руки. */
    fun removeMyCard(card: Card) {
        myCards.remove(card)
    }

    /** Положить карту на стол. */
    fun addTableCard(card: Card) {
        tableCards.add(card)
        myCards.remove(card)
    }

    /** Отбой — все карты со стола уходят в сброс. */
    fun discardTable() {
        discardedCards.addAll(tableCards)
        tableCards.clear()
    }

    /** Забрать карты со стола (противник забирает). */
    fun takeTableCards() {
        tableCards.clear()
    }

    /** Сброс состояния для новой игры. */
    fun reset() {
        myCards.clear()
        tableCards.clear()
        discardedCards.clear()
    }

    // ---------- Подсказки ----------

    /**
     * Подсказка для атаки: какими картами лучше атаковать.
     * Сортировка: сначала некозырные, по возрастанию номинала.
     */
    fun suggestAttack(): List<Card> {
        return myCards.sortedWith(
            compareBy<Card> { if (it.suit == trumpSuit) 1 else 0 }
                .thenBy { it.rank.value }
        )
    }

    /**
     * Подсказка для защиты: какой картой лучше побить [attackCard].
     * Возвращает список подходящих карт, отсортированный по «экономности».
     */
    fun suggestDefense(attackCard: Card): List<Card> {
        return myCards
            .filter { it.canBeat(attackCard, trumpSuit) }
            .sortedWith(
                compareBy<Card> { if (it.suit == trumpSuit) 1 else 0 }
                    .thenBy { it.rank.value }
            )
    }

    /**
     * Подсказка для подкидывания: карты с совпадающими номиналами
     * к уже лежащим на столе.
     */
    fun suggestThrowIn(): List<Card> {
        val tableRanks = tableCards.map { it.rank }.toSet()
        return myCards
            .filter { it.rank in tableRanks }
            .sortedWith(
                compareBy<Card> { if (it.suit == trumpSuit) 1 else 0 }
                    .thenBy { it.rank.value }
            )
    }

    /**
     * Статистика для отображения.
     */
    fun getStats(): GameStats {
        val unknownTrumps = unknownCards.count { it.suit == trumpSuit }
        val myTrumps = myCards.count { it.suit == trumpSuit }
        val discardedTrumps = discardedCards.count { it.suit == trumpSuit }

        return GameStats(
            totalCards = 36,
            myCardsCount = myCards.size,
            tableCardsCount = tableCards.size,
            discardedCount = discardedCards.size,
            remainingUnknown = unknownCards.size,
            myTrumps = myTrumps,
            unknownTrumps = unknownTrumps,
            discardedTrumps = discardedTrumps
        )
    }

    /**
     * Текстовая подсказка для текущей ситуации.
     */
    fun getAdvice(): String {
        val stats = getStats()
        val advice = StringBuilder()

        // Анализ козырей
        if (stats.unknownTrumps == 0 && stats.myTrumps > 0) {
            advice.appendLine("У противников нет козырей! Атакуй смело.")
        } else if (stats.myTrumps == 0) {
            advice.appendLine("У тебя нет козырей — берегись козырных атак!")
        } else if (stats.myTrumps >= 3) {
            advice.appendLine("Хороший запас козырей (${stats.myTrumps}). Можно играть агрессивно.")
        }

        // Анализ колоды
        if (stats.remainingUnknown <= 6) {
            advice.appendLine("Осталось мало неизвестных карт (${stats.remainingUnknown}). Считай карты!")
        }

        // Анализ козырных угроз
        val highTrumpsInUnknown = unknownCards.count {
            it.suit == trumpSuit && it.rank.value >= Rank.QUEEN.value
        }
        if (highTrumpsInUnknown > 0) {
            advice.appendLine("Внимание: $highTrumpsInUnknown старших козырей ещё не видно.")
        }

        // Общий совет по стратегии
        if (myCards.size <= 3 && stats.remainingUnknown == 0) {
            advice.appendLine("Финал игры! Разыгрывай оставшиеся карты аккуратно.")
        }

        if (advice.isEmpty()) {
            advice.appendLine("Играй стабильно: бей мелкими, береги козыри.")
        }

        return advice.toString().trim()
    }
}

/**
 * Статистика текущей игры.
 */
data class GameStats(
    val totalCards: Int,
    val myCardsCount: Int,
    val tableCardsCount: Int,
    val discardedCount: Int,
    val remainingUnknown: Int,
    val myTrumps: Int,
    val unknownTrumps: Int,
    val discardedTrumps: Int
)
