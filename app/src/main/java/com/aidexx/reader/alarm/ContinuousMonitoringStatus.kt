package com.aidexx.reader.alarm

import android.content.Context

data class ContinuousMonitoringSnapshot(
    val lastHeartbeatMs: Long,
    val lastStartedMs: Long,
    val lastStoppedMs: Long,
    val lastTaskRemovedMs: Long,
    val lastBootEventMs: Long,
    val lastBootAction: String?,
    val lastStartReason: String?,
    val lastSensorSerial: String?,
    val lastState: String?,
    val lastStartFailure: String?,
    val wakeLockHeld: Boolean,
    val scannerMode: String?,
    val backgroundScanActive: Boolean,
    val backgroundScanLastPacketMs: Long,
    val backgroundScanLastMinute: Int,
    val backgroundScanLastCrcValid: Boolean?,
    val backgroundScanLastError: String?,
    val liveGattConnected: Boolean,
    val liveGattStreaming: Boolean,
    val liveGattLastPacketMs: Long,
    val liveGattLastMinute: Int,
    val liveGattLastCrcValid: Boolean?,
    val liveGattReconnectCount: Int,
    val liveGattStage: String?,
    val liveGattProtocolStep: String?,
    val liveGattLastF002Response: Int,
    val liveGattLastF002PayloadHex: String?,
    val liveGattSensorActivationState: String?,
    val liveGattSensorStartYear: Int,
    val liveGattF003Properties: Int,
    val liveGattF003LocalNotifyEnabled: Boolean?,
    val liveGattF003CccdWriteStatus: Int,
    val liveGattF003CccdWriteCount: Int,
    val liveGattF003NotificationCount: Int,
    val liveGattF003LastRawAtMs: Long,
    val liveGattF003LastRawLength: Int,
    val liveGattF003LastSource: String?,
    val liveGattF003ReadSupported: Boolean,
    val liveGattF003ReadAttempts: Int,
    val liveGattF003LastReadStatus: Int,
    val liveGattMessage: String?,
    val liveGattLastError: String?
) {
    fun isHeartbeatFresh(
        nowMs: Long =
            System.currentTimeMillis()
    ): Boolean =
        lastHeartbeatMs > 0L &&
            nowMs -
                lastHeartbeatMs <=
            HEARTBEAT_STALE_MS

    companion object {
        private const val HEARTBEAT_STALE_MS =
            90_000L
    }
}

object ContinuousMonitoringStatus {
    private const val PREFS =
        "aidexx_reader"

    private const val KEY_HEARTBEAT =
        "monitor_service_heartbeat_ms"

    private const val KEY_STARTED =
        "monitor_service_started_ms"

    private const val KEY_STOPPED =
        "monitor_service_stopped_ms"

    private const val KEY_TASK_REMOVED =
        "monitor_task_removed_ms"

    private const val KEY_BOOT_EVENT =
        "monitor_boot_event_ms"

    private const val KEY_BOOT_ACTION =
        "monitor_boot_action"

    private const val KEY_START_REASON =
        "monitor_start_reason"

    private const val KEY_SENSOR_SERIAL =
        "monitor_sensor_serial"

    private const val KEY_STATE =
        "monitor_service_state"

    private const val KEY_START_FAILURE =
        "monitor_start_failure"

    private const val KEY_WAKE_LOCK_HELD =
        "monitor_wake_lock_held"

    private const val KEY_SCANNER_MODE =
        "monitor_scanner_mode"

    private const val KEY_BACKGROUND_SCAN_ACTIVE =
        "monitor_background_scan_active"

    private const val KEY_BACKGROUND_SCAN_PACKET_MS =
        "monitor_background_scan_packet_ms"

    private const val KEY_BACKGROUND_SCAN_MINUTE =
        "monitor_background_scan_minute"

    private const val KEY_BACKGROUND_SCAN_CRC =
        "monitor_background_scan_crc"

    private const val KEY_BACKGROUND_SCAN_CRC_KNOWN =
        "monitor_background_scan_crc_known"

    private const val KEY_BACKGROUND_SCAN_ERROR =
        "monitor_background_scan_error"

    private const val KEY_LIVE_GATT_CONNECTED =
        "monitor_live_gatt_connected"

    private const val KEY_LIVE_GATT_STREAMING =
        "monitor_live_gatt_streaming"

    private const val KEY_LIVE_GATT_PACKET_MS =
        "monitor_live_gatt_packet_ms"

    private const val KEY_LIVE_GATT_MINUTE =
        "monitor_live_gatt_minute"

    private const val KEY_LIVE_GATT_CRC =
        "monitor_live_gatt_crc"

    private const val KEY_LIVE_GATT_CRC_KNOWN =
        "monitor_live_gatt_crc_known"

    private const val KEY_LIVE_GATT_RECONNECTS =
        "monitor_live_gatt_reconnects"

    private const val KEY_LIVE_GATT_STAGE =
        "monitor_live_gatt_stage"

    private const val KEY_LIVE_GATT_PROTOCOL_STEP =
        "monitor_live_gatt_protocol_step"

    private const val KEY_LIVE_GATT_F002_RESPONSE =
        "monitor_live_gatt_f002_response"

    private const val KEY_LIVE_GATT_F002_PAYLOAD_HEX =
        "monitor_live_gatt_f002_payload_hex"

    private const val KEY_LIVE_GATT_SENSOR_ACTIVATION_STATE =
        "monitor_live_gatt_sensor_activation_state"

    private const val KEY_LIVE_GATT_SENSOR_START_YEAR =
        "monitor_live_gatt_sensor_start_year"

    private const val KEY_LIVE_GATT_F003_PROPERTIES =
        "monitor_live_gatt_f003_properties"

    private const val KEY_LIVE_GATT_F003_LOCAL_NOTIFY =
        "monitor_live_gatt_f003_local_notify"

    private const val KEY_LIVE_GATT_F003_LOCAL_NOTIFY_KNOWN =
        "monitor_live_gatt_f003_local_notify_known"

    private const val KEY_LIVE_GATT_F003_CCCD_STATUS =
        "monitor_live_gatt_f003_cccd_status"

    private const val KEY_LIVE_GATT_F003_CCCD_COUNT =
        "monitor_live_gatt_f003_cccd_count"

    private const val KEY_LIVE_GATT_F003_NOTIFY_COUNT =
        "monitor_live_gatt_f003_notify_count"

    private const val KEY_LIVE_GATT_F003_RAW_MS =
        "monitor_live_gatt_f003_raw_ms"

    private const val KEY_LIVE_GATT_F003_RAW_LENGTH =
        "monitor_live_gatt_f003_raw_length"

    private const val KEY_LIVE_GATT_F003_RAW_SOURCE =
        "monitor_live_gatt_f003_raw_source"

    private const val KEY_LIVE_GATT_F003_READ_SUPPORTED =
        "monitor_live_gatt_f003_read_supported"

    private const val KEY_LIVE_GATT_F003_READ_ATTEMPTS =
        "monitor_live_gatt_f003_read_attempts"

    private const val KEY_LIVE_GATT_F003_READ_STATUS =
        "monitor_live_gatt_f003_read_status"

    private const val KEY_LIVE_GATT_MESSAGE =
        "monitor_live_gatt_message"

    private const val KEY_LIVE_GATT_ERROR =
        "monitor_live_gatt_error"

    fun read(
        context: Context
    ): ContinuousMonitoringSnapshot {
        val prefs =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )

        return ContinuousMonitoringSnapshot(
            lastHeartbeatMs =
                prefs.getLong(
                    KEY_HEARTBEAT,
                    0L
                ),
            lastStartedMs =
                prefs.getLong(
                    KEY_STARTED,
                    0L
                ),
            lastStoppedMs =
                prefs.getLong(
                    KEY_STOPPED,
                    0L
                ),
            lastTaskRemovedMs =
                prefs.getLong(
                    KEY_TASK_REMOVED,
                    0L
                ),
            lastBootEventMs =
                prefs.getLong(
                    KEY_BOOT_EVENT,
                    0L
                ),
            lastBootAction =
                prefs.getString(
                    KEY_BOOT_ACTION,
                    null
                ),
            lastStartReason =
                prefs.getString(
                    KEY_START_REASON,
                    null
                ),
            lastSensorSerial =
                prefs.getString(
                    KEY_SENSOR_SERIAL,
                    null
                ),
            lastState =
                prefs.getString(
                    KEY_STATE,
                    null
                ),
            lastStartFailure =
                prefs.getString(
                    KEY_START_FAILURE,
                    null
                ),
            wakeLockHeld =
                prefs.getBoolean(
                    KEY_WAKE_LOCK_HELD,
                    false
                ),
            scannerMode =
                prefs.getString(
                    KEY_SCANNER_MODE,
                    null
                ),
            backgroundScanActive =
                prefs.getBoolean(
                    KEY_BACKGROUND_SCAN_ACTIVE,
                    false
                ),
            backgroundScanLastPacketMs =
                prefs.getLong(
                    KEY_BACKGROUND_SCAN_PACKET_MS,
                    0L
                ),
            backgroundScanLastMinute =
                prefs.getInt(
                    KEY_BACKGROUND_SCAN_MINUTE,
                    -1
                ),
            backgroundScanLastCrcValid =
                if (
                    prefs.getBoolean(
                        KEY_BACKGROUND_SCAN_CRC_KNOWN,
                        false
                    )
                ) {
                    prefs.getBoolean(
                        KEY_BACKGROUND_SCAN_CRC,
                        false
                    )
                } else {
                    null
                },
            backgroundScanLastError =
                prefs.getString(
                    KEY_BACKGROUND_SCAN_ERROR,
                    null
                ),
            liveGattConnected =
                prefs.getBoolean(
                    KEY_LIVE_GATT_CONNECTED,
                    false
                ),
            liveGattStreaming =
                prefs.getBoolean(
                    KEY_LIVE_GATT_STREAMING,
                    false
                ),
            liveGattLastPacketMs =
                prefs.getLong(
                    KEY_LIVE_GATT_PACKET_MS,
                    0L
                ),
            liveGattLastMinute =
                prefs.getInt(
                    KEY_LIVE_GATT_MINUTE,
                    -1
                ),
            liveGattLastCrcValid =
                if (
                    prefs.getBoolean(
                        KEY_LIVE_GATT_CRC_KNOWN,
                        false
                    )
                ) {
                    prefs.getBoolean(
                        KEY_LIVE_GATT_CRC,
                        false
                    )
                } else {
                    null
                },
            liveGattReconnectCount =
                prefs.getInt(
                    KEY_LIVE_GATT_RECONNECTS,
                    0
                ),
            liveGattStage =
                prefs.getString(
                    KEY_LIVE_GATT_STAGE,
                    null
                ),
            liveGattProtocolStep =
                prefs.getString(
                    KEY_LIVE_GATT_PROTOCOL_STEP,
                    null
                ),
            liveGattLastF002Response =
                prefs.getInt(
                    KEY_LIVE_GATT_F002_RESPONSE,
                    -1
                ),
            liveGattLastF002PayloadHex =
                prefs.getString(
                    KEY_LIVE_GATT_F002_PAYLOAD_HEX,
                    null
                ),
            liveGattSensorActivationState =
                prefs.getString(
                    KEY_LIVE_GATT_SENSOR_ACTIVATION_STATE,
                    null
                ),
            liveGattSensorStartYear =
                prefs.getInt(
                    KEY_LIVE_GATT_SENSOR_START_YEAR,
                    -1
                ),
            liveGattF003Properties =
                prefs.getInt(
                    KEY_LIVE_GATT_F003_PROPERTIES,
                    0
                ),
            liveGattF003LocalNotifyEnabled =
                if (
                    prefs.getBoolean(
                        KEY_LIVE_GATT_F003_LOCAL_NOTIFY_KNOWN,
                        false
                    )
                ) {
                    prefs.getBoolean(
                        KEY_LIVE_GATT_F003_LOCAL_NOTIFY,
                        false
                    )
                } else {
                    null
                },
            liveGattF003CccdWriteStatus =
                prefs.getInt(
                    KEY_LIVE_GATT_F003_CCCD_STATUS,
                    -1
                ),
            liveGattF003CccdWriteCount =
                prefs.getInt(
                    KEY_LIVE_GATT_F003_CCCD_COUNT,
                    0
                ),
            liveGattF003NotificationCount =
                prefs.getInt(
                    KEY_LIVE_GATT_F003_NOTIFY_COUNT,
                    0
                ),
            liveGattF003LastRawAtMs =
                prefs.getLong(
                    KEY_LIVE_GATT_F003_RAW_MS,
                    0L
                ),
            liveGattF003LastRawLength =
                prefs.getInt(
                    KEY_LIVE_GATT_F003_RAW_LENGTH,
                    -1
                ),
            liveGattF003LastSource =
                prefs.getString(
                    KEY_LIVE_GATT_F003_RAW_SOURCE,
                    null
                ),
            liveGattF003ReadSupported =
                prefs.getBoolean(
                    KEY_LIVE_GATT_F003_READ_SUPPORTED,
                    false
                ),
            liveGattF003ReadAttempts =
                prefs.getInt(
                    KEY_LIVE_GATT_F003_READ_ATTEMPTS,
                    0
                ),
            liveGattF003LastReadStatus =
                prefs.getInt(
                    KEY_LIVE_GATT_F003_READ_STATUS,
                    -1
                ),
            liveGattMessage =
                prefs.getString(
                    KEY_LIVE_GATT_MESSAGE,
                    null
                ),
            liveGattLastError =
                prefs.getString(
                    KEY_LIVE_GATT_ERROR,
                    null
                )
        )
    }

    fun markStarted(
        context: Context,
        serial: String,
        reason: String
    ) {
        val now =
            System.currentTimeMillis()

        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
            .edit()
            .putLong(
                KEY_STARTED,
                now
            )
            .putLong(
                KEY_HEARTBEAT,
                now
            )
            .putString(
                KEY_SENSOR_SERIAL,
                serial
            )
            .putString(
                KEY_START_REASON,
                reason
            )
            .putString(
                KEY_STATE,
                "running"
            )
            .remove(
                KEY_START_FAILURE
            )
            .apply()
    }

    fun heartbeat(
        context: Context,
        serial: String
    ) {
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
            .edit()
            .putLong(
                KEY_HEARTBEAT,
                System.currentTimeMillis()
            )
            .putString(
                KEY_SENSOR_SERIAL,
                serial
            )
            .putString(
                KEY_STATE,
                "running"
            )
            .apply()
    }

    fun markWakeLock(
        context: Context,
        held: Boolean
    ) {
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
            .edit()
            .putBoolean(
                KEY_WAKE_LOCK_HELD,
                held
            )
            .apply()
    }

    fun markScannerMode(
        context: Context,
        mode: String
    ) {
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
            .edit()
            .putString(
                KEY_SCANNER_MODE,
                mode
            )
            .apply()
    }

    fun markBackgroundScan(
        context: Context,
        active: Boolean,
        error: String?
    ) {
        val edit =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
                .edit()
                .putBoolean(
                    KEY_BACKGROUND_SCAN_ACTIVE,
                    active
                )

        if (
            error.isNullOrBlank()
        ) {
            edit.remove(
                KEY_BACKGROUND_SCAN_ERROR
            )
        } else {
            edit.putString(
                KEY_BACKGROUND_SCAN_ERROR,
                error
            )
        }

        edit.apply()
    }

    fun markBackgroundPacket(
        context: Context,
        serial: String,
        address: String,
        sensorMinute: Int,
        crcValid: Boolean
    ) {
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
            .edit()
            .putBoolean(
                KEY_BACKGROUND_SCAN_ACTIVE,
                true
            )
            .putLong(
                KEY_BACKGROUND_SCAN_PACKET_MS,
                System.currentTimeMillis()
            )
            .putInt(
                KEY_BACKGROUND_SCAN_MINUTE,
                sensorMinute
            )
            .putBoolean(
                KEY_BACKGROUND_SCAN_CRC,
                crcValid
            )
            .putBoolean(
                KEY_BACKGROUND_SCAN_CRC_KNOWN,
                true
            )
            .putString(
                KEY_SENSOR_SERIAL,
                serial
            )
            .putString(
                KEY_SCANNER_MODE,
                "Selected sensor only ($address) + Android wake scan"
            )
            .remove(
                KEY_BACKGROUND_SCAN_ERROR
            )
            .apply()
    }

    fun markLiveGatt(
        context: Context,
        connected: Boolean,
        streaming: Boolean,
        lastPacketAtMs: Long,
        sensorMinute: Int,
        crcValid: Boolean?,
        reconnectCount: Int,
        stage: String,
        protocolStep: String?,
        lastF002Response: Int,
        lastF002PayloadHex: String?,
        sensorActivationState: String?,
        sensorStartYear: Int,
        f003Properties: Int,
        f003LocalNotifyEnabled: Boolean?,
        f003CccdWriteStatus: Int,
        f003CccdWriteCount: Int,
        f003NotificationCount: Int,
        f003LastRawAtMs: Long,
        f003LastRawLength: Int,
        f003LastSource: String?,
        f003ReadSupported: Boolean,
        f003ReadAttempts: Int,
        f003LastReadStatus: Int,
        message: String,
        error: String?
    ) {
        val edit =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
                .edit()
                .putBoolean(
                    KEY_LIVE_GATT_CONNECTED,
                    connected
                )
                .putBoolean(
                    KEY_LIVE_GATT_STREAMING,
                    streaming
                )
                .putInt(
                    KEY_LIVE_GATT_RECONNECTS,
                    reconnectCount
                )
                .putString(
                    KEY_LIVE_GATT_STAGE,
                    stage
                )
                .putString(
                    KEY_LIVE_GATT_PROTOCOL_STEP,
                    protocolStep
                )
                .putInt(
                    KEY_LIVE_GATT_F003_PROPERTIES,
                    f003Properties
                )
                .putInt(
                    KEY_LIVE_GATT_F003_CCCD_STATUS,
                    f003CccdWriteStatus
                )
                .putInt(
                    KEY_LIVE_GATT_F003_CCCD_COUNT,
                    f003CccdWriteCount
                )
                .putInt(
                    KEY_LIVE_GATT_F003_NOTIFY_COUNT,
                    f003NotificationCount
                )
                .putLong(
                    KEY_LIVE_GATT_F003_RAW_MS,
                    f003LastRawAtMs
                )
                .putInt(
                    KEY_LIVE_GATT_F003_RAW_LENGTH,
                    f003LastRawLength
                )
                .putString(
                    KEY_LIVE_GATT_F003_RAW_SOURCE,
                    f003LastSource
                )
                .putBoolean(
                    KEY_LIVE_GATT_F003_READ_SUPPORTED,
                    f003ReadSupported
                )
                .putInt(
                    KEY_LIVE_GATT_F003_READ_ATTEMPTS,
                    f003ReadAttempts
                )
                .putInt(
                    KEY_LIVE_GATT_F003_READ_STATUS,
                    f003LastReadStatus
                )
                .putString(
                    KEY_LIVE_GATT_MESSAGE,
                    message
                )

        if (
            lastPacketAtMs >
            0L
        ) {
            edit.putLong(
                KEY_LIVE_GATT_PACKET_MS,
                lastPacketAtMs
            )
        } else {
            edit.remove(
                KEY_LIVE_GATT_PACKET_MS
            )
        }

        if (
            sensorMinute >=
            0
        ) {
            edit.putInt(
                KEY_LIVE_GATT_MINUTE,
                sensorMinute
            )
        } else {
            edit.remove(
                KEY_LIVE_GATT_MINUTE
            )
        }

        if (
            crcValid !=
            null
        ) {
            edit.putBoolean(
                KEY_LIVE_GATT_CRC,
                crcValid
            )
                .putBoolean(
                    KEY_LIVE_GATT_CRC_KNOWN,
                    true
                )
        } else {
            edit.remove(
                KEY_LIVE_GATT_CRC
            )
                .remove(
                    KEY_LIVE_GATT_CRC_KNOWN
                )
        }

        if (
            lastF002Response >=
            0
        ) {
            edit.putInt(
                KEY_LIVE_GATT_F002_RESPONSE,
                lastF002Response
            )
        } else {
            edit.remove(
                KEY_LIVE_GATT_F002_RESPONSE
            )
        }

        if (
            lastF002PayloadHex
                .isNullOrBlank()
        ) {
            edit.remove(
                KEY_LIVE_GATT_F002_PAYLOAD_HEX
            )
        } else {
            edit.putString(
                KEY_LIVE_GATT_F002_PAYLOAD_HEX,
                lastF002PayloadHex
            )
        }

        if (
            sensorActivationState
                .isNullOrBlank()
        ) {
            edit.remove(
                KEY_LIVE_GATT_SENSOR_ACTIVATION_STATE
            )
        } else {
            edit.putString(
                KEY_LIVE_GATT_SENSOR_ACTIVATION_STATE,
                sensorActivationState
            )
        }

        if (
            sensorStartYear >=
            0
        ) {
            edit.putInt(
                KEY_LIVE_GATT_SENSOR_START_YEAR,
                sensorStartYear
            )
        } else {
            edit.remove(
                KEY_LIVE_GATT_SENSOR_START_YEAR
            )
        }

        if (
            f003LocalNotifyEnabled !=
            null
        ) {
            edit.putBoolean(
                KEY_LIVE_GATT_F003_LOCAL_NOTIFY,
                f003LocalNotifyEnabled
            )
                .putBoolean(
                    KEY_LIVE_GATT_F003_LOCAL_NOTIFY_KNOWN,
                    true
                )
        } else {
            edit.remove(
                KEY_LIVE_GATT_F003_LOCAL_NOTIFY
            )
                .remove(
                    KEY_LIVE_GATT_F003_LOCAL_NOTIFY_KNOWN
                )
        }

        if (
            error.isNullOrBlank()
        ) {
            edit.remove(
                KEY_LIVE_GATT_ERROR
            )
        } else {
            edit.putString(
                KEY_LIVE_GATT_ERROR,
                error
            )
        }

        edit.apply()
    }

    fun markTaskRemoved(
        context: Context
    ) {
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
            .edit()
            .putLong(
                KEY_TASK_REMOVED,
                System.currentTimeMillis()
            )
            .putString(
                KEY_STATE,
                "running_after_recents_clear"
            )
            .apply()
    }

    fun markStopped(
        context: Context,
        reason: String
    ) {
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
            .edit()
            .putLong(
                KEY_STOPPED,
                System.currentTimeMillis()
            )
            .putString(
                KEY_STATE,
                reason
            )
            .apply()
    }

    fun markBootEvent(
        context: Context,
        action: String
    ) {
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
            .edit()
            .putLong(
                KEY_BOOT_EVENT,
                System.currentTimeMillis()
            )
            .putString(
                KEY_BOOT_ACTION,
                action
            )
            .apply()
    }

    fun markStartFailure(
        context: Context,
        message: String
    ) {
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
            .edit()
            .putString(
                KEY_START_FAILURE,
                message
            )
            .putString(
                KEY_STATE,
                "start_failed"
            )
            .apply()
    }
}
