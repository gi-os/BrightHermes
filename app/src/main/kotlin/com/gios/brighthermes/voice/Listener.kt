package com.gios.brighthermes.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Push-to-talk, transcribed on the phone.
 *
 * This is BrightThumb's `VoiceInputManager` with the keyboard taken out: the same NVIDIA
 * Parakeet TDT 110M (int8) through sherpa-onnx, the same recording loop, the same reasons.
 * Nothing about the audio leaves the device; June gets text.
 *
 * ## Why on-device, when there is a six-core server one hop away
 *
 * Because the priority is *fast*, and the round trip is where the time goes. Parakeet is a
 * transducer, so it decodes only the audio it was given — a two-second "lights off" is a few
 * hundred milliseconds of decode on the phone, with the model already warm. Streaming the same
 * audio to BasilNet, decoding there and coming back would be quicker per sample and slower per
 * command, and would not work at all on the F train. The model loads once, on the first hold,
 * and stays loaded; [warm] can be called at app open to hide even that.
 *
 * ## The gesture
 *
 * Hold the wheel in to talk; let go to send. [start] on the hold, [commit] then [release] on the
 * let-go. [commit] exists separately so a caller that wants a discard path — the activity going
 * away, a cancel gesture — can call [release] without it and nothing is sent. The same three are
 * wired to a long-press on the hint line for a phone in a pocket.
 */
object Listener {
    const val SAMPLE_RATE = 16000
    private const val ASSET_DIR = "parakeet-110m-en"
    private const val TAG = "BrightHermes.voice"

    sealed class State {
        data object Idle : State()
        data object Listening : State()
        data object Transcribing : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 0..1-ish RMS of the latest chunk, for the level bar. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _modelReady = MutableStateFlow(false)
    val modelReady: StateFlow<Boolean> = _modelReady.asStateFlow()

    /** Stable committed words plus a best-effort preview of the current overlapping window. */
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()
    private const val PARTIAL_EVERY_MS = 1100L

    @Volatile private var take: DictationTake? = null

    // One executor for model load + decode, so they serialise naturally.
    private val decode = Executors.newSingleThreadExecutor()
    @Volatile private var recognizer: OfflineRecognizer? = null
    private var recording: Thread? = null
    private var captureActive = false
    private var decodeTask: Future<*>? = null

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Load the model now so the first hold does not pay for it. Safe to call repeatedly. */
    fun warm(context: Context) = ensureRecognizer(context.applicationContext)

    /** Begin listening. Caller has checked [hasPermission]. [onText] runs on the decode thread. */
    @Synchronized fun start(context: Context, onText: (String) -> Unit) {
        if (take != null) return
        val current = DictationTake(SAMPLE_RATE)
        take = current
        _level.value = 0f
        _partial.value = ""
        _state.value = State.Listening
        captureActive = true
        ensureRecognizer(context.applicationContext)
        recording = Thread({ recordLoop(current, onText) }, "brighthermes-voice").also { it.start() }
    }

    /** Full press: whatever has been said so far is to be sent when the button is released. */
    @Synchronized fun commit() {
        if (_state.value is State.Listening) take?.commit()
    }

    /** Button released. Sends if [commit] was called during the hold, otherwise discards. */
    @Synchronized fun release() {
        if (_state.value is State.Listening) take?.release()
    }

    /** Stop and discard, whatever the button state — the activity going away, for instance. */
    @Synchronized fun cancel() {
        take?.cancel()
        decodeTask?.cancel(false) // Clear queued obsolete snapshots without interrupting JNI.
        decodeTask = null
        if (!captureActive) {
            take = null
            _partial.value = ""
            _state.value = State.Idle
        }
    }

    @SuppressLint("MissingPermission") // checked by the caller via hasPermission()
    private fun recordLoop(current: DictationTake, onText: (String) -> Unit) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBuf <= 0) return recordingFailed(current, "Mic unavailable", captureEnded = true)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
                maxOf(minBuf * 2, SAMPLE_RATE),
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord init failed", e)
            return recordingFailed(current, "Mic unavailable", captureEnded = true)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return recordingFailed(current, "Mic unavailable", captureEnded = true)
        }

        val chunk = FloatArray(SAMPLE_RATE / 10) // 100 ms
        var lastPartialAt = 0
        try {
            record.startRecording()
            while (current.isRecording) {
                val available = current.remaining
                check(available > 0) { "Transcription cannot keep up" }
                val n = record.read(chunk, 0, min(chunk.size, available), AudioRecord.READ_BLOCKING)
                check(n > 0) { "Microphone read failed: $n" }
                current.append(chunk, n)
                var sum = 0.0
                for (i in 0 until n) sum += chunk[i] * chunk[i]
                synchronized(this) {
                    if (take === current && !current.isFinished) {
                        _level.value = min(1f, sqrt(sum / n).toFloat() * 8f)
                    }
                }
                lastPartialAt += n
                val preview = lastPartialAt >= SAMPLE_RATE * PARTIAL_EVERY_MS / 1000
                val window = current.next(preview)
                if (window != null) {
                    lastPartialAt = 0
                    schedule(current, window, onText)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "recording failed", e)
            recordingFailed(current, e.message ?: "Recording failed")
        } finally {
            runCatching { record.stop() }
            record.release()
        }
        synchronized(this) {
            if (take !== current) return
            captureActive = false
            current.endCapture()
            _level.value = 0f
            if (current.isFinished) {
                take = null
                if (_state.value !is State.Error) {
                    _partial.value = ""
                    _state.value = State.Idle
                }
                return
            }
            _state.value = State.Transcribing
            current.next()?.let { window -> schedule(current, window, onText) }
            if (current.isFinished) {
                take = null
                _partial.value = ""
                _state.value = State.Idle
            }
        }
    }

    @Synchronized private fun schedule(
        current: DictationTake, window: DictationTake.Window, onText: (String) -> Unit,
    ) {
        if (take === current && !current.isFinished) {
            decodeTask = decode.submit { decodeWindow(current, window, onText) }
        }
    }

    /** Required work drains before another preview is admitted. Never share the recognizer concurrently. */
    private fun decodeWindow(current: DictationTake, window: DictationTake.Window, onText: (String) -> Unit) {
        synchronized(this) {
            if (take !== current || current.isFinished) return
        }
        try {
            val rec = recognizer ?: error("Speech model failed to load")
            val stream = rec.createStream()
            val result = try {
                stream.acceptWaveform(window.audio, SAMPLE_RATE)
                rec.decode(stream)
                rec.getResult(stream)
            } finally {
                stream.release()
            }
            synchronized(this) {
                if (take !== current) return
                val publication = current.complete(window, result.tokens, result.timestamps)
                if (publication != null) {
                    _partial.value = publication.text
                    if (window.final) {
                        if (!captureActive) take = null
                        _state.value = State.Idle
                        if (publication.send) onText(publication.text)
                    }
                }
            }
        } catch (e: Exception) {
            if (window.required) {
                Log.e(TAG, "required transcription failed", e)
                synchronized(this) {
                    if (take === current && !current.isFinished) {
                        current.cancel()
                        if (!captureActive) take = null
                        fail("Transcription failed")
                    }
                }
                return
            }
            Log.w(TAG, "partial decode failed", e)
            current.previewFailed()
        }
        synchronized(this) {
            if (take !== current) return
            current.next()?.let { next -> schedule(current, next, onText) }
            if (current.isFinished && !captureActive && take === current) {
                take = null
                if (_state.value !is State.Error) _state.value = State.Idle
            }
        }
    }

    @Synchronized private fun recordingFailed(current: DictationTake, message: String, captureEnded: Boolean = false) {
        if (take !== current) return
        val alreadyFinished = current.isFinished
        current.cancel()
        if (captureEnded) {
            captureActive = false
            take = null
        }
        if (alreadyFinished) {
            if (captureEnded && _state.value !is State.Error) _state.value = State.Idle
            return
        }
        fail(message)
    }

    private fun ensureRecognizer(appContext: Context) {
        if (recognizer != null) return
        decode.submit {
            if (recognizer != null) return@submit
            try {
                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = "$ASSET_DIR/encoder.int8.onnx",
                            decoder = "$ASSET_DIR/decoder.int8.onnx",
                            joiner = "$ASSET_DIR/joiner.int8.onnx",
                        ),
                        tokens = "$ASSET_DIR/tokens.txt",
                        // Stated rather than sniffed: NeMo models need their own per-feature
                        // normalisation, and getting that silently wrong degrades accuracy
                        // instead of failing.
                        modelType = "nemo_transducer",
                        numThreads = 2,
                        debug = false,
                    ),
                    decodingMethod = "greedy_search",
                )
                recognizer = OfflineRecognizer(appContext.assets, config)
                _modelReady.value = true
            } catch (e: Throwable) {
                Log.e(TAG, "failed to load speech model", e)
                fail("Speech model failed to load")
            }
        }
    }

    @Synchronized private fun fail(message: String) {
        take?.cancel()
        if (!captureActive) take = null
        _level.value = 0f
        _state.value = State.Error(message)
    }
}
