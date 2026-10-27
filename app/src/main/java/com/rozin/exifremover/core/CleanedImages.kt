package com.rozin.exifremover.core

import android.content.ClipData
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import com.rozin.exifremover.MainActivity
import com.rozin.exifremover.ShareActivity

/** Image URIs carried by an incoming ACTION_SEND / ACTION_SEND_MULTIPLE share; empty for anything else. */
fun Intent.sharedImageUris(): List<Uri> {
    val fromExtras = when (action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(this, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> return emptyList()
    }
    if (fromExtras.isNotEmpty()) return fromExtras
    val clip = clipData ?: return emptyList()
    return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
}

/** System share sheet for the cleaned copies, with our own share targets hidden. */
fun Context.shareIntent(images: List<CleanResult.Cleaned>): Intent {
    val uris = images.map { FileProvider.getUriForFile(this, "$packageName.files", it.file) }
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    send.type = images.map { it.mimeType }.distinct().singleOrNull() ?: "image/*"
    // ClipData is what carries the read grant through the chooser to the target app.
    send.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return Intent.createChooser(send, null)
        .putExtra(
            Intent.EXTRA_EXCLUDE_COMPONENTS,
            arrayOf(ComponentName(this, ShareActivity::class.java), ComponentName(this, MainActivity::class.java)),
        )
}

const val GALLERY_FOLDER = "EXIF Remover"

/** Copies cleaned images into Pictures/EXIF Remover. Returns how many were saved. */
fun Context.saveToGallery(images: List<CleanResult.Cleaned>): Int {
    val resolver = contentResolver
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    return images.count { image ->
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, image.file.name)
            put(MediaStore.Images.Media.MIME_TYPE, image.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$GALLERY_FOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return@count false
        runCatching {
            resolver.openOutputStream(uri)!!.use { out -> image.file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }.onFailure { resolver.delete(uri, null, null) }.isSuccess
    }
}

enum class SaveMode { COPY, REPLACE }

data class SaveOutcome(val replaced: Int, val copied: Int, val failed: Int)

/**
 * The MediaStore row behind a picked image, which is what we need to overwrite it.
 * Photo-picker URIs are read-only copies with no such row, so they map to null.
 */
fun Context.mediaStoreUri(source: Uri): Uri? {
    val uri = when {
        source.authority == MediaStore.AUTHORITY ->
            source.takeUnless { it.pathSegments.firstOrNull().orEmpty().startsWith("picker") }
        DocumentsContract.isDocumentUri(this, source) -> runCatching { MediaStore.getMediaUri(this, source) }.getOrNull()
        else -> null
    } ?: return null
    return asImageItem(uri)
}

/**
 * Document lookups often return a generic Files URI (`/<volume>/file/<id>`), which [MediaStore.createWriteRequest]
 * rejects; it only takes Images/Video/Audio items. The same row ID is valid in the Images collection.
 */
private fun asImageItem(uri: Uri): Uri {
    val segments = uri.pathSegments
    val id = segments.getOrNull(2)?.toLongOrNull()
    if (segments.size != 3 || segments[1] != "file" || id == null) return uri
    return ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(segments[0]), id)
}

/**
 * Originals that can be overwritten in place. Images converted to JPEG (GIF, BMP…) can't: the format differs.
 * Nor can files Android doesn't index as photos (e.g. inside an app's private folder); those are saved as copies.
 */
fun Context.replaceTargets(images: List<CleanResult.Cleaned>): Map<CleanResult.Cleaned, Uri> {
    val candidates = images.filterNot { it.reencoded }
        .mapNotNull { image -> mediaStoreUri(image.source)?.let { image to it } }
        .toMap()
    if (candidates.isEmpty() || canRequestWrite(candidates.values)) return candidates
    // A single ineligible item fails the whole batch request, so check them one by one.
    return candidates.filterValues { canRequestWrite(listOf(it)) }
}

private fun Context.canRequestWrite(uris: Collection<Uri>) =
    runCatching { MediaStore.createWriteRequest(contentResolver, uris) }.isSuccess

/** System "Allow EXIF Remover to modify these photos?" dialog, or null if Android refuses to build one. */
fun Context.writeRequest(uris: Collection<Uri>): IntentSender? =
    runCatching { MediaStore.createWriteRequest(contentResolver, uris).intentSender }.getOrNull()

/** Overwrites each original with its cleaned bytes (after the user granted [writeRequest]). */
fun Context.overwriteOriginals(targets: Map<CleanResult.Cleaned, Uri>): Int = targets.count { (image, uri) ->
    runCatching {
        contentResolver.openOutputStream(uri, "wt")!!.use { out -> image.file.inputStream().use { it.copyTo(out) } }
    }.isSuccess
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

object Prefs {
    private const val KEY_KEEP_ORIENTATION = "keep_orientation"
    private const val KEY_SAVE_MODE = "save_mode"
    private const val KEY_THEME_MODE = "theme_mode"

    private fun Context.prefs() = getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun keepOrientation(context: Context) = context.prefs().getBoolean(KEY_KEEP_ORIENTATION, true)

    fun setKeepOrientation(context: Context, keep: Boolean) =
        context.prefs().edit { putBoolean(KEY_KEEP_ORIENTATION, keep) }

    fun saveMode(context: Context): SaveMode =
        runCatching { SaveMode.valueOf(context.prefs().getString(KEY_SAVE_MODE, null)!!) }.getOrDefault(SaveMode.COPY)

    fun setSaveMode(context: Context, mode: SaveMode) = context.prefs().edit { putString(KEY_SAVE_MODE, mode.name) }

    fun themeMode(context: Context): ThemeMode =
        runCatching { ThemeMode.valueOf(context.prefs().getString(KEY_THEME_MODE, null)!!) }.getOrDefault(ThemeMode.SYSTEM)

    fun setThemeMode(context: Context, mode: ThemeMode) = context.prefs().edit { putString(KEY_THEME_MODE, mode.name) }
}
