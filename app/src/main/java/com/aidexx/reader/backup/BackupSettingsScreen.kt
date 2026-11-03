package com.aidexx.reader.backup

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun BackupSettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var summary by remember { mutableStateOf(AidexBackupManager.summary(context)) }
    var backups by remember { mutableStateOf<List<BackupEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var restoreName by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        summary = AidexBackupManager.summary(context)
        scope.launch {
            backups = withContext(Dispatchers.IO) {
                runCatching { AidexBackupManager.listBackups(context) }
                    .getOrDefault(emptyList())
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                AidexBackupManager.setBackupFolder(context, uri)
                message = "Google Drive backup folder selected."
                refresh()
            }.onFailure {
                message = "Could not use that folder: ${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    val restoreFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            restoreUri = uri
            restoreName = "Selected backup file"
        }
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Data & Backup",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Back up GlucoTrail history, notes, optimized note photos, calibrations, sensor records and settings to a Google Drive folder. Backups are encrypted before they leave the phone.",
            style = MaterialTheme.typography.bodyMedium
        )

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Google Drive Backup", fontWeight = FontWeight.Bold)
                Text(
                    if (summary.folderConfigured) {
                        "Backup folder: Connected"
                    } else {
                        "Backup folder: Not selected"
                    }
                )
                OutlinedButton(
                    onClick = { folderPicker.launch(null) },
                    enabled = !busy
                ) {
                    Text(if (summary.folderConfigured) "Change Google Drive Folder" else "Choose Google Drive Folder")
                }

                Text(
                    if (summary.passphraseConfigured) {
                        "Backup password: Saved securely on this phone"
                    } else {
                        "Backup password: Not set"
                    }
                )
                OutlinedButton(
                    onClick = { showPasswordDialog = true },
                    enabled = !busy
                ) {
                    Text(if (summary.passphraseConfigured) "Change Backup Password" else "Set Backup Password")
                }
                Text(
                    "Keep this password somewhere safe. A replacement phone needs it to decrypt your backups. Changing it affects new backups only.",
                    style = MaterialTheme.typography.bodySmall
                )

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Automatic Daily Backup", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Keeps the latest 5 automatic backups. Manual backups are never automatically deleted.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = summary.autoEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && !summary.folderConfigured) {
                                message = "Choose a Google Drive folder first."
                            } else if (enabled && !summary.passphraseConfigured) {
                                message = "Set a backup password first."
                                showPasswordDialog = true
                            } else {
                                AidexBackupManager.setAutomaticBackupEnabled(context, enabled)
                                refresh()
                            }
                        },
                        enabled = !busy
                    )
                }

                Button(
                    onClick = {
                        when {
                            !summary.folderConfigured -> folderPicker.launch(null)
                            !summary.passphraseConfigured -> showPasswordDialog = true
                            else -> scope.launch {
                                busy = true
                                message = "Creating encrypted backup..."
                                val result = withContext(Dispatchers.IO) {
                                    AidexBackupManager.createBackup(context, manual = true)
                                }
                                message = result.fold(
                                    onSuccess = { "Backup saved: ${it.name}" },
                                    onFailure = { "Backup failed: ${it.message ?: it.javaClass.simpleName}" }
                                )
                                busy = false
                                refresh()
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Working..." else "Back Up Now")
                }

                if (summary.lastBackupAtMs > 0L) {
                    Text(
                        "Last backup: ${formatDateTime(summary.lastBackupAtMs)}${summary.lastBackupName?.let { " • $it" }.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                summary.lastError?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Restore", fontWeight = FontWeight.Bold)
                Text(
                    "On a new phone, choose the same Google Drive folder and enter the backup password. You can also open an individual .axbackup file.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = {
                        restoreFilePicker.launch(
                            arrayOf("application/octet-stream", "application/zip", "*/*")
                        )
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Restore Backup File")
                }

                if (summary.folderConfigured) {
                    HorizontalDivider()
                    Text("Backups in selected folder", fontWeight = FontWeight.SemiBold)
                    if (backups.isEmpty()) {
                        Text("No GlucoTrail backups found.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        backups.forEach { entry ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !busy) {
                                        restoreUri = entry.uri
                                        restoreName = entry.name
                                    }
                                    .padding(vertical = 7.dp)
                            ) {
                                Text(
                                    entry.name,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "${if (entry.manual) "Manual" else "Automatic"} • ${formatDateTime(entry.modifiedAtMs)} • ${formatBytes(entry.sizeBytes)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

        message?.let {
            Text(
                it,
                color = if (it.contains("failed", ignoreCase = true) || it.contains("could not", ignoreCase = true)) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    if (showPasswordDialog) {
        BackupPasswordDialog(
            onDismiss = { showPasswordDialog = false },
            onSave = { passphrase ->
                runCatching {
                    BackupPassphraseStore.save(context, passphrase)
                    showPasswordDialog = false
                    message = "Backup password saved securely on this phone."
                    refresh()
                }.onFailure {
                    message = it.message ?: "Backup password could not be saved."
                }
            }
        )
    }

    restoreUri?.let { uri ->
        RestorePasswordDialog(
            backupName = restoreName ?: "GlucoTrail backup",
            onDismiss = {
                restoreUri = null
                restoreName = null
            },
            onRestore = { password ->
                scope.launch {
                    busy = true
                    message = "Checking and preparing restore..."
                    val result = withContext(Dispatchers.IO) {
                        AidexBackupManager.stageRestoreFromUri(
                            context,
                            uri,
                            password
                        )
                    }
                    result.fold(
                        onSuccess = {
                            message = "Restore prepared. Close GlucoTrail completely and reopen it to apply the restored data."
                            restoreUri = null
                            restoreName = null
                        },
                        onFailure = {
                            message = "Restore failed: ${it.message ?: it.javaClass.simpleName}"
                        }
                    )
                    busy = false
                    refresh()
                }
            }
        )
    }
}

@Composable
private fun BackupPasswordDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = password.length >= 8 && password == confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup Password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Use at least 8 characters. This password is required to restore on another phone.")
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("Confirm password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                if (confirm.isNotEmpty() && password != confirm) {
                    Text("Passwords do not match.", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(password) },
                enabled = valid
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun RestorePasswordDialog(
    backupName: String,
    onDismiss: () -> Unit,
    onRestore: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore GlucoTrail Data") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(backupName, fontWeight = FontWeight.SemiBold)
                Text("Enter the password used when this backup was created.")
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Backup password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Text(
                    "The restore is staged first. Existing app data is not replaced until GlucoTrail is restarted.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRestore(password) },
                enabled = password.length >= 8
            ) { Text("Prepare Restore") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatDateTime(value: Long): String =
    if (value <= 0L) {
        "Unknown time"
    } else {
        DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT
        ).format(Date(value))
    }

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "size unknown"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format("%.0f KB", kb)
    return String.format("%.1f MB", kb / 1024.0)
}
