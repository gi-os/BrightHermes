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
 * `content://com.gios.brighthermes.deck/lock` answers **at most one row** — `title`, `text`,
 * `expiresAt` (epoch seconds), `action`, `updatedAt` — the card June has put on the lock face,
 * and an empty cursor when there is none or it has run out. This is the one the face draws
 * where the music player goes, in place of the player. Querying it while the screen is on also
 * asks the gateway for a fresher card in the background and notifies the URI if one arrives,
 * so a query-on-wake is enough to surface a card posted while the phone lay dark. See [LockCard].
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
        if (uri.lastPathSegment == "lock") return lockCursor(ctx)
        val cursor = MatrixCursor(COLUMNS)
        if (uri.lastPathSegment != "tiles") return cursor
        val deck = runCatching { Prefs(ctx).cachedDeck?.let(Deck::parse) }.getOrNull() ?: return cursor
        // Widgets are HTML and the lock face draws text; they are not rows here.
        for ((slot, tile) in deck.rows(emptyMap()).filter { !it.second.isWidget }) {
            cursor.addRow(arrayOf(tile.id, slot.span, tile.label, tile.value, tile.sub, tile.action, tile.updatedAt, tile.staleAt))
        }
        return cursor
    }

    private fun lockCursor(ctx: Context): Cursor {
        val cursor = MatrixCursor(LOCK_COLUMNS)
        LockCards.cached(ctx)?.let { c -> cursor.addRow(arrayOf(c.title, c.text, c.expiresAt, c.action, c.updatedAt)) }
        // Answer first, then ask. The face observes the URI and re-reads when this lands.
        LockCards.refreshIfLit(ctx)
        return cursor
    }

    override fun getType(uri: Uri): String =
        if (uri.lastPathSegment == "lock") "vnd.android.cursor.item/vnd.com.gios.brighthermes.lock"
        else "vnd.android.cursor.dir/vnd.com.gios.brighthermes.tile"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0

    companion object {
        val URI: Uri = Uri.parse("content://com.gios.brighthermes.deck/tiles")
        val LOCK_URI: Uri = Uri.parse("content://com.gios.brighthermes.deck/lock")
        val COLUMNS = arrayOf("id", "span", "label", "value", "sub", "action", "updatedAt", "staleAt")
        val LOCK_COLUMNS = arrayOf("title", "text", "expiresAt", "action", "updatedAt")

        /** Called by the app after every successful deck fetch. */
        fun changed(context: Context) {
            runCatching { context.contentResolver.notifyChange(URI, null) }
        }

        /** Called whenever the lock card changes, from wherever it was learned. */
        fun lockChanged(context: Context) {
            runCatching { context.contentResolver.notifyChange(LOCK_URI, null) }
        }
    }
}
