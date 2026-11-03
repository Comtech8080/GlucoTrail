package com.aidexx.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

object NotePhotoStore {
    private const val MAX_EDGE_PX = 1280
    private const val TARGET_MAX_BYTES = 650 * 1024
    private val JPEG_QUALITIES = intArrayOf(78, 70, 62, 55)

    fun createCameraTempFile(context: Context): File {
        val dir = File(context.cacheDir, "note_camera").apply { mkdirs() }
        return File.createTempFile("note_", ".jpg", dir)
    }

    fun importOptimizedPhoto(
        context: Context,
        sourceUri: Uri
    ): String? {
        val bitmap = decodeDownsampled(context, sourceUri) ?: return null
        val encoded = encodeCompactJpeg(bitmap)
        if (!bitmap.isRecycled) bitmap.recycle()

        val dir = File(context.filesDir, "note_photos").apply { mkdirs() }
        val out = File(dir, "${UUID.randomUUID()}.jpg")
        FileOutputStream(out).use { it.write(encoded) }
        return out.absolutePath
    }

    fun deletePhoto(path: String) {
        if (path.isBlank()) return
        runCatching { File(path).delete() }
    }

    fun deletePhotos(paths: Collection<String>) {
        paths.forEach(::deletePhoto)
    }

    fun fileSizeBytes(path: String): Long =
        runCatching { File(path).length() }.getOrDefault(0L)

    private fun decodeDownsampled(
        context: Context,
        uri: Uri
    ): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val width = info.size.width.coerceAtLeast(1)
                    val height = info.size.height.coerceAtLeast(1)
                    val longest = max(width, height)
                    if (longest > MAX_EDGE_PX) {
                        val scale = MAX_EDGE_PX.toFloat() / longest.toFloat()
                        decoder.setTargetSize(
                            (width * scale).roundToInt().coerceAtLeast(1),
                            (height * scale).roundToInt().coerceAtLeast(1)
                        )
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            }.getOrNull()
        } else {
            decodeLegacy(context, uri)
        }
    }

    private fun decodeLegacy(
        context: Context,
        uri: Uri
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= MAX_EDGE_PX ||
            bounds.outHeight / (sample * 2) >= MAX_EDGE_PX
        ) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val longest = max(decoded.width, decoded.height)
        if (longest <= MAX_EDGE_PX) return decoded

        val scale = MAX_EDGE_PX.toFloat() / longest.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).roundToInt().coerceAtLeast(1),
            (decoded.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun encodeCompactJpeg(bitmap: Bitmap): ByteArray {
        var best = ByteArray(0)
        for (quality in JPEG_QUALITIES) {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            best = stream.toByteArray()
            if (best.size <= TARGET_MAX_BYTES) break
        }
        return best
    }
}
