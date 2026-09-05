package com.gios.brighthermes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gios.brighthermes.chat.Bot
import com.gios.brighthermes.chat.Frame
import com.gios.brighthermes.chat.Message
import com.gios.brighthermes.deck.Deck
import com.gios.brighthermes.deck.DeckProvider
import com.gios.brighthermes.deck.LocalTiles
import com.gios.brighthermes.deck.Slot
import com.gios.brighthermes.deck.Tile
import com.gios.brighthermes.net.Api
import com.gios.brighthermes.net.Socket
import com.gios.brighthermes.voice.Listener
import com.gios.light.common.report.Trouble
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

/**
 * One view model for the one screen. Deck, transcript, socket, voice.
 *
 * The deck is drawn from the cache first and refreshed on every open, every screen-on and every
 * `deck` frame from the socket — never on a timer while the phone is dark, which is the rule
 * every Bright* app that touched the lock face had to learn. The transcript is June's own
 * session on the server; the phone keeps nothing but what is on screen.
 */
class HermesViewModel(app: Application) : AndroidViewModel(app) {

    val prefs = Prefs(app)

    /** This install's id, sent as `X-Device`; widgets get it too. */
    val device = prefs.deviceId(app)

    val api = Api(server = { prefs.server }, token = { prefs.token }, device = { device })
    val socket = Socket(url = { Prefs.wsUrl(prefs.server, prefs.token) }, device = { device }, scope = viewModelScope, client = api.client)

    private val _deck = MutableStateFlow(prefs.cachedDeck?.let { runCatching { Deck.parse(it) }.getOrNull() } ?: Deck.EMPTY)
    val deck: StateFlow<Deck> = _deck.asStateFlow()

    private val _local = MutableStateFlow(LocalTiles.now(app))
    val local: StateFlow<Map<String, Tile>> = _local.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private fun show() {
        _messages.value = _all.value.filter { it.bot == _bot.value.id }
    }

    private fun updateAll(f: (List<Message>) -> List<Message>) {
        _all.update(f)
        show()
    }

    private val _chips = MutableStateFlow(_deck.value.chips)
    val chips: StateFlow<List<String>> = _chips.asStateFlow()

    /** Which Hermes agents can be talked to, June first. Comes with the socket's `ok`; until then, June. */
    private val _bots = MutableStateFlow(listOf(Bot.JUNE))
    val bots: StateFlow<List<Bot>> = _bots.asStateFlow()

    /** Who the input goes to. The transcript on screen is this bot's. */
    private val _bot = MutableStateFlow(Bot.JUNE)
    val bot: StateFlow<Bot> = _bot.asStateFlow()

    /** Everything fetched or said so far, every bot; [messages] is the current bot's slice. */
    private val _all = MutableStateFlow<List<Message>>(emptyList())

    /** strip / grid / line — see ui/DeckViews. */
    private val _deckMode = MutableStateFlow(prefs.deckMode)
    val deckMode: StateFlow<String> = _deckMode.asStateFlow()

    private val _editing = MutableStateFlow(false)
    val editing: StateFlow<Boolean> = _editing.asStateFlow()

    /** One line of status under the input; null when there is nothing to say. Clears itself. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _configured = MutableStateFlow(prefs.configured)
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    /** The reply id currently being typed, so Stop knows what to stop. */
    private var inFlight: String? = null

    /** Which bot each outgoing user message went to, so the reply lands in the right slice. */
    private val pendingBot = mutableMapOf<String, String>()
    private var noticeJob: Job? = null
    private var clockJob: Job? = null

    init {
        viewModelScope.launch { socket.frames.collect(::onFrame) }
    }

    // -- lifecycle -------------------------------------------------------------------------------

    /** The activity is in front: connect, refresh, tick the clock. */
    fun foreground() {
        if (!prefs.configured) return
        socket.open()
        refresh()
        loadThread()
        Listener.warm(getApplication<Application>())
        clockJob?.cancel()
        clockJob = viewModelScope.launch {
            while (isActive) {
                _local.value = LocalTiles.now(getApplication<Application>())
                // Wake on the minute, so the clock never shows a stale minute for long.
                delay(60_000L - System.currentTimeMillis() % 60_000L)
            }
        }
    }

    /** The activity is gone: no socket, no timers, no network. */
    fun background() {
        socket.close()
        clockJob?.cancel()
        Listener.cancel()
    }

    /** Screen came on with the app still in front: the deck may be old. */
    fun screenOn() {
        if (prefs.configured) refresh()
    }

    // -- deck ------------------------------------------------------------------------------------

    fun refresh() {
        viewModelScope.launch {
            try {
                val (raw, parsed) = api.deck()
                prefs.cachedDeck = raw
                _deck.value = parsed
                if (parsed.chips.isNotEmpty()) _chips.value = parsed.chips
                DeckProvider.changed(getApplication<Application>())
            } catch (e: IOException) {
                // Offline is not a fault; the cache is on screen. Say so only if there is no cache.
                if (_deck.value.layout.isEmpty()) notice("Can't reach ${prefs.server.removePrefix("https://").removePrefix("http://")}")
            }
        }
    }

    fun setDeckMode(mode: String) {
        _deckMode.value = mode
        prefs.deckMode = mode
    }

    fun cycleDeckMode() = setDeckMode(
        when (_deckMode.value) {
            "strip" -> "grid"
            "grid" -> "line"
            else -> "strip"
        },
    )

    fun setEditing(on: Boolean) {
        _editing.value = on
    }

    /** Save a new arrangement: shown at once, sent to the server, kept in the cache either way. */
    fun saveLayout(layout: List<Slot>) {
        val updated = _deck.value.copy(layout = layout)
        _deck.value = updated
        prefs.cachedDeck = updated.toJson()
        DeckProvider.changed(getApplication<Application>())
        viewModelScope.launch {
            try {
                api.putLayout(layout)
            } catch (e: IOException) {
                Trouble.record("Saving the deck layout failed", e)
                notice("Layout kept here; couldn't reach the server")
            }
        }
    }

    // -- chat ------------------------------------------------------------------------------------

    private fun loadThread(bot: Bot = _bot.value) {
        viewModelScope.launch {
            try {
                val history = api.thread(bot.id, 60)
                // Keep anything in flight at the bottom; history replaces the rest of this bot's slice.
                val live = _all.value.filter { it.bot == bot.id && (it.pending || it.at > (history.lastOrNull()?.at ?: 0L)) }
                val merged = history + live.filter { l -> history.none { it.text == l.text && it.who == l.who } }
                updateAll { all -> all.filter { it.bot != bot.id } + merged }
            } catch (e: IOException) {
                // The socket will still work; the screen just starts empty.
            }
        }
    }

    /** Talk to someone else. The transcript swaps to theirs, fetched if not seen yet. */
    fun pickBot(b: Bot) {
        if (b.id == _bot.value.id) return
        _bot.value = b
        show()
        if (_all.value.none { it.bot == b.id }) loadThread(b)
    }

    fun nextBot() {
        val list = _bots.value
        if (list.size < 2) return
        val i = list.indexOfFirst { it.id == _bot.value.id }
        pickBot(list[(i + 1) % list.size])
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val id = "u" + UUID.randomUUID().toString().take(8)
        val to = _bot.value
        updateAll { it + Message(id, Message.Who.USER, t, System.currentTimeMillis(), bot = to.id) }
        pendingBot[id] = to.id
        if (!socket.ask(id, t, to.id)) {
            notice("Not connected — reconnecting")
            socket.open()
            // The frame is gone; retry once when the socket comes up.
            viewModelScope.launch {
                repeat(20) {
                    delay(500)
                    if (socket.state.value == Socket.State.ON) {
                        socket.ask(id, t, to.id)
                        return@launch
                    }
                }
                updateAll { it.filterNot { m -> m.id == id } }
                notice("Couldn't reach ${to.name}")
            }
        }
    }

    fun stop() {
        inFlight?.let { socket.stop(it) }
    }

    private fun onFrame(f: Frame) {
        when (f) {
            is Frame.Ok -> {
                if (f.chips.isNotEmpty()) _chips.value = f.chips
                if (f.bots.isNotEmpty()) {
                    _bots.value = f.bots
                    // A bot that disappeared from the roster while selected falls back to June.
                    if (f.bots.none { it.id == _bot.value.id }) pickBot(f.bots.first())
                }
                if (f.deckUpdatedAt > _deck.value.updatedAt) refresh()
            }
            is Frame.Start -> {
                inFlight = f.reply
                val to = pendingBot.remove(f.id) ?: f.bot
                updateAll { it + Message(f.reply, Message.Who.JUNE, "", System.currentTimeMillis(), pending = true, bot = to) }
            }
            is Frame.Delta -> updateAll { list ->
                list.map { if (it.id == f.id) it.copy(text = it.text + f.text, thinking = false, tool = null) else it }
            }
            is Frame.Tool -> updateAll { list ->
                list.map { if (it.id == f.id) it.copy(tool = if (f.state == "started") f.name else null) else it }
            }
            is Frame.Thinking -> updateAll { list ->
                list.map { if (it.id == f.id && it.text.isEmpty()) it.copy(thinking = true) else it }
            }
            is Frame.Done -> {
                if (inFlight == f.id) inFlight = null
                updateAll { list ->
                    list.mapNotNull {
                        if (it.id != f.id) it
                        else {
                            val text = if (f.text.isNotBlank()) f.text else it.text
                            // A reply stopped before a single word is not a message.
                            if (text.isBlank()) null else it.copy(text = text, pending = false, tool = null, thinking = false)
                        }
                    }
                }
            }
            is Frame.Error -> {
                if (f.id != null) {
                    if (inFlight == f.id) inFlight = null
                    updateAll { list -> list.filterNot { it.id == f.id && it.text.isBlank() }.map { if (it.id == f.id) it.copy(pending = false, tool = null) else it } }
                }
                notice(f.message)
            }
            is Frame.DeckChanged -> refresh()
            is Frame.Pong, is Frame.Unknown -> Unit
        }
    }

    // -- voice -----------------------------------------------------------------------------------

    /** Camera button first stage down (or input row long-pressed): start listening. */
    fun pttDown() {
        val ctx = getApplication<Application>()
        if (!Listener.hasPermission(ctx)) {
            notice("Microphone permission needed")
            return
        }
        Listener.start(ctx) { text -> viewModelScope.launch { send(text) } }
    }

    /** Camera button second stage: send on release. */
    fun pttCommit() = Listener.commit()

    /** Camera button released. */
    fun pttUp() = Listener.release()

    // -- setup -----------------------------------------------------------------------------------

    /** Try a server + token; keep them only if `/deck` answers. Returns the error to show, or null. */
    suspend fun trySetup(server: String, token: String): String? {
        val s = Prefs.normaliseServer(server)
        val probe = Api(server = { s }, token = { token.trim() }, device = { device }, client = api.client)
        return try {
            probe.health()
            prefs.server = s
            prefs.token = token
            _configured.value = true
            foreground()
            null
        } catch (e: IOException) {
            e.message ?: "Couldn't connect"
        }
    }

    fun forget() {
        background()
        prefs.token = ""
        prefs.cachedDeck = null
        _configured.value = false
        _all.value = emptyList()
        _messages.value = emptyList()
        _deck.value = Deck.EMPTY
    }

    private fun notice(text: String) {
        _notice.value = text
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(4000)
            _notice.value = null
        }
    }

    override fun onCleared() {
        socket.close()
    }
}
