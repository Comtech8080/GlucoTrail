package com.aidexx.reader.updates

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aidexx.reader.BuildConfig
import kotlinx.coroutines.*

object UpdateScheduler {
    private const val JOB = 7319
    fun preferences(context: Context) = context.getSharedPreferences("github_updates", Context.MODE_PRIVATE)
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!preferences(context).getBoolean("automatic", true)) {
            scheduler.cancel(JOB)
            return
        }
        if (scheduler.getPendingJob(JOB) == null) {
            scheduler.schedule(JobInfo.Builder(JOB, ComponentName(context, UpdateCheckService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(24 * 60 * 60 * 1000L)
                .setBackoffCriteria(60 * 60 * 1000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true).build())
        }
    }

    fun notify(context: Context, release: AppRelease) {
        val prefs = preferences(context)
        if (!prefs.getBoolean("automatic", true) || !prefs.getBoolean("notifications", true) ||
            prefs.getLong("notified", 0) == release.code) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("app_updates", "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel("app_updates").importance == NotificationManager.IMPORTANCE_NONE) return
        val pending = PendingIntent.getActivity(context, 7319, Intent(context, AppUpdatesActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "app_updates")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("GlucoTrail update available")
            .setContentText("Version ${release.version} — tap to review")
            .setContentIntent(pending).setAutoCancel(true).build()
        manager.notify(7319, notification)
        prefs.edit().putLong("notified", release.code).apply()
    }
}

class UpdateCheckService : JobService() {
    private var job: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        val prefs = UpdateScheduler.preferences(this)
        if (!prefs.getBoolean("automatic", true)) return false
        job = CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            var retry = false
            try {
                val beta = prefs.getBoolean("beta", false)
                val release = GitHubUpdates(applicationContext).latest(beta)
                prefs.edit().putLong("checked", System.currentTimeMillis()).remove("error").apply()
                // Respect settings that changed while the request was running.
                if (release != null && release.code > BuildConfig.VERSION_CODE && beta == prefs.getBoolean("beta", false)) {
                    UpdateScheduler.notify(this@UpdateCheckService, release)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                prefs.edit().putString("error", failure.message ?: "Update check failed").apply()
                retry = true
            } finally {
                if (isActive) jobFinished(params, retry)
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return UpdateScheduler.preferences(this).getBoolean("automatic", true)
    }
    override fun onDestroy() { job?.cancel(); super.onDestroy() }
}
