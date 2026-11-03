package com.aidexx.reader.updates

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aidexx.reader.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.DateFormat
import java.util.Date

data class UpdatesState(val release: AppRelease? = null, val busy: Boolean = false,
                        val progress: Float? = null, val ready: File? = null,
                        val message: String = "Check GitHub for a newer version.")

class UpdatesViewModel(app: Application) : AndroidViewModel(app) {
    private val client = GitHubUpdates(app)
    private val mutable = MutableStateFlow(UpdatesState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun check(beta: Boolean) {
        if (mutable.value.busy) return
        mutable.value.ready?.delete()
        mutable.value = UpdatesState(busy = true, message = "Checking GitHub…")
        job = viewModelScope.launch {
            try {
                val release = client.latest(beta)
                UpdateScheduler.preferences(getApplication()).edit().putLong("checked", System.currentTimeMillis()).remove("error").apply()
                mutable.value = UpdatesState(release = release, message = when {
                    release == null -> "No compatible GlucoTrail releases published yet."
                    release.code > BuildConfig.VERSION_CODE -> "An update is available."
                    else -> "You are up to date."
                })
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { mutable.value = UpdatesState(message = failure.message ?: "Check failed. Please retry.") }
        }
    }
    fun download() {
        val release = mutable.value.release ?: return
        if (mutable.value.busy || release.code <= BuildConfig.VERSION_CODE) return
        mutable.value.ready?.delete()
        mutable.value = mutable.value.copy(busy = true, progress = 0f, ready = null, message = "Downloading APK…")
        job = viewModelScope.launch {
            try {
                val file = client.download(release) { progress ->
                    mutable.value = mutable.value.copy(progress = progress)
                }
                mutable.value = mutable.value.copy(busy = false, progress = null, ready = file,
                    message = "APK verified. Tap Install update to continue.")
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { fail(failure) }
        }
    }
    fun cancel() {
        job?.cancel()
        mutable.value = mutable.value.copy(busy = false, progress = null, ready = null, message = "Cancelled. You can try again.")
    }
    fun install(context: Context) {
        val state = mutable.value
        val file = state.ready ?: return
        val release = state.release ?: return
        if (state.busy) return
        mutable.value = state.copy(busy = true, message = "Rechecking APK…")
        job = viewModelScope.launch {
            try {
                client.verifyBeforeInstall(file, release)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    clipData = ClipData.newRawUri("GlucoTrail update", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
                mutable.value = mutable.value.copy(busy = false, message = "Confirm the update in Android. If cancelled, tap Install update to retry.")
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { fail(failure) }
        }
    }
    fun message(text: String) { mutable.value = mutable.value.copy(message = text) }
    private fun fail(failure: Exception) {
        mutable.value = mutable.value.copy(busy = false, progress = null, ready = null,
            message = failure.message ?: "Update failed. Please retry.")
    }
}

class AppUpdatesActivity : ComponentActivity() {
    private val vm: UpdatesViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { UpdateScreen(vm, this) }
            }
        }
    }
}

@Composable
private fun UpdateScreen(vm: UpdatesViewModel, activity: AppUpdatesActivity) {
    val prefs = remember { UpdateScheduler.preferences(activity) }
    val state by vm.state.collectAsState()
    var automatic by remember { mutableStateOf(prefs.getBoolean("automatic", true)) }
    var notifications by remember { mutableStateOf(prefs.getBoolean("notifications", true)) }
    var beta by remember { mutableStateOf(prefs.getBoolean("beta", false)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) vm.message("Notifications are disabled by Android. You can still check here.")
    }
    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.message(if (activity.packageManager.canRequestPackageInstalls()) "Install permission granted. Tap Install update to continue."
            else "Install permission was not granted. Your current app is unchanged.")
    }
    LaunchedEffect(Unit) { if (state.release == null && !state.busy) vm.check(beta) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = { activity.finish() }) { Text("‹ Settings") }
        Text("App Updates", style = MaterialTheme.typography.headlineMedium)
        Text("GlucoTrail")
        Text("Current version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Text("Latest version: ${state.release?.let { "${it.version} (${it.code})" } ?: "Not available"}")
        Toggle("Automatically check for updates", automatic, !state.busy) {
            automatic = it
            prefs.edit().putBoolean("automatic", it).apply()
            UpdateScheduler.schedule(activity)
        }
        Toggle("Notify me when an update is available", notifications, !state.busy) {
            notifications = it
            prefs.edit().putBoolean("notifications", it).apply()
            if (it && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        TextButton(onClick = {
            activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
        }) { Text("Android notification settings") }
        Toggle("Include beta releases", beta, !state.busy) {
            beta = it
            prefs.edit().putBoolean("beta", it).apply()
            vm.check(it)
        }
        Text("Automatic checks run about once a day when Android permits. Downloads only start when you choose.")
        val checked = prefs.getLong("checked", 0)
        Text("Last successful check: " + if (checked == 0L) "Never" else DateFormat.getDateTimeInstance().format(Date(checked)))
        prefs.getString("error", null)?.let { Text("Last background check: $it") }
        Button(onClick = { vm.check(beta) }, enabled = !state.busy) { Text("Check for updates") }
        Text(state.message)
        state.progress?.let {
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
            Text("${(it * 100).toInt()}% downloaded")
            TextButton(onClick = vm::cancel) { Text("Cancel download") }
        }
        state.release?.let { release ->
            Text("Release notes", style = MaterialTheme.typography.titleLarge)
            Text(release.notes.ifBlank { "No release notes provided." })
            TextButton(onClick = { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.page))) }) { Text("View GitHub release") }
            if (release.code > BuildConfig.VERSION_CODE) {
                Button(onClick = vm::download, enabled = !state.busy) { Text(if (state.ready == null) "Download update" else "Download again") }
            }
        }
        if (state.ready != null) {
            Button(enabled = !state.busy, onClick = {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                } else vm.install(activity)
            }) { Text("Install update") }
        }
        Text("Android asks you to confirm installation. Updating with the same signing key keeps your app data. Never uninstall to resolve a signing mismatch.")
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, enabled: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = changed, enabled = enabled)
    }
}
