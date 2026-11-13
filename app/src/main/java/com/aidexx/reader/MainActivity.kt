package com.aidexx.reader

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.app.NotificationManager
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.aidexx.reader.protocol.*
import com.aidexx.reader.alarm.AlarmSettings
import com.aidexx.reader.alarm.AlarmVoice
import com.aidexx.reader.alarm.AlarmNotifications
import com.aidexx.reader.alarm.AlarmSoundPlayer
import com.aidexx.reader.alarm.ContinuousMonitoringStatus
import com.aidexx.reader.data.CalibrationEvent
import com.aidexx.reader.data.CalibrationTimeline
import com.aidexx.reader.data.GlucoseNote
import com.aidexx.reader.ble.RawAdvertisementParser
import com.aidexx.reader.backup.AidexBackupManager
import com.aidexx.reader.backup.AidexBackupScheduler
import com.aidexx.reader.backup.BackupSettingsScreen
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.text.DateFormat
import java.util.Locale
import java.util.Date
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val AidexGreen = Color(0xFF73D338)
private val AidexGreenDark = Color(0xFF2F7D32)

class MainActivity : ComponentActivity() {
    private val vm: AidexViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply a staged backup before the ViewModel opens SQLite/preferences.
        AidexBackupManager.applyPendingRestore(this)
        AidexBackupScheduler.schedule(this)
        com.aidexx.reader.updates.UpdateScheduler.schedule(this)

        setContent {
            val themeMode by vm.themeMode.collectAsState()
            val systemDark = isSystemInDarkTheme()

            val useDark = themeMode.isDark(systemDark)
            val colors = trailColors(themeMode, systemDark)

            SideEffect {
                WindowCompat
                    .getInsetsController(
                        window,
                        window.decorView
                    )
                    .apply {
                        isAppearanceLightStatusBars =
                            !useDark
                        isAppearanceLightNavigationBars =
                            !useDark
                    }
            }

            val typography = Typography().let { base ->
                if(themeMode == AppThemeMode.CLINICAL_CLEAR) base.copy(
                    bodySmall=base.bodyMedium, labelSmall=base.labelMedium,
                    bodyMedium=base.bodyLarge, titleMedium=base.titleLarge
                ) else base
            }
            CompositionLocalProvider(LocalTrailTheme provides themeMode) {
                MaterialTheme(colorScheme = colors, typography = typography,
                    shapes = Shapes(medium=RoundedCornerShape(18.dp),large=RoundedCornerShape(22.dp))) {
                    AidexApp(vm)
                }
            }
        }
    }
}

private enum class Screen {
    HOME,
    DASHBOARD,
    FITNESS,
    NOTES,
    EXISTING,
    START,
    DIAGNOSTICS,
    SETTINGS_SENSOR,
    DUAL_SENSOR,
    SETTINGS_GLUCOSE,
    SETTINGS_FITNESS,
    SETTINGS_INSULIN,
    SETTINGS_BACKUP,
    SETTINGS_APPEARANCE
}

@Composable
private fun AidexApp(vm: AidexViewModel) {
    val context =
        LocalContext.current

    var screen by remember { mutableStateOf(Screen.HOME) }
    var settingsExpanded by remember { mutableStateOf(false) }
    var showGlucoseLimitsDialog by remember { mutableStateOf(false) }
    var showA1cEstimateDialog by remember { mutableStateOf(false) }
    var showAlarmSettingsDialog by remember { mutableStateOf(false) }
    var showContinuousMonitoringDialog by remember {
        mutableStateOf(false)
    }

    var showCalibrationDialog by remember {
        mutableStateOf(false)
    }

    var activationRepairMode by remember {
        mutableStateOf(false)
    }
    var replacementActivationMode by remember { mutableStateOf(false) }

    val permissions = rememberBluetoothPermissions()
    val notificationPermission = rememberNotificationPermission()
    val sensors by vm.sensors.collectAsState()
    val savedSensors by vm.savedSensors.collectAsState()
    val selectedSerial by vm.selectedSerial.collectAsState()
    val themeMode by vm.themeMode.collectAsState()
    val glucoseUnit by vm.glucoseUnit.collectAsState()
    val glucoseLimits by vm.glucoseLimits.collectAsState()
    val alarmSettings by vm.alarmSettings.collectAsState()
    val historyBySerial by vm.historyBySerial.collectAsState()
    val combinedRecentHistory by
        vm.combinedRecentHistory
            .collectAsState()
    val calibrationEvents by
        vm.calibrationEvents
            .collectAsState()

    val glucoseNotes by
        vm.glucoseNotes
            .collectAsState()

    androidx.activity.compose.BackHandler(
        enabled =
            screen !=
                Screen.HOME
    ) {
        if (
            screen ==
            Screen.START
        ) {
            vm.cancelActivation()
            activationRepairMode =
                false
        }

        screen =
            Screen.HOME
    }

    val rawSelectedSensor =
        selectedSerial?.let {
                serial ->
            val live =
                sensors.firstOrNull {
                    it.serial.equals(
                        serial,
                        ignoreCase =
                            true
                    )
                }

            val saved =
                savedSensors[
                    serial.uppercase()
                ]

            when {
                live ==
                    null ->
                    saved

                saved ==
                    null ->
                    live

                (
                    saved.reading
                        ?.sensorMinute
                        ?: -1
                ) >
                    (
                        live.reading
                            ?.sensorMinute
                            ?: -1
                    ) ->
                    saved

                (
                    saved.reading
                        ?.sensorMinute
                        ?: -1
                ) ==
                    (
                        live.reading
                            ?.sensorMinute
                            ?: -1
                    ) &&
                    (
                        saved.reading
                            ?.receivedAtMs
                            ?: 0L
                    ) >
                    (
                        live.reading
                            ?.receivedAtMs
                            ?: 0L
                    ) ->
                    saved

                else ->
                    live
            }
        }

    val selectedSensor =
        rawSelectedSensor
            ?.let {
                    sensor ->
                sensor.copy(
                    reading =
                        sensor.reading
                            ?.let {
                                    reading ->
                                CalibrationTimeline
                                    .applyReading(
                                        reading =
                                            reading,
                                        events =
                                            calibrationEvents
                                    )
                            }
                )
            }

    /*
     * Recent display history is combined across sensor serials. The database
     * assigns real wall-clock timestamps to live and recovered readings, so a
     * sensor replacement remains visible as one continuous time-based graph.
     */
    val selectedHistory =
        combinedRecentHistory

    /*
     * The selected sensor is owned by the 24/7 foreground monitoring scanner.
     * Running a second Activity scanner on HOME allowed one scanner to remain
     * fresh while the service scanner stalled.
     */
    val shouldScan =
        permissions.granted &&
            when (
                screen
            ) {
                Screen.HOME ->
                    false

                Screen.EXISTING ->
                    true

                Screen.DIAGNOSTICS ->
                    true

                Screen.START ->
                    true

                Screen.FITNESS,
                Screen.DASHBOARD,
                Screen.NOTES,
                Screen.SETTINGS_SENSOR,
                Screen.DUAL_SENSOR,
                Screen.SETTINGS_GLUCOSE,
                Screen.SETTINGS_FITNESS,
                Screen.SETTINGS_INSULIN,
                Screen.SETTINGS_BACKUP,
                Screen.SETTINGS_APPEARANCE ->
                    false
            }

    LaunchedEffect(shouldScan) {
        if (shouldScan) vm.startScanning() else vm.stopScanning()
    }

    LaunchedEffect(
        selectedSerial,
        alarmSettings,
        permissions.granted,
        notificationPermission.granted
    ) {
        vm.syncAlarmMonitoring()
    }

    LaunchedEffect(
        selectedSerial
    ) {
        if (
            selectedSerial !=
            null &&
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.M
        ) {
            val powerManager =
                context.getSystemService(
                    PowerManager::class.java
                )

            val exempt =
                powerManager
                    ?.isIgnoringBatteryOptimizations(
                        context.packageName
                    ) ==
                    true

            if (
                !exempt
            ) {
                val prefs =
                    context.getSharedPreferences(
                        "aidexx_reader",
                        Context.MODE_PRIVATE
                    )

                val alreadyPrompted =
                    prefs.getBoolean(
                        "screen_off_battery_prompt_v149",
                        false
                    )

                if (
                    !alreadyPrompted
                ) {
                    prefs.edit()
                        .putBoolean(
                            "screen_off_battery_prompt_v149",
                            true
                        )
                        .apply()

                    val launched =
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings
                                        .ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse(
                                        "package:${context.packageName}"
                                    )
                                )
                            )
                        }
                            .isSuccess

                    if (
                        !launched
                    ) {
                        showContinuousMonitoringDialog =
                            true
                    }
                }
            }
        }
    }

    LaunchedEffect(
        selectedSerial,
        notificationPermission.granted
    ) {
        if (
            selectedSerial != null &&
            !notificationPermission.granted
        ) {
            notificationPermission.request()
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(trailBackdrop())
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment =
                        Alignment.CenterVertically,
                    horizontalArrangement =
                        Arrangement.spacedBy(
                            4.dp
                        )
                ) {
                    if (
                        screen !=
                        Screen.HOME
                    ) {
                        TextButton(
                            onClick = {
                                if (
                                    screen ==
                                    Screen.START
                                ) {
                                    vm.cancelActivation()
                                    activationRepairMode =
                                        false
                                }

                                screen =
                                    Screen.HOME
                            }
                        ) {
                            Text(
                                "‹ Home"
                            )
                        }
                    }

                    Text(
                        "GlucoTrail",
                        style =
                            MaterialTheme
                                .typography
                                .headlineMedium,
                        fontWeight =
                            FontWeight.Bold
                    )
                }

                Row(
                    horizontalArrangement =
                        Arrangement.spacedBy(
                            6.dp
                        ),
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    if (
                        selectedSerial !=
                        null
                    ) {
                        OutlinedIconButton(
                            onClick = {
                                screen =
                                    Screen.NOTES
                            },
                            shape =
                                CircleShape
                        ) {
                            Text(
                                "✎",
                                style =
                                    MaterialTheme
                                        .typography
                                        .titleLarge
                            )
                        }

                        OutlinedIconButton(
                            onClick = {
                                screen =
                                    Screen.DASHBOARD
                            },
                            shape =
                                CircleShape
                        ) {
                            Text(
                                "▦",
                                style =
                                    MaterialTheme
                                        .typography
                                        .titleLarge
                            )
                        }
                    }

                    Box {
                        OutlinedIconButton(
                            onClick = { settingsExpanded = true },
                            shape = CircleShape
                        ) {
                            Text("⚙", style = MaterialTheme.typography.titleLarge)
                        }

                    DropdownMenu(
                        expanded =
                            settingsExpanded,
                        onDismissRequest = {
                            settingsExpanded =
                                false
                        }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Sensor  ›"
                                )
                            },
                            onClick = {
                                settingsExpanded =
                                    false

                                screen =
                                    Screen.SETTINGS_SENSOR
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Dual Sensor Monitoring") },
                            onClick = {
                                settingsExpanded = false
                                screen = Screen.DUAL_SENSOR
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Diagnostics"
                                )
                            },
                            onClick = {
                                settingsExpanded =
                                    false

                                screen =
                                    Screen.DIAGNOSTICS
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text(
                                    "24/7 Monitoring"
                                )
                            },
                            enabled =
                                selectedSerial !=
                                    null,
                            onClick = {
                                settingsExpanded =
                                    false

                                showContinuousMonitoringDialog =
                                    true
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Fitness") },
                            onClick = { settingsExpanded = false; screen = Screen.FITNESS }
                        )
                        DropdownMenuItem(
                            text = { Text("Fitness Settings") },
                            onClick = { settingsExpanded = false; screen = Screen.SETTINGS_FITNESS }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Insulin Settings") },
                            onClick = { settingsExpanded = false; screen = Screen.SETTINGS_INSULIN }
                        )

                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Glucose  ›"
                                )
                            },
                            onClick = {
                                settingsExpanded =
                                    false

                                screen =
                                    Screen.SETTINGS_GLUCOSE
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (
                                        calibrationEvents
                                            .lastOrNull()
                                            ?.offsetMgDl !=
                                        null &&
                                        calibrationEvents
                                            .lastOrNull()
                                            ?.offsetMgDl !=
                                        0
                                    ) {
                                        "Calibration ✓"
                                    } else {
                                        "Calibration"
                                    }
                                )
                            },
                            enabled =
                                rawSelectedSensor
                                    ?.reading !=
                                null,
                            onClick = {
                                settingsExpanded =
                                    false

                                showCalibrationDialog =
                                    true
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Alarm Settings"
                                )
                            },
                            onClick = {
                                settingsExpanded =
                                    false

                                showAlarmSettingsDialog =
                                    true
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text(
                                    "A1C Estimate"
                                )
                            },
                            onClick = {
                                settingsExpanded =
                                    false

                                showA1cEstimateDialog =
                                    true
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("App Updates  ›") },
                            onClick = {
                                settingsExpanded = false
                                context.startActivity(Intent(context, com.aidexx.reader.updates.AppUpdatesActivity::class.java))
                            }
                        )

                        HorizontalDivider()

                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Data & Backup  ›"
                                )
                            },
                            onClick = {
                                settingsExpanded =
                                    false

                                screen =
                                    Screen.SETTINGS_BACKUP
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Appearance  ›"
                                )
                            },
                            onClick = {
                                settingsExpanded =
                                    false

                                screen =
                                    Screen.SETTINGS_APPEARANCE
                            }
                        )
                    }
                    }
                }
            }

            if (
                showCalibrationDialog
            ) {
                CalibrationDialog(
                    rawSensor =
                        rawSelectedSensor,
                    calibrationEvents =
                        calibrationEvents,
                    glucoseUnit =
                        glucoseUnit,
                    onApply = {
                            rawReading,
                            bloodMgDl ->
                        vm.saveCalibration(
                            rawReading =
                                rawReading,
                            bloodMgDl =
                                bloodMgDl
                        )

                        showCalibrationDialog =
                            false
                    },
                    onReset = {
                            rawReading ->
                        vm.resetCalibration(
                            rawReading
                        )

                        showCalibrationDialog =
                            false
                    },
                    onDismiss = {
                        showCalibrationDialog =
                            false
                    }
                )
            }

            if (showGlucoseLimitsDialog) {
                GlucoseLimitsDialog(
                    limits = glucoseLimits,
                    glucoseUnit = glucoseUnit,
                    onDismiss = { showGlucoseLimitsDialog = false },
                    onSave = { lowMmol, highMmol ->
                        vm.setGlucoseLimits(lowMmol, highMmol)
                        showGlucoseLimitsDialog = false
                    }
                )
            }

            if (showA1cEstimateDialog) {
                A1cEstimateDialog(
                    history = selectedHistory,
                    glucoseUnit = glucoseUnit,
                    onDismiss = { showA1cEstimateDialog = false }
                )
            }

            if (showAlarmSettingsDialog) {
                AlarmSettingsDialog(
                    settings = alarmSettings,
                    glucoseLimits = glucoseLimits,
                    glucoseUnit = glucoseUnit,
                    notificationPermissionGranted =
                        notificationPermission.granted,
                    onRequestNotificationPermission =
                        notificationPermission.request,
                    onDismiss = {
                        showAlarmSettingsDialog = false
                    },
                    onSave = { updated ->
                        vm.setAlarmSettings(updated)
                        showAlarmSettingsDialog = false
                    }
                )
            }

            if (!permissions.granted) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Bluetooth scan permissions required", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Android Location permission is requested so the OS does not filter BLE advertisements. The app does not read or store GPS location.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = permissions.request) {
                            Text("Allow Bluetooth + Location")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp)
            ) {
                if (
                showContinuousMonitoringDialog
            ) {
                ContinuousMonitoringDialog(
                    selectedSerial =
                        selectedSerial,
                    onRestart = {
                        vm.restartContinuousMonitoring()
                    },
                    onDismiss = {
                        showContinuousMonitoringDialog =
                            false
                    }
                )
            }

            when (screen) {
                    Screen.HOME -> HomeScreen(
                        vm = vm,
                        selectedSerial = selectedSerial,
                        selectedSensor = selectedSensor,
                        history = selectedHistory,
                        notes = glucoseNotes,
                        glucoseLimits = glucoseLimits,
                        glucoseUnit = glucoseUnit,
                        onSelectExisting = {
                            screen =
                                Screen.EXISTING
                        },
                        onOpenNotes = {
                            screen =
                                Screen.NOTES
                        },
                        onCompleteActivation = {
                            activationRepairMode =
                                true
                            screen =
                                Screen.START
                        }
                    )

                    Screen.DASHBOARD ->
                        if (
                            selectedSensor !=
                            null
                        ) {
                            DashboardScreen(
                                vm =
                                    vm,
                                sensor =
                                    selectedSensor,
                                history =
                                    selectedHistory,
                                notes =
                                    glucoseNotes,
                                glucoseLimits =
                                    glucoseLimits,
                                glucoseUnit =
                                    glucoseUnit
                            )
                        } else {
                            Text(
                                "Select a sensor to open the dashboard."
                            )
                        }

                    Screen.NOTES ->
                        selectedSerial?.let { serial ->
                            NotesHistoryScreen(
                                vm = vm,
                                serial = serial,
                                notes = glucoseNotes
                            )
                        } ?: Text(
                            "Select a sensor to open Notes."
                        )

                    Screen.EXISTING -> ExistingSensorScreen(
                        vm = vm,
                        selectedSerial = selectedSerial,
                        glucoseUnit = glucoseUnit,
                        onSelect = { sensor ->
                            vm.selectSensor(sensor.serial)
                            screen = Screen.HOME
                        },
                        onBack = { screen = Screen.HOME }
                    )

                    Screen.START -> StartSensorScreen(
                        replacementMode = replacementActivationMode,
                        vm =
                            vm,
                        initialSerial =
                            if (
                                activationRepairMode
                            ) {
                                selectedSerial
                            } else {
                                null
                            },
                        initialBleAddress =
                            if (
                                activationRepairMode
                            ) {
                                selectedSensor
                                    ?.address
                            } else {
                                null
                            },
                        repairMode =
                            activationRepairMode,
                        onBack = {
                            activationRepairMode = false
                            replacementActivationMode = false
                            screen = Screen.HOME
                        }
                    )

                    Screen.DUAL_SENSOR -> DualSensorScreen(
                        vm = vm,
                        onStartReplacement = {
                            replacementActivationMode = true
                            activationRepairMode = false
                            screen = Screen.START
                        }
                    )

                    Screen.DIAGNOSTICS -> DiagnosticsScreen(
                        vm,
                        glucoseUnit =
                            glucoseUnit,
                        onBack = {
                            screen =
                                Screen.HOME
                        }
                    )

                    Screen.SETTINGS_SENSOR ->
                        SensorSettingsScreen(
                            selectedSerial =
                                selectedSerial,
                            onChangeSensor = {
                                screen =
                                    Screen.EXISTING
                            },
                            onUnselectSensor = {
                                vm.clearSelectedSensor()

                                screen =
                                    Screen.HOME
                            },
                            onStartNewSensor = {
                                activationRepairMode =
                                    false
                                screen =
                                    Screen.START
                            }
                        )

                    Screen.SETTINGS_FITNESS -> FitnessScreen(settingsOnly=true)
                    Screen.FITNESS -> FitnessScreen()
                    Screen.SETTINGS_INSULIN -> InsulinSettingsScreen()

                    Screen.SETTINGS_GLUCOSE ->
                        GlucoseSettingsScreen(
                            glucoseUnit =
                                glucoseUnit,
                            glucoseLimits =
                                glucoseLimits,
                            onUnitChanged = {
                                    unit ->
                                vm.setGlucoseUnit(
                                    unit
                                )
                            },
                            onEditLimits = {
                                showGlucoseLimitsDialog =
                                    true
                            }
                        )

                    Screen.SETTINGS_BACKUP ->
                        BackupSettingsScreen()

                    Screen.SETTINGS_APPEARANCE ->
                        AppearanceSettingsScreen(
                            themeMode =
                                themeMode,
                            onThemeChanged = {
                                    mode ->
                                vm.setThemeMode(
                                    mode
                                )
                            }
                        )
                }
            }
        }
    }
}


private data class NotePeriodSummary(
    val days: Int,
    val fastTotal: Double,
    val fastAveragePerLoggedDay: Double?,
    val fastLoggedDays: Int,
    val fastDoseCount: Int,
    val fastAverageDose: Double?,
    val slowTotal: Double,
    val slowAveragePerLoggedDay: Double?,
    val slowLoggedDays: Int,
    val carbsTotal: Double,
    val carbsAveragePerLoggedDay: Double?,
    val carbsLoggedDays: Int
)

private fun calculateNotePeriodSummary(
    notes: List<GlucoseNote>,
    days: Int,
    nowMs: Long =
        System.currentTimeMillis()
): NotePeriodSummary {
    val normalizedDays =
        days.coerceAtLeast(
            1
        )

    val zone =
        ZoneId.systemDefault()

    val endDate =
        Instant
            .ofEpochMilli(
                nowMs
            )
            .atZone(
                zone
            )
            .toLocalDate()

    val startDate =
        endDate.minusDays(
            (
                normalizedDays -
                    1
            ).toLong()
        )

    val periodNotes =
        notes.filter {
                note ->
            val date =
                Instant
                    .ofEpochMilli(
                        note.timestampMs
                    )
                    .atZone(
                        zone
                    )
                    .toLocalDate()

            !date.isBefore(
                startDate
            ) &&
                !date.isAfter(
                    endDate
                )
        }

    val fastNotes =
        periodNotes.filter {
            it.fastInsulinUnits >
                0.0
        }

    val fastByDay =
        fastNotes
            .groupBy {
                    note ->
                Instant
                    .ofEpochMilli(
                        note.timestampMs
                    )
                    .atZone(
                        zone
                    )
                    .toLocalDate()
            }
            .mapValues {
                    (_, dayNotes) ->
                dayNotes.sumOf {
                    it.fastInsulinUnits
                }
            }

    val slowByDay =
        periodNotes
            .filter {
                it.slowInsulinUnits >
                    0.0
            }
            .groupBy {
                    note ->
                Instant
                    .ofEpochMilli(
                        note.timestampMs
                    )
                    .atZone(
                        zone
                    )
                    .toLocalDate()
            }
            .mapValues {
                    (_, dayNotes) ->
                dayNotes.sumOf {
                    it.slowInsulinUnits
                }
            }

    val carbsByDay =
        periodNotes
            .filter {
                it.carbsGrams >
                    0.0
            }
            .groupBy {
                    note ->
                Instant
                    .ofEpochMilli(
                        note.timestampMs
                    )
                    .atZone(
                        zone
                    )
                    .toLocalDate()
            }
            .mapValues {
                    (_, dayNotes) ->
                dayNotes.sumOf {
                    it.carbsGrams
                }
            }

    val fastTotal =
        fastByDay
            .values
            .sum()

    val slowTotal =
        slowByDay
            .values
            .sum()

    val carbsTotal =
        carbsByDay
            .values
            .sum()

    return NotePeriodSummary(
        days =
            normalizedDays,
        fastTotal =
            fastTotal,
        fastAveragePerLoggedDay =
            fastByDay
                .values
                .takeIf {
                    it.isNotEmpty()
                }
                ?.average(),
        fastLoggedDays =
            fastByDay.size,
        fastDoseCount =
            fastNotes.size,
        fastAverageDose =
            fastNotes
                .map {
                    it.fastInsulinUnits
                }
                .takeIf {
                    it.isNotEmpty()
                }
                ?.average(),
        slowTotal =
            slowTotal,
        slowAveragePerLoggedDay =
            slowByDay
                .values
                .takeIf {
                    it.isNotEmpty()
                }
                ?.average(),
        slowLoggedDays =
            slowByDay.size,
        carbsTotal =
            carbsTotal,
        carbsAveragePerLoggedDay =
            carbsByDay
                .values
                .takeIf {
                    it.isNotEmpty()
                }
                ?.average(),
        carbsLoggedDays =
            carbsByDay.size
    )
}

private fun formatDashboardNumber(
    value: Double
): String =
    String.format(
        Locale.getDefault(),
        "%.1f",
        value
    )

@Composable
private fun DashboardScreen(
    vm: AidexViewModel,
    sensor: AidexSensor,
    history: List<AidexReading>,
    notes: List<GlucoseNote>,
    glucoseLimits: GlucoseLimits,
    glucoseUnit: GlucoseUnit
) {
    var selectedDays by remember {
        mutableIntStateOf(
            7
        )
    }

    val nowMs =
        rememberWallClockNowMs()

    val autoRecovery by
        vm.historyAutoRecoveryState
            .collectAsState()

    val refreshKey =
        history.size +
            autoRecovery
                .lastCompletedMs
                .hashCode()

    val glucoseReport by
        produceState<
            com.aidexx.reader.data.GlucosePeriodSummaryReport?
        >(
            initialValue =
                null,
            key1 =
                sensor.serial,
            key2 =
                selectedDays,
            key3 =
                Triple(
                    glucoseLimits.lowMmol,
                    glucoseLimits.highMmol,
                    refreshKey
                )
        ) {
            value =
                vm.loadGlucosePeriodSummaryReport(
                    selectedDays
                )
        }

    val timeInRangeReport by
        produceState<
            com.aidexx.reader.data.TimeInRangeReport?
        >(
            initialValue =
                null,
            key1 =
                sensor.serial,
            key2 =
                selectedDays,
            key3 =
                Triple(
                    glucoseLimits.lowMmol,
                    glucoseLimits.highMmol,
                    refreshKey
                )
        ) {
            value =
                vm.loadTimeInRangeReport(
                    selectedDays
                )
        }

    val noteSummary =
        remember(
            notes,
            selectedDays,
            nowMs /
                60_000L
        ) {
            calculateNotePeriodSummary(
                notes =
                    notes,
                days =
                    selectedDays,
                nowMs =
                    nowMs
            )
        }

    val todaySummary =
        remember(
            notes,
            nowMs /
                60_000L
        ) {
            calculateNotePeriodSummary(
                notes =
                    notes,
                days =
                    1,
                nowMs =
                    nowMs
            )
        }

    val reading =
        sensor.reading

    val readingAgeText =
        reading
            ?.let {
                val ageMinutes =
                    (
                        (
                            nowMs -
                                it.receivedAtMs
                        ) /
                            60_000L
                    )
                        .coerceAtLeast(
                            0L
                        )

                when {
                    ageMinutes <=
                        0L ->
                        "Just now"

                    ageMinutes ==
                        1L ->
                        "1 min ago"

                    else ->
                        "$ageMinutes min ago"
                }
            }
            ?: "No reading"

    val sensorRemainingText =
        reading
            ?.let {
                val remainingMinutes =
                    (
                        15 *
                            24 *
                            60 -
                            it.sensorMinute
                    )
                        .coerceAtLeast(
                            0
                        )

                val days =
                    remainingMinutes /
                        (
                            24 *
                                60
                        )

                val hours =
                    (
                        remainingMinutes %
                            (
                                24 *
                                    60
                            )
                    ) /
                        60

                if (
                    days >
                    0
                ) {
                    "${days}d ${hours}h"
                } else {
                    "${hours}h"
                }
            }
            ?: "—"

    LazyColumn(
        modifier =
            Modifier.fillMaxSize(),
        verticalArrangement =
            Arrangement.spacedBy(
                10.dp
            ),
        contentPadding =
            PaddingValues(
                bottom =
                    28.dp
            )
    ) {
        item {
            Column {
                Text(
                    "Dashboard",
                    style =
                        MaterialTheme
                            .typography
                            .headlineSmall,
                    fontWeight =
                        FontWeight.Bold
                )

                Text(
                    "Glucose, insulin, carbs and sensor status",
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }
        }

        item { ActiveInsulinCard(notes) }

        item {
            ReportDaySelector(
                selectedDays =
                    selectedDays,
                onSelected = {
                    selectedDays =
                        it
                }
            )
        }

        item {
            DashboardSectionCard(
                title =
                    "Today"
            ) {
                if (
                    todaySummary.fastDoseCount >
                        0
                ) {
                    DashboardMetricRow(
                        leftLabel =
                            "Current glucose",
                        leftValue =
                            reading
                                ?.let {
                                    "${glucoseUnit.formatValueFromMgDl(it.glucoseMgDl.toDouble())} ${glucoseUnit.label}"
                                }
                                ?: "—",
                        rightLabel =
                            "Fast insulin",
                        rightValue =
                            "${formatDashboardNumber(todaySummary.fastTotal)} U"
                    )

                    Spacer(
                        Modifier.height(
                            12.dp
                        )
                    )
                } else {
                    DashboardMetric(
                        label =
                            "Current glucose",
                        value =
                            reading
                                ?.let {
                                    "${glucoseUnit.formatValueFromMgDl(it.glucoseMgDl.toDouble())} ${glucoseUnit.label}"
                                }
                                ?: "—",
                        modifier =
                            Modifier.fillMaxWidth()
                    )

                    Spacer(
                        Modifier.height(
                            12.dp
                        )
                    )
                }

                DashboardMetricRow(
                    leftLabel =
                        "Slow insulin",
                    leftValue =
                        "${formatDashboardNumber(todaySummary.slowTotal)} U",
                    rightLabel =
                        "Carbs",
                    rightValue =
                        "${formatDashboardNumber(todaySummary.carbsTotal)} g"
                )
            }
        }

        item {
            DashboardSectionCard(
                title =
                    "Glucose · ${selectedDays} days"
            ) {
                val currentGlucoseReport =
                    glucoseReport

                val currentTimeInRange =
                    timeInRangeReport

                if (
                    currentGlucoseReport ==
                        null ||
                    currentTimeInRange ==
                        null
                ) {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    val averageMgDl =
                        currentGlucoseReport
                            .overallAverageMgDl

                    val gmi =
                        averageMgDl
                            ?.let {
                                A1cCalculator
                                    .fromAverageMgDl(
                                        it
                                    )
                                    .gmiPercent
                            }

                    DashboardMetricRow(
                        leftLabel =
                            "Average glucose",
                        leftValue =
                            averageMgDl
                                ?.let {
                                    "${glucoseUnit.formatValueFromMgDl(it)} ${glucoseUnit.label}"
                                }
                                ?: "—",
                        rightLabel =
                            "Time in range",
                        rightValue =
                            "${String.format(Locale.getDefault(), "%.0f", currentTimeInRange.targetPercent)}%"
                    )

                    Spacer(
                        Modifier.height(
                            12.dp
                        )
                    )

                    DashboardMetricRow(
                        leftLabel =
                            "Estimated A1C",
                        leftValue =
                            gmi
                                ?.let {
                                    "${String.format(Locale.getDefault(), "%.1f", it)}%"
                                }
                                ?: "—",
                        rightLabel =
                            "Low events",
                        rightValue =
                            currentGlucoseReport
                                .totalLowEvents
                                .toString()
                    )

                    Spacer(
                        Modifier.height(
                            12.dp
                        )
                    )

                    DashboardMetricRow(
                        leftLabel =
                            "High events",
                        leftValue =
                            currentGlucoseReport
                                .totalHighEvents
                                .toString(),
                        rightLabel =
                            "Very high events",
                        rightValue =
                            currentGlucoseReport
                                .totalVeryHighEvents
                                .toString()
                    )

                    Spacer(
                        Modifier.height(
                            10.dp
                        )
                    )

                    Text(
                        "Glucose data available for ${currentGlucoseReport.availableDays} of ${currentGlucoseReport.days} days · ${currentGlucoseReport.totalReadings} readings",
                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,
                        color =
                            if (
                                currentGlucoseReport
                                    .availableDays <
                                currentGlucoseReport
                                    .days
                            ) {
                                MaterialTheme
                                    .colorScheme
                                    .error
                            } else {
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant
                            }
                    )
                }
            }
        }

        if (
            noteSummary.fastDoseCount >
                0
        ) {
            item {
                DashboardSectionCard(
                    title =
                        "Fast insulin · ${selectedDays} days"
                ) {
                    DashboardMetricRow(
                        leftLabel =
                            "Average / logged day",
                        leftValue =
                            noteSummary
                                .fastAveragePerLoggedDay
                                ?.let {
                                    "${formatDashboardNumber(it)} U/day"
                                }
                                ?: "—",
                        rightLabel =
                            "Total",
                        rightValue =
                            "${formatDashboardNumber(noteSummary.fastTotal)} U"
                    )

                    Spacer(
                        Modifier.height(
                            12.dp
                        )
                    )

                    DashboardMetricRow(
                        leftLabel =
                            "Doses",
                        leftValue =
                            noteSummary
                                .fastDoseCount
                                .toString(),
                        rightLabel =
                            "Average dose",
                        rightValue =
                            noteSummary
                                .fastAverageDose
                                ?.let {
                                    "${formatDashboardNumber(it)} U"
                                }
                                ?: "—"
                    )

                    Spacer(
                        Modifier.height(
                            10.dp
                        )
                    )

                    Text(
                        "Fast insulin logged on ${noteSummary.fastLoggedDays} of ${noteSummary.days} calendar days. Days with no fast-insulin entry are not treated as zero.",
                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )
                }
            }
        }

        item {
            DashboardSectionCard(
                title =
                    "Slow insulin & carbs · ${selectedDays} days"
            ) {
                DashboardMetricRow(
                    leftLabel =
                        "Slow avg / logged day",
                    leftValue =
                        noteSummary
                            .slowAveragePerLoggedDay
                            ?.let {
                                "${formatDashboardNumber(it)} U/day"
                            }
                            ?: "—",
                    rightLabel =
                        "Slow total",
                    rightValue =
                        "${formatDashboardNumber(noteSummary.slowTotal)} U"
                )

                Spacer(
                    Modifier.height(
                        12.dp
                    )
                )

                DashboardMetricRow(
                    leftLabel =
                        "Carbs avg / logged day",
                    leftValue =
                        noteSummary
                            .carbsAveragePerLoggedDay
                            ?.let {
                                "${formatDashboardNumber(it)} g/day"
                            }
                            ?: "—",
                    rightLabel =
                        "Carbs total",
                    rightValue =
                        "${formatDashboardNumber(noteSummary.carbsTotal)} g"
                )

                Spacer(
                    Modifier.height(
                        10.dp
                    )
                )

                Text(
                    "Logged days: slow insulin ${noteSummary.slowLoggedDays}/${noteSummary.days} · carbs ${noteSummary.carbsLoggedDays}/${noteSummary.days}",
                    style =
                        MaterialTheme
                            .typography
                            .labelSmall,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }
        }

        item {
            DashboardSectionCard(
                title =
                    "Sensor & data"
            ) {
                DashboardMetricRow(
                    leftLabel =
                        "Latest reading",
                    leftValue =
                        readingAgeText,
                    rightLabel =
                        "Sensor remaining",
                    rightValue =
                        sensorRemainingText
                )

                Spacer(
                    Modifier.height(
                        12.dp
                    )
                )

                DashboardMetricRow(
                    leftLabel =
                        "History recovery",
                    leftValue =
                        if (
                            autoRecovery.active
                        ) {
                            "Recovering"
                        } else {
                            "Ready"
                        },
                    rightLabel =
                        "Selected sensor",
                    rightValue =
                        sensor.serial
                )
            }
        }

        item {
            Text(
                "Insulin and carbohydrate statistics are calculated only from Notes entered in GlucoTrail. They are informational summaries and are not dosing recommendations.",
                style =
                    MaterialTheme
                        .typography
                        .bodySmall,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DashboardSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier =
            Modifier.fillMaxWidth(),
        shape =
            RoundedCornerShape(
                18.dp
            )
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        16.dp
                    )
        ) {
            Text(
                title,
                style =
                    MaterialTheme
                        .typography
                        .titleMedium,
                fontWeight =
                    FontWeight.Bold
            )

            Spacer(
                Modifier.height(
                    12.dp
                )
            )

            content()
        }
    }
}

@Composable
private fun DashboardMetricRow(
    leftLabel: String,
    leftValue: String,
    rightLabel: String,
    rightValue: String
) {
    Row(
        modifier =
            Modifier.fillMaxWidth(),
        horizontalArrangement =
            Arrangement.spacedBy(
                16.dp
            )
    ) {
        DashboardMetric(
            label =
                leftLabel,
            value =
                leftValue,
            modifier =
                Modifier.weight(
                    1f
                )
        )

        DashboardMetric(
            label =
                rightLabel,
            value =
                rightValue,
            modifier =
                Modifier.weight(
                    1f
                )
        )
    }
}

@Composable
private fun DashboardMetric(
    label: String,
    value: String,
    modifier: Modifier =
        Modifier
) {
    Column(
        modifier =
            modifier
    ) {
        Text(
            label,
            style =
                MaterialTheme
                    .typography
                    .labelSmall,
            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )

        Text(
            value,
            style =
                MaterialTheme
                    .typography
                    .titleMedium,
            fontWeight =
                FontWeight.Bold,
            maxLines =
                1,
            overflow =
                TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsPageHeader(
    title: String,
    description: String
) {
    Text(
        title,
        style =
            MaterialTheme
                .typography
                .headlineSmall,
        fontWeight =
            FontWeight.Bold
    )

    Spacer(
        Modifier.height(
            4.dp
        )
    )

    Text(
        description,
        style =
            MaterialTheme
                .typography
                .bodyMedium,
        color =
            MaterialTheme
                .colorScheme
                .onSurfaceVariant
    )

    Spacer(
        Modifier.height(
            16.dp
        )
    )
}

@Composable
private fun SettingsChoiceRow(
    selected: Boolean,
    title: String,
    subtitle: String? =
        null,
    onClick: () -> Unit
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(
                    onClick =
                        onClick
                ),
        shape =
            RoundedCornerShape(
                14.dp
            ),
        tonalElevation =
            if (
                selected
            ) {
                3.dp
            } else {
                0.dp
            },
        color =
            if (
                selected
            ) {
                MaterialTheme
                    .colorScheme
                    .primaryContainer
            } else {
                MaterialTheme
                    .colorScheme
                    .surfaceVariant
                    .copy(
                        alpha =
                            0.45f
                    )
            }
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal =
                            14.dp,
                        vertical =
                            10.dp
                    ),
            verticalAlignment =
                Alignment.CenterVertically
        ) {
            RadioButton(
                selected =
                    selected,
                onClick =
                    onClick
            )

            Spacer(
                Modifier.width(
                    8.dp
                )
            )

            Column(
                modifier =
                    Modifier.weight(
                        1f
                    )
            ) {
                Text(
                    title,
                    style =
                        MaterialTheme
                            .typography
                            .titleMedium,
                    fontWeight =
                        FontWeight.SemiBold
                )

                if (
                    !subtitle
                        .isNullOrBlank()
                ) {
                    Spacer(
                        Modifier.height(
                            2.dp
                        )
                    )

                    Text(
                        subtitle,
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SensorSettingsScreen(
    selectedSerial: String?,
    onChangeSensor: () -> Unit,
    onUnselectSensor: () -> Unit,
    onStartNewSensor: () -> Unit
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(
                    top =
                        4.dp
                )
    ) {
        SettingsPageHeader(
            title =
                "Sensor",
            description =
                if (
                    selectedSerial !=
                    null
                ) {
                    "Current sensor: $selectedSerial"
                } else {
                    "No AiDEX X sensor is currently selected."
                }
        )

        Button(
            onClick =
                onChangeSensor,
            enabled =
                selectedSerial !=
                    null,
            modifier =
                Modifier.fillMaxWidth()
        ) {
            Text(
                "Change Sensor"
            )
        }

        Spacer(
            Modifier.height(
                10.dp
            )
        )

        OutlinedButton(
            onClick =
                onUnselectSensor,
            enabled =
                selectedSerial !=
                    null,
            modifier =
                Modifier.fillMaxWidth()
        ) {
            Text(
                "Unselect Sensor"
            )
        }

        Spacer(
            Modifier.height(
                18.dp
            )
        )

        HorizontalDivider()

        Spacer(
            Modifier.height(
                18.dp
            )
        )

        Text(
            "New sensor",
            style =
                MaterialTheme
                    .typography
                    .titleMedium,
            fontWeight =
                FontWeight.SemiBold
        )

        Spacer(
            Modifier.height(
                6.dp
            )
        )

        Text(
            "Use this only when installing and activating a new AiDEX X sensor.",
            style =
                MaterialTheme
                    .typography
                    .bodySmall,
            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )

        Spacer(
            Modifier.height(
                10.dp
            )
        )

        Button(
            onClick =
                onStartNewSensor,
            modifier =
                Modifier.fillMaxWidth()
        ) {
            Text(
                "Start New Sensor"
            )
        }
    }
}

@Composable
private fun GlucoseSettingsScreen(
    glucoseUnit: GlucoseUnit,
    glucoseLimits: GlucoseLimits,
    onUnitChanged:
        (
            GlucoseUnit
        ) -> Unit,
    onEditLimits: () -> Unit
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(
                    top =
                        4.dp
                )
    ) {
        SettingsPageHeader(
            title =
                "Glucose",
            description =
                "Choose how glucose is displayed and configure your target limits."
        )

        Text(
            "Glucose Units",
            style =
                MaterialTheme
                    .typography
                    .titleMedium,
            fontWeight =
                FontWeight.SemiBold
        )

        Spacer(
            Modifier.height(
                8.dp
            )
        )

        SettingsChoiceRow(
            selected =
                glucoseUnit ==
                    GlucoseUnit.MMOL_L,
            title =
                "mmol/L",
            subtitle =
                "Millimoles per litre",
            onClick = {
                onUnitChanged(
                    GlucoseUnit.MMOL_L
                )
            }
        )

        Spacer(
            Modifier.height(
                8.dp
            )
        )

        SettingsChoiceRow(
            selected =
                glucoseUnit ==
                    GlucoseUnit.MG_DL,
            title =
                "mg/dL",
            subtitle =
                "Milligrams per decilitre",
            onClick = {
                onUnitChanged(
                    GlucoseUnit.MG_DL
                )
            }
        )

        Spacer(
            Modifier.height(
                18.dp
            )
        )

        HorizontalDivider()

        Spacer(
            Modifier.height(
                18.dp
            )
        )

        Text(
            "Glucose Limits",
            style =
                MaterialTheme
                    .typography
                    .titleMedium,
            fontWeight =
                FontWeight.SemiBold
        )

        Spacer(
            Modifier.height(
                6.dp
            )
        )

        Text(
            "Current range: ${
                glucoseUnit
                    .formatValueFromMmol(
                        glucoseLimits.lowMmol
                    )
            } – ${
                glucoseUnit
                    .formatValueFromMmol(
                        glucoseLimits.highMmol
                    )
            } ${glucoseUnit.label}",
            style =
                MaterialTheme
                    .typography
                    .bodyMedium
        )

        Spacer(
            Modifier.height(
                10.dp
            )
        )

        OutlinedButton(
            onClick =
                onEditLimits,
            modifier =
                Modifier.fillMaxWidth()
        ) {
            Text(
                "Edit Glucose Limits"
            )
        }
    }
}

@Composable
private fun AppearanceSettingsScreen(themeMode: AppThemeMode, onThemeChanged: (AppThemeMode)->Unit) {
    TrailThemePicker(themeMode, onThemeChanged)
}

@Composable
private fun HomeScreen(
    vm: AidexViewModel,
    selectedSerial: String?,
    selectedSensor: AidexSensor?,
    history: List<AidexReading>,
    notes: List<GlucoseNote>,
    glucoseLimits: GlucoseLimits,
    glucoseUnit: GlucoseUnit,
    onSelectExisting: () -> Unit,
    onOpenNotes: () -> Unit,
    onCompleteActivation: () -> Unit
) {
    if (selectedSerial == null) {
        Column {
            Text("No sensor selected", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Choose an already-active AiDEX X sensor to show glucose on this dashboard.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onSelectExisting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Text("Select Existing Sensor")
            }
        }
        return
    }

    if (selectedSensor?.reading == null) {
        var rangeHours by remember(
            selectedSerial
        ) {
            mutableIntStateOf(
                4
            )
        }

        val context =
            LocalContext.current

        var monitoringSnapshot by remember(
            selectedSerial
        ) {
            mutableStateOf(
                ContinuousMonitoringStatus.read(
                    context
                )
            )
        }

        LaunchedEffect(
            selectedSerial
        ) {
            while (
                true
            ) {
                monitoringSnapshot =
                    ContinuousMonitoringStatus.read(
                        context
                    )

                delay(
                    1_000L
                )
            }
        }

        val secureSessionReady =
            monitoringSnapshot.liveGattLastF002Response >=
                0

        val sensorStateReady =
            monitoringSnapshot.liveGattLastF002Response ==
                0x122

        val f003SubscriptionReady =
            monitoringSnapshot.liveGattF003LocalNotifyEnabled ==
                true &&
                monitoringSnapshot.liveGattF003CccdWriteStatus ==
                    0

        val activationRequired =
            monitoringSnapshot
                .liveGattSensorActivationState ==
                "UNINITIALIZED" ||
                monitoringSnapshot
                    .liveGattStage ==
                "ACTIVATION_REQUIRED"

        val latestHistoricalReading =
            history
                .asSequence()
                .filter {
                    it.crcValid &&
                        it.valid &&
                        !it.isWarmup
                }
                .maxByOrNull {
                    it.receivedAtMs
                }

        Column(
            modifier =
                Modifier.fillMaxSize(),
            verticalArrangement =
                Arrangement.spacedBy(
                    8.dp
                )
        ) {
            Card(
                Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(
                        14.dp
                    )
                ) {
                    Text(
                        "AiDEX X-$selectedSerial",
                        style =
                            MaterialTheme.typography.titleMedium,
                        fontWeight =
                            FontWeight.Bold
                    )
                    Spacer(
                        Modifier.height(
                            6.dp
                        )
                    )
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth()
                    )
                    Spacer(
                        Modifier.height(
                            8.dp
                        )
                    )
                    Text(
                        if (
                            activationRequired
                        ) {
                            "Sensor activation incomplete"
                        } else {
                            "Connecting securely to sensor…"
                        },
                        fontWeight =
                            FontWeight.SemiBold
                    )
                    if (
                        activationRequired
                    ) {
                        Text(
                            "BLE ✓  •  Secure ✓  •  Start time: Uninitialized",
                            style =
                                MaterialTheme.typography.bodySmall,
                            fontWeight =
                                FontWeight.SemiBold
                        )

                        Text(
                            "GlucoTrail read the sensor's secure start-time state and it reports no initialized start time. This sensor will not produce normal F003 glucose until startup is completed.",
                            style =
                                MaterialTheme.typography.bodySmall
                        )

                        Spacer(
                            Modifier.height(
                                6.dp
                            )
                        )

                        Button(
                            onClick =
                                onCompleteActivation,
                            modifier =
                                Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Complete Sensor Activation"
                            )
                        }
                    } else {
                        Text(
                            "BLE ${
                                if (
                                    monitoringSnapshot.liveGattConnected
                                ) {
                                    "✓"
                                } else {
                                    "…"
                                }
                            }  •  Secure ${
                                if (
                                    secureSessionReady
                                ) {
                                    "✓"
                                } else {
                                    "…"
                                }
                            }  •  State ${
                                if (
                                    sensorStateReady
                                ) {
                                    "✓"
                                } else {
                                    "…"
                                }
                            }  •  F003 ${
                                if (
                                    f003SubscriptionReady
                                ) {
                                    "✓"
                                } else {
                                    "…"
                                }
                            }  •  Glucose ${
                                if (
                                    monitoringSnapshot.liveGattStreaming
                                ) {
                                    "✓"
                                } else {
                                    "Waiting"
                                }
                            }",
                            style =
                                MaterialTheme.typography.bodySmall,
                            fontWeight =
                                FontWeight.SemiBold
                        )
                        Text(
                            monitoringSnapshot.liveGattMessage
                                ?: "Waiting for a CRC-verified F003 current reading. Previous sensor history remains available below while the secure session finishes.",
                            style =
                                MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (
                latestHistoricalReading !=
                null
            ) {
                Card(
                    modifier =
                        Modifier.fillMaxWidth(),
                    shape =
                        RoundedCornerShape(
                            18.dp
                        )
                ) {
                    Column(
                        modifier =
                            Modifier.padding(
                                horizontal = 10.dp,
                                vertical = 9.dp
                            )
                    ) {
                        Row(
                            modifier =
                                Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                Arrangement.spacedBy(
                                    3.dp
                                )
                        ) {
                            listOf(
                                2,
                                4,
                                6,
                                12,
                                24,
                                48,
                                72, 168
                            ).forEach {
                                    hours ->
                                val selected =
                                    rangeHours ==
                                        hours

                                Surface(
                                    modifier =
                                        Modifier
                                            .weight(
                                                1f
                                            )
                                            .height(
                                                22.dp
                                            )
                                            .clickable {
                                                rangeHours =
                                                    hours
                                            },
                                    shape =
                                        RoundedCornerShape(
                                            6.dp
                                        ),
                                    color =
                                        if (
                                            selected
                                        ) {
                                            MaterialTheme
                                                .colorScheme
                                                .primaryContainer
                                        } else {
                                            MaterialTheme
                                                .colorScheme
                                                .surfaceVariant
                                        }
                                ) {
                                    Box(
                                        modifier =
                                            Modifier.fillMaxSize(),
                                        contentAlignment =
                                            Alignment.Center
                                    ) {
                                        Text(
                                            if (hours == 168) "7d" else "${hours}h",
                                            style =
                                                MaterialTheme
                                                    .typography
                                                    .labelSmall
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(
                            Modifier.height(
                                6.dp
                            )
                        )

                        GlucoseHistoryChart(
                            history =
                                history,
                            notes =
                                notes,
                            rangeHours =
                                rangeHours,
                            currentReading =
                                latestHistoricalReading,
                            glucoseLimits =
                                glucoseLimits,
                            glucoseUnit =
                                glucoseUnit,
                            historyRecoveryActive =
                                false,
                            historyRecoveryMessage =
                                "New sensor is connecting",
                            onRecentGapDetected =
                                {},
                            onNoteClick = {
                                onOpenNotes()
                            },
                            allowRecentGapRecovery =
                                false
                        )
                    }
                }
            } else {
                Card(
                    Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(
                            14.dp
                        )
                    ) {
                        Text(
                            "No previous glucose readings are available in the current history window.",
                            style =
                                MaterialTheme.typography.bodySmall
                        )
                        TextButton(
                            onClick =
                                onOpenNotes
                        ) {
                            Text(
                                "Open Notes History"
                            )
                        }
                    }
                }
            }
        }
        return
    }

    GlucoseDashboardPager(
        vm =
            vm,
        sensor =
            selectedSensor,
        history =
            history,
        notes =
            notes,
        glucoseLimits =
            glucoseLimits,
        glucoseUnit =
            glucoseUnit
    )
}

@Composable
private fun GlucoseDashboardPager(
    vm: AidexViewModel,
    sensor: AidexSensor,
    history: List<AidexReading>,
    notes: List<GlucoseNote>,
    glucoseLimits: GlucoseLimits,
    glucoseUnit: GlucoseUnit
) {
    /*
     * Swipe order from the default page:
     *
     * Current Glucose
     * -> Time in Range
     * -> Daily Patterns
     * -> Low Glucose Events
     * -> High Glucose Events
     * -> Very High Glucose Events
     * -> Average Glucose
     * -> Estimated A1C
     * -> Sensor Usage
     *
     * Swipe left from Current Glucose for Blood Pressure, Weight, then Fitness.
     * A rightward finger swipe moves toward a lower pager index.
     */
    val pagerState =
        rememberPagerState(
            initialPage =
                8,
            pageCount = {
                12
            }
        )

    val reportScope = rememberCoroutineScope()

    val historyRecovery by
        vm.historyAutoRecoveryState
            .collectAsState()

    HorizontalPager(
        state =
            pagerState,
        modifier =
            Modifier.fillMaxSize(),
        beyondViewportPageCount =
            1
    ) {
            page ->
        when (
            page
        ) {
            0 ->
                SensorUsageReportScreen(
                    vm =
                        vm,
                    sensor =
                        sensor,
                    history =
                        history
                )

            1 ->
                EstimatedA1cReportScreen(
                    vm =
                        vm,
                    sensor =
                        sensor,
                    history =
                        history,
                    glucoseLimits =
                        glucoseLimits,
                    glucoseUnit =
                        glucoseUnit
                )

            2 ->
                AverageGlucoseReportScreen(
                    vm =
                        vm,
                    sensor =
                        sensor,
                    history =
                        history,
                    glucoseLimits =
                        glucoseLimits,
                    glucoseUnit =
                        glucoseUnit
                )

            3 ->
                VeryHighGlucoseEventsScreen(
                    vm =
                        vm,
                    sensor =
                        sensor,
                    history =
                        history,
                    glucoseLimits =
                        glucoseLimits,
                    glucoseUnit =
                        glucoseUnit
                )

            4 ->
                HighGlucoseEventsScreen(
                    vm =
                        vm,
                    sensor =
                        sensor,
                    history =
                        history,
                    glucoseLimits =
                        glucoseLimits,
                    glucoseUnit =
                        glucoseUnit
                )

            5 ->
                LowGlucoseEventsScreen(
                    vm =
                        vm,
                    sensor =
                        sensor,
                    history =
                        history,
                    glucoseLimits =
                        glucoseLimits,
                    glucoseUnit =
                        glucoseUnit
                )

            6 ->
                DailyPatternsScreen(
                    vm =
                        vm,
                    sensor =
                        sensor,
                    history =
                        history,
                    glucoseLimits =
                        glucoseLimits,
                    glucoseUnit =
                        glucoseUnit
                )

            7 ->
                TimeInRangeReportScreen(
                    vm =
                        vm,
                    sensor =
                        sensor,
                    history =
                        history,
                    glucoseLimits =
                        glucoseLimits,
                    glucoseUnit =
                        glucoseUnit
                )

            9 -> BloodPressureScreen(
                onBack = { reportScope.launch { pagerState.animateScrollToPage(8) } },
                backLabel = "‹ Glucose",
                reportActive = pagerState.currentPage == 9,
                swipeHint = "Swipe right for Glucose · Swipe left for Weight"
            )
            11 -> FitnessScreen(onBack = { reportScope.launch { pagerState.animateScrollToPage(8) } })
            10 -> WeightScreen(
                onBack = { reportScope.launch { pagerState.animateScrollToPage(8) } },
                backLabel = "‹ Glucose",
                reportActive = pagerState.currentPage == 10,
                swipeHint = "Swipe right for Blood Pressure · Swipe left for Fitness"
            )
            else ->
                GlucoseDashboard(
                    vm =
                        vm,
                    sensor =
                        sensor,
                    history =
                        history,
                    notes =
                        notes,
                    glucoseLimits =
                        glucoseLimits,
                    glucoseUnit =
                        glucoseUnit,
                    reportActive = pagerState.currentPage == 8,
                    historyRecoveryActive =
                        historyRecovery.active,
                    historyRecoveryMessage =
                        historyRecovery.message,
                    onRecentGapDetected = {
                        vm.requestImmediateHistoryRecovery()
                    }
                )
        }
    }
}

@Composable
private fun GlucoseDashboard(
    vm: AidexViewModel,
    sensor: AidexSensor,
    history: List<AidexReading>,
    notes: List<GlucoseNote>,
    glucoseLimits: GlucoseLimits,
    glucoseUnit: GlucoseUnit,
    historyRecoveryActive: Boolean,
    historyRecoveryMessage: String,
    onRecentGapDetected: () -> Unit,
    reportActive: Boolean = true
) {
    val reading = sensor.reading ?: return

    val activationStartedAtMs by
        vm.selectedSensorActivationStartedAtMs
            .collectAsState()

    var rangeHours by remember { mutableIntStateOf(4) }
    LaunchedEffect(reportActive) { if (reportActive) rangeHours = 4 }

    var noteEditorOpen by remember {
        mutableStateOf(
            false
        )
    }

    var editingNote by remember {
        mutableStateOf<GlucoseNote?>(
            null
        )
    }

    /*
     * Keep the countdown directly below the glucose hero. Allow scrolling
     * on smaller displays so the graph and note controls remain accessible.
     */
    Column(
        modifier =
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement =
            Arrangement.spacedBy(
                8.dp
            )
    ) {
        GlucoseHero(
            vm = vm,
            sensor = sensor,
            history = history,
            notes = notes,
            glucoseLimits = glucoseLimits,
            glucoseUnit = glucoseUnit,
            activationStartedAtMs =
                activationStartedAtMs
        )

        ActiveInsulinCard(notes, compact = true)

        Card(
            modifier =
                Modifier.fillMaxWidth(),
            shape =
                RoundedCornerShape(
                    18.dp
                )
        ) {
            Column(
                modifier =
                    Modifier.padding(
                        horizontal = 10.dp,
                        vertical = 9.dp
                    )
            ) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(
                            3.dp
                        )
                ) {
                    listOf(
                        2,
                        4,
                        6,
                        12,
                        24,
                        48,
                        72, 168
                    ).forEach {
                            hours ->
                        val selected =
                            rangeHours ==
                                hours

                        Surface(
                            modifier =
                                Modifier
                                    .weight(
                                        1f
                                    )
                                    .height(
                                        22.dp
                                    )
                                    .clickable {
                                        rangeHours =
                                            hours
                                    },
                            shape =
                                RoundedCornerShape(
                                    6.dp
                                ),
                            color =
                                if (
                                    selected
                                ) {
                                    MaterialTheme
                                        .colorScheme
                                        .primaryContainer
                                } else {
                                    MaterialTheme
                                        .colorScheme
                                        .surfaceVariant
                                },
                            contentColor =
                                if (
                                    selected
                                ) {
                                    MaterialTheme
                                        .colorScheme
                                        .onPrimaryContainer
                                } else {
                                    MaterialTheme
                                        .colorScheme
                                        .onSurfaceVariant
                                }
                        ) {
                            Box(
                                modifier =
                                    Modifier.fillMaxSize(),
                                contentAlignment =
                                    Alignment.Center
                            ) {
                                Text(
                                    if (hours == 168) "7d" else "${hours}h",
                                    maxLines =
                                        1,
                                    softWrap =
                                        false,
                                    style =
                                        MaterialTheme
                                            .typography
                                            .labelSmall
                                )
                            }
                        }
                    }
                }

                Spacer(
                    Modifier.height(
                        6.dp
                    )
                )

                GlucoseHistoryChart(
                    history =
                        history,
                    notes =
                        notes,
                    rangeHours =
                        rangeHours,
                    currentReading =
                        reading,
                    glucoseLimits =
                        glucoseLimits,
                    glucoseUnit =
                        glucoseUnit,
                    historyRecoveryActive =
                        historyRecoveryActive,
                    historyRecoveryMessage =
                        historyRecoveryMessage,
                    onRecentGapDetected =
                        onRecentGapDetected,
                    onNoteClick = {
                            note ->
                        editingNote =
                            note
                        noteEditorOpen =
                            true
                    }
                )

                Spacer(
                    Modifier.height(
                        5.dp
                    )
                )

                GlucoseNotesSection(
                    notes =
                        notes,
                    rangeHours =
                        rangeHours,
                    onAddNote = {
                        editingNote =
                            null
                        noteEditorOpen =
                            true
                    },
                    onNoteClick = {
                            note ->
                        editingNote =
                            note
                        noteEditorOpen =
                            true
                    }
                )
            }
        }

        Text(
            "Research build • Verify with official AiDEX/LinX • Not for treatment decisions.",
            modifier =
                Modifier.fillMaxWidth(),
            style =
                MaterialTheme
                    .typography
                    .labelSmall,
            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant,
            maxLines =
                1,
            softWrap =
                false
        )
    }

    if (
        noteEditorOpen
    ) {
        AdvancedGlucoseNoteEditorDialog(
            serial =
                sensor.serial,
            existing =
                editingNote,
            onSave = {
                    note ->
                vm.saveGlucoseNote(
                    note
                )
                noteEditorOpen =
                    false
            },
            onDelete =
                editingNote
                    ?.let {
                            note ->
                        {
                            vm.deleteGlucoseNote(
                                note
                            )
                            noteEditorOpen =
                                false
                        }
                    },
            onDismiss = {
                noteEditorOpen =
                    false
            }
        )
    }
}

@Composable
private fun EstimatedA1cReportScreen(
    vm: AidexViewModel,
    sensor: AidexSensor,
    history: List<AidexReading>,
    glucoseLimits: GlucoseLimits,
    glucoseUnit: GlucoseUnit
) {
    var selectedDays by remember {
        mutableIntStateOf(
            7
        )
    }

    val autoRecovery by
        vm.historyAutoRecoveryState
            .collectAsState()

    val report by
        produceState<
            com.aidexx.reader.data.GlucosePeriodSummaryReport?
        >(
            initialValue =
                null,
            key1 =
                sensor.serial,
            key2 =
                selectedDays,
            key3 =
                Triple(
                    history.size +
                        history
                            .lastOrNull()
                            ?.sensorMinute
                            .hashCode(),
                    glucoseLimits.lowMmol +
                        glucoseLimits.highMmol,
                    autoRecovery
                        .lastCompletedMs
                )
        ) {
            value =
                vm.loadGlucosePeriodSummaryReport(
                    selectedDays
                )
        }

    val currentReport =
        report

    val estimate =
        currentReport
            ?.overallAverageMgDl
            ?.takeIf {
                it >
                0.0
            }
            ?.let {
                A1cCalculator
                    .fromAverageMgDl(
                        it
                    )
            }

    LazyColumn(
        modifier =
            Modifier.fillMaxSize(),
        verticalArrangement =
            Arrangement.spacedBy(
                12.dp
            ),
        contentPadding =
            PaddingValues(
                bottom =
                    28.dp
            )
    ) {
        item {
            Column(
                verticalArrangement =
                    Arrangement.spacedBy(
                        2.dp
                    )
            ) {
                Text(
                    "Estimated A1C",
                    style =
                        MaterialTheme
                            .typography
                            .headlineSmall,
                    fontWeight =
                        FontWeight.Bold
                )

                Text(
                    "← Swipe right for Sensor Usage",
                    style =
                        MaterialTheme
                            .typography
                            .labelMedium,
                    color =
                        MaterialTheme
                            .colorScheme
                            .primary
                )

                Text(
                    "Swipe left for Average Glucose →",
                    style =
                        MaterialTheme
                            .typography
                            .labelSmall,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }
        }

        item {
            Card(
                modifier =
                    Modifier.fillMaxWidth(),
                shape =
                    RoundedCornerShape(
                        18.dp
                    )
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal =
                                    18.dp,
                                vertical =
                                    22.dp
                            ),
                    horizontalAlignment =
                        Alignment.CenterHorizontally,
                    verticalArrangement =
                        Arrangement.spacedBy(
                            16.dp
                        )
                ) {
                    if (
                        currentReport ==
                        null
                    ) {
                        CircularProgressIndicator()

                        Text(
                            "Calculating ${selectedDays}-day Estimated A1C…",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodyMedium
                        )
                    } else {
                        Text(
                            "${
                                DateFormat
                                    .getDateInstance(
                                        DateFormat.MEDIUM
                                    )
                                    .format(
                                        Date(
                                            currentReport
                                                .windowStartMs
                                        )
                                    )
                            } – ${
                                DateFormat
                                    .getDateInstance(
                                        DateFormat.MEDIUM
                                    )
                                    .format(
                                        Date(
                                            currentReport
                                                .windowEndMs
                                        )
                                    )
                            }",
                            modifier =
                                Modifier.fillMaxWidth(),
                            style =
                                MaterialTheme
                                    .typography
                                    .titleMedium,
                            fontWeight =
                                FontWeight.SemiBold,
                            textAlign =
                                TextAlign.Center
                        )

                        Spacer(
                            Modifier.height(
                                18.dp
                            )
                        )

                        if (
                            estimate ==
                            null
                        ) {
                            Text(
                                "No accepted glucose history is available yet.",
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodyLarge,
                                textAlign =
                                    TextAlign.Center
                            )
                        } else {
                            Text(
                                String.format(
                                    Locale.getDefault(),
                                    "%.1f%%",
                                    estimate
                                        .gmiPercent
                                ),
                                style =
                                    MaterialTheme
                                        .typography
                                        .displayLarge,
                                fontWeight =
                                    FontWeight.Medium
                            )

                            Text(
                                "(${
                                    glucoseUnit
                                        .formatGlucoseFromMgDl(
                                            estimate
                                                .averageMgDl
                                        )
                                } average)",
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodyMedium,
                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .onSurfaceVariant
                            )
                        }

                        Spacer(
                            Modifier.height(
                                22.dp
                            )
                        )

                        Text(
                            "Data spans ${currentReport.availableDays} of ${currentReport.days} days",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodyMedium,
                            color =
                                if (
                                    currentReport
                                        .availableDays <
                                    currentReport
                                        .days
                                ) {
                                    MaterialTheme
                                        .colorScheme
                                        .error
                                } else {
                                    MaterialTheme
                                        .colorScheme
                                        .primary
                                },
                            textAlign =
                                TextAlign.Center
                        )

                        Text(
                            "${currentReport.totalReadings} accepted glucose readings",
                            style =
                                MaterialTheme
                                    .typography
                                    .labelSmall,
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            ReportDaySelector(
                selectedDays =
                    selectedDays,
                onSelected = {
                    selectedDays =
                        it
                }
            )
        }

        item {
            Text(
                "Estimated A1C uses the CGM Glucose Management Indicator (GMI) equation. It is an estimate from average glucose and may differ from a laboratory HbA1c result.",
                style =
                    MaterialTheme
                        .typography
                        .bodySmall,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SensorUsageReportScreen(
    vm: AidexViewModel,
    sensor: AidexSensor,
    history: List<AidexReading>
) {
    var selectedDays by remember {
        mutableIntStateOf(
            7
        )
    }

    val autoRecovery by
        vm.historyAutoRecoveryState
            .collectAsState()

    val report by
        produceState<
            com.aidexx.reader.data.SensorUsageReport?
        >(
            initialValue =
                null,
            key1 =
                sensor.serial,
            key2 =
                selectedDays,
            key3 =
                Triple(
                    history.size,
                    history
                        .lastOrNull()
                        ?.sensorMinute
                        ?: -1,
                    autoRecovery
                        .lastCompletedMs
                )
        ) {
            value =
                vm.loadSensorUsageReport(
                    selectedDays
                )
        }

    LazyColumn(
        modifier =
            Modifier.fillMaxSize(),
        verticalArrangement =
            Arrangement.spacedBy(
                12.dp
            ),
        contentPadding =
            PaddingValues(
                bottom =
                    28.dp
            )
    ) {
        item {
            Column(
                verticalArrangement =
                    Arrangement.spacedBy(
                        2.dp
                    )
            ) {
                Text(
                    "Sensor Usage",
                    style =
                        MaterialTheme
                            .typography
                            .headlineSmall,
                    fontWeight =
                        FontWeight.Bold
                )

                Text(
                    "Swipe left for Estimated A1C →",
                    style =
                        MaterialTheme
                            .typography
                            .labelMedium,
                    color =
                        MaterialTheme
                            .colorScheme
                            .primary
                )
            }
        }

        item {
            Card(
                modifier =
                    Modifier.fillMaxWidth(),
                shape =
                    RoundedCornerShape(
                        18.dp
                    )
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal =
                                    22.dp,
                                vertical =
                                    24.dp
                            ),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            22.dp
                        )
                ) {
                    val currentReport =
                        report

                    if (
                        currentReport ==
                        null
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(
                                        300.dp
                                    ),
                            contentAlignment =
                                Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        Text(
                            "${
                                DateFormat
                                    .getDateInstance(
                                        DateFormat.MEDIUM
                                    )
                                    .format(
                                        Date(
                                            currentReport
                                                .windowStartMs
                                        )
                                    )
                            } – ${
                                DateFormat
                                    .getDateInstance(
                                        DateFormat.MEDIUM
                                    )
                                    .format(
                                        Date(
                                            currentReport
                                                .windowEndMs
                                        )
                                    )
                            }",
                            modifier =
                                Modifier.fillMaxWidth(),
                            style =
                                MaterialTheme
                                    .typography
                                    .titleMedium,
                            fontWeight =
                                FontWeight.SemiBold,
                            textAlign =
                                TextAlign.Center
                        )

                        HorizontalDivider()

                        SensorUsageMetric(
                            value =
                                currentReport
                                    .totalReadings
                                    .toString(),
                            label =
                                "Total Readings"
                        )

                        SensorUsageMetric(
                            value =
                                String.format(
                                    Locale.getDefault(),
                                    "%.0f",
                                    currentReport
                                        .readingsPerDay
                                ),
                            label =
                                "Readings / Day"
                        )

                        SensorUsageMetric(
                            value =
                                String.format(
                                    Locale.getDefault(),
                                    "%.0f%%",
                                    currentReport
                                        .capturedPercent
                                ),
                            label =
                                "of Sensor Data Captured"
                        )

                        HorizontalDivider()

                        Text(
                            "Data available for ${currentReport.availableDays} of ${currentReport.days} days",
                            modifier =
                                Modifier.fillMaxWidth(),
                            style =
                                MaterialTheme
                                    .typography
                                    .bodyMedium,
                            color =
                                if (
                                    currentReport
                                        .availableDays <
                                    currentReport
                                        .days
                                ) {
                                    MaterialTheme
                                        .colorScheme
                                        .error
                                } else {
                                    MaterialTheme
                                        .colorScheme
                                        .primary
                                },
                            textAlign =
                                TextAlign.Center
                        )

                        if (
                            currentReport
                                .expectedSensorMinutes >
                            0
                        ) {
                            Text(
                                "Captured ${currentReport.totalReadings} of approximately ${currentReport.expectedSensorMinutes} expected sensor-minute readings across all sensor sessions in this period.",
                                modifier =
                                    Modifier.fillMaxWidth(),
                                style =
                                    MaterialTheme
                                        .typography
                                        .labelSmall,
                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .onSurfaceVariant,
                                textAlign =
                                    TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        item {
            ReportDaySelector(
                selectedDays =
                    selectedDays,
                onSelected = {
                    selectedDays =
                        it
                }
            )
        }

        item {
            Text(
                "GlucoTrail receives glucose automatically, so Sensor Usage reports readings rather than manual scans. Data-captured percentage measures accepted unique sensor-minute readings across every stored sensor session in the selected period.",
                style =
                    MaterialTheme
                        .typography
                        .bodySmall,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SensorUsageMetric(
    value: String,
    label: String
) {
    Row(
        modifier =
            Modifier.fillMaxWidth(),
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        Text(
            value,
            modifier =
                Modifier.width(
                    96.dp
                ),
            style =
                MaterialTheme
                    .typography
                    .headlineMedium,
            fontWeight =
                FontWeight.Bold,
            textAlign =
                TextAlign.End
        )

        Spacer(
            Modifier.width(
                14.dp
            )
        )

        Text(
            label,
            modifier =
                Modifier.weight(
                    1f
                ),
            style =
                MaterialTheme
                    .typography
                    .bodyLarge,
            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )
    }
}

@Composable
private fun LowGlucoseEventsScreen(
    vm: AidexViewModel,
    sensor: AidexSensor,
    history: List<AidexReading>,
    glucoseLimits: GlucoseLimits,
    glucoseUnit: GlucoseUnit
) {
    GlucosePeriodBarReportScreen(
        vm =
            vm,
        sensor =
            sensor,
        history =
            history,
        glucoseLimits =
            glucoseLimits,
        glucoseUnit =
            glucoseUnit,
        mode =
            GlucosePeriodReportMode.LOW_EVENTS,
        initialDays =
            7,
        leftHint =
            "← Swipe right for High Glucose Events",
        rightHint =
            "Swipe left for Daily Patterns →"
    )
}

@Composable
private fun HighGlucoseEventsScreen(
    vm: AidexViewModel,
    sensor: AidexSensor,
    history: List<AidexReading>,
    glucoseLimits: GlucoseLimits,
    glucoseUnit: GlucoseUnit
) {
    GlucosePeriodBarReportScreen(
        vm =
            vm,
        sensor =
            sensor,
        history =
            history,
        glucoseLimits =
            glucoseLimits,
        glucoseUnit =
            glucoseUnit,
        mode =
            GlucosePeriodReportMode.HIGH_EVENTS,
        initialDays =
            7,
        leftHint =
            "← Swipe right for Very High Glucose Events",
        rightHint =
            "Swipe left for Low Glucose Events →"
    )
}

@Composable
private fun VeryHighGlucoseEventsScreen(
    vm: AidexViewModel,
    sensor: AidexSensor,
    history: List<AidexReading>,
    glucoseLimits: GlucoseLimits,
    glucoseUnit: GlucoseUnit
) {
    GlucosePeriodBarReportScreen(
        vm =
            vm,
        sensor =
            sensor,
        history =
            history,
        glucoseLimits =
            glucoseLimits,
        glucoseUnit =
            glucoseUnit,
        mode =
            GlucosePeriodReportMode.VERY_HIGH_EVENTS,
        initialDays =
            7,
        leftHint =
            "← Swipe right for Average Glucose",
        rightHint =
            "Swipe left for High Glucose Events →"
    )
}

@Composable
private fun AverageGlucoseReportScreen(
    vm: AidexViewModel,
    sensor: AidexSensor,
    history: List<AidexReading>,
    glucoseLimits: GlucoseLimits,
    glucoseUnit: GlucoseUnit
) {
    GlucosePeriodBarReportScreen(
        vm =
            vm,
        sensor =
            sensor,
        history =
            history,
        glucoseLimits =
            glucoseLimits,
        glucoseUnit =
            glucoseUnit,
        mode =
            GlucosePeriodReportMode.AVERAGE_GLUCOSE,
        initialDays =
            7,
        leftHint =
            "← Swipe right for Estimated A1C",
        rightHint =
            "Swipe left for Very High Glucose Events →"
    )
}

private enum class GlucosePeriodReportMode {
    LOW_EVENTS,
    HIGH_EVENTS,
    VERY_HIGH_EVENTS,
    AVERAGE_GLUCOSE
}

@Composable
private fun GlucosePeriodBarReportScreen(
    vm: AidexViewModel,
    sensor: AidexSensor,
    history: List<AidexReading>,
    glucoseLimits: GlucoseLimits,
    glucoseUnit: GlucoseUnit,
    mode: GlucosePeriodReportMode,
    initialDays: Int,
    leftHint: String?,
    rightHint: String?
) {
    var selectedDays by remember(
        mode
    ) {
        mutableIntStateOf(
            initialDays
        )
    }

    val autoRecovery by
        vm.historyAutoRecoveryState
            .collectAsState()

    val report by
        produceState<
            com.aidexx.reader.data.GlucosePeriodSummaryReport?
        >(
            initialValue =
                null,
            key1 =
                sensor.serial,
            key2 =
                selectedDays,
            key3 =
                Triple(
                    history.size +
                        history
                            .lastOrNull()
                            ?.sensorMinute
                            .hashCode(),
                    glucoseLimits.lowMmol +
                        glucoseLimits.highMmol,
                    autoRecovery
                        .lastCompletedMs
                )
        ) {
            value =
                vm.loadGlucosePeriodSummaryReport(
                    selectedDays
                )
        }

    val title =
        when (
            mode
        ) {
            GlucosePeriodReportMode
                .LOW_EVENTS ->
                "Low Glucose Events"

            GlucosePeriodReportMode
                .HIGH_EVENTS ->
                "High Glucose Events"

            GlucosePeriodReportMode
                .VERY_HIGH_EVENTS ->
                "Very High Glucose Events"

            GlucosePeriodReportMode
                .AVERAGE_GLUCOSE ->
                "Average Glucose"
        }

    LazyColumn(
        modifier =
            Modifier.fillMaxSize(),
        verticalArrangement =
            Arrangement.spacedBy(
                12.dp
            ),
        contentPadding =
            PaddingValues(
                bottom =
                    28.dp
            )
    ) {
        item {
            Column(
                verticalArrangement =
                    Arrangement.spacedBy(
                        2.dp
                    )
            ) {
                Text(
                    title,
                    style =
                        MaterialTheme
                            .typography
                            .headlineSmall,
                    fontWeight =
                        FontWeight.Bold
                )

                leftHint
                    ?.let {
                        Text(
                            it,
                            style =
                                MaterialTheme
                                    .typography
                                    .labelMedium,
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .primary
                        )
                    }

                rightHint
                    ?.let {
                        Text(
                            it,
                            style =
                                MaterialTheme
                                    .typography
                                    .labelSmall,
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant
                        )
                    }
            }
        }

        item {
            Card(
                modifier =
                    Modifier.fillMaxWidth(),
                shape =
                    RoundedCornerShape(
                        18.dp
                    )
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                14.dp
                            ),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            14.dp
                        )
                ) {
                    val currentReport =
                        report

                    if (
                        currentReport ==
                        null
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(
                                        340.dp
                                    ),
                            contentAlignment =
                                Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment =
                                    Alignment.CenterHorizontally,
                                verticalArrangement =
                                    Arrangement.spacedBy(
                                        10.dp
                                    )
                            ) {
                                CircularProgressIndicator()

                                Text(
                                    "Calculating ${selectedDays}-day $title…",
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodyMedium
                                )
                            }
                        }
                    } else {
                        Text(
                            "${
                                DateFormat
                                    .getDateInstance(
                                        DateFormat.MEDIUM
                                    )
                                    .format(
                                        Date(
                                            currentReport
                                                .windowStartMs
                                        )
                                    )
                            } – ${
                                DateFormat
                                    .getDateInstance(
                                        DateFormat.MEDIUM
                                    )
                                    .format(
                                        Date(
                                            currentReport
                                                .windowEndMs
                                        )
                                    )
                            }",
                            modifier =
                                Modifier.fillMaxWidth(),
                            style =
                                MaterialTheme
                                    .typography
                                    .titleMedium,
                            fontWeight =
                                FontWeight.SemiBold,
                            textAlign =
                                TextAlign.Center
                        )

                        when (
                            mode
                        ) {
                            GlucosePeriodReportMode
                                .LOW_EVENTS ->
                                GlucoseEventBarChart(
                                    buckets =
                                        currentReport
                                            .buckets,
                                    eventSelector = {
                                        it.lowEvents
                                    },
                                    barColor =
                                        Color(
                                            0xFFE53935
                                        ),
                                    emptyText =
                                        "No low-glucose events were found in this period."
                                )

                            GlucosePeriodReportMode
                                .HIGH_EVENTS ->
                                GlucoseEventBarChart(
                                    buckets =
                                        currentReport
                                            .buckets,
                                    eventSelector = {
                                        it.highEvents
                                    },
                                    barColor =
                                        Color(
                                            0xFFFFD54F
                                        ),
                                    emptyText =
                                        "No high-glucose events were found in this period."
                                )

                            GlucosePeriodReportMode
                                .VERY_HIGH_EVENTS ->
                                GlucoseEventBarChart(
                                    buckets =
                                        currentReport
                                            .buckets,
                                    eventSelector = {
                                        it.veryHighEvents
                                    },
                                    barColor =
                                        Color(
                                            0xFFFF8A33
                                        ),
                                    emptyText =
                                        "No very-high-glucose events were found in this period."
                                )

                            GlucosePeriodReportMode
                                .AVERAGE_GLUCOSE ->
                                AverageGlucoseBarChart(
                                    buckets =
                                        currentReport
                                            .buckets,
                                    glucoseLimits =
                                        glucoseLimits,
                                    glucoseUnit =
                                        glucoseUnit
                                )
                        }

                        when (
                            mode
                        ) {
                            GlucosePeriodReportMode
                                .LOW_EVENTS -> {
                                Text(
                                    "Total Events: ${currentReport.totalLowEvents}",
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    style =
                                        MaterialTheme
                                            .typography
                                            .titleMedium,
                                    fontWeight =
                                        FontWeight.Bold,
                                    textAlign =
                                        TextAlign.Center
                                )

                                Text(
                                    "Low threshold: ≤ ${
                                        glucoseUnit
                                            .formatValueFromMmol(
                                                glucoseLimits
                                                    .lowMmol
                                            )
                                    } ${glucoseUnit.label}",
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall,
                                    textAlign =
                                        TextAlign.Center
                                )
                            }

                            GlucosePeriodReportMode
                                .HIGH_EVENTS -> {
                                Text(
                                    "Total Events: ${currentReport.totalHighEvents}",
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    style =
                                        MaterialTheme
                                            .typography
                                            .titleMedium,
                                    fontWeight =
                                        FontWeight.Bold,
                                    textAlign =
                                        TextAlign.Center
                                )

                                Text(
                                    "High threshold: > ${
                                        glucoseUnit
                                            .formatValueFromMmol(
                                                glucoseLimits
                                                    .highMmol
                                            )
                                    } ${glucoseUnit.label}",
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall,
                                    textAlign =
                                        TextAlign.Center
                                )
                            }

                            GlucosePeriodReportMode
                                .VERY_HIGH_EVENTS -> {
                                Text(
                                    "Total Events: ${currentReport.totalVeryHighEvents}",
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    style =
                                        MaterialTheme
                                            .typography
                                            .titleMedium,
                                    fontWeight =
                                        FontWeight.Bold,
                                    textAlign =
                                        TextAlign.Center
                                )

                                Text(
                                    "Very High threshold: ≥ ${
                                        glucoseUnit
                                            .formatValueFromMmol(
                                                13.4
                                            )
                                    } ${glucoseUnit.label}",
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall,
                                    textAlign =
                                        TextAlign.Center
                                )
                            }

                            GlucosePeriodReportMode
                                .AVERAGE_GLUCOSE -> {
                                Text(
                                    "Average: ${
                                        currentReport
                                            .overallAverageMgDl
                                            ?.let {
                                                glucoseUnit
                                                    .formatGlucoseFromMgDl(
                                                        it
                                                    )
                                            }
                                            ?: "No data"
                                    }",
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    style =
                                        MaterialTheme
                                            .typography
                                            .titleMedium,
                                    fontWeight =
                                        FontWeight.Bold,
                                    textAlign =
                                        TextAlign.Center
                                )
                            }
                        }

                        Text(
                            "Data available for ${currentReport.availableDays} of ${currentReport.days} days",
                            modifier =
                                Modifier.fillMaxWidth(),
                            style =
                                MaterialTheme
                                    .typography
                                    .bodyMedium,
                            color =
                                if (
                                    currentReport
                                        .availableDays <
                                    currentReport
                                        .days
                                ) {
                                    MaterialTheme
                                        .colorScheme
                                        .error
                                } else {
                                    MaterialTheme
                                        .colorScheme
                                        .primary
                                },
                            textAlign =
                                TextAlign.Center
                        )

                        Text(
                            "${currentReport.totalReadings} accepted glucose readings",
                            modifier =
                                Modifier.fillMaxWidth(),
                            style =
                                MaterialTheme
                                    .typography
                                    .labelSmall,
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant,
                            textAlign =
                                TextAlign.Center
                        )
                    }
                }
            }
        }

        item {
            ReportDaySelector(
                selectedDays =
                    selectedDays,
                onSelected = {
                    selectedDays =
                        it
                }
            )
        }

        item {
            Text(
                when (
                    mode
                ) {
                    GlucosePeriodReportMode
                        .LOW_EVENTS ->
                        "A low event is counted once when accepted glucose enters the configured Low range. Remaining low for multiple readings is still one event."

                    GlucosePeriodReportMode
                        .HIGH_EVENTS ->
                        "A high event is counted once when accepted glucose rises above the configured High limit. Remaining high for multiple readings is still one event. Very High excursions are also tracked separately."

                    GlucosePeriodReportMode
                        .VERY_HIGH_EVENTS ->
                        "A Very High event is counted once when accepted glucose reaches 13.4 mmol/L or higher. Remaining Very High for multiple readings is still one event."

                    GlucosePeriodReportMode
                        .AVERAGE_GLUCOSE ->
                        "Average Glucose groups accepted readings into eight 3-hour time-of-day periods. Bar colors use the same Low, Target, High and Very High ranges as the Current Glucose card."
                },
                style =
                    MaterialTheme
                        .typography
                        .bodySmall,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GlucoseEventBarChart(
    buckets:
        List<com.aidexx.reader.data.GlucosePeriodBucket>,
    eventSelector:
        (com.aidexx.reader.data.GlucosePeriodBucket) -> Int,
    barColor: Color,
    emptyText: String
) {
    val values =
        buckets.map(
            eventSelector
        )

    val maxValue =
        values.maxOrNull()
            ?: 0

    if (
        maxValue <=
        0
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(
                        270.dp
                    ),
            contentAlignment =
                Alignment.Center
        ) {
            Text(
                emptyText,
                style =
                    MaterialTheme
                        .typography
                        .bodyMedium,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant,
                textAlign =
                    TextAlign.Center
            )
        }

        TimeBlockLabels()
        return
    }

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(
                    290.dp
                )
    ) {
        val availableBarHeight =
            maxHeight -
                44.dp

        Row(
            modifier =
                Modifier.fillMaxSize(),
            horizontalArrangement =
                Arrangement.spacedBy(
                    4.dp
                ),
            verticalAlignment =
                Alignment.Bottom
        ) {
            buckets.forEach {
                    bucket ->
                val value =
                    eventSelector(
                        bucket
                    )

                Column(
                    modifier =
                        Modifier
                            .weight(
                                1f
                            )
                            .fillMaxHeight(),
                    horizontalAlignment =
                        Alignment.CenterHorizontally,
                    verticalArrangement =
                        Arrangement.Bottom
                ) {
                    Text(
                        if (
                            value >
                            0
                        ) {
                            value.toString()
                        } else {
                            ""
                        },
                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,
                        fontWeight =
                            FontWeight.Bold
                    )

                    Spacer(
                        Modifier.height(
                            4.dp
                        )
                    )

                    Box(
                        modifier =
                            Modifier
                                .widthIn(
                                    max =
                                        34.dp
                                )
                                .fillMaxWidth(
                                    0.72f
                                )
                                .height(
                                    if (
                                        value >
                                        0
                                    ) {
                                        availableBarHeight *
                                            (
                                                value
                                                    .toFloat() /
                                                    maxValue
                                                        .toFloat()
                                            )
                                    } else {
                                        0.dp
                                    }
                                )
                                .background(
                                    barColor,
                                    RoundedCornerShape(
                                        topStart =
                                            4.dp,
                                        topEnd =
                                            4.dp
                                    )
                                )
                    )
                }
            }
        }
    }

    TimeBlockLabels()
}

@Composable
private fun AverageGlucoseBarChart(
    buckets:
        List<com.aidexx.reader.data.GlucosePeriodBucket>,
    glucoseLimits: GlucoseLimits,
    glucoseUnit: GlucoseUnit
) {
    val averages =
        buckets.mapNotNull {
            it.averageMgDl
        }

    if (
        averages.isEmpty()
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(
                        270.dp
                    ),
            contentAlignment =
                Alignment.Center
        ) {
            Text(
                "No accepted glucose readings are available for this period.",
                style =
                    MaterialTheme
                        .typography
                        .bodyMedium,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant,
                textAlign =
                    TextAlign.Center
            )
        }

        TimeBlockLabels()
        return
    }

    val maxDisplay =
        averages
            .maxOf {
                glucoseUnit
                    .valueFromMgDl(
                        it
                    )
            }
            .coerceAtLeast(
                glucoseUnit
                    .valueFromMmol(
                        glucoseLimits
                            .highMmol
                    )
            ) *
            1.12

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(
                    290.dp
                )
    ) {
        val availableBarHeight =
            maxHeight -
                44.dp

        Row(
            modifier =
                Modifier.fillMaxSize(),
            horizontalArrangement =
                Arrangement.spacedBy(
                    4.dp
                ),
            verticalAlignment =
                Alignment.Bottom
        ) {
            buckets.forEach {
                    bucket ->
                val average =
                    bucket.averageMgDl

                val displayAverage =
                    average
                        ?.let {
                            glucoseUnit
                                .valueFromMgDl(
                                    it
                                )
                        }

                val averageMmol =
                    average
                        ?.div(
                            18.0
                        )

                val barColor =
                    when {
                        averageMmol ==
                            null ->
                            Color.Transparent

                        averageMmol <=
                            glucoseLimits
                                .lowMmol ->
                            Color(
                                0xFFE53935
                            )

                        averageMmol <=
                            glucoseLimits
                                .highMmol ->
                            AidexGreen

                        kotlin.math.round(
                            averageMmol *
                                10.0
                        ) /
                            10.0 <
                            13.4 ->
                            Color(
                                0xFFFFD54F
                            )

                        else ->
                            Color(
                                0xFFFF8A33
                            )
                    }

                Column(
                    modifier =
                        Modifier
                            .weight(
                                1f
                            )
                            .fillMaxHeight(),
                    horizontalAlignment =
                        Alignment.CenterHorizontally,
                    verticalArrangement =
                        Arrangement.Bottom
                ) {
                    Text(
                        average
                            ?.let {
                                glucoseUnit
                                    .formatValueFromMgDl(
                                        it
                                    )
                            }
                            ?: "",
                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,
                        fontWeight =
                            FontWeight.Bold
                    )

                    Spacer(
                        Modifier.height(
                            4.dp
                        )
                    )

                    Box(
                        modifier =
                            Modifier
                                .widthIn(
                                    max =
                                        34.dp
                                )
                                .fillMaxWidth(
                                    0.72f
                                )
                                .height(
                                    if (
                                        displayAverage !=
                                        null &&
                                        maxDisplay >
                                        0.0
                                    ) {
                                        availableBarHeight *
                                            (
                                                displayAverage /
                                                    maxDisplay
                                            )
                                                .toFloat()
                                                .coerceIn(
                                                    0f,
                                                    1f
                                                )
                                    } else {
                                        0.dp
                                    }
                                )
                                .background(
                                    barColor,
                                    RoundedCornerShape(
                                        topStart =
                                            4.dp,
                                        topEnd =
                                            4.dp
                                    )
                                )
                    )
                }
            }
        }
    }

    TimeBlockLabels()
}

@Composable
private fun TimeBlockLabels() {
    Row(
        modifier =
            Modifier.fillMaxWidth(),
        horizontalArrangement =
            Arrangement.spacedBy(
                2.dp
            )
    ) {
        listOf(
            "12a",
            "3a",
            "6a",
            "9a",
            "12p",
            "3p",
            "6p",
            "9p"
        ).forEach {
                label ->
            Text(
                label,
                modifier =
                    Modifier.weight(
                        1f
                    ),
                style =
                    MaterialTheme
                        .typography
                        .labelSmall,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant,
                textAlign =
                    TextAlign.Center,
                maxLines =
                    1,
                softWrap =
                    false
            )
        }
    }
}

@Composable
private fun DailyPatternsScreen(
    vm: AidexViewModel,
    sensor: AidexSensor,
    history: List<AidexReading>,
    glucoseLimits: GlucoseLimits,
    glucoseUnit: GlucoseUnit
) {
    var selectedDays by remember {
        mutableIntStateOf(
            7
        )
    }

    val autoRecovery by
        vm.historyAutoRecoveryState
            .collectAsState()

    val report by
        produceState<
            com.aidexx.reader.data.DailyPatternReport?
        >(
            initialValue =
                null,
            key1 =
                sensor.serial,
            key2 =
                selectedDays,
            key3 =
                Triple(
                    history.size,
                    history
                        .lastOrNull()
                        ?.sensorMinute
                        ?: -1,
                    autoRecovery
                        .lastCompletedMs
                )
        ) {
            value =
                vm.loadDailyPatternReport(
                    selectedDays
                )
        }

    LazyColumn(
        modifier =
            Modifier.fillMaxSize(),
        verticalArrangement =
            Arrangement.spacedBy(
                12.dp
            ),
        contentPadding =
            PaddingValues(
                bottom =
                    28.dp
            )
    ) {
        item {
            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.SpaceBetween,
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                Column(
                    verticalArrangement =
                        Arrangement.spacedBy(
                            2.dp
                        )
                ) {
                    Text(
                        "Daily Patterns",
                        style =
                            MaterialTheme
                                .typography
                                .headlineSmall,
                        fontWeight =
                            FontWeight.Bold
                    )

                    Text(
                        "← Swipe right for Low Glucose Events",
                        style =
                            MaterialTheme
                                .typography
                                .labelMedium,
                        color =
                            MaterialTheme
                                .colorScheme
                                .primary
                    )

                    Text(
                        "Swipe left for Time in Range →",
                        style =
                            MaterialTheme
                                .typography
                                .labelMedium,
                        color =
                            MaterialTheme
                                .colorScheme
                                .primary
                    )
                }

                Text(
                    glucoseUnit.label,
                    style =
                        MaterialTheme
                            .typography
                            .labelMedium,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }
        }

        item {
            Card(
                modifier =
                    Modifier.fillMaxWidth(),
                shape =
                    RoundedCornerShape(
                        18.dp
                    )
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                14.dp
                            ),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            12.dp
                        )
                ) {
                    val currentReport =
                        report

                    if (
                        currentReport ==
                        null
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(
                                        360.dp
                                    ),
                            contentAlignment =
                                Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment =
                                    Alignment.CenterHorizontally,
                                verticalArrangement =
                                    Arrangement.spacedBy(
                                        10.dp
                                    )
                            ) {
                                CircularProgressIndicator()

                                Text(
                                    "Calculating ${selectedDays}-day Daily Patterns…",
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodyMedium
                                )
                            }
                        }
                    } else {
                        Text(
                            "${
                                DateFormat
                                    .getDateInstance(
                                        DateFormat.MEDIUM
                                    )
                                    .format(
                                        Date(
                                            currentReport
                                                .windowStartMs
                                        )
                                    )
                            } – ${
                                DateFormat
                                    .getDateInstance(
                                        DateFormat.MEDIUM
                                    )
                                    .format(
                                        Date(
                                            currentReport
                                                .windowEndMs
                                        )
                                    )
                            }",
                            modifier =
                                Modifier.fillMaxWidth(),
                            style =
                                MaterialTheme
                                    .typography
                                    .titleMedium,
                            fontWeight =
                                FontWeight.SemiBold,
                            textAlign =
                                TextAlign.Center
                        )

                        DailyPatternChart(
                            report =
                                currentReport,
                            glucoseLimits =
                                glucoseLimits,
                            glucoseUnit =
                                glucoseUnit
                        )

                        DailyPatternLegend()

                        Text(
                            "Data available for ${currentReport.availableDays} of ${currentReport.days} days",
                            modifier =
                                Modifier.fillMaxWidth(),
                            style =
                                MaterialTheme
                                    .typography
                                    .bodyMedium,
                            color =
                                if (
                                    currentReport
                                        .availableDays <
                                    currentReport
                                        .days
                                ) {
                                    MaterialTheme
                                        .colorScheme
                                        .error
                                } else {
                                    MaterialTheme
                                        .colorScheme
                                        .primary
                                },
                            textAlign =
                                TextAlign.Center
                        )

                        Text(
                            "${currentReport.totalReadings} accepted glucose readings • 30-minute time-of-day buckets",
                            modifier =
                                Modifier.fillMaxWidth(),
                            style =
                                MaterialTheme
                                    .typography
                                    .labelSmall,
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant,
                            textAlign =
                                TextAlign.Center
                        )
                    }
                }
            }
        }

        item {
            ReportDaySelector(
                selectedDays =
                    selectedDays,
                onSelected = {
                    selectedDays =
                        it
                }
            )
        }

        item {
            Text(
                "Daily Patterns summarizes glucose by time of day across the selected period. The dark line is the median, the darker band is the middle 50% of readings, and the lighter band is the 10th–90th percentile range. Missing data is not filled with invented values.",
                style =
                    MaterialTheme
                        .typography
                        .bodySmall,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DailyPatternChart(
    report:
        com.aidexx.reader.data.DailyPatternReport,
    glucoseLimits: GlucoseLimits,
    glucoseUnit: GlucoseUnit
) {
    val points =
        report.points

    if (
        points.isEmpty()
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(
                        340.dp
                    ),
            contentAlignment =
                Alignment.Center
        ) {
            Text(
                "Not enough accepted history is available for Daily Patterns yet.",
                style =
                    MaterialTheme
                        .typography
                        .bodyMedium,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant,
                textAlign =
                    TextAlign.Center
            )
        }
        return
    }

    val lowDisplay =
        glucoseUnit
            .valueFromMmol(
                glucoseLimits
                    .lowMmol
            )

    val highDisplay =
        glucoseUnit
            .valueFromMmol(
                glucoseLimits
                    .highMmol
            )

    val p10Values =
        points.map {
            glucoseUnit
                .valueFromMgDl(
                    it.p10MgDl
                )
        }

    val p90Values =
        points.map {
            glucoseUnit
                .valueFromMgDl(
                    it.p90MgDl
                )
        }

    val rawMin =
        min(
            p10Values.minOrNull()
                ?: lowDisplay,
            lowDisplay
        )

    val rawMax =
        max(
            p90Values.maxOrNull()
                ?: highDisplay,
            highDisplay
        )

    val minimumSpan =
        if (
            glucoseUnit ==
            GlucoseUnit.MMOL_L
        ) {
            4.0
        } else {
            72.0
        }

    val span =
        max(
            rawMax -
                rawMin,
            minimumSpan
        )

    val padding =
        span *
            0.12

    val chartMin =
        max(
            0.0,
            rawMin -
                padding
        )

    val chartMax =
        rawMax +
            padding

    val medianColor =
        MaterialTheme
            .colorScheme
            .onSurface

    val innerBandColor =
        Color(
            0xFF64B5F6
        )
            .copy(
                alpha =
                    0.52f
            )

    val outerBandColor =
        Color(
            0xFF90CAF9
        )
            .copy(
                alpha =
                    0.34f
            )

    val targetColor =
        AidexGreen
            .copy(
                alpha =
                    0.14f
            )

    val targetEdgeColor =
        AidexGreenDark
            .copy(
                alpha =
                    0.45f
            )

    val gridColor =
        MaterialTheme
            .colorScheme
            .outlineVariant

    val yAxisTicks =
        remember(
            chartMin,
            chartMax
        ) {
            List(
                5
            ) {
                    index ->
                chartMax -
                    (
                        (
                            chartMax -
                                chartMin
                        ) *
                            index /
                            4.0
                    )
            }
        }

    Column(
        verticalArrangement =
            Arrangement.spacedBy(
                4.dp
            )
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(
                        340.dp
                    )
        ) {
            Column(
                modifier =
                    Modifier
                        .width(
                            44.dp
                        )
                        .fillMaxHeight(),
                horizontalAlignment =
                    Alignment.Start
            ) {
                Text(
                    glucoseUnit.label,
                    style =
                        MaterialTheme
                            .typography
                            .labelSmall,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant,
                    maxLines =
                        1,
                    softWrap =
                        false
                )

                Column(
                    modifier =
                        Modifier
                            .weight(
                                1f
                            )
                            .padding(
                                vertical =
                                    8.dp
                            ),
                    verticalArrangement =
                        Arrangement.SpaceBetween
                ) {
                    yAxisTicks.forEach {
                            tick ->
                        Text(
                            formatDailyPatternAxisValue(
                                tick,
                                glucoseUnit
                            ),
                            style =
                                MaterialTheme
                                    .typography
                                    .labelSmall,
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant,
                            maxLines =
                                1,
                            softWrap =
                                false
                        )
                    }
                }
            }

            Canvas(
                modifier =
                    Modifier
                        .weight(
                            1f
                        )
                        .fillMaxHeight()
                        .padding(
                            vertical =
                                8.dp
                        )
            ) {
                val w =
                    size.width

                val h =
                    size.height

                fun xForMinute(
                    minuteOfDay: Int
                ): Float =
                    (
                        minuteOfDay
                            .coerceIn(
                                0,
                                1439
                            )
                            .toFloat() /
                            1439f
                    ) *
                        w

                fun yForDisplay(
                    value: Double
                ): Float {
                    val normalized =
                        (
                            (
                                value -
                                    chartMin
                            ) /
                                (
                                    chartMax -
                                        chartMin
                                )
                        )
                            .coerceIn(
                                0.0,
                                1.0
                            )

                    return h -
                        normalized
                            .toFloat() *
                        h
                }

                fun displayMgDl(
                    mgDl: Double
                ): Double =
                    glucoseUnit
                        .valueFromMgDl(
                            mgDl
                        )

                val targetTop =
                    yForDisplay(
                        highDisplay
                    )

                val targetBottom =
                    yForDisplay(
                        lowDisplay
                    )

                drawRect(
                    color =
                        targetColor,
                    topLeft =
                        Offset(
                            0f,
                            targetTop
                        ),
                    size =
                        androidx.compose.ui.geometry.Size(
                            width =
                                w,
                            height =
                                (
                                    targetBottom -
                                        targetTop
                                )
                                    .coerceAtLeast(
                                        0f
                                    )
                        )
                )

                drawLine(
                    color =
                        targetEdgeColor,
                    start =
                        Offset(
                            0f,
                            targetTop
                        ),
                    end =
                        Offset(
                            w,
                            targetTop
                        ),
                    strokeWidth =
                        1.5f
                )

                drawLine(
                    color =
                        targetEdgeColor,
                    start =
                        Offset(
                            0f,
                            targetBottom
                        ),
                    end =
                        Offset(
                            w,
                            targetBottom
                        ),
                    strokeWidth =
                        1.5f
                )

                repeat(
                    5
                ) {
                        index ->
                    val y =
                        h *
                            index /
                            4f

                    drawLine(
                        color =
                            gridColor,
                        start =
                            Offset(
                                0f,
                                y
                            ),
                        end =
                            Offset(
                                w,
                                y
                            ),
                        strokeWidth =
                            1f
                    )
                }

                for (
                    hour in
                    0..24 step 3
                ) {
                    val x =
                        (
                            hour /
                                24f
                        ) *
                            w

                    drawLine(
                        color =
                            gridColor.copy(
                                alpha =
                                    0.65f
                            ),
                        start =
                            Offset(
                                x,
                                0f
                            ),
                        end =
                            Offset(
                                x,
                                h
                            ),
                        strokeWidth =
                            1f
                    )
                }

                fun drawBand(
                    segment:
                        List<com.aidexx.reader.data.DailyPatternPoint>,
                    upper:
                        (com.aidexx.reader.data.DailyPatternPoint) -> Double,
                    lower:
                        (com.aidexx.reader.data.DailyPatternPoint) -> Double,
                    color: Color
                ) {
                    if (
                        segment.isEmpty()
                    ) {
                        return
                    }

                    val path =
                        Path()

                    segment.forEachIndexed {
                            index,
                            point ->
                        val x =
                            xForMinute(
                                point.minuteOfDay
                            )

                        val y =
                            yForDisplay(
                                displayMgDl(
                                    upper(
                                        point
                                    )
                                )
                            )

                        if (
                            index ==
                            0
                        ) {
                            path.moveTo(
                                x,
                                y
                            )
                        } else {
                            path.lineTo(
                                x,
                                y
                            )
                        }
                    }

                    segment
                        .asReversed()
                        .forEach {
                                point ->
                            path.lineTo(
                                xForMinute(
                                    point.minuteOfDay
                                ),
                                yForDisplay(
                                    displayMgDl(
                                        lower(
                                            point
                                        )
                                    )
                                )
                            )
                        }

                    path.close()

                    drawPath(
                        path =
                            path,
                        color =
                            color
                    )
                }

                fun drawMedian(
                    segment:
                        List<com.aidexx.reader.data.DailyPatternPoint>
                ) {
                    if (
                        segment.isEmpty()
                    ) {
                        return
                    }

                    if (
                        segment.size ==
                        1
                    ) {
                        val point =
                            segment.first()

                        drawCircle(
                            color =
                                medianColor,
                            radius =
                                4f,
                            center =
                                Offset(
                                    xForMinute(
                                        point.minuteOfDay
                                    ),
                                    yForDisplay(
                                        displayMgDl(
                                            point.medianMgDl
                                        )
                                    )
                                )
                        )
                        return
                    }

                    val path =
                        Path()

                    segment.forEachIndexed {
                            index,
                            point ->
                        val x =
                            xForMinute(
                                point.minuteOfDay
                            )

                        val y =
                            yForDisplay(
                                displayMgDl(
                                    point.medianMgDl
                                )
                            )

                        if (
                            index ==
                            0
                        ) {
                            path.moveTo(
                                x,
                                y
                            )
                        } else {
                            path.lineTo(
                                x,
                                y
                            )
                        }
                    }

                    drawPath(
                        path =
                            path,
                        color =
                            medianColor,
                        style =
                            Stroke(
                                width =
                                    5f,
                                cap =
                                    StrokeCap.Round
                            )
                    )
                }

                val segments =
                    ArrayList<
                        MutableList<com.aidexx.reader.data.DailyPatternPoint>
                    >()

                var current =
                    mutableListOf<
                        com.aidexx.reader.data.DailyPatternPoint
                    >()

                points
                    .sortedBy {
                        it.minuteOfDay
                    }
                    .forEach {
                            point ->
                        val previous =
                            current.lastOrNull()

                        if (
                            previous !=
                            null &&
                            point.minuteOfDay -
                                previous.minuteOfDay >
                            report.bucketMinutes *
                                2
                        ) {
                            if (
                                current.isNotEmpty()
                            ) {
                                segments +=
                                    current
                            }

                            current =
                                mutableListOf()
                        }

                        current +=
                            point
                    }

                if (
                    current.isNotEmpty()
                ) {
                    segments +=
                        current
                }

                segments.forEach {
                        segment ->
                    drawBand(
                        segment =
                            segment,
                        upper = {
                            it.p90MgDl
                        },
                        lower = {
                            it.p10MgDl
                        },
                        color =
                            outerBandColor
                    )

                    drawBand(
                        segment =
                            segment,
                        upper = {
                            it.p75MgDl
                        },
                        lower = {
                            it.p25MgDl
                        },
                        color =
                            innerBandColor
                    )

                    drawMedian(
                        segment
                    )
                }
            }
        }

        Row(
            modifier =
                Modifier.fillMaxWidth(),
            verticalAlignment =
                Alignment.CenterVertically
        ) {
            Spacer(
                Modifier.width(
                    44.dp
                )
            )

            listOf(
                "12a",
                "3a",
                "6a",
                "9a",
                "12p",
                "3p",
                "6p",
                "9p",
                "12a"
            ).forEach {
                    label ->
                Text(
                    label,
                    modifier =
                        Modifier.weight(
                            1f
                        ),
                    style =
                        MaterialTheme
                            .typography
                            .labelSmall,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant,
                    textAlign =
                        TextAlign.Center,
                    maxLines =
                        1,
                    softWrap =
                        false
                )
            }
        }
    }
}

@Composable
private fun DailyPatternLegend() {
    Column(
        modifier =
            Modifier.fillMaxWidth(),
        verticalArrangement =
            Arrangement.spacedBy(
                6.dp
            )
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(
                    12.dp
                )
        ) {
            DailyPatternLegendItem(
                modifier =
                    Modifier.weight(
                        1f
                    ),
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurface,
                label =
                    "Median"
            )

            DailyPatternLegendItem(
                modifier =
                    Modifier.weight(
                        1f
                    ),
                color =
                    Color(
                        0xFF64B5F6
                    )
                        .copy(
                            alpha =
                                0.52f
                        ),
                label =
                    "25–75%"
            )
        }

        Row(
            modifier =
                Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(
                    12.dp
                )
        ) {
            DailyPatternLegendItem(
                modifier =
                    Modifier.weight(
                        1f
                    ),
                color =
                    Color(
                        0xFF90CAF9
                    )
                        .copy(
                            alpha =
                                0.34f
                        ),
                label =
                    "10–90%"
            )

            DailyPatternLegendItem(
                modifier =
                    Modifier.weight(
                        1f
                    ),
                color =
                    AidexGreen.copy(
                        alpha =
                            0.24f
                    ),
                label =
                    "Target range"
            )
        }
    }
}

@Composable
private fun DailyPatternLegendItem(
    modifier: Modifier,
    color: Color,
    label: String
) {
    Row(
        modifier =
            modifier,
        verticalAlignment =
            Alignment.CenterVertically,
        horizontalArrangement =
            Arrangement.spacedBy(
                6.dp
            )
    ) {
        Box(
            modifier =
                Modifier
                    .size(
                        width =
                            20.dp,
                        height =
                            10.dp
                    )
                    .background(
                        color,
                        RoundedCornerShape(
                            3.dp
                        )
                    )
        )

        Text(
            label,
            style =
                MaterialTheme
                    .typography
                    .labelSmall,
            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )
    }
}

@Composable
private fun ReportDaySelector(
    selectedDays: Int,
    onSelected: (Int) -> Unit
) {
    Row(
        modifier =
            Modifier.fillMaxWidth(),
        horizontalArrangement =
            Arrangement.spacedBy(
                3.dp
            )
    ) {
        listOf(
            7,
            14,
            30,
            90,
            120,
            180
        ).forEach {
                days ->
            val selected =
                selectedDays ==
                    days

            Surface(
                modifier =
                    Modifier
                        .weight(
                            1f
                        )
                        .height(
                            36.dp
                        )
                        .clickable {
                            onSelected(
                                days
                            )
                        },
                shape =
                    RoundedCornerShape(
                        7.dp
                    ),
                color =
                    if (
                        selected
                    ) {
                        MaterialTheme
                            .colorScheme
                            .primaryContainer
                    } else {
                        MaterialTheme
                            .colorScheme
                            .surfaceVariant
                    },
                contentColor =
                    if (
                        selected
                    ) {
                        MaterialTheme
                            .colorScheme
                            .onPrimaryContainer
                    } else {
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                    }
            ) {
                Box(
                    modifier =
                        Modifier.fillMaxSize(),
                    contentAlignment =
                        Alignment.Center
                ) {
                    Text(
                        "${days}D",
                        maxLines =
                            1,
                        softWrap =
                            false,
                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,
                        fontWeight =
                            if (
                                selected
                            ) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            }
                    )
                }
            }
        }
    }
}

private fun formatDailyPatternAxisValue(
    value: Double,
    glucoseUnit: GlucoseUnit
): String =
    if (
        glucoseUnit ==
        GlucoseUnit.MMOL_L
    ) {
        String.format(
            Locale.getDefault(),
            "%.1f",
            value
        )
    } else {
        String.format(
            Locale.getDefault(),
            "%.0f",
            value
        )
    }

@Composable
private fun TimeInRangeReportScreen(
    vm: AidexViewModel,
    sensor: AidexSensor,
    history: List<AidexReading>,
    glucoseLimits: GlucoseLimits,
    glucoseUnit: GlucoseUnit
) {
    var selectedDays by remember {
        mutableIntStateOf(
            7
        )
    }

    val autoRecovery by
        vm.historyAutoRecoveryState
            .collectAsState()

    val report by
        produceState<
            com.aidexx.reader.data.TimeInRangeReport?
        >(
            initialValue =
                null,
            key1 =
                sensor.serial,
            key2 =
                selectedDays,
            key3 =
                Triple(
                    glucoseLimits.lowMmol,
                    glucoseLimits.highMmol,
                    history.size +
                        autoRecovery
                            .lastCompletedMs
                            .hashCode()
                )
        ) {
            value =
                vm.loadTimeInRangeReport(
                    selectedDays
                )
        }

    LazyColumn(
        modifier =
            Modifier.fillMaxSize(),
        verticalArrangement =
            Arrangement.spacedBy(
                12.dp
            ),
        contentPadding =
            PaddingValues(
                bottom =
                    28.dp
            )
    ) {
        item {
            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.SpaceBetween,
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Time in Range",
                        style =
                            MaterialTheme
                                .typography
                                .headlineSmall,
                        fontWeight =
                            FontWeight.Bold
                    )

                    Text(
                        "← Swipe right for Daily Patterns",
                        style =
                            MaterialTheme
                                .typography
                                .labelMedium,
                        color =
                            MaterialTheme
                                .colorScheme
                                .primary
                    )

                    Text(
                        "Swipe left for Current Glucose →",
                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )
                }

                Text(
                    glucoseUnit.label,
                    style =
                        MaterialTheme
                            .typography
                            .labelMedium,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }
        }

        item {
            Card(
                modifier =
                    Modifier.fillMaxWidth(),
                shape =
                    RoundedCornerShape(
                        18.dp
                    )
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                16.dp
                            ),
                    horizontalAlignment =
                        Alignment.CenterHorizontally,
                    verticalArrangement =
                        Arrangement.spacedBy(
                            16.dp
                        )
                ) {
                    val currentReport =
                        report

                    if (
                        currentReport ==
                        null
                    ) {
                        CircularProgressIndicator()

                        Text(
                            "Calculating ${selectedDays}-day report…",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodyMedium
                        )
                    } else {
                        Text(
                            "${
                                DateFormat
                                    .getDateInstance(
                                        DateFormat.MEDIUM
                                    )
                                    .format(
                                        Date(
                                            currentReport
                                                .windowStartMs
                                        )
                                    )
                            } – ${
                                DateFormat
                                    .getDateInstance(
                                        DateFormat.MEDIUM
                                    )
                                    .format(
                                        Date(
                                            currentReport
                                                .windowEndMs
                                        )
                                    )
                            }",
                            style =
                                MaterialTheme
                                    .typography
                                    .titleMedium,
                            fontWeight =
                                FontWeight.SemiBold,
                            textAlign =
                                TextAlign.Center
                        )

                        HorizontalDivider()

                        TimeInRangeBar(
                            title =
                                "Very High",
                            rangeLabel =
                                if (
                                    glucoseUnit ==
                                    GlucoseUnit.MMOL_L
                                ) {
                                    "≥ 13.4"
                                } else {
                                    "≥ 241"
                                },
                            percent =
                                currentReport
                                    .veryHighPercent,
                            barColor =
                                Color(
                                    0xFFFF8A33
                                )
                        )

                        TimeInRangeBar(
                            title =
                                "High",
                            rangeLabel =
                                highRangeLabel(
                                    glucoseLimits =
                                        glucoseLimits,
                                    glucoseUnit =
                                        glucoseUnit
                                ),
                            percent =
                                currentReport
                                    .highPercent,
                            barColor =
                                Color(
                                    0xFFFFD54F
                                )
                        )

                        TimeInRangeBar(
                            title =
                                "Target",
                            rangeLabel =
                                targetRangeLabel(
                                    glucoseLimits =
                                        glucoseLimits,
                                    glucoseUnit =
                                        glucoseUnit
                                ),
                            percent =
                                currentReport
                                    .targetPercent,
                            barColor =
                                AidexGreen
                        )

                        TimeInRangeBar(
                            title =
                                "Low",
                            rangeLabel =
                                lowRangeLabel(
                                    glucoseLimits =
                                        glucoseLimits,
                                    glucoseUnit =
                                        glucoseUnit
                                ),
                            percent =
                                currentReport
                                    .lowPercent,
                            barColor =
                                Color(
                                    0xFFE53935
                                )
                        )

                        HorizontalDivider()

                        Text(
                            "Target range: ${
                                glucoseUnit
                                    .formatValueFromMmol(
                                        glucoseLimits
                                            .lowMmol
                                    )
                            } – ${
                                glucoseUnit
                                    .formatValueFromMmol(
                                        glucoseLimits
                                            .highMmol
                                    )
                            } ${glucoseUnit.label}",
                            style =
                                MaterialTheme
                                    .typography
                                    .titleMedium,
                            fontWeight =
                                FontWeight.Bold,
                            textAlign =
                                TextAlign.Center
                        )

                        Text(
                            "Data available for ${currentReport.availableDays} of ${currentReport.days} days",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodyMedium,
                            color =
                                if (
                                    currentReport
                                        .availableDays <
                                    currentReport
                                        .days
                                ) {
                                    MaterialTheme
                                        .colorScheme
                                        .error
                                } else {
                                    MaterialTheme
                                        .colorScheme
                                        .primary
                                },
                            textAlign =
                                TextAlign.Center
                        )

                        Text(
                            "${currentReport.totalReadings} accepted glucose readings",
                            style =
                                MaterialTheme
                                    .typography
                                    .labelSmall,
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            ReportDaySelector(
                selectedDays =
                    selectedDays,
                onSelected = {
                    selectedDays =
                        it
                }
            )
        }

        item {
            Text(
                "Percentages are calculated from accepted stored glucose readings in the selected period. Missing days are reported separately rather than being treated as normal glucose.",
                style =
                    MaterialTheme
                        .typography
                        .bodySmall,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TimeInRangeBar(
    title: String,
    rangeLabel: String,
    percent: Double,
    barColor: Color
) {
    val safePercent =
        percent.coerceIn(
            0.0,
            100.0
        )

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(
                    min =
                        58.dp
                ),
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        Column(
            modifier =
                Modifier.width(
                    92.dp
                ),
            horizontalAlignment =
                Alignment.End
        ) {
            Text(
                title,
                style =
                    MaterialTheme
                        .typography
                        .labelMedium,
                fontWeight =
                    FontWeight.Bold
            )

            Text(
                rangeLabel,
                style =
                    MaterialTheme
                        .typography
                        .labelSmall,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }

        Spacer(
            Modifier.width(
                12.dp
            )
        )

        Box(
            modifier =
                Modifier
                    .weight(
                        1f
                    )
                    .height(
                        44.dp
                    )
                    .background(
                        MaterialTheme
                            .colorScheme
                            .surfaceVariant
                            .copy(
                                alpha =
                                    0.35f
                            ),
                        RoundedCornerShape(
                            5.dp
                        )
                    )
        ) {
            if (
                safePercent >
                0.0
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(
                                (
                                    safePercent /
                                        100.0
                                )
                                    .toFloat()
                                    .coerceAtLeast(
                                        0.025f
                                    )
                            )
                            .background(
                                barColor,
                                RoundedCornerShape(
                                    5.dp
                                )
                            )
                )
            }
        }

        Spacer(
            Modifier.width(
                10.dp
            )
        )

        Text(
            String.format(
                Locale.getDefault(),
                "%.1f%%",
                safePercent
            ),
            modifier =
                Modifier.width(
                    58.dp
                ),
            style =
                MaterialTheme
                    .typography
                    .titleMedium,
            fontWeight =
                FontWeight.Bold,
            textAlign =
                TextAlign.End
        )
    }
}

private fun lowRangeLabel(
    glucoseLimits: GlucoseLimits,
    glucoseUnit: GlucoseUnit
): String =
    "≤ ${
        glucoseUnit
            .formatValueFromMmol(
                glucoseLimits
                    .lowMmol
            )
    }"

private fun targetRangeLabel(
    glucoseLimits: GlucoseLimits,
    glucoseUnit: GlucoseUnit
): String {
    val lowerMmol =
        (
            kotlin.math.round(
                (
                    glucoseLimits.lowMmol +
                        0.1
                ) *
                    10.0
            ) /
                10.0
        )
            .coerceAtMost(
                glucoseLimits
                    .highMmol
            )

    return "${
        glucoseUnit
            .formatValueFromMmol(
                lowerMmol
            )
    }–${
        glucoseUnit
            .formatValueFromMmol(
                glucoseLimits
                    .highMmol
            )
    }"
}

private fun highRangeLabel(
    glucoseLimits: GlucoseLimits,
    glucoseUnit: GlucoseUnit
): String {
    val lowerMmol =
        (
            kotlin.math.round(
                (
                    glucoseLimits.highMmol +
                        0.1
                ) *
                    10.0
            ) /
                10.0
        )

    return "${
        glucoseUnit
            .formatValueFromMmol(
                lowerMmol
            )
    }–${
        glucoseUnit
            .formatValueFromMmol(
                13.3
            )
    }"
}

@Composable
private fun GlucoseHero(
    vm: AidexViewModel,
    sensor: AidexSensor,
    history: List<AidexReading>,
    notes: List<GlucoseNote>,
    glucoseLimits: GlucoseLimits,
    glucoseUnit: GlucoseUnit,
    activationStartedAtMs: Long?
) {
    val reading = sensor.reading ?: return

    val wallClockNowMs = rememberWallClockNowMs()
    val autoRecovery by vm.historyAutoRecoveryState.collectAsState()
    // Use the same all-sensor 90-day report as the detailed A1C report.
    // The chart history is limited to the selected sensor and cannot supply this average.
    val a1cReport by produceState<com.aidexx.reader.data.GlucosePeriodSummaryReport?>(
        initialValue = null,
        key1 = history,
        key2 = autoRecovery.lastCompletedMs,
        key3 = wallClockNowMs / 60_000L
    ) {
        value = vm.loadGlucosePeriodSummaryReport(90)
    }
    val a1cEstimate = a1cReport?.overallAverageMgDl?.takeIf { it > 0.0 }
        ?.let { A1cCalculator.fromAverageMgDl(it) }

    val warmupElapsedMinutes =
        activationStartedAtMs
            ?.let {
                (
                    (
                        wallClockNowMs -
                            it
                    ) /
                        60_000L
                )
                    .coerceAtLeast(
                        0L
                    )
                    .toInt()
            }
            ?: reading.sensorMinute
                .coerceAtLeast(
                    0
                )

    /*
     * During startup some AiDEX advertisements can remain at sensor minute 0
     * even though real time is advancing. Use the later of the authenticated
     * sensor minute and the persisted activation clock for the UI countdown.
     * This changes presentation only; glucose is never treated as ready until
     * the sensor itself leaves warm-up.
     */
    val effectiveWarmupMinute =
        max(
            reading.sensorMinute
                .coerceAtLeast(
                    0
                ),
            warmupElapsedMinutes
        )

    val warmupRemainingMinutes =
        (
            60 -
                effectiveWarmupMinute
        )
            .coerceAtLeast(
                0
            )

    val fastInsulinSummary =
        remember(
            notes,
            wallClockNowMs /
                60_000L
        ) {
            calculateNotePeriodSummary(
                notes =
                    notes,
                days =
                    7,
                nowMs =
                    wallClockNowMs
            )
        }

    val minutesAgo =
        (
            (
                wallClockNowMs -
                    reading.receivedAtMs
            ) /
                60_000L
        )
            .coerceAtLeast(
                0
            )
    val updatedText = when {
        minutesAgo <= 0 -> "Just now"
        minutesAgo == 1L -> "1 minute ago"
        else -> "$minutesAgo minutes ago"
    }

    val remainingMinutes = (15 * 24 * 60 - reading.sensorMinute).coerceAtLeast(0)
    val remainingDays = remainingMinutes / (24 * 60)
    val remainingHours = (remainingMinutes % (24 * 60)) / 60

    /*
     * Current glucose status colors:
     * Low       <= configured Low limit     -> Red
     * Normal    <= configured High limit    -> Green
     * High      displayed <= 13.3 mmol/L    -> Yellow
     * Very High displayed >= 13.4 mmol/L    -> Orange
     *
     * Use the one-decimal mmol/L value for the 13.4 boundary so the card
     * color always agrees with what the user sees on screen.
     */
    val displayedMmol =
        kotlin.math.round(
            reading.glucoseMmol * 10.0
        ) / 10.0

    /*
     * v0.1.59.4: direct glucose hero-card palette.
     * Low is a true red instead of the previous pink/magenta shade.
     * Very low uses a deeper emergency red. In-range retains the bright
     * AiDEX action green (#73D338).
     */
    val heroColor =
        when {
            displayedMmol <= 3.0 ->
                Color(0xFFB71C1C)

            reading.glucoseMmol <=
                glucoseLimits.lowMmol ->
                Color(0xFFFF3B30)

            reading.glucoseMmol <=
                glucoseLimits.highMmol ->
                Color(0xFF73D338)

            displayedMmol <
                13.4 ->
                Color(0xFFFFB52E)

            else ->
                Color(0xFFFF5638)
        }

    val modern = LocalTrailTheme.current.isModern
    val statusContentColor = if (displayedMmol <= 3.0) Color.White else Color.Black
    val heroContentColor = if(modern) MaterialTheme.colorScheme.onSurface else statusContentColor
    val statusLabel = when {
        reading.isWarmup -> "Warming up"
        displayedMmol <= 3.0 -> "Very low"
        reading.glucoseMmol <= glucoseLimits.lowMmol -> "Low"
        reading.glucoseMmol <= glucoseLimits.highMmol -> "In range"
        displayedMmol < 13.4 -> "High"
        else -> "Very high"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if(modern) MaterialTheme.colorScheme.surfaceVariant else heroColor,
                contentColor =
                    heroContentColor
            )
    ) {
        Column(
            modifier =
                Modifier.padding(
                    horizontal = 14.dp,
                    vertical = 10.dp
                )
        ) {
            Text(
                sensor.name,
                color = heroContentColor.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )

            if(modern && !reading.isWarmup) {
                Surface(color = heroColor, contentColor = statusContentColor, shape=RoundedCornerShape(8.dp)) {
                    Text(statusLabel,Modifier.padding(horizontal=8.dp,vertical=3.dp),style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (reading.isWarmup) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Warm-up",
                            color = heroContentColor,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            when {
                                warmupRemainingMinutes >
                                    0 ->
                                    "$warmupRemainingMinutes min remaining"

                                minutesAgo >=
                                    2L ->
                                    "Reconnecting to sensor…"

                                else ->
                                    "Finishing warm-up…"
                            },
                            color = heroContentColor.copy(alpha = 0.75f)
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            glucoseUnit.formatValueFromMgDl(
                                reading.glucoseMgDl.toDouble()
                            ),
                            color = heroContentColor,
                            style = MaterialTheme.typography.displayLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            reading.trendArrow,
                            color = heroContentColor,
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        updatedText,
                        color = heroContentColor,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (remainingDays > 0) {
                            "${remainingDays}d ${remainingHours}h remaining"
                        } else {
                            "${remainingHours}h remaining"
                        },
                        color = heroContentColor.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (
                        !reading.isWarmup
                    ) {
                        Spacer(Modifier.height(4.dp))

                        Text(
                            "90d Estimated A1C",
                            color = heroContentColor.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            if (a1cEstimate != null) {
                                "${String.format("%.1f", a1cEstimate.gmiPercent)}%"
                            } else {
                                "—"
                            },
                            color = heroContentColor,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (!reading.isWarmup) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.SpaceBetween,
                    verticalAlignment =
                        Alignment.Bottom
                ) {
                    Column {
                        Text(
                            glucoseUnit.label,
                            color =
                                heroContentColor
                                    .copy(
                                        alpha =
                                            0.78f
                                    ),
                            style =
                                MaterialTheme
                                    .typography
                                    .bodyMedium
                        )
                        Text(
                            "Rate ${glucoseUnit.formatRateFromMgDlPerMinute(reading.rateMgDlPerMinute)}",
                            color =
                                heroContentColor
                                    .copy(
                                        alpha =
                                            0.78f
                                    ),
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall
                        )
                    }

                    Column(
                        horizontalAlignment =
                            Alignment.End
                    ) {
                        Text(
                            "7d Fast Avg",
                            color =
                                heroContentColor
                                    .copy(
                                        alpha =
                                            0.72f
                                    ),
                            style =
                                MaterialTheme
                                    .typography
                                    .labelSmall
                        )
                        Text(
                            fastInsulinSummary
                                .fastAveragePerLoggedDay
                                ?.let {
                                    "${formatDashboardNumber(it)} U/day"
                                }
                                ?: "—",
                            color =
                                heroContentColor,
                            style =
                                MaterialTheme
                                    .typography
                                    .bodyMedium,
                            fontWeight =
                                FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GlucoseHistoryChart(
    history: List<AidexReading>,
    notes: List<GlucoseNote>,
    rangeHours: Int,
    currentReading: AidexReading,
    glucoseLimits: GlucoseLimits,
    glucoseUnit: GlucoseUnit,
    historyRecoveryActive: Boolean,
    historyRecoveryMessage: String,
    onRecentGapDetected: () -> Unit,
    onNoteClick: (GlucoseNote) -> Unit,
    allowRecentGapRecovery: Boolean =
        true
) {
    val configuration =
        LocalConfiguration.current

    val graphHeight =
        (
            configuration.screenHeightDp *
                0.28f
        )
            .coerceIn(
                170f,
                240f
            )
            .dp

    /*
     * "Now" is the phone's real current time.
     *
     * Sensor minute remains the authoritative spacing between CGM readings,
     * but a stale newest reading is no longer shifted to the right edge.
     * This makes missing recent data visually obvious.
     */
    val wallClockNowMs =
        rememberWallClockNowMs()

    val rangeMs =
        rangeHours
            .toLong() *
            60L *
            60L *
            1000L

    val axisStartMs =
        wallClockNowMs -
            rangeMs

    val allValidReadings =
        remember(
            history,
            currentReading.sensorMinute,
            currentReading.receivedAtMs,
            currentReading.glucoseMgDl
        ) {
            (
                history +
                    currentReading
            )
                .filter {
                    it.crcValid &&
                        it.valid &&
                        !it.isWarmup
                }
                .associateBy {
                    "${it.serial.uppercase()}:${it.sensorMinute}"
                }
                .values
                .sortedBy {
                    it.receivedAtMs
                }
        }

    val latestReading =
        allValidReadings.maxByOrNull {
            it.receivedAtMs
        }

    val latestCurrentSensorReading =
        allValidReadings
            .asSequence()
            .filter {
                it.serial.equals(
                    currentReading.serial,
                    ignoreCase =
                        true
                )
            }
            .maxByOrNull {
                it.receivedAtMs
            }

    if (
        latestReading ==
        null
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(
                        graphHeight
                    ),
            contentAlignment =
                Alignment.Center
        ) {
            Text(
                "No glucose history is available yet.",
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant,
                textAlign =
                    TextAlign.Center
            )
        }
        return
    }

    val latestSensorMinute =
        latestReading.sensorMinute

    /*
     * Stored readings already carry reconstructed wall-clock timestamps for
     * encrypted history and advertisement backfill. Use that timestamp as the
     * common axis so data from consecutive sensor serials remains continuous.
     */
    fun graphTimestampFor(
        reading: AidexReading
    ): Long =
        reading.receivedAtMs

    val latestGraphTimestampMs =
        graphTimestampFor(
            latestReading
        )

    val latestCurrentSensorTimestampMs =
        latestCurrentSensorReading
            ?.let {
                graphTimestampFor(
                    it
                )
            }

    val latestCurrentSensorAgeMinutes =
        latestCurrentSensorTimestampMs
            ?.let {
                (
                    wallClockNowMs -
                        it
                )
                    .coerceAtLeast(
                        0L
                    ) /
                    60_000L
            }

    /*
     * Recent-gap recovery belongs only to the currently installed sensor.
     * A previous sensor may legitimately end hours or days before the next
     * one is inserted. That blank interval stays visible on the graph but is
     * never presented as recoverable F002 history for the new sensor. Warm-up
     * is also an intentional no-glucose period.
     */
    val recentGapMinutes =
        if (
            !allowRecentGapRecovery ||
            currentReading.isWarmup ||
            latestCurrentSensorAgeMinutes ==
            null
        ) {
            0L
        } else {
            latestCurrentSensorAgeMinutes
                .takeIf {
                    it >=
                        RECENT_DATA_GAP_WARNING_MINUTES
                }
                ?: 0L
        }

    LaunchedEffect(
        recentGapMinutes,
        currentReading.serial,
        currentReading.sensorMinute,
        currentReading.isWarmup
    ) {
        if (
            recentGapMinutes >=
            RECENT_DATA_GAP_WARNING_MINUTES
        ) {
            onRecentGapDetected()
        }
    }

    val points =
        remember(
            allValidReadings,
            rangeHours,
            latestReading.sensorMinute,
            latestReading.receivedAtMs,
            axisStartMs
        ) {
            allValidReadings
                .filter {
                    val timestamp =
                        graphTimestampFor(
                            it
                        )

                    timestamp >=
                        axisStartMs &&
                        timestamp <=
                            wallClockNowMs
                }
                .sortedBy {
                    it.receivedAtMs
                }
        }

    val valuesForScale =
        if (
            points.isEmpty()
        ) {
            listOf(
                glucoseLimits.lowMmol,
                glucoseLimits.highMmol
            )
        } else {
            points.map {
                it.glucoseMmol
            } +
                glucoseLimits.lowMmol +
                glucoseLimits.highMmol
        }

    val minValue =
        valuesForScale.minOrNull()
            ?: glucoseLimits.lowMmol

    val maxValue =
        valuesForScale.maxOrNull()
            ?: glucoseLimits.highMmol

    val padding =
        max(
            0.8,
            (
                maxValue -
                    minValue
            ) *
                0.12
        )

    val chartMin =
        max(
            0.0,
            minValue -
                padding
        )

    val chartMax =
        max(
            chartMin +
                1.0,
            maxValue +
                padding
        )

    var selectedReading by remember(
        rangeHours,
        latestSensorMinute,
        wallClockNowMs
    ) {
        mutableStateOf<AidexReading?>(
            null
        )
    }

    var plotWidthPx by remember {
        mutableIntStateOf(
            1
        )
    }

    var plotHeightPx by remember {
        mutableIntStateOf(
            1
        )
    }

    val density =
        LocalDensity.current

    val visibleNotes =
        remember(
            notes,
            rangeHours,
            axisStartMs,
            wallClockNowMs
        ) {
            notes.filter {
                    note ->
                note.timestampMs >=
                    axisStartMs &&
                    note.timestampMs <=
                    wallClockNowMs
            }
        }

    fun nearestReadingAtX(
        x: Float
    ): AidexReading? {
        if (
            plotWidthPx <= 0 ||
            points.isEmpty()
        ) {
            return null
        }

        val fraction =
            (
                x /
                    plotWidthPx
                        .toFloat()
            )
                .coerceIn(
                    0f,
                    1f
                )

        val targetTimestamp =
            axisStartMs +
                (
                    fraction *
                        rangeMs
                            .toDouble()
                )
                    .toLong()

        return points.minByOrNull {
                reading ->
            abs(
                graphTimestampFor(
                    reading
                ) -
                    targetTimestamp
            )
        }
    }

    Column {
        Row(
            modifier =
                Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.SpaceBetween
        ) {
            Text(
                "Glucose history",
                fontWeight =
                    FontWeight.Bold
            )

            Text(
                "Limits ${glucoseUnit.formatValueFromMmol(glucoseLimits.lowMmol)} – ${glucoseUnit.formatValueFromMmol(glucoseLimits.highMmol)} ${glucoseUnit.label}",
                style =
                    MaterialTheme
                        .typography
                        .bodySmall,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }

        if (
            recentGapMinutes > 0
        ) {
            Spacer(
                Modifier.height(
                    6.dp
                )
            )

            Surface(
                modifier =
                    Modifier.fillMaxWidth(),
                shape =
                    RoundedCornerShape(
                        8.dp
                    ),
                color =
                    MaterialTheme
                        .colorScheme
                        .errorContainer
                        .copy(
                            alpha =
                                0.72f
                        ),
                contentColor =
                    MaterialTheme
                        .colorScheme
                        .onErrorContainer
            ) {
                Text(
                    text =
                        if (
                            historyRecoveryActive
                        ) {
                            "Missing ${formatMissingMinutes(recentGapMinutes)} of recent data • $historyRecoveryMessage"
                        } else if (
                            historyRecoveryMessage.contains(
                                "retry",
                                ignoreCase =
                                    true
                            ) ||
                            historyRecoveryMessage.contains(
                                "failed",
                                ignoreCase =
                                    true
                            ) ||
                            historyRecoveryMessage.contains(
                                "could not",
                                ignoreCase =
                                    true
                            )
                        ) {
                            "Missing ${formatMissingMinutes(recentGapMinutes)} of recent data • $historyRecoveryMessage"
                        } else {
                            "Missing ${formatMissingMinutes(recentGapMinutes)} of recent data • latest ${
                                DateFormat
                                    .getTimeInstance(
                                        DateFormat.SHORT
                                    )
                                    .format(
                                        Date(
                                            latestGraphTimestampMs
                                        )
                                    )
                            } • auto-sync requested"
                        },
                    modifier =
                        Modifier.padding(
                            horizontal =
                                10.dp,
                            vertical =
                                7.dp
                        ),
                    style =
                        MaterialTheme
                            .typography
                            .labelMedium,
                    fontWeight =
                        FontWeight.Bold
                )
            }
        }

        Spacer(
            Modifier.height(
                8.dp
            )
        )

        val lineColor =
            MaterialTheme
                .colorScheme
                .primary

        val gridColor =
            MaterialTheme
                .colorScheme
                .outlineVariant

        val pointColor =
            MaterialTheme
                .colorScheme
                .primary

        val limitColor =
            MaterialTheme
                .colorScheme
                .error
                .copy(
                    alpha =
                        0.58f
                )

        val normalRangeColor =
            AidexGreen.copy(
                alpha =
                    0.16f
            )

        val recentGapColor =
            MaterialTheme
                .colorScheme
                .error
                .copy(
                    alpha =
                        0.07f
                )

        val recentGapEdgeColor =
            MaterialTheme
                .colorScheme
                .error
                .copy(
                    alpha =
                        0.45f
                )

        val crosshairColor =
            MaterialTheme
                .colorScheme
                .onSurface
                .copy(
                    alpha =
                        0.72f
                )

        val selectedPointOuterColor =
            MaterialTheme
                .colorScheme
                .surface

        val yAxisTicks =
            remember(
                chartMin,
                chartMax
            ) {
                List(
                    5
                ) {
                        index ->
                    chartMax -
                        (
                            (
                                chartMax -
                                    chartMin
                            ) *
                                index /
                                4.0
                        )
                }
            }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(
                        graphHeight
                    ),
            verticalAlignment =
                Alignment.Top
        ) {
            Column(
                modifier =
                    Modifier
                        .width(
                            42.dp
                        )
                        .fillMaxHeight(),
                horizontalAlignment =
                    Alignment.Start
            ) {
                Text(
                    glucoseUnit.label,
                    style =
                        MaterialTheme
                            .typography
                            .labelSmall,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    modifier =
                        Modifier.padding(
                            bottom =
                                4.dp
                        )
                )

                Column(
                    modifier =
                        Modifier
                            .weight(
                                1f
                            )
                            .padding(
                                vertical =
                                    8.dp
                            ),
                    verticalArrangement =
                        Arrangement
                            .SpaceBetween,
                    horizontalAlignment =
                        Alignment.Start
                ) {
                    yAxisTicks.forEach {
                            tick ->
                        Text(
                            glucoseUnit
                                .formatValueFromMmol(
                                    tick
                                ),
                            style =
                                MaterialTheme
                                    .typography
                                    .labelSmall,
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            Box(
                modifier =
                    Modifier
                        .weight(
                            1f
                        )
                        .fillMaxHeight()
                        .background(
                            MaterialTheme
                                .colorScheme
                                .surfaceVariant
                                .copy(
                                    alpha =
                                        0.22f
                                ),
                            RoundedCornerShape(
                                12.dp
                            )
                        )
                        .onSizeChanged {
                            plotWidthPx =
                                it.width
                                    .coerceAtLeast(
                                        1
                                    )
                            plotHeightPx =
                                it.height
                                    .coerceAtLeast(
                                        1
                                    )
                        }
                        .pointerInput(
                            points,
                            rangeHours,
                            latestSensorMinute,
                            plotWidthPx,
                            wallClockNowMs
                        ) {
                            awaitEachGesture {
                                val down =
                                    awaitFirstDown(
                                        requireUnconsumed =
                                            true
                                    )

                                selectedReading =
                                    nearestReadingAtX(
                                        down.position.x
                                    )

                                var pressed =
                                    true

                                while (
                                    pressed
                                ) {
                                    val event =
                                        awaitPointerEvent()

                                    val change =
                                        event
                                            .changes
                                            .firstOrNull {
                                                it.id ==
                                                    down.id
                                            }

                                    if (
                                        change ==
                                        null
                                    ) {
                                        pressed =
                                            false
                                    } else {
                                        selectedReading =
                                            nearestReadingAtX(
                                                change.position.x
                                            )

                                        pressed =
                                            change.pressed
                                    }
                                }

                                selectedReading =
                                    null
                            }
                        }
            ) {
                Canvas(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(
                                vertical =
                                    8.dp
                            )
                ) {
                    val w =
                        size.width

                    val h =
                        size.height

                    /*
                     * Leave a small inset so the newest dot remains completely
                     * visible when data is genuinely current.
                     */
                    val rightEdge =
                        (
                            w -
                                12f
                        )
                            .coerceAtLeast(
                                0f
                            )

                    fun xForTimestamp(
                        timestampMs: Long
                    ): Float {
                        val fraction =
                            (
                                (
                                    timestampMs -
                                        axisStartMs
                                )
                                    .toDouble() /
                                    rangeMs
                                        .toDouble()
                            )
                                .coerceIn(
                                    0.0,
                                    1.0
                                )

                        return (
                            fraction *
                                rightEdge
                        )
                            .toFloat()
                    }

                    fun xFor(
                        reading:
                            AidexReading
                    ): Float =
                        xForTimestamp(
                            graphTimestampFor(
                                reading
                            )
                        )

                    fun yFor(
                        value: Double
                    ): Float {
                        val normalized =
                            (
                                (
                                    value -
                                        chartMin
                                ) /
                                    (
                                        chartMax -
                                            chartMin
                                    )
                            )
                                .coerceIn(
                                    0.0,
                                    1.0
                                )

                        return h -
                            normalized
                                .toFloat() *
                            h
                    }

                    val normalTop =
                        yFor(
                            glucoseLimits
                                .highMmol
                        )

                    val normalBottom =
                        yFor(
                            glucoseLimits
                                .lowMmol
                        )

                    drawRect(
                        color =
                            normalRangeColor,
                        topLeft =
                            Offset(
                                0f,
                                normalTop
                            ),
                        size =
                            androidx
                                .compose
                                .ui
                                .geometry
                                .Size(
                                    width =
                                        w,
                                    height =
                                        (
                                            normalBottom -
                                                normalTop
                                        )
                                            .coerceAtLeast(
                                                0f
                                            )
                                )
                    )

                    repeat(
                        5
                    ) {
                            index ->
                        val y =
                            h *
                                index /
                                4f

                        drawLine(
                            color =
                                gridColor,
                            start =
                                Offset(
                                    0f,
                                    y
                                ),
                            end =
                                Offset(
                                    w,
                                    y
                                ),
                            strokeWidth =
                                1f
                        )
                    }

                    drawLine(
                        color =
                            limitColor,
                        start =
                            Offset(
                                0f,
                                yFor(
                                    glucoseLimits
                                        .highMmol
                                )
                            ),
                        end =
                            Offset(
                                w,
                                yFor(
                                    glucoseLimits
                                        .highMmol
                                )
                            ),
                        strokeWidth =
                            2f
                    )

                    drawLine(
                        color =
                            limitColor,
                        start =
                            Offset(
                                0f,
                                yFor(
                                    glucoseLimits
                                        .lowMmol
                                )
                            ),
                        end =
                            Offset(
                                w,
                                yFor(
                                    glucoseLimits
                                        .lowMmol
                                )
                            ),
                        strokeWidth =
                            2f
                    )

                    /*
                     * Explicitly shade the recent-data hole. The shaded area
                     * shrinks toward zero as live/backfilled readings catch up.
                     */
                    if (
                        recentGapMinutes > 0
                    ) {
                        val lastX =
                            xForTimestamp(
                                latestGraphTimestampMs
                            )

                        if (
                            lastX <
                            rightEdge
                        ) {
                            drawRect(
                                color =
                                    recentGapColor,
                                topLeft =
                                    Offset(
                                        lastX,
                                        0f
                                    ),
                                size =
                                    androidx
                                        .compose
                                        .ui
                                        .geometry
                                        .Size(
                                            width =
                                                (
                                                    rightEdge -
                                                        lastX
                                                )
                                                    .coerceAtLeast(
                                                        0f
                                                    ),
                                            height =
                                                h
                                        )
                            )

                            drawLine(
                                color =
                                    recentGapEdgeColor,
                                start =
                                    Offset(
                                        lastX,
                                        0f
                                    ),
                                end =
                                    Offset(
                                        lastX,
                                        h
                                    ),
                                strokeWidth =
                                    2f
                            )
                        }
                    }

                    /*
                     * Draw separate continuous segments. Never draw a line
                     * across an internal hole of more than two sensor minutes.
                     */
                    var segment =
                        mutableListOf<AidexReading>()

                    fun drawSegment() {
                        if (
                            segment.isEmpty()
                        ) {
                            return
                        }

                        if (
                            segment.size ==
                            1
                        ) {
                            val only =
                                segment.first()

                            drawCircle(
                                color =
                                    pointColor,
                                radius =
                                    7f,
                                center =
                                    Offset(
                                        xFor(
                                            only
                                        ),
                                        yFor(
                                            only
                                                .glucoseMmol
                                        )
                                    )
                            )

                            segment =
                                mutableListOf()
                            return
                        }

                        val path =
                            Path()

                        segment
                            .forEachIndexed {
                                    index,
                                    reading ->
                                val x =
                                    xFor(
                                        reading
                                    )

                                val y =
                                    yFor(
                                        reading
                                            .glucoseMmol
                                    )

                                if (
                                    index ==
                                    0
                                ) {
                                    path.moveTo(
                                        x,
                                        y
                                    )
                                } else {
                                    path.lineTo(
                                        x,
                                        y
                                    )
                                }
                            }

                        drawPath(
                            path =
                                path,
                            color =
                                lineColor,
                            style =
                                Stroke(
                                    width =
                                        8f,
                                    cap =
                                        StrokeCap
                                            .Round
                                )
                        )

                        segment =
                            mutableListOf()
                    }

                    points.forEach {
                            reading ->
                        val previous =
                            segment
                                .lastOrNull()

                        if (
                            previous != null &&
                            reading.receivedAtMs -
                                previous.receivedAtMs >
                            INTERNAL_GRAPH_GAP_MINUTES
                                .toLong() *
                                60_000L
                        ) {
                            drawSegment()
                        }

                        segment.add(
                            reading
                        )
                    }

                    drawSegment()

                    points.lastOrNull()
                        ?.let {
                                last ->
                            drawCircle(
                                color =
                                    pointColor,
                                radius =
                                    11f,
                                center =
                                    Offset(
                                        xFor(
                                            last
                                        ),
                                        yFor(
                                            last
                                                .glucoseMmol
                                        )
                                    )
                            )
                        }

                    selectedReading
                        ?.let {
                                selected ->
                            val selectedX =
                                xFor(
                                    selected
                                )

                            val selectedY =
                                yFor(
                                    selected
                                        .glucoseMmol
                                )

                            drawLine(
                                color =
                                    crosshairColor,
                                start =
                                    Offset(
                                        selectedX,
                                        0f
                                    ),
                                end =
                                    Offset(
                                        selectedX,
                                        h
                                    ),
                                strokeWidth =
                                    4f
                            )

                            drawCircle(
                                color =
                                    selectedPointOuterColor,
                                radius =
                                    16f,
                                center =
                                    Offset(
                                        selectedX,
                                        selectedY
                                    )
                            )

                            drawCircle(
                                color =
                                    pointColor,
                                radius =
                                    11f,
                                center =
                                    Offset(
                                        selectedX,
                                        selectedY
                                    )
                            )
                        }
                }

                visibleNotes.forEach {
                        note ->
                    val fraction =
                        (
                            (
                                note.timestampMs -
                                    axisStartMs
                            )
                                .toDouble() /
                                rangeMs.toDouble()
                        )
                            .coerceIn(
                                0.0,
                                1.0
                            )

                    val rightEdgePx =
                        (
                            plotWidthPx -
                                12
                        )
                            .coerceAtLeast(
                                1
                            )

                    val markerXPx =
                        fraction *
                            rightEdgePx

                    val nearest =
                        points.minByOrNull {
                                reading ->
                            abs(
                                graphTimestampFor(
                                    reading
                                ) -
                                    note.timestampMs
                            )
                        }

                    val markerYPx: Double =
                        if (
                            nearest !=
                            null
                        ) {
                            val normalized =
                                (
                                    (
                                        nearest.glucoseMmol -
                                            chartMin
                                    ) /
                                        (
                                            chartMax -
                                                chartMin
                                        )
                                )
                                    .coerceIn(
                                        0.0,
                                        1.0
                                    )

                            plotHeightPx -
                                normalized *
                                plotHeightPx
                        } else {
                            with(
                                density
                            ) {
                                36.dp.toPx()
                                    .toDouble()
                            }
                        }

                    val markerSize =
                        28.dp

                    val markerSizePx =
                        with(
                            density
                        ) {
                            markerSize.toPx()
                                .toDouble()
                        }

                    Surface(
                        modifier =
                            Modifier
                                .offset {
                                    IntOffset(
                                        x =
                                            (
                                                markerXPx -
                                                    markerSizePx /
                                                    2.0
                                            )
                                                .roundToInt()
                                                .coerceIn(
                                                    0,
                                                    (
                                                        plotWidthPx -
                                                            markerSizePx
                                                                .roundToInt()
                                                    )
                                                        .coerceAtLeast(
                                                            0
                                                        )
                                                ),
                                        y =
                                            (
                                                markerYPx -
                                                    markerSizePx -
                                                    with(
                                                        density
                                                    ) {
                                                        6.dp.toPx()
                                                            .toDouble()
                                                    }
                                            )
                                                .roundToInt()
                                                .coerceIn(
                                                    0,
                                                    (
                                                        plotHeightPx -
                                                            markerSizePx
                                                                .roundToInt()
                                                    )
                                                        .coerceAtLeast(
                                                            0
                                                        )
                                                )
                                    )
                                }
                                .size(
                                    markerSize
                                )
                                .clickable {
                                    onNoteClick(
                                        note
                                    )
                                },
                        shape =
                            CircleShape,
                        color =
                            glucoseNoteMarkerColor(
                                note
                            ),
                        contentColor =
                            Color.White,
                        shadowElevation =
                            3.dp
                    ) {
                        Box(
                            modifier =
                                Modifier.fillMaxSize(),
                            contentAlignment =
                                Alignment.Center
                        ) {
                            Text(
                                "✎",
                                style =
                                    MaterialTheme
                                        .typography
                                        .labelMedium,
                                fontWeight =
                                    FontWeight.Bold
                            )
                        }
                    }
                }

                if (
                    points.isEmpty()
                ) {
                    Text(
                        "No readings in this ${rangeHours}h window",
                        modifier =
                            Modifier.align(
                                Alignment.Center
                            ),
                        style =
                            MaterialTheme
                                .typography
                                .bodyMedium,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )
                }

                selectedReading
                    ?.let {
                            selected ->
                        Surface(
                            modifier =
                                Modifier
                                    .align(
                                        Alignment
                                            .TopCenter
                                    )
                                    .padding(
                                        top =
                                            10.dp
                                    ),
                            shape =
                                RoundedCornerShape(
                                    10.dp
                                ),
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .inverseSurface,
                            contentColor =
                                MaterialTheme
                                    .colorScheme
                                    .inverseOnSurface,
                            shadowElevation =
                                4.dp
                        ) {
                            Text(
                                text =
                                    "${
                                        formatGraphReadingTime(
                                            graphTimestampFor(
                                                selected
                                            ),
                                            rangeHours
                                        )
                                    }  •  ${
                                        glucoseUnit
                                            .formatGlucoseFromMgDl(
                                                selected
                                                    .glucoseMgDl
                                                    .toDouble()
                                            )
                                    }",
                                modifier =
                                    Modifier.padding(
                                        horizontal =
                                            10.dp,
                                        vertical =
                                            7.dp
                                    ),
                                style =
                                    MaterialTheme
                                        .typography
                                        .labelMedium,
                                fontWeight =
                                    FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
            }
        }

        Spacer(
            Modifier.height(
                6.dp
            )
        )

        Row(
            modifier =
                Modifier.fillMaxWidth()
        ) {
            Spacer(
                Modifier.width(
                    42.dp
                )
            )

            Box(
                modifier =
                    Modifier.weight(
                        1f
                    )
            ) {
                Text(
                    "${rangeHours}h ago",
                    style =
                        MaterialTheme
                            .typography
                            .labelSmall,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant,
                    modifier =
                        Modifier.align(
                            Alignment
                                .CenterStart
                        )
                )

                Text(
                    "Now",
                    style =
                        MaterialTheme
                            .typography
                            .labelSmall,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant,
                    modifier =
                        Modifier.align(
                            Alignment
                                .CenterEnd
                        )
                )
            }
        }

        Spacer(
            Modifier.height(
                4.dp
            )
        )

        if (
            recentGapMinutes >
            0
        ) {
            Spacer(
                Modifier.height(
                    2.dp
                )
            )

            Text(
                "Recent history incomplete • recovering automatically.",
                style =
                    MaterialTheme
                        .typography
                        .labelSmall,
                color =
                    MaterialTheme
                        .colorScheme
                        .error,
                maxLines =
                    1,
                softWrap =
                    false
            )
        }
    }
}

@Composable
private fun GlucoseNotesSection(
    notes: List<GlucoseNote>,
    rangeHours: Int,
    onAddNote: () -> Unit,
    onNoteClick: (GlucoseNote) -> Unit
) {
    val now =
        rememberWallClockNowMs()

    val cutoff =
        now -
            rangeHours.toLong() *
            60L *
            60L *
            1000L

    val visible =
        notes
            .filter {
                it.timestampMs >=
                    cutoff &&
                    it.timestampMs <=
                    now
            }
            .sortedByDescending {
                it.timestampMs
            }

    HorizontalDivider()

    Spacer(
        Modifier.height(
            5.dp
        )
    )

    Row(
        modifier =
            Modifier.fillMaxWidth(),
        verticalAlignment =
            Alignment.CenterVertically,
        horizontalArrangement =
            Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment =
                Alignment.CenterVertically,
            horizontalArrangement =
                Arrangement.spacedBy(
                    6.dp
                )
        ) {
            Text(
                "Notes",
                style =
                    MaterialTheme
                        .typography
                        .labelLarge,
                fontWeight =
                    FontWeight.Bold
            )

            if (
                visible.isNotEmpty()
            ) {
                Text(
                    "${visible.size}",
                    style =
                        MaterialTheme
                            .typography
                            .labelSmall,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }
        }

        Button(
            onClick =
                onAddNote,
            modifier =
                Modifier.height(
                    30.dp
                ),
            contentPadding =
                PaddingValues(
                    horizontal =
                        10.dp,
                    vertical =
                        0.dp
                )
        ) {
            Text(
                "+ Add Note",
                style =
                    MaterialTheme
                        .typography
                        .labelMedium
            )
        }
    }

    visible
        .firstOrNull()
        ?.let {
                note ->
            Spacer(
                Modifier.height(
                    4.dp
                )
            )

            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            onNoteClick(
                                note
                            )
                        },
                shape =
                    RoundedCornerShape(
                        8.dp
                    ),
                color =
                    MaterialTheme
                        .colorScheme
                        .surfaceVariant
                        .copy(
                            alpha =
                                0.34f
                        )
            ) {
                Row(
                    modifier =
                        Modifier.padding(
                            horizontal =
                                8.dp,
                            vertical =
                                5.dp
                        ),
                    verticalAlignment =
                        Alignment.CenterVertically,
                    horizontalArrangement =
                        Arrangement.spacedBy(
                            7.dp
                        )
                ) {
                    Surface(
                        modifier =
                            Modifier.size(
                                22.dp
                            ),
                        shape =
                            CircleShape,
                        color =
                            glucoseNoteMarkerColor(
                                note
                            ),
                        contentColor =
                            Color.White
                    ) {
                        Box(
                            modifier =
                                Modifier.fillMaxSize(),
                            contentAlignment =
                                Alignment.Center
                        ) {
                            Text(
                                "✎",
                                style =
                                    MaterialTheme
                                        .typography
                                        .labelSmall,
                                fontWeight =
                                    FontWeight.Bold
                            )
                        }
                    }

                    Text(
                        "${
                            DateFormat
                                .getTimeInstance(
                                    DateFormat.SHORT
                                )
                                .format(
                                    Date(
                                        note.timestampMs
                                    )
                                )
                        }  •  ${glucoseNoteSummary(note)}",
                        modifier =
                            Modifier.weight(
                                1f
                            ),
                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,
                        maxLines =
                            1,
                        softWrap =
                            false,
                        overflow =
                            TextOverflow.Ellipsis
                    )

                    if (
                        visible.size >
                        1
                    ) {
                        Text(
                            "+${visible.size - 1}",
                            style =
                                MaterialTheme
                                    .typography
                                    .labelSmall,
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .primary,
                            fontWeight =
                                FontWeight.Bold
                        )
                    }

                    Text(
                        "›",
                        style =
                            MaterialTheme
                                .typography
                                .labelLarge
                    )
                }
            }
        }
}

@Composable
private fun GlucoseNoteEditorDialog(
    serial: String,
    existing: GlucoseNote?,
    onSave: (GlucoseNote) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var fastText by remember(
        existing?.id
    ) {
        mutableStateOf(
            existing
                ?.fastInsulinUnits
                ?.takeIf {
                    it >
                        0.0
                }
                ?.let(
                    ::formatNoteNumber
                )
                .orEmpty()
        )
    }

    var slowText by remember(
        existing?.id
    ) {
        mutableStateOf(
            existing
                ?.slowInsulinUnits
                ?.takeIf {
                    it >
                        0.0
                }
                ?.let(
                    ::formatNoteNumber
                )
                .orEmpty()
        )
    }

    var carbsText by remember(
        existing?.id
    ) {
        mutableStateOf(
            existing
                ?.carbsGrams
                ?.takeIf {
                    it >
                        0.0
                }
                ?.let(
                    ::formatNoteNumber
                )
                .orEmpty()
        )
    }

    var notesText by remember(
        existing?.id
    ) {
        mutableStateOf(
            existing
                ?.noteText
                .orEmpty()
        )
    }

    val fast =
        fastText
            .toDoubleOrNull()
            ?.coerceAtLeast(
                0.0
            )
            ?: 0.0

    val slow =
        slowText
            .toDoubleOrNull()
            ?.coerceAtLeast(
                0.0
            )
            ?: 0.0

    val carbs =
        carbsText
            .toDoubleOrNull()
            ?.coerceAtLeast(
                0.0
            )
            ?: 0.0

    val canSave =
        fast >
            0.0 ||
            slow >
                0.0 ||
            carbs >
                0.0 ||
            notesText.isNotBlank()

    AlertDialog(
        onDismissRequest =
            onDismiss,
        title = {
            Text(
                if (
                    existing ==
                    null
                ) {
                    "Add Note"
                } else {
                    "Note"
                }
            )
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(
                            rememberScrollState()
                        ),
                verticalArrangement =
                    Arrangement.spacedBy(
                        10.dp
                    )
            ) {
                Text(
                    if (
                        existing ==
                        null
                    ) {
                        "Time: Now"
                    } else {
                        "Recorded: ${
                            DateFormat
                                .getDateTimeInstance(
                                    DateFormat.MEDIUM,
                                    DateFormat.SHORT
                                )
                                .format(
                                    Date(
                                        existing.timestampMs
                                    )
                                )
                        }"
                    },
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )

                OutlinedTextField(
                    value =
                        fastText,
                    onValueChange = {
                        fastText =
                            sanitizeDecimalNoteInput(
                                it
                            )
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            "Fast insulin"
                        )
                    },
                    suffix = {
                        Text(
                            "units"
                        )
                    },
                    singleLine =
                        true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                KeyboardType.Decimal
                        )
                )

                OutlinedTextField(
                    value =
                        slowText,
                    onValueChange = {
                        slowText =
                            sanitizeDecimalNoteInput(
                                it
                            )
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            "Slow insulin"
                        )
                    },
                    suffix = {
                        Text(
                            "units"
                        )
                    },
                    singleLine =
                        true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                KeyboardType.Decimal
                        )
                )

                OutlinedTextField(
                    value =
                        carbsText,
                    onValueChange = {
                        carbsText =
                            sanitizeDecimalNoteInput(
                                it
                            )
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            "Carbs per meal"
                        )
                    },
                    suffix = {
                        Text(
                            "g"
                        )
                    },
                    singleLine =
                        true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                KeyboardType.Decimal
                        )
                )

                OutlinedTextField(
                    value =
                        notesText,
                    onValueChange = {
                        notesText =
                            it.take(
                                500
                            )
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(
                                min =
                                    110.dp
                            ),
                    label = {
                        Text(
                            "Notes"
                        )
                    },
                    minLines =
                        4,
                    maxLines =
                        8
                )

                if (
                    existing !=
                    null &&
                    onDelete !=
                    null
                ) {
                    OutlinedButton(
                        onClick =
                            onDelete,
                        modifier =
                            Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Delete Note",
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .error
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        GlucoseNote(
                            id =
                                existing
                                    ?.id
                                    ?: 0L,
                            serial =
                                serial,
                            timestampMs =
                                existing
                                    ?.timestampMs
                                    ?: System.currentTimeMillis(),
                            fastInsulinUnits =
                                fast,
                            slowInsulinUnits =
                                slow,
                            carbsGrams =
                                carbs,
                            noteText =
                                notesText.trim()
                        )
                    )
                },
                enabled =
                    canSave
            ) {
                Text(
                    if (
                        existing ==
                        null
                    ) {
                        "Save Note"
                    } else {
                        "Save Changes"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick =
                    onDismiss
            ) {
                Text(
                    "Cancel"
                )
            }
        }
    )
}

private fun glucoseNoteMarkerColor(
    note: GlucoseNote
): Color =
    when {
        note.hasFastInsulin ->
            Color(
                0xFF3976C5
            )

        note.hasSlowInsulin ->
            Color(
                0xFF61B63D
            )

        note.hasCarbs ->
            Color(
                0xFFF3A324
            )

        else ->
            Color(
                0xFF8252C8
            )
    }

private fun glucoseNoteSummary(
    note: GlucoseNote
): String {
    val pieces =
        mutableListOf<String>()

    if (
        note.hasFastInsulin
    ) {
        pieces +=
            "Fast ${formatNoteNumber(note.fastInsulinUnits)}u"
    }

    if (
        note.hasSlowInsulin
    ) {
        pieces +=
            "Slow ${formatNoteNumber(note.slowInsulinUnits)}u"
    }

    if (
        note.hasCarbs
    ) {
        pieces +=
            "Carbs ${formatNoteNumber(note.carbsGrams)}g"
    }

    if (
        note.hasText
    ) {
        pieces +=
            note.noteText
                .replace(
                    "\n",
                    " "
                )
    }

    if (
        note.hasPhotos
    ) {
        pieces +=
            "📷 ${note.photoPaths.size}"
    }

    return pieces
        .joinToString(
            " • "
        )
        .ifBlank {
            "Note"
        }
}

private fun formatNoteNumber(
    value: Double
): String =
    if (
        value %
            1.0 ==
        0.0
    ) {
        value.toInt()
            .toString()
    } else {
        String.format(
            Locale.US,
            "%.1f",
            value
        )
    }

private fun sanitizeDecimalNoteInput(
    value: String
): String {
    val normalized =
        value
            .replace(
                ',',
                '.'
            )
            .filter {
                it.isDigit() ||
                    it ==
                    '.'
            }

    val firstDot =
        normalized.indexOf(
            '.'
        )

    return if (
        firstDot <
        0
    ) {
        normalized.take(
            7
        )
    } else {
        normalized
            .filterIndexed {
                    index,
                    char ->
                char !=
                    '.' ||
                    index ==
                    firstDot
            }
            .take(
                7
            )
    }
}

private const val RECENT_DATA_GAP_WARNING_MINUTES =
    3L

private const val INTERNAL_GRAPH_GAP_MINUTES =
    2

@Composable
private fun rememberWallClockNowMs():
    Long {
    var nowMs by remember {
        mutableLongStateOf(
            System.currentTimeMillis()
        )
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(
                10_000L
            )

            nowMs =
                System.currentTimeMillis()
        }
    }

    return nowMs
}

private fun formatMissingMinutes(
    minutes: Long
): String =
    when {
        minutes < 60L ->
            "$minutes min"

        minutes % 60L == 0L ->
            "${minutes / 60L}h"

        else ->
            "${minutes / 60L}h ${minutes % 60L}m"
    }

private fun formatGraphReadingTime(
    timestampMs: Long,
    rangeHours: Int
): String {
    val formatter =
        if (rangeHours > 24) {
            DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.SHORT
            )
        } else {
            DateFormat.getTimeInstance(
                DateFormat.SHORT
            )
        }

    return formatter.format(
        Date(timestampMs)
    )
}

@Composable
private fun AlarmSettingsDialog(
    settings: AlarmSettings,
    glucoseLimits: GlucoseLimits,
    glucoseUnit: GlucoseUnit,
    notificationPermissionGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (AlarmSettings) -> Unit
) {
    val context = LocalContext.current
    val notificationManager = remember {
        context.getSystemService(
            NotificationManager::class.java
        )
    }

    var lowEnabled by remember(settings) {
        mutableStateOf(settings.lowEnabled)
    }
    var highEnabled by remember(settings) {
        mutableStateOf(settings.highEnabled)
    }
    var disconnectedEnabled by remember(settings) {
        mutableStateOf(settings.disconnectedEnabled)
    }
    var criticalOverrideEnabled by remember(settings) {
        mutableStateOf(settings.criticalOverrideEnabled)
    }
    var alarmVoice by remember(settings) {
        mutableStateOf(settings.alarmVoice)
    }
    var alarmTone by remember(settings) {
        mutableIntStateOf(
            settings.alarmTone
                .coerceIn(1, 10)
        )
    }
    var disconnectMinutesText by remember(settings) {
        mutableStateOf(settings.disconnectedMinutes.toString())
    }

    val disconnectMinutes =
        disconnectMinutesText.toIntOrNull()

    val delayValid =
        disconnectMinutes != null &&
        disconnectMinutes in 2..60

    val anyEnabled =
        lowEnabled ||
        highEnabled ||
        disconnectedEnabled

    val canSave =
        delayValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alarm Settings") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "High and low phone notifications are always active for the selected sensor. The switches below control only the audible tone, spoken warning, and vibration.",
                    style = MaterialTheme.typography.bodyMedium
                )

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Low glucose sound alarm",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Phone notification is always shown at or below ${glucoseUnit.formatGlucoseFromMmol(glucoseLimits.lowMmol)}. This switch adds sound and vibration.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = lowEnabled,
                        onCheckedChange = { lowEnabled = it }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "High glucose sound alarm",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Phone notification is always shown at or above ${glucoseUnit.formatGlucoseFromMmol(glucoseLimits.highMmol)}. This switch adds sound and vibration.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = highEnabled,
                        onCheckedChange = { highEnabled = it }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Disconnected sensor alarm",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Alarm when no valid reading is received.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = disconnectedEnabled,
                        onCheckedChange = {
                            disconnectedEnabled = it
                        }
                    )
                }

                OutlinedTextField(
                    value = disconnectMinutesText,
                    onValueChange = {
                        disconnectMinutesText =
                            it.filter { ch -> ch.isDigit() }
                    },
                    label = {
                        Text("Disconnected after (minutes)")
                    },
                    enabled = disconnectedEnabled,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    supportingText = {
                        Text("Allowed range: 2 to 60 minutes")
                    },
                    isError =
                        disconnectedEnabled &&
                        !delayValid,
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider()

                if (!notificationPermissionGranted) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor =
                                MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "Notification permission required",
                                fontWeight = FontWeight.Bold,
                                color =
                                    MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "Android must allow GlucoTrail notifications so High and Low glucose alerts can pop up on the phone. Audible alarms can still be configured separately.",
                                style = MaterialTheme.typography.bodySmall,
                                color =
                                    MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick =
                                    onRequestNotificationPermission
                            ) {
                                Text("Allow Notifications")
                            }
                        }
                    }
                } else {
                    Text(
                        "High and Low glucose notifications are enabled whenever a sensor is selected. GlucoTrail keeps a low-priority monitoring notification so background glucose monitoring can continue.",
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider()

                Text(
                    "Alarm Voice",
                    fontWeight = FontWeight.Bold
                )

                Text(
                    "Choose the voice used for High Sugar, Low Sugar, and Sensor Disconnected announcements.",
                    style =
                        MaterialTheme.typography.bodySmall,
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(10.dp)
                ) {
                    FilterChip(
                        selected =
                            alarmVoice ==
                                AlarmVoice.MALE,
                        onClick = {
                            alarmVoice =
                                AlarmVoice.MALE
                        },
                        label = {
                            Text("Male")
                        },
                        modifier =
                            Modifier.weight(1f)
                    )

                    FilterChip(
                        selected =
                            alarmVoice ==
                                AlarmVoice.FEMALE,
                        onClick = {
                            alarmVoice =
                                AlarmVoice.FEMALE
                        },
                        label = {
                            Text("Female")
                        },
                        modifier =
                            Modifier.weight(1f)
                    )
                }

                HorizontalDivider()

                Text(
                    "Alarm Tone",
                    fontWeight = FontWeight.Bold
                )

                Text(
                    "Choose one of 10 high-pitched warning patterns. The selected tone plays before the spoken alarm message.",
                    style =
                        MaterialTheme.typography.bodySmall,
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(
                    verticalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(6.dp)
                    ) {
                        for (
                            tone in 1..5
                        ) {
                            FilterChip(
                                selected =
                                    alarmTone ==
                                        tone,
                                onClick = {
                                    alarmTone =
                                        tone
                                },
                                label = {
                                    Text(
                                        tone.toString()
                                    )
                                },
                                modifier =
                                    Modifier.weight(1f)
                            )
                        }
                    }

                    Row(
                        modifier =
                            Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(6.dp)
                    ) {
                        for (
                            tone in 6..10
                        ) {
                            FilterChip(
                                selected =
                                    alarmTone ==
                                        tone,
                                onClick = {
                                    alarmTone =
                                        tone
                                },
                                label = {
                                    Text(
                                        tone.toString()
                                    )
                                },
                                modifier =
                                    Modifier.weight(1f)
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        AlarmSoundPlayer
                            .previewTone(
                                context =
                                    context,
                                alarmTone =
                                    alarmTone,
                                overrideVolume =
                                    criticalOverrideEnabled
                            )
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Preview Tone $alarmTone"
                    )
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Critical Alarm Override",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Temporarily raises Android alarm volume to maximum while AiDEX plays its built-in alarm tone and vibration, then restores the previous alarm volume.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = criticalOverrideEnabled,
                        onCheckedChange = {
                            criticalOverrideEnabled = it
                        }
                    )
                }

                if (
                    criticalOverrideEnabled &&
                    !notificationManager.isNotificationPolicyAccessGranted
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor =
                                MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "Do Not Disturb access recommended",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Android requires special Notification Policy access for alarm notification channels to bypass Do Not Disturb when policy allows it.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
                                        )
                                    )
                                }
                            ) {
                                Text("Allow DND Override")
                            }
                        }
                    }
                }

                HorizontalDivider()

                Text(
                    "Test alarm sounds",
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Test alarms use Tone $alarmTone and the selected ${alarmVoice.displayName.lowercase()} voice for High Sugar, Low Sugar, and Sensor Disconnected. Change the tone or voice above and test it immediately. With Critical Alarm Override enabled, alarm volume is temporarily forced to maximum.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedButton(
                    onClick = {
                        AlarmNotifications.testLow(
                            context,
                            glucoseLimits.lowMmol,
                            criticalOverrideEnabled,
                            alarmVoice,
                            alarmTone
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Test Low Alarm")
                }

                OutlinedButton(
                    onClick = {
                        AlarmNotifications.testHigh(
                            context,
                            glucoseLimits.highMmol,
                            criticalOverrideEnabled,
                            alarmVoice,
                            alarmTone
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Test High Alarm")
                }

                OutlinedButton(
                    onClick = {
                        AlarmNotifications.testDisconnected(
                            context,
                            disconnectMinutes ?: 5,
                            criticalOverrideEnabled,
                            alarmVoice,
                            alarmTone
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Test Disconnected Alarm")
                }

                Text(
                    "Android still controls some device/OEM policies. Critical Alarm Override is designed as a best-effort emergency alarm path, not a guarantee that every possible system restriction can be bypassed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    "High and Low phone notifications appear once per excursion whether or not their sound switches are enabled. Use Dismiss Alarm on the notification, or swipe it away, to stop the current sound/vibration. The alert re-arms after glucose returns inside the configured limit and crosses it again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        AlarmSettings(
                            lowEnabled = lowEnabled,
                            highEnabled = highEnabled,
                            disconnectedEnabled =
                                disconnectedEnabled,
                            disconnectedMinutes =
                                disconnectMinutes ?: 5,
                            criticalOverrideEnabled =
                                criticalOverrideEnabled,
                            alarmVoice =
                                alarmVoice,
                            alarmTone =
                                alarmTone
                        )
                    )
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun CalibrationDialog(
    rawSensor: AidexSensor?,
    calibrationEvents: List<CalibrationEvent>,
    glucoseUnit: GlucoseUnit,
    onApply:
        (
            AidexReading,
            Int
        ) -> Unit,
    onReset:
        (
            AidexReading
        ) -> Unit,
    onDismiss: () -> Unit
) {
    val rawReading =
        rawSensor
            ?.reading

    val latestCalibration =
        calibrationEvents
            .lastOrNull()

    val adjustedReading =
        rawReading
            ?.let {
                CalibrationTimeline
                    .applyReading(
                        reading =
                            it,
                        events =
                            calibrationEvents
                    )
            }

    var bloodText by remember(
        rawReading?.sensorMinute,
        glucoseUnit
    ) {
        mutableStateOf(
            ""
        )
    }

    var acknowledgeWarning by remember {
        mutableStateOf(
            false
        )
    }

    val bloodDisplay =
        bloodText
            .toDoubleOrNull()

    val bloodMgDl =
        bloodDisplay
            ?.let {
                glucoseUnit
                    .mmolFromDisplay(
                        it
                    ) *
                    18.0
            }
            ?.let {
                kotlin.math.round(
                    it
                )
                    .toInt()
            }

    val proposedOffsetMgDl =
        if (
            rawReading !=
            null &&
            bloodMgDl !=
            null
        ) {
            bloodMgDl -
                rawReading
                    .glucoseMgDl
        } else {
            null
        }

    val now =
        System.currentTimeMillis()

    val readingAgeMinutes =
        rawReading
            ?.let {
                (
                    now -
                        it.receivedAtMs
                )
                    .coerceAtLeast(
                        0L
                    ) /
                    60_000L
            }

    val sensorAgeHours =
        rawReading
            ?.sensorMinute
            ?.div(
                60.0
            )

    val rawMmol =
        rawReading
            ?.glucoseMmol

    val stableTrend =
        rawReading
            ?.rateMgDlPerMinute
            ?.let {
                kotlin.math.abs(
                    it
                ) <
                    1.0
            } ?: false

    val lastCalibrationAgeHours =
        latestCalibration
            ?.let {
                (
                    now -
                        it.createdAtMs
                )
                    .coerceAtLeast(
                        0L
                    ) /
                    3_600_000.0
            }

    val safetyWarnings =
        buildList {
            if (
                readingAgeMinutes !=
                null &&
                readingAgeMinutes >
                5
            ) {
                add(
                    "The current sensor reading is more than 5 minutes old."
                )
            }

            if (
                sensorAgeHours !=
                null &&
                sensorAgeHours <
                24.0
            ) {
                add(
                    "The sensor is still within its first 24 hours."
                )
            }

            if (
                rawMmol !=
                null &&
                (
                    rawMmol <
                        5.0 ||
                    rawMmol >
                        15.0
                )
            ) {
                add(
                    "The raw sensor value is outside 5–15 mmol/L."
                )
            }

            if (
                rawReading !=
                null &&
                !stableTrend
            ) {
                add(
                    "Glucose is changing rather than showing a stable horizontal trend."
                )
            }

            if (
                lastCalibrationAgeHours !=
                null &&
                lastCalibrationAgeHours <
                24.0
            ) {
                add(
                    "A calibration was already entered within the last 24 hours."
                )
            }
        }

    val inputValid =
        bloodMgDl !=
            null &&
            bloodMgDl in
            36..600 &&
            rawReading !=
            null &&
            rawReading.valid &&
            !rawReading.isWarmup

    val canApply =
        inputValid &&
            (
                safetyWarnings.isEmpty() ||
                    acknowledgeWarning
            )

    AlertDialog(
        onDismissRequest =
            onDismiss,
        title = {
            Text(
                "Calibration"
            )
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(
                            max =
                                560.dp
                        )
                        .verticalScroll(
                            rememberScrollState()
                        ),
                verticalArrangement =
                    Arrangement.spacedBy(
                        12.dp
                    )
            ) {
                Text(
                    "Calibration is applied only inside GlucoTrail. Raw sensor values remain unchanged in the database and no calibration command is written back to the sensor.",
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )

                if (
                    rawReading ==
                    null
                ) {
                    Card {
                        Text(
                            "No current sensor reading is available.",
                            modifier =
                                Modifier.padding(
                                    14.dp
                                ),
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .error
                        )
                    }

                    return@Column
                }

                Card(
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier =
                            Modifier.padding(
                                14.dp
                            ),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                6.dp
                            )
                    ) {
                        Text(
                            "Current Sensor",
                            fontWeight =
                                FontWeight.Bold
                        )

                        Text(
                            "Raw: ${
                                glucoseUnit
                                    .formatGlucoseFromMgDl(
                                        rawReading
                                            .glucoseMgDl
                                            .toDouble()
                                    )
                            }"
                        )

                        Text(
                            "Adjusted: ${
                                glucoseUnit
                                    .formatGlucoseFromMgDl(
                                        adjustedReading
                                            ?.glucoseMgDl
                                            ?.toDouble()
                                            ?: rawReading
                                                .glucoseMgDl
                                                .toDouble()
                                    )
                            }"
                        )

                        Text(
                            "Trend: ${rawReading.trendArrow}  ${
                                glucoseUnit
                                    .formatRateFromMgDlPerMinute(
                                        rawReading
                                            .rateMgDlPerMinute
                                    )
                            }",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall
                        )

                        Text(
                            "Sensor minute ${rawReading.sensorMinute} • reading age ${readingAgeMinutes ?: 0} min",
                            style =
                                MaterialTheme
                                    .typography
                                    .labelSmall,
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant
                        )
                    }
                }

                latestCalibration
                    ?.let {
                            event ->
                        Card(
                            modifier =
                                Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier =
                                    Modifier.padding(
                                        14.dp
                                    ),
                                verticalArrangement =
                                    Arrangement.spacedBy(
                                        4.dp
                                    )
                            ) {
                                Text(
                                    "Current Calibration",
                                    fontWeight =
                                        FontWeight.Bold
                                )

                                Text(
                                    if (
                                        event.offsetMgDl ==
                                        0
                                    ) {
                                        "Factory/raw values"
                                    } else {
                                        "Offset ${
                                            if (
                                                event.offsetMgDl >
                                                0
                                            ) {
                                                "+"
                                            } else {
                                                ""
                                            }
                                        }${
                                            glucoseUnit
                                                .formatValueFromMgDl(
                                                    event
                                                        .offsetMgDl
                                                        .toDouble()
                                                )
                                        } ${glucoseUnit.label}"
                                    }
                                )

                                Text(
                                    "Blood ${
                                        glucoseUnit
                                            .formatGlucoseFromMgDl(
                                                event
                                                    .bloodMgDl
                                                    .toDouble()
                                            )
                                    } • Raw ${
                                        glucoseUnit
                                            .formatGlucoseFromMgDl(
                                                event
                                                    .rawCgmMgDl
                                                    .toDouble()
                                            )
                                    }",
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall
                                )

                                Text(
                                    DateFormat
                                        .getDateTimeInstance(
                                            DateFormat.SHORT,
                                            DateFormat.SHORT
                                        )
                                        .format(
                                            Date(
                                                event
                                                    .createdAtMs
                                            )
                                        ),
                                    style =
                                        MaterialTheme
                                            .typography
                                            .labelSmall,
                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .onSurfaceVariant
                                )
                            }
                        }
                    }

                OutlinedTextField(
                    value =
                        bloodText,
                    onValueChange = {
                        bloodText =
                            it
                                .filter {
                                        ch ->
                                    ch.isDigit() ||
                                        ch ==
                                        '.'
                                }
                                .take(
                                    6
                                )
                    },
                    label = {
                        Text(
                            "Fingerstick blood glucose (${glucoseUnit.label})"
                        )
                    },
                    supportingText = {
                        Text(
                            "Enter the blood glucose meter result measured at the same time as the current sensor reading."
                        )
                    },
                    singleLine =
                        true,
                    modifier =
                        Modifier.fillMaxWidth()
                )

                proposedOffsetMgDl
                    ?.let {
                            offset ->
                        Card(
                            colors =
                                CardDefaults
                                    .cardColors(
                                        containerColor =
                                            MaterialTheme
                                                .colorScheme
                                                .primaryContainer
                                    )
                        ) {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            14.dp
                                        ),
                                verticalArrangement =
                                    Arrangement.spacedBy(
                                        4.dp
                                    )
                            ) {
                                Text(
                                    "Proposed calibration",
                                    fontWeight =
                                        FontWeight.Bold
                                )

                                Text(
                                    "Blood ${
                                        bloodMgDl
                                            ?.let {
                                                glucoseUnit
                                                    .formatGlucoseFromMgDl(
                                                        it.toDouble()
                                                    )
                                            }
                                            ?: ""
                                    }"
                                )

                                Text(
                                    "Offset ${
                                        if (
                                            offset >
                                            0
                                        ) {
                                            "+"
                                        } else {
                                            ""
                                        }
                                    }${
                                        glucoseUnit
                                            .formatValueFromMgDl(
                                                offset
                                                    .toDouble()
                                            )
                                    } ${glucoseUnit.label}"
                                )

                                Text(
                                    "The current adjusted value will become ${
                                        bloodMgDl
                                            ?.let {
                                                glucoseUnit
                                                    .formatGlucoseFromMgDl(
                                                        it.toDouble()
                                                    )
                                            }
                                            ?: ""
                                    }.",
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall
                                )
                            }
                        }
                    }

                if (
                    safetyWarnings.isNotEmpty()
                ) {
                    Card(
                        colors =
                            CardDefaults
                                .cardColors(
                                    containerColor =
                                        MaterialTheme
                                            .colorScheme
                                            .errorContainer
                                )
                    ) {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        14.dp
                                    ),
                            verticalArrangement =
                                Arrangement.spacedBy(
                                    6.dp
                                )
                        ) {
                            Text(
                                "Calibration conditions to review",
                                fontWeight =
                                    FontWeight.Bold
                            )

                            safetyWarnings.forEach {
                                    warning ->
                                Text(
                                    "• $warning",
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall
                                )
                            }

                            Row(
                                modifier =
                                    Modifier.fillMaxWidth(),
                                verticalAlignment =
                                    Alignment.Top
                            ) {
                                Checkbox(
                                    checked =
                                        acknowledgeWarning,
                                    onCheckedChange = {
                                        acknowledgeWarning =
                                            it
                                    }
                                )

                                Text(
                                    "I understand these conditions and still want to apply this calibration.",
                                    modifier =
                                        Modifier
                                            .weight(
                                                1f
                                            )
                                            .padding(
                                                top =
                                                    11.dp
                                            ),
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall
                                )
                            }
                        }
                    }
                }

                if (
                    calibrationEvents.size >
                    1
                ) {
                    HorizontalDivider()

                    Text(
                        "Recent Calibration History",
                        fontWeight =
                            FontWeight.Bold
                    )

                    calibrationEvents
                        .takeLast(
                            5
                        )
                        .asReversed()
                        .forEach {
                                event ->
                            Text(
                                "${
                                    DateFormat
                                        .getDateTimeInstance(
                                            DateFormat.SHORT,
                                            DateFormat.SHORT
                                        )
                                        .format(
                                            Date(
                                                event.createdAtMs
                                            )
                                        )
                                }: ${
                                    if (
                                        event.offsetMgDl >=
                                        0
                                    ) {
                                        "+"
                                    } else {
                                        ""
                                    }
                                }${
                                    glucoseUnit
                                        .formatValueFromMgDl(
                                            event
                                                .offsetMgDl
                                                .toDouble()
                                        )
                                } ${glucoseUnit.label}",
                                style =
                                    MaterialTheme
                                        .typography
                                        .labelSmall
                            )
                        }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled =
                    canApply,
                onClick = {
                    val reading =
                        rawReading
                            ?: return@TextButton

                    val blood =
                        bloodMgDl
                            ?: return@TextButton

                    onApply(
                        reading,
                        blood
                    )
                }
            ) {
                Text(
                    "Apply Calibration"
                )
            }
        },
        dismissButton = {
            Row {
                if (
                    rawReading !=
                    null &&
                    latestCalibration !=
                    null &&
                    latestCalibration.offsetMgDl !=
                    0
                ) {
                    TextButton(
                        onClick = {
                            onReset(
                                rawReading
                            )
                        }
                    ) {
                        Text(
                            "Reset"
                        )
                    }
                }

                TextButton(
                    onClick =
                        onDismiss
                ) {
                    Text(
                        "Cancel"
                    )
                }
            }
        }
    )
}

@Composable
private fun A1cEstimateDialog(
    history: List<AidexReading>,
    glucoseUnit: GlucoseUnit,
    onDismiss: () -> Unit
) {
    val validReadings = remember(history) {
        history
            .filter {
                it.crcValid &&
                    it.valid &&
                    !it.isWarmup &&
                    it.glucoseMgDl > 0
            }
            .sortedBy { it.receivedAtMs }
    }

    val automaticAverageMgDl = remember(validReadings) {
        if (validReadings.isEmpty()) {
            null
        } else {
            validReadings
                .map { it.glucoseMgDl.toDouble() }
                .average()
        }
    }

    val automaticAverageDisplay =
        automaticAverageMgDl?.let {
            glucoseUnit.valueFromMgDl(it)
        }

    var manualText by remember(
        automaticAverageDisplay,
        glucoseUnit
    ) {
        mutableStateOf(
            automaticAverageDisplay?.let {
                if (glucoseUnit == GlucoseUnit.MMOL_L) {
                    String.format("%.1f", it)
                } else {
                    String.format("%.0f", it)
                }
            } ?: ""
        )
    }

    val enteredAverage = manualText.toDoubleOrNull()
    val estimate = enteredAverage
        ?.takeIf { it > 0.0 }
        ?.let {
            if (glucoseUnit == GlucoseUnit.MMOL_L) {
                A1cCalculator.fromAverageMmol(it)
            } else {
                A1cCalculator.fromAverageMgDl(it)
            }
        }

    val firstTime =
        validReadings.firstOrNull()?.receivedAtMs
    val lastTime =
        validReadings.lastOrNull()?.receivedAtMs

    val spanMinutes =
        if (firstTime != null && lastTime != null) {
            ((lastTime - firstTime) / 60_000L)
                .coerceAtLeast(0L)
        } else {
            0L
        }

    val spanHours = spanMinutes / 60.0
    val spanDays = spanHours / 24.0

    val expectedReadings =
        if (spanMinutes > 0) {
            spanMinutes + 1
        } else {
            validReadings.size.toLong()
        }

    val coveragePercent =
        if (expectedReadings > 0) {
            (
                validReadings.size.toDouble() /
                    expectedReadings.toDouble() *
                    100.0
            ).coerceIn(0.0, 100.0)
        } else {
            0.0
        }

    val dataConfidenceText = when {
        validReadings.isEmpty() ->
            "No sensor history is available yet. Enter an average glucose manually."
        spanDays >= 14.0 &&
            coveragePercent >= 70.0 ->
            "CGM data coverage is strong for an estimate."
        else ->
            "Preliminary estimate. More CGM data improves how representative the average is."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("A1C Estimate") },
        text = {
            Column(
                verticalArrangement =
                    Arrangement.spacedBy(12.dp)
            ) {
                if (estimate != null) {
                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor =
                                    MaterialTheme.colorScheme.primaryContainer
                            )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment =
                                Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Estimated A1C (GMI)",
                                style =
                                    MaterialTheme.typography.labelLarge
                            )

                            Text(
                                "${String.format("%.1f", estimate.gmiPercent)}%",
                                style =
                                    MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                "Average ${
                                    if (
                                        glucoseUnit ==
                                        GlucoseUnit.MMOL_L
                                    ) {
                                        glucoseUnit
                                            .formatGlucoseFromMmol(
                                                estimate.averageMmol
                                            )
                                    } else {
                                        glucoseUnit
                                            .formatGlucoseFromMgDl(
                                                estimate.averageMgDl
                                            )
                                    }
                                }",
                                style =
                                    MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = manualText,
                    onValueChange = {
                        manualText = it
                    },
                    label = {
                        Text(
                            "Average glucose (${glucoseUnit.label})"
                        )
                    },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                KeyboardType.Decimal
                        ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (automaticAverageDisplay != null) {
                    OutlinedButton(
                        onClick = {
                            manualText =
                                if (
                                    glucoseUnit ==
                                    GlucoseUnit.MMOL_L
                                ) {
                                    String.format(
                                        "%.1f",
                                        automaticAverageDisplay
                                    )
                                } else {
                                    String.format(
                                        "%.0f",
                                        automaticAverageDisplay
                                    )
                                }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Use Sensor Average")
                    }

                    Text(
                        "Sensor data: ${validReadings.size} readings • ${String.format("%.1f", spanHours)} hours • ${String.format("%.0f", coveragePercent)}% coverage",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    dataConfidenceText,
                    style =
                        MaterialTheme.typography.bodySmall
                )

                HorizontalDivider()

                Text(
                    "This is a CGM-derived estimate, also called GMI. It is not a laboratory HbA1c measurement and the two values can differ.",
                    style =
                        MaterialTheme.typography.bodySmall,
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
private fun GlucoseLimitsDialog(
    limits: GlucoseLimits,
    glucoseUnit: GlucoseUnit,
    onDismiss: () -> Unit,
    onSave: (Double, Double) -> Unit
) {
    var lowText by remember(
        limits.lowMmol,
        glucoseUnit
    ) {
        mutableStateOf(
            glucoseUnit.formatValueFromMmol(
                limits.lowMmol
            )
        )
    }

    var highText by remember(
        limits.highMmol,
        glucoseUnit
    ) {
        mutableStateOf(
            glucoseUnit.formatValueFromMmol(
                limits.highMmol
            )
        )
    }

    val lowDisplayValue =
        lowText.toDoubleOrNull()
    val highDisplayValue =
        highText.toDoubleOrNull()

    val lowMmol =
        lowDisplayValue?.let {
            glucoseUnit.mmolFromDisplay(it)
        }

    val highMmol =
        highDisplayValue?.let {
            glucoseUnit.mmolFromDisplay(it)
        }

    val valuesValid =
        lowMmol != null &&
            highMmol != null &&
            lowMmol > 0.0 &&
            highMmol > lowMmol

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Glucose Limits") },
        text = {
            Column(
                verticalArrangement =
                    Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Set the low and high glucose thresholds used on the dashboard and alarms.",
                    style =
                        MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = lowText,
                    onValueChange = {
                        lowText = it
                    },
                    label = {
                        Text(
                            "Low limit (${glucoseUnit.label})"
                        )
                    },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                KeyboardType.Decimal
                        ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = highText,
                    onValueChange = {
                        highText = it
                    },
                    label = {
                        Text(
                            "High limit (${glucoseUnit.label})"
                        )
                    },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                KeyboardType.Decimal
                        ),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "Current: ${glucoseUnit.formatGlucoseFromMmol(limits.lowMmol)} – ${glucoseUnit.formatGlucoseFromMmol(limits.highMmol)}",
                    style =
                        MaterialTheme.typography.bodySmall,
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!valuesValid) {
                    Text(
                        "Enter valid numbers with the high limit greater than the low limit.",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valuesValid,
                onClick = {
                    if (
                        lowMmol != null &&
                        highMmol != null &&
                        valuesValid
                    ) {
                        onSave(lowMmol, highMmol)
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ExistingSensorScreen(
    vm: AidexViewModel,
    selectedSerial: String?,
    glucoseUnit: GlucoseUnit,
    onSelect: (AidexSensor) -> Unit,
    onBack: () -> Unit
) {
    val sensors by vm.sensors.collectAsState()
    val scanStatus by vm.scanStatus.collectAsState()

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Select sensor", style = MaterialTheme.typography.titleLarge)
    }

    Spacer(Modifier.height(8.dp))

    if (sensors.isEmpty()) {
        Text("Scanning for AiDEX X / LinX…", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("BLE packets seen: ${scanStatus.totalResults}", style = MaterialTheme.typography.bodySmall)
        Text("0x181F packets: ${scanStatus.service181fResults}", style = MaterialTheme.typography.bodySmall)
        Text("0x0059 packets: ${scanStatus.manufacturer0059Results}", style = MaterialTheme.typography.bodySmall)
        Text("AiDEX-name packets: ${scanStatus.aidexNameResults}", style = MaterialTheme.typography.bodySmall)
        Text("AiDEX candidates: ${scanStatus.aidexCandidates}", style = MaterialTheme.typography.bodySmall)

        scanStatus.lastError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Any AiDEX X sensors detected nearby will appear here so you can choose the correct serial number.",
            style = MaterialTheme.typography.bodySmall
        )
    } else {
        Text(
            "${sensors.size} AiDEX X sensor${if (sensors.size == 1) "" else "s"} found",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
        ) {
            items(
                items = sensors,
                key = { it.address }
            ) { sensor ->
                SensorSelectionCard(
                    sensor = sensor,
                    currentlySelected = selectedSerial?.equals(sensor.serial, ignoreCase = true) == true,
                    glucoseUnit = glucoseUnit,
                    onSelect = { onSelect(sensor) }
                )
            }
        }
    }
}

@Composable
private fun SensorSelectionCard(
    sensor: AidexSensor,
    currentlySelected: Boolean,
    glucoseUnit: GlucoseUnit,
    onSelect: () -> Unit
) {
    val r = sensor.reading

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    sensor.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (currentlySelected) {
                    Text(
                        "Current",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text("Serial: ${sensor.serial}")
            Text("BLE: ${sensor.address}", style = MaterialTheme.typography.bodySmall)
            Text("Signal: ${sensor.rssi} dBm", style = MaterialTheme.typography.bodySmall)

            if (r != null && r.crcValid) {
                Spacer(Modifier.height(8.dp))
                if (r.isWarmup) {
                    Text("Warm-up • Sensor minute ${r.sensorMinute}")
                } else if (r.valid) {
                    Text(
                        "${glucoseUnit.formatGlucoseFromMgDl(r.glucoseMgDl.toDouble())}  ${r.trendArrow}",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Quality ${r.quality}"
                    )
                } else {
                    Text("Sensor found • waiting for a valid glucose packet")
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text("Sensor found • waiting for glucose advertisement")
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onSelect,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (currentlySelected) "Use This Sensor" else "Select This Sensor")
            }
        }
    }
}

@Composable
private fun SensorCard(
    sensor: AidexSensor,
    glucoseUnit: GlucoseUnit
) {
    val r = sensor.reading
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(sensor.name, fontWeight = FontWeight.Bold)
            Text("RSSI ${sensor.rssi} dBm")
            if (r == null) {
                Text("Advertisement found, glucose payload unavailable")
                return@Column
            }
            if (!r.crcValid) {
                Text("CRC FAILED", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                return@Column
            }
            Spacer(Modifier.height(8.dp))
            if (r.isWarmup) {
                Text("Warm-up: ${60 - r.sensorMinute} min remaining", style = MaterialTheme.typography.titleMedium)
            } else if (r.valid) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        glucoseUnit.formatValueFromMgDl(
                            r.glucoseMgDl.toDouble()
                        ),
                        style =
                            MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(r.trendArrow, style = MaterialTheme.typography.displaySmall)
                }
                Text(glucoseUnit.label)
                Text(
                    "Rate ${glucoseUnit.formatRateFromMgDlPerMinute(r.rateMgDlPerMinute)}"
                )
                Text(
                    "Previous ${
                        r.previousMgDl?.let {
                            glucoseUnit.formatValueFromMgDl(
                                it.toDouble()
                            )
                        } ?: "—"
                    } / ${
                        r.previous2MgDl?.let {
                            glucoseUnit.formatValueFromMgDl(
                                it.toDouble()
                            )
                        } ?: "—"
                    } ${glucoseUnit.label}"
                )
            } else {
                Text("Sensor packet is not marked valid")
            }
            Text("Sensor minute ${r.sensorMinute} • Quality ${r.quality} • CRC OK")
        }
    }
}

@Composable
private fun StartSensorScreen(
    vm: AidexViewModel,
    replacementMode: Boolean = false,
    initialSerial: String? =
        null,
    initialBleAddress: String? =
        null,
    repairMode: Boolean =
        false,
    onBack: () -> Unit
) {
    val context =
        LocalContext.current

    val nearbySensors by
        vm.sensors
            .collectAsState()

    val scanStatus by
        vm.scanStatus
            .collectAsState()

    val glucoseUnit by
        vm.glucoseUnit
            .collectAsState()

    var selectedBleAddress by remember(
        initialBleAddress,
        repairMode
    ) {
        mutableStateOf<String?>(
            initialBleAddress
        )
    }

    var serial by remember(
        initialSerial,
        repairMode
    ) {
        mutableStateOf(
            initialSerial
                ?.trim()
                ?.uppercase()
                .orEmpty()
        )
    }

    var agreed by remember(
        repairMode
    ) {
        mutableStateOf(
            false
        )
    }

    val activation by
        vm.activation
            .collectAsState()

    val scannerOptions =
        remember {
            GmsBarcodeScannerOptions
                .Builder()
                .enableAutoZoom()
                .build()
        }

    val codeScanner =
        remember {
            GmsBarcodeScanning
                .getClient(
                    context,
                    scannerOptions
                )
        }

    LaunchedEffect(
        activation.stage,
        activation.serial
    ) {
        if (
            activation.stage ==
            ActivationStage.STARTED &&
            AidexProtocol
                .isValidSerial(
                    activation.serial
                )
        ) {
            vm.recordSensorActivation(activation.serial)
            if (replacementMode) {
                val address = activation.address ?: selectedBleAddress
                if (!address.isNullOrBlank()) {
                    vm.configureReplacementSensor(
                        AidexSensor(
                            name = "AiDEX X-${activation.serial}",
                            serial = activation.serial,
                            address = address,
                            rssi = -100,
                            reading = null
                        )
                    )
                }
            } else {
                vm.selectSensor(activation.serial)
            }
            vm.cancelActivation()
            onBack()
        }
    }

    val activationRunning =
        activation.stage in
            setOf(
                ActivationStage
                    .CONNECTING,
                ActivationStage
                    .DISCOVERING_SERVICES,
                ActivationStage
                    .REQUESTING_MASTER_KEY,
                ActivationStage
                    .READING_SESSION_KEY,
                ActivationStage
                    .REQUESTING_DEVICE_INFO,
                ActivationStage
                    .BONDING,
                ActivationStage
                    .SETTING_TIME,
                ActivationStage
                    .FINALIZING
            )

    val serialValid =
        AidexProtocol
            .isValidSerial(
                serial
            )

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize(),
        verticalArrangement =
            Arrangement.spacedBy(
                12.dp
            ),
        contentPadding =
            PaddingValues(
                bottom =
                    28.dp
            )
    ) {
        item {
            Column(
                verticalArrangement =
                    Arrangement.spacedBy(
                        4.dp
                    )
            ) {
                Text(
                    if (
                        repairMode
                    ) {
                        "Complete Sensor Activation"
                    } else {
                        if (replacementMode) "Activate Replacement Sensor" else "Start New Sensor"
                    },
                    style =
                        MaterialTheme
                            .typography
                            .headlineSmall,
                    fontWeight =
                        FontWeight.Bold
                )

                Text(
                    if (
                        repairMode
                    ) {
                        "GlucoTrail securely read this sensor's start-time state and found it uninitialized. This page will run the controlled startup sequence for the selected sensor."
                    } else {
                        "Use this only for a new AiDEX X sensor that has not already been started."
                    },
                    style =
                        MaterialTheme
                            .typography
                            .bodyMedium,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }
        }

        item {
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                16.dp
                            ),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            8.dp
                        )
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement
                                .SpaceBetween,
                        verticalAlignment =
                            Alignment
                                .CenterVertically
                    ) {
                        Text(
                            "Nearby Bluetooth sensors",
                            style =
                                MaterialTheme
                                    .typography
                                    .titleMedium,
                            fontWeight =
                                FontWeight.Bold
                        )

                        if (
                            scanStatus.running
                        ) {
                            CircularProgressIndicator(
                                modifier =
                                    Modifier
                                        .size(
                                            20.dp
                                        ),
                                strokeWidth =
                                    2.dp
                            )
                        }
                    }

                    Text(
                        if (
                            nearbySensors
                                .isEmpty()
                        ) {
                            "Scanning for nearby AiDEX X / LinX sensors…"
                        } else {
                            "${nearbySensors.size} compatible sensor${if (nearbySensors.size == 1) "" else "s"} found. Tap the new sensor below."
                        },
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )

                    if (
                        nearbySensors
                            .isEmpty()
                    ) {
                        Text(
                            "BLE packets: ${scanStatus.totalResults} · AiDEX candidates: ${scanStatus.aidexCandidates}",
                            style =
                                MaterialTheme
                                    .typography
                                    .labelSmall
                        )
                    }

                    scanStatus
                        .lastError
                        ?.let {
                                error ->
                            Text(
                                error,
                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .error,
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall
                            )
                        }
                }
            }
        }

        items(
            items =
                nearbySensors,
            key = {
                    sensor ->
                sensor.address
            }
        ) {
                sensor ->
            val selected =
                selectedBleAddress
                    ?.equals(
                        sensor.address,
                        ignoreCase =
                            true
                    ) ==
                    true

            Card(
                modifier =
                    Modifier
                        .fillMaxWidth(),
                colors =
                    CardDefaults
                        .cardColors(
                            containerColor =
                                if (
                                    selected
                                ) {
                                    MaterialTheme
                                        .colorScheme
                                        .primaryContainer
                                } else {
                                    MaterialTheme
                                        .colorScheme
                                        .surfaceVariant
                                }
                        )
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                14.dp
                            ),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            5.dp
                        )
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement
                                .SpaceBetween,
                        verticalAlignment =
                            Alignment
                                .CenterVertically
                    ) {
                        Text(
                            sensor.name,
                            fontWeight =
                                FontWeight.Bold
                        )

                        if (
                            selected
                        ) {
                            Text(
                                "Selected",
                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .primary,
                                fontWeight =
                                    FontWeight.Bold
                            )
                        }
                    }

                    Text(
                        "Serial: ${sensor.serial}"
                    )

                    Text(
                        "BLE: ${sensor.address} · Signal: ${sensor.rssi} dBm",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall
                    )

                    sensor.reading
                        ?.let {
                                reading ->
                            if (
                                reading.crcValid &&
                                reading.valid &&
                                !reading.isWarmup
                            ) {
                                Text(
                                    "${glucoseUnit.formatGlucoseFromMgDl(reading.glucoseMgDl.toDouble())} ${reading.trendArrow} · sensor minute ${reading.sensorMinute}",
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall
                                )
                            } else if (
                                reading.isWarmup
                            ) {
                                Text(
                                    "Warm-up · sensor minute ${reading.sensorMinute}",
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall
                                )
                            }
                        }

                    Button(
                        enabled =
                            !activationRunning &&
                                AidexProtocol
                                    .isValidSerial(
                                        sensor.serial
                                    ),
                        onClick = {
                            serial =
                                sensor.serial
                                    .trim()
                                    .uppercase()
                            selectedBleAddress =
                                sensor.address
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                    ) {
                        Text(
                            if (
                                selected
                            ) {
                                "Selected"
                            } else {
                                "Select This Sensor"
                            }
                        )
                    }
                }
            }
        }

        item {
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                16.dp
                            ),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            12.dp
                        )
                ) {
                    Text(
                        "Manual / QR fallback",
                        style =
                            MaterialTheme
                                .typography
                                .titleMedium,
                        fontWeight =
                            FontWeight.Bold
                    )

                    OutlinedTextField(
                        value =
                            serial,
                        onValueChange = {
                            selectedBleAddress =
                                null
                            serial =
                                it
                                    .trim()
                                    .uppercase()
                        },
                        label = {
                            Text(
                                "Sensor serial"
                            )
                        },
                        supportingText = {
                            when {
                                serial.isBlank() ->
                                    Text(
                                        "Choose a nearby Bluetooth sensor above, or enter/scan the serial manually."
                                    )

                                serialValid ->
                                    Text(
                                        "Sensor serial looks valid."
                                    )

                                else ->
                                    Text(
                                        "Serial format is not valid yet."
                                    )
                            }
                        },
                        isError =
                            serial.isNotBlank() &&
                                !serialValid,
                        singleLine =
                            true,
                        enabled =
                            !activationRunning,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                    )

                    OutlinedButton(
                        onClick = {
                            codeScanner
                                .startScan()
                                .addOnSuccessListener {
                                        barcode ->
                                    selectedBleAddress =
                                        null
                                    serial =
                                        barcode
                                            .rawValue
                                            ?.trim()
                                            ?.uppercase()
                                            .orEmpty()
                                }
                        },
                        enabled =
                            !activationRunning,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(
                                    min =
                                        48.dp
                                )
                    ) {
                        Text(
                            "Scan QR / Data Matrix"
                        )
                    }
                }
            }
        }

        item {
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                16.dp
                            ),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            10.dp
                        )
                ) {
                    Text(
                        "Safety Confirmation",
                        style =
                            MaterialTheme
                                .typography
                                .titleMedium,
                        fontWeight =
                            FontWeight.Bold
                    )

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth(),
                        verticalAlignment =
                            Alignment.Top
                    ) {
                        Checkbox(
                            checked =
                                agreed,
                            onCheckedChange = {
                                agreed =
                                    it
                            },
                            enabled =
                                !activationRunning
                        )

                        Spacer(
                            Modifier.width(
                                8.dp
                            )
                        )

                        Text(
                            if (
                                repairMode
                            ) {
                                "I understand that GlucoTrail detected an uninitialized sensor start time and that completing activation will write the sensor clock/start state using the experimental startup sequence."
                            } else {
                                "I understand that new-sensor activation in GlucoTrail is experimental and has only been validated against a limited number of known-good startup captures."
                            },
                            modifier =
                                Modifier
                                    .weight(
                                        1f
                                    )
                                    .padding(
                                        top =
                                            10.dp
                                    ),
                            style =
                                MaterialTheme
                                    .typography
                                    .bodyMedium
                        )
                    }

                    Text(
                        "The official AiDEX application should remain available while this activation workflow is being validated.",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )
                }
            }
        }

        item {
            Button(
                enabled =
                    serialValid &&
                        agreed &&
                        !activationRunning,
                onClick = {
                    val selectedAddress =
                        selectedBleAddress

                    if (
                        !selectedAddress
                            .isNullOrBlank()
                    ) {
                        val manager =
                            context
                                .getSystemService(
                                    BluetoothManager::class.java
                                )

                        val adapter =
                            manager
                                .adapter

                        val device =
                            runCatching {
                                adapter
                                    ?.getRemoteDevice(
                                        selectedAddress
                                    )
                            }
                                .getOrNull()

                        if (
                            device !=
                            null
                        ) {
                            vm.activate(
                                device,
                                serial,
                                preservePrimaryMonitoring = replacementMode
                            )
                        } else {
                            scanForExactSensor(
                                context,
                                serial
                            ) {
                                    fallbackDevice ->
                                vm.activate(
                                    fallbackDevice,
                                    serial,
                                    preservePrimaryMonitoring = replacementMode
                                )
                            }
                        }
                    } else {
                        scanForExactSensor(
                            context,
                            serial
                        ) {
                                device ->
                            vm.activate(
                                device,
                                serial,
                                preservePrimaryMonitoring = replacementMode
                            )
                        }
                    }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(
                            min =
                                54.dp
                        )
            ) {
                Text(
                    if (
                        activationRunning
                    ) {
                        if (
                            repairMode
                        ) {
                            "Completing Activation…"
                        } else {
                            "Starting Sensor…"
                        }
                    } else if (
                        repairMode
                    ) {
                        "Complete Sensor Activation"
                    } else {
                        if (
                            selectedBleAddress !=
                            null
                        ) {
                            "Start Selected Sensor"
                        } else {
                            "Find and Start Sensor"
                        }
                    }
                )
            }
        }

        item {
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                16.dp
                            ),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            6.dp
                        )
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth(),
                        verticalAlignment =
                            Alignment.CenterVertically,
                        horizontalArrangement =
                            Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Activation Status",
                            style =
                                MaterialTheme
                                    .typography
                                    .titleMedium,
                            fontWeight =
                                FontWeight.Bold
                        )

                        if (
                            activationRunning
                        ) {
                            CircularProgressIndicator(
                                modifier =
                                    Modifier.size(
                                        22.dp
                                    ),
                                strokeWidth =
                                    3.dp
                            )
                        }
                    }

                    Text(
                        activation.message,
                        style =
                            MaterialTheme
                                .typography
                                .bodyMedium,
                        fontWeight =
                            FontWeight.SemiBold
                    )

                    activation.address
                        ?.let {
                            Text(
                                "BLE: $it",
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall
                            )
                        }

                    if (
                        activation.stage ==
                        ActivationStage.STARTED
                    ) {
                        Text(
                            "Sensor startup completed. Return to the main screen and select/monitor the active sensor.",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .primary
                        )
                    }

                    activation.error
                        ?.let {
                            Text(
                                it,
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,
                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .error
                            )
                        }
                }
            }
        }

        if (
            activation.log
                .isNotEmpty()
        ) {
            item {
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    16.dp
                                ),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                8.dp
                            )
                    ) {
                        Text(
                            "Activation Log",
                            style =
                                MaterialTheme
                                    .typography
                                    .titleMedium,
                            fontWeight =
                                FontWeight.Bold
                        )

                        activation.log
                            .takeLast(
                                20
                            )
                            .forEach {
                                    line ->
                                Text(
                                    "• $line",
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall
                                )
                            }
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = {
                    vm.cancelActivation()
                    onBack()
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(
                            min =
                                48.dp
                        )
            ) {
                Text(
                    "← Return to Main Screen"
                )
            }
        }
    }
}

@Suppress("MissingPermission")
private fun scanForExactSensor(
    context: android.content.Context,
    serial: String,
    onFound: (android.bluetooth.BluetoothDevice) -> Unit
) {
    val manager = context.getSystemService(BluetoothManager::class.java)
    val adapter: BluetoothAdapter = manager.adapter ?: return
    val scanner = adapter.bluetoothLeScanner ?: return
    val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .setLegacy(true)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .build()

    lateinit var callback: ScanCallback
    callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val raw = RawAdvertisementParser.parse(record.bytes)
            val fallbackName = try { result.device.name } catch (_: SecurityException) { null }
            val name = raw.localName ?: record.deviceName ?: fallbackName ?: return
            val foundSerial = AidexProtocol.serialFromName(name) ?: return
            if (foundSerial.equals(serial, ignoreCase = true)) {
                scanner.stopScan(this)
                onFound(result.device)
            }
        }
    }

    // Deliberately unfiltered for AiDEX X legacy ADV + scan response compatibility.
    scanner.startScan(null, settings, callback)
}

@Composable
private fun ContinuousMonitoringDialog(
    selectedSerial: String?,
    onRestart: () -> Unit,
    onDismiss: () -> Unit
) {
    val context =
        LocalContext.current

    val wallClockNowMs =
        rememberWallClockNowMs()

    val snapshot =
        ContinuousMonitoringStatus
            .read(
                context
            )

    val powerManager =
        context.getSystemService(
            PowerManager::class.java
        )

    val batteryOptimizationExempt =
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.M
        ) {
            powerManager
                .isIgnoringBatteryOptimizations(
                    context.packageName
                )
        } else {
            true
        }

    val heartbeatActive =
        selectedSerial != null &&
            snapshot.isHeartbeatFresh(
                wallClockNowMs
            )

    fun ageText(
        timestampMs: Long
    ): String {
        if (
            timestampMs <= 0L
        ) {
            return "Never"
        }

        val minutes =
            (
                (
                    wallClockNowMs -
                        timestampMs
                )
                    .coerceAtLeast(
                        0L
                    ) /
                    60_000L
            )

        return when {
            minutes <= 0L ->
                "Just now"

            minutes == 1L ->
                "1 minute ago"

            minutes < 60L ->
                "$minutes minutes ago"

            else ->
                "${minutes / 60L}h ${minutes % 60L}m ago"
        }
    }

    AlertDialog(
        onDismissRequest =
            onDismiss,
        title = {
            Text(
                "24/7 Monitoring"
            )
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(
                            rememberScrollState()
                        ),
                verticalArrangement =
                    Arrangement.spacedBy(
                        12.dp
                    )
            ) {
                Card(
                    colors =
                        CardDefaults
                            .cardColors(
                                containerColor =
                                    if (
                                        heartbeatActive
                                    ) {
                                        MaterialTheme
                                            .colorScheme
                                            .primaryContainer
                                    } else {
                                        MaterialTheme
                                            .colorScheme
                                            .errorContainer
                                    }
                            )
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    14.dp
                                ),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                4.dp
                            )
                    ) {
                        Text(
                            if (
                                heartbeatActive
                            ) {
                                "Monitoring service is running"
                            } else {
                                "Monitoring service heartbeat is stale"
                            },
                            fontWeight =
                                FontWeight.Bold
                        )

                        Text(
                            "Sensor: ${
                                selectedSerial
                                    ?: "None selected"
                            }",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall
                        )

                        Text(
                            "Heartbeat: ${
                                ageText(
                                    snapshot
                                        .lastHeartbeatMs
                                )
                            }",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall
                        )

                        Text(
                            "Last start: ${
                                ageText(
                                    snapshot
                                        .lastStartedMs
                                )
                            }",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall
                        )

                        Text(
                            "Start reason: ${
                                snapshot
                                    .lastStartReason
                                    ?: "Unknown"
                            }",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall
                        )

                        Text(
                            "Screen-off CPU lock: ${
                                if (
                                    snapshot
                                        .wakeLockHeld
                                ) {
                                    "Active"
                                } else {
                                    "Inactive"
                                }
                            }",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                            color =
                                if (
                                    snapshot
                                        .wakeLockHeld
                                ) {
                                    MaterialTheme
                                        .colorScheme
                                        .primary
                                } else {
                                    MaterialTheme
                                        .colorScheme
                                        .error
                                },
                            fontWeight =
                                FontWeight.SemiBold
                        )

                        Text(
                            "BLE scan: ${
                                snapshot
                                    .scannerMode
                                    ?: "Not reported yet"
                            }",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall
                        )

                        Text(
                            "Direct F003 GATT: ${
                                when {
                                    snapshot
                                        .liveGattStreaming ->
                                        "Streaming"

                                    snapshot
                                        .liveGattConnected ->
                                        "Connected"

                                    else ->
                                        "Not connected"
                                }
                            }",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                            color =
                                if (
                                    snapshot
                                        .liveGattStreaming
                                ) {
                                    MaterialTheme
                                        .colorScheme
                                        .primary
                                } else {
                                    MaterialTheme
                                        .colorScheme
                                        .error
                                },
                            fontWeight =
                                FontWeight.SemiBold
                        )

                        Text(
                            "F003 stage: ${
                                snapshot
                                    .liveGattStage
                                    ?: "Not started"
                            }",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall
                        )

                        Text(
                            "F002 protocol step: ${
                                snapshot
                                    .liveGattProtocolStep
                                    ?: "Not started"
                            }",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall
                        )

                        Text(
                            "F002 last response: ${
                                if (
                                    snapshot
                                        .liveGattLastF002Response >=
                                    0
                                ) {
                                    "0x${snapshot.liveGattLastF002Response.toString(16).uppercase().padStart(3, '0')}"
                                } else {
                                    "None"
                                }
                            }",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall
                        )

                        Text(
                            "Sensor activation state: ${
                                snapshot
                                    .liveGattSensorActivationState
                                    ?: "Unknown"
                            }${
                                if (
                                    snapshot
                                        .liveGattSensorStartYear >=
                                    0
                                ) {
                                    " • start year ${snapshot.liveGattSensorStartYear}"
                                } else {
                                    ""
                                }
                            }",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                            color =
                                if (
                                    snapshot
                                        .liveGattSensorActivationState ==
                                    "UNINITIALIZED"
                                ) {
                                    MaterialTheme
                                        .colorScheme
                                        .error
                                } else {
                                    MaterialTheme
                                        .colorScheme
                                        .onSurface
                                },
                            fontWeight =
                                if (
                                    snapshot
                                        .liveGattSensorActivationState ==
                                    "UNINITIALIZED"
                                ) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                }
                        )

                        snapshot
                            .liveGattLastF002PayloadHex
                            ?.let {
                                    payload ->
                                Text(
                                    "F002 last payload: $payload",
                                    style =
                                        MaterialTheme
                                            .typography
                                            .labelSmall
                                )
                            }

                        Text(
                            "F003 properties: 0x${
                                snapshot
                                    .liveGattF003Properties
                                    .toString(16)
                                    .uppercase()
                            } • local notify ${
                                snapshot
                                    .liveGattF003LocalNotifyEnabled
                                    ?.toString()
                                    ?: "unknown"
                            } • read ${
                                if (
                                    snapshot
                                        .liveGattF003ReadSupported
                                ) {
                                    "supported"
                                } else {
                                    "not supported"
                                }
                            }",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall
                        )

                        Text(
                            "F003 CCCD: status ${
                                if (
                                    snapshot
                                        .liveGattF003CccdWriteStatus >=
                                    0
                                ) {
                                    snapshot
                                        .liveGattF003CccdWriteStatus
                                        .toString()
                                } else {
                                    "none"
                                }
                            } • writes ${
                                snapshot
                                    .liveGattF003CccdWriteCount
                            } • notifications ${
                                snapshot
                                    .liveGattF003NotificationCount
                            }",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall
                        )

                        if (
                            snapshot
                                .liveGattF003LastRawAtMs >
                            0L
                        ) {
                            Text(
                                "F003 raw callback: ${
                                    ageText(
                                        snapshot
                                            .liveGattF003LastRawAtMs
                                    )
                                } • ${
                                    snapshot
                                        .liveGattF003LastRawLength
                                } bytes • ${
                                    snapshot
                                        .liveGattF003LastSource
                                        ?: "unknown source"
                                }",
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall
                            )
                        }

                        Text(
                            "F003 read probe: attempts ${
                                snapshot
                                    .liveGattF003ReadAttempts
                            } • status ${
                                when (
                                    snapshot
                                        .liveGattF003LastReadStatus
                                ) {
                                    -1 ->
                                        "none"

                                    -2 ->
                                        "start rejected"

                                    else ->
                                        snapshot
                                            .liveGattF003LastReadStatus
                                            .toString()
                                }
                            }",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall
                        )

                        snapshot
                            .liveGattMessage
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?.let {
                                    message ->
                                Text(
                                    message,
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall
                                )
                            }

                        if (
                            snapshot
                                .liveGattLastPacketMs >
                            0L
                        ) {
                            Text(
                                "F003 last packet: ${
                                    ageText(
                                        snapshot
                                            .liveGattLastPacketMs
                                    )
                                } • sensor minute ${
                                    snapshot
                                        .liveGattLastMinute
                                } • CRC ${
                                    snapshot
                                        .liveGattLastCrcValid
                                        ?.toString()
                                        ?: "unknown"
                                } • reconnects ${
                                    snapshot
                                        .liveGattReconnectCount
                                }",
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall
                            )
                        }

                        snapshot
                            .liveGattLastError
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?.let {
                                    error ->
                                Text(
                                    "F003 GATT: $error",
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall,
                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .error
                                )
                            }

                        Text(
                            "Android wake scan: ${
                                if (
                                    snapshot
                                        .backgroundScanActive
                                ) {
                                    "Active"
                                } else {
                                    "Inactive"
                                }
                            }",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                            color =
                                if (
                                    snapshot
                                        .backgroundScanActive
                                ) {
                                    MaterialTheme
                                        .colorScheme
                                        .primary
                                } else {
                                    MaterialTheme
                                        .colorScheme
                                        .error
                                },
                            fontWeight =
                                FontWeight.SemiBold
                        )

                        if (
                            snapshot
                                .backgroundScanLastPacketMs >
                            0L
                        ) {
                            Text(
                                "Wake-scan last packet: ${
                                    ageText(
                                        snapshot
                                            .backgroundScanLastPacketMs
                                    )
                                } • sensor minute ${
                                    snapshot
                                        .backgroundScanLastMinute
                                } • CRC ${
                                    snapshot
                                        .backgroundScanLastCrcValid
                                        ?.toString()
                                        ?: "unknown"
                                }",
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall
                            )
                        }

                        snapshot
                            .backgroundScanLastError
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?.let {
                                    error ->
                                Text(
                                    "Wake-scan error: $error",
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall,
                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .error
                                )
                            }
                    }
                }

                Text(
                    "GlucoTrail monitoring is always enabled while a sensor is selected. Clearing the app from Recent Apps does not stop the monitoring service.",
                    style =
                        MaterialTheme
                            .typography
                            .bodyMedium
                )

                Text(
                    "Screen-off protection now uses three paths. The primary path is a persistent direct BLE GATT connection subscribed to AiDEX F003 glucose notifications. A selected-sensor advertisement fallback and an OS-delivered PendingIntent BLE wake scan remain available for recovery. Android battery optimization should stay disabled for reliable overnight monitoring.",
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )

                Text(
                    "Automatic recovery is enabled for device boot, app upgrades, Android service restarts, BLE scan failures, and Bluetooth being turned off and back on.",
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )

                HorizontalDivider()

                Text(
                    "Battery optimization",
                    fontWeight =
                        FontWeight.Bold
                )

                Text(
                    if (
                        batteryOptimizationExempt
                    ) {
                        "GlucoTrail is currently excluded from Android battery optimization."
                    } else {
                        "Android battery optimization is still enabled for GlucoTrail. For the most reliable boot and background monitoring, set GlucoTrail to Unrestricted / Not optimized in Android battery settings."
                    },
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,
                    color =
                        if (
                            batteryOptimizationExempt
                        ) {
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                        } else {
                            MaterialTheme
                                .colorScheme
                                .error
                        }
                )

                if (
                    !batteryOptimizationExempt &&
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.M
                ) {
                    Button(
                        onClick = {
                            val directOpened =
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings
                                                .ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                            Uri.parse(
                                                "package:${context.packageName}"
                                            )
                                        )
                                    )
                                }
                                    .isSuccess

                            if (
                                !directOpened
                            ) {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings
                                                .ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                                        )
                                    )
                                }
                            }
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Allow 24/7 Battery Use"
                        )
                    }
                }

                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings
                                        .ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                                )
                            )
                        }
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (
                            batteryOptimizationExempt
                        ) {
                            "View Battery Optimization Settings"
                        } else {
                            "Open Battery Optimization Settings"
                        }
                    )
                }

                OutlinedButton(
                    onClick = {
                        onRestart()
                    },
                    enabled =
                        selectedSerial !=
                            null,
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Restart Monitoring Now"
                    )
                }

                if (
                    snapshot.lastBootEventMs >
                    0L
                ) {
                    HorizontalDivider()

                    Text(
                        "Last system restart event: ${
                            ageText(
                                snapshot
                                    .lastBootEventMs
                            )
                        }",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall
                    )

                    Text(
                        snapshot
                            .lastBootAction
                            ?: "",
                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )
                }

                snapshot
                    .lastStartFailure
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?.let {
                        Text(
                            "Last start failure: $it",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .error
                        )
                    }

                HorizontalDivider()

                Text(
                    "Android Force Stop, or pressing Stop for GlucoTrail in Android's Active Apps / Task Manager, is an operating-system override. Android does not allow an app to bypass that action. Open GlucoTrail again to resume monitoring after a Force Stop.",
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick =
                    onDismiss
            ) {
                Text(
                    "Done"
                )
            }
        }
    )
}

@Composable
private fun DiagnosticsScreen(
    vm: AidexViewModel,
    glucoseUnit: GlucoseUnit,
    onBack: () -> Unit
) {
    val sensors by vm.sensors.collectAsState()
    val savedSensors by
        vm.savedSensors
            .collectAsState()
    val selectedSerial by
        vm.selectedSerial
            .collectAsState()
    val scanStatus by vm.scanStatus.collectAsState()
    val historyBackfill by
        vm.historyBackfillState
            .collectAsState()

    val historyAutoRecovery by
        vm.historyAutoRecoveryState
            .collectAsState()

    val selectedSensor =
        selectedSerial
            ?.let {
                    serial ->
                sensors.firstOrNull {
                    it.serial.equals(
                        serial,
                        ignoreCase = true
                    )
                }
                    ?: savedSensors[
                        serial.uppercase()
                    ]
            }

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick =
                onBack
        ) {
            Text(
                "← Main"
            )
        }

        Text(
            "Diagnostics",
            style =
                MaterialTheme
                    .typography
                    .titleLarge
        )
    }
    Text("BLE packets: ${scanStatus.totalResults} • AiDEX candidates: ${scanStatus.aidexCandidates}")
    scanStatus.lastDeviceName?.let { Text("Last: $it", style = MaterialTheme.typography.bodySmall) }
    scanStatus.lastError
        ?.let {
            Text(
                it,
                color =
                    MaterialTheme
                        .colorScheme
                        .error
            )
        }

    Spacer(
        Modifier.height(
            8.dp
        )
    )

    Card(
        Modifier.fillMaxWidth()
    ) {
        Column(
            modifier =
                Modifier.padding(
                    12.dp
                ),
            verticalArrangement =
                Arrangement.spacedBy(
                    6.dp
                )
        ) {
            Text(
                "Automatic 72h History Recovery",
                fontWeight =
                    FontWeight.Bold
            )

            Text(
                if (
                    historyAutoRecovery.active
                ) {
                    "Running"
                } else {
                    "Enabled"
                },
                color =
                    if (
                        historyAutoRecovery.lastError !=
                        null
                    ) {
                        MaterialTheme
                            .colorScheme
                            .error
                    } else {
                        MaterialTheme
                            .colorScheme
                            .primary
                    },
                fontWeight =
                    FontWeight.Bold
            )

            Text(
                historyAutoRecovery.message,
                style =
                    MaterialTheme
                        .typography
                        .bodySmall
            )

            Text(
                "Missing ${historyAutoRecovery.missingMinutes} min • ${historyAutoRecovery.gapCount} gap(s) • largest ${historyAutoRecovery.largestGapMinutes} min",
                style =
                    MaterialTheme
                        .typography
                        .labelSmall,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )

            if (
                historyAutoRecovery.currentSensorMinute >
                0
            ) {
                Text(
                    "Checked sensor minutes ${historyAutoRecovery.windowStartMinute}–${historyAutoRecovery.currentSensorMinute}",
                    style =
                        MaterialTheme
                            .typography
                            .labelSmall,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }

            Text(
                "A continuous gap of 3+ minutes triggers encrypted F002 recovery automatically. The newest 2 minutes are given time to arrive normally first.",
                style =
                    MaterialTheme
                        .typography
                        .labelSmall,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }
    }

    Spacer(
        Modifier.height(
            8.dp
        )
    )

    Card(
        Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(
                12.dp
            ),
            verticalArrangement =
                Arrangement.spacedBy(
                    6.dp
                )
        ) {
            Text(
                "Encrypted History Backfill",
                fontWeight =
                    FontWeight.Bold
            )

            Text(
                historyBackfill.message,
                style =
                    MaterialTheme
                        .typography
                        .bodySmall
            )

            Text(
                "Received ${historyBackfill.recordsReceived} • Prepared ${historyBackfill.recordsPrepared}",
                style =
                    MaterialTheme
                        .typography
                        .labelSmall,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )

            Text(
                "Selected: ${
                    selectedSerial
                        ?: "none"
                }",
                style =
                    MaterialTheme
                        .typography
                        .labelSmall
            )

            selectedSensor
                ?.let {
                        sensor ->
                    Text(
                        "BLE ${sensor.address} • RSSI ${sensor.rssi}",
                        style =
                            MaterialTheme
                                .typography
                                .labelSmall
                    )

                    sensor.reading
                        ?.let {
                                reading ->
                            Text(
                                "Anchor minute ${reading.sensorMinute} • ${glucoseUnit.formatGlucoseFromMgDl(reading.glucoseMgDl.toDouble())}",
                                style =
                                    MaterialTheme
                                        .typography
                                        .labelSmall
                            )

                            Text(
                                "valid=${reading.valid} • warmup=${reading.isWarmup} • crc=${reading.crcValid}",
                                style =
                                    MaterialTheme
                                        .typography
                                        .labelSmall,
                                color =
                                    if (
                                        reading.valid &&
                                        reading.crcValid &&
                                        !reading.isWarmup
                                    ) {
                                        MaterialTheme
                                            .colorScheme
                                            .onSurfaceVariant
                                    } else {
                                        MaterialTheme
                                            .colorScheme
                                            .error
                                    }
                            )

                            if (
                                scanStatus
                                    .lastAdvertisementSensorMinute ==
                                reading.sensorMinute
                            ) {
                                Text(
                                    "ADV ${scanStatus.lastManufacturer0059Length ?: 0} bytes • seed ${scanStatus.lastAdvertisementSeedHex ?: "n/a"}",
                                    style =
                                        MaterialTheme
                                            .typography
                                            .labelSmall
                                )

                                Text(
                                    "CRC calculated ${scanStatus.lastAdvertisementCalculatedCrcHex ?: "n/a"} • received ${scanStatus.lastAdvertisementReceivedCrcHex ?: "n/a"}",
                                    style =
                                        MaterialTheme
                                            .typography
                                            .labelSmall,
                                    color =
                                        if (
                                            scanStatus
                                                .lastAdvertisementCrcValid ==
                                            true
                                        ) {
                                            MaterialTheme
                                                .colorScheme
                                                .onSurfaceVariant
                                        } else {
                                            MaterialTheme
                                                .colorScheme
                                                .error
                                        }
                                )

                                scanStatus
                                    .lastManufacturer0059Hex
                                    ?.let {
                                            rawHex ->
                                        Text(
                                            "Raw 0x0059: $rawHex",
                                            style =
                                                MaterialTheme
                                                    .typography
                                                    .labelSmall,
                                            color =
                                                MaterialTheme
                                                    .colorScheme
                                                    .onSurfaceVariant
                                        )
                                    }
                            }
                        }

                    if (
                        sensor.reading == null &&
                        scanStatus.lastAdvertisementCrcValid == false
                    ) {
                        Text(
                            "Advertisement detected but rejected by CRC • no trusted glucose/history anchor yet",
                            style =
                                MaterialTheme
                                    .typography
                                    .labelSmall,
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .error
                        )
                    }
                }

            if (
                historyBackfill.log
                    .isNotEmpty()
            ) {
                HorizontalDivider()

                Text(
                    "Backfill log",
                    fontWeight =
                        FontWeight.Bold,
                    style =
                        MaterialTheme
                            .typography
                            .labelMedium
                )

                historyBackfill.log
                    .takeLast(
                        8
                    )
                    .forEach {
                            line ->
                        Text(
                            "• $line",
                            style =
                                MaterialTheme
                                    .typography
                                    .labelSmall
                        )
                    }
            }

            OutlinedButton(
                onClick = {
                    vm.requestHistoryBackfillNow()
                },
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Text(
                    "Backfill 72h Now (F002)"
                )
            }

            Button(
                onClick =
                    onBack,
                modifier =
                    Modifier
                        .fillMaxWidth()
            ) {
                Text(
                    "← Return to Main Screen"
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(sensors) { sensor ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(sensor.name, fontWeight = FontWeight.Bold)
                    Text("Address ${sensor.address}")
                    Text("RSSI ${sensor.rssi}")
                    sensor.reading?.let {
                        Text(
                            "minute=${it.sensorMinute} glucose=${glucoseUnit.formatGlucoseFromMgDl(it.glucoseMgDl.toDouble())} trend=${it.trendRaw} quality=${it.quality}"
                        )
                        Text("valid=${it.valid} warmup=${it.isWarmup} crc=${it.crcValid}")
                    }
                }
            }
        }
    }
}

private data class PermissionState(val granted: Boolean, val request: () -> Unit)

@Composable
private fun rememberNotificationPermission(): PermissionState {
    val context = LocalContext.current

    if (Build.VERSION.SDK_INT < 33) {
        return PermissionState(
            granted = true,
            request = {}
        )
    }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        granted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(Unit) {
        granted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    return PermissionState(
        granted = granted,
        request = {
            launcher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
    )
}

@Composable
private fun rememberBluetoothPermissions(): PermissionState {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = requiredBluetoothPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    LaunchedEffect(Unit) {
        granted = requiredBluetoothPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    return PermissionState(granted) { launcher.launch(requiredBluetoothPermissions()) }
}

private fun requiredBluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
    arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}
