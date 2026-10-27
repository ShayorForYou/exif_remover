package com.rozin.exifremover

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.rozin.exifremover.core.Prefs
import com.rozin.exifremover.core.ThemeMode
import com.rozin.exifremover.core.shareIntent
import com.rozin.exifremover.core.sharedImageUris
import com.rozin.exifremover.ui.AppTheme
import com.rozin.exifremover.ui.CleanState
import com.rozin.exifremover.ui.CleanViewModel
import com.rozin.exifremover.ui.HomeScreen
import com.rozin.exifremover.ui.InspectScreen
import com.rozin.exifremover.ui.isDark
import com.rozin.exifremover.ui.ResultsScreen
import com.rozin.exifremover.ui.WorkingScreen

class MainActivity : ComponentActivity() {

    private val viewModel: CleanViewModel by viewModels()

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
        viewModel.open(uris, Prefs.keepOrientation(this))
    }
}

private const val MAX_PICK = 100

@Composable
private fun App(viewModel: CleanViewModel, themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    var keepOrientation by remember { mutableStateOf(Prefs.keepOrientation(context)) }
    var pendingUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    // Media-location access lets the details screen read real GPS values instead of Android's blanked
    // copy, which is what makes "replace original" safe when the user keeps their location. Asked once.
    val mediaLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        Prefs.setAskedMediaLocation(context)
        viewModel.open(pendingUris, keepOrientation)
        pendingUris = emptyList()
    }
    // The document picker (not the photo picker) so picked images map back to their originals,
    // which keeps "replace original" available at save time.
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val picked = uris.take(MAX_PICK)
        val askFirst = picked.size == 1 && !Prefs.askedMediaLocation(context) &&
            context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED
        if (askFirst) {
            pendingUris = picked
            mediaLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        } else {
            viewModel.open(picked, keepOrientation)
        }
    }
    val writeConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onWriteConsent(it.resultCode == Activity.RESULT_OK)
    }

    BackHandler(enabled = state !is CleanState.Idle && state !is CleanState.Working, onBack = viewModel::reset)

    AnimatedContent(
        targetState = state,
        contentKey = { it::class },
        transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)) togetherWith fadeOut() },
        label = "screen",
    ) { current ->
        when (current) {
            CleanState.Idle -> HomeScreen(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                keepOrientation = keepOrientation,
                onKeepOrientationChange = {
                    keepOrientation = it
                    Prefs.setKeepOrientation(context, it)
                },
                onChoosePhotos = { documentPicker.launch(arrayOf("image/*")) },
            )
            is CleanState.Working -> WorkingScreen(current.done, current.total)
            is CleanState.Inspecting -> InspectScreen(
                state = current,
                onBack = viewModel::reset,
                onToggleField = viewModel::toggleField,
                onToggleGroup = viewModel::toggleGroup,
                onSelect = viewModel::select,
                onRemoveSelected = viewModel::removeSelected,
                onRemoveAll = { viewModel.removeAll(keepOrientation) },
            )
            is CleanState.Done -> ResultsScreen(
                state = current,
                onClose = viewModel::reset,
                onSave = { mode -> viewModel.save(mode)?.let { writeConsent.launch(IntentSenderRequest.Builder(it).build()) } },
                onShare = { context.startActivity(context.shareIntent(current.cleaned)) },
            )
        }
    }
}
