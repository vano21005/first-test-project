package com.durakhelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Оверлей-трекер карт поверх игры. Без AI, без интернета.
 * Плавающая кнопка + компактная панель с сеткой карт.
 */
class OverlayTrackerService : Service() {

    companion object {
        const val CHANNEL_ID = "durak_tracker"
        const val NOTIFICATION_ID = 2001
        const val EXTRA_PLAYER_COUNT = "player_count"
        const val EXTRA_TRUMP_INDEX = "trump_index"
        const val ACTION_STOP = "com.durakhelper.STOP_OVERLAY"
    }

    private enum class EditMode { MY_CARDS, TABLE, DISCARD, REMOVE }

    private lateinit var wm: WindowManager
    private var fabView: View? = null
    private var panelView: View? = null
    private lateinit var gameState: GameState
    private var currentMode = EditMode.MY_CARDS
    private var panelVisible = false

    private val cardViews = linkedMapOf<Card, TextView>()
    private lateinit var tvStats: TextView
    private lateinit var tvAdvice: TextView
    private lateinit var tvMyCards: TextView
    private lateinit var tvTableCards: TextView
    private lateinit var btnModeMy: TextView
    private lateinit var btnModeTable: TextView
    private lateinit var btnModeDiscard: TextView
    private lateinit var btnModeRemove: TextView

    private lateinit var fabParams: WindowManager.LayoutParams
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var fabStartX = 0
    private var fabStartY = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        removeFab()
        removePanel()

        val playerCount = intent?.getIntExtra(EXTRA_PLAYER_COUNT, 2) ?: 2
        val trumpIndex = intent?.getIntExtra(EXTRA_TRUMP_INDEX, 0) ?: 0
        gameState = GameState(playerCount, Suit.entries[trumpIndex])

        createFab()
        createPanel()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        removeFab()
        removePanel()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    // ---------- FAB ----------

    private fun createFab() {
        val size = dp(56)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF1B5E20.toInt())
            setStroke(dp(2), 0xFF66BB6A.toInt())
        }
        val btn = TextView(this).apply {
            text = "\u2660"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = bg
            elevation = dp(6).toFloat()
        }

        fabParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(4)
            y = dp(200)
        }

        btn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    fabStartX = fabParams.x
                    fabStartY = fabParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    fabParams.x = (fabStartX + (event.rawX - touchStartX)).toInt()
                    fabParams.y = (fabStartY + (event.rawY - touchStartY)).toInt()
                    try { wm.updateViewLayout(fabView, fabParams) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(event.rawX - touchStartX) < dp(10) &&
                        abs(event.rawY - touchStartY) < dp(10)) {
                        togglePanel()
                    }
                    true
                }
                else -> false
            }
        }

        wm.addView(btn, fabParams)
        fabView = btn
    }

    // ---------- Panel ----------

    private fun togglePanel() {
        panelVisible = !panelVisible
        panelView?.visibility = if (panelVisible) View.VISIBLE else View.GONE
        if (panelVisible) updateUi()
    }

    private fun createPanel() {
        val panel = buildPanelView()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(24)
        }
        panel.visibility = View.GONE
        wm.addView(panel, params)
        panelView = panel
    }

    private fun buildPanelView(): View {
        val ctx: Context = this

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(0xF0111111.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0xFF388E3C.toInt())
            }
        }

        // --- Козырь + режимы + свернуть ---
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(bottomMargin = dp(4))
        }

        val tvTrump = TextView(ctx).apply {
            text = "\u2660"
            textSize = 20f
            setTextColor(0xFF66BB6A.toInt())
            setPadding(dp(6), 0, dp(6), 0)
        }
        headerRow.addView(tvTrump)

        btnModeMy = makeModeBtn("Мои") {
            currentMode = EditMode.MY_CARDS; updateModeButtons()
        }
        btnModeTable = makeModeBtn("Стол") {
            currentMode = EditMode.TABLE; updateModeButtons()
        }
        btnModeDiscard = makeModeBtn("Бито") {
            currentMode = EditMode.DISCARD; updateModeButtons()
        }
        btnModeRemove = makeModeBtn("Убр") {
            currentMode = EditMode.REMOVE; updateModeButtons()
        }
        val btnClose = makeModeBtn("\u2014") { togglePanel() }

        headerRow.addView(btnModeMy)
        headerRow.addView(btnModeTable)
        headerRow.addView(btnModeDiscard)
        headerRow.addView(btnModeRemove)
        headerRow.addView(btnClose)
        root.addView(headerRow)

        // --- Мои / Стол (краткая инфо) ---
        val infoRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(bottomMargin = dp(3))
        }
        tvMyCards = TextView(ctx).apply {
            textSize = 11f
            setTextColor(0xFF81C784.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "Мои: \u2014"
        }
        tvTableCards = TextView(ctx).apply {
            textSize = 11f
            setTextColor(0xFF64B5F6.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "Стол: \u2014"
        }
        infoRow.addView(tvMyCards)
        infoRow.addView(tvTableCards)
        root.addView(infoRow)

        // --- Сетка карт: 4 ряда (масти) × 9 столбцов (ранги) ---
        val gridContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(bottomMargin = dp(4))
        }

        for (suit in Suit.entries) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = lp(bottomMargin = dp(2))
            }

            // Метка масти
            val suitColor = if (suit == Suit.HEARTS || suit == Suit.DIAMONDS)
                0xFFEF5350.toInt() else 0xFFE0E0E0.toInt()

            val suitLabel = TextView(ctx).apply {
                text = suit.symbol
                textSize = 14f
                setTextColor(suitColor)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(34))
            }
            row.addView(suitLabel)

            for (rank in Rank.entries) {
                val card = Card(rank, suit)
                val cardBtn = TextView(ctx).apply {
                    text = rank.shortName
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTextColor(suitColor)
                    background = GradientDrawable().apply {
                        setColor(0xFF2A2A2A.toInt())
                        cornerRadius = dp(4).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                        marginStart = dp(1)
                        marginEnd = dp(1)
                    }
                    setOnClickListener { applyCardAction(card) }
                }
                row.addView(cardBtn)
                cardViews[card] = cardBtn
            }

            gridContainer.addView(row)
        }

        root.addView(gridContainer)

        // --- Быстрые действия ---
        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(bottomMargin = dp(4))
        }
        actionRow.addView(makeActionBtn("\u2192 Бито") {
            gameState.discardTable(); updateUi()
        })
        actionRow.addView(makeActionBtn("Забрали") {
            gameState.takeTableCards(); updateUi()
        })
        actionRow.addView(makeActionBtn("Новая") {
            gameState.reset()
            currentMode = EditMode.MY_CARDS
            updateModeButtons()
            updateUi()
        })
        actionRow.addView(makeActionBtn("Стоп") {
            stopSelf()
        })
        root.addView(actionRow)

        // --- Статистика ---
        tvStats = TextView(ctx).apply {
            textSize = 11f
            setTextColor(0xFFB0BEC5.toInt())
            layoutParams = lp(bottomMargin = dp(2))
            text = ""
        }
        root.addView(tvStats)

        // --- Подсказка ---
        tvAdvice = TextView(ctx).apply {
            textSize = 12f
            setTextColor(0xFFFFD54F.toInt())
            setTypeface(null, Typeface.BOLD)
            text = ""
        }
        root.addView(tvAdvice)

        updateModeButtons()
        return root
    }

    private fun makeModeBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(6), dp(2), dp(6))
            background = GradientDrawable().apply {
                setColor(0xFF616161.toInt())
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun makeActionBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(6), dp(2), dp(6))
            background = GradientDrawable().apply {
                setColor(0xFF37474F.toInt())
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun lp(bottomMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { this.bottomMargin = bottomMargin }

    // ---------- Logic ----------

    private fun applyCardAction(card: Card) {
        when (currentMode) {
            EditMode.MY_CARDS -> {
                if (card in gameState.myCards) gameState.removeMyCard(card)
                else gameState.addMyCard(card)
            }
            EditMode.TABLE -> {
                if (card in gameState.tableCards) gameState.tableCards.remove(card)
                else gameState.addTableCard(card)
            }
            EditMode.DISCARD -> {
                if (card in gameState.discardedCards) gameState.discardedCards.remove(card)
                else {
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
        styleMode(btnModeMy, currentMode == EditMode.MY_CARDS, 0xFF2E7D32.toInt())
        styleMode(btnModeTable, currentMode == EditMode.TABLE, 0xFF1565C0.toInt())
        styleMode(btnModeDiscard, currentMode == EditMode.DISCARD, 0xFF6D4C41.toInt())
        styleMode(btnModeRemove, currentMode == EditMode.REMOVE, 0xFFC62828.toInt())
    }

    private fun styleMode(btn: TextView, active: Boolean, color: Int) {
        val bg = btn.background as? GradientDrawable ?: return
        bg.setColor(if (active) color else 0xFF616161.toInt())
        if (active) bg.setStroke(dp(2), Color.WHITE) else bg.setStroke(0, 0)
    }

    private fun updateUi() {
        val stats = gameState.getStats()

        tvMyCards.text = "Мои (${stats.myCardsCount}): ${formatShort(gameState.myCards)}"
        tvTableCards.text = "Стол (${stats.tableCardsCount}): ${formatShort(gameState.tableCards)}"

        tvStats.text = buildString {
            append("Колода: ${stats.remainingInDeck}")
            append(" | Козыри: ${stats.myTrumps}/${stats.unknownTrumps}")
            append(" | Бито: ${stats.discardedCount}")
        }

        tvAdvice.text = gameState.getAdvice()

        cardViews.forEach { (card, tv) ->
            val bg = tv.background as? GradientDrawable ?: return@forEach
            when {
                card in gameState.myCards -> {
                    bg.setColor(0xFF2E7D32.toInt())
                    tv.setTextColor(Color.WHITE)
                }
                card in gameState.tableCards -> {
                    bg.setColor(0xFF1565C0.toInt())
                    tv.setTextColor(Color.WHITE)
                }
                card in gameState.discardedCards -> {
                    bg.setColor(0xFF3E2723.toInt())
                    tv.setTextColor(0xFF757575.toInt())
                }
                else -> {
                    bg.setColor(0xFF2A2A2A.toInt())
                    tv.setTextColor(
                        if (card.suit == Suit.HEARTS || card.suit == Suit.DIAMONDS)
                            0xFFEF5350.toInt() else 0xFFE0E0E0.toInt()
                    )
                }
            }
        }
    }

    private fun formatShort(cards: Set<Card>): String {
        if (cards.isEmpty()) return "\u2014"
        return cards.sortedWith(compareBy<Card> { it.suit.ordinal }.thenBy { it.rank.value })
            .joinToString(" ") { it.displayName }
    }

    // ---------- Notification ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Дурак Помощник", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Трекер карт работает" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, OverlayTrackerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Дурак Помощник")
                .setContentText("Нажмите \u2660 поверх игры")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(openIntent)
                .addAction(Notification.Action.Builder(
                    null, "Стоп", stopPending
                ).build())
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Дурак Помощник")
                .setContentText("Нажмите \u2660 поверх игры")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(openIntent)
                .build()
        }
    }

    // ---------- Cleanup ----------

    private fun removeFab() {
        fabView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        fabView = null
    }

    private fun removePanel() {
        panelView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panelView = null
        cardViews.clear()
    }
}
