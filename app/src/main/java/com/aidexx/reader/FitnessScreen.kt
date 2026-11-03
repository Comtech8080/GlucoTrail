package com.aidexx.reader

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.UUID

private fun MachineLog.json() = JSONObject().put("id", id).put("name", name).put("category", category)
    .put("weight", weight).put("sets", sets).put("reps", reps).put("unit", unit)
    .put("useEditedValues", useEditedValues).put("treadmill", treadmill).put("minutes", minutes).put("speed", speed).put("incline", incline)
    .put("distance", distance).put("distanceUnit", distanceUnit).put("enabled", enabled).put("done", done).put("completedAt", completedAt).put("previous", previous)
private fun JSONObject.machine() = MachineLog(getString("id"), getString("name"), getString("category"),
    getString("weight"), getString("sets"), getString("reps"), optString("unit", "lb"),
    optBoolean("enabled", true), optBoolean("done"), optLong("completedAt"), optString("previous"),
    optBoolean("treadmill"), optString("minutes", "20"), optString("speed", "3"),
    optString("incline", "0"), optString("distance", "1"), optString("distanceUnit", "mi"), optBoolean("useEditedValues"))
private fun JSONArray.machines() = (0 until length()).map { getJSONObject(it).machine() }
private fun List<MachineLog>.json() = JSONArray().also { a -> forEach { a.put(it.json()) } }

private class FitnessStore(context: Context) {
    private val prefs = context.getSharedPreferences("aidexx_reader", Context.MODE_PRIVATE)
    private val saved = prefs.getString("fitness_state_v1", null)?.let { JSONObject(it) }
    var machines by mutableStateOf(saved?.getJSONArray("machines")?.machines() ?: defaultMachines())
    var unit by mutableStateOf(saved?.optString("unit", "lb")?.takeIf { it == "lb" || it == "kg" } ?: "lb")
    var distanceUnit by mutableStateOf(saved?.optString("distanceUnit", "mi")?.takeIf { it == "mi" || it == "km" } ?: "mi")
    var start by mutableStateOf(saved?.optLong("start") ?: 0L)
    var history by mutableStateOf(saved?.getJSONArray("history")?.let { a ->
        (0 until a.length()).map { i -> a.getJSONObject(i).let {
            MachineWorkout(it.getLong("start"), it.getLong("end"), it.getJSONArray("machines").machines())
        } }
    } ?: emptyList())
    private fun save() {
        val h = JSONArray()
        history.forEach { h.put(JSONObject().put("start", it.startedAt).put("end", it.endedAt).put("machines", it.machines.json())) }
        prefs.edit().putString("fitness_state_v1", JSONObject().put("treadmillAdded", true).put("machines", machines.json()).put("start", start).put("unit", unit).put("distanceUnit", distanceUnit).put("history", h).toString()).apply()
    }
    init {
        if(saved?.optBoolean("treadmillAdded", false) != true && machines.none { it.id == "treadmill" }) machines = machines + treadmillMachine().copy(enabled=start==0L)
        if(start == 0L) machines = machines.map { it.inFitnessUnit(unit).inDistanceUnit(distanceUnit) }
        save()
    }
    fun setDistance(value: String) {
        if(start != 0L) return
        require(value == "mi" || value == "km")
        machines = machines.map { it.inDistanceUnit(value) }; distanceUnit=value; save()
    }
    fun setWeightUnit(value: String) {
        if(start != 0L) return
        require(value == "lb" || value == "kg")
        machines = machines.map { it.inFitnessUnit(value) }; unit = value; save()
    }
    fun update(m: MachineLog) { machines = machines.map { if(it.id == m.id) m else it }; save() }
    fun remove(id: String) { machines = machines.filterNot { it.id == id }; save() }
    fun edit(m: MachineLog) { update(m.copy(useEditedValues=true)) }
    fun add(m: MachineLog) { machines = machines + m; save() }
    fun begin() { machines = nextMachineWorkout(machines, history).map { it.inFitnessUnit(unit).inDistanceUnit(distanceUnit) }; start = System.currentTimeMillis(); save() }
    fun discard() { machines = nextMachineWorkout(machines, history).map { it.inFitnessUnit(unit).inDistanceUnit(distanceUnit) }; start = 0; save() }
    fun finish() {
        val workout = finishedMachineWorkout(start, System.currentTimeMillis(), machines) ?: return
        history = listOf(workout) + history
        machines = machines.map { if(it.done && it.enabled && it.valid()) it.copy(useEditedValues=false) else it }
        machines = nextMachineWorkout(machines, history).map { it.inFitnessUnit(unit).inDistanceUnit(distanceUnit) }
        start = 0; save()
    }
}
private fun fitnessTime(time: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FitnessScreen(settingsOnly: Boolean = false, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current.applicationContext
    val result = remember { runCatching { FitnessStore(context) } }
    val store = result.getOrNull()
    if(store == null) { Text("Fitness records could not be opened. Your saved data has been kept."); return }
    if(settingsOnly) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Fitness Settings", style=MaterialTheme.typography.headlineMedium)
            Text("Weight unit", style=MaterialTheme.typography.titleMedium)
            Text("Pounds (lb) is the default. This setting applies to all machines and new workouts.")
            listOf("lb" to "Pounds (lb)", "kg" to "Kilograms (kg)").forEach { (value, label) ->
                FilterChip(selected=store.unit==value, onClick={store.setWeightUnit(value)},
                    enabled=store.start==0L, label={Text(label)})
            }
            Text("Treadmill distance and speed", style=MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("mi" to "Miles / mph", "km" to "Kilometres / km/h").forEach { (value, label) ->
                    FilterChip(selected=store.distanceUnit==value, onClick={store.setDistance(value)},
                        enabled=store.start==0L, label={Text(label)})
                }
            }
            Text("Changing units converts machine weights. Workout history keeps the original recorded units.")
            if(store.start>0) Text("Finish or discard the current workout before changing units.")
        }
        return
    }
    var page by rememberSaveable { mutableStateOf("Workout") }
    var menuMachine by remember { mutableStateOf<MachineLog?>(null) }
    var editingMachine by remember { mutableStateOf<MachineLog?>(null) }
    var removingMachine by remember { mutableStateOf<MachineLog?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }
    val active = store.machines.filter { it.enabled }.sortedByDescending { it.treadmill }
    val completed = active.count { it.done }
    Column(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
        if(onBack != null) TextButton(onClick=onBack) { Text("‹ Glucose") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
            Column {
                Text("Fitness", style=MaterialTheme.typography.headlineMedium, fontWeight=FontWeight.Bold)
                Text("YOUR GYM · YOUR PROGRESS", style=MaterialTheme.typography.labelSmall, color=MaterialTheme.colorScheme.primary)
            }
            Surface(shape=RoundedCornerShape(12.dp), color=MaterialTheme.colorScheme.secondaryContainer) {
                Text("${store.history.size} workouts", Modifier.padding(10.dp), style=MaterialTheme.typography.labelMedium)
            }
        }
        if(onBack != null) Text("Swipe right for Weight", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            listOf("Workout", "Machines", "History").forEach { label ->
                FilterChip(selected=page==label, onClick={page=label}, label={Text(label)})
            }
        }
        if(page == "Workout") {
            Card(Modifier.fillMaxWidth().padding(vertical=8.dp), shape=RoundedCornerShape(24.dp),
                colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                        Text(if(store.start>0) "Workout in progress" else "Ready when you are", style=MaterialTheme.typography.titleMedium, fontWeight=FontWeight.Bold)
                        Text("${if(active.isEmpty()) 0 else completed*100/active.size}%", style=MaterialTheme.typography.titleMedium)
                    }
                    Text("$completed / ${active.size} machines completed")
                    LinearProgressIndicator(progress={ if(active.isEmpty()) 0f else completed.toFloat()/active.size },
                        modifier=Modifier.fillMaxWidth().height(10.dp))
                    if(store.start == 0L) {
                        Button(onClick={store.begin()}, enabled=active.isNotEmpty(), modifier=Modifier.fillMaxWidth()) { Text("Start Workout") }
                    } else {
                        Text("Started ${fitnessTime(store.start)}", style=MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick={confirming=true}, enabled=completed>0, modifier=Modifier.weight(1f)) { Text("Finish Workout") }
                            TextButton(onClick={discarding=true}) { Text("Discard") }
                        }
                    }
                }
            }
            if(active.isEmpty()) Text("Enable or add machines in Machines to get started.")
            LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                items(active, key={it.id}) { m ->
                    Card(Modifier.fillMaxWidth().combinedClickable(onClick={}, onLongClick={menuMachine=m}, onLongClickLabel="Edit or remove machine"), shape=RoundedCornerShape(20.dp), colors=CardDefaults.cardColors(
                        containerColor=if(m.done) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)) { Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(m.name, style=MaterialTheme.typography.titleMedium)
                        Text(m.category, style=MaterialTheme.typography.bodySmall)
                        Text(if(m.previous.isBlank()) "No previous workout" else "Last: ${m.previous}")
                        if(m.treadmill) {
                            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                FitnessNumber("Minutes", m.minutes, !m.done && store.start>0, Modifier.weight(1f)) { store.update(m.copy(minutes=it)) }
                                FitnessNumber("Speed (${if(m.distanceUnit=="mi") "mph" else "km/h"})", m.speed, !m.done && store.start>0, Modifier.weight(1f)) { store.update(m.copy(speed=it)) }
                            }
                            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                FitnessNumber("Incline (%)", m.incline, !m.done && store.start>0, Modifier.weight(1f)) { store.update(m.copy(incline=it)) }
                                FitnessNumber("Distance (${m.distanceUnit})", m.distance, !m.done && store.start>0, Modifier.weight(1f)) { store.update(m.copy(distance=it)) }
                            }
                        } else {
                        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            FitnessNumber("Weight (${m.unit})", m.weight, !m.done && store.start>0, Modifier.weight(1f)) { store.update(m.copy(weight=it)) }
                            FitnessNumber("Sets", m.sets, !m.done && store.start>0, Modifier.weight(1f)) { store.update(m.copy(sets=it)) }
                            FitnessNumber("Reps", m.reps, !m.done && store.start>0, Modifier.weight(1f)) { store.update(m.copy(reps=it)) }
                        }
                        }
                        Row {
                            TextButton(onClick={store.update(m.copy(done=!m.done, completedAt=if(m.done) 0 else System.currentTimeMillis()))}, enabled=store.start>0 && m.valid()) { Text(if(m.done) "Completed ✓ · Undo" else "Mark complete") }
                        }
                        if(!m.valid()) Text(if(m.treadmill) "Enter minutes 0.1–1,440, speed 0.1–100, incline -15–50% and distance 0–1,000." else "Enter weight 0–10,000, sets 1–100 and reps 1–1,000.", color=MaterialTheme.colorScheme.error)
                    } }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        } else if(page == "Machines") {
            Text("Common machine functions for any gym. Weight unit: ${store.unit}. Change it in Fitness Settings.")
            Button(onClick={adding=true}, enabled=store.start==0L) { Text("Add custom machine") }
            Text("Press and hold a machine to edit or remove it.")
            if(store.start>0) Text("Finish the current workout before enabling or adding machines.")
            LazyColumn { items(store.machines, key={it.id}) { m ->
                Row(Modifier.fillMaxWidth().combinedClickable(onClick={}, onLongClick={menuMachine=m}, onLongClickLabel="Edit or remove machine").padding(vertical=8.dp), horizontalArrangement=Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text(m.name); Text(m.category, style=MaterialTheme.typography.bodySmall) }
                    Switch(checked=m.enabled, onCheckedChange={store.update(m.copy(enabled=it))}, enabled=store.start==0L)
                }
            } }
        } else {
            if(store.history.isEmpty()) Text("Finished workouts will appear here.")
            LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                items(store.history) { w -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(fitnessTime(w.startedAt), style=MaterialTheme.typography.titleMedium)
                    Text("Ended ${fitnessTime(w.endedAt)}")
                    Text("${w.machines.size} machines · ${w.totalSets} sets · ${w.totalReps} reps · ${((w.endedAt-w.startedAt)/60000).coerceAtLeast(0)} min")
                    w.machines.forEach { Text("${it.name}: ${it.summary()}") }
                } } }
            }
        }
    }
    menuMachine?.let { machine ->
        AlertDialog(onDismissRequest={menuMachine=null}, title={Text(machine.name)},
            text={Column(Modifier.fillMaxWidth()) {
                TextButton(onClick={menuMachine=null; editingMachine=machine}, modifier=Modifier.fillMaxWidth()) { Text("Edit machine") }
                TextButton(onClick={menuMachine=null; removingMachine=machine}, modifier=Modifier.fillMaxWidth()) { Text("Remove machine", color=MaterialTheme.colorScheme.error) }
            }}, confirmButton={TextButton(onClick={menuMachine=null}) {Text("Cancel")}})
    }
    editingMachine?.let { machine ->
        key(machine.id) { EditFitnessMachine(machine, onDismiss={editingMachine=null}, onSave={store.edit(it); editingMachine=null}) }
    }
    removingMachine?.let { machine ->
        AlertDialog(onDismissRequest={removingMachine=null}, title={Text("Remove ${machine.name}?")},
            text={Text("This removes the machine from your list and the current workout, including any completion logged for it. Past workout history is kept.")},
            confirmButton={TextButton(onClick={store.remove(machine.id); removingMachine=null}) {Text("Remove", color=MaterialTheme.colorScheme.error)}},
            dismissButton={TextButton(onClick={removingMachine=null}) {Text("Cancel")}})
    }
    if(confirming) AlertDialog(onDismissRequest={confirming=false}, title={Text("Finish workout?")},
        text={Text("Save $completed completed machines. ${active.size-completed} unfinished machines will not be included.")},
        confirmButton={TextButton(onClick={store.finish(); confirming=false; page="History"}) { Text("Save workout") }},
        dismissButton={TextButton(onClick={confirming=false}) { Text("Keep logging") }})
    if(adding) AddFitnessMachine(unit=store.unit, onDismiss={adding=false}, onAdd={store.add(it); adding=false})
    if(discarding) AlertDialog(onDismissRequest={discarding=false}, title={Text("Discard this workout?")},
        text={Text("This unfinished workout will not be saved to history. Previous workouts are kept.")},
        confirmButton={TextButton(onClick={store.discard(); discarding=false}) {Text("Discard")}},
        dismissButton={TextButton(onClick={discarding=false}) {Text("Keep logging")}})
}

@Composable
private fun FitnessNumber(label: String, value: String, enabled: Boolean, modifier: Modifier, changed: (String)->Unit) {
    OutlinedTextField(value=value, onValueChange={if(it.length<=10) changed(it)}, label={Text(label)},
        singleLine=true, enabled=enabled, modifier=modifier, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
}
@Composable
private fun AddFitnessMachine(unit: String, onDismiss: ()->Unit, onAdd: (MachineLog)->Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var weight by rememberSaveable { mutableStateOf("0") }
    val machine = MachineLog("", name.trim(), category.trim(), weight=weight, unit=unit)
    AlertDialog(onDismissRequest=onDismiss, title={Text("Add machine")}, text={Column {
        OutlinedTextField(name, {name=it.take(80)}, label={Text("Machine name")}, singleLine=true)
        OutlinedTextField(category, {category=it.take(40)}, label={Text("Category (e.g. Legs)")}, singleLine=true)
        FitnessNumber("Default weight ($unit)", weight, true, Modifier.fillMaxWidth()) {weight=it}
    }}, confirmButton={TextButton(enabled=name.isNotBlank() && category.isNotBlank() && machine.valid(),
        onClick={onAdd(machine.copy(id=UUID.randomUUID().toString()))}) {Text("Add")}},
        dismissButton={TextButton(onClick=onDismiss) {Text("Cancel")}})
}

@Composable
private fun EditFitnessMachine(original: MachineLog, onDismiss: () -> Unit, onSave: (MachineLog) -> Unit) {
    var name by rememberSaveable { mutableStateOf(original.name) }
    var category by rememberSaveable { mutableStateOf(original.category) }
    var weight by rememberSaveable { mutableStateOf(original.weight) }
    var sets by rememberSaveable { mutableStateOf(original.sets) }
    var reps by rememberSaveable { mutableStateOf(original.reps) }
    var minutes by rememberSaveable { mutableStateOf(original.minutes) }
    var speed by rememberSaveable { mutableStateOf(original.speed) }
    var incline by rememberSaveable { mutableStateOf(original.incline) }
    var distance by rememberSaveable { mutableStateOf(original.distance) }
    val edited = original.copy(name=name.trim(), category=category.trim(), weight=weight, sets=sets,
        reps=reps, minutes=minutes, speed=speed, incline=incline, distance=distance)
    AlertDialog(onDismissRequest=onDismiss, title={Text("Edit machine")}, text={
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, {name=it.take(80)}, label={Text("Machine name")}, singleLine=true)
            OutlinedTextField(category, {category=it.take(40)}, label={Text("Category")}, singleLine=true)
            if(original.treadmill) {
                FitnessNumber("Minutes", minutes, true, Modifier.fillMaxWidth()) {minutes=it}
                FitnessNumber("Speed (${if(original.distanceUnit=="mi") "mph" else "km/h"})", speed, true, Modifier.fillMaxWidth()) {speed=it}
                FitnessNumber("Incline (%)", incline, true, Modifier.fillMaxWidth()) {incline=it}
                FitnessNumber("Distance (${original.distanceUnit})", distance, true, Modifier.fillMaxWidth()) {distance=it}
            } else {
                FitnessNumber("Weight (${original.unit})", weight, true, Modifier.fillMaxWidth()) {weight=it}
                FitnessNumber("Sets", sets, true, Modifier.fillMaxWidth()) {sets=it}
                FitnessNumber("Reps", reps, true, Modifier.fillMaxWidth()) {reps=it}
            }
            Text("These values will be used for this machine next time. Past workouts are unchanged.", style=MaterialTheme.typography.bodySmall)
            if(!edited.valid()) Text("Enter valid numbers before saving.", color=MaterialTheme.colorScheme.error)
        }
    }, confirmButton={TextButton(enabled=name.isNotBlank() && category.isNotBlank() && edited.valid(), onClick={onSave(edited)}) {Text("Save")}},
        dismissButton={TextButton(onClick=onDismiss) {Text("Cancel")}})
}
