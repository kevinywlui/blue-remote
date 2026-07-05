package com.kevinywlui.garageremote

import android.content.Context
import com.kevinywlui.garageremote.ui.theme.AppTheme

/**
 * Non-secret app state: theme choice, the bonded board's MAC (enables the
 * fast direct-reconnect path), and the one-time first-press hint flag.
 */
object AppPrefs {
    private const val PREFS = "garage"
    private const val KEY_THEME = "theme"
    private const val KEY_DEVICE_MAC = "device_mac"
    private const val KEY_HINT_SEEN = "first_press_hint_seen"

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

    fun firstPressHintSeen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HINT_SEEN, false)

    fun setFirstPressHintSeen(context: Context, seen: Boolean) {
        prefs(context).edit().putBoolean(KEY_HINT_SEEN, seen).apply()
    }
}
