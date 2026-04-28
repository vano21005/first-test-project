package com.durakhelper

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Главный экран: настройка игры (козырь, количество игроков).
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val spinnerPlayers = findViewById<Spinner>(R.id.spinnerPlayers)
        val spinnerTrump = findViewById<Spinner>(R.id.spinnerTrump)
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
            val playerCount = spinnerPlayers.selectedItemPosition + 2
            val trumpSuit = Suit.entries[spinnerTrump.selectedItemPosition]

            val intent = Intent(this, GameActivity::class.java).apply {
                putExtra("playerCount", playerCount)
                putExtra("trumpSuit", trumpSuit.name)
            }
            startActivity(intent)
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
}
