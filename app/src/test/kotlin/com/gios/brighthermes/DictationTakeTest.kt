package com.gios.brighthermes

import com.gios.brighthermes.voice.DictationTake
import org.junit.Assert.*
import org.junit.Test

class DictationTakeTest {
    private val rate = 100

    private fun record(take: DictationTake, seconds: Int): FloatArray {
        val expected = FloatArray(rate * seconds) { it.toFloat() }
        var offset = 0
        while (take.isRecording && offset < expected.size) {
            val size = minOf(rate, take.remaining, expected.size - offset)
            take.append(expected.copyOfRange(offset, offset + size), size)
            offset += size
        }
        return expected
    }

    @Test fun `hold crosses old cutoff and release hands every sample to final decoding`() {
        val take = DictationTake(rate)
        val expected = record(take, 45)
        assertTrue("Still listening before release", take.isRecording)
        take.commit()
        take.release()
        assertFalse(take.isRecording)
        val finalDecoderInput = requireNotNull(take.finish())
        assertArrayEquals(expected, finalDecoderInput, 0f)
    }

    @Test fun `old cutoff alone neither stops listening nor finalizes`() {
        val take = DictationTake(rate)
        record(take, 28)
        assertTrue("28 seconds must not end the hold", take.isRecording)
        assertNull(take.finish())
    }

    @Test fun `cancel at and beyond old cutoff overrides commit and discards`() {
        for (seconds in listOf(28, 45)) {
            val take = DictationTake(rate)
            record(take, seconds)
            take.commit()
            take.cancel()
            assertFalse(take.isRecording)
            assertNull("No final decode or text delivery on cancel", take.finish())
        }
    }

    @Test fun `partial decoding continues after twenty seconds with latest bounded window`() {
        val take = DictationTake(rate)
        val expected = record(take, 35)
        assertArrayEquals(expected.copyOfRange(15 * rate, 35 * rate), take.partial(), 0f)
    }

    @Test fun `pending partial is single flight and next tick resumes after completion`() {
        val take = DictationTake(rate)
        record(take, 21)
        val first = requireNotNull(take.partial())
        take.append(FloatArray(rate) { -1f }, rate)
        assertNull("No queued partial while decoder owns a snapshot", take.partial())
        take.partialComplete()
        val second = requireNotNull(take.partial())
        assertEquals(20 * rate, second.size)
        assertEquals(-1f, second.last(), 0f)
        assertEquals(2099f, first.last(), 0f)
        assertNull(take.partial())
    }

    @Test fun `safety ceiling finalizes without release but cancel still wins before handoff`() {
        val take = DictationTake(rate)
        val expected = record(take, 300)
        assertFalse(take.isRecording)
        assertArrayEquals(expected, take.finish(), 0f)

        val canceled = DictationTake(rate)
        record(canceled, 300)
        canceled.cancel()
        assertNull(canceled.finish())
    }

    @Test fun `uncommitted release and sub quarter second holds are discarded`() {
        val uncommitted = DictationTake(rate)
        record(uncommitted, 45)
        uncommitted.release()
        assertNull(uncommitted.finish())

        val short = DictationTake(rate)
        short.append(FloatArray(24), 24)
        short.commit()
        short.release()
        assertNull(short.finish())
    }
}
