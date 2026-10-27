package com.rozin.exifremover.ui

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val thumbnailCache = LruCache<String, ImageBitmap>(64)

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
            value = withContext(Dispatchers.IO) { runCatching { decode(context, source, maxPx) }.getOrNull() }
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
