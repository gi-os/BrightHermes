package com.gios.brighthermes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableStateListOf
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
import com.gios.brighthermes.chat.Markdown
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
    val lock by vm.lock.collectAsStateWithLifecycle()

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
        lock?.takeIf { it.live(now) }?.let { LockCardRow(it, type) }
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

        val host = remember(vm.prefs.server, vm.prefs.token) { WidgetHost(vm.prefs.server, vm.prefs.token, vm.device) }
        Transcript(messages, type, host, Modifier.weight(1f))

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

/**
 * The card that is also on the lock face right now. Inverted — the one filled shape in the app —
 * because it is the one thing June decided could not wait.
 */
@Composable
private fun LockCardRow(card: com.gios.brighthermes.deck.LockCard, type: Type) {
    Column(Modifier.fillMaxWidth().background(Ink.Content).padding(horizontal = Grid, vertical = 12.dp)) {
        Text("JUNE", style = type.label, color = Ink.Paper)
        if (card.title.isNotBlank()) Text(card.title, style = type.bodyYou, color = Ink.Paper)
        if (card.text.isNotBlank()) Text(card.text, style = type.small, color = Ink.Paper)
    }
}

@Composable
private fun Transcript(messages: List<Message>, type: Type, host: WidgetHost, modifier: Modifier) {
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
        val lastYours = messages.lastOrNull { it.who == Message.Who.USER }?.id
        items(reversed, key = { it.id }) { m ->
            val idx = reversed.indexOf(m)
            val older = reversed.getOrNull(idx + 1)
            Column(Modifier.fillMaxWidth()) {
                // A ruled timestamp when more than an hour passed since the message above it.
                if (m.at > 0 && (older == null || m.at - older.at > 60 * 60 * 1000L)) {
                    Timestamp(m.at, type)
                    Spacer(Modifier.height(Grid))
                }
                MessageRow(m, type, host, lastFromYou = m.id == lastYours)
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
private fun MessageRow(m: Message, type: Type, host: WidgetHost, lastFromYou: Boolean) {
    when (m.who) {
        Message.Who.USER -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Text(
                m.text,
                style = type.bodyYou,
                color = Ink.Content,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(start = Grid * 3),
            )
            // The read receipt, under your latest message only: the gateway has it and June has
            // started. It stays until you say something else, the way a read receipt does.
            if (m.seen && lastFromYou) {
                Spacer(Modifier.height(3.dp))
                Text("READ", style = type.label, color = Ink.Secondary)
            }
        }
        Message.Who.JUNE -> Column(Modifier.fillMaxWidth()) {
            if (m.unprompted) {
                Text("JUNE", style = type.label, color = Ink.Secondary)
                Spacer(Modifier.height(3.dp))
            }
            if (m.text.isNotEmpty()) {
                // Parsed on every change while streaming; the texts are short and the parser is a
                // few string scans, so this is cheaper than trying to be clever about it.
                val blocks = remember(m.text) { Markdown.parse(m.text) }
                MarkdownView(blocks, type, host, Modifier.fillMaxWidth())
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

/**
 * Quick replies from the server, in one row: underlined words, not pills, as in the brief's 1A
 * strip. Three fit at this size; the row scrolls sideways if the server sends longer ones.
 */
@Composable
private fun Chips(chips: List<String>, type: Type, onPick: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Grid, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(Grid + 3.dp),
    ) {
        chips.take(4).forEach { c ->
            Column(Modifier.clickable { onPick(c) }) {
                Text(c.lowercase(), style = type.small, color = Ink.Content, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Content))
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

/**
 * Push-to-talk, held. The brief's PTT screen — "■ LISTENING", what you are saying set large
 * with a caret, a bar meter along the bottom, one line of instruction — on black rather than the
 * inverted white the brief drew, so the whole app stays one surface.
 */
@Composable
private fun ListeningPanel(type: Type, state: Listener.State) {
    val level by Listener.level.collectAsStateWithLifecycle()
    val partial by Listener.partial.collectAsStateWithLifecycle()
    val transcribing = state is Listener.State.Transcribing

    // The meter is the last thirteen level readings, oldest left — a slow waveform, not a VU.
    val bars = remember { mutableStateListOf<Float>().apply { repeat(BARS) { add(0f) } } }
    LaunchedEffect(Unit) {
        while (true) {
            delay(90)
            bars.removeAt(0)
            bars.add(level)
        }
    }
    var caret by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            caret = !caret
        }
    }

    Column(Modifier.fillMaxSize().background(Ink.Paper)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(Grid),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(9.dp).height(9.dp).background(if (transcribing) Ink.Secondary else Ink.Content))
                Spacer(Modifier.width(9.dp))
                Text(if (transcribing) "HEARD" else "LISTENING", style = type.label, color = Ink.Content)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                buildString {
                    append(partial.ifBlank { if (transcribing) "…" else "" })
                    if (!transcribing && caret) append("▌")
                },
                style = type.live,
                color = if (partial.isBlank()) Ink.Secondary else Ink.Content,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Column(Modifier.fillMaxWidth().padding(Grid), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.height(24.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                bars.forEachIndexed { i, v ->
                    val h = (4 + v * 20).dp
                    Box(Modifier.width(3.dp).height(h).background(if (i >= BARS - 2) Ink.Secondary else Ink.Content))
                }
            }
            Text(
                if (transcribing) "Sending…" else "Let go to send · turn the wheel to cancel",
                style = type.small,
                color = Ink.Secondary,
            )
        }
    }
}

private const val BARS = 13
