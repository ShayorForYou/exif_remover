package com.rozin.exifremover.core

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Which library photos carry a GPS position. MediaStore stopped indexing that in Android 10, so it's read from each
 * file's EXIF header, once: answers are kept on disk by row ID and generation, so later visits only read photos that
 * are new or changed (e.g. just cleaned in place). Needs media location access, without which GPS reads as zero.
 */
class LocationIndex(context: Context) {

    private val app = context.applicationContext
    private val file = File(app.noBackupFilesDir, "location_index.bin")

    /** Row ID → generation shl 1 or 1 if it has a location. Only touched while holding [lock]. */
    private val known = HashMap<Long, Long>()
    private var loaded = false
    private val lock = Mutex()

    /**
     * Reports which of [photos] have a location: first everything already known, in one go, then fresh answers in
     * batches as files are read. Cancellable at any point; what was learnt so far is saved either way.
     */
    suspend fun scan(photos: List<LibraryPhoto>, onBatch: (located: List<Uri>, checked: Int) -> Unit) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (!loaded) {
                    load()
                    loaded = true
                }
                val located = ArrayList<Uri>()
                val unknown = ArrayList<LibraryPhoto>()
                for (photo in photos) {
                    val entry = known[photo.id]
                    when {
                        entry == null || entry ushr 1 != photo.generation -> unknown += photo
                        entry and 1L == 1L -> located += photo.uri
                    }
                }
                var checked = photos.size - unknown.size
                onBatch(located, checked)
                try {
                    for (batch in unknown.chunked(BATCH)) {
                        val answers = coroutineScope {
                            batch.map { photo -> async(probeDispatcher) { photo to hasLocation(photo.uri) } }.awaitAll()
                        }
                        val found = ArrayList<Uri>()
                        for ((photo, has) in answers) {
                            if (has == null) continue // unreadable right now; asked again next time
                            known[photo.id] = (photo.generation shl 1) or if (has) 1L else 0L
                            if (has) found += photo.uri
                        }
                        checked += batch.size
                        onBatch(found, checked)
                    }
                } finally {
                    withContext(NonCancellable) { save(photos) }
                }
            }
        }

    /** Null when the file can't be read. Only EXIF is parsed; for JPEG that's the first few KB of the file. */
    private fun hasLocation(uri: Uri): Boolean? = runCatching {
        app.contentResolver.openFileDescriptor(MediaStore.setRequireOriginal(uri), "r")?.use { fd ->
            ExifInterface(fd.fileDescriptor).latLong?.let { (lat, lng) -> lat != 0.0 || lng != 0.0 } ?: false
        }
    }.getOrNull()

    private fun load() = runCatching {
        if (!file.exists()) return@runCatching
        DataInputStream(file.inputStream().buffered()).use { input ->
            repeat(input.readInt()) { known[input.readLong()] = input.readLong() }
        }
    }.onFailure { known.clear() }

    /** Writes what's known about [photos] only, so deleted photos drop out instead of piling up. */
    private fun save(photos: List<LibraryPhoto>) = runCatching {
        val entries = photos.mapNotNull { photo -> known[photo.id]?.let { photo.id to it } }
        val tmp = File(file.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(entries.size)
            for ((id, value) in entries) {
                out.writeLong(id)
                out.writeLong(value)
            }
        }
        tmp.renameTo(file)
    }

    private companion object {
        /** Files read at once. Header reads are I/O-bound, but HEIF goes through the platform's media parser. */
        val probeDispatcher = Dispatchers.IO.limitedParallelism(3)

        /** Answers per UI update: frequent enough to feel live, rare enough not to churn the gallery state. */
        const val BATCH = 48
    }
}
