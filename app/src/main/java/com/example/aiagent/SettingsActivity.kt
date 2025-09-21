package com.example.aiagent

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

class SettingsActivity : AppCompatActivity() {
    private lateinit var apiKeyInput: EditText
    private lateinit var saveBtn: Button
    private lateinit var wifiOnlySwitch: Switch
    private lateinit var chargingOnlySwitch: Switch
    private lateinit var startFgBtn: Button
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        apiKeyInput = findViewById(R.id.editApiKey)
        saveBtn = findViewById(R.id.btnSaveApiKey)
        wifiOnlySwitch = findViewById(R.id.switchWifiOnly)
        chargingOnlySwitch = findViewById(R.id.switchChargingOnly)
        startFgBtn = findViewById(R.id.btnStartFg)

        // Load existing key
        StorageUtils.getOpenRouterKey(this)?.let {
            apiKeyInput.setText(it)
        }

        saveBtn.setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            if (key.isNotEmpty()) {
                StorageUtils.setOpenRouterKey(this, key)
                toast("API key saved (encrypted).")
            } else toast("Please paste OpenRouter API key.")
        }

        wifiOnlySwitch.isChecked = PreferencesUtils.getBool(this, "pref_wifi_only", true)
        chargingOnlySwitch.isChecked = PreferencesUtils.getBool(this, "pref_charging_only", false)

        wifiOnlySwitch.setOnCheckedChangeListener { _, checked ->
            PreferencesUtils.putBool(this, "pref_wifi_only", checked)
        }
        chargingOnlySwitch.setOnCheckedChangeListener { _, checked ->
            PreferencesUtils.putBool(this, "pref_charging_only", checked)
        }

        startFgBtn.setOnClickListener {
            NotificationHelper.startForegroundService(this, "Agent active", "Agent running background tasks")
            toast("Foreground service started.")
        }
    }

    private fun toast(s: String){
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}