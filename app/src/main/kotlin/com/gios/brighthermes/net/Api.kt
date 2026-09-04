package com.gios.brighthermes.net

import com.gios.brighthermes.chat.Message
import com.gios.brighthermes.deck.Deck
import com.gios.brighthermes.deck.Slot
import com.gios.brighthermes.deck.layoutJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The gateway's plain-HTTP half. Small on purpose: the deck, the layout, the transcript, the
 * journal. Chat is [Socket]. Every call is a suspend on IO and throws [IOException] on anything
 * that is not a 2xx, with the server's `{"error": …}` text where there is one.
 */
class Api(
    private val server: () -> String,
    private val token: () -> String,
    private val device: () -> String,
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    private val json = "application/json; charset=utf-8".toMediaType()

    private fun req(path: String): Request.Builder = Request.Builder()
        .url(server().trimEnd('/') + path)
        .header("Authorization", "Bearer " + token())
        .header("X-Device", device())

    private suspend fun call(b: Request.Builder): String = withContext(Dispatchers.IO) {
        client.newCall(b.build()).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(body).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }
                throw IOException(msg ?: "HTTP ${r.code}")
            }
            body
        }
    }

    /** `/health` — the setup screen's "does this URL and token work". Throws on a bad token. */
    suspend fun health(): Boolean {
        val o = JSONObject(call(req("/health")))
        // /health itself is unauthenticated; /deck is the auth check.
        call(req("/deck"))
        return o.optBoolean("june", false)
    }

    /** The raw `/deck` body, for the cache, and its parse. */
    suspend fun deck(): Pair<String, Deck> {
        val body = call(req("/deck"))
        return body to Deck.parse(body)
    }

    suspend fun putLayout(layout: List<Slot>) {
        call(req("/deck/layout").put(layoutJson(layout).toRequestBody(json)))
    }

    suspend fun thread(limit: Int = 60): List<Message> {
        val arr = JSONObject(call(req("/thread?limit=$limit"))).optJSONArray("messages") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val m = arr.optJSONObject(i) ?: return@mapNotNull null
            val who = when (m.optString("role")) {
                "user" -> Message.Who.USER
                "assistant" -> Message.Who.JUNE
                else -> return@mapNotNull null
            }
            val ts = m.optDouble("ts", 0.0)
            Message(id = "h$i", who = who, text = m.optString("content"), at = if (ts > 0) (ts * 1000).toLong() else 0L)
        }
    }

    /** The journal pipe. `events` are `{app, type, ts, payload}`; batched by the caller. */
    suspend fun ingest(events: JSONArray) {
        call(req("/ingest").post(JSONObject().put("events", events).toString().toRequestBody(json)))
    }
}
