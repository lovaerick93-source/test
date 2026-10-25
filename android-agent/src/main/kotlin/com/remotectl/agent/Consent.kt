package com.remotectl.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

/**
 * The phone owner's decision about an incoming control request.
 *
 * A request is always surfaced two ways: a high-importance notification with
 * Accept / Decline buttons, which works even when the agent's own screen is
 * closed, and an in-app dialog when it happens to be open. The phone can never
 * be controlled until one of those is answered -- the hub refuses to pair until
 * the phone replies.
 */
object Consent {

    const val ACTION_ACCEPT = "com.remotectl.agent.CONSENT_ACCEPT"
    const val ACTION_DECLINE = "com.remotectl.agent.CONSENT_DECLINE"

    private const val CHANNEL_ID = "consent"
    private const val NOTIFICATION_ID = 4712
    private const val RC_ACCEPT = 21
    private const val RC_DECLINE = 22
    private const val RC_OPEN = 23

    /**
     * Set by MainActivity while it is on screen and cleared in onPause, so a
     * stale Activity is never asked to show a dialog. Invoked on whatever
     * thread the hub reader runs on; the listener hops to the UI thread itself.
     */
    @Volatile
    var dialogListener: ((String) -> Unit)? = null

    fun post(context: Context, controllerId: String) {
        dialogListener?.invoke(controllerId)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(context, nm)
        nm.notify(NOTIFICATION_ID, build(context))
    }

    fun dismiss(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    private fun flags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun build(context: Context): Notification {
        val accept = PendingIntent.getForegroundService(
            context, RC_ACCEPT,
            Intent(context, AgentService::class.java).setAction(ACTION_ACCEPT),
            flags(),
        )
        val decline = PendingIntent.getForegroundService(
            context, RC_DECLINE,
            Intent(context, AgentService::class.java).setAction(ACTION_DECLINE),
            flags(),
        )
        val open = PendingIntent.getActivity(
            context, RC_OPEN, Intent(context, MainActivity::class.java), flags(),
        )
        val text = context.getString(R.string.consent_notification_text)
        val icon = Icon.createWithResource(context, R.drawable.ic_stat_agent)

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(context.getString(R.string.consent_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(
                Notification.Action.Builder(
                    icon, context.getString(R.string.consent_accept), accept,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    icon, context.getString(R.string.consent_decline), decline,
                ).build(),
            )
            .build()
    }

    private fun ensureChannel(context: Context, nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.consent_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.consent_channel_description)
            },
        )
    }
}
