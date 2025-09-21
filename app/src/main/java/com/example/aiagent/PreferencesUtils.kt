package com.example.aiagent

import android.content.Context

object PreferencesUtils {
    private const val PREF = "agent_settings"
    fun putBool(ctx: Context, key: String, value: Boolean) {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        p.edit().putBoolean(key, value).apply()
    }
    fun getBool(ctx: Context, key: String, def: Boolean): Boolean {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return p.getBoolean(key, def)
    }
}