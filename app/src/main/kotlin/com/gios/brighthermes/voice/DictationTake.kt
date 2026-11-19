package com.gios.brighthermes.voice

/** One hold's audio and release/discard decision, independent of the Android microphone. */
internal class DictationTake(private val sampleRate: Int) {
    // Five minutes of 16 kHz float PCM: 19.2 MB, independent of hold duration.
    private val samples = FloatArray(sampleRate * 300)
    private var count = 0
    @Volatile private var stopRequested = false
    @Volatile private var commitRequested = false
    private var partialBusy = false
    private var finished = false

    val remaining: Int get() = samples.size - count
    val isRecording: Boolean get() = !stopRequested && remaining > 0

    @Synchronized fun commit() { if (!stopRequested) commitRequested = true }
    @Synchronized fun release() { stopRequested = true }
    @Synchronized fun cancel() { commitRequested = false; stopRequested = true }

    fun append(chunk: FloatArray, size: Int) {
        chunk.copyInto(samples, count, 0, size)
        count += size
    }

    /** Called on the recording thread; completion may arrive from the decode thread. */
    @Synchronized fun partial(): FloatArray? {
        if (partialBusy || !isRecording || count < sampleRate / 2) return null
        partialBusy = true
        return samples.copyOfRange(maxOf(0, count - sampleRate * 20), count)
    }

    @Synchronized fun partialComplete() { partialBusy = false }

    @Synchronized fun finish(): FloatArray? {
        if (isRecording || finished) return null
        finished = true
        // An explicit stop without commit is a discard, even at the safety ceiling.
        val send = commitRequested || (!stopRequested && remaining == 0)
        stopRequested = true
        if (!send || count < sampleRate / 4) return null
        return if (remaining == 0) samples else samples.copyOf(count)
    }
}
