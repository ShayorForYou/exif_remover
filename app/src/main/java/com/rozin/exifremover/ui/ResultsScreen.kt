package com.rozin.exifremover.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.rememberLazyListState
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.rozin.exifremover.core.StripLevel

@Composable
fun ResultsScreen(
    state: CleanState.Done,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onSaveCopies: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (CleanResult) -> Unit,
    onCloseDetail: () -> Unit,
    onView: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val cleaned = state.cleaned
    val single = state.results.size == 1
    val located = remember(cleaned) { cleaned.count { it.hadLocation } }
    val removedBytes = remember(cleaned) { cleaned.sumOf { it.removedBytes } }
    val options = state.settings.strip
    val locationOnly = options.level == StripLevel.LOCATION

    state.detail?.let { DetailSheet(it, onDismiss = onCloseDetail, onRetry = onRetry.takeIf { state.saved == null }) }

    // Status bar inset on the frame, not the grid: inside the grid it scrolls away and content slides under the bar.
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    IconButton(onClick = onClose, modifier = Modifier.padding(top = 4.dp).offset(x = (-12).dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        when {
                            cleaned.isEmpty() -> "Nothing cleaned"
                            locationOnly -> "Location removed"
                            else -> "All clean"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            cleaned.isEmpty() -> "None of the photos could be cleaned."
                            locationOnly && located > 0 -> "GPS data is gone. Camera details and dates were kept."
                            locationOnly -> "No GPS data found. Camera details and dates were kept."
                            located > 0 -> "Location, camera and personal details have been removed."
                            else -> "Camera and personal details have been removed."
                        } + if (!locationOnly && options.keepDate && cleaned.isNotEmpty()) " The date taken was kept." else "",
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
                    state.saved?.let { saved ->
                        Notice(
                            if (saved.trashed) Icons.Rounded.DeleteOutline else Icons.Rounded.Done,
                            saved.describe(),
                            error = saved.failed > 0,
                            action = saved.first?.let { first ->
                                {
                                    TextButton(onClick = { onView(first) }) {
                                        Text("View", color = if (saved.failed > 0) colors.onErrorContainer else colors.onSurface)
                                    }
                                }
                            },
                        )
                    }
                }
            }
            if (state.consentDenied && state.saved == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Notice(
                        Icons.Rounded.ErrorOutline,
                        "Originals weren't changed. Allow the edit to replace them, or keep them and save copies.",
                        error = true,
                        action = {
                            TextButton(onClick = onSaveCopies, enabled = !state.saving) {
                                Text("Save copies instead", color = colors.onErrorContainer)
                            }
                        },
                    )
                }
            }
            if (state.failed.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Notice(
                        Icons.Rounded.ErrorOutline,
                        "${state.failed.size} couldn't be cleaned: ${state.failed.first().reason}",
                        error = true,
                        // Once saved, a retry's new copies would never be saved, so it's only offered before.
                        action = if (state.saved == null) {
                            { RetryButton(state.retrying, onRetry) }
                        } else {
                            null
                        },
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
                ResultTile(result, Modifier.fillMaxWidth().aspectRatio(1f).clickable { onOpen(result) })
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
                // Saving no longer leaves the screen by itself, so Share stays one tap away; "Done" closes it.
                Button(
                    onClick = if (state.saved != null) onClose else onSave,
                    enabled = !state.saving && !state.retrying,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    when {
                        state.saving -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        state.saved != null -> Text("Done")
                        else -> {
                            val replace = state.settings.saveMode == SaveMode.REPLACE
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

private fun SaveOutcome.describe(): String = buildList {
    if (replaced > 0) {
        add("$replaced original${if (replaced == 1) "" else "s"} replaced" + if (trashed) " (old ones in Trash for 30 days)" else "")
    }
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
private fun Notice(icon: ImageVector, text: String, error: Boolean, action: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val content = if (error) colors.error else colors.onSurfaceVariant
    Column(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (error) colors.errorContainer else colors.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = if (error) colors.onErrorContainer else colors.onSurface)
        }
        if (action != null) Box(Modifier.align(Alignment.End).offset(x = 8.dp)) { action() }
    }
}

@Composable
private fun RetryButton(retrying: Boolean, onRetry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    TextButton(onClick = onRetry, enabled = !retrying) {
        if (retrying) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.onErrorContainer)
        } else {
            Text("Retry", color = colors.onErrorContainer)
        }
    }
}

/** What one photo lost and kept, or why it couldn't be cleaned. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(detail: Detail, onDismiss: () -> Unit, onRetry: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val result = detail.result
    val comparison = detail.comparison
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
        LazyColumn(state = list, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Same key as the grid tile, so this comes straight from the thumbnail cache.
                    Thumbnail(
                        if (result is CleanResult.Cleaned) result.file else result.source,
                        maxPx = 320,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(MaterialTheme.shapes.small)
                            .border(1.dp, colors.outlineVariant, MaterialTheme.shapes.small),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            when {
                                result is CleanResult.Failed -> "Couldn't clean this photo"
                                comparison == null -> "Reading details…"
                                comparison.removed.isEmpty() -> "No EXIF details found"
                                comparison.removed.size == 1 -> "1 detail removed"
                                else -> "${comparison.removed.size} details removed"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (result is CleanResult.Cleaned) {
                            Text(
                                Formatter.formatShortFileSize(LocalContext.current, result.removedBytes) + " of metadata removed",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            when {
                result is CleanResult.Failed -> item {
                    Notice(
                        Icons.Rounded.ErrorOutline, result.reason, error = true,
                        action = onRetry?.let { retry -> { RetryButton(retrying = false) { retry(); onDismiss() } } },
                    )
                }
                comparison == null -> item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
                else -> {
                    val removed = comparison.removed
                    val kept = comparison.kept
                    // Header, tiles, then each section is a title item followed by its card.
                    val removedAt = 2
                    val keptAt = if (removed.isEmpty()) 2 else 4
                    if (removed.isNotEmpty() || kept.isNotEmpty()) item {
                        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CountTile(
                                removed.size, "Removed", Icons.Rounded.RemoveCircleOutline,
                                accent = colors.tertiary, container = colors.tertiaryContainer,
                                modifier = Modifier.weight(1f),
                                onClick = { scope.launch { list.animateScrollToItem(removedAt) }; Unit }.takeIf { removed.isNotEmpty() },
                            )
                            CountTile(
                                kept.size, "Kept", Icons.Rounded.CheckCircleOutline,
                                accent = colors.onSurface, container = colors.surfaceContainer,
                                modifier = Modifier.weight(1f),
                                onClick = { scope.launch { list.animateScrollToItem(keptAt) }; Unit }.takeIf { kept.isNotEmpty() },
                            )
                        }
                    }
                    if (removed.isNotEmpty()) {
                        item {
                            SheetSection(
                                "Removed", removed.size, "Gone from the cleaned copy",
                                Icons.Rounded.RemoveCircleOutline, colors.tertiary,
                            )
                        }
                        item { FieldCard(removed, strikeValues = true) }
                    }
                    if (kept.isNotEmpty()) {
                        item {
                            SheetSection(
                                "Kept", kept.size, "Still in the cleaned copy",
                                Icons.Rounded.CheckCircleOutline, colors.onSurface,
                            )
                        }
                        item { FieldCard(kept) }
                    }
                    if (comparison.removed.isEmpty() && comparison.kept.isEmpty()) {
                        item { Notice(Icons.Rounded.Done, "Any other hidden blocks (XMP, vendor data) were still cleared.", error = false) }
                    }
                }
            }
        }
    }
}

/** Section title with its own icon, colour and count, so Removed and Kept can't be mistaken for each other. */
@Composable
private fun SheetSection(title: String, count: Int, subtitle: String, icon: ImageVector, accent: Color) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 28.dp, bottom = 10.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Text(
            "$count",
            style = MaterialTheme.typography.labelLarge,
            color = accent,
            modifier = Modifier.clip(CircleShape).background(accent.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

/** At-a-glance count at the top of the sheet; tapping jumps to that section. */
@Composable
private fun CountTile(
    count: Int,
    label: String,
    icon: ImageVector,
    accent: Color,
    container: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text("$count", style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
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
fun WorkingScreen(done: Int, total: Int, onCancel: () -> Unit) {
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
        Spacer(Modifier.height(24.dp))
        // Nothing is saved until the results screen, so stopping here leaves every original untouched.
        TextButton(onClick = onCancel) { Text("Cancel", color = MaterialTheme.colorScheme.onSurface) }
    }
}
