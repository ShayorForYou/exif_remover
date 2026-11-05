package com.rozin.exifremover.core

import android.net.Uri
import androidx.core.content.FileProvider

/**
 * Serves cleaned copies. A URI carrying the "as file" marker reports a generic type instead of image/…, so chat
 * apps such as WhatsApp send it as a document (original quality, no re-compression) rather than as a photo.
 * The marker only changes the reported type: the file itself is resolved from the path as usual.
 */
class CleanedFileProvider : FileProvider() {

    override fun getType(uri: Uri): String? = if (uri.isAsFile()) GENERIC_TYPE else super.getType(uri)

    override fun getTypeAnonymous(uri: Uri): String? = if (uri.isAsFile()) GENERIC_TYPE else super.getTypeAnonymous(uri)

    companion object {
        const val GENERIC_TYPE = "application/octet-stream"
        private const val AS_FILE = "as"
        private const val AS_FILE_VALUE = "file"

        fun Uri.asFile(): Uri = buildUpon().appendQueryParameter(AS_FILE, AS_FILE_VALUE).build()

        private fun Uri.isAsFile() = getQueryParameter(AS_FILE) == AS_FILE_VALUE
    }
}
