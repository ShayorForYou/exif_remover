package com.rozin.exifremover.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

sealed interface CleanResult {
    val source: Uri

    data class Cleaned(
        override val source: Uri,
        val file: File,
        val mimeType: String,
        val originalBytes: Long,
        /** Metadata bytes removed. HEIF files keep their size (metadata is blanked in place), so it's tracked directly. */
        val removedBytes: Long,
        val hadLocation: Boolean,
        /** True when the format had no lossless path (e.g. GIF, BMP) and was converted to JPEG. */
        val reencoded: Boolean,
    ) : CleanResult

    data class Failed(override val source: Uri, val reason: String) : CleanResult
}

/** What a single photo carries, for the details screen. */
data class Inspection(val source: Uri, val fields: List<ExifField>)

/** One cleaned photo, before and after. */
data class Comparison(val removed: List<ExifField>, val kept: List<ExifField>)

class ImageCleaner(private val context: Context) {

    private val outputDir = File(context.cacheDir, "cleaned")

    suspend fun clean(
        uris: List<Uri>,
        options: StripOptions,
        onProgress: (done: Int) -> Unit,
    ): List<CleanResult> = withContext(Dispatchers.IO) {
        pruneOldOutputs()
        val done = AtomicInteger()
        // Bounded so a large batch doesn't hold every full-size file in memory at once.
        val permits = Semaphore(PARALLELISM)
        val written = ConcurrentLinkedQueue<File>()
        try {
            coroutineScope {
                uris.map { uri ->
                    async {
                        permits.withPermit { cleanOne(uri, options) }
                            .also { if (it is CleanResult.Cleaned) written += it.file }
                            .also { onProgress(done.incrementAndGet()) }
                    }
                }.awaitAll()
            }
        } catch (e: CancellationException) {
            // A cancelled batch is never shown, so nothing will read what it already wrote.
            written.forEach { it.delete() }
            throw e
        }
    }

    suspend fun inspect(uri: Uri): Inspection = withContext(Dispatchers.IO) {
        val bytes = readBytes(uri)
        val fields = runCatching { ExifCatalog.read(ExifInterface(ByteArrayInputStream(bytes))) }.getOrDefault(emptyList())
        Inspection(uri, fields)
    }

    /**
     * What cleaning took out of one photo, and what it left (the date or rotation it was asked to keep, or everything
     * but the location). Reads the original again, so it's only for the one photo the user taps on.
     */
    suspend fun compare(image: CleanResult.Cleaned): Comparison = withContext(Dispatchers.IO) {
        val before = inspect(image.source).fields
        val after = runCatching { ExifCatalog.read(ExifInterface(image.file)) }.getOrDefault(emptyList())
        val kept = after.mapTo(HashSet()) { it.id to it.value }
        Comparison(removed = before.filterNot { (it.id to it.value) in kept }, kept = after)
    }

    private fun readBytes(uri: Uri): ByteArray =
        (openOriginal(uri) ?: context.contentResolver.openInputStream(uri))?.use { it.readBytes() }
            ?: throw IllegalStateException("Couldn't open image")

    /**
     * Android blanks out GPS values unless we hold ACCESS_MEDIA_LOCATION and ask for the original.
     * Everything is stripped either way; the real values only matter for showing them on the details screen.
     */
    private fun openOriginal(uri: Uri): InputStream? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val media = context.mediaStoreUri(uri) ?: return null
        return runCatching { context.contentResolver.openInputStream(MediaStore.setRequireOriginal(media)) }.getOrNull()
    }

    private fun cleanOne(uri: Uri, options: StripOptions): CleanResult = try {
        val bytes = readBytes(uri)
        val format = MetadataStripper.detect(bytes)
        (format?.let { stripLossless(uri, bytes, it, options) }) ?: reencode(uri, bytes, options)
    } catch (e: Exception) {
        CleanResult.Failed(uri, e.message ?: e.javaClass.simpleName)
    }

    private fun stripLossless(uri: Uri, bytes: ByteArray, format: ImageFormat, options: StripOptions): CleanResult? {
        val out = newOutputFile(format.extension)
        return try {
            val report = out.outputStream().buffered(BUFFER_SIZE).use {
                MetadataStripper.strip(bytes, format, it, options)
            }
            CleanResult.Cleaned(
                uri, out, format.mimeType, bytes.size.toLong(), report.removedBytes.toLong(), report.hadLocation, reencoded = false,
            )
        } catch (e: MalformedImageException) {
            out.delete()
            null // let the decoder have a go; it's more forgiving of odd files
        }
    }

    /** Fallback for formats with no lossless path, or malformed files: decode pixels and write a fresh JPEG. */
    private fun reencode(uri: Uri, bytes: ByteArray, options: StripOptions): CleanResult {
        val source = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()
        val hadLocation = source?.latLong != null
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
        if (source != null) carryOver(source, out, options)
        val removed = (bytes.size - out.length()).coerceAtLeast(0)
        return CleanResult.Cleaned(uri, out, ImageFormat.JPEG.mimeType, bytes.size.toLong(), removed, hadLocation, reencoded = true)
    }

    /**
     * A fresh JPEG starts with no metadata at all; this writes back what [options] keeps. Rotation never needs to:
     * the decoder already applied it to the pixels.
     */
    private fun carryOver(source: ExifInterface, out: File, options: StripOptions) {
        val tags = when (options.level) {
            StripLevel.LOCATION -> DATE_TAGS + CAMERA_TAGS
            StripLevel.ALL -> if (options.keepDate) DATE_TAGS else return
        }
        val values = tags.mapNotNull { tag -> source.getAttribute(tag)?.let { tag to it } }
        if (values.isEmpty()) return
        runCatching {
            ExifInterface(out).apply {
                values.forEach { (tag, value) -> setAttribute(tag, value) }
                saveAttributes()
            }
        }
    }

    // Random names: the original filename often embeds the capture date and time.
    private fun newOutputFile(extension: String): File {
        outputDir.mkdirs()
        return File(outputDir, "img_${UUID.randomUUID().toString().take(8)}.$extension")
    }

    // Shared outputs must outlive this call (the receiving app reads them later), so those are cleared lazily.
    fun pruneOldOutputs() {
        val cutoff = System.currentTimeMillis() - OUTPUT_TTL_MS
        outputDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    /**
     * Deletes outputs nothing will read again, once [after] (e.g. a save copying them) has finished.
     * Runs outside any screen's scope, so leaving the screen doesn't cut it short.
     */
    fun discard(files: List<File>, after: Job? = null) {
        if (files.isEmpty()) return
        discardScope.launch {
            after?.join()
            files.forEach { it.delete() }
        }
    }

    private companion object {
        val discardScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        const val PARALLELISM = 4
        const val BUFFER_SIZE = 64 * 1024
        const val JPEG_QUALITY = 95
        const val OUTPUT_TTL_MS = 24 * 60 * 60 * 1000L

        val DATE_TAGS = listOf(
            ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        )
        val CAMERA_TAGS = listOf(
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        )
    }
}
