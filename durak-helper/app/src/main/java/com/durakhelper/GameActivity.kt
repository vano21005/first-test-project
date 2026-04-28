package com.durakhelper

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Игровой экран: ввод карт, отслеживание, подсказки.
 */
class GameActivity : AppCompatActivity() {

    private lateinit var gameState: GameState
    private lateinit var tvStats: TextView
    private lateinit var tvAdvice: TextView
    private lateinit var tvMyCards: TextView
    private lateinit var tvTableCards: TextView
    private lateinit var gridCards: GridLayout
    private lateinit var tvAiAdvice: TextView
    private lateinit var btnAiAdvice: Button

    // Режим ввода: в какую зону добавляется карта
    private enum class InputMode { MY_CARDS, TABLE }
    private var inputMode = InputMode.MY_CARDS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        val playerCount = intent.getIntExtra("playerCount", 2)
        val trumpName = intent.getStringExtra("trumpSuit") ?: Suit.SPADES.name
        val trumpSuit = Suit.valueOf(trumpName)

        gameState = GameState(playerCount, trumpSuit)

        // Привязка элементов
        tvStats = findViewById(R.id.tvStats)
        tvAdvice = findViewById(R.id.tvAdvice)
        tvMyCards = findViewById(R.id.tvMyCards)
        tvTableCards = findViewById(R.id.tvTableCards)
        gridCards = findViewById(R.id.gridCards)
        tvAiAdvice = findViewById(R.id.tvAiAdvice)
        btnAiAdvice = findViewById(R.id.btnAiAdvice)

        val btnModeMyCards = findViewById<Button>(R.id.btnModeMyCards)
        val btnModeTable = findViewById<Button>(R.id.btnModeTable)
        val btnDiscard = findViewById<Button>(R.id.btnDiscard)
        val btnTakeCards = findViewById<Button>(R.id.btnTakeCards)
        val btnReset = findViewById<Button>(R.id.btnReset)
        val tvTrump = findViewById<TextView>(R.id.tvTrump)

        tvTrump.text = "Козырь: ${trumpSuit.symbol} ${trumpSuit.displayName}"

        // Переключение режимов ввода
        btnModeMyCards.setOnClickListener {
            inputMode = InputMode.MY_CARDS
            btnModeMyCards.alpha = 1f
            btnModeTable.alpha = 0.5f
            Toast.makeText(this, "Режим: Мои карты", Toast.LENGTH_SHORT).show()
        }

        btnModeTable.setOnClickListener {
            inputMode = InputMode.TABLE
            btnModeMyCards.alpha = 0.5f
            btnModeTable.alpha = 1f
            Toast.makeText(this, "Режим: Карты на столе", Toast.LENGTH_SHORT).show()
        }

        btnDiscard.setOnClickListener {
            gameState.discardTable()
            updateUI()
            Toast.makeText(this, "Бито! Карты отправлены в сброс.", Toast.LENGTH_SHORT).show()
        }

        btnTakeCards.setOnClickListener {
            gameState.takeTableCards()
            updateUI()
            Toast.makeText(this, "Карты забраны со стола.", Toast.LENGTH_SHORT).show()
        }

        btnReset.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Новая игра")
                .setMessage("Сбросить все карты?")
                .setPositiveButton("Да") { _, _ ->
                    gameState.reset()
                    updateUI()
                }
                .setNegativeButton("Нет", null)
                .show()
        }

        // AI-совет
        btnAiAdvice.setOnClickListener {
            requestAiAdvice()
        }

        // Построить сетку карт
        buildCardGrid()
        updateUI()

        // По умолчанию режим "Мои карты"
        btnModeMyCards.alpha = 1f
        btnModeTable.alpha = 0.5f
    }

    /** Построить сетку из 36 карт, сгруппированных по мастям. */
    private fun buildCardGrid() {
        gridCards.removeAllViews()
        gridCards.columnCount = 9

        for (suit in Suit.entries) {
            for (rank in Rank.entries) {
                val card = Card(rank, suit)
                val btn = Button(this).apply {
                    text = card.displayName
                    textSize = 12f
                    setPadding(4, 4, 4, 4)
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = 0
                    minimumHeight = 0

                    val params = GridLayout.LayoutParams().apply {
                        width = 0
                        height = GridLayout.LayoutParams.WRAP_CONTENT
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins(2, 2, 2, 2)
                    }
                    layoutParams = params

                    // Цвет по масти
                    val textColor = when (suit) {
                        Suit.HEARTS, Suit.DIAMONDS -> Color.RED
                        else -> Color.BLACK
                    }
                    setTextColor(textColor)

                    tag = card

                    setOnClickListener { onCardClicked(card, this) }
                }
                gridCards.addView(btn)
            }
        }
    }

    /** Обработка нажатия на карту в сетке. */
    @Suppress("UNUSED_PARAMETER")
    private fun onCardClicked(card: Card, button: Button) {
        when (inputMode) {
            InputMode.MY_CARDS -> {
                if (card in gameState.myCards) {
                    gameState.removeMyCard(card)
                } else {
                    gameState.addMyCard(card)
                }
            }
            InputMode.TABLE -> {
                if (card in gameState.tableCards) {
                    gameState.tableCards.remove(card)
                } else {
                    gameState.addTableCard(card)
                }
            }
        }
        updateUI()
    }

    /** Обновить весь интерфейс. */
    private fun updateUI() {
        // Обновить цвета кнопок в сетке
        for (i in 0 until gridCards.childCount) {
            val btn = gridCards.getChildAt(i) as Button
            val card = btn.tag as Card
            when {
                card in gameState.myCards -> {
                    btn.setBackgroundColor(Color.parseColor("#4CAF50")) // зелёный
                    btn.setTextColor(Color.WHITE)
                }
                card in gameState.tableCards -> {
                    btn.setBackgroundColor(Color.parseColor("#FF9800")) // оранжевый
                    btn.setTextColor(Color.WHITE)
                }
                card in gameState.discardedCards -> {
                    btn.setBackgroundColor(Color.parseColor("#9E9E9E")) // серый
                    btn.setTextColor(Color.DKGRAY)
                    btn.alpha = 0.5f
                }
                else -> {
                    btn.setBackgroundColor(Color.parseColor("#E3F2FD")) // светло-голубой
                    val textColor = when (card.suit) {
                        Suit.HEARTS, Suit.DIAMONDS -> Color.RED
                        else -> Color.BLACK
                    }
                    btn.setTextColor(textColor)
                    btn.alpha = 1f
                }
            }
        }

        // Мои карты
        val myCardsStr = if (gameState.myCards.isEmpty()) "—"
            else gameState.myCards
                .sortedWith(compareBy<Card> { it.suit }.thenBy { it.rank.value })
                .joinToString("  ") { it.displayName }
        tvMyCards.text = "Мои карты: $myCardsStr"

        // Карты на столе
        val tableStr = if (gameState.tableCards.isEmpty()) "—"
            else gameState.tableCards.joinToString("  ") { it.displayName }
        tvTableCards.text = "На столе: $tableStr"

        // Статистика
        val stats = gameState.getStats()
        tvStats.text = buildString {
            appendLine("Игроков: ${gameState.playerCount} | Козырь: ${gameState.trumpSuit.symbol}")
            appendLine("Моих: ${stats.myCardsCount} | На столе: ${stats.tableCardsCount} | Бито: ${stats.discardedCount}")
            appendLine("Неизвестных: ${stats.remainingUnknown} | Козыри у них: ${stats.unknownTrumps}")
        }.trim()

        // Подсказка
        tvAdvice.text = gameState.getAdvice()
    }

    /** Запросить совет у AI. */
    private fun requestAiAdvice() {
        val prefs = getSharedPreferences("durak_settings", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        val apiTypeStr = prefs.getString("api_type", "GIGACHAT") ?: "GIGACHAT"

        if (apiKey.isEmpty()) {
            tvAiAdvice.text = "API-ключ не задан. Настройте в меню настроек."
            tvAiAdvice.visibility = View.VISIBLE
            return
        }

        val apiType = try {
            AiHelper.ApiType.valueOf(apiTypeStr)
        } catch (e: Exception) {
            AiHelper.ApiType.GIGACHAT
        }

        tvAiAdvice.text = "Загрузка совета от AI..."
        tvAiAdvice.visibility = View.VISIBLE
        btnAiAdvice.isEnabled = false

        val aiHelper = AiHelper(apiType, apiKey)
        aiHelper.getAdvice(gameState) { advice ->
            runOnUiThread {
                tvAiAdvice.text = "AI: $advice"
                btnAiAdvice.isEnabled = true
            }
        }
    }
}
