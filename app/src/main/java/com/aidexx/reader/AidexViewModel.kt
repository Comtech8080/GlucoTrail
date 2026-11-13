package com.aidexx.reader

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aidexx.reader.ble.AidexActivator
import com.aidexx.reader.ble.AidexBleScanner
import com.aidexx.reader.ble.AidexHistoryBackfillClient
import com.aidexx.reader.ble.HistoryBackfillStage
import com.aidexx.reader.ble.HistoryBackfillState
import com.aidexx.reader.alarm.AlarmMonitorController
import com.aidexx.reader.alarm.AlarmMonitorService
import com.aidexx.reader.alarm.AlarmPreferences
import com.aidexx.reader.alarm.AlarmSettings
import com.aidexx.reader.data.AidexHistoryDatabase
import com.aidexx.reader.data.CalibrationEvent
import com.aidexx.reader.data.HistoryAutoRecoverySnapshot
import com.aidexx.reader.data.HistoryAutoRecoveryStatus
import com.aidexx.reader.data.HistoryDatabaseEvents
import com.aidexx.reader.data.DailyPatternReport
import com.aidexx.reader.data.GlucoseNote
import com.aidexx.reader.data.NotePhotoStore
import com.aidexx.reader.data.GlucosePeriodSummaryReport
import com.aidexx.reader.data.SensorOverlapComparison
import com.aidexx.reader.data.SensorUsageReport
import com.aidexx.reader.data.TimeInRangeReport
import com.aidexx.reader.protocol.AidexReading
import com.aidexx.reader.protocol.AidexSensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppThemeMode {
    SYSTEM,
    DAY,
    NIGHT,
    MODERN_DARK, CLEAN_LIGHT, AMOLED, SOFT_GLASS, CLINICAL_CLEAR, SIGNATURE
}

data class GlucoseLimits(
    val lowMmol: Double = 3.9,
    val highMmol: Double = 10.0
)

class AidexViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("aidexx_reader", 0)
    private val historyDb = AidexHistoryDatabase(app.applicationContext)

    val scanner = AidexBleScanner(app)
    val activator = AidexActivator(app)
    val historyBackfill =
        AidexHistoryBackfillClient(
            app.applicationContext
        )
    val historyBackfillState:
        StateFlow<HistoryBackfillState> =
        historyBackfill.state

    val historyAutoRecoveryState:
        StateFlow<HistoryAutoRecoverySnapshot> =
        HistoryAutoRecoveryStatus
            .state

    val sensors = scanner.sensors
    val scanStatus = scanner.status
    val activation = activator.state

    private val _savedSensors =
        MutableStateFlow<Map<String, AidexSensor>>(emptyMap())
    val savedSensors: StateFlow<Map<String, AidexSensor>> =
        _savedSensors.asStateFlow()

    private val _selectedSerial = MutableStateFlow(
        prefs.getString("selected_sensor_serial", null)
    )
    val selectedSerial: StateFlow<String?> = _selectedSerial.asStateFlow()

    private val _overlapOldSerial = MutableStateFlow(prefs.getString("overlap_old_serial", null))
    val overlapOldSerial: StateFlow<String?> = _overlapOldSerial.asStateFlow()
    private val _overlapNewSerial = MutableStateFlow(prefs.getString("overlap_new_serial", null))
    val overlapNewSerial: StateFlow<String?> = _overlapNewSerial.asStateFlow()

    // Background scan receiver can promote the next sensor while UI is open.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "selected_sensor_serial") {
            val newPrimary = prefs.getString("selected_sensor_serial", null)
            if (_selectedSerial.value != newPrimary) {
                _selectedSerial.value = newPrimary
                newPrimary?.let { serial ->
                    viewModelScope.launch(Dispatchers.IO) {
                        loadRecentHistory(serial)
                        loadCalibrations(serial)
                        resolveSelectedSensorActivationStart(serial)
                        loadCombinedRecentHistory()
                    }
                }
            }
        }
        if (key == "replacement_sensor_serial") {
            _replacementSerial.value = prefs.getString("replacement_sensor_serial", null)
        }
        if (key == "replacement_auto_handover") {
            _autoHandoverEnabled.value = prefs.getBoolean("replacement_auto_handover", false)
        }
    }

    private val _replacementSerial = MutableStateFlow(
        prefs.getString("replacement_sensor_serial", null)
    )
    val replacementSerial: StateFlow<String?> = _replacementSerial.asStateFlow()
    private val _autoHandoverEnabled = MutableStateFlow(
        prefs.getBoolean("replacement_auto_handover", false)
    )
    val autoHandoverEnabled: StateFlow<Boolean> = _autoHandoverEnabled.asStateFlow()

    fun setAutoHandoverEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("replacement_auto_handover", enabled).apply()
        _autoHandoverEnabled.value = enabled
    }

    private val _overlapComparison =
        MutableStateFlow<SensorOverlapComparison.PairedReading?>(null)
    val overlapComparison: StateFlow<SensorOverlapComparison.PairedReading?> =
        _overlapComparison.asStateFlow()

    private val _overlapCalibrations =
        MutableStateFlow<Map<String, List<CalibrationEvent>>>(emptyMap())
    val overlapCalibrations: StateFlow<Map<String, List<CalibrationEvent>>> =
        _overlapCalibrations.asStateFlow()

    /**
     * Register a replacement sensor without changing the primary or its
     * glucose alarm source. Call this with a scanned sensor after activation.
     */
    fun configureReplacementSensor(sensor: AidexSensor) {
        val serial = sensor.serial.trim().uppercase()
        val address = sensor.address.trim().uppercase()
        require(serial.isNotBlank() && address.isNotBlank())
        require(!serial.equals(_selectedSerial.value, ignoreCase = true))
        prefs.edit()
            .putString("replacement_sensor_serial", serial)
            .putString("replacement_sensor_address", address)
            .putString("overlap_old_serial", _selectedSerial.value)
            .putString("overlap_new_serial", serial)
            .apply()
        _overlapOldSerial.value = _selectedSerial.value
        _overlapNewSerial.value = serial
        _replacementSerial.value = serial
        AlarmMonitorController.sync(
            getApplication<Application>(),
            reason = "replacement_sensor_configured"
        )
        refreshOverlapComparison()
    }

    fun clearReplacementSensor() {
        prefs.edit()
            .remove("replacement_sensor_serial")
            .remove("replacement_sensor_address")
            .putBoolean("replacement_auto_handover", false)
            .apply()
        _autoHandoverEnabled.value = false
        _replacementSerial.value = null
        _overlapComparison.value = null
        AlarmMonitorController.sync(
            getApplication<Application>(),
            reason = "replacement_sensor_cleared"
        )
    }

    /** Compare two independently saved raw sensor streams, never autocalibrate. */
    fun refreshOverlapComparison() {
        val primary = _overlapOldSerial.value ?: _selectedSerial.value ?: return
        val replacement = _overlapNewSerial.value ?: _replacementSerial.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val oldReadings = historyDb.loadRecentReadings(primary, RECENT_MEMORY_DAYS)
            val newReadings = historyDb.loadRecentReadings(replacement, RECENT_MEMORY_DAYS)
            _historyBySerial.value = _historyBySerial.value.toMutableMap().apply {
                put(primary.uppercase(), oldReadings)
                put(replacement.uppercase(), newReadings)
            }
            _overlapComparison.value = SensorOverlapComparison.pair(
                oldReadings,
                newReadings,
                System.currentTimeMillis()
            )
            _overlapCalibrations.value = mapOf(
                primary.uppercase() to historyDb.loadCalibrations(primary),
                replacement.uppercase() to historyDb.loadCalibrations(replacement)
            )
        }
    }

    private val _selectedSensorActivationStartedAtMs =
        MutableStateFlow<Long?>(
            null
        )

    val selectedSensorActivationStartedAtMs:
        StateFlow<Long?> =
        _selectedSensorActivationStartedAtMs
            .asStateFlow()

    private val _calibrationEvents =
        MutableStateFlow<List<CalibrationEvent>>(
            emptyList()
        )

    val calibrationEvents:
        StateFlow<List<CalibrationEvent>> =
        _calibrationEvents
            .asStateFlow()

    private val _glucoseNotes =
        MutableStateFlow<List<GlucoseNote>>(
            emptyList()
        )

    val glucoseNotes:
        StateFlow<List<GlucoseNote>> =
        _glucoseNotes.asStateFlow()

    private val _themeMode = MutableStateFlow(
        runCatching {
            AppThemeMode.valueOf(
                prefs.getString("theme_mode", AppThemeMode.SYSTEM.name)
                    ?: AppThemeMode.SYSTEM.name
            )
        }.getOrDefault(AppThemeMode.SYSTEM)
    )
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _glucoseUnit = MutableStateFlow(
        runCatching {
            GlucoseUnit.valueOf(
                prefs.getString(
                    "glucose_unit",
                    GlucoseUnit.MMOL_L.name
                ) ?: GlucoseUnit.MMOL_L.name
            )
        }.getOrDefault(GlucoseUnit.MMOL_L)
    )
    val glucoseUnit: StateFlow<GlucoseUnit> =
        _glucoseUnit.asStateFlow()

    private val _glucoseLimits = MutableStateFlow(
        GlucoseLimits(
            lowMmol = prefs.getString("low_glucose_mmol", null)?.toDoubleOrNull() ?: 3.9,
            highMmol = prefs.getString("high_glucose_mmol", null)?.toDoubleOrNull() ?: 10.0
        )
    )
    val glucoseLimits: StateFlow<GlucoseLimits> = _glucoseLimits.asStateFlow()

    private val _alarmSettings =
        MutableStateFlow(AlarmPreferences.load(app.applicationContext))
    val alarmSettings: StateFlow<AlarmSettings> =
        _alarmSettings.asStateFlow()

    /*
     * Persistent SQLite history is retained across sensor replacements and
     * normal APK updates. The UI only keeps recent data in memory because the
     * graph needs at most 72 hours; long-period reports query SQLite directly.
     */
    private val _historyBySerial =
        MutableStateFlow<Map<String, List<AidexReading>>>(emptyMap())
    val historyBySerial: StateFlow<Map<String, List<AidexReading>>> =
        _historyBySerial.asStateFlow()

    private val _combinedRecentHistory =
        MutableStateFlow<List<AidexReading>>(
            emptyList()
        )

    val combinedRecentHistory:
        StateFlow<List<AidexReading>> =
        _combinedRecentHistory
            .asStateFlow()

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        HistoryAutoRecoveryStatus
            .initialize(
                app.applicationContext
            )

        // Restore saved sensor metadata and recent history immediately after
        // app restart or APK upgrade.
        viewModelScope.launch(Dispatchers.IO) {
            refreshSavedSensors()
            loadCombinedRecentHistory()
            loadAllNotes()

            _selectedSerial.value?.let { serial ->
                loadRecentHistory(
                    serial
                )

                loadCalibrations(
                    serial
                )

                resolveSelectedSensorActivationStart(
                    serial
                )
            }

            AlarmMonitorController.sync(
                app.applicationContext,
                reason =
                    "viewmodel_init"
            )
        }

        // Persist every sensor packet as it arrives.
        viewModelScope.launch(Dispatchers.IO) {
            scanner.sensors.collect { discovered ->
                if (discovered.isEmpty()) return@collect

                discovered.forEach { sensor ->
                    historyDb.saveSensor(sensor)

                    val reading = sensor.reading
                    if (
                        reading != null &&
                        reading.crcValid &&
                        reading.valid &&
                        !reading.isWarmup
                    ) {
                        appendRecentReading(reading)
                        appendAdvertisementBackfillToMemory(
                            reading
                        )

                    }

                    val key = sensor.serial.uppercase()
                    _savedSensors.value =
                        _savedSensors.value.toMutableMap().apply {
                            put(key, sensor)
                        }
                }
            }
        }
    }

    init {
        viewModelScope.launch(
            Dispatchers.IO
        ) {
            HistoryDatabaseEvents
                .event
                .collect {
                        event ->
                    if (
                        event ==
                        null
                    ) {
                        return@collect
                    }

                    loadCombinedRecentHistory()

                    if (
                        event.reason ==
                        "note_changed"
                    ) {
                        loadAllNotes()
                    }

                    val selected =
                        _selectedSerial.value
                            ?.uppercase()

                    if (
                        selected ==
                        event.serial
                    ) {
                        loadRecentHistory(
                            selected
                        )

                        if (
                            event.reason ==
                            "calibration_changed"
                        ) {
                            loadCalibrations(
                                selected
                            )
                        }

                        refreshSavedSensors()
                    }
                }
        }
    }

    fun saveCalibration(
        rawReading: AidexReading,
        bloodMgDl: Int
    ) {
        val serial =
            rawReading.serial
                .uppercase()

        val offset =
            bloodMgDl -
                rawReading.glucoseMgDl

        val event =
            CalibrationEvent(
                serial =
                    serial,
                effectiveAtMs =
                    rawReading.receivedAtMs,
                createdAtMs =
                    System.currentTimeMillis(),
                sensorMinute =
                    rawReading.sensorMinute,
                rawCgmMgDl =
                    rawReading.glucoseMgDl,
                bloodMgDl =
                    bloodMgDl,
                offsetMgDl =
                    offset
            )

        viewModelScope.launch(
            Dispatchers.IO
        ) {
            historyDb.saveCalibration(
                event
            )

            if (serial.equals(_selectedSerial.value, ignoreCase = true)) {
                loadCalibrations(serial)
            }
            refreshOverlapComparison()
            AlarmMonitorController.sync(
                getApplication<Application>(),
                reason = "calibration_changed"
            )
        }
    }

    fun resetCalibration(
        rawReading: AidexReading
    ) {
        saveCalibration(
            rawReading =
                rawReading,
            bloodMgDl =
                rawReading.glucoseMgDl
        )
    }

    fun saveGlucoseNote(
        note: GlucoseNote
    ) {
        val normalized =
            note.copy(
                serial =
                    note.serial.uppercase()
            )

        val previous =
            normalized.id
                .takeIf { it > 0L }
                ?.let { id ->
                    _glucoseNotes.value
                        .firstOrNull { it.id == id }
                }

        viewModelScope.launch(
            Dispatchers.IO
        ) {
            historyDb.saveNote(
                normalized
            )

            previous
                ?.photoPaths
                ?.filterNot { it in normalized.photoPaths }
                ?.let(NotePhotoStore::deletePhotos)

            loadNotes(
                normalized.serial
            )
        }
    }

    fun deleteGlucoseNote(
        note: GlucoseNote
    ) {
        if (
            note.id <=
            0L
        ) {
            return
        }

        viewModelScope.launch(
            Dispatchers.IO
        ) {
            historyDb.deleteNote(
                serial =
                    note.serial,
                noteId =
                    note.id
            )

            NotePhotoStore.deletePhotos(
                note.photoPaths
            )

            loadNotes(
                note.serial
            )
        }
    }

    suspend fun loadSensorUsageReport(
        days: Int
    ): SensorUsageReport =
        withContext(
            Dispatchers.IO
        ) {
            historyDb
                .calculateSensorUsageReportAllSensors(
                    days =
                        days
                )
        }

    suspend fun loadGlucosePeriodSummaryReport(
        days: Int
    ): GlucosePeriodSummaryReport =
        withContext(
            Dispatchers.IO
        ) {
            val limits =
                _glucoseLimits.value

            historyDb
                .calculateGlucosePeriodSummaryReportAllSensors(
                    days =
                        days,
                    lowMmol =
                        limits.lowMmol,
                    highMmol =
                        limits.highMmol
                )
        }

    suspend fun loadDailyPatternReport(
        days: Int
    ): DailyPatternReport =
        withContext(
            Dispatchers.IO
        ) {
            historyDb
                .calculateDailyPatternReportAllSensors(
                    days =
                        days
                )
        }

    suspend fun loadTimeInRangeReport(
        days: Int
    ): TimeInRangeReport =
        withContext(
            Dispatchers.IO
        ) {
            val limits =
                _glucoseLimits.value

            historyDb
                .calculateTimeInRangeReportAllSensors(
                    days =
                        days,
                    lowMmol =
                        limits.lowMmol,
                    highMmol =
                        limits.highMmol
                )
        }

    fun selectSensor(serial: String) {
        val normalized = serial.trim().uppercase()
        prefs.edit().putString("selected_sensor_serial", normalized).apply()
        _selectedSerial.value = normalized
        _selectedSensorActivationStartedAtMs.value =
            prefs
                .getLong(
                    sensorActivationKey(
                        normalized
                    ),
                    0L
                )
                .takeIf {
                    it > 0L
                }

        viewModelScope.launch(Dispatchers.IO) {
            loadRecentHistory(
                normalized
            )

            loadCalibrations(
                normalized
            )

            resolveSelectedSensorActivationStart(
                normalized
            )

            loadCombinedRecentHistory()
            loadAllNotes()
            refreshSavedSensors()
            AlarmMonitorController.sync(
                getApplication<Application>(),
                reason =
                    "sensor_selected"
            )
        }
    }

    /*
     * "Forget Sensor" deliberately does NOT delete database history.
     * It only removes the currently selected sensor from the dashboard.
     */
    fun clearSelectedSensor() {
        prefs.edit().remove("selected_sensor_serial").apply()
        _selectedSerial.value = null
        _selectedSensorActivationStartedAtMs.value = null

        _calibrationEvents.value =
            emptyList()

        AlarmMonitorController.sync(
            getApplication<Application>(),
            reason =
                "sensor_unselected"
        )
    }

    fun setAlarmSettings(settings: AlarmSettings) {
        AlarmPreferences.save(
            getApplication<Application>(),
            settings
        )
        _alarmSettings.value = settings
        AlarmMonitorController.sync(
            getApplication<Application>(),
            reason =
                "alarm_settings_changed"
        )
    }

    fun syncAlarmMonitoring() {
        _alarmSettings.value =
            AlarmPreferences.load(
                getApplication<Application>()
            )

        AlarmMonitorController.sync(
            getApplication<Application>(),
            reason =
                "ui_sync"
        )
    }

    fun requestImmediateHistoryRecovery() {
        AlarmMonitorController
            .requestHistoryRecovery(
                getApplication<Application>(),
                reason =
                    "graph_recent_gap"
            )
    }

    fun restartContinuousMonitoring() {
        AlarmMonitorController.sync(
            getApplication<Application>(),
            reason =
                "manual_restart"
        )
    }

    fun setThemeMode(mode: AppThemeMode) {
        prefs.edit().putString("theme_mode", mode.name).apply()
        _themeMode.value = mode
    }

    fun setGlucoseUnit(unit: GlucoseUnit) {
        prefs.edit()
            .putString("glucose_unit", unit.name)
            .apply()
        _glucoseUnit.value = unit
    }

    fun setGlucoseLimits(lowMmol: Double, highMmol: Double) {
        require(lowMmol > 0.0) { "Low glucose limit must be greater than 0." }
        require(highMmol > lowMmol) { "High glucose limit must be greater than low glucose limit." }

        prefs.edit()
            .putString("low_glucose_mmol", lowMmol.toString())
            .putString("high_glucose_mmol", highMmol.toString())
            .apply()

        _glucoseLimits.value = GlucoseLimits(
            lowMmol = lowMmol,
            highMmol = highMmol
        )
    }

    fun requestHistoryBackfillNow() {
        val selected =
            _selectedSerial.value

        if (
            selected.isNullOrBlank()
        ) {
            historyBackfill
                .reportBlocked(
                    "Backfill could not start: no sensor is selected."
                )
            return
        }

        val normalized =
            selected.uppercase()

        prefs.edit()
            .remove(
                backfillSuccessKey(
                    normalized
                )
            )
            .remove(
                backfillAttemptKey(
                    normalized
                )
            )
            .apply()

        val liveSensor =
            scanner.sensors.value
                .firstOrNull {
                    it.serial.equals(
                        normalized,
                        ignoreCase = true
                    )
                }

        val savedSensor =
            _savedSensors.value[
                normalized
            ]

        val sensor =
            liveSensor
                ?: savedSensor

        if (
            sensor == null
        ) {
            historyBackfill
                .reportBlocked(
                    "Backfill could not start: selected sensor $normalized is not present in the live scanner or saved sensor database."
                )
            return
        }

        val reading =
            liveSensor?.reading
                ?: sensor.reading

        if (
            reading == null
        ) {
            val invalidAdvertisementSeen =
                scanner
                    .status
                    .value
                    .lastAdvertisementCrcValid ==
                false

            historyBackfill
                .reportBlocked(
                    if (
                        invalidAdvertisementSeen
                    ) {
                        "Backfill could not start: an advertisement was detected but failed CRC validation. Waiting for a verified secure/current sensor anchor."
                    } else {
                        "Backfill could not start: no verified current reading is available to anchor the sensor timeline."
                    }
                )
            return
        }

        if (
            !reading.crcValid ||
            !reading.valid ||
            reading.isWarmup
        ) {
            historyBackfill
                .reportBlocked(
                    "Backfill could not start: waiting for a CRC-verified secure/current sensor anchor."
                )
            return
        }

        if (
            reading.sensorMinute <=
            0
        ) {
            historyBackfill
                .reportBlocked(
                    "Backfill could not start: the verified current sensor minute is invalid (${reading.sensorMinute})."
                )
            return
        }

        maybeStartHistoryBackfill(
            sensor =
                sensor.copy(
                    reading =
                        reading
                ),
            force =
                true
        )
    }

    fun startScanning() = scanner.start()
    fun stopScanning() = scanner.stop()
    fun activate(
        device: BluetoothDevice,
        serial: String,
        preservePrimaryMonitoring: Boolean = false
    ) {
        // On replacement activation the primary sensor must remain active.
        // Separate GATT clients may still be subject to Android BLE limits;
        // this path requires real two-sensor hardware validation.
        if (!preservePrimaryMonitoring) {
            pauseContinuousMonitoringForActivation()
        }
        activator.start(device, serial)
    }

    fun cancelActivation() {
        activator.cancel()

        AlarmMonitorController.sync(
            getApplication<Application>(),
            reason =
                "activation_cancelled_or_finished"
        )
    }

    private fun pauseContinuousMonitoringForActivation() {
        val app =
            getApplication<Application>()

        app.stopService(
            Intent(
                app,
                AlarmMonitorService::class.java
            )
        )
    }

    fun sensorActivationStartedAt(serial: String?): Long? =
        serial?.let {
            prefs.getLong(sensorActivationKey(it.trim().uppercase()), 0L)
                .takeIf { time -> time > 0L }
        }

    fun recordSensorActivation(
        serial: String,
        startedAtMs: Long =
            System.currentTimeMillis()
    ) {
        val normalized =
            serial
                .trim()
                .uppercase()

        if (normalized.isBlank()) {
            return
        }

        prefs
            .edit()
            .putLong(
                sensorActivationKey(
                    normalized
                ),
                startedAtMs
            )
            .apply()

        if (
            _selectedSerial.value
                ?.uppercase() ==
            normalized
        ) {
            _selectedSensorActivationStartedAtMs.value =
                startedAtMs
        }
    }

    private fun resolveSelectedSensorActivationStart(
        serial: String
    ) {
        val normalized =
            serial
                .trim()
                .uppercase()

        if (normalized.isBlank()) {
            return
        }

        val key =
            sensorActivationKey(
                normalized
            )

        val stored =
            prefs
                .getLong(
                    key,
                    0L
                )

        val resolved =
            if (stored > 0L) {
                stored
            } else {
                val firstSeen =
                    historyDb
                        .loadSensorFirstSeenMs(
                            normalized
                        )

                val latest =
                    historyDb
                        .loadLatestReading(
                            normalized
                        )

                val minuteEstimate =
                    latest
                        ?.let {
                            it.receivedAtMs -
                                it.sensorMinute
                                    .coerceAtLeast(
                                        0
                                    )
                                    .toLong() *
                                60_000L
                        }

                val inferred =
                    when {
                        latest != null &&
                            latest.sensorMinute >=
                            1 ->
                            minuteEstimate

                        firstSeen != null ->
                            firstSeen

                        else ->
                            minuteEstimate
                    }

                inferred
                    ?.takeIf {
                        it > 0L
                    }
                    ?.also {
                        prefs
                            .edit()
                            .putLong(
                                key,
                                it
                            )
                            .apply()
                    }
            }

        if (
            _selectedSerial.value
                ?.uppercase() ==
            normalized
        ) {
            _selectedSensorActivationStartedAtMs.value =
                resolved
        }
    }

    private fun sensorActivationKey(
        serial: String
    ): String =
        "sensor_activation_started_${serial.uppercase()}"

    private fun maybeStartHistoryBackfill(
        sensor: AidexSensor,
        force: Boolean
    ) {
        val reading =
            sensor.reading
                ?: return

        /*
         * F002 history must be anchored to a trustworthy current sensor
         * minute. Force bypasses only the retry/cooldown timers, never packet
         * validation or warm-up state.
         */
        if (
            !reading.crcValid ||
            !reading.valid ||
            reading.isWarmup ||
            reading.sensorMinute <= 0
        ) {
            return
        }

        val currentStage =
            historyBackfill
                .state
                .value
                .stage

        if (
            currentStage in
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
        ) {
            return
        }

        val normalized =
            sensor.serial.uppercase()

        val now =
            System.currentTimeMillis()

        val lastSuccess =
            prefs.getLong(
                backfillSuccessKey(
                    normalized
                ),
                0L
            )

        val lastAttempt =
            prefs.getLong(
                backfillAttemptKey(
                    normalized
                ),
                0L
            )

        if (
            !force &&
            now - lastSuccess <
            HISTORY_BACKFILL_INTERVAL_MS
        ) {
            return
        }

        if (
            !force &&
            now - lastAttempt <
            HISTORY_BACKFILL_RETRY_MS
        ) {
            return
        }

        val adapter =
            getApplication<Application>()
                .getSystemService(
                    BluetoothManager::class.java
                )
                ?.adapter

        if (
            adapter == null
        ) {
            if (
                force
            ) {
                historyBackfill
                    .reportBlocked(
                        "Backfill could not start: Android Bluetooth adapter is unavailable."
                    )
            }
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
            if (
                force
            ) {
                historyBackfill
                    .reportBlocked(
                        "Backfill could not start: BLE address '${sensor.address}' could not be opened."
                    )
            }
            return
        }

        prefs.edit()
            .putLong(
                backfillAttemptKey(
                    normalized
                ),
                now
            )
            .apply()

        val started =
            historyBackfill.start(
                device =
                    device,
                serial =
                    normalized,
                anchor =
                    reading,
                hours =
                    HISTORY_BACKFILL_HOURS
            ) {
                    backfilled ->
                viewModelScope.launch(
                    Dispatchers.IO
                ) {
                    historyDb
                        .saveBackfillReadings(
                            backfilled
                        )

                    loadRecentHistory(
                        normalized
                    )

                    refreshSavedSensors()

                    prefs.edit()
                        .putLong(
                            backfillSuccessKey(
                                normalized
                            ),
                            System.currentTimeMillis()
                        )
                        .apply()

                }
            }

        if (
            !started
        ) {
            if (
                force &&
                historyBackfill
                    .state
                    .value
                    .stage !=
                HistoryBackfillStage
                    .FAILED
            ) {
                historyBackfill
                    .reportBlocked(
                        "Backfill could not start: the encrypted history client rejected the request."
                    )
            }
            return
        }
    }

    private fun backfillSuccessKey(
        serial: String
    ): String =
        "history_backfill_success_${
            serial.uppercase()
        }"

    private fun backfillAttemptKey(
        serial: String
    ): String =
        "history_backfill_attempt_${
            serial.uppercase()
        }"

    private fun appendAdvertisementBackfillToMemory(
        current: AidexReading
    ) {
        val previous =
            current.previousMgDl

        if (
            previous != null &&
            current.sensorMinute >= 1
        ) {
            val previous2 =
                current.previous2MgDl

            appendRecentReadingIfMissing(
                AidexReading(
                    serial =
                        current.serial,
                    sensorMinute =
                        current.sensorMinute -
                            1,
                    glucoseMgDl =
                        previous,
                    trendRaw =
                        if (
                            previous2 != null
                        ) {
                            (
                                previous -
                                    previous2
                            ) * 10
                        } else {
                            0
                        },
                    quality =
                        current.quality,
                    valid =
                        true,
                    previousMgDl =
                        previous2,
                    previous2MgDl =
                        null,
                    crcValid =
                        true,
                    rssi =
                        current.rssi,
                    receivedAtMs =
                        current.receivedAtMs -
                            60_000L
                )
            )
        }

        val previous2 =
            current.previous2MgDl

        if (
            previous2 != null &&
            current.sensorMinute >= 2
        ) {
            appendRecentReadingIfMissing(
                AidexReading(
                    serial =
                        current.serial,
                    sensorMinute =
                        current.sensorMinute -
                            2,
                    glucoseMgDl =
                        previous2,
                    trendRaw =
                        0,
                    quality =
                        current.quality,
                    valid =
                        true,
                    previousMgDl =
                        null,
                    previous2MgDl =
                        null,
                    crcValid =
                        true,
                    rssi =
                        current.rssi,
                    receivedAtMs =
                        current.receivedAtMs -
                            120_000L
                )
            )
        }
    }

    private fun appendRecentReadingIfMissing(
        reading: AidexReading
    ) {
        val serial =
            reading.serial.uppercase()

        val existing =
            _historyBySerial.value[
                serial
            ].orEmpty()

        if (
            existing.any {
                it.sensorMinute ==
                    reading.sensorMinute
            }
        ) {
            return
        }

        appendRecentReading(
            reading
        )
    }

    private fun appendRecentReading(reading: AidexReading) {
        val serial = reading.serial.uppercase()
        val cutoff = System.currentTimeMillis() - RECENT_MEMORY_DAYS * DAY_MS

        val existing = _historyBySerial.value[serial]
            .orEmpty()
            .filter { it.receivedAtMs >= cutoff }
            .toMutableList()

        val sameMinute = existing.indexOfFirst {
            it.sensorMinute == reading.sensorMinute
        }

        if (sameMinute >= 0) {
            existing[sameMinute] = reading
        } else {
            existing += reading
        }

        _historyBySerial.value =
            _historyBySerial.value.toMutableMap().apply {
                put(serial, existing.sortedBy { it.receivedAtMs })
            }
    }

    private fun loadCalibrations(
        serial: String
    ) {
        _calibrationEvents.value =
            historyDb
                .loadCalibrations(
                    serial
                )
    }

    private fun loadNotes(
        serial: String
    ) {
        // Notes form one continuous journal across sensor replacements.
        loadAllNotes()
    }

    private fun loadAllNotes() {
        _glucoseNotes.value =
            historyDb
                .loadAllNotes()
    }

    private fun loadCombinedRecentHistory() {
        _combinedRecentHistory.value =
            historyDb
                .loadRecentCalibratedReadingsAllSensors(
                    RECENT_MEMORY_DAYS
                )
    }

    private fun loadRecentHistory(serial: String) {
        val normalized = serial.uppercase()
        val history = historyDb.loadRecentReadings(
            normalized,
            RECENT_MEMORY_DAYS
        )

        _historyBySerial.value =
            _historyBySerial.value.toMutableMap().apply {
                put(normalized, history)
            }
    }

    private fun refreshSavedSensors() {
        _savedSensors.value = historyDb
            .loadSavedSensors()
            .associateBy { it.serial.uppercase() }
    }

    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        scanner.stop()
        activator.cancel()
        historyBackfill.cancel()
        historyDb.close()
        super.onCleared()
    }

    companion object {
        private const val RECENT_MEMORY_DAYS = 14
        private const val HISTORY_BACKFILL_HOURS = 72
        private const val HISTORY_BACKFILL_INTERVAL_MS =
            15L * 60L * 1000L
        private const val HISTORY_BACKFILL_RETRY_MS =
            2L * 60L * 1000L
        private const val DAY_MS =
            24L * 60L * 60L * 1000L
    }
}
