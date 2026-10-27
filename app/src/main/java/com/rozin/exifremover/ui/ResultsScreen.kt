package com.rozin.exifremover.ui

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rozin.exifremover.core.CleanResult
import com.rozin.exifremover.core.GALLERY_FOLDER
import com.rozin.exifremover.core.SaveMode
import com.rozin.exifremover.core.SaveOutcome
import kotlinx.coroutines.delay

@Composable
fun ResultsScreen(
    state: CleanState.Done,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val cleaned = state.cleaned
    val single = state.results.size == 1
    val located = cleaned.count { it.hadLocation }
    val removedBytes = cleaned.sumOf { it.removedBytes }

    // A clean save is the end of the flow: show the confirmation briefly, then head home.
    // On partial failure, stay so the message can be read.
    LaunchedEffect(state.saved) {
        val saved = state.saved ?: return@LaunchedEffect
        if (saved.failed == 0) {
            delay(SAVED_RETURN_DELAY_MS)
            onClose()
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.statusBarsPadding()) {
                    IconButton(onClick = onClose, modifier = Modifier.padding(top = 4.dp).offset(x = (-12).dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(if (cleaned.isEmpty()) "Nothing cleaned" else "All clean", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            cleaned.isEmpty() -> "None of the photos could be cleaned."
                            located > 0 -> "Location, camera and personal details have been removed."
                            else -> "Camera and personal details have been removed."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                // IntrinsicSize.Min gives every column the same height, so the dividers and values line up.
                Row(
                    Modifier
                        .padding(top = 20.dp)
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .clip(MaterialTheme.shapes.medium)
                        .background(colors.surfaceContainerLow)
                        .border(1.dp, colors.outlineVariant, MaterialTheme.shapes.medium)
                        .padding(vertical = 16.dp),
                ) {
                    Stat("${cleaned.size}", if (cleaned.size == 1) "Photo" else "Photos", colors.onSurface, Modifier.weight(1f))
                    VerticalDivider(color = colors.outlineVariant)
                    Stat(
                        "$located", if (located == 1) "Location" else "Locations",
                        if (located > 0) colors.tertiary else colors.onSurface, Modifier.weight(1f),
                    )
                    VerticalDivider(color = colors.outlineVariant)
                    Stat(Formatter.formatShortFileSize(context, removedBytes), "Data removed", colors.onSurface, Modifier.weight(1f))
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                AnimatedVisibility(state.saved != null) {
                    state.saved?.let { Notice(Icons.Rounded.Done, it.describe(), error = it.failed > 0) }
                }
            }
            if (state.failed.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Notice(
                        Icons.Rounded.ErrorOutline,
                        "${state.failed.size} couldn't be cleaned: ${state.failed.first().reason}",
                        error = true,
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (single) "PHOTO" else "PHOTOS",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, top = 20.dp, bottom = 4.dp),
                )
            }
            items(state.results) { result ->
                ResultTile(result, Modifier.fillMaxWidth().aspectRatio(1f))
            }
        }

        if (cleaned.isNotEmpty()) {
            HorizontalDivider(color = colors.outlineVariant)
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                ) {
                    Text("Share", color = colors.onSurface)
                }
                Button(
                    onClick = onSave,
                    enabled = !state.saving && state.saved == null,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    when {
                        state.saving -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        state.saved != null -> Text("Saved")
                        else -> {
                            val replace = state.mode == SaveMode.REPLACE
                            Text(
                                when {
                                    replace && single -> "Replace original"
                                    replace -> "Replace originals"
                                    single -> "Save copy"
                                    else -> "Save copies"
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val SAVED_RETURN_DELAY_MS = 1400L

private fun SaveOutcome.describe(): String = buildList {
    if (replaced > 0) add("$replaced original${if (replaced == 1) "" else "s"} replaced")
    if (copied > 0) add("$copied saved to Pictures/$GALLERY_FOLDER")
    if (failed > 0) add("$failed failed")
}.joinToString(" · ").ifEmpty { "Nothing was saved" }

@Composable
private fun Stat(value: String, label: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 16.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = valueColor, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Notice(icon: ImageVector, text: String, error: Boolean) {
    val colors = MaterialTheme.colorScheme
    val content = if (error) colors.error else colors.onSurfaceVariant
    Row(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (error) colors.errorContainer else colors.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (error) colors.onErrorContainer else colors.onSurface)
    }
}

@Composable
private fun ResultTile(result: CleanResult, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.clip(MaterialTheme.shapes.small).border(1.dp, colors.outlineVariant, MaterialTheme.shapes.small)) {
        when (result) {
            is CleanResult.Cleaned -> {
                Thumbnail(result.file, maxPx = 320, modifier = Modifier.fillMaxSize())
                Row(
                    Modifier.align(Alignment.TopEnd).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val scrim = Color.Black.copy(alpha = 0.5f)
                    if (result.reencoded) Pill("JPEG", null, scrim, Color.White)
                    if (result.hadLocation) Pill("", Icons.Rounded.LocationOff, scrim, Color.White)
                }
            }
            is CleanResult.Failed -> Column(
                Modifier.fillMaxSize().background(colors.surfaceContainer).padding(8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = colors.error, modifier = Modifier.size(20.dp))
                Spacer(Modifier.height(4.dp))
                Text("Failed", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun WorkingScreen(done: Int, total: Int) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { if (total == 0) 0f else done / total.toFloat() },
                modifier = Modifier.size(96.dp),
                strokeWidth = 3.dp,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
            )
            Text(
                if (total > 1) "$done/$total" else "",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(28.dp))
        Text("Removing hidden data…", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Location, camera and personal details are being stripped.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
