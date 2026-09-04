package com.gios.brighthermes

import com.gios.brighthermes.deck.Deck
import com.gios.brighthermes.deck.Slot
import com.gios.brighthermes.deck.Tile
import com.gios.brighthermes.deck.collapsedLine
import com.gios.brighthermes.deck.layoutJson
import com.gios.brighthermes.ui.packRows
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckTest {

    private val body = """
        {"v":1,
         "layout":[{"id":"clock","span":1},{"id":"weather","span":1},{"id":"digest","span":2},{"id":"bogus"}],
         "tiles":{
           "weather":{"label":"NYC","value":"63°","sub":"rain 7p","action":"brighthermes://tile/weather","updated_at":100.0,"stale_at":200.0},
           "digest":{"label":"June","value":"3 done","sub":"1 waiting on you","updated_at":90.0}
         },
         "catalog":[{"id":"clock","name":"Clock","local":true},{"id":"weather","name":"Weather","local":false}],
         "chips":["lights off","snooze 10m"],
         "updated_at":100.0}
    """.trimIndent()

    @Test
    fun `parses the gateway body`() {
        val d = Deck.parse(body)
        assertEquals(listOf(Slot("clock", 1), Slot("weather", 1), Slot("digest", 2), Slot("bogus", 1)), d.layout)
        assertEquals("63°", d.tiles.getValue("weather").value)
        assertEquals(200.0, d.tiles.getValue("weather").staleAt!!, 0.0)
        assertNull(d.tiles.getValue("digest").staleAt)
        assertTrue(d.catalog.first { it.id == "clock" }.local)
        assertEquals(listOf("lights off", "snooze 10m"), d.chips)
    }

    @Test
    fun `rows skip slots with nothing to show and pull local tiles`() {
        val d = Deck.parse(body)
        val clock = Tile("clock", "", "12:34", "Mon 4")
        val rows = d.rows(mapOf("clock" to clock))
        assertEquals(listOf("clock", "weather", "digest"), rows.map { it.second.id })
    }

    @Test
    fun `strip leaves the clock out and takes three`() {
        val d = Deck.parse(body)
        val strip = d.strip(mapOf("clock" to Tile("clock", "", "12:34", "")))
        assertEquals(listOf("weather", "digest"), strip.map { it.id })
    }

    @Test
    fun `staleness is judged against now`() {
        val t = Deck.parse(body).tiles.getValue("weather")
        assertFalse(t.isStale(150.0))
        assertTrue(t.isStale(250.0))
    }

    @Test
    fun `round trips through toJson for the cache`() {
        val d = Deck.parse(body)
        val again = Deck.parse(d.toJson())
        assertEquals(d.layout, again.layout)
        assertEquals(d.tiles, again.tiles)
        assertEquals(d.chips, again.chips)
    }

    @Test
    fun `layout json is what the server expects`() {
        val j = JSONObject(layoutJson(listOf(Slot("digest", 2), Slot("clock", 1))))
        val arr = j.getJSONArray("layout")
        assertEquals("digest", arr.getJSONObject(0).getString("id"))
        assertEquals(2, arr.getJSONObject(0).getInt("span"))
    }

    @Test
    fun `collapsed line reads like the brief`() {
        val line = collapsedLine(
            listOf(
                Tile("weather", "NYC", "63°", "rain 7p"),
                Tile("next", "Next", "7:30p", "Dinner, Keens"),
                Tile("digest", "June", "2 pending", "1 waiting"),
            ),
        )
        assertEquals("63° · 7:30p dinner · 2 pending", line)
    }

    @Test
    fun `grid rows pair singles and give doubles their own row`() {
        val rows = packRows(listOf(Slot("a", 1) to 1, Slot("b", 1) to 2, Slot("c", 2) to 3, Slot("d", 1) to 4))
        assertEquals(listOf(2, 1, 1), rows.map { it.size })
        assertEquals("c", rows[1][0].first.id)
    }
}
