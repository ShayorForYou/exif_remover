package com.rozin.exifremover

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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

    // "Save a copy": the system photo picker. Its URIs are read-only stand-ins, fine for making copies.
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) {
        viewModel.open(it, SaveMode.COPY, keepOrientation)
    }
    // "Replace" fallback when photo access is denied: document URIs map back to the real MediaStore rows.
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        viewModel.open(it, SaveMode.REPLACE, keepOrientation)
    }
    val writeConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onWriteConsent(it.resultCode == Activity.RESULT_OK)
    }
    val photoRequest = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    // "Replace": the in-app gallery lists real MediaStore rows, which can be edited in place after consent.
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
        if (MediaLibrary.access(context) == LibraryAccess.NONE) {
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
                onBack = ::closeGallery,
                onAlbum = gallery::selectAlbum,
                onToggle = { gallery.toggle(it.uri) },
                onSelectAll = gallery::selectAllVisible,
                onClear = gallery::clearSelection,
                onAddMore = { libraryAccess.launch(MediaLibrary.permissions()) },
                onClean = {
                    val uris = galleryState.selected.toList()
                    closeGallery()
                    viewModel.open(uris, SaveMode.REPLACE, keepOrientation)
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
                onChoosePhotos = {
                    when (saveMode) {
                        SaveMode.COPY -> photoPicker.launch(photoRequest)
                        SaveMode.REPLACE -> openGallery()
                    }
                },
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
