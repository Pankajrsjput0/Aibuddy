package com.example.aiagent

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

object StorageUtils {
    private const val PREF_FILE = "agent_prefs"

    fun getOpenRouterKey(context: Context): String? {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val prefs = EncryptedSharedPreferences.create(context, PREF_FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        return prefs.getString("openrouter_key", null)
    }

    fun setOpenRouterKey(context: Context, key: String) {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val prefs = EncryptedSharedPreferences.create(context, PREF_FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        prefs.edit().putString("openrouter_key", key).apply()
    }

    fun getTaskDir(context: Context, taskId: String): File {
        val dir = File(context.filesDir, "tasks/$taskId")
        dir.mkdirs()
        return dir
    }
}