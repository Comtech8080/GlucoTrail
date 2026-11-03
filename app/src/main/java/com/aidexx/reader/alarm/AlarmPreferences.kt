package com.aidexx.reader.alarm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.aidexx.reader.ble.AidexBackgroundScanManager
import com.aidexx.reader.data.HistoryAutoRecoveryStatus

enum class AlarmVoice(
    val displayName: String
) {
    MALE("Male"),
    FEMALE("Female")
}

data class AlarmSettings(
    val lowEnabled: Boolean = false,
    val highEnabled: Boolean = false,
    val disconnectedEnabled: Boolean = false,
    val disconnectedMinutes: Int = 5,
    val criticalOverrideEnabled: Boolean = false,
    val alarmVoice: AlarmVoice = AlarmVoice.MALE,
    val alarmTone: Int = 1
) {
    val anyEnabled: Boolean
        get() =
            lowEnabled ||
            highEnabled ||
            disconnectedEnabled
}

object AlarmPreferences {
    private const val PREFS = "aidexx_reader"

    private const val KEY_LOW_ENABLED =
        "alarm_low_enabled"
    private const val KEY_HIGH_ENABLED =
        "alarm_high_enabled"
    private const val KEY_DISCONNECTED_ENABLED =
        "alarm_disconnected_enabled"
    private const val KEY_DISCONNECTED_MINUTES =
        "alarm_disconnected_minutes"
    private const val KEY_CRITICAL_OVERRIDE =
        "alarm_critical_override"
    private const val KEY_ALARM_VOICE =
        "alarm_voice"
    private const val KEY_ALARM_TONE =
        "alarm_tone"

    fun load(
        context: Context
    ): AlarmSettings {
        val prefs =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )

        val voice =
            runCatching {
                AlarmVoice.valueOf(
                    prefs.getString(
                        KEY_ALARM_VOICE,
                        AlarmVoice.MALE.name
                    ) ?: AlarmVoice.MALE.name
                )
            }.getOrDefault(
                AlarmVoice.MALE
            )

        return AlarmSettings(
            lowEnabled =
                prefs.getBoolean(
                    KEY_LOW_ENABLED,
                    false
                ),
            highEnabled =
                prefs.getBoolean(
                    KEY_HIGH_ENABLED,
                    false
                ),
            disconnectedEnabled =
                prefs.getBoolean(
                    KEY_DISCONNECTED_ENABLED,
                    false
                ),
            disconnectedMinutes =
                prefs.getInt(
                    KEY_DISCONNECTED_MINUTES,
                    5
                ).coerceIn(2, 60),
            criticalOverrideEnabled =
                prefs.getBoolean(
                    KEY_CRITICAL_OVERRIDE,
                    false
                ),
            alarmVoice = voice,
            alarmTone =
                prefs.getInt(
                    KEY_ALARM_TONE,
                    1
                ).coerceIn(1, 10)
        )
    }

    fun save(
        context: Context,
        settings: AlarmSettings
    ) {
        context
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .edit()
            .putBoolean(
                KEY_LOW_ENABLED,
                settings.lowEnabled
            )
            .putBoolean(
                KEY_HIGH_ENABLED,
                settings.highEnabled
            )
            .putBoolean(
                KEY_DISCONNECTED_ENABLED,
                settings.disconnectedEnabled
            )
            .putInt(
                KEY_DISCONNECTED_MINUTES,
                settings.disconnectedMinutes
                    .coerceIn(2, 60)
            )
            .putBoolean(
                KEY_CRITICAL_OVERRIDE,
                settings.criticalOverrideEnabled
            )
            .putString(
                KEY_ALARM_VOICE,
                settings.alarmVoice.name
            )
            .putInt(
                KEY_ALARM_TONE,
                settings.alarmTone
                    .coerceIn(1, 10)
            )
            .apply()
    }
}

object AlarmMonitorController {
    private const val APP_PREFS =
        "aidexx_reader"

    fun requestHistoryRecovery(
        context: Context,
        reason: String =
            "ui_recent_gap"
    ) {
        val appContext =
            context.applicationContext

        val selectedSerial =
            appContext
                .getSharedPreferences(
                    APP_PREFS,
                    Context.MODE_PRIVATE
                )
                .getString(
                    "selected_sensor_serial",
                    null
                )

        if (
            selectedSerial.isNullOrBlank() ||
            !hasBluetoothPermissions(
                appContext
            )
        ) {
            return
        }

        HistoryAutoRecoveryStatus
            .requested(
                appContext,
                "Preparing recent history sync…"
            )

        val intent =
            Intent(
                appContext,
                AlarmMonitorService::class.java
            ).apply {
                action =
                    AlarmMonitorService
                        .ACTION_FORCE_HISTORY_RECOVERY

                putExtra(
                    AlarmMonitorService
                        .EXTRA_START_REASON,
                    reason
                )
            }

        runCatching {
            ContextCompat
                .startForegroundService(
                    appContext,
                    intent
                )
        }
            .onFailure {
                ContinuousMonitoringStatus
                    .markStartFailure(
                        appContext,
                        "${
                            it.javaClass
                                .simpleName
                        }: ${
                            it.message
                                ?: "History recovery request failed"
                        }"
                    )
            }
    }

    fun sync(
        context: Context,
        reason: String =
            "sync"
    ) {
        val appContext =
            context.applicationContext
        val selectedSerial =
            appContext
                .getSharedPreferences(
                    APP_PREFS,
                    Context.MODE_PRIVATE
                )
                .getString(
                    "selected_sensor_serial",
                    null
                )

        val shouldRun =
            !selectedSerial.isNullOrBlank() &&
                hasBluetoothPermissions(
                    appContext
                )

        val intent =
            Intent(
                appContext,
                AlarmMonitorService::class.java
            ).apply {
                putExtra(
                    AlarmMonitorService
                        .EXTRA_START_REASON,
                    reason
                )
            }

        if (shouldRun) {
            runCatching {
                ContextCompat
                    .startForegroundService(
                        appContext,
                        intent
                    )
            }
                .onFailure {
                    ContinuousMonitoringStatus
                        .markStartFailure(
                            appContext,
                            "${
                                it.javaClass
                                    .simpleName
                            }: ${
                                it.message
                                    ?: "Foreground service start failed"
                            }"
                        )
                }
        } else {
            AidexBackgroundScanManager
                .stop(
                    appContext
                )

            appContext.stopService(
                intent
            )

            ContinuousMonitoringStatus
                .markStopped(
                    appContext,
                    if (
                        selectedSerial
                            .isNullOrBlank()
                    ) {
                        "no_selected_sensor"
                    } else {
                        "bluetooth_permission_missing"
                    }
                )
        }
    }

    private fun hasBluetoothPermissions(
        context: Context
    ): Boolean {
        val locationGranted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission
                    .ACCESS_FINE_LOCATION
            ) ==
                PackageManager.PERMISSION_GRANTED

        return if (
            Build.VERSION.SDK_INT >= 31
        ) {
            val scanGranted =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission
                        .BLUETOOTH_SCAN
                ) ==
                    PackageManager.PERMISSION_GRANTED

            val connectGranted =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission
                        .BLUETOOTH_CONNECT
                ) ==
                    PackageManager.PERMISSION_GRANTED

            scanGranted &&
                connectGranted &&
                locationGranted
        } else {
            locationGranted
        }
    }
}
