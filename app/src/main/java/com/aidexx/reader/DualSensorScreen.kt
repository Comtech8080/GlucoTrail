package com.aidexx.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.aidexx.reader.data.SensorOverlapComparison
import com.aidexx.reader.protocol.AidexReading
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

/**
 * An independent comparison page. Readings are displayed, never invented.
 * Automatic CGM-to-CGM calibration is deliberately not offered.
 */
@Composable
fun DualSensorScreen(vm: AidexViewModel, onStartReplacement: () -> Unit) {
    val selected by vm.selectedSerial.collectAsState()
    val replacement by vm.replacementSerial.collectAsState()
    val priorPrimary by vm.overlapOldSerial.collectAsState()
    val recordedReplacement by vm.overlapNewSerial.collectAsState()
    val oldSerial = priorPrimary ?: selected
    val newSerial = recordedReplacement ?: replacement
    val calibrationHistory by vm.overlapCalibrations.collectAsState()
    val history by vm.historyBySerial.collectAsState()
    val comparison by vm.overlapComparison.collectAsState()
    val autoHandover by vm.autoHandoverEnabled.collectAsState()
    val units by vm.glucoseUnit.collectAsState()
    var graphMode by remember { mutableStateOf(2) }
    var hours by remember { mutableIntStateOf(6) }
    var fingerstick by remember { mutableStateOf("") }
    var confirmAdjustment by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val old = history[oldSerial?.uppercase()].orEmpty()
    val next = history[newSerial?.uppercase()].orEmpty()

    LaunchedEffect(selected, replacement, oldSerial, newSerial) {
        while (true) {
            vm.refreshOverlapComparison()
            delay(30_000L)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Dual sensor monitoring", style = MaterialTheme.typography.headlineSmall)
        Text("The old sensor stays primary until you switch or automatic handover confirms a ready replacement.")
        Text("Current primary: ${selected ?: "None"}")
        Text("Old sensor: ${oldSerial ?: "None"}")
        Text("New sensor: ${newSerial ?: "Not configured"}")
        if (newSerial == selected && newSerial != null) Text("Handover completed: new sensor is primary.")
        Button(onClick = onStartReplacement, enabled = selected != null && replacement == null) {
            Text("Add / activate replacement sensor")
        }
        if (replacement != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val newSerial = replacement ?: return@OutlinedButton
                    if (SensorOverlapComparison.replacementReady(next, System.currentTimeMillis())) {
                        vm.selectSensor(newSerial)
                        vm.clearReplacementSensor()
                        status = "Replacement selected as primary."
                    } else {
                        status = "Replacement not ready. Wait for verified readings."
                    }
                }) { Text("Switch primary") }
                OutlinedButton(onClick = { vm.clearReplacementSensor() }) {
                    Text("Remove replacement")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Automatic handover", modifier = Modifier.weight(1f))
                Switch(checked = autoHandover, onCheckedChange = vm::setAutoHandoverEnabled)
            }
            Text("When enabled, GlucoTrail may switch after the old sensor has no valid reading for 15 minutes and the new sensor has multiple recent verified readings. Loss of Bluetooth is not proof of expiry.", style = MaterialTheme.typography.bodySmall)
        }
        status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        if (selected != null) {
            Text("Glucose graphs", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Old", "New", "Compare").forEachIndexed { index, label ->
                    FilterChip(selected = graphMode == index, onClick = { graphMode = index }, label = { Text(label) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(2, 4, 6, 48, 72).forEach { span ->
                    FilterChip(selected = hours == span, onClick = { hours = span }, label = { Text("${span}h") })
                }
            }
            SensorOverlapGraph(
                primary = if (graphMode == 1) emptyList() else old,
                replacement = if (graphMode == 0) emptyList() else next,
                hours = hours
            )
            val oldReading = old.lastOrNull { it.valid && it.crcValid && !it.isWarmup }
            val newReading = next.lastOrNull { it.valid && it.crcValid && !it.isWarmup }
            Text("Old: ${oldReading?.let { units.formatGlucoseFromMgDl(it.glucoseMgDl.toDouble()) } ?: "No valid reading"}")
            Text("New: ${newReading?.let { units.formatGlucoseFromMgDl(it.glucoseMgDl.toDouble()) } ?: "No valid reading"}")
            oldReading?.let { last ->
                Text("Old last valid reading: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last.receivedAtMs))}")
                vm.sensorActivationStartedAt(oldSerial)?.let { started ->
                    if (last.receivedAtMs >= started) {
                        val runtimeMinutes = (last.receivedAtMs - started) / 60_000L
                        val days = runtimeMinutes / 1440L
                        val hoursRemaining = (runtimeMinutes % 1440L) / 60L
                        Text("Observed old sensor runtime: ${days}d ${hoursRemaining}h (from recorded activation to last verified reading)")
                        val extra = runtimeMinutes - 14L * 1440L
                        if (extra > 0) Text("Observed past labelled 14 days: ${extra / 60L}h ${extra % 60L}m. Not validated for treatment use.")
                    }
                }
            }
            newReading?.let { last ->
                Text("New last valid reading: ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(last.receivedAtMs))}")
            }
            comparison?.let { pair ->
                val msg = "Difference: %.1f mmol/L (%.0f%%)".format(
                    pair.differenceMmol, pair.differencePercent
                )
                Text(msg, color = if (pair.significantDisagreement) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                if (pair.significantDisagreement) {
                    Text("Sensors disagree. Do not automatically correct the new sensor based on the old one. Check with a fingerstick meter.", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = fingerstick,
                    onValueChange = { fingerstick = it },
                    label = { Text("Fingerstick reading (mmol/L)") },
                    singleLine = true
                )
                val meterMgDl = fingerstick.toDoubleOrNull()
                    ?.takeIf { it in 1.0..44.4 }
                    ?.let { (it * 18.0).toInt() }
                if (meterMgDl != null) {
                    val reference = SensorOverlapComparison.compareFingerstick(pair, meterMgDl)
                    Text("Old difference from meter: %.1f mmol/L".format(reference.primaryErrorMgDl / 18.0))
                    Text("New difference from meter: %.1f mmol/L".format(reference.replacementErrorMgDl / 18.0))
                    Text("Meter values can be used for a manual display adjustment. The raw sensor data stays unchanged.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { confirmAdjustment = true }) {
                        Text("Adjust replacement using fingerstick")
                    }
                    if (confirmAdjustment) {
                        AlertDialog(
                            onDismissRequest = { confirmAdjustment = false },
                            title = { Text("Confirm display calibration") },
                            text = {
                                Text("Apply a time-based display correction to the new sensor using your fingerstick result of ${fingerstick} mmol/L? This does not change sensor firmware or raw readings. Use only a fresh meter result and follow manufacturer guidance.")
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    vm.saveCalibration(pair.replacement, meterMgDl)
                                    status = "Fingerstick-based display adjustment recorded."
                                    confirmAdjustment = false
                                }) { Text("Confirm") }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirmAdjustment = false }) { Text("Cancel") }
                            }
                        )
                    }
                }
            } ?: Text("Waiting for two trustworthy readings taken close together.")
            if (oldSerial != null || newSerial != null) {
                Text("Display calibration history", style = MaterialTheme.typography.titleMedium)
                listOfNotNull(oldSerial, newSerial).distinct().forEach { serial ->
                    Text("Sensor $serial")
                    val events = calibrationHistory[serial.uppercase()].orEmpty()
                    if (events.isEmpty()) Text("No saved adjustments", style = MaterialTheme.typography.bodySmall)
                    events.takeLast(5).asReversed().forEach { event ->
                        Text(
                            "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(event.createdAtMs))}: %+.1f mmol/L".format(event.offsetMmol),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        Text("Readings from expired or unreliable sensors must not be used for treatment decisions. Confirm unexpected readings with a fingerstick meter.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SensorOverlapGraph(
    primary: List<AidexReading>,
    replacement: List<AidexReading>,
    hours: Int
) {
    val oldColor = Color(0xFF4A90E2)
    val newColor = Color(0xFFFF9F43)
    val now = System.currentTimeMillis()
    val from = now - hours * 3_600_000L
    val old = primary.filter { it.receivedAtMs in from..now && it.valid && it.crcValid && !it.isWarmup }.sortedBy { it.receivedAtMs }
    val next = replacement.filter { it.receivedAtMs in from..now && it.valid && it.crcValid && !it.isWarmup }.sortedBy { it.receivedAtMs }
    val values = old + next
    val minimum = ((values.minOfOrNull { it.glucoseMgDl } ?: 72) - 18).coerceAtLeast(18)
    val maximum = ((values.maxOfOrNull { it.glucoseMgDl } ?: 180) + 18).coerceAtLeast(minimum + 36)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (primary.isNotEmpty()) Text("● Old sensor", color = oldColor)
                if (replacement.isNotEmpty()) Text("● New sensor", color = newColor)
            }
            Canvas(Modifier.fillMaxWidth().height(205.dp)) {
                val left = 6f
                val bottom = size.height - 5f
                val x = { t: Long -> left + ((t - from).toFloat() / (now - from).toFloat()) * (size.width - 2 * left) }
                val y = { v: Int -> bottom - (v - minimum).toFloat() / (maximum - minimum).toFloat() * (size.height - 10f) }
                fun drawSeries(records: List<AidexReading>, color: Color) {
                    if (records.size < 2) return
                    var path = Path()
                    var previous: AidexReading? = null
                    records.forEach { reading ->
                        val px = x(reading.receivedAtMs)
                        val py = y(reading.glucoseMgDl)
                        if (previous == null || reading.receivedAtMs - previous!!.receivedAtMs > 10 * 60_000L) {
                            if (previous != null) drawPath(path, color, style = Stroke(width = 3f))
                            path = Path().apply { moveTo(px, py) }
                        } else {
                            path.lineTo(px, py)
                        }
                        previous = reading
                    }
                    drawPath(path, color, style = Stroke(width = 3f))
                }
                drawLine(Color.Gray.copy(alpha = .3f), Offset(left, bottom), Offset(size.width - left, bottom))
                drawSeries(old, oldColor)
                drawSeries(next, newColor)
            }
            Text("${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(from))} to now | Real readings only. Missing segments are not filled.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
