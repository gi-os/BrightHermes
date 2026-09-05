package com.gios.brighthermes.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps the process alive while an answer is still on its way and the app is not in front.
 *
 * The rule everywhere else in this app is *no network when backgrounded*, and it stands: the
 * socket the view model holds is what this protects, for one turn, and it is torn down the moment
 * the turn ends. Without this a long answer — June running three tools for forty seconds — died
 * with the activity the moment you locked the phone, and you had to sit there watching. Now you
 * lock it, and the answer arrives as a notification.
 *
 * The service itself does nothing. It is the foreground notice Android requires in exchange for
 * not killing the process, and a hard stop [MAX_MS] later so a turn that never ends cannot
 * pin the phone awake for the night. All the work is in `HermesViewModel`, which starts and stops
 * this from `background()` / `foreground()` and from the frame that ends the turn.
 *
 * `remoteMessaging` rather than `dataSync`, as in BrightChat: it is the semantically right type
 * for "waiting on a message from a server", and it is not one of the types Android 14+ puts a
 * running-time cap on.
 */
class ReplyService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.ensureChannels(this)
        val who = intent?.getStringExtra(EXTRA_WHO) ?: "June"
        startForeground(Notifier.ID_WORKING, Notifier.working(this, who), ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        // Belt and braces: whoever started this is expected to stop it, and if they forget, the
        // process still is not kept up for ever.
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopSelf() }, MAX_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        private const val EXTRA_WHO = "who"
        const val MAX_MS = 10 * 60 * 1000L

        fun start(context: Context, who: String) {
            runCatching {
                context.startForegroundService(Intent(context, ReplyService::class.java).putExtra(EXTRA_WHO, who))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ReplyService::class.java)) }
        }
    }
}
