package com.rozin.exifremover.ui

import android.net.Uri
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rozin.exifremover.core.LibraryAccess
import com.rozin.exifremover.core.LibraryPhoto
import com.rozin.exifremover.core.SaveMode

/** In-app photo grid: albums, multi-select, then clean the chosen photos as copies or in place. */
@Composable
fun GalleryScreen(
    state: GalleryState,
    saveMode: SaveMode,
    onBack: () -> Unit,
    onAlbum: (String?) -> Unit,
    onToggleLocated: () -> Unit,
    onToggle: (LibraryPhoto) -> Unit,
    onDragSelect: (base: Set<Uri>, range: List<Uri>, select: Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onAddMore: () -> Unit,
    onClean: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val grid = rememberLazyGridState()
    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(
            Modifier.statusBarsPadding().padding(start = 8.dp, end = 12.dp, top = 4.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("Choose photos", style = MaterialTheme.typography.titleLarge)
                Text(
                    when {
                        state.selected.isEmpty() -> when (saveMode) {
                            SaveMode.COPY -> "Clean copies are saved separately"
                            SaveMode.REPLACE -> "Originals are cleaned in place"
                        }
                        state.atLimit -> "${state.selected.size} selected · limit reached"
                        else -> "${state.selected.size} selected"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            AnimatedVisibility(state.canSelectMore, enter = fadeIn(), exit = fadeOut()) {
                // Spell out the cap when it would cut the album short.
                val everything = state.selectableCount == state.visible.count { it.uri !in state.selected }
                TextButton(onClick = onSelectAll) {
                    Text(if (everything) "Select all" else "Select ${state.selectableCount}", color = colors.onSurface)
                }
            }
            AnimatedVisibility(state.selected.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                TextButton(onClick = onClear) { Text("Clear", color = colors.onSurfaceVariant) }
            }
        }

        if (state.albums.size > 1 || state.locationAvailable) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.locationAvailable) {
                    item(key = "located") {
                        LocationChip(state.locatedInAlbum, state.onlyLocated, state.checking, onToggleLocated)
                    }
                    if (state.albums.size > 1) {
                        item(key = "divider") { VerticalDivider(Modifier.height(20.dp), color = colors.outlineVariant) }
                    }
                }
                if (state.albums.size > 1) {
                    item(key = "all") { AlbumChip("All photos", state.photos.size, state.album == null) { onAlbum(null) } }
                    items(state.albums, key = { it.id }) { album ->
                        AlbumChip(album.name, album.count, state.album == album.id) { onAlbum(album.id) }
                    }
                }
            }
        } else {
            Spacer(Modifier.height(12.dp))
        }

        if (state.access == LibraryAccess.PARTIAL) {
            Row(
                Modifier
                    .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(colors.surfaceContainer)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Only photos you've allowed are shown",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onAddMore) { Text("Add more", color = colors.onSurface) }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 2.dp)
                state.visible.isEmpty() -> Text(
                    when {
                        state.onlyLocated && state.checking -> "Checking photos for a location…"
                        state.onlyLocated -> "No photos with a location here"
                        else -> "No photos here"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    state = grid,
                    modifier = Modifier.dragToSelect(grid, state.visible, state.selected, onDragSelect),
                    contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(state.visible, key = { it.uri.toString() }) { photo ->
                        PhotoCell(
                            photo,
                            selected = photo.uri in state.selected,
                            enabled = photo.uri in state.selected || !state.atLimit,
                            located = photo.uri in state.located,
                        ) { onToggle(photo) }
                    }
                }
            }
        }

        AnimatedVisibility(state.selected.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                HorizontalDivider(color = colors.outlineVariant)
                Button(
                    onClick = onClean,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    val n = state.selected.size
                    Text(if (n == 1) "Clean 1 photo" else "Clean $n photos")
                }
            }
        }
    }
}

@Composable
private fun AlbumChip(name: String, count: Int, active: Boolean, onClick: () -> Unit) {
    Chip(active, onClick) { content ->
        Text(name, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Text("$count", style = MaterialTheme.typography.labelMedium, color = content.copy(alpha = 0.6f))
    }
}

/** Filters the album down to photos that would give away where they were taken. Counts up while photos are checked. */
@Composable
private fun LocationChip(count: Int, active: Boolean, checking: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Chip(active, onClick) { content ->
        Icon(
            Icons.Outlined.LocationOn, contentDescription = null,
            tint = if (active) content else colors.tertiary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text("With location", style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        if (checking) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = content.copy(alpha = 0.6f))
            Spacer(Modifier.width(4.dp))
        }
        Text("$count", style = MaterialTheme.typography.labelMedium, color = content.copy(alpha = 0.6f))
    }
}

@Composable
private fun Chip(active: Boolean, onClick: () -> Unit, content: @Composable RowScope.(Color) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(if (active) colors.onSurface else Color.Transparent, label = "chip")
    val text by animateColorAsState(if (active) colors.surface else colors.onSurfaceVariant, label = "chipText")
    Row(
        Modifier
            .clip(CircleShape)
            .background(container)
            .border(1.dp, if (active) colors.onSurface else colors.outlineVariant, CircleShape)
            .selectable(selected = active, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content(text)
    }
}

@Composable
private fun PhotoCell(photo: LibraryPhoto, selected: Boolean, enabled: Boolean, located: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // Selected photos shrink into a rounded tile, like the system photo picker.
    val scale by animateFloatAsState(if (selected) 0.86f else 1f, label = "scale")
    Box(
        Modifier
            .aspectRatio(1f)
            .background(if (selected) colors.surfaceContainerHigh else Color.Transparent)
            // The long press itself belongs to the grid's drag-select; claiming it here keeps it from also
            // registering as a tap when the finger lifts.
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = {}),
    ) {
        Thumbnail(
            photo.uri,
            maxPx = 256,
            modifier = Modifier
                .fillMaxSize()
                .scale(scale)
                .clip(if (selected) MaterialTheme.shapes.small else MaterialTheme.shapes.extraSmall),
        )
        SelectMark(selected, Modifier.align(Alignment.TopEnd).padding(6.dp))
        if (located) LocatedMark(Modifier.align(Alignment.BottomStart).padding(6.dp))
    }
}

@Composable
private fun LocatedMark(modifier: Modifier = Modifier) {
    Box(
        modifier.size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.LocationOn, contentDescription = "Has location", tint = Color.White, modifier = Modifier.size(12.dp))
    }
}

@Composable
private fun SelectMark(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (selected) colors.primary else Color.Black.copy(alpha = 0.25f))
            // A surface-coloured ring keeps the check readable on both light and dark photos, in either theme.
            .border(1.5.dp, if (selected) colors.surface else Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = colors.onPrimary, modifier = Modifier.size(14.dp))
    }
}

/**
 * Long-press a photo, then drag to select every photo between it and the finger, like the system gallery.
 * Starting on a selected photo deselects instead. Dragging near the top or bottom edge scrolls the grid.
 */
@Composable
private fun Modifier.dragToSelect(
    grid: LazyGridState,
    photos: List<LibraryPhoto>,
    selected: Set<Uri>,
    onRange: (base: Set<Uri>, range: List<Uri>, select: Boolean) -> Unit,
): Modifier {
    // The gesture outlives recompositions, so it reads the latest values through these.
    val currentPhotos by rememberUpdatedState(photos)
    val currentSelected by rememberUpdatedState(selected)
    val currentOnRange by rememberUpdatedState(onRange)
    val drag = remember { DragSelection() }
    val edge = with(LocalDensity.current) { AUTO_SCROLL_EDGE.toPx() }

    fun update(position: Offset) {
        drag.position = position
        val index = grid.photoIndexAt(position) ?: return
        if (index == drag.current || drag.anchor !in currentPhotos.indices || index !in currentPhotos.indices) return
        drag.current = index
        val range = currentPhotos.subList(minOf(drag.anchor, index), maxOf(drag.anchor, index) + 1).map { it.uri }
        currentOnRange(drag.base, range, drag.select)
    }

    LaunchedEffect(drag.scrollSpeed) {
        if (drag.scrollSpeed == 0f) return@LaunchedEffect
        while (isActive) {
            grid.scrollBy(drag.scrollSpeed)
            // New photos slide under a finger that isn't moving; pick them up too.
            update(drag.position)
            delay(AUTO_SCROLL_FRAME_MS)
        }
    }

    return pointerInput(grid) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                val index = grid.photoIndexAt(offset) ?: return@detectDragGesturesAfterLongPress
                val photo = currentPhotos.getOrNull(index) ?: return@detectDragGesturesAfterLongPress
                drag.anchor = index
                drag.current = -1
                drag.base = currentSelected
                drag.select = photo.uri !in currentSelected
                update(offset)
            },
            onDrag = { change, _ ->
                change.consume()
                val y = change.position.y
                drag.scrollSpeed = when {
                    y > size.height - edge -> (y - (size.height - edge)) / AUTO_SCROLL_DIVISOR
                    y < edge -> -(edge - y) / AUTO_SCROLL_DIVISOR
                    else -> 0f
                }
                update(change.position)
            },
            onDragEnd = { drag.reset() },
            onDragCancel = { drag.reset() },
        )
    }
}

private class DragSelection {
    var anchor = -1
    var current = -1
    var base: Set<Uri> = emptySet()
    var select = true
    var position = Offset.Zero
    var scrollSpeed by mutableFloatStateOf(0f)

    fun reset() {
        anchor = -1
        scrollSpeed = 0f
    }
}

private fun LazyGridState.photoIndexAt(position: Offset): Int? = layoutInfo.visibleItemsInfo.firstOrNull { item ->
    position.x.toInt() in item.offset.x until item.offset.x + item.size.width &&
        position.y.toInt() in item.offset.y until item.offset.y + item.size.height
}?.index

private val AUTO_SCROLL_EDGE = 72.dp
/** Pixels of finger depth into the edge zone per pixel scrolled each frame; larger is gentler. */
private const val AUTO_SCROLL_DIVISOR = 4f
private const val AUTO_SCROLL_FRAME_MS = 16L
