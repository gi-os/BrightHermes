package com.gios.brighthermes

import com.gios.brighthermes.voice.DictationTake
import org.junit.Assert.*
import org.junit.Test

/** Decoder seam: verifies sample/ownership/lifecycle logic, not native recognition accuracy. */
class DictationTakeTest {
    private val rate = 100
    private fun append(take: DictationTake, from: Int, size: Int) =
        take.append(FloatArray(size) { (from + it).toFloat() }, size)

    private fun complete(take: DictationTake, window: DictationTake.Window, word: String = "word") =
        take.complete(window, arrayOf("▁$word"), floatArrayOf(if (window.start == 0) .5f else 4f))

    @Test fun `long take has ordered overlapping audio bounded storage and final tail`() {
        val take = DictationTake(rate)
        val windows = mutableListOf<DictationTake.Window>()
        var sends = 0
        for (second in 0 until 299) {
            append(take, second * rate, rate)
            take.next()?.let {
                windows.add(it)
                assertFalse(requireNotNull(complete(take, it)).send)
            }
            assertTrue(take.isRecording)
            assertTrue(take.retainedSamples <= 28 * rate)
        }
        take.commit()
        take.release()
        take.endCapture()
        while (true) {
            val window = take.next() ?: break
            windows.add(window)
            if (requireNotNull(complete(take, window)).send) sends++
        }
        assertEquals(1, sends)
        assertTrue(take.isFinished)
        assertNull(take.next())
        var covered = 0
        windows.forEachIndexed { i, window ->
            assertEquals(i * 24 * rate, window.start)
            window.audio.forEachIndexed { j, sample -> assertEquals((window.start + j).toFloat(), sample, 0f) }
            assertTrue(window.start <= covered)
            covered = window.start + window.audio.size
        }
        assertEquals(299 * rate, covered)
        assertEquals(11 * rate, windows.last().audio.size)
    }

    @Test fun `word crossing midpoint is owned whole and overlap is not duplicated`() {
        val stitcher = DictationTake.Stitcher(rate)
        val tokens = arrayOf("▁say", "▁bound", "ary", ",", "▁next")
        val times = floatArrayOf(25f, 26.8f, 27.1f, 27.2f, 27.5f)
        assertEquals("say boundary,", stitcher.text(tokens, times, 0, 0, 2700, required = true))
        assertEquals("next", stitcher.text(
            arrayOf("▁bound", "ary", ",", "▁next"), floatArrayOf(.8f, 1.1f, 1.2f, 1.5f),
            2600, 2700, Int.MAX_VALUE,
        ))
    }

    @Test fun `repetitions whitespace punctuation and silence are preserved without lexical deduplication`() {
        assertEquals("go go, go!", DictationTake.Stitcher(rate).text(
            arrayOf("▁go", "▁go", ",", "▁go", "!"), floatArrayOf(0f, 1f, 1.1f, 2f, 2.1f),
            0, 0, Int.MAX_VALUE,
        ))
        assertEquals("", DictationTake.Stitcher(rate).text(emptyArray(), floatArrayOf(), 0, 0, 100))
        assertEquals("hello world.", DictationTake.Stitcher(rate).text(
            arrayOf("▁hello", "▁", "▁world", "."), floatArrayOf(0f, 1f, 2f, 2f),
            0, 0, 1000,
        ))
    }

    @Test fun `release waits for outstanding window then sends tail exactly once`() {
        val take = DictationTake(rate)
        append(take, 0, 2800)
        val first = requireNotNull(take.next())
        append(take, 2800, 35)
        take.commit()
        take.release()
        take.endCapture()
        assertNull(take.next())
        assertFalse(requireNotNull(complete(take, first, "first")).send)
        val tail = requireNotNull(take.next())
        assertEquals(435, tail.audio.size)
        val result = requireNotNull(complete(take, tail, "tail"))
        assertEquals("first tail", result.text)
        assertTrue(result.send)
        assertNull(take.next())
    }

    @Test fun `cancel and release before commit reject in flight results`() {
        for (cancel in listOf(false, true)) {
            val take = DictationTake(rate)
            append(take, 0, 2800)
            val first = requireNotNull(take.next())
            if (cancel) { take.commit(); take.cancel() } else { take.release(); take.commit() }
            take.endCapture()
            assertNull(complete(take, first))
            assertNull(take.next())
            assertTrue(take.isFinished)
        }
    }

    @Test fun `safety finalization drains all windows and sends even while held`() {
        val take = DictationTake(rate)
        var sends = 0
        for (second in 0 until 300) {
            append(take, second * rate, rate)
            while (true) {
                val window = take.next() ?: break
                if (requireNotNull(complete(take, window)).send) sends++
            }
        }
        take.endCapture()
        while (true) {
            val window = take.next() ?: break
            if (requireNotNull(complete(take, window)).send) sends++
        }
        assertFalse(take.isRecording)
        assertTrue(take.isFinished)
        assertEquals(1, sends)
    }

    @Test fun `preview is single flight includes stable prefix and cannot consume required tail`() {
        val take = DictationTake(rate)
        append(take, 0, 2800)
        complete(take, requireNotNull(take.next()), "stable")
        val preview = requireNotNull(take.next(true))
        assertFalse(preview.required)
        assertNull(take.next(true))
        assertEquals("stable tail", requireNotNull(complete(take, preview, "tail")).text)
        take.commit()
        take.release()
        take.endCapture()
        assertTrue(requireNotNull(complete(take, requireNotNull(take.next()), "final")).send)
    }

    @Test fun `bounded backlog rejects overflow instead of dropping unhandled samples`() {
        val take = DictationTake(rate)
        append(take, 0, 2800)
        take.next()
        append(take, 2800, 5200)
        assertEquals(5600, take.retainedSamples)
        assertNull(take.next())
        assertThrows(IllegalStateException::class.java) { append(take, 8000, 1) }
        take.cancel()
        assertNull(take.next())
    }

    @Test fun `missing timestamps fail rather than silently sending a gap`() {
        val take = DictationTake(rate)
        append(take, 0, 100)
        take.commit()
        take.release()
        take.endCapture()
        val window = requireNotNull(take.next())
        assertThrows(IllegalArgumentException::class.java) {
            take.complete(window, arrayOf("▁lost"), floatArrayOf())
        }
        take.cancel()
        assertNull(take.complete(window, arrayOf("▁stale"), floatArrayOf(0f)))
    }

    @Test fun `short holds and silent finalization do not send`() {
        val short = DictationTake(rate)
        append(short, 0, 24)
        short.commit()
        short.release()
        short.endCapture()
        assertNull(short.next())
        val silent = DictationTake(rate)
        append(silent, 0, 100)
        silent.commit()
        silent.release()
        silent.endCapture()
        assertFalse(requireNotNull(silent.complete(requireNotNull(silent.next()), emptyArray(), floatArrayOf())).send)
        assertTrue(silent.isFinished)
    }

    @Test fun `release cannot finalize before last microphone read has been appended`() {
        val take = DictationTake(rate)
        append(take, 0, 100)
        take.commit()
        take.release()
        assertNull(take.next())
        append(take, 100, 10)
        take.endCapture()
        val final = requireNotNull(take.next())
        assertEquals(110, final.audio.size)
        assertTrue(requireNotNull(complete(take, final)).send)
    }

    @Test fun `native observed timestamp drift cannot duplicate boundary word`() {
        val stitcher = DictationTake.Stitcher(16000)
        assertEquals("I wish", stitcher.text(
            arrayOf(" I", " w", "ish"), floatArrayOf(26.5f, 26.96f, 27.04f),
            416000, 432000, 848000, true,
        ))
        assertEquals("to see", stitcher.text(
            arrayOf(" w", "ish", " to", " see"), floatArrayOf(1.12f, 1.2f, 1.5f, 1.7f),
            832000, 848000, Int.MAX_VALUE,
        ))
    }

    @Test fun `drift cannot drop withheld word and repeated words are not erased`() {
        val stitcher = DictationTake.Stitcher(rate)
        assertEquals("go", stitcher.text(
            arrayOf("▁go", "▁go"), floatArrayOf(26.1f, 27.1f), 0, 0, 2700, true,
        ))
        assertEquals("go now", stitcher.text(
            arrayOf("▁go", "▁go", "▁now"), floatArrayOf(.2f, .95f, 2f),
            2600, 2700, Int.MAX_VALUE,
        ))
        val withheld = DictationTake.Stitcher(rate)
        assertEquals("before", withheld.text(
            arrayOf("▁before", "▁after"), floatArrayOf(25f, 27.1f), 0, 0, 2700, true,
        ))
        assertEquals("after", withheld.text(
            arrayOf("▁after"), floatArrayOf(.95f), 2600, 2700, Int.MAX_VALUE,
        ))
    }

    @Test fun `missing or ambiguous overlap anchor fails rather than guessing`() {
        for (times in listOf(floatArrayOf(1f), floatArrayOf(.8f, 1.2f))) {
            val stitcher = DictationTake.Stitcher(rate)
            stitcher.text(arrayOf("▁go"), floatArrayOf(26.9f), 0, 0, 2700, true)
            val tokens = if (times.size == 1) arrayOf("▁changed") else arrayOf("▁go", "▁go")
            assertThrows(IllegalStateException::class.java) {
                stitcher.text(tokens, times, 2600, 2700, Int.MAX_VALUE)
            }
        }
    }

    @Test fun `missing committed edge word uses withheld anchor without losing it`() {
        val stitcher = DictationTake.Stitcher(rate)
        assertEquals("her eyes.", stitcher.text(
            arrayOf(" her", " eyes", ".", " It"), floatArrayOf(26f, 26.5f, 26.6f, 27.4f),
            0, 0, 2700, true,
        ))
        assertEquals("It is", stitcher.text(
            arrayOf(" It", " is"), floatArrayOf(1.45f, 1.6f), 2600, 2700, Int.MAX_VALUE,
        ))
    }

    @Test fun `silent overlaps continue but contradictory speech fails`() {
        val stitcher = DictationTake.Stitcher(rate)
        assertEquals("", stitcher.text(emptyArray(), floatArrayOf(), 0, 0, 2700, true))
        assertEquals("", stitcher.text(emptyArray(), floatArrayOf(), 2600, 2700, 5300, true))
        assertThrows(IllegalStateException::class.java) {
            stitcher.text(arrayOf("▁unexpected"), floatArrayOf(1f), 5200, 5300, Int.MAX_VALUE)
        }
    }
}
