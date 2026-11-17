package com.aidexx.reader.alarm

import android.content.Context
import com.aidexx.reader.protocol.AidexReading

object GlucoseAlarmEvaluator {
    private const val PREFS =
        "aidexx_reader"

    private const val KEY_LOW_ACTIVE =
        "alarm_state_low_active"

    private const val KEY_HIGH_ACTIVE =
        "alarm_state_high_active"

    private const val KEY_ESCALATION = "alarm_high_escalation_level"

    private const val KEY_PENDING_ZONE =
        "alarm_fallback_pending_zone"

    private const val KEY_PENDING_MINUTE =
        "alarm_fallback_pending_minute"

    private const val KEY_PENDING_SERIAL =
        "alarm_fallback_pending_serial"

    private enum class Zone {
        LOW,
        NORMAL,
        HIGH,
        VERY_HIGH,
        DANGER
    }

    /**
     * Evaluates an AiDEX reading for High/Low notifications.
     *
     * CRC-valid readings are accepted immediately.
     *
     * The selected AiDEX advertisement decoder is still under validation and
     * has produced crc=false on otherwise plausible live packets. To avoid
     * losing screen-off safety notifications entirely, a crc=false reading is
     * used only after two consecutive sensor minutes agree on the same
     * High/Normal/Low zone. This fallback is intentionally more conservative
     * than the normal path and remains experimental.
     */
    fun evaluateReading(
        context: Context,
        reading: AidexReading,
        calibratedMgDl: Double
    ) {
        if (
            !reading.valid ||
            reading.isWarmup ||
            calibratedMgDl <=
            0.0
        ) {
            return
        }

        val mmol =
            calibratedMgDl /
                18.0

        if (
            reading.crcValid
        ) {
            clearPending(
                context
            )

            evaluateMmol(
                context =
                    context,
                mmol =
                    mmol
            )

            return
        }

        val prefs =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )

        val lowLimit =
            prefs
                .getString(
                    "low_glucose_mmol",
                    null
                )
                ?.toDoubleOrNull()
                ?: 3.9

        val highLimit =
            prefs
                .getString(
                    "high_glucose_mmol",
                    null
                )
                ?.toDoubleOrNull()
                ?: 10.0

        val zone =
            when {
                mmol <=
                    lowLimit ->
                    Zone.LOW

                mmol >= 20.0 -> Zone.DANGER

                mmol >= 15.0 -> Zone.VERY_HIGH

                mmol >=
                    highLimit ->
                    Zone.HIGH

                else ->
                    Zone.NORMAL
            }

        val priorSerial =
            prefs.getString(
                KEY_PENDING_SERIAL,
                null
            )

        val priorZone =
            prefs.getString(
                KEY_PENDING_ZONE,
                null
            )

        val priorMinute =
            prefs.getInt(
                KEY_PENDING_MINUTE,
                Int.MIN_VALUE
            )

        val confirmed =
            priorSerial
                .equals(
                    reading.serial,
                    ignoreCase =
                        true
                ) &&
                priorZone ==
                    zone.name &&
                reading.sensorMinute >
                    priorMinute &&
                reading.sensorMinute -
                    priorMinute <=
                    2

        prefs.edit()
            .putString(
                KEY_PENDING_SERIAL,
                reading.serial
            )
            .putString(
                KEY_PENDING_ZONE,
                zone.name
            )
            .putInt(
                KEY_PENDING_MINUTE,
                reading.sensorMinute
            )
            .apply()

        if (
            confirmed
        ) {
            evaluateMmol(
                context =
                    context,
                mmol =
                    mmol
            )
        }
    }

    fun evaluateMmol(
        context: Context,
        mmol: Double
    ) {
        val alarms =
            AlarmPreferences.load(
                context
            )

        val prefs =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )

        val lowLimit =
            prefs
                .getString(
                    "low_glucose_mmol",
                    null
                )
                ?.toDoubleOrNull()
                ?: 3.9

        val highLimit =
            prefs
                .getString(
                    "high_glucose_mmol",
                    null
                )
                ?.toDoubleOrNull()
                ?: 10.0

        var lowActive =
            prefs.getBoolean(
                KEY_LOW_ACTIVE,
                false
            )

        var highActive =
            prefs.getBoolean(
                KEY_HIGH_ACTIVE,
                false
            )

        /*
         * Android High/Low notifications are always active for the selected
         * sensor. Alarm settings control direct sound/vibration only.
         */
        if (
            mmol <=
            lowLimit
        ) {
            if (
                !lowActive
            ) {
                AlarmNotifications
                    .notifyLow(
                        context =
                            context,
                        mmol =
                            mmol,
                        limit =
                            lowLimit,
                        playSound =
                            alarms.lowEnabled
                    )

                lowActive =
                    true
            }
        } else if (
            lowActive
        ) {
            AlarmNotifications
                .clearLow(
                    context
                )

            lowActive =
                false
        }

        // Escalate independently when crossing 15 and 20 mmol/L, even if
        // the regular high alarm is already active. Never replay every minute.
        val severity = when {
            mmol >= 20.0 -> 3
            mmol >= 15.0 -> 2
            mmol >= highLimit -> 1
            else -> 0
        }
        val previousSeverity = prefs.getInt(KEY_ESCALATION, if(highActive) 1 else 0)
        if (severity > previousSeverity) {
            when(severity) {
                3 -> AlarmNotifications.notifyDanger(context, mmol, alarms.dangerEnabled)
                2 -> AlarmNotifications.notifyVeryHigh(context, mmol, alarms.veryHighEnabled)
                1 -> AlarmNotifications.notifyHigh(context, mmol, highLimit, alarms.highEnabled)
            }
        }
        if (severity == 0 && highActive) {
            AlarmNotifications.clearHigh(context)
            AlarmNotifications.clearVeryHigh(context)
            AlarmNotifications.clearDanger(context)
        } else if(severity < 3) {
            AlarmNotifications.clearDanger(context)
            if(severity < 2) AlarmNotifications.clearVeryHigh(context)
        }
        highActive = severity > 0

        prefs.edit()
            .putBoolean(
                KEY_LOW_ACTIVE,
                lowActive
            )
            .putBoolean(
                KEY_HIGH_ACTIVE,
                highActive
            )
            .putInt(KEY_ESCALATION, severity)
            .apply()
    }

    private fun clearPending(
        context: Context
    ) {
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
            .edit()
            .remove(
                KEY_PENDING_SERIAL
            )
            .remove(
                KEY_PENDING_ZONE
            )
            .remove(
                KEY_PENDING_MINUTE
            )
            .apply()
    }
}
