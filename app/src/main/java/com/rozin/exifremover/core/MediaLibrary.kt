package com.rozin.exifremover.core

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore

/**
 * A photo on the device. [uri] is a real MediaStore row, so it can be edited in place after consent.
 * [generation] changes whenever the file does, which is what keeps cached facts about it honest.
 */
data class LibraryPhoto(val uri: Uri, val id: Long, val generation: Long, val albumId: String, val albumName: String)

data class Album(val id: String, val name: String, val count: Int)

enum class LibraryAccess {
    FULL,

    /** Android 14+ "Allow limited access": only the photos the user chose are visible. */
    PARTIAL,
    NONE,
}

object MediaLibrary {

    private val fullPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    /** What to request. Media location rides along so the details screen can show real coordinates. */
    fun permissions(): Array<String> = buildList {
        add(fullPermission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }.toTypedArray()

    fun access(context: Context): LibraryAccess = when {
        context.granted(fullPermission) -> LibraryAccess.FULL
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            context.granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> LibraryAccess.PARTIAL
        else -> LibraryAccess.NONE
    }

    /** Without this, Android zeroes the GPS values in every photo we read. */
    fun hasLocationAccess(context: Context): Boolean = context.granted(Manifest.permission.ACCESS_MEDIA_LOCATION)

    /** Every visible photo, newest first. Only IDs and album names are read, so even large libraries load quickly. */
    fun load(context: Context): List<LibraryPhoto> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.VOLUME_NAME,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.GENERATION_MODIFIED,
        )
        val photos = ArrayList<LibraryPhoto>()
        context.contentResolver.query(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            projection, null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val volume = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.VOLUME_NAME)
            val bucket = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketName = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val generation = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.GENERATION_MODIFIED)
            while (cursor.moveToNext()) {
                val collection = MediaStore.Images.Media.getContentUri(cursor.getString(volume))
                val rowId = cursor.getLong(id)
                photos += LibraryPhoto(
                    uri = ContentUris.withAppendedId(collection, rowId),
                    id = rowId,
                    generation = cursor.getLong(generation),
                    albumId = cursor.getString(bucket).orEmpty(),
                    albumName = cursor.getString(bucketName) ?: "Other",
                )
            }
        }
        return photos
    }

    /**
     * The newest visible photo, or with [screenshots] the newest screenshot. One single-row query, so it's quick
     * enough for a launcher shortcut. Null without photo access or when there's none.
     */
    fun latest(context: Context, screenshots: Boolean): Uri? {
        val selection = if (screenshots) "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" else null
        val args = if (screenshots) arrayOf("%Screenshots%") else null
        val query = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.VOLUME_NAME)
        return runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, query, null,
            )?.use { c ->
                if (!c.moveToFirst()) null
                else ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(c.getString(1)), c.getLong(0))
            }
        }.getOrNull()
    }

    /** Albums by size, biggest (usually Camera) first. */
    fun albums(photos: List<LibraryPhoto>): List<Album> =
        photos.groupBy { it.albumId }
            .map { (id, items) -> Album(id, items.first().albumName, items.size) }
            .sortedByDescending { it.count }

    private fun Context.granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
