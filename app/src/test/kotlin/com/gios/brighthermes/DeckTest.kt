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
    fun `widgets parse, hide when blank, take a row, and stay off the strip`() {
        val d = Deck.parse(
            """{"layout":[{"id":"weather","span":1},{"id":"web1","span":1},{"id":"web2","span":2}],
                "tiles":{"weather":{"label":"NYC","value":"63°","sub":""},
                         "web1":{"label":"Garage","value":"","sub":"","html":"<b>open</b>","height":4},
                         "web2":{"label":"Widget 2","value":"","sub":"","html":"","height":8}},
                "catalog":[{"id":"web1","name":"Widget 1","local":false,"html":true}],"chips":[],"updated_at":1}""",
        )
        val w1 = d.tiles.getValue("web1")
        assertTrue(w1.isWidget && w1.showable && w1.height == 4)
        assertFalse(d.tiles.getValue("web2").showable)
        assertEquals(listOf("weather", "web1"), d.rows(emptyMap()).map { it.second.id })
        assertEquals(listOf("web1"), d.widgets().map { it.id })
        assertEquals(listOf("weather"), d.strip(emptyMap()).map { it.id })
        assertTrue(d.catalog.single().html)
        // A widget in a half slot still gets its own row.
        val rows = packRows(d.rows(emptyMap())) { it.isWidget }
        assertEquals(listOf(1, 1), rows.map { it.size })
        // And it round-trips through the cache with its HTML intact.
        assertEquals("<b>open</b>", Deck.parse(d.toJson()).tiles.getValue("web1").html)
    }

    @Test
    fun `widget document wraps a fragment and leaves a page alone`() {
        val frag = com.gios.brighthermes.ui.document("<b>hi</b>", "https://h", "tok", "lp3-x")
        assertTrue(frag.startsWith("<!doctype html>"))
        assertTrue(frag.contains("window.brighthermes={") && frag.contains("\"token\":\"tok\"") && frag.contains("\"device\":\"lp3-x\""))
        assertTrue(frag.contains("<body><b>hi</b></body>"))
        val page = com.gios.brighthermes.ui.document("<html><head><title>x</title></head><body>y</body></html>", "https://h", "tok", "d")
        assertTrue(page.startsWith("<html><head><script>window.brighthermes="))
        assertTrue(page.endsWith("<body>y</body></html>"))
    }

    @Test
    fun `grid rows pair singles and give doubles their own row`() {
        val rows = packRows(listOf(Slot("a", 1) to 1, Slot("b", 1) to 2, Slot("c", 2) to 3, Slot("d", 1) to 4))
        assertEquals(listOf(2, 1, 1), rows.map { it.size })
        assertEquals("c", rows[1][0].first.id)
    }
}
