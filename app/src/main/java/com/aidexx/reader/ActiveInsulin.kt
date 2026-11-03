package com.aidexx.reader

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aidexx.reader.data.AidexHistoryDatabase
import com.aidexx.reader.data.GlucoseNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object InsulinSettings {
    fun minutes(context: Context): Int = context.getSharedPreferences("aidexx_reader", Context.MODE_PRIVATE)
        .getInt("fast_insulin_action_minutes", 240).takeIf { it in insulinActionOptions } ?: 240
    fun save(context: Context, minutes: Int) {
        require(minutes in insulinActionOptions)
        context.getSharedPreferences("aidexx_reader", Context.MODE_PRIVATE).edit()
            .putInt("fast_insulin_action_minutes", minutes).apply()
    }
}

@Composable
fun InsulinSettingsScreen() {
    val context = LocalContext.current
    var minutes by remember { mutableIntStateOf(InsulinSettings.minutes(context)) }
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Insulin Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Fast Insulin Action Time", style = MaterialTheme.typography.titleMedium)
                Box {
                    OutlinedButton(onClick = { expanded = true }) { Text(insulinHoursLabel(minutes)) }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        insulinActionOptions.forEach { option ->
                            DropdownMenuItem(text = { Text(insulinHoursLabel(option) + if(option == 240) " (default)" else "") }, onClick = {
                                InsulinSettings.save(context, option); minutes = option; expanded = false
                            })
                        }
                    }
                }
                Text("Applies to new fast-insulin doses only. Existing doses keep their saved action time.")
                Text("Countdowns are time estimates, not measurements of insulin remaining or dosing advice.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun ActiveInsulinCard(notes: List<GlucoseNote>, compact: Boolean = false) {
    val context = LocalContext.current.applicationContext
    var allNotes by remember { mutableStateOf<List<GlucoseNote>>(emptyList()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var details by remember { mutableStateOf(false) }
    // Read across sensors: a sensor change must not hide a recent dose.
    LaunchedEffect(notes) {
        while(true) {
            allNotes = withContext(Dispatchers.IO) { AidexHistoryDatabase(context).use { it.loadAllNotes() } }
            now = System.currentTimeMillis()
            delay(5_000L)
        }
    }
    val active = activeInsulinDoses(allNotes, now)
    if (compact) {
        Card(onClick = { details = true }, modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Active Insulin · ${active.size} active · Details ›", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                if (active.isEmpty()) Text("No active fast-insulin countdowns", style = MaterialTheme.typography.bodyMedium)
                else {
                    val dose = active.first()
                    val time = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())
                    Text("${insulinRemainingLabel(dose.remainingMs(now))} · ${dose.note.fastInsulinUnits} U", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Taken ${time.format(Instant.ofEpochMilli(dose.note.timestampMs))} · Est. finish ${time.format(Instant.ofEpochMilli(dose.finishMs))}", style = MaterialTheme.typography.bodySmall)
                    if (active.size > 1) Text("Next to finish · ${active.size - 1} more tracked separately", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    } else Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Active Insulin", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if(active.isEmpty()) Text("No active fast-insulin countdowns")
            else {
                Text("${active.size} active ${if(active.size == 1) "dose" else "doses"} · estimated", style = MaterialTheme.typography.labelLarge)
                InsulinDoseDetails(active.first(), now)
                if(active.size > 1) Text("${active.size - 1} more tracked independently")
            }
            TextButton(onClick = { details = true }) { Text("Active Insulin details") }
        }
    }
    if(details) AlertDialog(
        onDismissRequest = { details = false },
        title = { Text("Active Insulin") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if(active.isEmpty()) Text("No active fast-insulin countdowns")
                active.forEach { dose -> InsulinDoseDetails(dose, now); HorizontalDivider() }
                Text("Each dose uses the action time saved when it was logged. Older doses without a saved action time have no countdown. These are time estimates, not measurements of insulin remaining or dosing advice.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { details = false }) { Text("Close") } }
    )
}

@Composable
private fun InsulinDoseDetails(dose: ActiveInsulinDose, now: Long) {
    val format = DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault())
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(insulinRemainingLabel(dose.remainingMs(now)), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Fast insulin · ${dose.note.fastInsulinUnits} U")
        Text("Taken: ${format.format(Instant.ofEpochMilli(dose.note.timestampMs))}")
        Text("Estimated finish: ${format.format(Instant.ofEpochMilli(dose.finishMs))}")
        Text("Action time: ${insulinHoursLabel(dose.note.fastInsulinActionMinutes!!)}", style = MaterialTheme.typography.bodySmall)
    }
}
