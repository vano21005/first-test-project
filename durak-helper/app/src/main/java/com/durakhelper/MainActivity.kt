package com.durakhelper

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Главный экран офлайн-режима.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var spinnerPlayers: Spinner
    private lateinit var spinnerTrump: Spinner
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerPlayers = findViewById(R.id.spinnerPlayers)
        spinnerTrump = findViewById(R.id.spinnerTrump)
        tvStatus = findViewById(R.id.tvOverlayStatus)
        val btnStart = findViewById<Button>(R.id.btnStartGame)

        val playerOptions = (2..6).map { "$it игроков" }
        spinnerPlayers.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            playerOptions
        )

        val trumpOptions = Suit.entries.map { "${it.symbol} ${it.displayName}" }
        spinnerTrump.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            trumpOptions
        )

        btnStart.setOnClickListener {
            val intent = Intent(this, TrackerActivity::class.java).apply {
                putExtra("player_count", spinnerPlayers.selectedItemPosition + 2)
                putExtra("trump_index", spinnerTrump.selectedItemPosition)
            }
            startActivity(intent)
            tvStatus.text = "Открыт офлайн-трекер карт"
        }
    }
}
