package com.rozin.exifremover

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import android.graphics.Color
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.rozin.exifremover.core.Prefs
import com.rozin.exifremover.core.SaveMode
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
        // In-place replace is only safe with unredacted reads (see the mediaLocation request below).
        val canReplace = Prefs.saveMode(this) == SaveMode.REPLACE &&
            checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        viewModel.reset()
        viewModel.open(uris, if (canReplace) SaveMode.REPLACE else SaveMode.COPY, Prefs.keepOrientation(this))
    }
}

private const val MAX_PICK = 100

@Composable
private fun App(viewModel: CleanViewModel, themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    var saveMode by remember { mutableStateOf(Prefs.saveMode(context)) }
    var keepOrientation by remember { mutableStateOf(Prefs.keepOrientation(context)) }

    // The photo picker hands out read-only copies, so replacing originals needs the document picker,
    // whose URIs map back to the real MediaStore rows.
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) {
        viewModel.open(it, SaveMode.COPY, keepOrientation)
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        viewModel.open(it, SaveMode.REPLACE, keepOrientation)
    }
    val writeConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onWriteConsent(it.resultCode == Activity.RESULT_OK)
    }
    val photoRequest = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    // Without it we only ever see GPS-redacted bytes, and writing those back would erase locations
    // the user chose to keep. So no permission, no in-place replace.
    val mediaLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            documentPicker.launch(arrayOf("image/*"))
        } else {
            saveMode = SaveMode.COPY
            Prefs.setSaveMode(context, SaveMode.COPY)
            Toast.makeText(context, "Replacing needs media location access. Saving copies instead.", Toast.LENGTH_LONG).show()
            photoPicker.launch(photoRequest)
        }
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
                        SaveMode.REPLACE -> mediaLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                    }
                },
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
                onSave = { viewModel.save()?.let { writeConsent.launch(IntentSenderRequest.Builder(it).build()) } },
                onShare = { context.startActivity(context.shareIntent(current.cleaned)) },
            )
        }
    }
}
