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
    /** The handshake answer. `session` is June's session id for this phone; `bots` is who can be talked to. */
    data class Ok(val session: String, val chips: List<String>, val bots: List<Bot>, val deckUpdatedAt: Double) : Frame()

    /** Someone started answering user message [id]; the reply will be [reply], from [bot]. */
    data class Start(val id: String, val reply: String, val bot: String) : Frame()

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
                    o.optJSONArray("bots")?.let { a ->
                        (0 until a.length()).mapNotNull { i ->
                            val b = a.optJSONObject(i) ?: return@mapNotNull null
                            val bid = b.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            Bot(bid, b.optString("name", bid))
                        }
                    } ?: listOf(Bot.JUNE),
                    o.optDouble("deck_updated_at", 0.0),
                )
                "start" -> Start(id ?: return null, o.optString("reply"), o.optString("bot", Bot.JUNE.id))
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
    fun user(id: String, text: String, bot: String = Bot.JUNE.id): String =
        JSONObject().put("type", "user").put("id", id).put("text", text).put("bot", bot).toString()
    fun stop(id: String): String = JSONObject().put("type", "stop").put("id", id).toString()
    const val PING = """{"type":"ping"}"""
}

/**
 * Someone to talk to. June is the default; any other bot is another Hermes agent the gateway was
 * configured with (`BOTS`) — a second profile on June's gateway, or a Hermes on another box.
 * Same frames, same sessions, same tool markers; the phone only picks.
 */
data class Bot(val id: String, val name: String) {
    companion object {
        val JUNE = Bot("june", "June")
    }
}

/** One turn in the transcript. `who` is user or june; `pending` while the bot is still typing. */
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
    /** Which bot this belongs to. The transcript shows one bot at a time. */
    val bot: String = Bot.JUNE.id,
) {
    enum class Who { USER, JUNE }
}
