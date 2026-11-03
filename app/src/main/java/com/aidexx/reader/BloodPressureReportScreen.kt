package com.aidexx.reader

import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aidexx.reader.data.AidexHistoryDatabase
import com.aidexx.reader.data.BloodPressureEntry
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val BpBlue = Color(0xFF75ADE3)
private val BpInk = Color(0xFFF2F4F7)
private val BpMuted = Color(0xFFB4BCC8)
private val BpBackground = Color.Black
private val BpRanges = listOf(7, 14, 30, 60, 90, 180)
private fun bpDate(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

@Composable
fun BloodPressureScreen(
    onBack: () -> Unit,
    backLabel: String = "‹ Notes",
    reportActive: Boolean = true,
    swipeHint: String? = null
) {
    val reportModern = LocalTrailTheme.current.isModern
    val reportColors = MaterialTheme.colorScheme
    val reportBackground = if(reportModern) reportColors.background else Color.Black
    val reportInk = if(reportModern) reportColors.onSurface else Color(0xFFF2F4F7)
    val reportMuted = if(reportModern) reportColors.onSurfaceVariant else Color(0xFFB4BCC8)
    val reportSurface = if(reportModern) reportColors.surface else Color(0xFF17191D)
    val reportGrid = if(reportModern) reportColors.outlineVariant else Color(0xFF303640)
    val reportAccent = if(reportModern) reportColors.primary else BpBlue

    val context = LocalContext.current
    val db = remember { AidexHistoryDatabase(context.applicationContext) }
    var entries by remember { mutableStateOf(db.loadBloodPressure()) }
    var pieDays by remember { mutableIntStateOf(7) }
    var historyDays by remember { mutableIntStateOf(7) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var editing by remember { mutableStateOf<BloodPressureEntry?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reportActive) {
        if (reportActive) {
            entries = db.loadBloodPressure()
            pieDays = 7
            historyDays = 7
            while (true) { nowMs = System.currentTimeMillis(); delay(30_000L) }
        }
    }
    DisposableEffect(db) { onDispose { db.close() } }
    val pieEntries = bpWindow(entries, pieDays, nowMs)
    val history = bpWindow(entries, historyDays, nowMs)
    val latest = entries.filter { it.timestampMs <= nowMs }.maxByOrNull { it.timestampMs }
    BackHandler(enabled = reportActive && !editorOpen, onBack = onBack)

    Box(Modifier.fillMaxSize().background(reportBackground)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column(Modifier.fillMaxWidth().background(reportBackground).padding(16.dp)) {
                    TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                        Text(backLabel, color = reportInk)
                    }
                    Text("Blood Pressure", color = reportInk, fontSize = 28.sp, fontWeight = FontWeight.Medium)
                    Text("Your readings at a glance", color = reportInk, style = MaterialTheme.typography.bodyMedium)
                    swipeHint?.let { Text(it, color = reportInk, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
            item {
                BpReportCard {
                    Text("Last measurement", style = MaterialTheme.typography.titleLarge)
                    if (latest == null) {
                        Text("No readings yet", style = MaterialTheme.typography.headlineSmall)
                        Text("Add BP in Notes on the glucose screen.", color = reportMuted)
                    } else {
                        Text(
                            "${latest.systolic}/${latest.diastolic}" + (latest.pulse?.let { " ($it)" } ?: ""),
                            fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(enabled = latest.id > 0) { editing = latest; editorOpen = true }
                        )
                        Text("mmHg" + if (latest.pulse != null) " · pulse in bpm" else "", color = reportMuted, style = MaterialTheme.typography.labelMedium)
                        Text(bpDate(latest.timestampMs), color = reportMuted)
                    }
                }
            }
            item {
                BpReportCard {
                    BpRangeTitle("Pie chart", pieDays) { pieDays = it }
                    BpDistribution(pieEntries)
                    Text("${pieEntries.size} readings in this period", color = reportMuted, style = MaterialTheme.typography.labelMedium)
                    BpCategoryInfo()
                }
            }
            item {
                BpReportCard {
                    BpRangeTitle("History chart", historyDays) { historyDays = it }
                    if (history.isEmpty()) {
                        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                            Text("No readings in this period", color = reportMuted)
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BpStat("Min", "${history.minOf { it.systolic }}/${history.minOf { it.diastolic }}", Modifier.weight(1f))
                            BpStat("Avg", "${history.map { it.systolic }.average().roundToInt()}/${history.map { it.diastolic }.average().roundToInt()}", Modifier.weight(1f))
                            BpStat("Max", "${history.maxOf { it.systolic }}/${history.maxOf { it.diastolic }}", Modifier.weight(1f))
                        }
                        Text("mmHg · systolic / diastolic", color = reportMuted, style = MaterialTheme.typography.labelMedium)
                        BpHistoryGraph(history, historyDays, nowMs)
                        Text("Solid: systolic   ·   Dashed: diastolic", color = reportMuted, style = MaterialTheme.typography.labelMedium)
                        Text("Min/max are calculated separately for each pressure.", color = reportMuted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item {
                Text("Reading history", color = reportInk, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
                Text("Edit note-linked readings in Notes; tap older entries to edit.", color = reportMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 22.dp))
            }
            items(entries.sortedByDescending { it.timestampMs }, key = { it.id }) { entry ->
                BpReportCard(modifier = Modifier.clickable(enabled = entry.id > 0) { editing = entry; editorOpen = true }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${entry.systolic}/${entry.diastolic} mmHg", fontWeight = FontWeight.SemiBold)
                        entry.pulse?.let { Text("$it bpm", color = reportMuted) }
                    }
                    Text(bpDate(entry.timestampMs), color = reportMuted, style = MaterialTheme.typography.bodySmall)
                    if (entry.note.isNotBlank()) Text(entry.note, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (editorOpen) BloodPressureEditor(
        existing = editing,
        dismiss = { editorOpen = false },
        save = { entry ->
            try {
                check(db.saveBloodPressure(entry) > 0L)
                entries = db.loadBloodPressure()
                nowMs = System.currentTimeMillis()
                editorOpen = false
            } catch (_: Exception) { saveError = "The reading could not be saved. Please try again." }
        },
        delete = { entry ->
            try {
                db.deleteBloodPressure(entry.id)
                entries = db.loadBloodPressure()
                editorOpen = false
            } catch (_: Exception) { saveError = "The reading could not be deleted. Please try again." }
        }
    )
    saveError?.let { message ->
        AlertDialog(onDismissRequest = { saveError = null }, title = { Text("Blood Pressure") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { saveError = null }) { Text("OK") } })
    }
}

@Composable
private fun BpReportCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val reportModern = LocalTrailTheme.current.isModern
    val reportColors = MaterialTheme.colorScheme
    val reportBackground = if(reportModern) reportColors.background else Color.Black
    val reportInk = if(reportModern) reportColors.onSurface else Color(0xFFF2F4F7)
    val reportMuted = if(reportModern) reportColors.onSurfaceVariant else Color(0xFFB4BCC8)
    val reportSurface = if(reportModern) reportColors.surface else Color(0xFF17191D)
    val reportGrid = if(reportModern) reportColors.outlineVariant else Color(0xFF303640)
    val reportAccent = if(reportModern) reportColors.primary else BpBlue

    Card(
        modifier = Modifier.padding(horizontal = 10.dp).fillMaxWidth().then(modifier),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = reportSurface, contentColor = reportInk)
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
}

@Composable
private fun BpRangeTitle(title: String, days: Int, onSelect: (Int) -> Unit) {
    val reportModern = LocalTrailTheme.current.isModern
    val reportColors = MaterialTheme.colorScheme
    val reportBackground = if(reportModern) reportColors.background else Color.Black
    val reportInk = if(reportModern) reportColors.onSurface else Color(0xFFF2F4F7)
    val reportMuted = if(reportModern) reportColors.onSurfaceVariant else Color(0xFFB4BCC8)
    val reportSurface = if(reportModern) reportColors.surface else Color(0xFF17191D)
    val reportGrid = if(reportModern) reportColors.outlineVariant else Color(0xFF303640)
    val reportAccent = if(reportModern) reportColors.primary else BpBlue

    var open by remember { mutableStateOf(false) }
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Box {
            TextButton(onClick = { open = true }, contentPadding = PaddingValues(0.dp)) {
                Text("Last $days days  ▾", color = reportAccent, style = MaterialTheme.typography.bodyLarge)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                BpRanges.forEach { value -> DropdownMenuItem(text = { Text("Last $value days") }, onClick = { onSelect(value); open = false }) }
            }
        }
    }
}

@Composable
private fun BpStat(title: String, value: String, modifier: Modifier) {
    val reportModern = LocalTrailTheme.current.isModern
    val reportColors = MaterialTheme.colorScheme
    val reportBackground = if(reportModern) reportColors.background else Color.Black
    val reportInk = if(reportModern) reportColors.onSurface else Color(0xFFF2F4F7)
    val reportMuted = if(reportModern) reportColors.onSurfaceVariant else Color(0xFFB4BCC8)
    val reportSurface = if(reportModern) reportColors.surface else Color(0xFF17191D)
    val reportGrid = if(reportModern) reportColors.outlineVariant else Color(0xFF303640)
    val reportAccent = if(reportModern) reportColors.primary else BpBlue

    Column(modifier) {
        Text(title, color = reportMuted, style = MaterialTheme.typography.labelMedium)
        Text(value, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun BpDistribution(entries: List<BloodPressureEntry>) {
    val reportModern = LocalTrailTheme.current.isModern
    val reportColors = MaterialTheme.colorScheme
    val reportBackground = if(reportModern) reportColors.background else Color.Black
    val reportInk = if(reportModern) reportColors.onSurface else Color(0xFFF2F4F7)
    val reportMuted = if(reportModern) reportColors.onSurfaceVariant else Color(0xFFB4BCC8)
    val reportSurface = if(reportModern) reportColors.surface else Color(0xFF17191D)
    val reportGrid = if(reportModern) reportColors.outlineVariant else Color(0xFF303640)
    val reportAccent = if(reportModern) reportColors.primary else BpBlue

    val categories = BpCategory.values().toList()
    val counts = categories.map { category -> entries.count { bpCategory(it.systolic, it.diastolic) == category } }
    val percentages = bpPercentages(counts)
    val normal = percentages[categories.indexOf(BpCategory.NORMAL)]
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(0.42f).aspectRatio(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().semantics { contentDescription = if (entries.isEmpty()) "No readings" else "$normal percent normal readings" }) {
                val stroke = size.minDimension * 0.17f
                val diameter = size.minDimension - stroke
                val origin = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
                drawCircle(reportGrid, radius = diameter / 2, style = Stroke(stroke))
                var angle = -90f
                counts.forEachIndexed { index, count ->
                    val sweep = if (entries.isEmpty()) 0f else count * 360f / entries.size
                    if (sweep > 0f) drawArc(Color(categories[index].argb), angle, sweep, false, origin, Size(diameter, diameter), style = Stroke(stroke))
                    angle += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (entries.isEmpty()) "—" else "$normal%", fontWeight = FontWeight.Bold, fontSize = 24.sp)
                Text(if (entries.isEmpty()) "No data" else "Normal", color = reportMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
        Column(Modifier.weight(0.58f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEachIndexed { index, category ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).background(Color(category.argb), CircleShape))
                    Text("${percentages[index]}%", modifier = Modifier.width(36.dp), style = MaterialTheme.typography.labelMedium)
                    Text(category.label, style = MaterialTheme.typography.labelMedium, color = reportMuted)
                }
            }
        }
    }
}

@Composable
private fun BpCategoryInfo() {
    val reportModern = LocalTrailTheme.current.isModern
    val reportColors = MaterialTheme.colorScheme
    val reportBackground = if(reportModern) reportColors.background else Color.Black
    val reportInk = if(reportModern) reportColors.onSurface else Color(0xFFF2F4F7)
    val reportMuted = if(reportModern) reportColors.onSurfaceVariant else Color(0xFFB4BCC8)
    val reportSurface = if(reportModern) reportColors.surface else Color(0xFF17191D)
    val reportGrid = if(reportModern) reportColors.outlineVariant else Color(0xFF303640)
    val reportAccent = if(reportModern) reportColors.primary else BpBlue

    var show by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = { show = true }, contentPadding = PaddingValues(0.dp)) { Text("About reading categories", color = reportAccent, style = MaterialTheme.typography.labelMedium) }
    if (show) AlertDialog(
        onDismissRequest = { show = false },
        title = { Text("Reading categories") },
        text = { Text("Adult AHA ranges (mmHg):\nNormal: below 120 and below 80\nElevated: 120–129 and below 80\nStage 1: 130–139 or 80–89\nStage 2: 140+ or 90+\n\nLow: systolic below 90 or diastolic below 60. High categories take priority for mixed readings.\n\nThese groups describe individual readings, not a diagnosis.") },
        confirmButton = { TextButton(onClick = { show = false }) { Text("Close") } },
        dismissButton = { TextButton(onClick = { uriHandler.openUri("https://www.heart.org/en/health-topics/high-blood-pressure/understanding-blood-pressure-readings") }) { Text("AHA reference") } }
    )
}

@Composable
private fun BpHistoryGraph(entries: List<BloodPressureEntry>, days: Int, nowMs: Long) {
    val reportModern = LocalTrailTheme.current.isModern
    val reportColors = MaterialTheme.colorScheme
    val reportBackground = if(reportModern) reportColors.background else Color.Black
    val reportInk = if(reportModern) reportColors.onSurface else Color(0xFFF2F4F7)
    val reportMuted = if(reportModern) reportColors.onSurfaceVariant else Color(0xFFB4BCC8)
    val reportSurface = if(reportModern) reportColors.surface else Color(0xFF17191D)
    val reportGrid = if(reportModern) reportColors.outlineVariant else Color(0xFF303640)
    val reportAccent = if(reportModern) reportColors.primary else BpBlue

    val axisStart = nowMs - days.toLong() * 86_400_000L
    val minValue = ((entries.minOf { minOf(it.systolic, it.diastolic) } - 20) / 20 * 20).coerceAtLeast(0)
    val maxValue = (ceil((entries.maxOf { maxOf(it.systolic, it.diastolic) } + 20) / 20.0) * 20).toInt().coerceAtLeast(minValue + 80)
    Canvas(Modifier.fillMaxWidth().height(235.dp).semantics { contentDescription = "Blood pressure over the last $days days. ${entries.size} readings. Values listed in reading history below." }) {
        val left = 32.dp.toPx()
        val top = 22.dp.toPx()
        val right = size.width - 12.dp.toPx()
        val bottom = size.height - 26.dp.toPx()
        val width = (right - left).coerceAtLeast(1f)
        val height = (bottom - top).coerceAtLeast(1f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = reportMuted.toArgb(); textSize = 10.sp.toPx() }
        fun x(time: Long): Float = left + ((time - axisStart).toDouble() / (nowMs - axisStart)).toFloat() * width
        fun y(value: Int): Float = bottom - (value - minValue).toFloat() / (maxValue - minValue) * height
        drawRect(reportSurface, Offset(left, top), Size(width, height))
        for (i in 0..4) {
            val value = minValue + (maxValue - minValue) * i / 4
            drawLine(reportGrid, Offset(left, y(value)), Offset(right, y(value)), 1.dp.toPx())
            paint.textAlign = Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText("$value", left - 5.dp.toPx(), y(value) + 3.dp.toPx(), paint)
        }
        val formatter = SimpleDateFormat("MMM d", Locale.getDefault())
        for (i in 0..3) {
            val time = axisStart + (nowMs - axisStart) * i / 3
            drawLine(reportGrid, Offset(x(time), top), Offset(x(time), bottom), 1.dp.toPx())
            paint.textAlign = when (i) { 0 -> Paint.Align.LEFT; 3 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            drawContext.canvas.nativeCanvas.drawText(formatter.format(Date(time)), x(time), bottom + 19.dp.toPx(), paint)
        }
        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
        entries.zipWithNext().forEach { (a, b) ->
            val color = Color(bpCategory(b.systolic, b.diastolic).argb)
            drawLine(color, Offset(x(a.timestampMs), y(a.systolic)), Offset(x(b.timestampMs), y(b.systolic)), 2.5.dp.toPx())
            drawLine(color, Offset(x(a.timestampMs), y(a.diastolic)), Offset(x(b.timestampMs), y(b.diastolic)), 2.dp.toPx(), pathEffect = dash)
        }
        var lastLabelX = -1000f
        entries.forEach { entry ->
            val px = x(entry.timestampMs)
            val color = Color(bpCategory(entry.systolic, entry.diastolic).argb)
            listOf(entry.systolic, entry.diastolic).forEach { value ->
                drawCircle(reportInk, 4.5.dp.toPx(), Offset(px, y(value)))
                drawCircle(color, 3.dp.toPx(), Offset(px, y(value)))
            }
            if (entries.size <= 8 && px - lastLabelX >= 28.dp.toPx()) {
                paint.color = reportInk.toArgb()
                paint.textAlign = when { px < left + 16.dp.toPx() -> Paint.Align.LEFT; px > right - 16.dp.toPx() -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                drawContext.canvas.nativeCanvas.drawText("${entry.systolic}", px, y(entry.systolic) - 8.dp.toPx(), paint)
                drawContext.canvas.nativeCanvas.drawText("${entry.diastolic}", px, y(entry.diastolic) + 16.dp.toPx(), paint)
                lastLabelX = px
            }
        }
    }
}
