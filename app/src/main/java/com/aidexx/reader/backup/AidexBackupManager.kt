package com.aidexx.reader.backup

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import com.aidexx.reader.BuildConfig
import com.aidexx.reader.alarm.AlarmMonitorService
import com.aidexx.reader.data.AidexHistoryDatabase
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream


data class BackupEntry(
    val uri: Uri,
    val name: String,
    val modifiedAtMs: Long,
    val sizeBytes: Long,
    val manual: Boolean
)

data class BackupSummary(
    val folderConfigured: Boolean,
    val autoEnabled: Boolean,
    val passphraseConfigured: Boolean,
    val lastBackupAtMs: Long,
    val lastBackupName: String?,
    val lastError: String?
)

object AidexBackupManager {
    private const val PREFS = "aidexx_backup"
    private const val KEY_TREE_URI = "drive_tree_uri"
    private const val KEY_AUTO_ENABLED = "auto_enabled"
    private const val KEY_LAST_BACKUP_AT = "last_backup_at"
    private const val KEY_LAST_BACKUP_NAME = "last_backup_name"
    private const val KEY_LAST_ERROR = "last_error"
    private const val AUTO_PREFIX = "AiDEX-X-Auto-"
    private const val MANUAL_PREFIX = "AiDEX-X-Manual-"
    private const val EXTENSION = ".axbackup"
    private const val KEEP_AUTO = 5
    private const val DATABASE_NAME = "aidexx_history.db"
    private const val APP_PREFS_FILE = "aidexx_reader.xml"
    private const val MIME = "application/octet-stream"

    fun summary(context: Context): BackupSummary {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return BackupSummary(
            folderConfigured = treeUri(context) != null,
            autoEnabled = prefs.getBoolean(KEY_AUTO_ENABLED, false),
            passphraseConfigured = BackupPassphraseStore.hasPassphrase(context),
            lastBackupAtMs = prefs.getLong(KEY_LAST_BACKUP_AT, 0L),
            lastBackupName = prefs.getString(KEY_LAST_BACKUP_NAME, null),
            lastError = prefs.getString(KEY_LAST_ERROR, null)
        )
    }

    fun setBackupFolder(
        context: Context,
        uri: Uri
    ) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TREE_URI, uri.toString())
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun clearBackupFolder(context: Context) {
        treeUri(context)?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_TREE_URI)
            .putBoolean(KEY_AUTO_ENABLED, false)
            .apply()
        AidexBackupScheduler.cancel(context)
    }

    fun setAutomaticBackupEnabled(
        context: Context,
        enabled: Boolean
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_ENABLED, enabled)
            .apply()
        if (enabled) {
            AidexBackupScheduler.schedule(context)
        } else {
            AidexBackupScheduler.cancel(context)
        }
    }

    fun automaticBackupEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_ENABLED, false)

    fun treeUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null)
            ?.let(Uri::parse)

    fun listBackups(context: Context): List<BackupEntry> {
        val tree = treeUri(context) ?: return emptyList()
        return listBackupsInTree(context, tree)
    }

    fun createBackup(
        context: Context,
        manual: Boolean
    ): Result<BackupEntry> = runCatching {
        val tree = treeUri(context)
            ?: error("Choose a Google Drive backup folder first.")
        val passphrase = BackupPassphraseStore.load(context)
            ?: error("Set a backup password first.")

        val cacheDir = File(context.cacheDir, "aidexx_backup").apply { mkdirs() }
        val plainZip = File(cacheDir, "backup-${System.nanoTime()}.zip")
        val encrypted = File(cacheDir, "backup-${System.nanoTime()}$EXTENSION")

        try {
            buildPlainArchive(context, plainZip)
            BackupCrypto.encryptFile(
                plainZip,
                encrypted,
                passphrase.toCharArray()
            )

            val stamp = SimpleDateFormat(
                "yyyyMMdd-HHmmss",
                Locale.US
            ).apply {
                timeZone = TimeZone.getDefault()
            }.format(Date())
            val name = (if (manual) MANUAL_PREFIX else AUTO_PREFIX) + stamp + EXTENSION
            val target = createDocument(context, tree, name)
                ?: error("Google Drive did not create the backup file.")

            context.contentResolver.openOutputStream(target, "wt").use { out ->
                requireNotNull(out) { "Google Drive backup file could not be opened for writing." }
                BufferedInputStream(FileInputStream(encrypted)).use { input ->
                    input.copyTo(out)
                }
            }

            if (!manual) {
                pruneAutomaticBackups(context, tree)
            }

            val now = System.currentTimeMillis()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_BACKUP_AT, now)
                .putString(KEY_LAST_BACKUP_NAME, name)
                .remove(KEY_LAST_ERROR)
                .apply()

            BackupEntry(
                uri = target,
                name = name,
                modifiedAtMs = now,
                sizeBytes = encrypted.length(),
                manual = manual
            )
        } finally {
            plainZip.delete()
            encrypted.delete()
        }
    }.onFailure { error ->
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_ERROR, error.message ?: error.javaClass.simpleName)
            .apply()
    }

    fun stageRestoreFromUri(
        context: Context,
        uri: Uri,
        passphrase: String
    ): Result<Unit> = runCatching {
        require(passphrase.length >= 8) {
            "Enter the backup password used when this backup was created."
        }

        val cacheDir = File(context.cacheDir, "aidexx_restore").apply {
            deleteRecursively()
            mkdirs()
        }
        val encrypted = File(cacheDir, "restore$EXTENSION")
        val plainZip = File(cacheDir, "restore.zip")

        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "The selected backup could not be opened." }
            BufferedOutputStream(FileOutputStream(encrypted)).use { out ->
                input.copyTo(out)
            }
        }

        try {
            BackupCrypto.decryptFile(
                encrypted,
                plainZip,
                passphrase.toCharArray()
            )
        } catch (error: Throwable) {
            throw IllegalArgumentException(
                "Backup could not be decrypted. Check the backup password.",
                error
            )
        }

        validateArchive(plainZip)

        val pending = File(context.filesDir, "restore_pending")
        pending.deleteRecursively()
        pending.mkdirs()
        extractArchive(plainZip, pending)
        File(pending, "restore_pending.ready").writeText("ready\n")

        BackupPassphraseStore.save(context, passphrase)
    }

    /**
     * Runs at app startup before the ViewModel opens SQLite. Restored data is
     * applied atomically enough for this single-process research build.
     */
    fun applyPendingRestore(context: Context): Boolean {
        val pending = File(context.filesDir, "restore_pending")
        val marker = File(pending, "restore_pending.ready")
        if (!marker.exists()) return false

        return runCatching {
            context.stopService(Intent(context, AlarmMonitorService::class.java))

            val stagedDb = File(pending, "database/$DATABASE_NAME")
            require(stagedDb.exists()) { "Pending restore database is missing." }

            val targetDb = context.getDatabasePath(DATABASE_NAME)
            targetDb.parentFile?.mkdirs()
            File(targetDb.absolutePath + "-wal").delete()
            File(targetDb.absolutePath + "-shm").delete()
            targetDb.delete()
            stagedDb.copyTo(targetDb, overwrite = true)

            val stagedPrefs = File(pending, "shared_prefs/$APP_PREFS_FILE")
            if (stagedPrefs.exists()) {
                val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs").apply { mkdirs() }
                stagedPrefs.copyTo(
                    File(prefsDir, APP_PREFS_FILE),
                    overwrite = true
                )
            }

            val targetPhotos = File(context.filesDir, "note_photos")
            targetPhotos.deleteRecursively()
            val stagedPhotos = File(pending, "note_photos")
            if (stagedPhotos.exists()) {
                copyDirectory(stagedPhotos, targetPhotos)
            } else {
                targetPhotos.mkdirs()
            }

            normalizeNotePhotoPaths(targetDb, targetPhotos)

            pending.deleteRecursively()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong("last_restore_at", System.currentTimeMillis())
                .remove(KEY_LAST_ERROR)
                .apply()
            true
        }.getOrElse { error ->
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_ERROR, "Restore failed: ${error.message ?: error.javaClass.simpleName}")
                .apply()
            false
        }
    }

    private fun buildPlainArchive(
        context: Context,
        output: File
    ) {
        checkpointDatabase(context)
        val database = context.getDatabasePath(DATABASE_NAME)
        require(database.exists()) { "GlucoTrail history database is not available." }

        ZipOutputStream(
            BufferedOutputStream(FileOutputStream(output))
        ).use { zip ->
            val manifest = """
                {
                  "format": 1,
                  "application": "AiDEX X",
                  "appVersion": "${jsonEscape(BuildConfig.VERSION_NAME)}",
                  "createdAtMs": ${System.currentTimeMillis()},
                  "database": "$DATABASE_NAME",
                  "encrypted": true
                }
            """.trimIndent().toByteArray(Charsets.UTF_8)
            addBytes(zip, "manifest.json", manifest)
            addFile(zip, database, "database/$DATABASE_NAME")

            val prefsFile = File(
                context.applicationInfo.dataDir,
                "shared_prefs/$APP_PREFS_FILE"
            )
            if (prefsFile.exists()) {
                addFile(zip, prefsFile, "shared_prefs/$APP_PREFS_FILE")
            }

            val photos = File(context.filesDir, "note_photos")
            if (photos.exists()) {
                photos.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        addFile(
                            zip,
                            file,
                            "note_photos/${file.relativeTo(photos).invariantSeparatorsPath}"
                        )
                    }
            }
        }
    }

    private fun checkpointDatabase(context: Context) {
        val helper = AidexHistoryDatabase(context.applicationContext)
        try {
            helper.writableDatabase.rawQuery(
                "PRAGMA wal_checkpoint(FULL)",
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    // Query completion is sufficient. Values are diagnostic only.
                }
            }
        } finally {
            helper.close()
        }
    }

    private fun validateArchive(file: File) {
        ZipFile(file).use { zip ->
            require(zip.getEntry("manifest.json") != null) {
                "Backup manifest is missing."
            }
            require(zip.getEntry("database/$DATABASE_NAME") != null) {
                "Backup history database is missing."
            }
        }
    }

    private fun extractArchive(
        zipFile: File,
        destination: File
    ) {
        ZipInputStream(
            BufferedInputStream(FileInputStream(zipFile))
        ).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val out = File(destination, entry.name)
                val canonicalRoot = destination.canonicalPath + File.separator
                val canonicalOut = out.canonicalPath
                require(
                    canonicalOut == destination.canonicalPath ||
                        canonicalOut.startsWith(canonicalRoot)
                ) { "Unsafe backup entry rejected." }

                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(out)).use { output ->
                        input.copyTo(output)
                    }
                }
                input.closeEntry()
            }
        }
    }

    private fun normalizeNotePhotoPaths(
        database: File,
        targetPhotos: File
    ) {
        if (!database.exists()) return
        val db = SQLiteDatabase.openDatabase(
            database.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        try {
            val rows = ArrayList<Pair<Long, String>>()
            db.rawQuery(
                "SELECT id, photo_paths FROM glucose_notes WHERE photo_paths <> ''",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    rows += cursor.getLong(0) to cursor.getString(1).orEmpty()
                }
            }

            db.beginTransaction()
            try {
                rows.forEach { (id, pathsText) ->
                    val normalized = pathsText
                        .split("\n")
                        .map { path ->
                            val name = File(path.trim()).name
                            if (name.isBlank()) "" else File(targetPhotos, name).absolutePath
                        }
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                    val values = android.content.ContentValues().apply {
                        put("photo_paths", normalized)
                    }
                    db.update(
                        "glucose_notes",
                        values,
                        "id = ?",
                        arrayOf(id.toString())
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }
    }

    private fun listBackupsInTree(
        context: Context,
        tree: Uri
    ): List<BackupEntry> {
        val resolver = context.contentResolver
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val result = ArrayList<BackupEntry>()

        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val typeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getString(idIndex)
                val name = cursor.getString(nameIndex).orEmpty()
                val mime = if (typeIndex >= 0) cursor.getString(typeIndex).orEmpty() else ""
                if (!name.endsWith(EXTENSION, ignoreCase = true)) continue
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue

                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                result += BackupEntry(
                    uri = uri,
                    name = name,
                    modifiedAtMs = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                        cursor.getLong(modifiedIndex)
                    } else {
                        0L
                    },
                    sizeBytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        cursor.getLong(sizeIndex)
                    } else {
                        0L
                    },
                    manual = name.startsWith(MANUAL_PREFIX)
                )
            }
        }

        return result.sortedByDescending { it.modifiedAtMs }
    }

    private fun createDocument(
        context: Context,
        tree: Uri,
        name: String
    ): Uri? {
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        return DocumentsContract.createDocument(
            context.contentResolver,
            parent,
            MIME,
            name
        )
    }

    private fun pruneAutomaticBackups(
        context: Context,
        tree: Uri
    ) {
        listBackupsInTree(context, tree)
            .filter { !it.manual && it.name.startsWith(AUTO_PREFIX) }
            .sortedByDescending { it.modifiedAtMs }
            .drop(KEEP_AUTO)
            .forEach { entry ->
                runCatching {
                    DocumentsContract.deleteDocument(
                        context.contentResolver,
                        entry.uri
                    )
                }
            }
    }

    private fun addBytes(
        zip: ZipOutputStream,
        name: String,
        bytes: ByteArray
    ) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun addFile(
        zip: ZipOutputStream,
        file: File,
        name: String
    ) {
        zip.putNextEntry(ZipEntry(name))
        BufferedInputStream(FileInputStream(file)).use { input ->
            input.copyTo(zip)
        }
        zip.closeEntry()
    }

    private fun copyDirectory(
        source: File,
        target: File
    ) {
        source.walkTopDown().forEach { file ->
            val relative = file.relativeTo(source)
            val out = File(target, relative.path)
            if (file.isDirectory) {
                out.mkdirs()
            } else {
                out.parentFile?.mkdirs()
                file.copyTo(out, overwrite = true)
            }
        }
    }

    private fun jsonEscape(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"")
}
