package com.gios.brighthermes

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.brighthermes.ui.BrightHermesTheme
import com.gios.brighthermes.ui.EditDeckScreen
import com.gios.brighthermes.ui.HomeScreen
import com.gios.brighthermes.ui.SetupScreen
import com.gios.brighthermes.voice.Listener
import com.gios.light.common.hw.LightKey
import com.gios.light.common.hw.LightKeys
import com.gios.light.common.hw.LocalWheelBus
import com.gios.light.common.hw.WheelBus
import com.gios.light.common.report.LightReport
import com.gios.light.common.report.ReportOverlay

/**
 * The activity. Hardware in, screens out.
 *
 * Wheel turns go onto the [WheelBus] for whichever list is on screen; a wheel click toggles the
 * deck between strip and grid. The camera button is push-to-talk: first stage down starts
 * listening, second stage marks the take as one to send, first stage up ends it. All of that
 * arrives as ordinary key events because LightOS dispatches the buttons to the focused window
 * (see light-common's `LightKeys`), so there is no service and no permission behind it.
 *
 * Network only while in front: `onStart` opens the socket and refreshes the deck, `onStop`
 * closes everything. A screen-on while in front refreshes the deck again.
 */
class MainActivity : ComponentActivity() {

    private val vm: HermesViewModel by viewModels()
    private val wheel = WheelBus()

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) vm.screenOn()
        }
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Listener.warm(this)
    }

    /** True while the camera button's first stage is down, so a repeat does not restart the take. */
    private var focusHeld = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LightReport.install(context = this, appName = "BrightHermes", label = "hermes", token = BuildConfig.REPORT_TOKEN)

        setContent {
            BrightHermesTheme { type ->
                val configured by vm.configured.collectAsStateWithLifecycle()
                val editing by vm.editing.collectAsStateWithLifecycle()
                val deck by vm.deck.collectAsStateWithLifecycle()
                val bus = remember { wheel }
                CompositionLocalProvider(LocalWheelBus provides bus) {
                    when {
                        !configured -> SetupScreen(type, vm.prefs.server) { s, t -> vm.trySetup(s, t) }
                        editing -> EditDeckScreen(deck, type) { layout ->
                            vm.saveLayout(layout)
                            vm.setEditing(false)
                        }
                        else -> HomeScreen(vm, type)
                    }
                }
                ReportOverlay()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, screenOn, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
        vm.foreground()
        if (vm.prefs.configured && !Listener.hasPermission(this)) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(screenOn) }
        vm.background()
        focusHeld = false
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (LightKeys.of(event)) {
            LightKey.WheelUp -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(1)
                return true
            }
            LightKey.WheelDown -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(-1)
                return true
            }
            LightKey.WheelClick -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) vm.cycleDeckMode()
                return true
            }
            LightKey.Focus -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> if (!focusHeld) {
                        focusHeld = true
                        if (Listener.hasPermission(this)) vm.pttDown() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    KeyEvent.ACTION_UP -> {
                        focusHeld = false
                        vm.pttUp()
                    }
                }
                return true
            }
            LightKey.Camera -> {
                // The full press. Order against Focus is not guaranteed, so a Camera down that
                // arrives first also starts the take rather than being lost.
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (!focusHeld) {
                        focusHeld = true
                        if (Listener.hasPermission(this)) vm.pttDown()
                    }
                    vm.pttCommit()
                }
                return true
            }
            null -> Unit
        }
        return super.dispatchKeyEvent(event)
    }
}
