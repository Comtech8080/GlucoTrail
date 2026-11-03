package com.aidexx.reader.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.aidexx.reader.MainActivity
import com.aidexx.reader.GlucoseUnitPreferences

object AlarmNotifications {
    const val MONITOR_NOTIFICATION_ID = 7000
    const val LOW_NOTIFICATION_ID = 7001
    const val HIGH_NOTIFICATION_ID = 7002
    const val DISCONNECTED_NOTIFICATION_ID = 7003

    private const val CHANNEL_MONITOR = "aidexx_monitor"
    private const val CHANNEL_LOW = "aidexx_low_glucose_v3"
    private const val CHANNEL_HIGH = "aidexx_high_glucose_v3"
    private const val CHANNEL_DISCONNECTED = "aidexx_disconnected_v3"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java)
        val canBypassDnd =
            manager.isNotificationPolicyAccessGranted

        val monitor = NotificationChannel(
            CHANNEL_MONITOR,
            "GlucoTrail monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification while GlucoTrail monitors glucose and sensor status."
            setSound(null, null)
            enableVibration(false)
        }


        val low = NotificationChannel(
            CHANNEL_LOW,
            "Low glucose alarms",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Audible alarm when glucose is at or below the configured low limit."
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(canBypassDnd)
        }

        val high = NotificationChannel(
            CHANNEL_HIGH,
            "High glucose alarms",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Audible alarm when glucose is at or above the configured high limit."
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(canBypassDnd)
        }

        val disconnected = NotificationChannel(
            CHANNEL_DISCONNECTED,
            "Sensor disconnected alarms",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Audible alarm when the selected sensor stops reporting."
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(canBypassDnd)
        }

        manager.createNotificationChannels(
            listOf(monitor, low, high, disconnected)
        )
    }

    fun monitoringNotification(
        context: Context,
        serial: String
    ): Notification {
        return NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("GlucoTrail glucose monitoring")
            .setContentText("Monitoring sensor $serial")
            .setContentIntent(contentIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(
                NotificationCompat
                    .PRIORITY_LOW
            )
            .setCategory(
                NotificationCompat
                    .CATEGORY_SERVICE
            )
            .setForegroundServiceBehavior(
                NotificationCompat
                    .FOREGROUND_SERVICE_IMMEDIATE
            )
            .build()
    }

    fun notifyLow(
        context: Context,
        mmol: Double,
        limit: Double,
        playSound: Boolean
    ) {
        val unit = GlucoseUnitPreferences.load(context)
        postAlarm(
            context = context,
            channel = CHANNEL_LOW,
            notificationId = LOW_NOTIFICATION_ID,
            alarmSound = AlarmSound.LOW,
            title = "Low glucose",
            text = "${unit.formatGlucoseFromMmol(mmol)} is at or below your ${unit.formatGlucoseFromMmol(limit)} low limit.",
            playSound = playSound
        )
    }

    fun notifyHigh(
        context: Context,
        mmol: Double,
        limit: Double,
        playSound: Boolean
    ) {
        val unit = GlucoseUnitPreferences.load(context)
        postAlarm(
            context = context,
            channel = CHANNEL_HIGH,
            notificationId = HIGH_NOTIFICATION_ID,
            alarmSound = AlarmSound.HIGH,
            title = "High glucose",
            text = "${unit.formatGlucoseFromMmol(mmol)} is at or above your ${unit.formatGlucoseFromMmol(limit)} high limit.",
            playSound = playSound
        )
    }

    fun notifyDisconnected(
        context: Context,
        serial: String,
        minutes: Int
    ) {
        postAlarm(
            context = context,
            channel = CHANNEL_DISCONNECTED,
            notificationId = DISCONNECTED_NOTIFICATION_ID,
            alarmSound = AlarmSound.DISCONNECTED,
            title = "GlucoTrail sensor not reporting",
            text = "No reading from $serial for at least $minutes minutes.",
            playSound = true
        )
    }

    fun testLow(
        context: Context,
        limit: Double,
        criticalOverride: Boolean,
        alarmVoice: AlarmVoice,
        alarmTone: Int
    ) {
        createChannels(context)

        AlarmSoundPlayer.play(
            context = context,
            alarmSound = AlarmSound.LOW,
            alarmVoice = alarmVoice,
            alarmTone = alarmTone,
            overrideVolume = criticalOverride
        )

        val unit = GlucoseUnitPreferences.load(context)
        postAlarm(
            context = context,
            channel = CHANNEL_LOW,
            notificationId = LOW_NOTIFICATION_ID,
            alarmSound = AlarmSound.LOW,
            title = "TEST: Low glucose alarm",
            text = "This is a test of the low glucose alarm at ${unit.formatGlucoseFromMmol(limit)}.",
            playSound = false
        )
    }

    fun testHigh(
        context: Context,
        limit: Double,
        criticalOverride: Boolean,
        alarmVoice: AlarmVoice,
        alarmTone: Int
    ) {
        createChannels(context)

        AlarmSoundPlayer.play(
            context = context,
            alarmSound = AlarmSound.HIGH,
            alarmVoice = alarmVoice,
            alarmTone = alarmTone,
            overrideVolume = criticalOverride
        )

        val unit = GlucoseUnitPreferences.load(context)
        postAlarm(
            context = context,
            channel = CHANNEL_HIGH,
            notificationId = HIGH_NOTIFICATION_ID,
            alarmSound = AlarmSound.HIGH,
            title = "TEST: High glucose alarm",
            text = "This is a test of the high glucose alarm at ${unit.formatGlucoseFromMmol(limit)}.",
            playSound = false
        )
    }

    fun testDisconnected(
        context: Context,
        minutes: Int,
        criticalOverride: Boolean,
        alarmVoice: AlarmVoice,
        alarmTone: Int
    ) {
        createChannels(context)

        AlarmSoundPlayer.play(
            context = context,
            alarmSound = AlarmSound.DISCONNECTED,
            alarmVoice = alarmVoice,
            alarmTone = alarmTone,
            overrideVolume = criticalOverride
        )

        postAlarm(
            context = context,
            channel = CHANNEL_DISCONNECTED,
            notificationId = DISCONNECTED_NOTIFICATION_ID,
            alarmSound = AlarmSound.DISCONNECTED,
            title = "TEST: Sensor disconnected alarm",
            text = "This is a test of the sensor disconnected alarm after $minutes minutes.",
            playSound = false
        )
    }

    fun clearLow(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(LOW_NOTIFICATION_ID)
    }

    fun clearHigh(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(HIGH_NOTIFICATION_ID)
    }

    fun clearDisconnected(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(DISCONNECTED_NOTIFICATION_ID)
    }

    private fun postAlarm(
        context: Context,
        channel: String,
        notificationId: Int,
        alarmSound: AlarmSound,
        title: String,
        text: String,
        playSound: Boolean
    ) {
        createChannels(context)

        if (playSound) {
            val savedSettings =
                AlarmPreferences.load(
                    context
                )

            AlarmSoundPlayer.play(
                context = context,
                alarmSound = alarmSound,
                alarmVoice =
                    savedSettings.alarmVoice,
                alarmTone =
                    savedSettings.alarmTone,
                overrideVolume =
                    savedSettings
                        .criticalOverrideEnabled
            )
        }

        val dismissIntent =
            dismissPendingIntent(
                context = context,
                notificationId = notificationId
            )

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent(context))
            .setDeleteIntent(dismissIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Dismiss Alarm",
                dismissIntent
            )
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId, notification)
    }

    private fun dismissPendingIntent(
        context: Context,
        notificationId: Int
    ): PendingIntent {
        val intent =
            Intent(
                context,
                AlarmDismissReceiver::class.java
            ).apply {
                action =
                    AlarmDismissReceiver
                        .ACTION_DISMISS
                putExtra(
                    AlarmDismissReceiver
                        .EXTRA_NOTIFICATION_ID,
                    notificationId
                )
            }

        return PendingIntent.getBroadcast(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags =
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

}
