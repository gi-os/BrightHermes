package com.gios.brighthermes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gios.brighthermes.deck.Deck
import com.gios.brighthermes.deck.Slot
import com.gios.light.common.hw.WheelScroll

/**
 * Arranging the deck, with a scroll wheel and a thumb.
 *
 * Not drag and drop: the brief's "tiles lift, snap to grid" is a two-hand gesture on a
 * three-inch panel driven by a wheel. Instead every tile the server knows is a row —
 * on the deck or not, one column or two — with ▲▼ to reorder. Same outcome, one thumb.
 * Local tiles are listed like any other; the phone fills those, so they need no network to show.
 */
@Composable
fun EditDeckScreen(deck: Deck, type: Type, onDone: (List<Slot>) -> Unit) {
    var layout by remember { mutableStateOf(deck.layout) }
    val scroll = rememberScrollState()
    WheelScroll(scroll)

    val onDeck = layout.map { it.id }.toSet()
    val off = deck.catalog.filter { it.id !in onDeck }

    Column(Modifier.fillMaxSize().padding(Grid)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Deck", style = type.title, color = Ink.Content, modifier = Modifier.weight(1f))
            Text("Done", style = type.small, color = Ink.Content, modifier = Modifier.clickable { onDone(layout) }.padding(6.dp))
        }
        Spacer(Modifier.height(Grid))
        Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(Grid)) {
            Text("ON THE DECK", style = type.label, color = Ink.Secondary)
            layout.forEachIndexed { i, slot ->
                val name = deck.catalog.firstOrNull { it.id == slot.id }?.name ?: slot.id
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = type.body, color = Ink.Content, modifier = Modifier.weight(1f))
                    Text(
                        if (slot.span == 2) "wide" else "half",
                        style = type.small,
                        color = Ink.Content,
                        modifier = Modifier
                            .clickable { layout = layout.toMutableList().also { it[i] = slot.copy(span = if (slot.span == 2) 1 else 2) } }
                            .padding(6.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "▲",
                        style = type.small,
                        color = if (i > 0) Ink.Content else Ink.Rule,
                        modifier = Modifier.clickable(enabled = i > 0) { layout = layout.swap(i, i - 1) }.padding(6.dp),
                    )
                    Text(
                        "▼",
                        style = type.small,
                        color = if (i < layout.lastIndex) Ink.Content else Ink.Rule,
                        modifier = Modifier.clickable(enabled = i < layout.lastIndex) { layout = layout.swap(i, i + 1) }.padding(6.dp),
                    )
                    Text(
                        "×",
                        style = type.small,
                        color = Ink.Content,
                        modifier = Modifier.clickable { layout = layout.filterIndexed { j, _ -> j != i } }.padding(6.dp),
                    )
                }
            }
            if (off.isNotEmpty()) {
                Spacer(Modifier.height(Grid))
                Text("NOT SHOWN", style = type.label, color = Ink.Secondary)
                off.forEach { kind ->
                    Row(
                        Modifier.fillMaxWidth().clickable { layout = layout + Slot(kind.id, 1) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(kind.name, style = type.body, color = Ink.Secondary, modifier = Modifier.weight(1f))
                        Text("+", style = type.small, color = Ink.Content, modifier = Modifier.padding(6.dp))
                    }
                }
            }
            Spacer(Modifier.height(Grid))
            Text(
                "Half tiles pair up on a row; a wide one takes the row. The order here is the order on the deck.",
                style = type.small,
                color = Ink.Secondary,
            )
        }
    }
}

private fun List<Slot>.swap(a: Int, b: Int): List<Slot> = toMutableList().also { val t = it[a]; it[a] = it[b]; it[b] = t }
