package com.gios.brighthermes.deck

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.gios.brighthermes.Prefs
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The one card June can put on the lock face, and how it gets to the phone with the app closed.
 *
 * BrightControl's face queries `content://com.gios.brighthermes.deck/lock` on every show and every
 * wake (see its `LockHermes`). The app is usually not running then, so the provider cannot wait for
 * a socket: it answers from the cache **at once**, and — only if the screen is on — asks the gateway
 * in the background with a short timeout, writes what comes back, and calls `notifyChange` if it
 * differs. The face is observing, so a card posted while the phone lay dark appears on the first
 * wake after it, about a second in. That is the pull half of "take over the lock screen" without a
 * push service, and it costs one small request per wake, never one against a dark panel.
 *
 * The card carries its own clock (`expiresAt`, epoch seconds); an expired card is nothing, on
 * every side, so a gateway that went away cannot leave a stale alarm on the face.
 */
data class LockCard(val title: String, val text: String, val expiresAt: Double, val action: String?, val updatedAt: Double) {
    fun live(nowSeconds: Double = System.currentTimeMillis() / 1000.0): Boolean =
        (title.isNotBlank() || text.isNotBlank()) && nowSeconds < expiresAt

    fun toJson(): String = JSONObject().put("title", title).put("text", text).put("expires_at", expiresAt)
        .put("action", action).put("updated_at", updatedAt).toString()

    companion object {
        fun parse(json: String?): LockCard? {
            if (json.isNullOrBlank()) return null
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            return LockCard(
                title = o.optString("title", ""),
                text = o.optString("text", o.optString("sub", "")),
                expiresAt = o.optDouble("expires_at", 0.0),
                action = o.optString("action").takeIf { it.isNotBlank() },
                updatedAt = o.optDouble("updated_at", 0.0),
            )
        }
    }
}

object LockCards {
    private val io = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    /** The provider is asked on every wake; one network ask per [MIN_GAP_MS] is plenty. */
    private const val MIN_GAP_MS = 5_000L
    @Volatile private var lastAsk = 0L

    fun cached(context: Context): LockCard? = LockCard.parse(Prefs(context).cachedLock)?.takeIf { it.live() }

    /** Ask the gateway now, off the calling thread, and notify the provider's URI if the card changed. */
    fun refreshIfLit(context: Context) {
        val interactive = runCatching { context.getSystemService(PowerManager::class.java)?.isInteractive == true }.getOrDefault(true)
        if (!interactive) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAsk < MIN_GAP_MS) return
        lastAsk = now
        io.execute { fetch(context) }
    }

    /** Synchronous fetch + cache; also used by the app's own refresh. Returns the live card or null. */
    fun fetch(context: Context): LockCard? {
        val prefs = Prefs(context)
        if (!prefs.configured) return null
        val before = prefs.cachedLock
        val body = runCatching {
            client.newCall(
                Request.Builder()
                    .url(prefs.server.trimEnd('/') + "/lock")
                    .header("Authorization", "Bearer " + prefs.token)
                    .header("X-Device", prefs.deviceId(context))
                    .build(),
            ).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.getOrNull() ?: return LockCard.parse(before)?.takeIf { it.live() }
        val card = LockCard.parse(body)
        val after = card?.takeIf { it.live() }?.toJson()
        if (after != before) {
            prefs.cachedLock = after
            DeckProvider.lockChanged(context)
        }
        return card?.takeIf { it.live() }
    }
}
