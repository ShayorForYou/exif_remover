package com.rozin.exifremover.ui

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Sized in bytes, so a gallery grid of small thumbnails and a few large previews share one budget.
private val thumbnailCache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().byteCount
}

/**
 * Plain Dispatchers.IO runs up to 64 decodes at once: a results grid of fresh full-size files then saturates every
 * core, starving the UI thread (felt as jank when leaving the screen while they're still in flight).
 */
private val decodeDispatcher = Dispatchers.IO.limitedParallelism(3)

/** Drops cached thumbnails of files that are being deleted; their random names mean they'd never be hit again. */
fun evictThumbnails(files: Collection<File>) {
    if (files.isEmpty()) return
    val paths = files.mapTo(HashSet()) { it.toString() }
    thumbnailCache.snapshot().keys
        .filter { it.substringBeforeLast('@') in paths }
        .forEach { thumbnailCache.remove(it) }
}

/** Downsampled image from a content [Uri] or a [File], decoded off the main thread and cached. */
@Composable
fun Thumbnail(
    source: Any,
    maxPx: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val key = "$source@$maxPx"
    val bitmap by produceState(thumbnailCache.get(key), key) {
        if (value == null) {
            // Queued decodes for tiles that scrolled away or left with their screen are skipped, not run.
            value = withContext(decodeDispatcher) { runCatching { decode(context, source, maxPx) }.getOrNull() }
                ?.also { thumbnailCache.put(key, it) }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = contentScale)
        } else {
            Icon(Icons.Rounded.Image, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

private fun decode(context: Context, source: Any, maxPx: Int): ImageBitmap {
    // MediaStore keeps ready-made thumbnails; far cheaper than decoding the full photo for every grid cell.
    if (source is Uri && source.authority == MediaStore.AUTHORITY) {
        runCatching { context.contentResolver.loadThumbnail(source, Size(maxPx, maxPx), null) }
            .getOrNull()?.let { return it.asImageBitmap() }
    }
    val src = when (source) {
        is File -> ImageDecoder.createSource(source)
        is Uri -> ImageDecoder.createSource(context.contentResolver, source)
        else -> error("Unsupported image source: $source")
    }
    return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
        val shortSide = minOf(info.size.width, info.size.height)
        decoder.setTargetSampleSize(maxOf(1, shortSide / maxPx))
    }.asImageBitmap()
}

/** Small rounded label, e.g. "Location removed". */
@Composable
fun Pill(
    text: String,
    icon: ImageVector?,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.clip(CircleShape).background(container).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
            if (text.isNotEmpty()) Spacer(Modifier.width(6.dp))
        }
        if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.labelMedium, color = content)
    }
}
