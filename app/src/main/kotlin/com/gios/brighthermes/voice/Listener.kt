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
    const val MAX_RECORD_SECONDS = 28
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

    /** Set while a recording is up: whether releasing should send or discard. */
    @Volatile private var commitRequested = false
    @Volatile private var stopRequested = false

    // One executor for model load + decode, so they serialise naturally.
    private val decode = Executors.newSingleThreadExecutor()
    @Volatile private var recognizer: OfflineRecognizer? = null
    private var recording: Thread? = null

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Load the model now so the first hold does not pay for it. Safe to call repeatedly. */
    fun warm(context: Context) = ensureRecognizer(context.applicationContext)

    /** Begin listening. Caller has checked [hasPermission]. [onText] runs on the decode thread. */
    fun start(context: Context, onText: (String) -> Unit) {
        if (_state.value is State.Listening || _state.value is State.Transcribing) return
        commitRequested = false
        stopRequested = false
        _level.value = 0f
        _state.value = State.Listening
        ensureRecognizer(context.applicationContext)
        recording = Thread({ recordLoop(onText) }, "brighthermes-voice").also { it.start() }
    }

    /** Full press: whatever has been said so far is to be sent when the button is released. */
    fun commit() {
        if (_state.value is State.Listening) commitRequested = true
    }

    /** Button released. Sends if [commit] was called during the hold, otherwise discards. */
    fun release() {
        if (_state.value is State.Listening) stopRequested = true
    }

    /** Stop and discard, whatever the button state — the activity going away, for instance. */
    fun cancel() {
        when (_state.value) {
            is State.Listening -> { commitRequested = false; stopRequested = true }
            is State.Error -> _state.value = State.Idle
            else -> Unit
        }
    }

    @SuppressLint("MissingPermission") // checked by the caller via hasPermission()
    private fun recordLoop(onText: (String) -> Unit) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBuf <= 0) return fail("Mic unavailable")
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
                maxOf(minBuf * 2, SAMPLE_RATE),
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord init failed", e)
            return fail("Mic unavailable")
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return fail("Mic unavailable")
        }

        val maxSamples = SAMPLE_RATE * MAX_RECORD_SECONDS
        val samples = FloatArray(maxSamples)
        var count = 0
        val chunk = FloatArray(SAMPLE_RATE / 10) // 100 ms
        try {
            record.startRecording()
            while (!stopRequested && count < maxSamples) {
                val n = record.read(chunk, 0, min(chunk.size, maxSamples - count), AudioRecord.READ_BLOCKING)
                if (n <= 0) break
                System.arraycopy(chunk, 0, samples, count, n)
                count += n
                var sum = 0.0
                for (i in 0 until n) sum += chunk[i] * chunk[i]
                _level.value = min(1f, sqrt(sum / n).toFloat() * 8f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "recording failed", e)
            record.release()
            return fail("Recording failed")
        }
        runCatching { record.stop() }
        record.release()
        _level.value = 0f

        if (!commitRequested || count < SAMPLE_RATE / 4) {
            // Let go without pressing, or under 250 ms of audio: nothing to send.
            _state.value = State.Idle
            return
        }
        _state.value = State.Transcribing
        val audio = samples.copyOf(count)
        decode.submit { transcribe(audio, onText) }
    }

    private fun transcribe(audio: FloatArray, onText: (String) -> Unit) {
        val rec = recognizer ?: return fail("Speech model failed to load")
        try {
            val stream = rec.createStream()
            stream.acceptWaveform(audio, SAMPLE_RATE)
            rec.decode(stream)
            val text = rec.getResult(stream).text.trim()
            stream.release()
            _state.value = State.Idle
            if (text.isNotEmpty()) onText(text)
        } catch (e: Exception) {
            Log.e(TAG, "transcription failed", e)
            fail("Transcription failed")
        }
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

    private fun fail(message: String) {
        _level.value = 0f
        _state.value = State.Error(message)
    }
}
