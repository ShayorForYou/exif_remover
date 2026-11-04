package com.rozin.exifremover.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rozin.exifremover.core.ExifField
import com.rozin.exifremover.core.ExifGroup
import com.rozin.exifremover.core.StripLevel
import com.rozin.exifremover.core.StripOptions

/** Read-only view of what a photo carries. Cleaning always removes all of it. */
@Composable
fun InspectScreen(
    state: CleanState.Inspecting,
    onBack: () -> Unit,
    onRemoveAll: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val inspection = state.inspection
    val fields = inspection.fields
    val location = fields.firstOrNull { it.label == "Coordinates" }
    val options = state.settings.strip
    val removed = remember(fields, options) { fields.count { !options.keeps(it) } }
    val locationOnly = options.level == StripLevel.LOCATION

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
                                removed == 0 -> "Nothing here will be removed"
                                removed == 1 -> "1 detail will be removed"
                                else -> "$removed details will be removed"
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
            if (fields.isEmpty()) {
                item { Notice(Icons.Outlined.Verified, "Nothing to review. Cleaning still clears any other hidden data.") }
            }

            items(state.groups.entries.toList(), key = { it.key }) { (group, groupFields) ->
                FieldGroup(group, groupFields, kept = options::keeps)
            }
        }

        HorizontalDivider(color = colors.outlineVariant)
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Button(
                onClick = onRemoveAll,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(if (locationOnly) "Remove location" else "Remove all metadata")
            }
            Text(
                if (locationOnly) "Camera details and dates stay. Image quality is untouched." else "Image quality and format stay exactly the same.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
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

/** Whether cleaning with these options leaves [field] in the photo. */
internal fun StripOptions.keeps(field: ExifField): Boolean = when (level) {
    StripLevel.LOCATION -> field.group != ExifGroup.LOCATION
    StripLevel.ALL -> (keepDate && field.label == "Taken") || (keepOrientation && field.label == "Orientation")
}

@Composable
private fun FieldGroup(group: ExifGroup, fields: List<ExifField>, kept: (ExifField) -> Boolean) {
    val colors = MaterialTheme.colorScheme
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
            Text(group.title.uppercase(), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        FieldCard(fields, kept)
    }
}

/** Label/value rows in one outlined card. Fields that [kept] marks are tagged, so it's clear they stay. */
@Composable
internal fun FieldCard(fields: List<ExifField>, kept: (ExifField) -> Boolean = { false }) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column {
            fields.forEachIndexed { index, field ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = colors.outlineVariant)
                FieldRow(field, kept(field))
            }
        }
    }
}

@Composable
private fun FieldRow(field: ExifField, kept: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(0.4f)) {
            Text(field.label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            if (kept) Text("Kept", style = MaterialTheme.typography.labelSmall, color = colors.primary)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            field.value,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            textAlign = TextAlign.End,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.6f),
        )
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
