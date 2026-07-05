package com.kevinywlui.garageremote

import android.content.Context
import java.security.MessageDigest

/**
 * The shared secret is derived from a PIN the user types: SHA-256 of the
 * PIN, truncated to 16 bytes. The firmware adopts the first secret it ever
 * receives (trust on first use), so the PIN set at provisioning time is the
 * one the door answers to. A new phone just needs the same PIN (after a
 * factory reset of the board to free the bond slot).
 */
object PinStore {
    private const val PREFS = "garage"
    private const val KEY = "pin_secret"
    const val SECRET_LEN = 16

    // Hard floor for newly entered PINs; 6+ is recommended in the UI but
    // not enforced — boards already provisioned with a 4-digit PIN must
    // stay reachable, so length is guidance, not a gate.
    const val MIN_PIN_LENGTH = 4
    const val RECOMMENDED_PIN_LENGTH = 6

    fun isSet(context: Context): Boolean = prefs(context).contains(KEY)

    fun set(context: Context, pin: String) {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(pin.toByteArray(Charsets.UTF_8))
        val secret = digest.copyOf(SECRET_LEN)
        prefs(context).edit()
            .putString(KEY, secret.joinToString("") { "%02x".format(it) })
            .apply()
    }

    fun get(context: Context): ByteArray? =
        prefs(context).getString(KEY, null)
            ?.chunked(2)
            ?.map { it.toInt(16).toByte() }
            ?.toByteArray()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
