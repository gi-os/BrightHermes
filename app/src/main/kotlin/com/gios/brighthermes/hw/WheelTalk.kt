package com.gios.brighthermes.hw

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.gios.light.common.hw.LightKey
import com.gios.light.common.hw.LightKeys
import com.gios.light.common.hw.WheelBus

/**
 * The wheel, pointed at this app: turns scroll, a click switches the deck, a **hold is talk**.
 *
 * Ported from Roll's `LightControls`, which splits a wheel press into click and press-and-turn
 * on one fact: a held `WHEEL_CLICK` produces **no key repeat**. DOWN arrives, then whatever the
 * wheel does while held, then UP. So by the time UP lands you know whether the press was only a
 * press. Roll needs nothing more, because its modifier is "turned while held". A hold that does
 * nothing but last needs a clock, so this adds one: [HOLD_MS] after DOWN, with no turn and no UP,
 * the press becomes push-to-talk. Release sends. A turn during the press is a press-and-turn and
 * spends the click, exactly as in Roll — turning the wheel while holding it is physically not a
 * click, and it is not a hold either.
 *
 * Why the wheel and not the camera button: BrightControl runs as an accessibility service with
 * `flagRequestFilterKeyEvents`, sees every key before the focused window, and by default spends
 * the camera key on opening the camera and the wheel click on the torch. Neither reaches an app
 * unless BrightControl's built-in table says that app owns the whole wheel — which is what Roll
 * and BrightRecorder get, and what this app now gets too. The wheel is the control that survives
 * a phone where that table is out of date, because its *turns* always come through.
 *
 * [Witness] answers the question that cost Roll days: did the click get here at all. A hold
 * that never starts has two causes with one symptom, and the readout tells them apart.
 */
class WheelTalk(
    private val wheel: WheelBus,
    private val onClick: () -> Unit,
    private val onHoldStart: () -> Unit,
    private val onHoldEnd: () -> Unit,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private var held = false

    /** Whether this press has already been spent — as a press-and-turn or as a hold. */
    private var spent = false

    private var holding = false

    private val becomeHold = Runnable {
        if (held && !spent) {
            spent = true
            holding = true
            onHoldStart()
        }
    }

    /** True if [event] was one of ours and has been dealt with. */
    fun dispatch(event: KeyEvent): Boolean {
        val key = LightKeys.of(event) ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        when (key) {
            LightKey.WheelClick -> {
                if (down) {
                    if (event.repeatCount == 0) {
                        Witness.seen()
                        held = true
                        spent = false
                        holding = false
                        handler.removeCallbacks(becomeHold)
                        handler.postDelayed(becomeHold, HOLD_MS)
                    }
                } else {
                    handler.removeCallbacks(becomeHold)
                    held = false
                    when {
                        holding -> {
                            holding = false
                            onHoldEnd()
                        }
                        !spent -> onClick()
                    }
                }
            }
            LightKey.WheelUp, LightKey.WheelDown -> {
                // One notch is a complete DOWN+UP pair, so act on DOWN and swallow the UP.
                if (!down) return true
                Witness.turned()
                if (held && !holding) {
                    // Press-and-turn. Not a click, not a hold.
                    spent = true
                    handler.removeCallbacks(becomeHold)
                }
                wheel.send(if (key == LightKey.WheelUp) 1 else -1, pressed = held)
            }
            // The camera button, for a phone whose BrightControl lets it through. Same gesture:
            // hold to talk, let go to send. Repeats cannot happen on these keys, but guard anyway.
            LightKey.Focus, LightKey.Camera -> {
                if (down && event.repeatCount > 0) return true
                if (key == LightKey.Focus) {
                    if (down && !holding) {
                        holding = true
                        onHoldStart()
                    } else if (!down && holding) {
                        holding = false
                        onHoldEnd()
                    }
                }
            }
        }
        return true
    }

    /** The activity is going away mid-press. */
    fun reset() {
        handler.removeCallbacks(becomeHold)
        held = false
        spent = false
        holding = false
    }

    /**
     * Whether a wheel click has ever reached this app since it opened, and how long ago.
     *
     * Roll's `WheelClickWitness`, kept because the diagnosis it enables is the same here: a hold
     * that does nothing is either a key swallowed upstream (BrightControl's per-app table) or a
     * key that arrived and did nothing, and from the phone the two are identical without this.
     */
    object Witness {
        @Volatile private var lastAt = 0L
        @Volatile private var turns = 0
        @Volatile private var since = SystemClock.elapsedRealtime()

        fun seen() { lastAt = SystemClock.elapsedRealtime() }

        fun turned() { turns++ }

        fun watchFrom() { since = SystemClock.elapsedRealtime(); lastAt = 0L; turns = 0 }

        fun secondsAgo(): Long? = lastAt.takeIf { it != 0L }?.let { (SystemClock.elapsedRealtime() - it) / 1000L }

        fun watchingForSeconds(): Long = (SystemClock.elapsedRealtime() - since) / 1000L

        /**
         * One line for the hint under the input, or null when there is nothing to say.
         *
         * Only when the *turns* are arriving and the click never has. That pattern has exactly one
         * cause — BrightControl's ScrollThrough rule, which passes turns and keeps the press for
         * the torch — so it can be named. A phone that has not touched the wheel at all says nothing.
         */
        fun warning(): String? {
            if (turns < 12 || secondsAgo() != null) return null
            return "Wheel turns reach this app but its click does not. In BrightControl, give BrightHermes the whole wheel."
        }
    }

    companion object {
        /** How long a press has to last, with no turn, before it is a hold. Under a human "click". */
        const val HOLD_MS = 320L
    }
}
