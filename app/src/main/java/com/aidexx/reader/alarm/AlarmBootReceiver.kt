package com.aidexx.reader.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aidexx.reader.backup.AidexBackupScheduler

class AlarmBootReceiver :
    BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        val action =
            intent.action
                ?: return

        if (
            action ==
                Intent.ACTION_BOOT_COMPLETED ||
            action ==
                Intent.ACTION_MY_PACKAGE_REPLACED ||
            action ==
                Intent.ACTION_USER_UNLOCKED
        ) {
            ContinuousMonitoringStatus
                .markBootEvent(
                    context =
                        context,
                    action =
                        action
                )

            AidexBackupScheduler.schedule(context)

            AlarmMonitorController.sync(
                context =
                    context,
                reason =
                    when (
                        action
                    ) {
                        Intent
                            .ACTION_BOOT_COMPLETED ->
                            "boot_completed"

                        Intent
                            .ACTION_MY_PACKAGE_REPLACED ->
                            "package_replaced"

                        Intent
                            .ACTION_USER_UNLOCKED ->
                            "user_unlocked"

                        else ->
                            "system_event"
                    }
            )
        }
    }
}
