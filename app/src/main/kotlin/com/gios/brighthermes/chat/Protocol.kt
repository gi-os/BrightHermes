package com.gios.brighthermes.chat

import org.json.JSONObject

/**
 * The WebSocket protocol with the gateway, one JSON object per text frame. Pure Kotlin.
 *
 * Client → server: [hello], [user], [stop], [ping]. Server → client: everything in [Frame].
 * The shapes are documented at the top of the gateway's `app.py`; this file is the other half
 * of that contract and the two are kept in step by hand.
 */
sealed class Frame {
    /** The handshake answer. `session` is June's session id for this phone. */
    data class Ok(val session: String, val chips: List<String>, val deckUpdatedAt: Double) : Frame()

    /** June started answering user message [id]; her reply will be [reply]. */
    data class Start(val id: String, val reply: String) : Frame()

    data class Delta(val id: String, val text: String) : Frame()

    /** A tool ran during reply [id]. `state` is started / done / failed. */
    data class Tool(val id: String, val name: String, val state: String) : Frame()

    /** June is reasoning. The phone shows a glyph, never the content. */
    data class Thinking(val id: String) : Frame()

    /** Reply [id] is complete; [text] is the whole thing. `stopped` when the user cut it off. */
    data class Done(val id: String, val text: String, val stopped: Boolean) : Frame()

    data class Error(val id: String?, val message: String) : Frame()

    /** A tile changed on the server; fetch `/deck` again. */
    data class DeckChanged(val updatedAt: Double) : Frame()

    data object Pong : Frame()

    data class Unknown(val type: String) : Frame()

    companion object {
        fun parse(json: String): Frame? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val id = o.optString("id").takeIf { it.isNotBlank() }
            return when (val t = o.optString("type")) {
                "ok" -> Ok(
                    o.optString("session"),
                    o.optJSONArray("chips")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList(),
                    o.optDouble("deck_updated_at", 0.0),
                )
                "start" -> Start(id ?: return null, o.optString("reply"))
                "delta" -> Delta(id ?: return null, o.optString("text"))
                "tool" -> Tool(id ?: return null, o.optString("name"), o.optString("state"))
                "thinking" -> Thinking(id ?: return null)
                "done" -> Done(id ?: return null, o.optString("text"), o.optBoolean("stopped", false))
                "error" -> Error(id, o.optString("message", "Something went wrong"))
                "deck" -> DeckChanged(o.optDouble("updated_at", 0.0))
                "pong" -> Pong
                else -> Unknown(t)
            }
        }
    }
}

object Frames {
    fun hello(device: String): String = JSONObject().put("type", "hello").put("v", 1).put("device", device).toString()
    fun user(id: String, text: String): String = JSONObject().put("type", "user").put("id", id).put("text", text).toString()
    fun stop(id: String): String = JSONObject().put("type", "stop").put("id", id).toString()
    const val PING = """{"type":"ping"}"""
}

/** One turn in the transcript. `who` is user or june; `pending` while June is still typing. */
data class Message(
    val id: String,
    val who: Who,
    val text: String,
    /** Epoch millis. */
    val at: Long,
    val pending: Boolean = false,
    /** Name of the tool currently running for this reply, or null. Drawn as a quiet marker. */
    val tool: String? = null,
    val thinking: Boolean = false,
    /** True for a message June sent on her own (a cron job, a watcher) rather than in reply. */
    val unprompted: Boolean = false,
) {
    enum class Who { USER, JUNE }
}
