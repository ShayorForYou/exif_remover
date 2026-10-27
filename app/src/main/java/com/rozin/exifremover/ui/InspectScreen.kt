package com.rozin.exifremover.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rozin.exifremover.core.ExifField
import com.rozin.exifremover.core.ExifGroup

@Composable
fun InspectScreen(
    state: CleanState.Inspecting,
    onBack: () -> Unit,
    onToggleField: (String) -> Unit,
    onToggleGroup: (ExifGroup) -> Unit,
    onSelect: (Set<String>) -> Unit,
    onRemoveSelected: () -> Unit,
    onRemoveAll: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val inspection = state.inspection
    val fields = inspection.fields
    val location = fields.firstOrNull { it.label == "Coordinates" }
    val canSelect = inspection.selectable && fields.isNotEmpty()

    Column(Modifier.fillMaxSize().background(colors.background)) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            item {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.statusBarsPadding().padding(top = 4.dp).offset(x = (-12).dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
            }

            item {
                // The user just picked this photo, so a small thumbnail is enough to confirm which one it is.
                Row(Modifier.padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumbnail(
                        inspection.source,
                        maxPx = 160,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(MaterialTheme.shapes.small)
                            .border(1.dp, colors.outlineVariant, MaterialTheme.shapes.small),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Photo details", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            when {
                                fields.isEmpty() -> "No EXIF details found"
                                fields.size == 1 -> "1 detail found"
                                else -> "${fields.size} details found"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }

            if (location != null) {
                item { LocationCallout(location.value) }
            }
            if (!inspection.selectable) {
                item { Notice(Icons.Outlined.Info, "This format can only be cleaned completely. It's saved as a high-quality JPEG.") }
            }
            if (fields.isEmpty()) {
                item { Notice(Icons.Outlined.Verified, "Nothing to review. \"Remove all\" still clears any other hidden data.") }
            }

            if (canSelect) {
                item {
                    val recommended = fields.filter { it.sensitive }.map { it.id }.toSet()
                    val all = fields.map { it.id }.toSet()
                    // "Recommended" only earns a chip when it's a distinct, non-empty preset.
                    val showRecommended = recommended.isNotEmpty() && recommended != all
                    val active = when (state.selected) {
                        all -> "All"
                        emptySet<String>() -> "None"
                        recommended -> "Recommended"
                        else -> null
                    }
                    Row(
                        Modifier.padding(top = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (showRecommended) SelectChip("Recommended", active == "Recommended") { onSelect(recommended) }
                        SelectChip("All", active == "All") { onSelect(all) }
                        SelectChip("None", active == "None") { onSelect(emptySet()) }
                    }
                }
            }

            items(state.groups.entries.toList(), key = { it.key }) { (group, groupFields) ->
                FieldGroup(
                    group = group,
                    fields = groupFields,
                    selected = state.selected,
                    selectable = inspection.selectable,
                    onToggleField = onToggleField,
                    onToggleGroup = { onToggleGroup(group) },
                )
            }
        }

        // Actions
        HorizontalDivider(color = colors.outlineVariant)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (canSelect) {
                OutlinedButton(
                    onClick = onRemoveAll,
                    modifier = Modifier.height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                ) {
                    Text("Remove all", color = colors.onSurface)
                }
                Button(
                    onClick = onRemoveSelected,
                    enabled = state.selected.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        when (val n = state.selected.size) {
                            0 -> "Select details to remove"
                            1 -> "Remove 1 detail"
                            else -> "Remove $n details"
                        }
                    )
                }
            } else {
                Button(
                    onClick = onRemoveAll,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text("Remove all metadata")
                }
            }
        }
    }
}

@Composable
private fun LocationCallout(value: String) {
    val colors = MaterialTheme.colorScheme
    val hasCoordinates = '°' in value
    Row(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(colors.tertiaryContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                if (hasCoordinates) value else "Location is embedded",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onTertiaryContainer,
            )
            Text(
                "Anyone you share this photo with can see where it was taken",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onTertiaryContainer.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun SelectChip(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(if (active) colors.onSurface else Color.Transparent, label = "chip")
    val content by animateColorAsState(if (active) colors.surface else colors.onSurfaceVariant, label = "chipText")
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = content,
        modifier = Modifier
            .clip(CircleShape)
            .background(container)
            .border(1.dp, if (active) colors.onSurface else colors.outlineVariant, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun FieldGroup(
    group: ExifGroup,
    fields: List<ExifField>,
    selected: Set<String>,
    selectable: Boolean,
    onToggleField: (String) -> Unit,
    onToggleGroup: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val allSelected = fields.all { it.id in selected }

    Column(Modifier.padding(top = 28.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                group.icon(), contentDescription = null,
                tint = if (group == ExifGroup.LOCATION) colors.tertiary else colors.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                group.title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (selectable) {
                Text(
                    if (allSelected) "Clear" else "Select all",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurface,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onToggleGroup).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = colors.surfaceContainerLow,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Column {
                fields.forEachIndexed { index, field ->
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = colors.outlineVariant)
                    FieldRow(field, checked = field.id in selected, selectable = selectable) { onToggleField(field.id) }
                }
            }
        }
    }
}

@Composable
private fun FieldRow(field: ExifField, checked: Boolean, selectable: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // Fields marked for removal fade slightly, so what stays is what stands out.
    val textAlpha by animateFloatAsState(if (selectable && checked) 0.55f else 1f, label = "fade")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = selectable, onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).alpha(textAlpha)) {
            Text(field.label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(
                field.value,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selectable) {
            Spacer(Modifier.width(12.dp))
            RoundCheck(checked)
        }
    }
}

@Composable
private fun RoundCheck(checked: Boolean) {
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (checked) colors.onSurface else Color.Transparent, label = "fill")
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(fill)
            .border(1.5.dp, if (checked) colors.onSurface else colors.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = colors.surface, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun Notice(icon: ImageVector, text: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(colors.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

private fun ExifGroup.icon(): ImageVector = when (this) {
    ExifGroup.LOCATION -> Icons.Outlined.LocationOn
    ExifGroup.PEOPLE -> Icons.Outlined.Person
    ExifGroup.DEVICE -> Icons.Outlined.PhoneAndroid
    ExifGroup.DATES -> Icons.Outlined.Schedule
    ExifGroup.SETTINGS -> Icons.Outlined.CameraAlt
    ExifGroup.IMAGE -> Icons.Outlined.Crop
}
