package com.rozin.exifremover.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rozin.exifremover.core.ThemeMode

@Composable
fun HomeScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    keepOrientation: Boolean,
    onKeepOrientationChange: (Boolean) -> Unit,
    onChoosePhotos: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Shield, contentDescription = null, tint = colors.onSurface, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("EXIF Remover", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            ThemeSwitch(themeMode, onThemeModeChange)
        }

        Spacer(Modifier.height(48.dp))
        Text("Post the photo,\nnot your location.", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "Remove GPS, camera details and other hidden data before a photo leaves your phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onChoosePhotos,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Choose photos", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "One photo to review its details, or several to clean at once. " +
                "You choose to save a copy or replace the original afterwards.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionLabel("Options")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ScreenRotation, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text("Keep rotation", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Keeps only the orientation tag",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Switch(checked = keepOrientation, onCheckedChange = onKeepOrientationChange)
        }
        HorizontalDivider(Modifier.padding(top = 20.dp), color = colors.outlineVariant)

        SectionLabel("Quickest way to post")
        Row(verticalAlignment = Alignment.Top) {
            Step(Icons.Outlined.Image, "Gallery")
            StepArrow()
            Step(Icons.Outlined.Share, "Share")
            StepArrow()
            Step(Icons.Outlined.Shield, "Strip &\nshare", highlighted = true)
            StepArrow()
            Step(Icons.Outlined.Public, "Any app")
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Share from any gallery app to \"Strip & share\". The share sheet reopens with the clean copy.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
}

/** Three-way System / Light / Dark switch. The choice is saved by the caller. */
@Composable
private fun ThemeSwitch(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(CircleShape)
            .border(1.dp, colors.outlineVariant, CircleShape)
            .padding(3.dp),
    ) {
        listOf(
            Triple(ThemeMode.SYSTEM, Icons.Outlined.BrightnessAuto, "Match device theme"),
            Triple(ThemeMode.LIGHT, Icons.Outlined.LightMode, "Light theme"),
            Triple(ThemeMode.DARK, Icons.Outlined.DarkMode, "Dark theme"),
        ).forEach { (option, icon, description) ->
            val selected = option == mode
            val background by animateColorAsState(if (selected) colors.onSurface else Color.Transparent, label = "themeBg")
            val tint by animateColorAsState(if (selected) colors.surface else colors.onSurfaceVariant, label = "themeTint")
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(background)
                    .selectable(selected = selected, role = Role.RadioButton) { onChange(option) }
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, top = 36.dp, bottom = 12.dp),
    )
}

@Composable
private fun RowScope.Step(icon: ImageVector, label: String, highlighted: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (highlighted) colors.onSurface else Color.Transparent)
                .border(1.dp, if (highlighted) colors.onSurface else colors.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, contentDescription = null,
                tint = if (highlighted) colors.surface else colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            color = if (highlighted) colors.onSurface else colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepArrow() {
    Icon(
        Icons.AutoMirrored.Rounded.ArrowForward,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 12.dp).size(14.dp),
    )
}
