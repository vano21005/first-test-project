package com.durakhelper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        private const val OVERLAY_REQUEST_CODE = 100
        private const val PREFS = "durak_settings"
        private const val KEY_PROFILES = "prompt_profiles"
        private const val KEY_ACTIVE_PROFILE = "active_profile_name"
        private const val KEY_ACTIVE_PROMPT = "active_prompt"
        private const val DEFAULT_PROFILE_NAME = "\u0414\u0435\u0444\u043e\u043b\u0442\u043d\u044b\u0439"
    }

    private lateinit var spinnerPlayers: Spinner
    private lateinit var spinnerTrump: Spinner
    private lateinit var tvStatus: TextView
    private lateinit var etApiKey: EditText
    private lateinit var tvApiStatus: TextView
    private lateinit var spinnerPromptProfile: Spinner
    private lateinit var etPromptText: EditText
    private lateinit var etNewProfileName: EditText

    private var profileNames = mutableListOf<String>()
    private var profiles = mutableMapOf<String, String>()
    private var suppressSpinnerEvent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerPlayers = findViewById(R.id.spinnerPlayers)
        spinnerTrump = findViewById(R.id.spinnerTrump)
        tvStatus = findViewById(R.id.tvOverlayStatus)
        etApiKey = findViewById(R.id.etApiKey)
        tvApiStatus = findViewById(R.id.tvApiStatus)
        spinnerPromptProfile = findViewById(R.id.spinnerPromptProfile)
        etPromptText = findViewById(R.id.etPromptText)
        etNewProfileName = findViewById(R.id.etNewProfileName)

        val playerOptions = (2..6).map { "$it \u0438\u0433\u0440\u043e\u043a\u043e\u0432" }
        spinnerPlayers.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, playerOptions
        )

        val trumpOptions = Suit.entries.map { "${it.symbol} ${it.displayName}" }
        spinnerTrump.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, trumpOptions
        )

        loadSavedApiKey()
        loadProfiles()

        findViewById<Button>(R.id.btnSaveApi).setOnClickListener { saveApiKey() }
        findViewById<Button>(R.id.btnSavePrompt).setOnClickListener { saveCurrentPrompt() }
        findViewById<Button>(R.id.btnDeletePrompt).setOnClickListener { deleteCurrentProfile() }
        findViewById<Button>(R.id.btnAddProfile).setOnClickListener { addNewProfile() }

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

        spinnerPromptProfile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSpinnerEvent) { suppressSpinnerEvent = false; return }
                val name = profileNames.getOrNull(position) ?: return
                val text = profiles[name] ?: AiHelper.DEFAULT_PROMPT_TEMPLATE
                etPromptText.setText(text)
                saveActiveProfile(name, text)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadSavedApiKey() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString("gigachat_key", "") ?: ""
        if (saved.isNotEmpty()) {
            etApiKey.setText(saved)
            tvApiStatus.text = "API \u043a\u043b\u044e\u0447 \u0441\u043e\u0445\u0440\u0430\u043d\u0451\u043d"
            tvApiStatus.setTextColor(0xFF81C784.toInt())
        } else {
            tvApiStatus.text = "\u0412\u0441\u0442\u0430\u0432\u044c\u0442\u0435 Base64 \u043a\u043b\u044e\u0447 \u0438 \u043d\u0430\u0436\u043c\u0438\u0442\u0435 \u0421\u043e\u0445\u0440\u0430\u043d\u0438\u0442\u044c"
            tvApiStatus.setTextColor(0xFFFFD54F.toInt())
        }
    }

    private fun saveApiKey() {
        val key = etApiKey.text.toString().trim()
        if (key.isEmpty()) {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove("gigachat_key").apply()
            tvApiStatus.text = "API \u043a\u043b\u044e\u0447 \u0443\u0434\u0430\u043b\u0451\u043d"
            tvApiStatus.setTextColor(0xFFFFD54F.toInt())
            Toast.makeText(this, "API \u043a\u043b\u044e\u0447 \u0443\u0434\u0430\u043b\u0451\u043d", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("gigachat_key", key).apply()
        tvApiStatus.text = "API \u043a\u043b\u044e\u0447 \u0441\u043e\u0445\u0440\u0430\u043d\u0451\u043d"
        tvApiStatus.setTextColor(0xFF81C784.toInt())
        Toast.makeText(this, "GigaChat API \u0441\u043e\u0445\u0440\u0430\u043d\u0451\u043d", Toast.LENGTH_SHORT).show()
    }

    // --- Prompt profiles ---

    private fun loadProfiles() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PROFILES, null)
        profiles.clear()
        profiles[DEFAULT_PROFILE_NAME] = AiHelper.DEFAULT_PROMPT_TEMPLATE
        if (json != null) {
            try {
                val obj = JSONObject(json)
                for (key in obj.keys()) {
                    profiles[key] = obj.getString(key)
                }
            } catch (_: Exception) {}
        }
        profileNames = profiles.keys.toMutableList()
        refreshProfileSpinner()

        val activeName = prefs.getString(KEY_ACTIVE_PROFILE, DEFAULT_PROFILE_NAME) ?: DEFAULT_PROFILE_NAME
        val idx = profileNames.indexOf(activeName).coerceAtLeast(0)
        suppressSpinnerEvent = true
        spinnerPromptProfile.setSelection(idx)
        etPromptText.setText(profiles[profileNames[idx]] ?: AiHelper.DEFAULT_PROMPT_TEMPLATE)
    }

    private fun refreshProfileSpinner() {
        profileNames = profiles.keys.toMutableList()
        spinnerPromptProfile.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, profileNames
        )
    }

    private fun persistProfiles() {
        val obj = JSONObject()
        for ((k, v) in profiles) {
            if (k != DEFAULT_PROFILE_NAME) {
                obj.put(k, v)
            }
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROFILES, obj.toString()).apply()
    }

    private fun saveActiveProfile(name: String, text: String) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACTIVE_PROFILE, name)
            .putString(KEY_ACTIVE_PROMPT, if (name == DEFAULT_PROFILE_NAME) "" else text)
            .apply()
    }

    private fun saveCurrentPrompt() {
        val idx = spinnerPromptProfile.selectedItemPosition
        val name = profileNames.getOrNull(idx) ?: return
        val text = etPromptText.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "\u041f\u0440\u043e\u043c\u043f\u0442 \u043d\u0435 \u043c\u043e\u0436\u0435\u0442 \u0431\u044b\u0442\u044c \u043f\u0443\u0441\u0442\u044b\u043c", Toast.LENGTH_SHORT).show()
            return
        }
        profiles[name] = text
        persistProfiles()
        saveActiveProfile(name, text)
        Toast.makeText(this, "\u041f\u0440\u043e\u043c\u043f\u0442 \u00ab$name\u00bb \u0441\u043e\u0445\u0440\u0430\u043d\u0451\u043d", Toast.LENGTH_SHORT).show()
    }

    private fun deleteCurrentProfile() {
        val idx = spinnerPromptProfile.selectedItemPosition
        val name = profileNames.getOrNull(idx) ?: return
        if (name == DEFAULT_PROFILE_NAME) {
            Toast.makeText(this, "\u0414\u0435\u0444\u043e\u043b\u0442\u043d\u044b\u0439 \u043d\u0435\u043b\u044c\u0437\u044f \u0443\u0434\u0430\u043b\u0438\u0442\u044c", Toast.LENGTH_SHORT).show()
            return
        }
        profiles.remove(name)
        persistProfiles()
        suppressSpinnerEvent = true
        refreshProfileSpinner()
        spinnerPromptProfile.setSelection(0)
        etPromptText.setText(profiles[DEFAULT_PROFILE_NAME])
        saveActiveProfile(DEFAULT_PROFILE_NAME, "")
        Toast.makeText(this, "\u041f\u0440\u043e\u0444\u0438\u043b\u044c \u00ab$name\u00bb \u0443\u0434\u0430\u043b\u0451\u043d", Toast.LENGTH_SHORT).show()
    }

    private fun addNewProfile() {
        val name = etNewProfileName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "\u0412\u0432\u0435\u0434\u0438\u0442\u0435 \u0438\u043c\u044f \u043f\u0440\u043e\u0444\u0438\u043b\u044f", Toast.LENGTH_SHORT).show()
            return
        }
        if (profiles.containsKey(name)) {
            Toast.makeText(this, "\u041f\u0440\u043e\u0444\u0438\u043b\u044c \u00ab$name\u00bb \u0443\u0436\u0435 \u0441\u0443\u0449\u0435\u0441\u0442\u0432\u0443\u0435\u0442", Toast.LENGTH_SHORT).show()
            return
        }
        profiles[name] = AiHelper.DEFAULT_PROMPT_TEMPLATE
        persistProfiles()
        refreshProfileSpinner()
        val newIdx = profileNames.indexOf(name)
        suppressSpinnerEvent = true
        spinnerPromptProfile.setSelection(newIdx)
        etPromptText.setText(AiHelper.DEFAULT_PROMPT_TEMPLATE)
        etNewProfileName.setText("")
        saveActiveProfile(name, AiHelper.DEFAULT_PROMPT_TEMPLATE)
        Toast.makeText(this, "\u041f\u0440\u043e\u0444\u0438\u043b\u044c \u00ab$name\u00bb \u0441\u043e\u0437\u0434\u0430\u043d", Toast.LENGTH_SHORT).show()
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
