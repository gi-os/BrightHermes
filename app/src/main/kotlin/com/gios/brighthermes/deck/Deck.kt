package com.gios.brighthermes.deck

import org.json.JSONArray
import org.json.JSONObject

/**
 * The deck, as data. No Android imports: this file is what the unit tests exercise.
 *
 * A [Tile] is `label + value + sub + action`, nothing more. The layout is an ordered list of
 * [Slot]s on a two-column grid; a slot spans one column or both. Remote tiles come from the
 * gateway's `/deck`; local ones ([TileKind.local]) are filled on the phone by [LocalTiles] and
 * never wait on the network.
 */
data class Tile(
    val id: String,
    val label: String,
    val value: String,
    val sub: String,
    val action: String? = null,
    /** Epoch seconds the gateway last wrote it; 0 for a local tile. */
    val updatedAt: Double = 0.0,
    /** Epoch seconds after which the phone draws it dimmed; null never dims. */
    val staleAt: Double? = null,
) {
    fun isStale(nowSeconds: Double): Boolean = staleAt != null && nowSeconds > staleAt

    fun toJson(): JSONObject = JSONObject().apply {
        put("label", label); put("value", value); put("sub", sub)
        action?.let { put("action", it) }
        put("updated_at", updatedAt)
        staleAt?.let { put("stale_at", it) }
    }

    companion object {
        fun fromJson(id: String, o: JSONObject): Tile = Tile(
            id = id,
            label = o.optString("label", id),
            value = o.optString("value", ""),
            sub = o.optString("sub", ""),
            action = o.optString("action").takeIf { it.isNotBlank() },
            updatedAt = o.optDouble("updated_at", 0.0),
            staleAt = if (o.has("stale_at") && !o.isNull("stale_at")) o.optDouble("stale_at") else null,
        )
    }
}

data class Slot(val id: String, val span: Int) {
    init {
        require(span == 1 || span == 2) { "span is 1 or 2" }
    }
}

data class TileKind(val id: String, val name: String, val local: Boolean)

data class Deck(
    val layout: List<Slot>,
    val tiles: Map<String, Tile>,
    val catalog: List<TileKind>,
    val chips: List<String>,
    val updatedAt: Double,
) {
    /** The tiles in layout order, local ones pulled from [local]. Slots with nothing to show are skipped. */
    fun rows(local: Map<String, Tile>): List<Pair<Slot, Tile>> = layout.mapNotNull { slot ->
        (tiles[slot.id] ?: local[slot.id])?.let { slot to it }
    }

    /** What is worth putting in the one-line strip: the first three remote-or-local tiles that have a value. */
    fun strip(local: Map<String, Tile>, n: Int = 3): List<Tile> =
        rows(local).map { it.second }.filter { it.value.isNotBlank() && it.id != "clock" }.take(n)

    fun toJson(): String = JSONObject().apply {
        put("v", 1)
        put("layout", JSONArray().apply { layout.forEach { put(JSONObject().put("id", it.id).put("span", it.span)) } })
        put("tiles", JSONObject().apply { tiles.values.forEach { put(it.id, it.toJson()) } })
        put("catalog", JSONArray().apply { catalog.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("local", it.local)) } })
        put("chips", JSONArray(chips))
        put("updated_at", updatedAt)
    }.toString()

    companion object {
        val EMPTY = Deck(emptyList(), emptyMap(), emptyList(), emptyList(), 0.0)

        /** Parses the gateway's `/deck` body (also what [toJson] writes, so the cache round-trips). */
        fun parse(json: String): Deck {
            val o = JSONObject(json)
            val layout = o.optJSONArray("layout")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val s = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = s.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Slot(id, if (s.optInt("span", 1) == 2) 2 else 1)
                }
            } ?: emptyList()
            val tiles = mutableMapOf<String, Tile>()
            o.optJSONObject("tiles")?.let { t ->
                for (key in t.keys()) {
                    val tj = t.optJSONObject(key) ?: continue
                    tiles[key] = Tile.fromJson(key, tj)
                }
            }
            val catalog = o.optJSONArray("catalog")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val c = arr.optJSONObject(i) ?: return@mapNotNull null
                    TileKind(c.optString("id"), c.optString("name", c.optString("id")), c.optBoolean("local", false))
                }
            } ?: emptyList()
            val chips = o.optJSONArray("chips")?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() } }
                ?: emptyList()
            return Deck(layout, tiles, catalog, chips, o.optDouble("updated_at", 0.0))
        }
    }
}

/** Serialises a layout for `PUT /deck/layout`. */
fun layoutJson(layout: List<Slot>): String = JSONObject().put(
    "layout",
    JSONArray().apply { layout.forEach { put(JSONObject().put("id", it.id).put("span", it.span)) } },
).toString()

/**
 * The strip line when the deck is collapsed to a single row of text: `63° · 7:30p dinner · 2 pending`.
 * Values only, subs only where the value alone is ambiguous (a time needs its event).
 */
fun collapsedLine(tiles: List<Tile>): String = tiles.joinToString(" · ") { t ->
    when (t.id) {
        "next" -> listOf(t.value, t.sub.substringBefore(",").lowercase()).filter { it.isNotBlank() }.joinToString(" ")
        "digest" -> t.value.lowercase()
        else -> t.value
    }
}
