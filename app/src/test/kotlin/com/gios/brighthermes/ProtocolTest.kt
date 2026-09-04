package com.gios.brighthermes

import com.gios.brighthermes.chat.Bot
import com.gios.brighthermes.chat.Frame
import com.gios.brighthermes.chat.Frames
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test
    fun `parses every server frame`() {
        assertEquals(
            Frame.Ok("s1", listOf("lights off"), listOf(Bot.JUNE, Bot("z13", "Qwen")), 5.0),
            Frame.parse("""{"type":"ok","session":"s1","chips":["lights off"],"bots":[{"id":"june","name":"June"},{"id":"z13","name":"Qwen"}],"deck_updated_at":5}"""),
        )
        // An older gateway with no roster still means June.
        assertEquals(listOf(Bot.JUNE), (Frame.parse("""{"type":"ok","session":"s1"}""") as Frame.Ok).bots)
        assertEquals(Frame.Start("u1", "r1", "june"), Frame.parse("""{"type":"start","id":"u1","reply":"r1"}"""))
        assertEquals(Frame.Start("u1", "r1", "z13"), Frame.parse("""{"type":"start","id":"u1","reply":"r1","bot":"z13"}"""))
        assertEquals(Frame.Delta("r1", "hi"), Frame.parse("""{"type":"delta","id":"r1","text":"hi"}"""))
        assertEquals(Frame.Tool("r1", "homeassistant", "started"), Frame.parse("""{"type":"tool","id":"r1","name":"homeassistant","state":"started"}"""))
        assertEquals(Frame.Thinking("r1"), Frame.parse("""{"type":"thinking","id":"r1"}"""))
        assertEquals(Frame.Done("r1", "done", true), Frame.parse("""{"type":"done","id":"r1","text":"done","stopped":true}"""))
        assertEquals(Frame.Error(null, "nope"), Frame.parse("""{"type":"error","message":"nope"}"""))
        assertEquals(Frame.DeckChanged(7.0), Frame.parse("""{"type":"deck","updated_at":7}"""))
        assertEquals(Frame.Pong, Frame.parse("""{"type":"pong"}"""))
        assertTrue(Frame.parse("""{"type":"weird"}""") is Frame.Unknown)
        assertNull(Frame.parse("not json"))
        assertNull(Frame.parse("""{"type":"delta","text":"no id"}"""))
    }

    @Test
    fun `client frames carry what the gateway reads`() {
        val hello = JSONObject(Frames.hello("lp3-abc"))
        assertEquals("hello", hello.getString("type"))
        assertEquals(1, hello.getInt("v"))
        assertEquals("lp3-abc", hello.getString("device"))
        val user = JSONObject(Frames.user("u1", "lights to 40%"))
        assertEquals("lights to 40%", user.getString("text"))
        assertEquals("june", user.getString("bot"))
        assertEquals("z13", JSONObject(Frames.user("u1", "hi", "z13")).getString("bot"))
        assertEquals("stop", JSONObject(Frames.stop("r1")).getString("type"))
    }

    @Test
    fun `server urls normalise the way people type them`() {
        assertEquals("https://hermes.basilnet.com", Prefs.normaliseServer("hermes.basilnet.com/"))
        assertEquals("http://192.168.68.59:8650", Prefs.normaliseServer("192.168.68.59:8650"))
        assertEquals("https://x.example", Prefs.normaliseServer("https://x.example"))
        assertEquals("wss://hermes.basilnet.com/ws?token=t", Prefs.wsUrl("https://hermes.basilnet.com", "t"))
        assertEquals("ws://192.168.68.59:8650/ws?token=t", Prefs.wsUrl("http://192.168.68.59:8650", "t"))
    }
}
