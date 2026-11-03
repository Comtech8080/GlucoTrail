package com.aidexx.reader

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.aidexx.reader.data.GlucoseNote
import com.aidexx.reader.data.NotePhotoStore
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NotesHistoryScreen(
    vm: AidexViewModel,
    serial: String,
    notes: List<GlucoseNote>
) {
    var selectedNoteId by remember { mutableStateOf<Long?>(null) }
    var editingNote by remember { mutableStateOf<GlucoseNote?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<GlucoseNote?>(null) }
    var healthPage by remember { mutableStateOf<String?>(null) }
    var healthEntry by remember { mutableStateOf<String?>(null) }
    var healthSaveMessage by remember { mutableStateOf<String?>(null) }
    val healthContext = LocalContext.current
    val healthDb = remember { com.aidexx.reader.data.AidexHistoryDatabase(healthContext.applicationContext) }

    val selectedNote = selectedNoteId?.let { id -> notes.firstOrNull { it.id == id } }

    LaunchedEffect(selectedNoteId, notes) {
        if (selectedNoteId != null && selectedNote == null) selectedNoteId = null
    }

    BackHandler(enabled = selectedNote != null) {
        selectedNoteId = null
    }

    if (healthEntry == "bp") {
        BloodPressureEditor(
            existing = null,
            dismiss = { healthEntry = null },
            save = { entry ->
                if (healthDb.saveBloodPressure(entry) > 0L) {
                    healthEntry = null
                    healthSaveMessage = "Blood pressure saved. View it in BP history or the Blood Pressure report."
                }
            },
            delete = {}
        )
    } else if (healthEntry == "weight") {
        WeightEditor(
            existing = null,
            pounds = true,
            dismiss = { healthEntry = null },
            save = { entry ->
                if (healthDb.saveWeight(entry) > 0L) {
                    healthEntry = null
                    healthSaveMessage = "Weight saved in lb. View it in Weight history or the Weight report."
                }
            },
            delete = {}
        )
    }

    if (healthPage == "bp") {
        BloodPressureScreen(onBack = { healthPage = null })
        return
    } else if (healthPage == "weight") {
        WeightScreen(onBack = { healthPage = null })
        return
    }

    if (selectedNote == null) {
        NotesHistoryList(
            notes = notes,
            onAddBloodPressure = { editingNote = null; editorOpen = true },
            onAddWeight = { editingNote = null; editorOpen = true },
            healthSaveMessage = healthSaveMessage,
            onBloodPressure = { healthPage = "bp" },
            onWeight = { healthPage = "weight" },
            onAdd = {
                editingNote = null
                editorOpen = true
            },
            onOpen = { selectedNoteId = it.id }
        )
    } else {
        NoteDetailsPage(
            note = selectedNote,
            onBack = { selectedNoteId = null },
            onEdit = {
                editingNote = selectedNote
                editorOpen = true
            },
            onDelete = { deleteTarget = selectedNote }
        )
    }

    if (editorOpen) {
        AdvancedGlucoseNoteEditorDialog(
            serial = serial,
            existing = editingNote,
            onSave = { note ->
                vm.saveGlucoseNote(note)
                editorOpen = false
                if (note.id > 0L) selectedNoteId = note.id
            },
            onDelete = editingNote?.let { note ->
                {
                    deleteTarget = note
                    editorOpen = false
                }
            },
            onDismiss = { editorOpen = false }
        )
    }

    deleteTarget?.let { note ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete note?") },
            text = {
                Text("This removes the note and its GlucoTrail photo attachments from this phone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deleteGlucoseNote(note)
                        if (selectedNoteId == note.id) selectedNoteId = null
                        deleteTarget = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun NotesHistoryList(
    notes: List<GlucoseNote>,
    onAddBloodPressure: () -> Unit,
    onAddWeight: () -> Unit,
    healthSaveMessage: String?,
    onBloodPressure: () -> Unit,
    onWeight: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (GlucoseNote) -> Unit
) {
    var selectedDays by remember { mutableIntStateOf(7) }
    val cutoff = System.currentTimeMillis() - selectedDays * 86400000L
    val ordered = notes.filter { selectedDays == 0 || it.timestampMs >= cutoff }
        .sortedByDescending { it.timestampMs }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "Notes History",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (ordered.isEmpty()) "No notes in this period" else "${ordered.size} notes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(onClick = onAdd) {
                Text("+ Add Note")
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAddBloodPressure, modifier = Modifier.weight(1f)) { Text("+ Add BP") }
            OutlinedButton(onClick = onAddWeight, modifier = Modifier.weight(1f)) { Text("+ Weight (lb)") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBloodPressure, modifier = Modifier.weight(1f)) { Text("BP history") }
            TextButton(onClick = onWeight, modifier = Modifier.weight(1f)) { Text("Weight history") }
        }
        healthSaveMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(10.dp))

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(7, 14, 30, 60, 90, 180, 0).forEach { days ->
                FilterChip(
                    selected = selectedDays == days,
                    onClick = { selectedDays = days },
                    label = { Text(if (days == 0) "All" else "${days}d") }
                )
            }
        }

        if (ordered.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("No notes in this period", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "Add insulin, carbs, a text note, or a photo. You can also backdate a note if you forgot to enter it earlier.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 18.dp)
            ) {
                items(ordered, key = { it.id }) { note ->
                    NoteHistoryRow(note = note, onClick = { onOpen(note) })
                }
            }
        }
    }
}

@Composable
private fun NoteHistoryRow(
    note: GlucoseNote,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatNoteDateTime(note.timestampMs),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Text("›", style = MaterialTheme.typography.titleLarge)
            }

            Text(
                noteSummaryText(note),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (note.hasPhotos) {
                Text(
                    "📷 ${note.photoPaths.size} ${if (note.photoPaths.size == 1) "photo" else "photos"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun NoteDetailsPage(
    note: GlucoseNote,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TextButton(onClick = onBack) {
            Text("‹ All Notes")
        }

        Text(
            "Note Details",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Card(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                DetailLine("Date & Time", formatNoteDateTime(note.timestampMs))
                note.systolic?.let { DetailLine("Blood pressure", "$it/${note.diastolic} mmHg") }
                note.pulse?.let { DetailLine("Pulse", "$it bpm") }
                note.weightKg?.let { DetailLine("Weight", "%.1f lb".format(it*2.2046226218)) }
                note.bodyFatPercent?.let { DetailLine("Body fat", "%.1f%%".format(it)) }
                note.heightCm?.let { DetailLine("Height", "%.1f in".format(it/2.54)) }
                if (note.hasFastInsulin) DetailLine("Fast Insulin", "${formatNoteValue(note.fastInsulinUnits)} U")
                if (note.hasSlowInsulin) DetailLine("Slow Insulin", "${formatNoteValue(note.slowInsulinUnits)} U")
                if (note.hasCarbs) DetailLine("Carbs", "${formatNoteValue(note.carbsGrams)} g")

                if (note.hasText) {
                    HorizontalDivider()
                    Text("Notes", fontWeight = FontWeight.Bold)
                    Text(note.noteText)
                }
            }
        }

        if (note.hasPhotos) {
            Text(
                "Photos (${note.photoPaths.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            note.photoPaths.forEach { path ->
                NotePhotoCard(path = path, large = true)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onEdit,
                modifier = Modifier.weight(1f)
            ) {
                Text("Edit Note")
            }
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.weight(1f)
            ) {
                Text("Delete Note", color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun AdvancedGlucoseNoteEditorDialog(
    serial: String,
    existing: GlucoseNote?,
    onSave: (GlucoseNote) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val originalPhotos = remember(existing?.id) { existing?.photoPaths.orEmpty() }

    var fastText by remember(existing?.id) {
        mutableStateOf(existing?.fastInsulinUnits?.takeIf { it > 0.0 }?.let(::formatNoteValue).orEmpty())
    }
    var slowText by remember(existing?.id) {
        mutableStateOf(existing?.slowInsulinUnits?.takeIf { it > 0.0 }?.let(::formatNoteValue).orEmpty())
    }
    var carbsText by remember(existing?.id) {
        mutableStateOf(existing?.carbsGrams?.takeIf { it > 0.0 }?.let(::formatNoteValue).orEmpty())
    }
    var systolicText by remember(existing?.id) { mutableStateOf(existing?.systolic?.toString().orEmpty()) }
    var diastolicText by remember(existing?.id) { mutableStateOf(existing?.diastolic?.toString().orEmpty()) }
    var pulseText by remember(existing?.id) { mutableStateOf(existing?.pulse?.toString().orEmpty()) }
    var weightText by remember(existing?.id) { mutableStateOf(existing?.weightKg?.let { "%.2f".format(Locale.US,it*2.2046226218) }.orEmpty()) }
    var bodyFatText by remember(existing?.id) { mutableStateOf(existing?.bodyFatPercent?.toString().orEmpty()) }
    // New notes reuse the saved height across restarts and sensor changes.
    // Editing an older note preserves that note's original height (including blank).
    val initialHeightCm = remember(existing?.id) {
        if (existing != null) existing.heightCm else {
            val heightDb = com.aidexx.reader.data.AidexHistoryDatabase(context.applicationContext)
            try { heightDb.loadHeightCm(System.currentTimeMillis()) }
            finally { heightDb.close() }
        }
    }
    val initialHeightText = remember(existing?.id) {
        initialHeightCm?.let { "%.2f".format(Locale.US, it / 2.54) }.orEmpty()
    }
    var heightText by remember(existing?.id) { mutableStateOf(initialHeightText) }
    var notesText by remember(existing?.id) { mutableStateOf(existing?.noteText.orEmpty()) }
    var noteTimeMs by remember(existing?.id) {
        mutableLongStateOf(existing?.timestampMs ?: System.currentTimeMillis())
    }
    var photoPaths by remember(existing?.id) { mutableStateOf(originalPhotos) }
    var newPhotoPaths by remember(existing?.id) { mutableStateOf(emptySet<String>()) }
    var photoBusy by remember { mutableStateOf(false) }
    var photoMessage by remember { mutableStateOf<String?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    fun importPhoto(uri: Uri, cleanup: (() -> Unit)? = null) {
        if (photoPaths.size >= 8) {
            photoMessage = "A note can contain up to 8 photos."
            cleanup?.invoke()
            return
        }
        photoBusy = true
        photoMessage = null
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                NotePhotoStore.importOptimizedPhoto(context, uri)
            }
            cleanup?.invoke()
            if (path != null) {
                photoPaths = (photoPaths + path).distinct()
                newPhotoPaths = newPhotoPaths + path
            } else {
                photoMessage = "Could not read that photo."
            }
            photoBusy = false
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { importPhoto(it) }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = cameraFile
        val uri = cameraUri
        if (success && file != null && uri != null) {
            importPhoto(uri) { file.delete() }
        } else {
            file?.delete()
        }
        cameraFile = null
        cameraUri = null
    }

    fun launchCamera() {
        runCatching {
            val file = NotePhotoStore.createCameraTempFile(context)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.notephotos",
                file
            )
            cameraFile = file
            cameraUri = uri
            cameraLauncher.launch(uri)
        }.onFailure {
            cameraFile?.delete()
            cameraFile = null
            cameraUri = null
            photoMessage = "Camera could not be opened."
        }
    }

    fun discardNewPhotosAndDismiss() {
        NotePhotoStore.deletePhotos(newPhotoPaths)
        cameraFile?.delete()
        onDismiss()
    }

    fun pickDateTime() {
        val initial = Calendar.getInstance().apply { timeInMillis = noteTimeMs }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                val chosen = Calendar.getInstance().apply {
                    timeInMillis = noteTimeMs
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                }
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        chosen.set(Calendar.HOUR_OF_DAY, hour)
                        chosen.set(Calendar.MINUTE, minute)
                        chosen.set(Calendar.SECOND, 0)
                        chosen.set(Calendar.MILLISECOND, 0)
                        noteTimeMs = chosen.timeInMillis.coerceAtMost(System.currentTimeMillis())
                    },
                    initial.get(Calendar.HOUR_OF_DAY),
                    initial.get(Calendar.MINUTE),
                    false
                ).show()
            },
            initial.get(Calendar.YEAR),
            initial.get(Calendar.MONTH),
            initial.get(Calendar.DAY_OF_MONTH)
        ).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    val fast = fastText.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
    val slow = slowText.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
    val carbs = carbsText.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
    val sys = systolicText.toIntOrNull()
    val dia = diastolicText.toIntOrNull()
    val pulse = pulseText.toIntOrNull()
    val weightLb = weightText.toDoubleOrNull()
    val heightIn = heightText.toDoubleOrNull()
    val heightWasEntered = heightIn != null && (existing?.heightCm != null || heightText != initialHeightText)
    val bodyFat = bodyFatText.toDoubleOrNull()
    val bodyFatValid = bodyFatText.isBlank() || (bodyFat != null && bodyFat.isFinite() && bodyFat in 0.0..100.0)
    val hasBp = systolicText.isNotBlank() || diastolicText.isNotBlank() || pulseText.isNotBlank()
    val healthValid = bodyFatValid && (!hasBp || ((sys ?: 0) > 0 && (dia ?: 0) > 0 && (pulseText.isBlank() || (pulse ?: 0) > 0))) &&
        (weightText.isBlank() || (weightLb != null && weightLb.isFinite() && weightLb > 0)) &&
        (heightText.isBlank() || (heightIn != null && heightIn.isFinite() && heightIn > 0))
    val canSave = healthValid && (fast > 0.0 || slow > 0.0 || carbs > 0.0 || notesText.isNotBlank() || photoPaths.isNotEmpty() || hasBp || weightLb != null || heightWasEntered || bodyFat != null)

    AlertDialog(
        onDismissRequest = ::discardNewPhotosAndDismiss,
        title = { Text(if (existing == null) "Add Note" else "Edit Note") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Date & Time", fontWeight = FontWeight.Bold)
                OutlinedButton(
                    onClick = ::pickDateTime,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(formatNoteDateTime(noteTimeMs))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    NoteTimeShortcut("Now", Modifier.weight(1f)) {
                        noteTimeMs = System.currentTimeMillis()
                    }
                    NoteTimeShortcut("15m", Modifier.weight(1f)) {
                        noteTimeMs = System.currentTimeMillis() - 15L * 60L * 1000L
                    }
                    NoteTimeShortcut("30m", Modifier.weight(1f)) {
                        noteTimeMs = System.currentTimeMillis() - 30L * 60L * 1000L
                    }
                    NoteTimeShortcut("1h", Modifier.weight(1f)) {
                        noteTimeMs = System.currentTimeMillis() - 60L * 60L * 1000L
                    }
                }

                OutlinedTextField(
                    value = fastText,
                    onValueChange = { fastText = sanitizeNoteDecimal(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Fast insulin") },
                    suffix = { Text("units") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = slowText,
                    onValueChange = { slowText = sanitizeNoteDecimal(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Slow insulin") },
                    suffix = { Text("units") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = carbsText,
                    onValueChange = { carbsText = sanitizeNoteDecimal(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Carbs per meal") },
                    suffix = { Text("g") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                HorizontalDivider()
                Text("Blood Pressure & Weight", fontWeight = FontWeight.Bold)
                OutlinedTextField(systolicText, { systolicText = it.filter(Char::isDigit).take(3) }, label = { Text("Systolic (mmHg)") }, modifier=Modifier.fillMaxWidth(), singleLine=true, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                OutlinedTextField(diastolicText, { diastolicText = it.filter(Char::isDigit).take(3) }, label = { Text("Diastolic (mmHg)") }, modifier=Modifier.fillMaxWidth(), singleLine=true, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                OutlinedTextField(pulseText, { pulseText = it.filter(Char::isDigit).take(3) }, label = { Text("Pulse (bpm, optional)") }, modifier=Modifier.fillMaxWidth(), singleLine=true, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                OutlinedTextField(weightText, { weightText = sanitizeNoteDecimal(it) }, label = { Text("Weight (lb)") }, modifier=Modifier.fillMaxWidth(), singleLine=true, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                OutlinedTextField(
                    bodyFatText,
                    { bodyFatText = it.replace(',', '.').take(12) },
                    label = { Text("Body fat (%)") },
                    placeholder = { Text("e.g. 33.3") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = !bodyFatValid,
                    supportingText = { Text(if(bodyFatValid) "Optional · enter the percentage shown on your scale" else "Enter a percentage from 0 to 100") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(heightText, { heightText = sanitizeNoteDecimal(it) }, label = { Text("Height (inches, remembered for new notes)") }, modifier=Modifier.fillMaxWidth(), singleLine=true, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                Text("Your saved height is filled in automatically. Change it here only when needed.", style=MaterialTheme.typography.bodySmall)
                Text("Saved with this note's date and time. Edit or delete this note to update the BP and Weight reports.", style=MaterialTheme.typography.bodySmall)
                if(!healthValid) Text("Enter both blood pressure values when recording BP, positive weight/height, and body fat from 0 to 100%.",color=MaterialTheme.colorScheme.error)
                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it.take(1000) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp),
                    label = { Text("Notes") },
                    minLines = 3,
                    maxLines = 7
                )

                HorizontalDivider()
                Text("Photos", fontWeight = FontWeight.Bold)
                Text(
                    "GlucoTrail saves an optimized copy only: max 1280 px and compressed for low storage use.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { galleryLauncher.launch("image/*") },
                        enabled = !photoBusy && photoPaths.size < 8,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Gallery")
                    }
                    OutlinedButton(
                        onClick = ::launchCamera,
                        enabled = !photoBusy && photoPaths.size < 8,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Camera")
                    }
                }

                if (photoBusy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Optimizing photo…", style = MaterialTheme.typography.labelSmall)
                }
                photoMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                photoPaths.forEach { path ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(Modifier.size(72.dp)) {
                            NotePhotoCard(path = path, large = false)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Photo", fontWeight = FontWeight.SemiBold)
                            Text(
                                formatBytes(NotePhotoStore.fileSizeBytes(path)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = {
                                photoPaths = photoPaths - path
                                if (path in newPhotoPaths) {
                                    NotePhotoStore.deletePhoto(path)
                                    newPhotoPaths = newPhotoPaths - path
                                }
                            }
                        ) {
                            Text("Remove")
                        }
                    }
                }

                if (existing != null && onDelete != null) {
                    OutlinedButton(
                        onClick = {
                            NotePhotoStore.deletePhotos(newPhotoPaths)
                            cameraFile?.delete()
                            onDelete()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete Note", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        GlucoseNote(
                            id = existing?.id ?: 0L,
                            serial = serial,
                            timestampMs = noteTimeMs.coerceAtMost(System.currentTimeMillis()),
                            fastInsulinUnits = fast,
                            slowInsulinUnits = slow,
                            carbsGrams = carbs,
                            noteText = notesText.trim(),
                            photoPaths = photoPaths.distinct(),
                            systolic = sys,
                            diastolic = dia,
                            pulse = pulse,
                            weightKg = weightLb?.div(2.2046226218),
                            heightCm = if (heightText == initialHeightText) initialHeightCm else heightIn?.times(2.54),
                            bodyFatPercent = bodyFat
                        )
                    )
                },
                enabled = canSave && !photoBusy
            ) {
                Text(if (existing == null) "Save Note" else "Save Changes")
            }
        },
        dismissButton = {
            TextButton(onClick = ::discardNewPhotosAndDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun NoteTimeShortcut(
    text: String,
    modifier: Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun NotePhotoCard(
    path: String,
    large: Boolean
) {
    val bitmap = remember(path, File(path).lastModified()) {
        runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    }

    Card(
        modifier = if (large) Modifier.fillMaxWidth() else Modifier.fillMaxSize(),
        shape = RoundedCornerShape(12.dp)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Note photo",
                modifier = if (large) {
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 360.dp)
                } else {
                    Modifier.fillMaxSize()
                },
                contentScale = if (large) ContentScale.Fit else ContentScale.Crop
            )
        } else {
            Box(
                modifier = if (large) Modifier.fillMaxWidth().height(120.dp) else Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Photo unavailable", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun formatNoteDateTime(timestampMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestampMs))

private fun formatNoteValue(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString()
    else String.format(Locale.US, "%.1f", value)

private fun sanitizeNoteDecimal(value: String): String {
    val filtered = value.filter { it.isDigit() || it == '.' || it == ',' }.replace(',', '.')
    val firstDot = filtered.indexOf('.')
    return if (firstDot < 0) filtered.take(7)
    else filtered.substring(0, firstDot + 1) + filtered.substring(firstDot + 1).replace(".", "").take(2)
}

private fun noteSummaryText(note: GlucoseNote): String {
    val parts = mutableListOf<String>()
    note.systolic?.let { parts += "BP $it/${note.diastolic}" }
    note.weightKg?.let { parts += "%.1f lb".format(it*2.2046226218) }
    note.bodyFatPercent?.let { parts += "Body fat %.1f%%".format(it) }
    note.heightCm?.let { parts += "Height %.1f in".format(it/2.54) }
    if (note.hasFastInsulin) parts += "Fast ${formatNoteValue(note.fastInsulinUnits)} U"
    if (note.hasSlowInsulin) parts += "Slow ${formatNoteValue(note.slowInsulinUnits)} U"
    if (note.hasCarbs) parts += "Carbs ${formatNoteValue(note.carbsGrams)} g"
    if (note.hasText) parts += note.noteText.replace("\n", " ")
    if (note.hasPhotos) parts += "📷 ${note.photoPaths.size}"
    return parts.joinToString(" • ").ifBlank { "Note" }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "Optimized"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
