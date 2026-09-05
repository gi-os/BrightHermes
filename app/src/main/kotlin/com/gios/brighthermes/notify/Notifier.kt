package com.gios.brighthermes.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gios.brighthermes.MainActivity
import com.gios.brighthermes.R

/**
 * The two notifications this app ever posts.
 *
 * One is a reply that finished while you were somewhere else — the whole answer, expanded, so
 * the phone never has to be opened to read it. The other is the quiet foreground notice
 * Android requires while [ReplyService] keeps the socket alive for a turn in flight; it says
 * who is thinking and nothing more, and it is gone the moment the answer lands.
 *
 * No sound, no vibration on either: LightOS is a phone people carry to be interrupted less,
 * and a reply you asked for is not news. The status-bar icon is enough.
 */
object Notifier {
    private const val CH_REPLIES = "replies"
    private const val CH_WORKING = "working"
    const val ID_WORKING = 1
    private const val ID_REPLY = 2

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_REPLIES, "Replies", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A reply that arrived while the app was closed"
                setSound(null, null)
                enableVibration(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WORKING, "Thinking", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown only while a reply is still on its way"
                setShowBadge(false)
            },
        )
    }

    fun canPost(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** The foreground notice. Low importance, ongoing, one line. */
    fun working(context: Context, who: String): Notification =
        NotificationCompat.Builder(context, CH_WORKING)
            .setSmallIcon(R.drawable.ic_stat_june)
            .setContentTitle("$who is thinking")
            .setContentText("You'll be told when the answer lands.")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open(context))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

    /** The answer itself. Replaces any earlier reply notification rather than stacking. */
    fun reply(context: Context, who: String, text: String) {
        if (!canPost(context)) return
        ensureChannels(context)
        val body = text.trim().ifBlank { "(no answer)" }
        val n = NotificationCompat.Builder(context, CH_REPLIES)
            .setSmallIcon(R.drawable.ic_stat_june)
            .setContentTitle(who)
            .setContentText(body.lineSequence().first().take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(4000)))
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(open(context))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_REPLY, n)
    }

    /** The app is in front again; anything it would tell you is on the screen. */
    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_REPLY)
    }

    private fun open(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
