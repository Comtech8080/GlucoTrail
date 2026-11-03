package com.aidexx.reader.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.aidexx.reader.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AppRelease(val code: Long, val version: String, val notes: String, val page: String,
                      val apk: String, val checksum: String, val filename: String, val size: Long)

class GitHubUpdates(private val context: Context) {
    companion object { private val downloadLock = Mutex() }
    private val api = "https://api.github.com/repos/${UpdatePolicy.REPO}/releases"

    // Bounded HTTPS redirects: never follow release metadata to an arbitrary host or plaintext URL.
    private fun connection(address: String): HttpURLConnection {
        var url = URL(address)
        repeat(6) {
            require(url.protocol == "https" && url.port == -1 && url.userInfo == null &&
                url.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) {
                "Untrusted download address"
            }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "GlucoTrail/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", if (url.host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
            }
            val status = connection.responseCode
            if (status in setOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(!location.isNullOrEmpty()) { "Missing download redirect" }
                url = URL(url, location)
            } else {
                if (status != 200) {
                    connection.disconnect()
                    error(if (status == 403 || status == 429) "GitHub rate limit reached. Try again later."
                        else "GitHub request failed (HTTP $status)")
                }
                return connection
            }
        }
        error("Too many download redirects")
    }

    private suspend fun read(address: String, output: OutputStream, limit: Long,
                             progress: (Long) -> Unit = {}): Long {
        val connection = connection(address)
        try {
            require(connection.contentLengthLong <= limit) { "Download exceeds size limit" }
            var total = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= limit) { "Download exceeds size limit" }
                    output.write(buffer, 0, count)
                    currentCoroutineContext().ensureActive()
                    progress(total)
                }
            }
            return total
        } finally { connection.disconnect() }
    }

    private suspend fun text(address: String, limit: Long = 1024 * 1024): String {
        val out = ByteArrayOutputStream()
        read(address, out, limit)
        return out.toString("UTF-8")
    }

    suspend fun latest(beta: Boolean): AppRelease? = withContext(Dispatchers.IO) {
        val found = mutableListOf<AppRelease>()
        // A bounded scan of recent releases; select by Android versionCode, never lexicographic tags.
        val releases = JSONArray(text("$api?per_page=100", 8 * 1024 * 1024))
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft") || (!beta && release.optBoolean("prerelease"))) continue
            val assets = release.getJSONArray("assets")
            val byName = (0 until assets.length()).map { assets.getJSONObject(it) }.associateBy { it.getString("name") }
            val manifest = byName["update.json"] ?: continue
            val manifestUrl = manifest.getString("browser_download_url")
            require(UpdatePolicy.assetUrl(manifestUrl)) { "Invalid update manifest URL" }
            val metadata = JSONObject(text(manifestUrl, 16 * 1024))
            require(metadata.getString("applicationId") == context.packageName) { "Release targets a different app" }
            val filename = metadata.getString("apk")
            require(Regex("GlucoTrail-[A-Za-z0-9._-]+\\.apk").matches(filename)) { "Invalid APK name" }
            val apk = byName[filename] ?: error("Release is missing its APK")
            val sha = byName["$filename.sha256"] ?: error("Release is missing its SHA-256 file")
            val apkUrl = apk.getString("browser_download_url")
            val shaUrl = sha.getString("browser_download_url")
            require(UpdatePolicy.assetUrl(apkUrl) && UpdatePolicy.assetUrl(shaUrl)) { "Invalid release asset URL" }
            val size = apk.getLong("size")
            require(size in 1..UpdatePolicy.MAX_APK) { "Invalid APK size" }
            val code = metadata.getLong("versionCode")
            require(code > 0) { "Invalid release version" }
            val tag = release.getString("tag_name")
            val page = "https://github.com/${UpdatePolicy.REPO}/releases/tag/" + android.net.Uri.encode(tag)
            found += AppRelease(code, metadata.getString("versionName"), release.optString("body"), page,
                apkUrl, shaUrl, filename, size)
        }
        found.maxByOrNull { it.code }
    }

    suspend fun download(release: AppRelease, progress: (Float) -> Unit): File = downloadLock.withLock { withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File.createTempFile("download-", ".part", directory)
        val ready = File(directory, partial.name.removeSuffix(".part") + ".apk")
        try {
            val expected = UpdatePolicy.checksum(text(release.checksum, 1024), release.filename)
            val count = partial.outputStream().use { out ->
                read(release.apk, out, release.size) { progress(it.toFloat() / release.size) }
            }
            require(count == release.size) { "Incomplete APK download" }
            verify(partial, release, expected)
            require(partial.renameTo(ready)) { "Could not save verified APK" }
            // Cache the digest only for revalidation within this download session.
            expectedDigest = expected
            ready
        } catch (failure: Throwable) {
            partial.delete()
            ready.delete()
            throw failure
        }
    } }

    private var expectedDigest: String? = null

    suspend fun verifyBeforeInstall(file: File, release: AppRelease) = withContext(Dispatchers.IO) {
        verify(file, release, expectedDigest ?: error("Please download the update again"))
    }

    @Suppress("DEPRECATION")
    private fun verify(file: File, release: AppRelease, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().hex() == expected) { "SHA-256 mismatch. The APK will not be installed." }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val candidate = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("Invalid or unsigned APK")
        fun code(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        fun signers(info: PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
        }
        UpdatePolicy.validateIdentity(context.packageName, candidate.packageName, code(installed), release.code,
            code(candidate), signers(installed), signers(candidate))
        require(candidate.versionName == release.version) { "APK version name differs from release metadata" }
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
