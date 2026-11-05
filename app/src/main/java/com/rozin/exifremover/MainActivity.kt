package com.rozin.exifremover

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Color
import android.view.HapticFeedbackConstants
import android.window.SplashScreenView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.rozin.exifremover.core.LibraryAccess
import com.rozin.exifremover.core.MediaLibrary
import com.rozin.exifremover.core.Prefs
import com.rozin.exifremover.core.Settings
import com.rozin.exifremover.core.ThemeMode
import com.rozin.exifremover.core.shareIntent
import com.rozin.exifremover.core.sharedImageUris
import com.rozin.exifremover.core.viewIntent
import com.rozin.exifremover.ui.AppTheme
import com.rozin.exifremover.ui.CleanState
import com.rozin.exifremover.ui.CleanViewModel
import com.rozin.exifremover.ui.GalleryScreen
import com.rozin.exifremover.ui.GalleryState
import com.rozin.exifremover.ui.GalleryViewModel
import com.rozin.exifremover.ui.HomeScreen
import com.rozin.exifremover.ui.InspectScreen
import com.rozin.exifremover.ui.isDark
import com.rozin.exifremover.ui.ResultsScreen
import com.rozin.exifremover.ui.WorkingScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val viewModel: CleanViewModel by viewModels()
    private val gallery: GalleryViewModel by viewModels()

    /** False while the system splash is still on screen; the first real screen is only composed after it's gone. */
    private val splashDone = mutableStateOf(true)

    /** Set by the "Choose photos" shortcut and the Quick Settings tile; the UI opens the gallery once it's up. */
    private val galleryRequested = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && savedInstanceState == null) {
            splashDone.value = false
            splashScreen.setOnExitAnimationListener(::exitSplash)
        }
        if (savedInstanceState == null) handle(intent)
        lifecycleScope.launch { viewModel.finished.collect(::onFinished) }
        setContent {
            var themeMode by remember { mutableStateOf(Prefs.themeMode(this)) }
            val dark = themeMode.isDark()
            // Bar icons follow the app's theme, not the device's, when the user has overridden it.
            DisposableEffect(dark) {
                val bars = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose {}
            }
            AppTheme(themeMode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(
                        splashDone = splashDone.value,
                        onSplashTimeout = { splashDone.value = true },
                        galleryRequested = galleryRequested.value,
                        onGalleryRequestHandled = { galleryRequested.value = false },
                        viewModel = viewModel,
                        gallery = gallery,
                        themeMode = themeMode,
                        onThemeModeChange = {
                            themeMode = it
                            Prefs.setThemeMode(this, it)
                        },
                    )
                }
            }
        }
    }

    /**
     * Lets the splash icon animation (res/drawable/splash_icon.xml, under a second) play out instead of being cut off
     * when the app is ready first, then zooms the icon away as the splash fades into the app.
     * Home is composed only once the splash is removed: building it while the exit animation runs drops frames.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun exitSplash(splash: SplashScreenView) {
        val start = splash.iconAnimationStart?.toEpochMilli()
        val duration = splash.iconAnimationDuration?.toMillis() ?: 0L
        // The start time is on the uptime clock. With animations turned off in system settings, don't wait at all.
        val remaining = if (start != null && ValueAnimator.areAnimatorsEnabled()) {
            (start + duration - SystemClock.uptimeMillis()).coerceIn(0L, duration)
        } else {
            0L
        }
        splash.iconView?.animate()
            ?.scaleX(1.15f)?.scaleY(1.15f)
            ?.setStartDelay(remaining)?.setDuration(SPLASH_EXIT_MS)
            ?.start()
        splash.animate()
            .alpha(0f)
            .setStartDelay(remaining).setDuration(SPLASH_EXIT_MS)
            .withEndAction {
                splash.remove()
                splashDone.value = true
            }
            .start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        when (intent.action) {
            ACTION_CHOOSE -> galleryRequested.value = true
            ACTION_LATEST_PHOTO -> openLatest(screenshots = false)
            ACTION_LATEST_SCREENSHOT -> openLatest(screenshots = true)
            else -> openShared(intent)
        }
    }

    /**
     * "Review & clean" share target: images shared from another app open straight into the details
     * screen (one image) or bulk results (several), just like picking them here.
     */
    private fun openShared(intent: Intent) {
        val uris = intent.sharedImageUris().take(MAX_PICK)
        if (uris.isEmpty()) return
        viewModel.reset()
        viewModel.open(uris, Prefs.settings(this))
    }

    /** Launcher shortcuts: the newest photo or screenshot, straight into its details. Without access, ask first. */
    private fun openLatest(screenshots: Boolean) {
        if (MediaLibrary.access(this) == LibraryAccess.NONE) {
            galleryRequested.value = true
            return
        }
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) { MediaLibrary.latest(this@MainActivity, screenshots) }
            if (uri == null) {
                Toast.makeText(this@MainActivity, if (screenshots) "No screenshots found" else "No photos found", Toast.LENGTH_SHORT).show()
                return@launch
            }
            viewModel.reset()
            viewModel.open(listOf(uri), Prefs.settings(this@MainActivity))
        }
    }

    /** A tick when the results land in front of the user; a summary when they've switched to another app meanwhile. */
    private fun onFinished(done: CleanState.Done) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            return
        }
        val cleaned = done.cleaned.size
        val summary = buildString {
            append(if (cleaned == 1) "1 photo cleaned" else "$cleaned photos cleaned")
            if (done.failed.isNotEmpty()) append(" · ${done.failed.size} failed")
            append(". Open EXIF Remover to save.")
        }
        Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val ACTION_CHOOSE = "com.rozin.exifremover.action.CHOOSE"
        const val ACTION_LATEST_PHOTO = "com.rozin.exifremover.action.LATEST_PHOTO"
        const val ACTION_LATEST_SCREENSHOT = "com.rozin.exifremover.action.LATEST_SCREENSHOT"
    }
}

private const val MAX_PICK = GalleryState.MAX_SELECTION
private const val SPLASH_EXIT_MS = 250L
/** Some launches (e.g. from another app's share sheet) show no splash, so its exit callback never comes. */
private const val SPLASH_FALLBACK_MS = 2000L

/** Screen change: the old screen fades out, then the new one fades in. */
private const val SCREEN_OUT_MS = 90
private const val SCREEN_IN_MS = 210

/** Blank background under the system splash, so nothing heavy is composed while it animates out. */
private object SplashRoute

/** The in-app gallery sits in front of the home screen, before any cleaning state exists. */
private object GalleryRoute

@Composable
private fun App(
    splashDone: Boolean,
    onSplashTimeout: () -> Unit,
    galleryRequested: Boolean,
    onGalleryRequestHandled: () -> Unit,
    viewModel: CleanViewModel,
    gallery: GalleryViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val galleryState by gallery.state.collectAsState()
    var settings by remember { mutableStateOf(Prefs.settings(context)) }
    var picking by rememberSaveable { mutableStateOf(false) }

    // Fallback when photo access is denied: document URIs map back to the real MediaStore rows.
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        viewModel.open(it, settings)
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onConsent(it.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(Unit) {
        viewModel.consentRequests.collect { consent.launch(IntentSenderRequest.Builder(it).build()) }
    }
    // Both modes use the in-app gallery, not the system photo picker: the picker strips GPS from what it hands
    // out, so the details screen could never show the location. Gallery items are real MediaStore rows, which
    // reveal it (with media location access) and can be edited in place after consent.
    // Re-requesting from inside the gallery is how Android 14+ users add more photos to limited access.
    val libraryAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (MediaLibrary.access(context) == LibraryAccess.NONE) {
            picking = false
            Toast.makeText(context, "No photo access. Choose files instead.", Toast.LENGTH_SHORT).show()
            documentPicker.launch(arrayOf("image/*"))
        } else {
            gallery.refresh()
            picking = true
        }
    }
    fun openGallery() {
        // Also re-ask when only media location is missing (e.g. photo access was granted by an older build).
        // Once the user has declined twice, Android answers immediately without a dialog.
        if (MediaLibrary.access(context) == LibraryAccess.NONE || !MediaLibrary.hasLocationAccess(context)) {
            libraryAccess.launch(MediaLibrary.permissions())
        } else {
            gallery.refresh()
            picking = true
        }
    }
    fun closeGallery() {
        picking = false
        gallery.stopLocationCheck()
        gallery.clearSelection()
    }

    if (!splashDone) {
        LaunchedEffect(Unit) {
            delay(SPLASH_FALLBACK_MS)
            onSplashTimeout()
        }
    }
    val currentOpenGallery by rememberUpdatedState(::openGallery)
    LaunchedEffect(galleryRequested, splashDone) {
        if (galleryRequested && splashDone) {
            onGalleryRequestHandled()
            viewModel.reset()
            currentOpenGallery()
        }
    }

    val screen: Any = when {
        !splashDone -> SplashRoute
        picking && state is CleanState.Idle -> GalleryRoute
        else -> state
    }
    BackHandler(enabled = screen === GalleryRoute) { closeGallery() }
    // Back from the progress screen cancels the batch, same as its Cancel button.
    BackHandler(enabled = state !is CleanState.Idle, onBack = viewModel::reset)

    AnimatedContent(
        targetState = screen,
        contentKey = {
            when (it) {
                SplashRoute -> "splash"
                GalleryRoute -> "gallery"
                else -> it::class
            }
        },
        // Fade through: the old screen is gone before the new one appears. Fading both at once (with the default
        // springs the outgoing screen lingered) left two layers of text and buttons on top of each other.
        transitionSpec = {
            (fadeIn(tween(SCREEN_IN_MS, delayMillis = SCREEN_OUT_MS)) +
                scaleIn(tween(SCREEN_IN_MS, delayMillis = SCREEN_OUT_MS), initialScale = 0.98f)) togetherWith
                fadeOut(tween(SCREEN_OUT_MS))
        },
        label = "screen",
    ) { current ->
        when (current) {
            SplashRoute -> Unit
            GalleryRoute -> GalleryScreen(
                state = galleryState,
                saveMode = settings.saveMode,
                onBack = ::closeGallery,
                onAlbum = gallery::selectAlbum,
                onToggleLocated = gallery::toggleOnlyLocated,
                onToggle = { gallery.toggle(it.uri) },
                onDragSelect = gallery::applyRange,
                onSelectAll = gallery::selectAllVisible,
                onClear = gallery::clearSelection,
                onAddMore = { libraryAccess.launch(MediaLibrary.permissions()) },
                onClean = {
                    val uris = galleryState.selected.toList()
                    closeGallery()
                    viewModel.open(uris, settings)
                },
            )
            CleanState.Idle -> HomeScreen(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                settings = settings,
                onSettingsChange = { changed: Settings ->
                    settings = changed
                    Prefs.setSettings(context, changed)
                },
                onChoosePhotos = ::openGallery,
            )
            is CleanState.Working -> WorkingScreen(current.done, current.total, onCancel = viewModel::reset)
            is CleanState.Inspecting -> InspectScreen(
                state = current,
                onBack = viewModel::reset,
                onRemoveAll = viewModel::removeAll,
            )
            is CleanState.Done -> ResultsScreen(
                state = current,
                onClose = viewModel::reset,
                onSave = viewModel::save,
                onShare = {
                    viewModel.markShared()
                    context.startActivity(context.shareIntent(current.cleaned))
                },
                onSaveCopies = viewModel::saveAsCopies,
                onRetry = viewModel::retryFailed,
                onOpen = viewModel::showDetail,
                onCloseDetail = viewModel::hideDetail,
                onView = { uri ->
                    runCatching { context.startActivity(context.viewIntent(uri)) }
                        .onFailure { Toast.makeText(context, "No app can open photos", Toast.LENGTH_SHORT).show() }
                },
            )
        }
    }
}
