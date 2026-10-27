package com.rozin.exifremover.ui

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rozin.exifremover.core.CleanResult
import com.rozin.exifremover.core.GALLERY_FOLDER
import com.rozin.exifremover.core.SaveMode
import com.rozin.exifremover.core.SaveOutcome

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    state: CleanState.Done,
    onClose: () -> Unit,
    onSave: (SaveMode) -> Unit,
    onShare: () -> Unit,
) {
    val context = LocalContext.current
    val cleaned = state.cleaned
    val single = state.results.size == 1

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (cleaned.isEmpty()) "Nothing cleaned" else "All clean") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (cleaned.isNotEmpty()) {
                Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f).height(52.dp), shape = MaterialTheme.shapes.medium) {
                            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Share")
                        }
                        Button(
                            onClick = onSave,
                            enabled = !state.saving && state.saved == null,
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            when {
                                state.saving -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                state.saved != null -> {
                                    Icon(Icons.Rounded.Done, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Saved")
                                }
                                else -> {
                                    val replace = state.mode == SaveMode.REPLACE
                                    Icon(if (replace) Icons.Rounded.SwapHoriz else Icons.Rounded.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
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
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = if (single) GridCells.Fixed(1) else GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                val removedBytes = cleaned.sumOf { (it.originalBytes - it.file.length()).coerceAtLeast(0) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    val colors = MaterialTheme.colorScheme
                    StatTile(
                        Icons.Rounded.CheckCircle, "${cleaned.size}", if (cleaned.size == 1) "photo cleaned" else "photos cleaned",
                        colors.onSurfaceVariant, Modifier.weight(1f),
                    )
                    val located = cleaned.count { it.hadLocation }
                    StatTile(
                        Icons.Rounded.LocationOff, "$located", if (located == 1) "location removed" else "locations removed",
                        colors.tertiary, Modifier.weight(1f),
                    )
                    StatTile(
                        Icons.Rounded.DataUsage, Formatter.formatShortFileSize(context, removedBytes), "metadata removed",
                        colors.onSurfaceVariant, Modifier.weight(1f),
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                AnimatedVisibility(state.saved != null) {
                    state.saved?.let { Banner(Icons.Rounded.Done, it.describe(), success = it.failed == 0) }
                }
            }
            if (state.failed.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Banner(
                        Icons.Rounded.ErrorOutline,
                        "${state.failed.size} couldn't be cleaned: ${state.failed.first().reason}",
                        success = false,
                    )
                }
            }
            items(state.results) { result ->
                ResultTile(result, Modifier.fillMaxWidth().aspectRatio(if (single) 4f / 3f else 1f))
            }
        }
    }
}

private fun SaveOutcome.describe(): String = buildList {
    if (replaced > 0) add("$replaced original${if (replaced == 1) "" else "s"} replaced")
    if (copied > 0) add("$copied saved to Pictures/$GALLERY_FOLDER")
    if (failed > 0) add("$failed failed")
}.joinToString(" · ").ifEmpty { "Nothing was saved" }

@Composable
private fun StatTile(
    icon: ImageVector,
    value: String,
    label: String,
    iconTint: Color,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(colors.surfaceContainerLow)
            .border(1.dp, colors.outlineVariant, MaterialTheme.shapes.medium)
            .padding(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(12.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, color = colors.onSurface, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 2)
    }
}

@Composable
private fun Banner(icon: ImageVector, text: String, success: Boolean) {
    val colors = MaterialTheme.colorScheme
    val container = if (success) colors.surfaceContainer else colors.errorContainer
    val content = if (success) colors.onSurface else colors.onErrorContainer
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(container).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = content)
    }
}

@Composable
private fun ResultTile(result: CleanResult, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.clip(MaterialTheme.shapes.medium)) {
        when (result) {
            is CleanResult.Cleaned -> {
                Thumbnail(result.file, maxPx = 720, modifier = Modifier.fillMaxSize())
                Row(
                    Modifier.align(Alignment.TopEnd).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val scrim = Color.Black.copy(alpha = 0.55f)
                    if (result.reencoded) Pill("JPEG", null, scrim, Color.White)
                    if (result.hadLocation) Pill("", Icons.Rounded.LocationOff, scrim, Color.White)
                }
            }
            is CleanResult.Failed -> Column(
                Modifier.fillMaxSize().background(colors.errorContainer).padding(8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = colors.onErrorContainer)
                Text("Failed", style = MaterialTheme.typography.labelMedium, color = colors.onErrorContainer, textAlign = TextAlign.Center)
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
