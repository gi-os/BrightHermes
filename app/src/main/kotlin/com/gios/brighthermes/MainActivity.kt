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
import com.gios.brighthermes.hw.WheelTalk
import com.gios.brighthermes.notify.Notifier
import com.gios.brighthermes.voice.Listener
import com.gios.light.common.hw.LocalWheelBus
import com.gios.light.common.hw.WheelBus
import com.gios.light.common.report.LightReport
import com.gios.light.common.report.ReportOverlay

/**
 * The activity. Hardware in, screens out.
 *
 * Every physical control goes through [WheelTalk]: turns scroll whatever list is on screen, a
 * click cycles the deck, and holding the wheel in is push-to-talk — release sends. The camera
 * button does the same where BrightControl lets it through. See `hw/WheelTalk.kt` for why the
 * wheel is the primary control and not the camera button.
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

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) Listener.warm(this)
    }

    private val controls = WheelTalk(
        wheel = wheel,
        onClick = { vm.cycleDeckMode() },
        onHoldStart = {
            if (Listener.hasPermission(this)) vm.pttDown() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        },
        onHoldEnd = {
            vm.pttCommit()
            vm.pttUp()
        },
    )

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
        WheelTalk.Witness.watchFrom()
        vm.foreground()
        if (vm.prefs.configured) {
            // Microphone for the wheel, notifications for the answers that land after you lock
            // the phone. One prompt for both, once.
            val wanted = buildList {
                if (!Listener.hasPermission(this@MainActivity)) add(Manifest.permission.RECORD_AUDIO)
                if (!Notifier.canPost(this@MainActivity)) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(screenOn) }
        vm.background()
        controls.reset()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        controls.dispatch(event) || super.dispatchKeyEvent(event)
}
