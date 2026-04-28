package com.durakhelper

import android.app.Activity
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
 * Главный экран: настройка игры и запуск оверлея.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var spinnerPlayers: Spinner
    private lateinit var spinnerTrump: Spinner
    private lateinit var tvOverlayStatus: TextView

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
            OverlayService.trumpSuit = Suit.entries[spinnerTrump.selectedItemPosition]

            startOverlayService()
        } else {
            Toast.makeText(this, "Нужно разрешить захват экрана", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerPlayers = findViewById(R.id.spinnerPlayers)
        spinnerTrump = findViewById(R.id.spinnerTrump)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        val btnStart = findViewById<Button>(R.id.btnStartGame)
        val btnSettings = findViewById<Button>(R.id.btnSettings)

        // Количество игроков: 2-6
        val playerOptions = (2..6).map { "$it игроков" }
        spinnerPlayers.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, playerOptions
        )

        // Выбор козыря
        val trumpOptions = Suit.entries.map { "${it.symbol} ${it.displayName}" }
        spinnerTrump.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, trumpOptions
        )

        btnStart.setOnClickListener {
            checkOverlayPermissionAndStart()
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    /** Проверить разрешение на оверлей и запросить захват экрана. */
    private fun checkOverlayPermissionAndStart() {
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

    /** Запросить разрешение на захват экрана. */
    private fun requestScreenCapture() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
        tvOverlayStatus.text = "Разрешите захват экрана..."
    }

    /** Запустить оверлей-сервис. */
    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        tvOverlayStatus.text = "Оверлей запущен! Откройте игру «Дурак»"
        Toast.makeText(this, "Помощник запущен! Переключитесь в игру.", Toast.LENGTH_LONG).show()

        // Свернуть приложение
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
