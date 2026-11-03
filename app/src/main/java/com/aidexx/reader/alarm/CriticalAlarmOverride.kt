package com.aidexx.reader.alarm

import android.content.Context

object CriticalAlarmOverride {
    fun play(
        context: Context
    ) {
        val settings =
            AlarmPreferences.load(
                context
            )

        AlarmSoundPlayer.play(
            context = context,
            alarmSound =
                AlarmSound.HIGH,
            alarmVoice =
                settings.alarmVoice,
            alarmTone =
                settings.alarmTone,
            overrideVolume = true
        )
    }

    fun stopAndRestore(
        context: Context
    ) {
        AlarmSoundPlayer.stop(
            context.applicationContext
        )
    }
}
