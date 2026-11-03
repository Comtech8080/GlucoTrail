package com.aidexx.reader.alarm

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Stops the currently playing AiDEX alarm and dismisses its notification.
 *
 * The glucose excursion remains latched by AlarmMonitorService, so dismissing
 * an alarm silences the current excursion. It will re-arm only after glucose
 * returns inside the configured limit and crosses the threshold again.
 */
class AlarmDismissReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        AlarmSoundPlayer.stop(
            context.applicationContext
        )

        val notificationId =
            intent.getIntExtra(
                EXTRA_NOTIFICATION_ID,
                -1
            )

        if (notificationId >= 0) {
            context.getSystemService(
                NotificationManager::class.java
            ).cancel(notificationId)
        }
    }

    companion object {
        const val ACTION_DISMISS =
            "com.aidexx.reader.action.DISMISS_ALARM"

        const val EXTRA_NOTIFICATION_ID =
            "notification_id"
    }
}
