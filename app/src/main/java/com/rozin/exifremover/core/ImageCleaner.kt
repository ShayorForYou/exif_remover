package com.rozin.exifremover.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

sealed interface CleanResult {
    val source: Uri

    data class Cleaned(
        override val source: Uri,
        val file: File,
        val mimeType: String,
        val originalBytes: Long,
        val hadLocation: Boolean,
        /** True when the format had no lossless path (e.g. HEIC) and was converted to JPEG. */
        val reencoded: Boolean,
    ) : CleanResult

    data class Failed(override val source: Uri, val reason: String) : CleanResult
}

/** What a single photo carries, for the pick-what-to-remove screen. */
data class Inspection(
    val source: Uri,
    val fields: List<ExifField>,
    /** False for formats ExifInterface can't rewrite (HEIC/AVIF): only "remove all" is possible. */
    val selectable: Boolean,
)

class ImageCleaner(private val context: Context) {

    private val outputDir = File(context.cacheDir, "cleaned")

    suspend fun clean(
        uris: List<Uri>,
        keepOrientation: Boolean,
        onProgress: (done: Int) -> Unit,
    ): List<CleanResult> = withContext(Dispatchers.IO) {
        pruneOldOutputs()
        val done = AtomicInteger()
        // Bounded so a 100-photo batch doesn't hold 100 full-size files in memory at once.
        val permits = Semaphore(PARALLELISM)
        coroutineScope {
            uris.map { uri ->
                async {
                    permits.withPermit { cleanOne(uri, keepOrientation) }
                        .also { onProgress(done.incrementAndGet()) }
                }
            }.awaitAll()
        }
    }

    suspend fun inspect(uri: Uri): Inspection = withContext(Dispatchers.IO) {
        val bytes = readBytes(uri)
        val fields = runCatching { ExifCatalog.read(ExifInterface(ByteArrayInputStream(bytes))) }.getOrDefault(emptyList())
        Inspection(uri, fields, selectable = MetadataStripper.detect(bytes) != null)
    }

    /** Removes only [tags], leaving everything else (and the pixels) untouched. */
    suspend fun removeTags(uri: Uri, tags: Set<String>): CleanResult = withContext(Dispatchers.IO) {
        try {
            val bytes = readBytes(uri)
            val format = MetadataStripper.detect(bytes)
                ?: throw IllegalStateException("This format can only be cleaned completely")
            val out = newOutputFile(format.extension)
            out.writeBytes(bytes)
            ExifInterface(out).apply {
                tags.forEach { setAttribute(it, null) }
                saveAttributes()
            }
            val removedLocation = ExifInterface.TAG_GPS_LATITUDE in tags
            CleanResult.Cleaned(uri, out, format.mimeType, bytes.size.toLong(), removedLocation, reencoded = false)
        } catch (e: Exception) {
            CleanResult.Failed(uri, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun readBytes(uri: Uri): ByteArray =
        (openOriginal(uri) ?: context.contentResolver.openInputStream(uri))?.use { it.readBytes() }
            ?: throw IllegalStateException("Couldn't open image")

    /**
     * Android blanks out GPS values unless we hold ACCESS_MEDIA_LOCATION and ask for the original.
     * Reading the unredacted bytes matters when replacing in place with some fields kept.
     */
    private fun openOriginal(uri: Uri): InputStream? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val media = context.mediaStoreUri(uri) ?: return null
        return runCatching { context.contentResolver.openInputStream(MediaStore.setRequireOriginal(media)) }.getOrNull()
    }

    private fun cleanOne(uri: Uri, keepOrientation: Boolean): CleanResult = try {
        val bytes = readBytes(uri)
        val format = MetadataStripper.detect(bytes)
        (format?.let { stripLossless(uri, bytes, it, keepOrientation) }) ?: reencode(uri, bytes)
    } catch (e: Exception) {
        CleanResult.Failed(uri, e.message ?: e.javaClass.simpleName)
    }

    private fun stripLossless(uri: Uri, bytes: ByteArray, format: ImageFormat, keepOrientation: Boolean): CleanResult? {
        val out = newOutputFile(format.extension)
        return try {
            val report = out.outputStream().buffered(BUFFER_SIZE).use {
                MetadataStripper.strip(bytes, format, it, keepOrientation)
            }
            CleanResult.Cleaned(uri, out, format.mimeType, bytes.size.toLong(), report.hadLocation, reencoded = false)
        } catch (e: MalformedImageException) {
            out.delete()
            null // let the decoder have a go; it's more forgiving of odd files
        }
    }

    /** Fallback for HEIC/AVIF/etc: decode pixels and write a fresh JPEG, which carries no metadata. */
    private fun reencode(uri: Uri, bytes: ByteArray): CleanResult {
        val hadLocation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).latLong != null }.getOrDefault(false)
        // ImageDecoder applies the EXIF rotation to the pixels, so the output is upright without the tag.
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val out = newOutputFile(ImageFormat.JPEG.extension)
        try {
            out.outputStream().buffered(BUFFER_SIZE).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } finally {
            bitmap.recycle()
        }
        return CleanResult.Cleaned(uri, out, ImageFormat.JPEG.mimeType, bytes.size.toLong(), hadLocation, reencoded = true)
    }

    // Random names: the original filename often embeds the capture date and time.
    private fun newOutputFile(extension: String): File {
        outputDir.mkdirs()
        return File(outputDir, "img_${UUID.randomUUID().toString().take(8)}.$extension")
    }

    // Outputs must outlive this call (the receiving app reads them later), so clear them lazily.
    private fun pruneOldOutputs() {
        val cutoff = System.currentTimeMillis() - OUTPUT_TTL_MS
        outputDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    private companion object {
        const val PARALLELISM = 4
        const val BUFFER_SIZE = 64 * 1024
        const val JPEG_QUALITY = 95
        const val OUTPUT_TTL_MS = 24 * 60 * 60 * 1000L
    }
}
