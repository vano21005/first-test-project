package com.durakhelper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val OVERLAY_REQUEST_CODE = 100
    }

    private lateinit var spinnerPlayers: Spinner
    private lateinit var spinnerTrump: Spinner
    private lateinit var tvStatus: TextView
    private lateinit var etApiKey: EditText
    private lateinit var tvApiStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerPlayers = findViewById(R.id.spinnerPlayers)
        spinnerTrump = findViewById(R.id.spinnerTrump)
        tvStatus = findViewById(R.id.tvOverlayStatus)
        etApiKey = findViewById(R.id.etApiKey)
        tvApiStatus = findViewById(R.id.tvApiStatus)

        val playerOptions = (2..6).map { "$it \u0438\u0433\u0440\u043e\u043a\u043e\u0432" }
        spinnerPlayers.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, playerOptions
        )

        val trumpOptions = Suit.entries.map { "${it.symbol} ${it.displayName}" }
        spinnerTrump.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, trumpOptions
        )

        loadSavedApiKey()

        findViewById<Button>(R.id.btnSaveApi).setOnClickListener {
            saveApiKey()
        }

        findViewById<Button>(R.id.btnStartGame).setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                launchOverlay()
            } else {
                requestOverlayPermission()
            }
        }

        findViewById<Button>(R.id.btnStopOverlay).setOnClickListener {
            val intent = Intent(this, OverlayTrackerService::class.java).apply {
                action = OverlayTrackerService.ACTION_STOP
            }
            stopService(intent)
            tvStatus.text = "\u041e\u0432\u0435\u0440\u043b\u0435\u0439 \u043e\u0441\u0442\u0430\u043d\u043e\u0432\u043b\u0435\u043d"
        }
    }

    private fun loadSavedApiKey() {
        val prefs = getSharedPreferences("durak_settings", Context.MODE_PRIVATE)
        val saved = prefs.getString("gigachat_key", "") ?: ""
        if (saved.isNotEmpty()) {
            etApiKey.setText(saved)
            tvApiStatus.text = "API \u043a\u043b\u044e\u0447 \u0441\u043e\u0445\u0440\u0430\u043d\u0451\u043d \u2714"
            tvApiStatus.setTextColor(0xFF81C784.toInt())
        } else {
            tvApiStatus.text = "\u0412\u0441\u0442\u0430\u0432\u044c\u0442\u0435 Base64 \u043a\u043b\u044e\u0447 \u0438 \u043d\u0430\u0436\u043c\u0438\u0442\u0435 \u0421\u043e\u0445\u0440\u0430\u043d\u0438\u0442\u044c"
            tvApiStatus.setTextColor(0xFFFFD54F.toInt())
        }
    }

    private fun saveApiKey() {
        val key = etApiKey.text.toString().trim()

        if (key.isEmpty()) {
            getSharedPreferences("durak_settings", Context.MODE_PRIVATE)
                .edit().remove("gigachat_key").apply()
            tvApiStatus.text = "API \u043a\u043b\u044e\u0447 \u0443\u0434\u0430\u043b\u0451\u043d"
            tvApiStatus.setTextColor(0xFFFFD54F.toInt())
            Toast.makeText(this, "API \u043a\u043b\u044e\u0447 \u0443\u0434\u0430\u043b\u0451\u043d", Toast.LENGTH_SHORT).show()
            return
        }

        getSharedPreferences("durak_settings", Context.MODE_PRIVATE)
            .edit().putString("gigachat_key", key).apply()

        tvApiStatus.text = "API \u043a\u043b\u044e\u0447 \u0441\u043e\u0445\u0440\u0430\u043d\u0451\u043d \u2714"
        tvApiStatus.setTextColor(0xFF81C784.toInt())
        Toast.makeText(this, "GigaChat API \u0441\u043e\u0445\u0440\u0430\u043d\u0451\u043d", Toast.LENGTH_SHORT).show()
    }

    private fun requestOverlayPermission() {
        tvStatus.text = "\u041d\u0443\u0436\u043d\u043e \u0440\u0430\u0437\u0440\u0435\u0448\u0435\u043d\u0438\u0435 \u00ab\u041f\u043e\u0432\u0435\u0440\u0445 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0439\u00bb"
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        @Suppress("DEPRECATION")
        startActivityForResult(intent, OVERLAY_REQUEST_CODE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_REQUEST_CODE && Settings.canDrawOverlays(this)) {
            launchOverlay()
        }
    }

    private fun launchOverlay() {
        val intent = Intent(this, OverlayTrackerService::class.java).apply {
            putExtra(OverlayTrackerService.EXTRA_PLAYER_COUNT, spinnerPlayers.selectedItemPosition + 2)
            putExtra(OverlayTrackerService.EXTRA_TRUMP_INDEX, spinnerTrump.selectedItemPosition)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        tvStatus.text = "\u041e\u0432\u0435\u0440\u043b\u0435\u0439 \u0437\u0430\u043f\u0443\u0449\u0435\u043d! \u041d\u0430\u0436\u043c\u0438\u0442\u0435 \u2660 \u043f\u043e\u0432\u0435\u0440\u0445 \u0438\u0433\u0440\u044b"
        Toast.makeText(this, "\u041f\u043e\u043c\u043e\u0449\u043d\u0438\u043a \u0437\u0430\u043f\u0443\u0449\u0435\u043d! \u041e\u0442\u043a\u0440\u043e\u0439\u0442\u0435 \u0438\u0433\u0440\u0443.", Toast.LENGTH_SHORT).show()
    }
}
