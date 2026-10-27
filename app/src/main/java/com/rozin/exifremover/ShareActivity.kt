package com.rozin.exifremover

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rozin.exifremover.core.CleanResult
import com.rozin.exifremover.core.Prefs
import com.rozin.exifremover.core.shareIntent
import com.rozin.exifremover.core.sharedImageUris
import com.rozin.exifremover.ui.AppTheme
import com.rozin.exifremover.ui.CleanState
import com.rozin.exifremover.ui.CleanViewModel

/**
 * "Strip & share" target in the system share sheet: cleans whatever was shared, then immediately
 * re-opens the share sheet with the clean copies. Originals are never forwarded.
 */
class ShareActivity : ComponentActivity() {

    private val viewModel: CleanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val uris = intent.sharedImageUris()
            if (uris.isEmpty()) {
                Toast.makeText(this, "No images received", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            viewModel.clean(uris, Prefs.keepOrientation(this))
        }

        setContent {
            AppTheme(Prefs.themeMode(this)) {
                val state by viewModel.state.collectAsState()
                val done = state as? CleanState.Done
                LaunchedEffect(done) {
                    // Happy path: no dialog interaction at all, straight back to the share sheet.
                    if (done != null && done.failed.isEmpty()) forward(done.cleaned)
                }
                when {
                    done == null -> AlertDialog(
                        onDismissRequest = ::finish,
                        title = { Text("Removing metadata") },
                        text = {
                            val working = state as? CleanState.Working
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LinearProgressIndicator(
                                    progress = { working?.let { it.done / it.total.toFloat() } ?: 0f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                working?.let { Text("${it.done} of ${it.total}") }
                            }
                        },
                        confirmButton = {},
                        dismissButton = { TextButton(onClick = ::finish) { Text("Cancel") } },
                    )
                    done.failed.isNotEmpty() -> AlertDialog(
                        onDismissRequest = ::finish,
                        title = { Text("Some photos couldn't be cleaned") },
                        text = {
                            Text(
                                "${done.failed.size} of ${done.results.size} won't be shared " +
                                    "(${done.failed.first().reason})."
                            )
                        },
                        confirmButton = {
                            if (done.cleaned.isNotEmpty()) {
                                TextButton(onClick = { forward(done.cleaned) }) { Text("Share ${done.cleaned.size}") }
                            }
                        },
                        dismissButton = { TextButton(onClick = ::finish) { Text("Cancel") } },
                    )
                }
            }
        }
    }

    private fun forward(cleaned: List<CleanResult.Cleaned>) {
        if (isFinishing) return
        if (cleaned.isNotEmpty()) startActivity(shareIntent(cleaned))
        finish()
    }
}
