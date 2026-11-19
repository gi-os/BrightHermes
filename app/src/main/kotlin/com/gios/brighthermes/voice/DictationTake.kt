package com.gios.brighthermes.voice

/**
 * Sample-indexed offline windows: 28 s input, 24 s stride, ownership at overlap midpoint.
 * At most 56 s capture backlog plus bounded single-flight decoder snapshots.
 * All methods crossing capture/decode/UI threads use this take's monitor.
 */
internal class DictationTake(private val sampleRate: Int) {
    data class Window(
        val audio: FloatArray, val start: Int, val lower: Int, val upper: Int,
        val required: Boolean, val final: Boolean,
    )
    data class Result(val text: String, val send: Boolean)

    private var samples = FloatArray(sampleRate * 56)
    private var count = 0
    private var start = 0
    private var total = 0
    private var lower = 0
    private var stopped = false
    private var captureEnded = false
    private var committed = false
    private var busy = false
    private var finished = false
    private val text = StringBuilder()
    private val stitcher = Stitcher(sampleRate)

    val remaining: Int @Synchronized get() = minOf(samples.size - count, sampleRate * 300 - total)
    val isRecording: Boolean @Synchronized get() = !stopped && !finished && total < sampleRate * 300
    val isFinished: Boolean @Synchronized get() = finished
    val retainedSamples: Int @Synchronized get() = count

    @Synchronized fun commit() { if (!stopped && !finished) committed = true }
    @Synchronized fun release() { stopped = true }
    @Synchronized fun endCapture() { captureEnded = true }
    @Synchronized fun cancel() { stopped = true; finished = true; count = 0; samples = EMPTY_AUDIO }

    @Synchronized fun append(chunk: FloatArray, size: Int) {
        if (finished) return
        check(size <= remaining) { "Transcription cannot keep up" }
        chunk.copyInto(samples, count, 0, size)
        count += size
        total += size
    }

    /** Null means no work, not completion. A required window is never skipped for a preview. */
    @Synchronized fun next(preview: Boolean = false): Window? {
        if (busy || finished) return null
        val ending = captureEnded
        if (ending && ((!committed && stopped) || total < sampleRate / 4)) {
            cancel()
            return null
        }
        val full = count >= sampleRate * 28
        if (!ending && !full && (!preview || count < sampleRate / 2)) return null
        val final = ending && count <= sampleRate * 28
        val required = full || ending
        val size = minOf(count, sampleRate * 28)
        val upper = if (final || !required) Int.MAX_VALUE else start + sampleRate * 26
        val window = Window(samples.copyOf(size), start, lower, upper, required, final)
        if (required && !final) {
            val stride = sampleRate * 24
            samples.copyInto(samples, 0, stride, count)
            count -= stride
            start += stride
            lower = upper
        }
        busy = true
        return window
    }

    @Synchronized fun complete(window: Window, tokens: Array<String>, timestamps: FloatArray): Result? {
        if (finished || (stopped && !committed)) {
            cancel()
            return null
        }
        val decoded = stitcher.text(tokens, timestamps, window.start, window.lower, window.upper, window.required)
        busy = false
        if (!window.required) return Result(join(text.toString(), decoded), false)
        if (decoded.isNotEmpty()) {
            if (text.isNotEmpty()) text.append(' ')
            text.append(decoded)
        }
        if (window.final) { finished = true; count = 0; samples = EMPTY_AUDIO }
        return Result(text.toString(), window.final && text.isNotEmpty())
    }

    @Synchronized fun previewFailed() { busy = false }

    companion object {
        private val EMPTY_AUDIO = FloatArray(0)
        private fun join(a: String, b: String) = if (a.isEmpty()) b else if (b.isEmpty()) a else "$a $b"
    }

    /**
     * Midpoint ownership with a timestamp-constrained word anchor across each overlap.
     * A changed/missing/ambiguous anchor aborts required work, rather than guessing a seam.
     */
    internal class Stitcher(private val rate: Int) {
        private data class Word(val text: String, val position: Double) {
            val key = text.trim { !it.isLetterOrDigit() }.lowercase()
        }
        private var hasBoundary = false
        private var anchor: Word? = null
        private var anchorIncluded = false
        private var fallbackAnchor: Word? = null
        private var overlapEnd = 0

        fun text(
            tokens: Array<String>, timestamps: FloatArray, start: Int, lower: Int, upper: Int,
            required: Boolean = false,
        ): String {
            val words = words(tokens, timestamps, start)
            var from = 0
            if (hasBoundary) {
                val previousAnchor = anchor
                if (previousAnchor == null) {
                    check(words.none { it.position < overlapEnd }) { "Speech changed across silent overlap" }
                } else {
                    val best = findAnchor(words, previousAnchor)
                    if (best >= 0) {
                        from = best + if (anchorIncluded) 1 else 0
                    } else {
                        val fallback = fallbackAnchor
                        val next = if (fallback == null) -1 else findAnchor(words, fallback)
                        check(next >= 0) { "Cannot reconcile speech overlap" }
                        from = next
                    }
                }
            } else {
                while (from < words.size && words[from].position < lower) from++
            }
            var until = from
            while (until < words.size && words[until].position < upper) until++
            val out = StringBuilder()
            for (i in from until until) {
                if (out.isNotEmpty()) out.append(' ')
                out.append(words[i].text)
            }
            if (required && upper != Int.MAX_VALUE) {
                // Prefer the last committed overlap word; otherwise anchor the first withheld word.
                // Repetitions are distinguished by absolute sample position, not suffix deletion.
                anchor = if (until > from && words[until - 1].position >= upper - rate * 2) {
                    anchorIncluded = true
                    words[until - 1]
                } else {
                    anchorIncluded = false
                    words.getOrNull(until)
                }
                fallbackAnchor = if (anchorIncluded) words.getOrNull(until) else null
                hasBoundary = true
                overlapEnd = upper + rate * 2
            }
            return out.toString()
        }

        private fun findAnchor(words: List<Word>, previous: Word): Int {
            var found = -1
            for (i in words.indices) {
                val word = words[i]
                if (word.key == previous.key && kotlin.math.abs(word.position - previous.position) <= rate * 0.6) {
                    check(found < 0) { "Ambiguous repeated speech at overlap" }
                    found = i
                }
            }
            return found
        }

        private fun words(tokens: Array<String>, timestamps: FloatArray, start: Int): List<Word> {
            require(tokens.size == timestamps.size) { "Missing speech token timestamps" }
            val words = ArrayList<Word>()
            val current = StringBuilder()
            var position = 0.0
            var previous = -1f
            fun flush() {
                val word = current.toString().trim()
                if (word.isNotEmpty()) words.add(Word(word, position))
                current.setLength(0)
            }
            for (i in tokens.indices) {
                val time = timestamps[i]
                require(time.isFinite() && time >= previous && time >= 0) { "Invalid speech timestamps" }
                previous = time
                val token = tokens[i]
                if (i == 0 || token.startsWith('▁') || token.startsWith(' ')) {
                    flush()
                    position = start.toDouble() + time.toDouble() * rate
                }
                current.append(token.replace('▁', ' '))
            }
            flush()
            return words
        }
    }
}
