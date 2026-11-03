package com.aidexx.reader.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object AidexBackupScheduler {
    private const val REQUEST_CODE = 44591
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun schedule(context: Context) {
        if (!AidexBackupManager.automaticBackupEnabled(context)) return
        if (AidexBackupManager.treeUri(context) == null) return
        if (!BackupPassphraseStore.hasPassphrase(context)) return

        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context)
        val next = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 3)
            set(Calendar.MINUTE, 15)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        alarm.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            next,
            DAY_MS,
            pending
        )
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)
            ?.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BackupAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
