package com.durakhelper

import android.content.Context
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Экран настроек: API-ключ для AI Vision распознавания карт.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val spinnerApiType = findViewById<Spinner>(R.id.spinnerApiType)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)
        val btnBack = findViewById<Button>(R.id.btnBackFromSettings)
        val tvHint = findViewById<TextView>(R.id.tvApiHint)

        val apiTypes = listOf("GigaChat", "OpenAI")
        spinnerApiType.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, apiTypes
        )

        val prefs = getSharedPreferences("durak_settings", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("api_key", "") ?: ""
        val savedType = prefs.getString("api_type", "GIGACHAT") ?: "GIGACHAT"

        etApiKey.setText(savedKey)
        spinnerApiType.setSelection(
            if (savedType == "OPENAI") 1 else 0
        )

        updateHint(tvHint, spinnerApiType.selectedItemPosition)

        spinnerApiType.onItemSelectedListener = object :
            android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: android.view.View?,
                position: Int, id: Long
            ) {
                updateHint(tvHint, position)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        btnSave.setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            val apiType = if (spinnerApiType.selectedItemPosition == 1) "OPENAI" else "GIGACHAT"

            if (apiKey.isEmpty()) {
                Toast.makeText(this, "Введите API-ключ!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit().apply {
                putString("api_key", apiKey)
                putString("api_type", apiType)
                apply()
            }

            Toast.makeText(this, "Настройки сохранены!", Toast.LENGTH_SHORT).show()
            finish()
        }

        btnBack.setOnClickListener {
            finish()
        }
    }

    private fun updateHint(tvHint: TextView, apiTypePosition: Int) {
        tvHint.text = if (apiTypePosition == 0) {
            "GigaChat (рекомендуется для России):\n" +
            "1. Зарегистрируйтесь на developers.sber.ru\n" +
            "2. Создайте проект GigaChat API\n" +
            "3. Скопируйте Client ID и Client Secret\n" +
            "4. Закодируйте в Base64: ClientID:ClientSecret\n" +
            "5. Вставьте Base64-строку сюда\n\n" +
            "Модель: GigaChat-Pro (поддержка Vision)"
        } else {
            "OpenAI:\n" +
            "1. Зарегистрируйтесь на platform.openai.com\n" +
            "2. Создайте API Key в Settings → API Keys\n" +
            "3. Вставьте ключ (sk-...) сюда\n\n" +
            "Модель: GPT-4o-mini (Vision)"
        }
    }
}
