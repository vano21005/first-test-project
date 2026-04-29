package com.durakhelper

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.GridLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Офлайн-трекер карт без AI и сети.
 */
class TrackerActivity : AppCompatActivity() {

    private enum class EditMode {
        MY_CARDS,
        TABLE,
        DISCARD,
        REMOVE
    }

    private lateinit var spinnerPlayers: Spinner
    private lateinit var spinnerTrump: Spinner
    private lateinit var tvMyCards: TextView
    private lateinit var tvTableCards: TextView
    private lateinit var tvDiscardedCards: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvAdvice: TextView
    private lateinit var gridCards: GridLayout
    private lateinit var btnModeMy: Button
    private lateinit var btnModeTable: Button
    private lateinit var btnModeDiscard: Button
    private lateinit var btnModeRemove: Button

    private lateinit var gameState: GameState
    private var currentMode = EditMode.MY_CARDS
    private val cardButtons = linkedMapOf<Card, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tracker)

        spinnerPlayers = findViewById(R.id.spinnerTrackerPlayers)
        spinnerTrump = findViewById(R.id.spinnerTrackerTrump)
        tvMyCards = findViewById(R.id.tvTrackerMyCards)
        tvTableCards = findViewById(R.id.tvTrackerTableCards)
        tvDiscardedCards = findViewById(R.id.tvTrackerDiscarded)
        tvStats = findViewById(R.id.tvTrackerStats)
        tvAdvice = findViewById(R.id.tvTrackerAdvice)
        gridCards = findViewById(R.id.gridCards)
        btnModeMy = findViewById(R.id.btnModeMy)
        btnModeTable = findViewById(R.id.btnModeTable)
        btnModeDiscard = findViewById(R.id.btnModeDiscard)
        btnModeRemove = findViewById(R.id.btnModeRemove)

        setupSpinners()
        setupButtons()
        createCardGrid()
        initGameState()
        updateModeButtons()
        updateUi()
    }

    private fun setupSpinners() {
        val playerOptions = (2..6).map { "$it игроков" }
        spinnerPlayers.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            playerOptions
        )
        spinnerPlayers.setSelection(intent.getIntExtra("player_count", 2) - 2)

        val trumpOptions = Suit.entries.map { "${it.symbol} ${it.displayName}" }
        spinnerTrump.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            trumpOptions
        )
        spinnerTrump.setSelection(intent.getIntExtra("trump_index", Suit.SPADES.ordinal))
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnTrackerApplyConfig).setOnClickListener {
            initGameState()
            updateUi()
        }
        findViewById<Button>(R.id.btnTrackerDiscardTable).setOnClickListener {
            gameState.discardTable()
            updateUi()
        }
        findViewById<Button>(R.id.btnTrackerTakeTable).setOnClickListener {
            gameState.takeTableCards()
            updateUi()
        }
        findViewById<Button>(R.id.btnTrackerClearTable).setOnClickListener {
            gameState.tableCards.clear()
            updateUi()
        }
        findViewById<Button>(R.id.btnTrackerReset).setOnClickListener {
            initGameState()
            updateUi()
        }
        findViewById<Button>(R.id.btnTrackerBack).setOnClickListener {
            finish()
        }

        btnModeMy.setOnClickListener {
            currentMode = EditMode.MY_CARDS
            updateModeButtons()
        }
        btnModeTable.setOnClickListener {
            currentMode = EditMode.TABLE
            updateModeButtons()
        }
        btnModeDiscard.setOnClickListener {
            currentMode = EditMode.DISCARD
            updateModeButtons()
        }
        btnModeRemove.setOnClickListener {
            currentMode = EditMode.REMOVE
            updateModeButtons()
        }
    }

    private fun initGameState() {
        val playerCount = spinnerPlayers.selectedItemPosition + 2
        val trump = Suit.entries[spinnerTrump.selectedItemPosition]
        gameState = GameState(playerCount, trump)
    }

    private fun createCardGrid() {
        gridCards.removeAllViews()
        cardButtons.clear()

        Card.fullDeck().forEach { card ->
            val button = Button(this).apply {
                text = card.displayName
                textSize = 16f
                minWidth = 0
                minHeight = 0
                setPadding(4, 12, 4, 12)
                setOnClickListener {
                    applyCardAction(card)
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(6, 6, 6, 6)
            }
            gridCards.addView(button, params)
            cardButtons[card] = button
        }
    }

    private fun applyCardAction(card: Card) {
        when (currentMode) {
            EditMode.MY_CARDS -> {
                if (card in gameState.myCards) {
                    gameState.removeMyCard(card)
                } else {
                    gameState.addMyCard(card)
                }
            }
            EditMode.TABLE -> {
                if (card in gameState.tableCards) {
                    gameState.tableCards.remove(card)
                } else {
                    gameState.addTableCard(card)
                }
            }
            EditMode.DISCARD -> {
                if (card in gameState.discardedCards) {
                    gameState.discardedCards.remove(card)
                } else {
                    gameState.discardedCards.add(card)
                    gameState.myCards.remove(card)
                    gameState.tableCards.remove(card)
                }
            }
            EditMode.REMOVE -> {
                gameState.myCards.remove(card)
                gameState.tableCards.remove(card)
                gameState.discardedCards.remove(card)
            }
        }
        updateUi()
    }

    private fun updateModeButtons() {
        styleModeButton(btnModeMy, currentMode == EditMode.MY_CARDS, 0xFF2E7D32.toInt())
        styleModeButton(btnModeTable, currentMode == EditMode.TABLE, 0xFF1565C0.toInt())
        styleModeButton(btnModeDiscard, currentMode == EditMode.DISCARD, 0xFF6D4C41.toInt())
        styleModeButton(btnModeRemove, currentMode == EditMode.REMOVE, 0xFFC62828.toInt())
    }

    private fun styleModeButton(button: Button, active: Boolean, color: Int) {
        button.setBackgroundColor(if (active) color else 0xFFB0BEC5.toInt())
        button.setTextColor(0xFFFFFFFF.toInt())
    }

    private fun updateUi() {
        tvMyCards.text = "Мои: " + formatCards(gameState.myCards)
        tvTableCards.text = "Стол: " + formatCards(gameState.tableCards)
        tvDiscardedCards.text = "Бито: " + formatCards(gameState.discardedCards)

        val stats = gameState.getStats()
        val unknown = gameState.unknownCards
            .sortedWith(compareBy<Card> { it.suit.ordinal }.thenBy { it.rank.value })
            .joinToString(" ") { it.displayName }

        tvStats.text = buildString {
            appendLine("Колода/неизвестные: ${stats.remainingInDeck}")
            appendLine("Козыри у меня/неизвестные: ${stats.myTrumps}/${stats.unknownTrumps}")
            append("Осталось в игре: ${if (unknown.isEmpty()) "—" else unknown}")
        }
        tvAdvice.text = gameState.getAdvice()

        cardButtons.forEach { (card, button) ->
            when {
                card in gameState.myCards -> {
                    button.setBackgroundColor(0xFF2E7D32.toInt())
                    button.setTextColor(0xFFFFFFFF.toInt())
                }
                card in gameState.tableCards -> {
                    button.setBackgroundColor(0xFF1565C0.toInt())
                    button.setTextColor(0xFFFFFFFF.toInt())
                }
                card in gameState.discardedCards -> {
                    button.setBackgroundColor(0xFF6D4C41.toInt())
                    button.setTextColor(0xFFFFFFFF.toInt())
                }
                else -> {
                    button.setBackgroundColor(0xFFFFFFFF.toInt())
                    button.setTextColor(if (card.suit == Suit.HEARTS || card.suit == Suit.DIAMONDS) {
                        0xFFD32F2F.toInt()
                    } else {
                        0xFF111111.toInt()
                    })
                }
            }
        }
    }

    private fun formatCards(cards: Set<Card>): String {
        if (cards.isEmpty()) return "—"
        return cards.sortedWith(compareBy<Card> { it.suit.ordinal }.thenBy { it.rank.value })
            .joinToString(" ") { it.displayName }
    }
}
