package com.rozin.exifremover.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rozin.exifremover.core.CleanResult
import com.rozin.exifremover.core.ExifField
import com.rozin.exifremover.core.ExifGroup
import com.rozin.exifremover.core.ImageCleaner
import com.rozin.exifremover.core.Inspection
import com.rozin.exifremover.core.SaveMode
import com.rozin.exifremover.core.SaveOutcome
import com.rozin.exifremover.core.overwriteOriginals
import com.rozin.exifremover.core.replaceTargets
import com.rozin.exifremover.core.saveToGallery
import com.rozin.exifremover.core.writeRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface CleanState {
    data object Idle : CleanState

    data class Working(val done: Int, val total: Int) : CleanState

    data class Inspecting(
        val inspection: Inspection,
        val selected: Set<String>,
    ) : CleanState {
        val groups: Map<ExifGroup, List<ExifField>> get() = inspection.fields.groupBy { it.group }
        val selectedTags: Set<String> get() = inspection.fields.filter { it.id in selected }.flatMap { it.tags }.toSet()
    }

    data class Done(
        val results: List<CleanResult>,
        /** How many originals can be overwritten in place; 0 disables "Replace original". */
        val replaceable: Int,
        val saving: Boolean = false,
        val saved: SaveOutcome? = null,
    ) : CleanState {
        val cleaned get() = results.filterIsInstance<CleanResult.Cleaned>()
        val failed get() = results.filterIsInstance<CleanResult.Failed>()
    }
}

class CleanViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val cleaner = ImageCleaner(application)
    private val _state = MutableStateFlow<CleanState>(CleanState.Idle)
    val state = _state.asStateFlow()

    /** Originals awaiting the system write-consent dialog. */
    private var pendingReplace: Map<CleanResult.Cleaned, Uri> = emptyMap()

    /** One photo opens the details screen; several are cleaned in bulk straight away. */
    fun open(uris: List<Uri>, keepOrientation: Boolean) {
        when (uris.size) {
            0 -> Unit
            1 -> inspect(uris[0])
            else -> clean(uris, keepOrientation)
        }
    }

    fun clean(uris: List<Uri>, keepOrientation: Boolean) {
        if (uris.isEmpty() || _state.value is CleanState.Working) return
        _state.value = CleanState.Working(0, uris.size)
        viewModelScope.launch {
            val results = cleaner.clean(uris, keepOrientation) { done ->
                _state.update { if (it is CleanState.Working) it.copy(done = maxOf(it.done, done)) else it }
            }
            _state.value = done(results)
        }
    }

    private suspend fun done(results: List<CleanResult>): CleanState.Done {
        val replaceable = withContext(Dispatchers.IO) {
            app.replaceTargets(results.filterIsInstance<CleanResult.Cleaned>()).size
        }
        return CleanState.Done(results, replaceable)
    }

    private fun inspect(uri: Uri) {
        _state.value = CleanState.Working(0, 1)
        viewModelScope.launch {
            _state.value = runCatching { cleaner.inspect(uri) }.fold(
                onSuccess = { inspection ->
                    val preselected = inspection.fields.filter { it.sensitive }.map { it.id }.toSet()
                    CleanState.Inspecting(inspection, preselected)
                },
                onFailure = { done(listOf(CleanResult.Failed(uri, it.message ?: "Couldn't read photo"))) },
            )
        }
    }

    fun toggleField(id: String) = _state.update {
        if (it !is CleanState.Inspecting) return@update it
        it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
    }

    fun select(ids: Set<String>) = _state.update {
        if (it is CleanState.Inspecting) it.copy(selected = ids) else it
    }

    /** Selects the whole group, or clears it if it was already fully selected. */
    fun toggleGroup(group: ExifGroup) = _state.update {
        if (it !is CleanState.Inspecting) return@update it
        val ids = it.groups[group].orEmpty().map { f -> f.id }.toSet()
        it.copy(selected = if (it.selected.containsAll(ids)) it.selected - ids else it.selected + ids)
    }

    fun removeSelected() {
        val inspecting = _state.value as? CleanState.Inspecting ?: return
        val tags = inspecting.selectedTags
        if (tags.isEmpty()) return
        _state.value = CleanState.Working(0, 1)
        viewModelScope.launch {
            _state.value = done(listOf(cleaner.removeTags(inspecting.inspection.source, tags)))
        }
    }

    fun removeAll(keepOrientation: Boolean) {
        val inspecting = _state.value as? CleanState.Inspecting ?: return
        _state.value = CleanState.Idle
        clean(listOf(inspecting.inspection.source), keepOrientation)
    }

    /**
     * Saves as copies, or replaces the originals where possible (the rest become copies).
     * Returns the consent dialog to launch when replacing; report its outcome to [onWriteConsent].
     */
    fun save(mode: SaveMode): IntentSender? {
        val done = _state.value as? CleanState.Done ?: return null
        if (done.saving || done.saved != null) return null
        if (mode == SaveMode.REPLACE) {
            val targets = app.replaceTargets(done.cleaned)
            if (targets.isNotEmpty()) {
                pendingReplace = targets
                return app.writeRequest(targets.values)
            }
        }
        finishSave(emptyMap())
        return null
    }

    fun onWriteConsent(granted: Boolean) {
        val targets = pendingReplace
        pendingReplace = emptyMap()
        if (granted) finishSave(targets)
    }

    private fun finishSave(replace: Map<CleanResult.Cleaned, Uri>) {
        val done = _state.value as? CleanState.Done ?: return
        _state.value = done.copy(saving = true)
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val replaced = app.overwriteOriginals(replace)
                // Anything that couldn't be replaced in place still gets saved, as a copy.
                val copied = app.saveToGallery(done.cleaned - replace.keys)
                SaveOutcome(replaced, copied, done.cleaned.size - replaced - copied)
            }
            _state.update { if (it is CleanState.Done) it.copy(saving = false, saved = outcome) else it }
        }
    }

    fun reset() {
        pendingReplace = emptyMap()
        _state.value = CleanState.Idle
    }
}
