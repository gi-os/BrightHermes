package com.gios.brighthermes.deck

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Tiles the phone fills without asking anyone.
 *
 * Only the clock for now. Now-playing (BrightMusic's media session), next transit (BrightTransit)
 * and LightPods each get a reader here when their apps expose one; until then their slots are
 * simply skipped by [Deck.rows], which is the right thing for a tile with nothing to say.
 */
object LocalTiles {
    private val time = DateTimeFormatter.ofPattern("H:mm", Locale.US)
    private val date = DateTimeFormatter.ofPattern("EEE d MMMM", Locale.US)

    fun now(context: Context): Map<String, Tile> {
        val t = LocalDateTime.now()
        val battery = batteryPercent(context)
        val sub = date.format(t) + if (battery != null) " · $battery%" else ""
        return mapOf("clock" to Tile(id = "clock", label = "", value = time.format(t), sub = sub))
    }

    private fun batteryPercent(context: Context): Int? = runCatching {
        val i: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) null else (level * 100) / scale
    }.getOrNull()
}
