package com.rozin.exifremover.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rozin.exifremover.core.LibraryAccess
import com.rozin.exifremover.core.LibraryPhoto

/** In-app photo grid for "Replace original": albums, multi-select, then clean the chosen originals in place. */
@Composable
fun GalleryScreen(
    state: GalleryState,
    onBack: () -> Unit,
    onAlbum: (String?) -> Unit,
    onToggle: (LibraryPhoto) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onAddMore: () -> Unit,
    onClean: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
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
                        state.selected.isEmpty() -> "Originals are cleaned in place"
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

        if (state.albums.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "all") { AlbumChip("All photos", state.photos.size, state.album == null) { onAlbum(null) } }
                items(state.albums, key = { it.id }) { album ->
                    AlbumChip(album.name, album.count, state.album == album.id) { onAlbum(album.id) }
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
                    "No photos here",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(state.visible, key = { it.uri.toString() }) { photo ->
                        PhotoCell(
                            photo,
                            selected = photo.uri in state.selected,
                            enabled = photo.uri in state.selected || !state.atLimit,
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
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(if (active) colors.onSurface else Color.Transparent, label = "album")
    val content by animateColorAsState(if (active) colors.surface else colors.onSurfaceVariant, label = "albumText")
    Row(
        Modifier
            .clip(CircleShape)
            .background(container)
            .border(1.dp, if (active) colors.onSurface else colors.outlineVariant, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Text("$count", style = MaterialTheme.typography.labelMedium, color = content.copy(alpha = 0.6f))
    }
}

@Composable
private fun PhotoCell(photo: LibraryPhoto, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // Selected photos shrink into a rounded tile, like the system photo picker.
    val scale by animateFloatAsState(if (selected) 0.86f else 1f, label = "scale")
    Box(
        Modifier
            .aspectRatio(1f)
            .background(if (selected) colors.surfaceContainerHigh else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
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
