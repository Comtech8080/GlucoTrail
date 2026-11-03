package com.aidexx.reader.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BackupAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!AidexBackupManager.automaticBackupEnabled(context)) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                AidexBackupManager.createBackup(
                    context.applicationContext,
                    manual = false
                )
            } finally {
                pending.finish()
            }
        }
    }
}
