package com.rozin.exifremover.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ripple
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.remember
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.ui.semantics.stateDescription
import com.rozin.exifremover.core.Settings
import com.rozin.exifremover.core.StripLevel
import com.rozin.exifremover.core.StripOptions
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.rozin.exifremover.core.GALLERY_FOLDER
import com.rozin.exifremover.core.SaveMode
import com.rozin.exifremover.core.ThemeMode

@Composable
fun HomeScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    onChoosePhotos: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            // Outside the scroll, so the status bar keeps a fixed strip and content clips below it instead of
            // scrolling underneath the (transparent, edge-to-edge) bar.
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
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
            "One photo to review its details, or several to clean at once.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )


        SectionLabel("Remove", hint = "tap to keep")
        RemoveChips(settings.strip) { onSettingsChange(settings.copy(strip = it)) }

        SectionLabel("Saving")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OptionCard(
                icon = Icons.Outlined.ContentCopy,
                title = "Save a copy",
                subtitle = "Keep the original",
                selected = settings.saveMode == SaveMode.COPY,
                onClick = { onSettingsChange(settings.copy(saveMode = SaveMode.COPY)) },
                modifier = Modifier.weight(1f),
            )
            OptionCard(
                icon = Icons.Outlined.SwapHoriz,
                title = "Replace",
                subtitle = "Clean it in place",
                selected = settings.saveMode == SaveMode.REPLACE,
                onClick = { onSettingsChange(settings.copy(saveMode = SaveMode.REPLACE)) },
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            when {
                settings.saveMode == SaveMode.COPY -> "Copies are saved to Pictures/$GALLERY_FOLDER."
                settings.trashOriginals -> "Pick from your photos. Android asks before moving originals to Trash."
                else -> "Pick from your photos. Android asks before each edit."
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp, start = 2.dp),
        )
        AnimatedVisibility(settings.saveMode == SaveMode.REPLACE, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                HorizontalDivider(Modifier.padding(vertical = 20.dp), color = colors.outlineVariant)
                ToggleRow(
                    icon = Icons.Outlined.RestoreFromTrash,
                    title = "Keep originals in Trash",
                    subtitle = "Restorable for 30 days. Off overwrites them.",
                    checked = settings.trashOriginals,
                    onChange = { onSettingsChange(settings.copy(trashOriginals = it)) },
                )
            }
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

/**
 * What cleaning takes out, as chips: checked ones go, outlined ones stay; tap to switch. Location always goes, it's
 * the point of the app. Date and rotation are only separate choices while camera and personal details go too:
 * with those kept, everything but GPS stays anyway.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoveChips(options: StripOptions, onChange: (StripOptions) -> Unit) {
    val everything = options.level == StripLevel.ALL
    // Tapping the locked chip explains itself in place of the summary for a moment, instead of a toast.
    var lockedTapped by remember { mutableIntStateOf(0) }
    var showLockedHint by remember { mutableStateOf(false) }
    LaunchedEffect(lockedTapped) {
        if (lockedTapped == 0) return@LaunchedEffect
        showLockedHint = true
        delay(LOCKED_HINT_MS)
        showLockedHint = false
    }
    Column(Modifier.fillMaxWidth()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RemoveChip(Icons.Outlined.LocationOn, "Location", removed = true, locked = true) { lockedTapped++ }
            RemoveChip(Icons.Outlined.PhoneAndroid, "Camera & personal", removed = everything) {
                onChange(options.copy(level = if (everything) StripLevel.LOCATION else StripLevel.ALL))
            }
        }
        // The two finer choices live on their own row that unfolds from under the chips like a drawer. Items popping
        // in and out of a wrapping row made the whole row reflow, which is what read as a jump.
        AnimatedVisibility(
            visible = everything,
            enter = expandVertically(tween(MOTION_MS), expandFrom = Alignment.Top) +
                fadeIn(tween(MOTION_MS / 2, delayMillis = MOTION_MS / 2)),
            exit = fadeOut(tween(MOTION_MS / 2)) +
                shrinkVertically(tween(MOTION_MS, delayMillis = MOTION_MS / 4), shrinkTowards = Alignment.Top),
        ) {
            FlowRow(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RemoveChip(Icons.Outlined.Event, "Date taken", removed = !options.keepDate) {
                    onChange(options.copy(keepDate = !options.keepDate))
                }
                RemoveChip(Icons.Outlined.ScreenRotation, "Rotation", removed = !options.keepOrientation) {
                    onChange(options.copy(keepOrientation = !options.keepOrientation))
                }
            }
        }
        // A fixed two-line slot: swapping sentences of different lengths never nudges the layout below, and the old
        // text is gone before the new one shows, so they can't overlap.
        AnimatedContent(
            targetState = if (showLockedHint) "Location is always removed, that's the point of the app." else removeSummary(options),
            transitionSpec = { sequentialFade() using null },
            label = "removeSummary",
        ) { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, start = 2.dp),
            )
        }
    }
}

/** Fades the old content out, then the new one in, so the two never show on top of each other. */
private fun sequentialFade(scaled: Boolean = false): ContentTransform {
    val half = MOTION_MS / 2
    val enter = fadeIn(tween(half, delayMillis = half)) +
        if (scaled) scaleIn(tween(half, delayMillis = half), initialScale = 0.6f) else EnterTransition.None
    val exit = fadeOut(tween(half)) + if (scaled) scaleOut(tween(half), targetScale = 0.6f) else ExitTransition.None
    return enter togetherWith exit
}

private fun removeSummary(options: StripOptions): String {
    if (options.level == StripLevel.LOCATION) return "Only GPS goes. Camera details, dates and rotation stay."
    val kept = listOfNotNull("date taken".takeIf { options.keepDate }, "rotation".takeIf { options.keepOrientation })
    return if (kept.isEmpty()) {
        "Everything goes, including the date taken and rotation."
    } else {
        "Location, camera and personal details go. The ${kept.joinToString(" and ")} ${if (kept.size == 1) "stays" else "stay"}."
    }
}

/**
 * A toggle chip in the Material filter-chip idiom: when [removed], its icon turns into a check. Pressing shrinks it
 * slightly under the finger, and each toggle ticks. A [locked] chip can't change; tapping it gives it a small
 * shake and a reject buzz instead, then calls [onClick] so the screen can say why.
 */
@Composable
private fun RemoveChip(icon: ImageVector, label: String, removed: Boolean, locked: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) PRESSED_SCALE else 1f, label = "chipScale")
    val shake = remember { Animatable(0f) }
    // Fill, outline and text move together on one clock, so the outline doesn't snap ahead of the fill.
    val container by animateColorAsState(if (removed) colors.onSurface else Color.Transparent, tween(MOTION_MS), label = "chip")
    val content by animateColorAsState(if (removed) colors.surface else colors.onSurfaceVariant, tween(MOTION_MS), label = "chipContent")
    val outline by animateColorAsState(if (removed) colors.onSurface else colors.outlineVariant, tween(MOTION_MS), label = "chipOutline")

    val input = if (locked) {
        Modifier
            .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                scope.launch {
                    for (x in SHAKE_STEPS) shake.animateTo(x, tween(SHAKE_STEP_MS))
                }
                onClick()
            }
            .semantics { stateDescription = "Always removed" }
    } else {
        Modifier.toggleable(value = removed, interactionSource = interaction, indication = ripple(), role = Role.Checkbox) {
            haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            onClick()
        }
    }
    Row(
        Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = shake.value * density
            }
            .height(36.dp)
            .clip(CircleShape)
            .background(container)
            .border(1.dp, outline, CircleShape)
            .then(input)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Swapping the icon for a check is what tells people the chip is a switch, not a label.
        AnimatedContent(
            targetState = removed && !locked,
            transitionSpec = { sequentialFade(scaled = true) },
            label = "chipIcon",
        ) { checked ->
            Icon(
                if (checked) Icons.Rounded.Check else icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
        if (locked) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = content.copy(alpha = 0.6f), modifier = Modifier.size(12.dp))
        }
    }
}

private const val PRESSED_SCALE = 0.96f
/** One duration for every part of a chip toggle, so fill, icon, text and layout finish together. */
private const val MOTION_MS = 240
private const val LOCKED_HINT_MS = 2500L
/** Horizontal offsets in dp for the locked chip's "no" shake. */
private val SHAKE_STEPS = floatArrayOf(-6f, 6f, -4f, 4f, 0f)
private const val SHAKE_STEP_MS = 45

/** A setting with a switch; the whole row toggles it, which also gives screen readers one control to announce. */
@Composable
private fun ToggleRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    // The row takes the tap, but the feedback is the switch's own round ripple: a full-bleed highlight on an
    // unpadded row reads as a hard-edged flash.
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = null,
                role = Role.Switch,
                onValueChange = onChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, interactionSource = interaction)
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
private fun SectionLabel(text: String, hint: String? = null) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.padding(start = 2.dp, top = 36.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        if (hint != null) {
            Text(" · $hint", style = MaterialTheme.typography.labelSmall, color = colors.outline)
        }
    }
}

@Composable
private fun OptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val borderColor by animateColorAsState(if (selected) colors.onSurface else colors.outlineVariant, label = "border")
    Surface(
        onClick = onClick,
        modifier = modifier.border(if (selected) 1.5.dp else 1.dp, borderColor, MaterialTheme.shapes.medium),
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainerLow,
    ) {
        Box(Modifier.padding(14.dp)) {
            Column {
                Icon(
                    icon, contentDescription = null,
                    tint = if (selected) colors.onSurface else colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            RadioDot(selected, Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun RadioDot(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .size(16.dp)
            .clip(CircleShape)
            .border(1.5.dp, if (selected) colors.onSurface else colors.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(colors.onSurface))
    }
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
