package com.rozin.exifremover.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rozin.exifremover.core.CleanResult
import com.rozin.exifremover.core.Comparison
import com.rozin.exifremover.core.ExifField
import com.rozin.exifremover.core.ExifGroup
import com.rozin.exifremover.core.ImageCleaner
import com.rozin.exifremover.core.Inspection
import com.rozin.exifremover.core.SaveOutcome
import com.rozin.exifremover.core.SavePlan
import com.rozin.exifremover.core.Settings
import com.rozin.exifremover.core.abandon
import com.rozin.exifremover.core.execute
import com.rozin.exifremover.core.planSave
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface CleanState {
    data object Idle : CleanState

    data class Working(val done: Int, val total: Int) : CleanState

    data class Inspecting(val inspection: Inspection, val settings: Settings) : CleanState {
        val groups: Map<ExifGroup, List<ExifField>> by lazy { inspection.fields.groupBy { it.group } }
    }

    data class Done(
        val results: List<CleanResult>,
        val settings: Settings,
        val saving: Boolean = false,
        val saved: SaveOutcome? = null,
        /** The user declined Android's consent dialog, so nothing was written. */
        val consentDenied: Boolean = false,
        /** Failed photos are being cleaned again. */
        val retrying: Boolean = false,
        /** The tile the user tapped, shown in a sheet. */
        val detail: Detail? = null,
    ) : CleanState {
        // Read on every recomposition of a grid that can hold 500 tiles, so worked out once per state.
        val cleaned by lazy { results.filterIsInstance<CleanResult.Cleaned>() }
        val failed by lazy { results.filterIsInstance<CleanResult.Failed>() }
    }
}

/** One results tile, opened. [comparison] is null while the original is being read again. */
data class Detail(val result: CleanResult, val comparison: Comparison? = null)

class CleanViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val cleaner = ImageCleaner(application)
    private val _state = MutableStateFlow<CleanState>(CleanState.Idle)
    val state = _state.asStateFlow()

    private val consents = Channel<IntentSender>(Channel.BUFFERED)

    /** System consent dialogs to launch; report each outcome to [onConsent]. */
    val consentRequests = consents.receiveAsFlow()

    private val finishedEvents = Channel<CleanState.Done>(Channel.CONFLATED)

    /** A clean just finished: a cue for a haptic tick, or a summary if the user switched away meanwhile. */
    val finished = finishedEvents.receiveAsFlow()

    /** A save waiting on its consent dialog. */
    private var pendingPlan: SavePlan? = null
    private var saveJob: Job? = null

    /** The running clean or inspect; cancelled on [reset] so a stale batch can't land on top of a newer one. */
    private var job: Job? = null
    private var detailJob: Job? = null

    /** Set once the cleaned copies go to another app, which may read them long after we're done with them. */
    private var shared = false

    init {
        // Shared copies from earlier sessions are only swept by age; don't wait for the next clean to do it.
        viewModelScope.launch(Dispatchers.IO) { cleaner.pruneOldOutputs() }
    }

    /** One photo opens the details screen; several are cleaned in bulk straight away. */
    fun open(uris: List<Uri>, settings: Settings) {
        when (uris.size) {
            0 -> Unit
            1 -> inspect(uris[0], settings)
            else -> clean(uris, settings)
        }
    }

    fun clean(uris: List<Uri>, settings: Settings) {
        if (uris.isEmpty() || _state.value is CleanState.Working) return
        _state.value = CleanState.Working(0, uris.size)
        job = viewModelScope.launch {
            val results = cleaner.clean(uris, settings.strip) { done ->
                // Photos already mid-flight still report in after a cancel; don't count them toward a newer batch.
                if (isActive) _state.update { if (it is CleanState.Working) it.copy(done = maxOf(it.done, done)) else it }
            }
            val done = CleanState.Done(results, settings)
            _state.value = done
            finishedEvents.trySend(done)
        }
    }

    private fun inspect(uri: Uri, settings: Settings) {
        _state.value = CleanState.Working(0, 1)
        job = viewModelScope.launch {
            val inspection = runCatching { cleaner.inspect(uri) }
            // runCatching also swallows cancellation, which must not turn into a "couldn't read" result.
            ensureActive()
            _state.value = inspection.fold(
                onSuccess = { CleanState.Inspecting(it, settings) },
                onFailure = { CleanState.Done(listOf(CleanResult.Failed(uri, it.message ?: "Couldn't read photo")), settings) },
            )
        }
    }

    fun removeAll() {
        val inspecting = _state.value as? CleanState.Inspecting ?: return
        _state.value = CleanState.Idle
        clean(listOf(inspecting.inspection.source), inspecting.settings)
    }

    /**
     * Saves the results. Finding originals and staging copies runs off the main thread with the button spinning;
     * if a consent dialog is needed, it arrives on [consentRequests].
     */
    fun save() {
        val done = _state.value as? CleanState.Done ?: return
        if (done.saving || done.saved != null) return
        _state.value = done.copy(saving = true, consentDenied = false)
        saveJob = viewModelScope.launch {
            // Planning may stage copies in the library, so it always runs to the end; if the user left the results
            // meanwhile ([reset] cancels this), those copies are taken back instead of asking for consent.
            val plan = withContext(Dispatchers.IO + NonCancellable) { app.planSave(done.cleaned, done.settings) }
            if (!isActive) {
                withContext(Dispatchers.IO + NonCancellable) { app.abandon(plan) }
                return@launch
            }
            val consent = plan.consent
            if (consent == null) {
                finishSave(plan)
            } else {
                pendingPlan = plan
                consents.send(consent)
            }
        }
    }

    fun onConsent(granted: Boolean) {
        val plan = pendingPlan ?: return
        pendingPlan = null
        if (granted) {
            saveJob = viewModelScope.launch { finishSave(plan) }
        } else {
            viewModelScope.launch(Dispatchers.IO) { app.abandon(plan) }
            _state.update { if (it is CleanState.Done) it.copy(saving = false, consentDenied = true) else it }
        }
    }

    /** The way out after declining consent: keep the originals and save cleaned copies instead. */
    fun saveAsCopies() {
        val done = _state.value as? CleanState.Done ?: return
        if (done.saving || done.saved != null) return
        _state.value = done.copy(saving = true)
        saveJob = viewModelScope.launch { finishSave(SavePlan(done.cleaned)) }
    }

    private suspend fun finishSave(plan: SavePlan) {
        val outcome = withContext(Dispatchers.IO) { app.execute(plan) }
        _state.update { if (it is CleanState.Done) it.copy(saving = false, saved = outcome) else it }
    }

    /** Cleans the failed photos again (e.g. after a cloud photo finished downloading), in place in the results. */
    fun retryFailed() {
        val done = _state.value as? CleanState.Done ?: return
        if (done.failed.isEmpty() || done.retrying || done.saving || done.saved != null) return
        _state.value = done.copy(retrying = true)
        job = viewModelScope.launch {
            val fresh = cleaner.clean(done.failed.map { it.source }, done.settings.strip) {}.associateBy { it.source }
            _state.update { s ->
                if (s !is CleanState.Done) s else s.copy(results = s.results.map { fresh[it.source] ?: it }, retrying = false)
            }
        }
    }

    /** Opens a results tile; for a cleaned photo, reads what was removed and kept. */
    fun showDetail(result: CleanResult) {
        _state.update { if (it is CleanState.Done) it.copy(detail = Detail(result)) else it }
        detailJob?.cancel()
        if (result !is CleanResult.Cleaned) return
        detailJob = viewModelScope.launch {
            val comparison = runCatching { cleaner.compare(result) }.getOrDefault(Comparison(emptyList(), emptyList()))
            ensureActive()
            _state.update {
                if (it is CleanState.Done && it.detail?.result == result) it.copy(detail = Detail(result, comparison)) else it
            }
        }
    }

    fun hideDetail() {
        detailJob?.cancel()
        _state.update { if (it is CleanState.Done) it.copy(detail = null) else it }
    }

    /** Call before handing the cleaned copies to the share sheet, so they're kept for the receiving app. */
    fun markShared() {
        shared = true
    }

    fun reset() {
        pendingPlan?.let { plan -> viewModelScope.launch(Dispatchers.IO) { app.abandon(plan) } }
        pendingPlan = null
        // A save still being planned must not end in a consent dialog once the results are gone. A running write
        // isn't stopped by this (it's blocking I/O); discarding the outputs below still waits for it to finish.
        saveJob?.cancel()
        job?.cancel()
        job = null
        detailJob?.cancel()
        discardOutputs()
        _state.value = CleanState.Idle
    }

    override fun onCleared() = discardOutputs()

    /** Leaving the results: unshared copies are now unreachable, so free their storage once any save is done. */
    private fun discardOutputs() {
        val done = _state.value as? CleanState.Done
        if (done != null && !shared) {
            val files = done.cleaned.map { it.file }
            evictThumbnails(files)
            cleaner.discard(files, after = saveJob)
        }
        shared = false
        saveJob = null
    }
}
