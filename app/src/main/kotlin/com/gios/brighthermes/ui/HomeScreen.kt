package com.gios.brighthermes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.brighthermes.HermesViewModel
import com.gios.brighthermes.chat.Message
import com.gios.brighthermes.hw.WheelTalk
import com.gios.brighthermes.voice.Listener
import com.gios.light.common.hw.WheelScroll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The one screen: deck on top, conversation below, input at the bottom. Direction 1B.
 *
 * No sender labels. June is left and full width; you are right and set in medium. Ruled
 * timestamp dividers where the conversation skipped more than an hour. Chips are full-width
 * rows with a return glyph. While the wheel is held in the whole panel inverts to white, so the
 * held state is unmistakable at arm's length.
 */
@Composable
fun HomeScreen(vm: HermesViewModel, type: Type) {
    val deck by vm.deck.collectAsStateWithLifecycle()
    val local by vm.local.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val chips by vm.chips.collectAsStateWithLifecycle()
    val mode by vm.deckMode.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val voice by Listener.state.collectAsStateWithLifecycle()
    val bots by vm.bots.collectAsStateWithLifecycle()
    val bot by vm.bot.collectAsStateWithLifecycle()

    // Staleness is judged against a clock that ticks once a minute, not against every recomposition.
    val now by produceState(System.currentTimeMillis() / 1000.0) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis() / 1000.0
        }
    }

    if (voice is Listener.State.Listening || voice is Listener.State.Transcribing) {
        ListeningPanel(type, voice)
        return
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        when (mode) {
            "grid" -> DeckGrid(
                deck, local, type, now,
                onCollapse = { vm.setDeckMode("strip") },
                onEdit = { vm.setEditing(true) },
                widget = { tile -> WidgetView(tile, type, vm.prefs.server, vm.prefs.token, vm.device) },
            )
            "line" -> DeckLine(deck.strip(local), type, onOpen = { vm.setDeckMode("strip") })
            else -> DeckStrip(
                deck.strip(local), deck.widgets(), type, now,
                onOpen = { vm.setDeckMode("grid") },
                onEdit = { vm.setEditing(true) },
                widget = { tile -> WidgetView(tile, type, vm.prefs.server, vm.prefs.token, vm.device) },
            )
        }

        Transcript(messages, type, Modifier.weight(1f))

        if (chips.isNotEmpty() && messages.none { it.pending }) {
            Chips(chips, type) { vm.send(it) }
        }

        Composer(
            type = type,
            busy = messages.any { it.pending },
            notice = notice,
            botName = bot.name,
            canSwitch = bots.size > 1,
            onSwitch = vm::nextBot,
            onSend = vm::send,
            onStop = vm::stop,
            onHoldStart = { vm.pttDown() },
            onHoldEnd = { vm.pttCommit(); vm.pttUp() },
        )
    }
}

@Composable
private fun Transcript(messages: List<Message>, type: Type, modifier: Modifier) {
    val state = rememberLazyListState()
    WheelScroll(state, reverse = true)
    // Newest at the bottom, and the list keeps its bottom pinned while June types.
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) state.animateScrollToItem(0)
    }
    LazyColumn(
        state = state,
        reverseLayout = true,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Grid, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(Grid),
    ) {
        if (messages.isEmpty()) {
            item {
                Text(
                    "Say something, or hold the wheel in.",
                    style = type.body,
                    color = Ink.Secondary,
                    modifier = Modifier.fillMaxWidth().padding(vertical = Grid * 2),
                )
            }
        }
        val reversed = messages.asReversed()
        items(reversed, key = { it.id }) { m ->
            val idx = reversed.indexOf(m)
            val older = reversed.getOrNull(idx + 1)
            Column(Modifier.fillMaxWidth()) {
                // A ruled timestamp when more than an hour passed since the message above it.
                if (m.at > 0 && (older == null || m.at - older.at > 60 * 60 * 1000L)) {
                    Timestamp(m.at, type)
                    Spacer(Modifier.height(Grid))
                }
                MessageRow(m, type)
            }
        }
    }
}

@Composable
private fun Timestamp(at: Long, type: Type) {
    val fmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val dayFmt = remember { DateTimeFormatter.ofPattern("EEE HH:mm") }
    val t = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    val today = t.toLocalDate() == java.time.LocalDate.now()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(Ink.Rule))
        Text(
            if (today) fmt.format(t) else dayFmt.format(t),
            style = type.label,
            color = Ink.Secondary,
            modifier = Modifier.padding(horizontal = 9.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(Ink.Rule))
    }
}

@Composable
private fun MessageRow(m: Message, type: Type) {
    when (m.who) {
        Message.Who.USER -> Text(
            m.text,
            style = type.bodyYou,
            color = Ink.Content,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth().padding(start = Grid * 3),
        )
        Message.Who.JUNE -> Column(Modifier.fillMaxWidth()) {
            if (m.unprompted) {
                Text("JUNE", style = type.label, color = Ink.Secondary)
                Spacer(Modifier.height(3.dp))
            }
            if (m.text.isNotEmpty()) {
                Text(m.text, style = type.body, color = Ink.Content, modifier = Modifier.fillMaxWidth())
            }
            if (m.pending) {
                Spacer(Modifier.height(if (m.text.isEmpty()) 0.dp else 3.dp))
                PendingMark(m, type)
            }
        }
    }
}

/**
 * What June is doing while she has not said anything yet. A two-frame blink at 1 Hz — the brief's
 * `▁` / `●` — with the tool's name beside it when one is running. Never a spinner.
 */
@Composable
private fun PendingMark(m: Message, type: Type) {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            on = !on
        }
    }
    val what = when {
        m.tool != null -> m.tool.replace('_', ' ')
        m.thinking -> "thinking"
        else -> ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (on) "●" else "▁", style = type.small, color = Ink.Secondary)
        if (what.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(what, style = type.small, color = Ink.Secondary)
        }
    }
}

/** Quick replies from the server as full-width rows, each ending in a return glyph. */
@Composable
private fun Chips(chips: List<String>, type: Type, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        chips.take(3).forEach { c ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(c) }.padding(horizontal = Grid, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(c.replaceFirstChar { it.uppercase() }, style = type.small, color = Ink.Content, modifier = Modifier.weight(1f))
                Text("↵", style = type.small, color = Ink.Secondary)
            }
        }
    }
}

/**
 * The input. LightTextField style: 3dp underline, 80% width, no box. Return sends. While a reply
 * is being typed the row reads Stop instead. With more than one bot configured, the name of the
 * one listening sits at the right of the row; tap it to talk to the next.
 */
@Composable
private fun Composer(
    type: Type,
    busy: Boolean,
    notice: String?,
    botName: String,
    canSwitch: Boolean,
    onSwitch: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = Grid, vertical = 12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.fillMaxWidth(0.8f)) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = type.body.copy(color = Ink.Content),
                    cursorBrush = SolidColor(Ink.Content),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (text.isNotBlank()) {
                            onSend(text)
                            text = ""
                        }
                    }),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) Text("Say something", style = type.body, color = Ink.Secondary)
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
                Box(Modifier.fillMaxWidth().height(3.dp).background(Ink.Content))
            }
            Spacer(Modifier.weight(1f))
            when {
                busy -> Text("Stop", style = type.small, color = Ink.Content, modifier = Modifier.clickable(onClick = onStop).padding(bottom = 6.dp))
                canSwitch -> Text(
                    botName.uppercase(),
                    style = type.label,
                    color = Ink.Secondary,
                    modifier = Modifier.clickable(onClick = onSwitch).padding(bottom = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // The hint line doubles as the on-screen push-to-talk: press and hold it. A hold longer
        // than a tap starts listening; letting go sends. The camera button is the real control,
        // this is for the hand that is holding a coffee.
        Text(
            notice ?: WheelTalk.Witness.warning() ?: "Hold the wheel in, or hold here, to talk to $botName",
            style = type.label,
            color = Ink.Secondary,
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            var held = false
                            coroutineScope {
                                val timer = launch {
                                    delay(350)
                                    held = true
                                    onHoldStart()
                                }
                                tryAwaitRelease()
                                timer.cancel()
                            }
                            if (held) onHoldEnd()
                        },
                    )
                }
                .padding(vertical = 6.dp),
        )
    }
}

/** The whole panel inverted while the button is down. Level bar underneath; no text of what was heard. */
@Composable
private fun ListeningPanel(type: Type, state: Listener.State) {
    val level by Listener.level.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().background(Ink.Content).padding(Grid),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("JUNE", style = type.label, color = Ink.Paper)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (state is Listener.State.Transcribing) "…" else "Listening",
                style = type.value,
                color = Ink.Paper,
            )
            Spacer(Modifier.height(Grid))
            Box(Modifier.fillMaxWidth().height(3.dp).background(Ink.Secondary)) {
                Box(Modifier.fillMaxWidth(level.coerceIn(0.02f, 1f)).fillMaxHeight().background(Ink.Paper))
            }
        }
        Text("Let go to send", style = type.label, color = Ink.Paper)
    }
}
