package com.durakhelper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Главный экран: настройка и запуск оверлея.
 * Козырь определяется автоматически AI Vision с экрана.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var spinnerPlayers: Spinner
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvApiStatus: TextView

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST = 1001
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            OverlayService.resultCode = result.resultCode
            OverlayService.resultData = result.data
            OverlayService.playerCount = spinnerPlayers.selectedItemPosition + 2
            OverlayService.trumpSuit = Suit.SPADES

            startOverlayService()
        } else {
            Toast.makeText(this, "Нужно разрешить захват экрана", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerPlayers = findViewById(R.id.spinnerPlayers)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvApiStatus = findViewById(R.id.tvApiStatus)
        val btnStart = findViewById<Button>(R.id.btnStartGame)
        val btnSettings = findViewById<Button>(R.id.btnSettings)

        val playerOptions = (2..6).map { "$it игроков" }
        spinnerPlayers.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, playerOptions
        )

        btnStart.setOnClickListener {
            checkOverlayPermissionAndStart()
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateApiStatus()
    }

    /** Показать статус API-ключа. */
    private fun updateApiStatus() {
        val prefs = getSharedPreferences("durak_settings", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        val apiType = prefs.getString("api_type", "GIGACHAT") ?: "GIGACHAT"

        if (apiKey.isEmpty()) {
            tvApiStatus.text = "API не настроен — нажмите «Настройки AI»"
            tvApiStatus.setTextColor(0xFFFF5722.toInt())
        } else {
            val typeName = if (apiType == "OPENAI") "OpenAI" else "GigaChat"
            tvApiStatus.text = "API: $typeName (настроен)"
            tvApiStatus.setTextColor(0xFF4CAF50.toInt())
        }
    }

    private fun checkOverlayPermissionAndStart() {
        val prefs = getSharedPreferences("durak_settings", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isEmpty()) {
            tvOverlayStatus.text = "Сначала настройте API-ключ в Настройках AI!"
            Toast.makeText(this, "Нужен API-ключ для распознавания карт", Toast.LENGTH_LONG).show()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            tvOverlayStatus.text = "Нужно разрешение на показ поверх приложений"
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            @Suppress("DEPRECATION")
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
            return
        }

        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
        tvOverlayStatus.text = "Разрешите захват экрана..."
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        tvOverlayStatus.text = "Оверлей запущен! Откройте игру «Дурак»"
        Toast.makeText(this, "Помощник запущен! Переключитесь в игру.", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (Settings.canDrawOverlays(this)) {
                requestScreenCapture()
            } else {
                tvOverlayStatus.text = "Разрешение не получено"
                Toast.makeText(this, "Без разрешения оверлей не работает", Toast.LENGTH_LONG).show()
            }
        }
    }
}
