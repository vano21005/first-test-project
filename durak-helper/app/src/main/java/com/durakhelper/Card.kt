package com.durakhelper

/**
 * Масти карт.
 */
enum class Suit(val symbol: String, val displayName: String) {
    SPADES("\u2660", "Пики"),
    HEARTS("\u2665", "Черви"),
    DIAMONDS("\u2666", "Бубны"),
    CLUBS("\u2663", "Трефы");
}

/**
 * Номиналы карт (колода 36 карт: от 6 до Туза).
 */
enum class Rank(val value: Int, val shortName: String) {
    SIX(6, "6"),
    SEVEN(7, "7"),
    EIGHT(8, "8"),
    NINE(9, "9"),
    TEN(10, "10"),
    JACK(11, "В"),
    QUEEN(12, "Д"),
    KING(13, "К"),
    ACE(14, "Т");
}

/**
 * Карта с мастью и номиналом.
 */
data class Card(val rank: Rank, val suit: Suit) {

    /** Краткое отображение: "6♠", "Т♥" и т.д. */
    val displayName: String
        get() = "${rank.shortName}${suit.symbol}"

    /** Может ли эта карта побить [other] при козыре [trump]. */
    fun canBeat(other: Card, trump: Suit): Boolean {
        if (suit == other.suit) {
            return rank.value > other.rank.value
        }
        // Козырь бьёт некозырную карту
        return suit == trump && other.suit != trump
    }

    companion object {
        /** Полная колода 36 карт. */
        fun fullDeck(): List<Card> {
            val deck = mutableListOf<Card>()
            for (suit in Suit.entries) {
                for (rank in Rank.entries) {
                    deck.add(Card(rank, suit))
                }
            }
            return deck
        }
    }
}
