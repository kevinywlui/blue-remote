package com.kevinywlui.garageremote

import android.content.Context
import com.kevinywlui.garageremote.ui.theme.AppTheme

/**
 * App state: theme choice and the bonded board's MAC (enables the fast
 * direct-reconnect path).
 */
object AppPrefs {
    private const val PREFS = "garage"
    private const val KEY_THEME = "theme"
    private const val KEY_DEVICE_MAC = "device_mac"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun theme(context: Context): AppTheme =
        AppTheme.fromName(prefs(context).getString(KEY_THEME, null))

    fun setTheme(context: Context, theme: AppTheme) {
        prefs(context).edit().putString(KEY_THEME, theme.name).apply()
    }

    fun deviceMac(context: Context): String? =
        prefs(context).getString(KEY_DEVICE_MAC, null)

    fun setDeviceMac(context: Context, mac: String?) {
        prefs(context).edit().apply {
            if (mac == null) remove(KEY_DEVICE_MAC) else putString(KEY_DEVICE_MAC, mac)
        }.apply()
    }
}
