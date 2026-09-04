package com.gios.brighthermes

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import java.security.MessageDigest

/**
 * What the app remembers: where the gateway is, how to prove itself, and the last deck it saw.
 *
 * The server is a URL the user types, not a constant — `hermes.basilnet.com` is the default
 * and the only thing in this file that is Gio's. Anybody else running the gateway points the
 * app at theirs. Plain SharedPreferences in the app's private storage: the token unlocks a
 * chat with a home server, and a phone that gives up its app data has given up far more.
 */
class Prefs(context: Context) {
    private val p: SharedPreferences = context.applicationContext.getSharedPreferences("brighthermes", Context.MODE_PRIVATE)

    var server: String
        get() = p.getString(K_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(v) = p.edit().putString(K_SERVER, normaliseServer(v)).apply()

    var token: String
        get() = p.getString(K_TOKEN, "") ?: ""
        set(v) = p.edit().putString(K_TOKEN, v.trim()).apply()

    val configured: Boolean get() = token.isNotBlank() && server.isNotBlank()

    /** The last `/deck` body, so the app draws something before the network answers. */
    var cachedDeck: String?
        get() = p.getString(K_DECK, null)
        set(v) = p.edit().putString(K_DECK, v).apply()

    /** How the deck is shown: strip, grid or line. Remembered because it is a preference, not a mode. */
    var deckMode: String
        get() = p.getString(K_MODE, "strip") ?: "strip"
        set(v) = p.edit().putString(K_MODE, v).apply()

    /**
     * A stable id for this install, sent as `X-Device`. Scopes June's session and the layout
     * on the server. Hashed `ANDROID_ID` (the same derivation light-common's `Device` uses),
     * so the same phone gets the same conversation back after a reinstall.
     */
    fun deviceId(context: Context): String {
        p.getString(K_DEVICE, null)?.let { return it }
        val raw = runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull()
            ?: java.util.UUID.randomUUID().toString()
        val id = "lp3-" + MessageDigest.getInstance("SHA-256").digest(("brighthermes:$raw").toByteArray())
            .take(6).joinToString("") { "%02x".format(it) }
        p.edit().putString(K_DEVICE, id).apply()
        return id
    }

    companion object {
        const val DEFAULT_SERVER = "https://hermes.basilnet.com"
        private const val K_SERVER = "server"
        private const val K_TOKEN = "token"
        private const val K_DECK = "deck"
        private const val K_MODE = "deck_mode"
        private const val K_DEVICE = "device"

        /** `hermes.basilnet.com` → `https://hermes.basilnet.com`; a LAN `192.168.68.59:8650` → http. */
        fun normaliseServer(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (s.isEmpty()) return DEFAULT_SERVER
            if (!s.startsWith("http://") && !s.startsWith("https://")) {
                val host = s.substringBefore('/').substringBefore(':')
                val lan = host.startsWith("192.168.") || host.startsWith("10.") || host == "localhost" || host.endsWith(".local")
                s = (if (lan) "http://" else "https://") + s
            }
            return s
        }

        /** `https://host` → `wss://host/ws?token=…`. */
        fun wsUrl(server: String, token: String): String =
            server.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws?token=" + token
    }
}
