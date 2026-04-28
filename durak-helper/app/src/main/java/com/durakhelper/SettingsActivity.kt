package com.durakhelper

import android.content.Context
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Экран настроек: API-ключ для AI-подсказок.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val spinnerApiType = findViewById<Spinner>(R.id.spinnerApiType)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)
        val btnBack = findViewById<Button>(R.id.btnBackFromSettings)

        // Выбор типа API
        val apiTypes = listOf("GigaChat", "OpenAI")
        spinnerApiType.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, apiTypes
        )

        // Загрузить сохранённые настройки
        val prefs = getSharedPreferences("durak_settings", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("api_key", "") ?: ""
        val savedType = prefs.getString("api_type", "GIGACHAT") ?: "GIGACHAT"

        etApiKey.setText(savedKey)
        spinnerApiType.setSelection(
            if (savedType == "OPENAI") 1 else 0
        )

        btnSave.setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            val apiType = if (spinnerApiType.selectedItemPosition == 1) "OPENAI" else "GIGACHAT"

            prefs.edit().apply {
                putString("api_key", apiKey)
                putString("api_type", apiType)
                apply()
            }

            Toast.makeText(this, "Настройки сохранены!", Toast.LENGTH_SHORT).show()
        }

        btnBack.setOnClickListener {
            finish()
        }
    }
}
