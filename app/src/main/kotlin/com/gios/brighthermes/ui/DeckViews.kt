@file:OptIn(ExperimentalFoundationApi::class)

package com.gios.brighthermes.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.brighthermes.deck.Deck
import com.gios.brighthermes.deck.Slot
import com.gios.brighthermes.deck.Tile
import com.gios.brighthermes.deck.collapsedLine

/**
 * The deck in its three states — strip, grid, line — each the same data drawn at a different
 * size. Direction 1B from the design brief: no fills, no boxes around tiles, alignment and a
 * single rule do the separating. A stale tile is drawn in secondary, never hidden: an old
 * temperature beats a blank.
 */

@Composable
fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Ink.Content))
}

@Composable
fun TileView(tile: Tile, type: Type, now: Double, modifier: Modifier = Modifier, big: Boolean = false) {
    val stale = tile.isStale(now)
    val content = if (stale) Ink.Secondary else Ink.Content
    Column(modifier) {
        Text(
            text = tile.value.ifBlank { "—" },
            style = if (big) type.clock else type.value,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        if (tile.sub.isNotBlank() || tile.label.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = tile.sub.ifBlank { tile.label },
                style = type.small,
                color = Ink.Secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Three tiles in a row over a rule. The default. Tap to open the grid; long-press to edit. */
@Composable
fun DeckStrip(tiles: List<Tile>, type: Type, now: Double, onOpen: () -> Unit, onEdit: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onEdit),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Grid, vertical = Grid),
            horizontalArrangement = Arrangement.spacedBy(Grid),
        ) {
            if (tiles.isEmpty()) {
                Text("Deck", style = type.label, color = Ink.Secondary)
            }
            tiles.forEach { TileView(it, type, now, Modifier.weight(1f)) }
        }
        Rule()
    }
}

/**
 * The whole deck on a two-column grid. Slots are packed in order: two single spans share a row,
 * a double span takes one. The clock gets the big face. Wheel click or the header collapses it.
 */
@Composable
fun DeckGrid(deck: Deck, local: Map<String, Tile>, type: Type, now: Double, onCollapse: () -> Unit, onEdit: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onCollapse).padding(horizontal = Grid, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("DECK", style = type.label, color = Ink.Secondary, modifier = Modifier.weight(1f))
            Text("Edit", style = type.small, color = Ink.Content, modifier = Modifier.clickable(onClick = onEdit))
        }
        val rows = packRows(deck.rows(local))
        Column(Modifier.padding(horizontal = Grid), verticalArrangement = Arrangement.spacedBy(Grid)) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Grid)) {
                    row.forEach { (slot, tile) ->
                        TileView(tile, type, now, Modifier.weight(slot.span.toFloat()), big = tile.id == "clock" && slot.span == 2)
                    }
                    if (row.size == 1 && row[0].first.span == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Text(
            "Wheel click to collapse",
            style = type.label,
            color = Ink.Secondary,
            modifier = Modifier.padding(horizontal = Grid, vertical = 12.dp),
        )
        Rule()
    }
}

/** The deck as one line of text. For when the conversation matters more than the numbers. */
@Composable
fun DeckLine(tiles: List<Tile>, type: Type, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Grid, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                collapsedLine(tiles).ifBlank { "Deck" },
                style = type.small,
                color = Ink.Content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Grid))
            Text("▾", style = type.small, color = Ink.Secondary)
        }
        Rule()
    }
}

/** Pack `(slot, tile)` pairs into grid rows: singles pair up, doubles stand alone. */
fun <T> packRows(items: List<Pair<Slot, T>>): List<List<Pair<Slot, T>>> {
    val rows = mutableListOf<MutableList<Pair<Slot, T>>>()
    var open: MutableList<Pair<Slot, T>>? = null
    for (item in items) {
        if (item.first.span == 2) {
            open = null
            rows += mutableListOf(item)
        } else if (open == null) {
            open = mutableListOf(item)
            rows += open
        } else {
            open += item
            open = null
        }
    }
    return rows
}
