package com.aidexx.reader.alarm

import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.aidexx.reader.ble.AidexBackgroundScanManager
import com.aidexx.reader.ble.AidexBleScanner
import com.aidexx.reader.ble.AidexHistoryBackfillClient
import com.aidexx.reader.ble.AidexLiveGattClient
import com.aidexx.reader.ble.LiveGattStage
import com.aidexx.reader.ble.HistoryBackfillStage
import com.aidexx.reader.data.AidexHistoryDatabase
import com.aidexx.reader.data.CalibrationTimeline
import com.aidexx.reader.data.HistoryAutoRecoveryStatus
import com.aidexx.reader.protocol.AidexReading
import com.aidexx.reader.protocol.AidexSensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AlarmMonitorService : Service() {
    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var scanner:
        AidexBleScanner

    private lateinit var historyDb:
        AidexHistoryDatabase

    private lateinit var historyBackfill:
        AidexHistoryBackfillClient

    private lateinit var liveGatt:
        AidexLiveGattClient

    private var monitorWakeLock:
        PowerManager.WakeLock? =
        null

    private var selectedBleAddress:
        String? =
        null

    private var sensorJob: Job? =
        null

    private var disconnectedJob: Job? =
        null

    private var watchdogJob: Job? =
        null

    private var backfillStateJob: Job? =
        null

    private var liveGattStateJob: Job? =
        null

    private var historyRetryJob: Job? =
        null

    @Volatile
    private var lastReadingAtMs: Long =
        0L

    @Volatile
    private var lastGapCheckAtMs: Long =
        0L

    @Volatile
    private var lastPacketSeenAtMs: Long =
        0L

    @Volatile
    private var lastForcedRecoveryAtMs: Long =
        0L

    @Volatile
    private var postWarmupRecoveryActive =
        false

    @Volatile
    private var lastPostWarmupRecoveryAtMs: Long =
        0L

    @Volatile
    private var scannerPausedForHistory =
        false

    @Volatile
    private var recentRecoveryInFlight =
        false

    private var selectedSerial: String =
        ""

    override fun onCreate() {
        super.onCreate()
        AlarmNotifications.createChannels(this)
        scanner =
            AidexBleScanner(
                applicationContext
            )

        historyDb =
            AidexHistoryDatabase(
                applicationContext
            )

        historyBackfill =
            AidexHistoryBackfillClient(
                applicationContext
            )

        liveGatt =
            AidexLiveGattClient(
                applicationContext
            ) {
                    reading ->
                serviceScope.launch(
                    Dispatchers.IO
                ) {
                    handleLiveGattReading(
                        reading
                    )
                }
            }

        liveGattStateJob =
            serviceScope.launch {
                liveGatt
                    .state
                    .collect {
                            state ->
                        ContinuousMonitoringStatus
                            .markLiveGatt(
                                context =
                                    applicationContext,
                                connected =
                                    state.connected,
                                streaming =
                                    state.streaming,
                                lastPacketAtMs =
                                    state.lastPacketAtMs,
                                sensorMinute =
                                    state.lastSensorMinute,
                                crcValid =
                                    state.lastCrcValid,
                                reconnectCount =
                                    state.reconnectCount,
                                stage =
                                    state.stage.name,
                                protocolStep =
                                    state.protocolStep,
                                lastF002Response =
                                    state.lastF002Response,
                                lastF002PayloadHex =
                                    state.lastF002PayloadHex,
                                sensorActivationState =
                                    state.sensorActivationState,
                                sensorStartYear =
                                    state.sensorStartYear,
                                f003Properties =
                                    state.f003Properties,
                                f003LocalNotifyEnabled =
                                    state.f003LocalNotifyEnabled,
                                f003CccdWriteStatus =
                                    state.f003CccdWriteStatus,
                                f003CccdWriteCount =
                                    state.f003CccdWriteCount,
                                f003NotificationCount =
                                    state.f003NotificationCount,
                                f003LastRawAtMs =
                                    state.f003LastRawAtMs,
                                f003LastRawLength =
                                    state.f003LastRawLength,
                                f003LastSource =
                                    state.f003LastSource,
                                f003ReadSupported =
                                    state.f003ReadSupported,
                                f003ReadAttempts =
                                    state.f003ReadAttempts,
                                f003LastReadStatus =
                                    state.f003LastReadStatus,
                                message =
                                    state.message,
                                error =
                                    state.error
                            )

                        if (
                            state.streaming &&
                            state.lastPacketAtMs >
                            0L
                        ) {
                            lastPacketSeenAtMs =
                                state.lastPacketAtMs
                        }
                    }
            }

        backfillStateJob =
            serviceScope.launch {
                historyBackfill
                    .state
                    .collect {
                            state ->
                        when (
                            state.stage
                        ) {
                            HistoryBackfillStage.CONNECTING ->
                                HistoryAutoRecoveryStatus
                                    .progress(
                                        applicationContext,
                                        "Connecting to F002 history…"
                                    )

                            HistoryBackfillStage.DISCOVERING ->
                                HistoryAutoRecoveryStatus
                                    .progress(
                                        applicationContext,
                                        "Connected • discovering F002 history…"
                                    )

                            HistoryBackfillStage.CONFIGURING ->
                                HistoryAutoRecoveryStatus
                                    .progress(
                                        applicationContext,
                                        "Configuring encrypted history channel…"
                                    )

                            HistoryBackfillStage.REQUESTING ->
                                HistoryAutoRecoveryStatus
                                    .progress(
                                        applicationContext,
                                        state.message
                                    )

                            HistoryBackfillStage.RECEIVING ->
                                HistoryAutoRecoveryStatus
                                    .progress(
                                        applicationContext,
                                        state.message
                                    )

                            HistoryBackfillStage.COMPLETE -> {
                                resumeScannerAfterHistory()
                            }

                            HistoryBackfillStage.FAILED -> {
                                val retryRecent =
                                    recentRecoveryInFlight

                                recentRecoveryInFlight =
                                    false

                                resumeScannerAfterHistory()

                                HistoryAutoRecoveryStatus
                                    .failed(
                                        applicationContext,
                                        state.error
                                            ?: state.message
                                    )

                                if (
                                    retryRecent
                                ) {
                                    scheduleRecentHistoryRetry(
                                        state.error
                                            ?: state.message
                                    )
                                }
                            }

                            HistoryBackfillStage.IDLE ->
                                Unit
                        }
                    }
            }

        HistoryAutoRecoveryStatus
            .initialize(
                applicationContext
            )

        ContinuousMonitoringStatus
            .heartbeat(
                this,
                selectedSerial
            )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        val alarms =
            AlarmPreferences.load(
                this
            )

        val previousSerial =
            selectedSerial

        val requestedSerial =
            getSharedPreferences(
                APP_PREFS,
                Context.MODE_PRIVATE
            )
                .getString(
                    "selected_sensor_serial",
                    null
                )
                ?.trim()
                ?.uppercase()
                .orEmpty()

        selectedSerial =
            requestedSerial

        val serialChanged =
            previousSerial !=
                selectedSerial

        if (
            selectedSerial.isBlank()
        ) {
            ContinuousMonitoringStatus
                .markStopped(
                    this,
                    "no_selected_sensor"
                )

            liveGatt.stop()

            AidexBackgroundScanManager
                .stop(
                    applicationContext
                )

            releaseMonitoringWakeLock()

            stopSelf()

            return START_NOT_STICKY
        }

        val startReason =
            intent
                ?.getStringExtra(
                    EXTRA_START_REASON
                )
                ?: if (
                    intent == null
                ) {
                    "android_sticky_restart"
                } else {
                    "service_start"
                }

        startForeground(
            AlarmNotifications
                .MONITOR_NOTIFICATION_ID,
            AlarmNotifications
                .monitoringNotification(
                    this,
                    selectedSerial
                )
        )

        acquireMonitoringWakeLock()

        ContinuousMonitoringStatus
            .markStarted(
                context =
                    this,
                serial =
                    selectedSerial,
                reason =
                    startReason
            )

        val needsMonitoringRestart =
            serialChanged ||
                sensorJob
                    ?.isActive !=
                true ||
                watchdogJob
                    ?.isActive !=
                true

        if (
            needsMonitoringRestart
        ) {
            restartMonitoring()
        }

        if (
            intent?.action ==
            ACTION_FORCE_HISTORY_RECOVERY
        ) {
            serviceScope.launch(
                Dispatchers.IO
            ) {
                if (
                    needsMonitoringRestart
                ) {
                    delay(
                        500L
                    )
                }

                forceRecentHistoryRecovery()
            }
        }

        /*
         * START_STICKY asks Android to recreate this long-running foreground
         * service after process reclamation when resources become available.
         */
        return START_STICKY
    }

    private fun restartMonitoring() {
        sensorJob?.cancel()
        disconnectedJob?.cancel()
        watchdogJob?.cancel()
        scanner.stop()

        postWarmupRecoveryActive =
            false

        lastPostWarmupRecoveryAtMs =
            0L

        val now = System.currentTimeMillis()
        val latest = historyDb.loadLatestReading(selectedSerial)

        /*
         * Start a fresh disconnected-alarm window whenever the monitor itself
         * starts so an app update/reboot does not immediately alarm on old data.
         */
        lastReadingAtMs = maxOf(
            now,
            latest?.receivedAtMs ?: 0L
        )

        lastPacketSeenAtMs =
            latest
                ?.receivedAtMs
                ?: now

        selectedBleAddress =
            historyDb
                .loadSavedSensors()
                .firstOrNull {
                    it.serial.equals(
                        selectedSerial,
                        ignoreCase =
                            true
                    )
                }
                ?.address

        startLiveGattMonitor()

        /*
         * Advertisement scanning remains a fallback. The direct connected
         * F003 stream is now the primary current-glucose transport because it
         * remains a connected-device workload while the phone is asleep.
         */
        sensorJob = serviceScope.launch {
            scanner.sensors.collect { sensors ->
                val sensor = sensors.firstOrNull {
                    it.serial.equals(
                        selectedSerial,
                        ignoreCase = true
                    )
                } ?: return@collect

                val previousAddress =
                    selectedBleAddress

                selectedBleAddress =
                    sensor.address

                historyDb.saveSensor(
                    sensor
                )

                if (
                    postWarmupRecoveryActive &&
                    !previousAddress.equals(
                        sensor.address,
                        ignoreCase =
                            true
                    )
                ) {
                    liveGatt.start(
                        serial =
                            selectedSerial,
                        address =
                            sensor.address
                    )

                    ensureBackgroundWakeScan()

                    ContinuousMonitoringStatus
                        .markScannerMode(
                            applicationContext,
                            "Post-warm-up recovery • rediscovered $selectedSerial at ${sensor.address} • reconnecting F003"
                        )
                }

                val reading =
                    sensor.reading
                        ?: return@collect

                lastPacketSeenAtMs =
                    System.currentTimeMillis()

                /*
                 * F002 backfill has its own encrypted integrity/CRC checks, so
                 * it may safely use the newest sensor minute as an anchor even
                 * while the advertisement CRC issue is being diagnosed.
                 */
                maybeAutomaticallyRecoverHistory(
                    sensor =
                        sensor,
                    reading =
                        reading
                )

                if (
                    !reading.valid ||
                    reading.isWarmup
                ) {
                    return@collect
                }

                lastReadingAtMs =
                    System.currentTimeMillis()

                postWarmupRecoveryActive =
                    false

                clearDisconnectedState()

                val calibratedMgDl =
                    CalibrationTimeline
                        .applyMgDl(
                            rawMgDl =
                                reading
                                    .glucoseMgDl
                                    .toDouble(),
                            receivedAtMs =
                                reading
                                    .receivedAtMs,
                            events =
                                historyDb
                                    .loadCalibrations(
                                        selectedSerial
                                    )
                        )

                GlucoseAlarmEvaluator
                    .evaluateReading(
                        context =
                            this@AlarmMonitorService,
                        reading =
                            reading,
                        calibratedMgDl =
                            calibratedMgDl
                    )
            }
        }

        disconnectedJob =
            serviceScope.launch {
                while (isActive) {
                    delay(
                        DISCONNECT_CHECK_MS
                    )

                    evaluateDisconnected()
                }
            }

        watchdogJob =
            serviceScope.launch {
                while (isActive) {
                    ContinuousMonitoringStatus
                        .heartbeat(
                            applicationContext,
                            selectedSerial
                        )

                    /*
                     * BLE can stop if Bluetooth is toggled, the Android scanner
                     * reports an error, or the stack is temporarily unavailable.
                     * start() is idempotent while scanning and retries safely
                     * after those conditions recover.
                     */
                    val now =
                        System.currentTimeMillis()

                    val liveState =
                        liveGatt
                            .state
                            .value

                    val latestSelectedReading =
                        historyDb
                            .loadLatestReading(
                                selectedSerial
                            )

                    val warmupComplete =
                        selectedSensorWarmupComplete(
                            nowMs =
                                now,
                            latestReading =
                                latestSelectedReading
                        )

                    val freshUsableGlucose =
                        latestSelectedReading
                            ?.let {
                                    reading ->
                                reading.valid &&
                                    !reading.isWarmup &&
                                    now -
                                        reading.receivedAtMs <=
                                    FRESH_GLUCOSE_WINDOW_MS
                            }
                            ?: false

                    val postWarmupNeedsReading =
                        warmupComplete &&
                            !freshUsableGlucose

                    val liveHandshakeActive =
                        isLiveGattAttemptActive(
                            liveState.stage
                        )

                    val streamingPacketStale =
                        liveState.streaming &&
                            (
                                liveState.lastPacketAtMs <=
                                    0L ||
                                    now -
                                        liveState.lastPacketAtMs >
                                    LIVE_GATT_PACKET_STALE_MS
                            )

                    if (
                        !scannerPausedForHistory &&
                        postWarmupNeedsReading
                    ) {
                        if (
                            !postWarmupRecoveryActive
                        ) {
                            postWarmupRecoveryActive =
                                true

                            /*
                             * The saved address is normally correct, but a full
                             * rediscovery gives Android a clean path back to a
                             * replacement sensor and lets us notice a changed
                             * random BLE address.
                             */
                            scanner.stop()
                            scanner.start()
                        } else if (
                            !scanner
                                .status
                                .value
                                .running
                        ) {
                            scanner.start()
                        }

                        ensureBackgroundWakeScan()

                        val mayNudgeGatt =
                            now -
                                lastPostWarmupRecoveryAtMs >=
                            POST_WARMUP_GATT_NUDGE_INTERVAL_MS

                        if (
                            mayNudgeGatt &&
                            !liveHandshakeActive
                        ) {
                            val address =
                                selectedBleAddress

                            if (
                                address.isNullOrBlank()
                            ) {
                                scanner.stop()
                                scanner.start()
                            } else if (
                                streamingPacketStale
                            ) {
                                liveGatt.restart()
                            } else {
                                liveGatt.start(
                                    serial =
                                        selectedSerial,
                                    address =
                                        address
                                )
                            }

                            lastPostWarmupRecoveryAtMs =
                                now
                        }

                        ContinuousMonitoringStatus
                            .markScannerMode(
                                applicationContext,
                                "Post-warm-up recovery • rediscovering $selectedSerial + secure F003 reconnect"
                            )

                        ContinuousMonitoringStatus
                            .markStarted(
                                context =
                                    applicationContext,
                                serial =
                                    selectedSerial,
                                reason =
                                    "post_warmup_recovery"
                            )
                    } else if (
                        !scannerPausedForHistory &&
                        streamingPacketStale
                    ) {
                        /*
                         * Only restart a stale connection that had already
                         * reached STREAMING. Do not tear down CONNECTING,
                         * INITIALIZING or WAITING_FOR_CURRENT every watchdog
                         * cycle; AidexLiveGattClient owns those timeouts.
                         */
                        liveGatt.restart()

                        if (
                            !scanner
                                .status
                                .value
                                .running
                        ) {
                            startSelectedSensorScanner()
                        }

                        ContinuousMonitoringStatus
                            .markStarted(
                                context =
                                    applicationContext,
                                serial =
                                    selectedSerial,
                                reason =
                                    "live_gatt_stale_reconnect"
                            )
                    } else if (
                        !scannerPausedForHistory &&
                        !liveState.streaming &&
                        !liveHandshakeActive
                    ) {
                        startLiveGattMonitor()

                        if (
                            !scanner
                                .status
                                .value
                                .running
                        ) {
                            startSelectedSensorScanner()
                        }
                    } else if (
                        !scannerPausedForHistory &&
                        liveState.streaming
                    ) {
                        postWarmupRecoveryActive =
                            false

                        /*
                         * Once the direct F003 stream is healthy, stop the
                         * active callback scanner. The PendingIntent safety scan
                         * stays registered as a secondary OS-delivered path.
                         */
                        if (
                            scanner
                                .status
                                .value
                                .running
                        ) {
                            scanner.stop()
                        }

                        ensureBackgroundWakeScan()
                    }

                    if (
                        monitorWakeLock
                            ?.isHeld !=
                        true
                    ) {
                        acquireMonitoringWakeLock()
                    }

                    delay(
                        WATCHDOG_INTERVAL_MS
                    )
                }
            }
    }

    private suspend fun maybeAutomaticallyRecoverHistory(
        sensor: AidexSensor,
        reading: AidexReading,
        force: Boolean =
            false,
        recoveryWindowMinutes: Int =
            AUTO_RECOVERY_WINDOW_MINUTES,
        requestedHours: Int =
            72,
        recentRecovery: Boolean =
            false
    ) {
        if (
            !reading.valid ||
            reading.isWarmup ||
            reading.sensorMinute <
            FIRST_RECOVERABLE_SENSOR_MINUTE
        ) {
            return
        }

        val now =
            System.currentTimeMillis()

        if (
            !force &&
            now -
                lastGapCheckAtMs <
            AUTO_GAP_CHECK_INTERVAL_MS
        ) {
            return
        }

        lastGapCheckAtMs =
            now

        val summary =
            historyDb
                .findRecoverableGaps(
                    serial =
                        selectedSerial,
                    currentSensorMinute =
                        reading.sensorMinute,
                    windowMinutes =
                        recoveryWindowMinutes,
                    graceMinutes =
                        AUTO_RECOVERY_GRACE_MINUTES,
                    minimumGapMinutes =
                        AUTO_RECOVERY_MIN_GAP_MINUTES
                )

        if (
            summary.gaps.isEmpty()
        ) {
            HistoryAutoRecoveryStatus
                .checked(
                    context =
                        applicationContext,
                    summary =
                        summary,
                    message =
                        "History is caught up. No recoverable gaps in the last 72h."
                )
            return
        }

        val stage =
            historyBackfill
                .state
                .value
                .stage

        if (
            stage in
            ACTIVE_BACKFILL_STAGES
        ) {
            HistoryAutoRecoveryStatus
                .progress(
                    applicationContext,
                    "F002 history recovery is already running…"
                )
            return
        }

        val prefs =
            getSharedPreferences(
                APP_PREFS,
                Context.MODE_PRIVATE
            )

        val lastAttempt =
            prefs.getLong(
                autoAttemptKey(
                    selectedSerial
                ),
                0L
            )

        if (
            !force &&
            now -
                lastAttempt <
            AUTO_RECOVERY_RETRY_COOLDOWN_MS
        ) {
            HistoryAutoRecoveryStatus
                .checked(
                    context =
                        applicationContext,
                    summary =
                        summary,
                    message =
                        "Missing history detected. Waiting for the automatic recovery cooldown."
                )
            return
        }

        val unresolvedSignature =
            prefs.getString(
                autoUnresolvedSignatureKey(
                    selectedSerial
                ),
                null
            )

        val unresolvedAt =
            prefs.getLong(
                autoUnresolvedAtKey(
                    selectedSerial
                ),
                0L
            )

        if (
            !force &&
            unresolvedSignature ==
                summary.signature &&
            now -
                unresolvedAt <
            AUTO_UNRESOLVED_SUPPRESSION_MS
        ) {
            HistoryAutoRecoveryStatus
                .checked(
                    context =
                        applicationContext,
                    summary =
                        summary,
                    message =
                        "The same gap was already requested from the sensor and remained unavailable. Automatic retry is temporarily suppressed."
                )
            return
        }

        val adapter =
            getSystemService(
                BluetoothManager::class.java
            )
                ?.adapter

        if (
            adapter == null
        ) {
            HistoryAutoRecoveryStatus
                .failed(
                    applicationContext,
                    "Automatic history recovery could not start: Bluetooth adapter unavailable."
                )
            return
        }

        val device =
            runCatching {
                adapter.getRemoteDevice(
                    sensor.address
                )
            }.getOrNull()

        if (
            device == null
        ) {
            HistoryAutoRecoveryStatus
                .failed(
                    applicationContext,
                    "Automatic history recovery could not open BLE address ${sensor.address}."
                )
            return
        }

        prefs.edit()
            .putLong(
                autoAttemptKey(
                    selectedSerial
                ),
                now
            )
            .apply()

        HistoryAutoRecoveryStatus
            .started(
                context =
                    applicationContext,
                summary =
                    summary
            )

        recentRecoveryInFlight =
            recentRecovery

        pauseScannerForHistory()

        HistoryAutoRecoveryStatus
            .progress(
                applicationContext,
                "Preparing F002 sync • pausing BLE scan…"
            )

        delay(
            HISTORY_SCANNER_SETTLE_MS
        )

        val started =
            historyBackfill.start(
                device =
                    device,
                serial =
                    selectedSerial,
                anchor =
                    reading,
                hours =
                    requestedHours
            ) {
                    recovered ->
                serviceScope.launch(
                    Dispatchers.IO
                ) {
                    val saved =
                        historyDb
                            .saveBackfillReadings(
                                recovered
                            )

                    val after =
                        historyDb
                            .findRecoverableGaps(
                                serial =
                                    selectedSerial,
                                currentSensorMinute =
                                    reading.sensorMinute,
                                windowMinutes =
                                    recoveryWindowMinutes,
                                graceMinutes =
                                    AUTO_RECOVERY_GRACE_MINUTES,
                                minimumGapMinutes =
                                    AUTO_RECOVERY_MIN_GAP_MINUTES
                            )

                    if (
                        after.gaps.isNotEmpty()
                    ) {
                        prefs.edit()
                            .putString(
                                autoUnresolvedSignatureKey(
                                    selectedSerial
                                ),
                                after.signature
                            )
                            .putLong(
                                autoUnresolvedAtKey(
                                    selectedSerial
                                ),
                                System.currentTimeMillis()
                            )
                            .apply()
                    } else {
                        prefs.edit()
                            .remove(
                                autoUnresolvedSignatureKey(
                                    selectedSerial
                                )
                            )
                            .remove(
                                autoUnresolvedAtKey(
                                    selectedSerial
                                )
                            )
                            .apply()
                    }

                    recentRecoveryInFlight =
                        false

                    HistoryAutoRecoveryStatus
                        .completed(
                            context =
                                applicationContext,
                            summary =
                                after,
                            recoveredReadings =
                                saved
                        )

                    if (
                        recentRecovery &&
                        after.gaps.isNotEmpty()
                    ) {
                        scheduleRecentHistoryRetry(
                            "Recent history still has ${after.missingMinutes} missing minutes after F002."
                        )
                    }
                }
            }

        if (
            !started
        ) {
            val message =
                historyBackfill
                    .state
                    .value
                    .message

            recentRecoveryInFlight =
                false

            resumeScannerAfterHistory()

            HistoryAutoRecoveryStatus
                .failed(
                    applicationContext,
                    "F002 could not start: $message"
                )

            if (
                recentRecovery
            ) {
                scheduleRecentHistoryRetry(
                    message
                )
            }
        }
    }

    private suspend fun forceRecentHistoryRecovery(
        ignoreThrottle: Boolean =
            false
    ) {
        val now =
            System.currentTimeMillis()

        if (
            !ignoreThrottle &&
            now -
                lastForcedRecoveryAtMs <
            FORCE_RECOVERY_MIN_INTERVAL_MS
        ) {
            return
        }

        lastForcedRecoveryAtMs =
            now

        val liveSensor =
            scanner.sensors
                .value
                .firstOrNull {
                    it.serial.equals(
                        selectedSerial,
                        ignoreCase =
                            true
                    )
                }

        val savedSensor =
            historyDb
                .loadSavedSensors()
                .firstOrNull {
                    it.serial.equals(
                        selectedSerial,
                        ignoreCase =
                            true
                    )
                }

        val sensor =
            when {
                liveSensor ==
                    null ->
                    savedSensor

                savedSensor ==
                    null ->
                    liveSensor

                (
                    savedSensor.reading
                        ?.sensorMinute
                        ?: -1
                ) >
                    (
                        liveSensor.reading
                            ?.sensorMinute
                            ?: -1
                    ) ->
                    savedSensor

                else ->
                    liveSensor
            }

        val reading =
            sensor
                ?.reading

        if (
            sensor ==
            null ||
            reading ==
            null
        ) {
            HistoryAutoRecoveryStatus
                .failed(
                    applicationContext,
                    "Recent-data recovery is waiting for a live AiDEX packet."
                )

            return
        }

        maybeAutomaticallyRecoverHistory(
            sensor =
                sensor,
            reading =
                reading,
            force =
                true,
            recoveryWindowMinutes =
                RECENT_RECOVERY_WINDOW_MINUTES,
            requestedHours =
                RECENT_RECOVERY_HOURS,
            recentRecovery =
                true
        )
    }

    private fun pauseScannerForHistory() {
        if (
            scannerPausedForHistory
        ) {
            return
        }

        scannerPausedForHistory =
            true

        scanner.stop()

        liveGatt.stop()

        AidexBackgroundScanManager
            .stop(
                applicationContext
            )
    }

    private suspend fun resumeScannerAfterHistory() {
        if (
            !scannerPausedForHistory
        ) {
            return
        }

        scannerPausedForHistory =
            false

        delay(
            HISTORY_SCANNER_RESUME_MS
        )

        startLiveGattMonitor()

        ensureBackgroundWakeScan()

        lastPacketSeenAtMs =
            System.currentTimeMillis()
    }

    private fun startSelectedSensorScanner() {
        val address =
            selectedBleAddress

        scanner.start(
            address
        )

        ensureBackgroundWakeScan()

        ContinuousMonitoringStatus
            .markScannerMode(
                applicationContext,
                if (
                    address.isNullOrBlank()
                ) {
                    "Advertisement fallback (address unavailable)"
                } else {
                    "Advertisement fallback ($address) + Android wake scan"
                }
            )
    }

    private fun startLiveGattMonitor() {
        val address =
            selectedBleAddress

        if (
            address.isNullOrBlank()
        ) {
            startSelectedSensorScanner()
            return
        }

        liveGatt.start(
            serial =
                selectedSerial,
            address =
                address
        )

        ensureBackgroundWakeScan()

        ContinuousMonitoringStatus
            .markScannerMode(
                applicationContext,
                "Direct F003 GATT ($address) + Android wake scan"
            )
    }

    private fun ensureBackgroundWakeScan() {
        AidexBackgroundScanManager
            .start(
                context =
                    applicationContext,
                serial =
                    selectedSerial,
                deviceAddress =
                    selectedBleAddress
            )
    }

    private suspend fun handleLiveGattReading(
        reading: AidexReading
    ) {
        val address =
            selectedBleAddress
                ?: return

        val sensor =
            AidexSensor(
                name =
                    historyDb
                        .loadSavedSensors()
                        .firstOrNull {
                            it.serial.equals(
                                selectedSerial,
                                ignoreCase =
                                    true
                            )
                        }
                        ?.name
                        ?: "AiDEX X-$selectedSerial",
                serial =
                    selectedSerial,
                address =
                    address,
                rssi =
                    reading.rssi,
                reading =
                    reading
            )

        historyDb.saveSensor(
            sensor
        )

        lastPacketSeenAtMs =
            System.currentTimeMillis()

        maybeAutomaticallyRecoverHistory(
            sensor =
                sensor,
            reading =
                reading
        )

        if (
            !reading.valid ||
            reading.isWarmup
        ) {
            return
        }

        lastReadingAtMs =
            System.currentTimeMillis()

        postWarmupRecoveryActive =
            false

        clearDisconnectedState()

        val calibratedMgDl =
            CalibrationTimeline
                .applyMgDl(
                    rawMgDl =
                        reading
                            .glucoseMgDl
                            .toDouble(),
                    receivedAtMs =
                        reading
                            .receivedAtMs,
                    events =
                        historyDb
                            .loadCalibrations(
                                selectedSerial
                            )
                )

        GlucoseAlarmEvaluator
            .evaluateReading(
                context =
                    this@AlarmMonitorService,
                reading =
                    reading,
                calibratedMgDl =
                    calibratedMgDl
            )
    }

    private fun selectedSensorWarmupComplete(
        nowMs: Long,
        latestReading: AidexReading?
    ): Boolean {
        if (
            latestReading !=
                null &&
            !latestReading.isWarmup &&
            latestReading.sensorMinute >=
                60
        ) {
            return true
        }

        val prefs =
            getSharedPreferences(
                APP_PREFS,
                Context.MODE_PRIVATE
            )

        val storedActivation =
            prefs
                .getLong(
                    sensorActivationKey(
                        selectedSerial
                    ),
                    0L
                )

        val inferredActivation =
            if (
                storedActivation >
                    0L
            ) {
                storedActivation
            } else {
                historyDb
                    .loadSensorFirstSeenMs(
                        selectedSerial
                    )
                    ?: 0L
            }

        return inferredActivation >
            0L &&
            nowMs -
                inferredActivation >=
            SENSOR_WARMUP_DURATION_MS
    }

    private fun isLiveGattAttemptActive(
        stage: LiveGattStage
    ): Boolean =
        when (
            stage
        ) {
            LiveGattStage.CONNECTING,
            LiveGattStage.DISCOVERING,
            LiveGattStage.ENABLING_F003,
            LiveGattStage.ENABLING_F002,
            LiveGattStage.ENABLING_F001,
            LiveGattStage.BONDING,
            LiveGattStage.REQUESTING_MASTER_KEY,
            LiveGattStage.READING_SESSION_KEY,
            LiveGattStage.INITIALIZING_PROTOCOL,
            LiveGattStage.ACTIVATION_REQUIRED,
            LiveGattStage.WAITING_FOR_CURRENT,
            LiveGattStage.RETRY_WAIT ->
                true

            else ->
                false
        }

    private fun sensorActivationKey(
        serial: String
    ): String =
        "sensor_activation_started_${serial.uppercase()}"

    private fun acquireMonitoringWakeLock() {
        val existing =
            monitorWakeLock

        if (
            existing !=
            null &&
            existing.isHeld
        ) {
            ContinuousMonitoringStatus
                .markWakeLock(
                    applicationContext,
                    true
                )
            return
        }

        val powerManager =
            getSystemService(
                PowerManager::class.java
            )
                ?: return

        val wakeLock =
            existing
                ?: powerManager
                    .newWakeLock(
                        PowerManager
                            .PARTIAL_WAKE_LOCK,
                        "$packageName:AidexContinuousMonitor"
                    )
                    .apply {
                        setReferenceCounted(
                            false
                        )
                    }
                    .also {
                        monitorWakeLock =
                            it
                    }

        runCatching {
            if (
                !wakeLock.isHeld
            ) {
                wakeLock.acquire()
            }
        }

        ContinuousMonitoringStatus
            .markWakeLock(
                applicationContext,
                wakeLock.isHeld
            )
    }

    private fun releaseMonitoringWakeLock() {
        val wakeLock =
            monitorWakeLock

        if (
            wakeLock !=
            null &&
            wakeLock.isHeld
        ) {
            runCatching {
                wakeLock.release()
            }
        }

        ContinuousMonitoringStatus
            .markWakeLock(
                applicationContext,
                false
            )
    }

    private fun scheduleRecentHistoryRetry(
        failure: String
    ) {
        historyRetryJob
            ?.cancel()

        HistoryAutoRecoveryStatus
            .retryScheduled(
                applicationContext,
                "F002 sync failed • retrying automatically in 30 sec • $failure"
            )

        historyRetryJob =
            serviceScope.launch(
                Dispatchers.IO
            ) {
                delay(
                    RECENT_RECOVERY_RETRY_MS
                )

                HistoryAutoRecoveryStatus
                    .requested(
                        applicationContext,
                        "Retrying recent F002 history sync…"
                    )

                forceRecentHistoryRecovery(
                    ignoreThrottle =
                        true
                )
            }
    }

    private fun autoAttemptKey(
        serial: String
    ): String =
        "history_auto_attempt_${
            serial.uppercase()
        }"

    private fun autoUnresolvedSignatureKey(
        serial: String
    ): String =
        "history_auto_unresolved_signature_${
            serial.uppercase()
        }"

    private fun autoUnresolvedAtKey(
        serial: String
    ): String =
        "history_auto_unresolved_at_${
            serial.uppercase()
        }"

    private fun evaluateDisconnected() {
        val alarms = AlarmPreferences.load(this)
        val prefs = getSharedPreferences(
            APP_PREFS,
            Context.MODE_PRIVATE
        )

        if (!alarms.disconnectedEnabled) {
            if (prefs.getBoolean(KEY_DISCONNECTED_ACTIVE, false)) {
                clearDisconnectedState()
            }
            return
        }

        val elapsed =
            System.currentTimeMillis() - lastReadingAtMs

        val delayMs =
            alarms.disconnectedMinutes
                .coerceIn(2, 60)
                .toLong() * 60_000L

        val active =
            prefs.getBoolean(KEY_DISCONNECTED_ACTIVE, false)

        if (elapsed >= delayMs && !active) {
            AlarmNotifications.notifyDisconnected(
                this,
                selectedSerial,
                alarms.disconnectedMinutes
            )

            prefs.edit()
                .putBoolean(KEY_DISCONNECTED_ACTIVE, true)
                .apply()
        }
    }

    private fun clearDisconnectedState() {
        val prefs = getSharedPreferences(
            APP_PREFS,
            Context.MODE_PRIVATE
        )

        if (prefs.getBoolean(KEY_DISCONNECTED_ACTIVE, false)) {
            AlarmNotifications.clearDisconnected(this)
        }

        prefs.edit()
            .putBoolean(KEY_DISCONNECTED_ACTIVE, false)
            .apply()
    }

    override fun onTaskRemoved(
        rootIntent: Intent?
    ) {
        /*
         * Clearing AiDEX X from Recent Apps must not stop CGM monitoring.
         * The service is intentionally independent of the Activity task.
         */
        ContinuousMonitoringStatus
            .markTaskRemoved(
                this
            )

        super.onTaskRemoved(
            rootIntent
        )
    }

    override fun onDestroy() {
        ContinuousMonitoringStatus
            .markStopped(
                this,
                "service_destroyed"
            )

        scanner.stop()

        liveGatt.stop()

        releaseMonitoringWakeLock()

        historyBackfill.cancel()

        sensorJob?.cancel()
        disconnectedJob?.cancel()
        watchdogJob?.cancel()
        backfillStateJob?.cancel()
        liveGattStateJob?.cancel()
        historyRetryJob?.cancel()

        serviceScope.cancel()
        historyDb.close()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? =
        null

    companion object {
        private const val APP_PREFS = "aidexx_reader"
        private const val KEY_LOW_ACTIVE = "alarm_state_low_active"
        private const val KEY_HIGH_ACTIVE = "alarm_state_high_active"
        private const val KEY_DISCONNECTED_ACTIVE =
            "alarm_state_disconnected_active"

        const val EXTRA_START_REASON =
            "monitor_start_reason"

        const val ACTION_FORCE_HISTORY_RECOVERY =
            "com.aidexx.reader.action.FORCE_HISTORY_RECOVERY"

        private const val DISCONNECT_CHECK_MS =
            30_000L

        private const val WATCHDOG_INTERVAL_MS =
            30_000L

        private const val BLE_PACKET_STALE_RESTART_MS =
            2L * 60L * 1000L

        private const val LIVE_GATT_PACKET_STALE_MS =
            2L * 60L * 1000L

        private const val FRESH_GLUCOSE_WINDOW_MS =
            3L * 60L * 1000L

        private const val SENSOR_WARMUP_DURATION_MS =
            60L * 60L * 1000L

        /*
         * Longer than AidexLiveGattClient's 90-second first-current timeout.
         * This prevents the service watchdog from continuously resetting a
         * secure session while it is legitimately waiting for F003.
         */
        private const val POST_WARMUP_GATT_NUDGE_INTERVAL_MS =
            2L * 60L * 1000L

        private const val FORCE_RECOVERY_MIN_INTERVAL_MS =
            45_000L

        private const val RECENT_RECOVERY_RETRY_MS =
            30_000L

        private const val RECENT_RECOVERY_WINDOW_MINUTES =
            120

        private const val RECENT_RECOVERY_HOURS =
            2

        private const val HISTORY_SCANNER_SETTLE_MS =
            350L

        private const val HISTORY_SCANNER_RESUME_MS =
            400L

        private const val FIRST_RECOVERABLE_SENSOR_MINUTE =
            60

        private const val AUTO_RECOVERY_WINDOW_MINUTES =
            72 * 60

        private const val AUTO_RECOVERY_GRACE_MINUTES =
            2

        private const val AUTO_RECOVERY_MIN_GAP_MINUTES =
            3

        private const val AUTO_GAP_CHECK_INTERVAL_MS =
            30_000L

        private const val AUTO_RECOVERY_RETRY_COOLDOWN_MS =
            2L * 60L * 1000L

        private const val AUTO_UNRESOLVED_SUPPRESSION_MS =
            6L * 60L * 60L * 1000L

        private val ACTIVE_BACKFILL_STAGES =
            setOf(
                HistoryBackfillStage
                    .CONNECTING,
                HistoryBackfillStage
                    .DISCOVERING,
                HistoryBackfillStage
                    .CONFIGURING,
                HistoryBackfillStage
                    .REQUESTING,
                HistoryBackfillStage
                    .RECEIVING
            )
    }
}
