package com.rozin.exifremover.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rozin.exifremover.core.Album
import com.rozin.exifremover.core.LibraryAccess
import com.rozin.exifremover.core.LibraryPhoto
import com.rozin.exifremover.core.LocationIndex
import com.rozin.exifremover.core.MediaLibrary
import com.rozin.exifremover.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GalleryState(
    val loading: Boolean = true,
    val access: LibraryAccess = LibraryAccess.NONE,
    val photos: List<LibraryPhoto> = emptyList(),
    val albums: List<Album> = emptyList(),
    /** null = all photos. */
    val album: String? = null,
    val visible: List<LibraryPhoto> = emptyList(),
    val selected: Set<Uri> = emptySet(),
    /** Whether photos can be checked for a location at all (it takes media location access). */
    val locationAvailable: Boolean = false,
    /** Photos known to carry a GPS position; grows while the library is being checked. */
    val located: Set<Uri> = emptySet(),
    /** Photos checked for a location so far, out of [photos]. */
    val checked: Int = 0,
    /** Show only [located] photos of the current album. */
    val onlyLocated: Boolean = false,
    /** Located photos in the current album, for the filter chip. */
    val locatedInAlbum: Int = 0,
) {
    val atLimit get() = selected.size >= MAX_SELECTION

    val checking get() = locationAvailable && checked < photos.size

    /** Whether "Select all" would add anything in the current view. */
    val canSelectMore get() = !atLimit && visible.any { it.uri !in selected }

    /** How many more "Select all" would add, given the cap. */
    val selectableCount get() = minOf(visible.count { it.uri !in selected }, MAX_SELECTION - selected.size)

    /** Recomputes what's on screen after the album, filter or located set changed. */
    internal fun withVisible(): GalleryState {
        val inAlbum = if (album == null) photos else photos.filter { it.albumId == album }
        val locatedHere = if (locationAvailable) inAlbum.filter { it.uri in located } else emptyList()
        return copy(visible = if (onlyLocated) locatedHere else inAlbum, locatedInAlbum = locatedHere.size)
    }

    companion object {
        /**
         * Per-batch cap. Cleaning runs a few files at a time, so memory doesn't grow with it; what does is the
         * single MediaStore write-consent request (one Binder transaction) and the cleaned copies held in cache.
         */
        const val MAX_SELECTION = 500
    }
}

/** The in-app gallery: lists real MediaStore rows, which reveal GPS and can be edited in place. */
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val locations = LocationIndex(application)
    private val _state = MutableStateFlow(GalleryState(album = Prefs.galleryAlbum(application)))
    val state = _state.asStateFlow()
    private var scan: Job? = null

    /** Reloads the library, e.g. on entering the gallery or after the user changes which photos are shared. */
    fun refresh() {
        viewModelScope.launch {
            val access = MediaLibrary.access(app)
            val photos = withContext(Dispatchers.IO) {
                if (access == LibraryAccess.NONE) emptyList() else runCatching { MediaLibrary.load(app) }.getOrDefault(emptyList())
            }
            val albums = withContext(Dispatchers.Default) { MediaLibrary.albums(photos) }
            val present = photos.mapTo(HashSet()) { it.uri }
            _state.update { old ->
                old.copy(
                    loading = false,
                    access = access,
                    photos = photos,
                    albums = albums,
                    album = old.album?.takeIf { id -> albums.any { it.id == id } },
                    selected = old.selected.filterTo(LinkedHashSet()) { it in present },
                    locationAvailable = MediaLibrary.hasLocationAccess(app),
                    located = old.located.filterTo(HashSet()) { it in present },
                    checked = 0,
                ).withVisible()
            }
            checkLocations(photos)
        }
    }

    /**
     * Reads which photos have a location, newest first. Known answers come from disk at once; the rest trickle in.
     * Stopped when the gallery closes, so it never competes with cleaning; it resumes where it left off.
     */
    private fun checkLocations(photos: List<LibraryPhoto>) {
        scan?.cancel()
        if (!_state.value.locationAvailable || photos.isEmpty()) return
        scan = viewModelScope.launch {
            var first = true
            locations.scan(photos) { found, checked ->
                val replace = first
                first = false
                _state.update {
                    val located = if (replace) found.toHashSet() else if (found.isEmpty()) it.located else it.located + found
                    it.copy(located = located, checked = checked).withVisible()
                }
            }
        }
    }

    fun stopLocationCheck() {
        scan?.cancel()
        scan = null
    }

    fun selectAlbum(id: String?) {
        _state.update { it.copy(album = id).withVisible() }
        Prefs.setGalleryAlbum(app, id)
    }

    fun toggleOnlyLocated() = _state.update { it.copy(onlyLocated = !it.onlyLocated).withVisible() }

    fun toggle(uri: Uri) = _state.update {
        when {
            uri in it.selected -> it.copy(selected = it.selected - uri)
            it.atLimit -> it
            else -> it.copy(selected = it.selected + uri)
        }
    }

    /** Adds every photo in view (the album, or only its located photos), newest first, up to the selection cap. */
    fun selectAllVisible() = _state.update {
        val room = GalleryState.MAX_SELECTION - it.selected.size
        val added = it.visible.asSequence().map { p -> p.uri }.filter { uri -> uri !in it.selected }.take(room)
        it.copy(selected = it.selected + added)
    }

    /**
     * Drag-select: [base] is the selection when the drag began and [range] the photos swept over so far, added as a
     * block (up to the cap) or removed. Recomputing from [base] lets the range shrink again as the finger moves back.
     */
    fun applyRange(base: Set<Uri>, range: List<Uri>, select: Boolean) = _state.update {
        val selected = if (select) {
            val room = (GalleryState.MAX_SELECTION - base.size).coerceAtLeast(0)
            base + range.asSequence().filter { uri -> uri !in base }.take(room)
        } else {
            base - range.toSet()
        }
        it.copy(selected = selected)
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }
}
