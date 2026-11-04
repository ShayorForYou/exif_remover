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

data class SaveOutcome(
    val replaced: Int,
    val copied: Int,
    val failed: Int,
    /** Originals went to the system Trash (restorable for 30 days) rather than being overwritten. */
    val trashed: Boolean = false,
    /** One of the saved photos, for opening it in a gallery app. */
    val first: Uri? = null,
)

/** A cleaned photo already written next to its original, hidden (pending) until the original is in the Trash. */
class StagedCopy(val image: CleanResult.Cleaned, val original: Uri, val copy: Uri, val name: String)

/**
 * How a batch will be saved. Everything slow (finding originals, writing staged copies) happens while building it,
 * off the main thread; [consent] is the one system dialog the user must accept before [execute] may run.
 */
class SavePlan(
    val copies: List<CleanResult.Cleaned>,
    val overwrite: Map<CleanResult.Cleaned, Uri> = emptyMap(),
    val staged: List<StagedCopy> = emptyList(),
    val consent: IntentSender? = null,
) {
    val trashing get() = staged.isNotEmpty()
}

/** Works out where each image goes. Blocking: call off the main thread. */
fun Context.planSave(images: List<CleanResult.Cleaned>, settings: Settings): SavePlan {
    if (settings.saveMode == SaveMode.COPY) return SavePlan(images)
    val targets = replaceTargets(images)
    if (targets.isEmpty()) return SavePlan(images)
    if (settings.trashOriginals) {
        val staged = stageCopies(targets)
        val request = staged.takeIf { it.isNotEmpty() }?.let { trashRequest(it.map(StagedCopy::original)) }
        if (request == null) {
            discardStaged(staged)
            return SavePlan(images)
        }
        val stagedImages = staged.mapTo(HashSet()) { it.image }
        return SavePlan(images.filterNot { it in stagedImages }, staged = staged, consent = request)
    }
    // No consent dialog: fall through and save everything as copies.
    val request = writeRequest(targets.values) ?: return SavePlan(images)
    return SavePlan(images.filterNot { it in targets }, overwrite = targets, consent = request)
}

/** Carries out [plan] once its consent (if any) was granted. Blocking: call off the main thread. */
fun Context.execute(plan: SavePlan): SaveOutcome {
    val overwritten = overwriteOriginals(plan.overwrite)
    val published = publishStaged(plan.staged)
    val copied = saveToGallery(plan.copies)
    val total = plan.copies.size + plan.overwrite.size + plan.staged.size
    val replaced = overwritten.size + published.size
    return SaveOutcome(
        replaced = replaced,
        copied = copied.size,
        failed = total - replaced - copied.size,
        trashed = published.isNotEmpty(),
        first = (published + overwritten + copied).firstOrNull(),
    )
}

/** Undoes the parts of [plan] written ahead of consent, after the user declined it. */
fun Context.abandon(plan: SavePlan) = discardStaged(plan.staged)

/** Copies cleaned images into Pictures/EXIF Remover. Returns the saved items. */
private fun Context.saveToGallery(images: List<CleanResult.Cleaned>): List<Uri> = images.mapNotNull { image ->
    insertImage(image, MediaStore.VOLUME_EXTERNAL_PRIMARY, "${Environment.DIRECTORY_PICTURES}/$GALLERY_FOLDER", publish = true)
}

/**
 * Writes [image] as a new MediaStore item. Unless [publish], it stays pending: invisible to other apps, and
 * removed by the system after about a week if never published, so an interrupted save leaves nothing behind.
 */
private fun Context.insertImage(image: CleanResult.Cleaned, volume: String, relativePath: String, publish: Boolean): Uri? {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, image.file.name)
        put(MediaStore.Images.Media.MIME_TYPE, image.mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = runCatching { contentResolver.insert(MediaStore.Images.Media.getContentUri(volume), values) }.getOrNull()
        ?: return null
    return runCatching {
        contentResolver.openOutputStream(uri)!!.use { out -> image.file.inputStream().use { it.copyTo(out) } }
        if (publish) contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        uri
    }.onFailure { contentResolver.delete(uri, null, null) }.getOrNull()
}

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
 * Originals that can be replaced. Images converted to JPEG (GIF, BMP…) can't: the format differs.
 * Nor can files Android doesn't index as photos (e.g. inside an app's private folder); those are saved as copies.
 */
private fun Context.replaceTargets(images: List<CleanResult.Cleaned>): Map<CleanResult.Cleaned, Uri> {
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
private fun Context.writeRequest(uris: Collection<Uri>): IntentSender? =
    runCatching { MediaStore.createWriteRequest(contentResolver, uris).intentSender }.getOrNull()

/** System "Move these photos to Trash?" dialog. Accepting it is what trashes them. */
private fun Context.trashRequest(uris: Collection<Uri>): IntentSender? =
    runCatching { MediaStore.createTrashRequest(contentResolver, uris, true).intentSender }.getOrNull()

/** Overwrites each original with its cleaned bytes (after the user granted [writeRequest]). */
private fun Context.overwriteOriginals(targets: Map<CleanResult.Cleaned, Uri>): List<Uri> =
    targets.mapNotNull { (image, uri) ->
        runCatching {
            // Source first: "wt" empties the original on open, so it must never be opened if there's nothing to copy.
            image.file.inputStream().use { input -> contentResolver.openOutputStream(uri, "wt")!!.use { input.copyTo(it) } }
            uri
        }.getOrNull()
    }

/**
 * Writes each cleaned image, still hidden, into its original's folder and volume. Originals whose copy can't be
 * written there (e.g. folders apps may not add photos to) are left out, so they're never trashed without one.
 */
private fun Context.stageCopies(targets: Map<CleanResult.Cleaned, Uri>): List<StagedCopy> {
    val places = originalPlaces(targets.values)
    return targets.mapNotNull { (image, original) ->
        val place = places[ContentUris.parseId(original)] ?: return@mapNotNull null
        val copy = insertImage(image, place.volume, place.relativePath, publish = false) ?: return@mapNotNull null
        StagedCopy(image, original, copy, place.name)
    }
}

/** Gives each staged copy its original's name (free now that the original is in the Trash) and makes it visible. */
private fun Context.publishStaged(staged: List<StagedCopy>): List<Uri> = staged.mapNotNull { item ->
    val visible = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
    val renamed = ContentValues(visible).apply { put(MediaStore.Images.Media.DISPLAY_NAME, item.name) }
    val ok = runCatching { contentResolver.update(item.copy, renamed, null, null) > 0 }.getOrDefault(false) ||
        runCatching { contentResolver.update(item.copy, visible, null, null) > 0 }.getOrDefault(false)
    item.copy.takeIf { ok }
}

private fun Context.discardStaged(staged: List<StagedCopy>) =
    staged.forEach { runCatching { contentResolver.delete(it.copy, null, null) } }

private class Place(val volume: String, val relativePath: String, val name: String)

/** Volume, folder and file name of each original, by row ID, in one query per few hundred rows. */
private fun Context.originalPlaces(originals: Collection<Uri>): Map<Long, Place> {
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.VOLUME_NAME,
        MediaStore.Images.Media.RELATIVE_PATH,
        MediaStore.Images.Media.DISPLAY_NAME,
    )
    val places = HashMap<Long, Place>()
    // Row IDs are unique across external volumes, so one collection covers SD cards too.
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    originals.map(ContentUris::parseId).chunked(QUERY_CHUNK).forEach { ids ->
        val selection = "${MediaStore.Images.Media._ID} IN (${ids.joinToString(",")})"
        runCatching {
            contentResolver.query(collection, projection, selection, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(2) ?: continue
                    val name = c.getString(3) ?: continue
                    places[c.getLong(0)] = Place(c.getString(1), path, name)
                }
            }
        }
    }
    return places
}

private const val QUERY_CHUNK = 400

/** Opens a saved photo in whichever gallery app the user prefers. */
fun Context.viewIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW)
    .setDataAndType(uri, contentResolver.getType(uri) ?: "image/*")
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
