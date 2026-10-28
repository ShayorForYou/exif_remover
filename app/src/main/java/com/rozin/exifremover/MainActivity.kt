package com.rozin.exifremover

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Color
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.rozin.exifremover.core.LibraryAccess
import com.rozin.exifremover.core.MediaLibrary
import com.rozin.exifremover.core.Prefs
import com.rozin.exifremover.core.SaveMode
import com.rozin.exifremover.core.ThemeMode
import com.rozin.exifremover.core.shareIntent
import com.rozin.exifremover.core.sharedImageUris
import com.rozin.exifremover.ui.AppTheme
import com.rozin.exifremover.ui.CleanState
import com.rozin.exifremover.ui.CleanViewModel
import com.rozin.exifremover.ui.GalleryScreen
import com.rozin.exifremover.ui.GalleryViewModel
import com.rozin.exifremover.ui.HomeScreen
import com.rozin.exifremover.ui.InspectScreen
import com.rozin.exifremover.ui.isDark
import com.rozin.exifremover.ui.ResultsScreen
import com.rozin.exifremover.ui.WorkingScreen

class MainActivity : ComponentActivity() {

    private val viewModel: CleanViewModel by viewModels()
    private val gallery: GalleryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) splashScreen.setOnExitAnimationListener(::exitSplash)
        if (savedInstanceState == null) openShared(intent)
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
            .withEndAction(splash::remove)
            .start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openShared(intent)
    }

    /**
     * "Review & clean" share target: images shared from another app open straight into the details
     * screen (one image) or bulk results (several), just like picking them here.
     */
    private fun openShared(intent: Intent) {
        val uris = intent.sharedImageUris().take(MAX_PICK)
        if (uris.isEmpty()) return
        viewModel.reset()
        viewModel.open(uris, Prefs.saveMode(this), Prefs.keepOrientation(this))
    }
}

private const val MAX_PICK = 100
private const val SPLASH_EXIT_MS = 250L

/** The in-app gallery sits in front of the home screen, before any cleaning state exists. */
private object GalleryRoute

@Composable
private fun App(
    viewModel: CleanViewModel,
    gallery: GalleryViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val galleryState by gallery.state.collectAsState()
    var saveMode by remember { mutableStateOf(Prefs.saveMode(context)) }
    var keepOrientation by remember { mutableStateOf(Prefs.keepOrientation(context)) }
    var picking by rememberSaveable { mutableStateOf(false) }

    // Fallback when photo access is denied: document URIs map back to the real MediaStore rows.
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        viewModel.open(it, saveMode, keepOrientation)
    }
    val writeConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onWriteConsent(it.resultCode == Activity.RESULT_OK)
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
        gallery.clearSelection()
    }

    val screen: Any = if (picking && state is CleanState.Idle) GalleryRoute else state
    BackHandler(enabled = screen === GalleryRoute) { closeGallery() }
    BackHandler(enabled = state !is CleanState.Idle && state !is CleanState.Working, onBack = viewModel::reset)

    AnimatedContent(
        targetState = screen,
        contentKey = { if (it === GalleryRoute) "gallery" else it::class },
        transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)) togetherWith fadeOut() },
        label = "screen",
    ) { current ->
        when (current) {
            GalleryRoute -> GalleryScreen(
                state = galleryState,
                saveMode = saveMode,
                onBack = ::closeGallery,
                onAlbum = gallery::selectAlbum,
                onToggle = { gallery.toggle(it.uri) },
                onSelectAll = gallery::selectAllVisible,
                onClear = gallery::clearSelection,
                onAddMore = { libraryAccess.launch(MediaLibrary.permissions()) },
                onClean = {
                    val uris = galleryState.selected.toList()
                    closeGallery()
                    viewModel.open(uris, saveMode, keepOrientation)
                },
            )
            CleanState.Idle -> HomeScreen(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                saveMode = saveMode,
                onSaveModeChange = {
                    saveMode = it
                    Prefs.setSaveMode(context, it)
                },
                keepOrientation = keepOrientation,
                onKeepOrientationChange = {
                    keepOrientation = it
                    Prefs.setKeepOrientation(context, it)
                },
                onChoosePhotos = ::openGallery,
            )
            is CleanState.Working -> WorkingScreen(current.done, current.total)
            is CleanState.Inspecting -> InspectScreen(
                state = current,
                onBack = viewModel::reset,
                onRemoveAll = { viewModel.removeAll(keepOrientation) },
            )
            is CleanState.Done -> ResultsScreen(
                state = current,
                onClose = viewModel::reset,
                onSave = { viewModel.save()?.let { writeConsent.launch(IntentSenderRequest.Builder(it).build()) } },
                onShare = { context.startActivity(context.shareIntent(current.cleaned)) },
            )
        }
    }
}
