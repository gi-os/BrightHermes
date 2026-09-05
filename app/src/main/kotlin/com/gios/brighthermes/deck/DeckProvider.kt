package com.gios.brighthermes.deck

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.gios.brighthermes.Prefs

/**
 * The deck at rest, for BrightControl's lock face.
 *
 * ### The contract
 *
 * `content://com.gios.brighthermes.deck/tiles` answers one row per slot in layout order —
 * `id`, `span`, `label`, `value`, `sub`, `action`, `updatedAt` (epoch seconds), `staleAt`
 * (epoch seconds or null) — from the last deck this app fetched. Local tiles the phone fills
 * itself (the clock) are not rows: the face has its own clock. `notifyChange` fires on every
 * fetch, and is best-effort; the face should query on show and on wake as well as observing,
 * and must never poll on a schedule while the panel is dark.
 *
 * ### Why a provider and not a broadcast
 *
 * One deck, two canvases, zero drift. The app owns the data; the lock face reads the same
 * snapshot the app would draw, so the two can never disagree about what the weather is. It is
 * also the pattern BrightControl already reads (`com.gios.lightfog.stays`,
 * `com.gios.lightnotebook.nextup`), so the face needs one more `LockXxx` class and nothing new.
 *
 * No permission, exported: everything here is already on the lock screen by intent.
 */
class DeckProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? {
        val ctx = context ?: return null
        val cursor = MatrixCursor(COLUMNS)
        if (uri.lastPathSegment != "tiles") return cursor
        val deck = runCatching { Prefs(ctx).cachedDeck?.let(Deck::parse) }.getOrNull() ?: return cursor
        // Widgets are HTML and the lock face draws text; they are not rows here.
        for ((slot, tile) in deck.rows(emptyMap()).filter { !it.second.isWidget }) {
            cursor.addRow(arrayOf(tile.id, slot.span, tile.label, tile.value, tile.sub, tile.action, tile.updatedAt, tile.staleAt))
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.com.gios.brighthermes.tile"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0

    companion object {
        val URI: Uri = Uri.parse("content://com.gios.brighthermes.deck/tiles")
        val COLUMNS = arrayOf("id", "span", "label", "value", "sub", "action", "updatedAt", "staleAt")

        /** Called by the app after every successful deck fetch. */
        fun changed(context: Context) {
            runCatching { context.contentResolver.notifyChange(URI, null) }
        }
    }
}
