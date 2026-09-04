package com.gios.brighthermes.net

import com.gios.brighthermes.chat.Frame
import com.gios.brighthermes.chat.Frames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * The chat socket. One connection while the app is in front, none when it is not.
 *
 * That rule is the whole battery story. The gateway keeps June's session, so nothing is lost by
 * hanging up: on the next open the transcript comes back over `/thread` and the socket picks up
 * where it left off. Reconnects back off from one second to thirty and stop entirely when
 * [close] is called, which the activity does in `onStop`.
 *
 * Frames arrive on [frames] already parsed; [state] is for the one-word status line.
 */
class Socket(
    private val url: () -> String,
    private val device: () -> String,
    private val scope: CoroutineScope,
    client: OkHttpClient,
) {
    enum class State { OFF, CONNECTING, ON }

    private val http = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // a WebSocket read blocks by design
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private val _frames = MutableSharedFlow<Frame>(extraBufferCapacity = 256)
    val frames: SharedFlow<Frame> = _frames.asSharedFlow()

    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state.asStateFlow()

    private var ws: WebSocket? = null
    private var wanted = false
    private var attempts = 0
    private var reconnect: Job? = null

    fun open() {
        if (wanted) return
        wanted = true
        attempts = 0
        connect()
    }

    fun close() {
        wanted = false
        reconnect?.cancel()
        ws?.close(1000, "background")
        ws = null
        _state.value = State.OFF
    }

    /** True if the frame went out. False means "not connected", and the caller should say so. */
    fun send(text: String): Boolean = ws?.send(text) == true

    fun ask(id: String, text: String): Boolean = send(Frames.user(id, text))

    fun stop(id: String) {
        send(Frames.stop(id))
    }

    private fun connect() {
        if (!wanted) return
        _state.value = State.CONNECTING
        val req = Request.Builder().url(url()).header("X-Device", device()).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempts = 0
                webSocket.send(Frames.hello(device()))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val f = Frame.parse(text) ?: return
                if (f is Frame.Ok) _state.value = State.ON
                _frames.tryEmit(f)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (ws === webSocket) ws = null
                _state.value = State.OFF
                // 4401 is the gateway saying the token is wrong. Retrying that is noise.
                if (code != 4401) scheduleReconnect()
                else _frames.tryEmit(Frame.Error(null, "The server refused the token"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (ws === webSocket) ws = null
                _state.value = State.OFF
                if (response?.code == 401) {
                    _frames.tryEmit(Frame.Error(null, "The server refused the token"))
                    return
                }
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!wanted) return
        reconnect?.cancel()
        val wait = min(30_000L, 1000L shl min(attempts, 5))
        attempts++
        reconnect = scope.launch {
            delay(wait)
            if (wanted && ws == null) connect()
        }
    }
}
