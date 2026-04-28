package com.durakhelper

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * Оверлей-сервис: плавающее окно поверх игры.
 * Захватывает экран, отправляет в AI Vision, показывает подсказки.
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "durak_overlay"
        const val NOTIFICATION_ID = 1001

        var resultCode: Int = Activity.RESULT_CANCELED
        var resultData: Intent? = null
        var playerCount: Int = 2
        var trumpSuit: Suit = Suit.SPADES

        private const val VIRTUAL_DISPLAY_NAME = "DurakCapture"
        private const val AUTO_SCAN_INTERVAL = 5000L
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var gameState: GameState
    private var cardRecognizer: CardRecognizer? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isExpanded = false
    private var autoScanEnabled = false
    private var autoScanRunnable: Runnable? = null
    private var hasApiKey = false
    private var isOverlayActive = false

    private lateinit var tvStatus: TextView
    private lateinit var tvMyCards: TextView
    private lateinit var tvTableCards: TextView
    private lateinit var tvTrump: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvAdvice: TextView
    private lateinit var btnScan: Button
    private lateinit var btnAutoScan: Button
    private lateinit var btnDiscard: Button
    private lateinit var btnTake: Button
    private lateinit var btnToggle: Button
    private lateinit var panelExpanded: View

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        gameState = GameState(playerCount, trumpSuit)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        initCardRecognizer()
    }

    /** Загрузить API-ключ и создать CardRecognizer. */
    private fun initCardRecognizer() {
        val prefs = getSharedPreferences("durak_settings", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        val apiTypeStr = prefs.getString("api_type", "GIGACHAT") ?: "GIGACHAT"

        hasApiKey = apiKey.isNotEmpty()
        if (hasApiKey) {
            val apiType = if (apiTypeStr == "OPENAI") {
                AiHelper.ApiType.OPENAI
            } else {
                AiHelper.ApiType.GIGACHAT
            }
            cardRecognizer = CardRecognizer(apiType, apiKey)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Дурак Помощник")
            .setContentText("Оверлей активен — AI Vision")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        if (!isOverlayActive) {
            setupMediaProjection()
            createOverlay()
            isOverlayActive = true
        }

        return START_NOT_STICKY
    }

    /** Настроить захват экрана. */
    private fun setupMediaProjection() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val data = resultData ?: return
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopAutoScan()
                virtualDisplay?.release()
                virtualDisplay = null
                imageReader?.close()
                imageReader = null
                mediaProjection = null
            }
        }, handler)

        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler
        )
    }

    /** Создать плавающее окно. */
    private fun createOverlay() {
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_layout, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        btnToggle = overlayView.findViewById(R.id.btnToggle)
        panelExpanded = overlayView.findViewById(R.id.panelExpanded)
        tvStatus = overlayView.findViewById(R.id.tvOverlayStatus)
        tvMyCards = overlayView.findViewById(R.id.tvOverlayMyCards)
        tvTableCards = overlayView.findViewById(R.id.tvOverlayTableCards)
        tvTrump = overlayView.findViewById(R.id.tvOverlayTrump)
        tvStats = overlayView.findViewById(R.id.tvOverlayStats)
        tvAdvice = overlayView.findViewById(R.id.tvOverlayAdvice)
        btnScan = overlayView.findViewById(R.id.btnOverlayScan)
        btnAutoScan = overlayView.findViewById(R.id.btnOverlayAutoScan)
        btnDiscard = overlayView.findViewById(R.id.btnOverlayDiscard)
        btnTake = overlayView.findViewById(R.id.btnOverlayTake)
        val btnClose = overlayView.findViewById<Button>(R.id.btnOverlayClose)
        val btnReset = overlayView.findViewById<Button>(R.id.btnOverlayReset)

        // Показать статус API
        if (!hasApiKey) {
            tvStatus.text = "API не настроен! Откройте Настройки AI"
            btnScan.isEnabled = false
            btnAutoScan.isEnabled = false
        } else {
            tvStatus.text = "Готов к сканированию (AI Vision)"
        }

        btnToggle.setOnClickListener {
            isExpanded = !isExpanded
            panelExpanded.visibility = if (isExpanded) View.VISIBLE else View.GONE
            btnToggle.text = if (isExpanded) "▼" else "♠"
        }

        btnScan.setOnClickListener { captureAndAnalyze() }

        btnAutoScan.setOnClickListener {
            autoScanEnabled = !autoScanEnabled
            btnAutoScan.text = if (autoScanEnabled) "Авто: ВКЛ" else "Авто: ВЫКЛ"
            if (autoScanEnabled) {
                startAutoScan()
            } else {
                stopAutoScan()
            }
        }

        btnDiscard.setOnClickListener {
            gameState.discardTable()
            updateOverlayUI()
            tvStatus.text = "Бито — карты ушли в сброс"
        }

        btnTake.setOnClickListener {
            gameState.takeTableCards()
            updateOverlayUI()
            tvStatus.text = "Забрали — карты у противника"
        }

        btnReset.setOnClickListener {
            gameState.reset()
            updateOverlayUI()
            tvStatus.text = "Сброс — новая игра"
        }

        btnClose.setOnClickListener {
            stopSelf()
        }

        setupDrag(overlayView, params)
        windowManager.addView(overlayView, params)
        panelExpanded.visibility = View.GONE
    }

    /** Перетаскивание оверлея. */
    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        btnToggle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }
    }

    /** Захватить скриншот и проанализировать через AI Vision. */
    private fun captureAndAnalyze() {
        val recognizer = cardRecognizer
        if (recognizer == null) {
            tvStatus.text = "API не настроен! Откройте Настройки AI"
            return
        }

        tvStatus.text = "Сканирование..."

        overlayView.visibility = View.INVISIBLE
        handler.postDelayed({
            val bitmap = captureScreen()
            overlayView.visibility = View.VISIBLE

            if (bitmap != null) {
                recognizer.recognizeCards(bitmap) { result ->
                    handler.post {
                        if (result.isBusy) {
                            tvStatus.text = "Ожидание предыдущего скана..."
                        } else if (result.isError) {
                            tvStatus.text = result.rawResponse
                        } else {
                            gameState.updateFromScan(
                                result.myCards,
                                result.tableCards,
                                result.trumpSuit,
                                result.deckCount
                            )

                            val statusParts = mutableListOf<String>()
                            statusParts.add("Мои: ${result.myCards.size}")
                            statusParts.add("Стол: ${result.tableCards.size}")
                            if (result.trumpSuit != null) {
                                statusParts.add("Козырь: ${result.trumpSuit.symbol}")
                            }
                            if (result.deckCount != null) {
                                statusParts.add("Колода: ${result.deckCount}")
                            }

                            tvStatus.text = statusParts.joinToString(" | ")
                            updateOverlayUI()
                        }
                    }
                    bitmap.recycle()
                }
            } else {
                tvStatus.text = "Ошибка захвата экрана"
            }
        }, 300)
    }

    /** Получить скриншот через MediaProjection. */
    private fun captureScreen(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val tempBitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            tempBitmap.copyPixelsFromBuffer(buffer)

            val croppedBitmap = Bitmap.createBitmap(tempBitmap, 0, 0, screenWidth, screenHeight)
            tempBitmap.recycle()
            return croppedBitmap
        } finally {
            image.close()
        }
    }

    /** Автоматическое сканирование. */
    private fun startAutoScan() {
        autoScanRunnable = object : Runnable {
            override fun run() {
                if (autoScanEnabled) {
                    captureAndAnalyze()
                    handler.postDelayed(this, AUTO_SCAN_INTERVAL)
                }
            }
        }
        handler.postDelayed(autoScanRunnable!!, 1000)
    }

    private fun stopAutoScan() {
        autoScanRunnable?.let { handler.removeCallbacks(it) }
        autoScanRunnable = null
    }

    /** Обновить текст в оверлее. */
    private fun updateOverlayUI() {
        val myCardsStr = if (gameState.myCards.isEmpty()) "—"
        else gameState.myCards
            .sortedWith(compareBy<Card> { it.suit }.thenBy { it.rank.value })
            .joinToString(" ") { it.displayName }

        val tableStr = if (gameState.tableCards.isEmpty()) "—"
        else gameState.tableCards.joinToString(" ") { it.displayName }

        val stats = gameState.getStats()

        tvMyCards.text = "Мои: $myCardsStr"
        tvTableCards.text = "Стол: $tableStr"
        tvTrump.text = "Козырь: ${gameState.trumpSuit.symbol} ${gameState.trumpSuit.displayName}"
        tvStats.text = "Бито: ${stats.discardedCount} | " +
                "Колода: ${stats.remainingInDeck} | " +
                "Козыри: ${stats.myTrumps}м/${stats.unknownTrumps}н"
        tvAdvice.text = gameState.getAdvice()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Дурак Помощник",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Оверлей помощника для карточной игры"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopAutoScan()
        cardRecognizer?.close()
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
        imageReader?.close()
        imageReader = null
        isOverlayActive = false
        try {
            windowManager.removeView(overlayView)
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
