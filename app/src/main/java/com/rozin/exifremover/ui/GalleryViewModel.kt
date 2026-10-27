package com.rozin.exifremover.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rozin.exifremover.core.Album
import com.rozin.exifremover.core.LibraryAccess
import com.rozin.exifremover.core.LibraryPhoto
import com.rozin.exifremover.core.MediaLibrary
import kotlinx.coroutines.Dispatchers
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
) {
    val atLimit get() = selected.size >= MAX_SELECTION

    /** Whether "Select all" would add anything in the current album. */
    val canSelectMore get() = !atLimit && visible.any { it.uri !in selected }

    /** How many more "Select all" would add, given the cap. */
    val selectableCount get() = minOf(visible.count { it.uri !in selected }, MAX_SELECTION - selected.size)

    companion object {
        /** Same cap as the system photo picker in "Save a copy" mode. */
        const val MAX_SELECTION = 100
    }
}

/** The in-app gallery used by "Replace original": it lists real MediaStore rows, which can be edited in place. */
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(GalleryState())
    val state = _state.asStateFlow()

    /** Reloads the library, e.g. on entering the gallery or after the user changes which photos are shared. */
    fun refresh() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val access = MediaLibrary.access(app)
            val photos = withContext(Dispatchers.IO) {
                if (access == LibraryAccess.NONE) emptyList() else runCatching { MediaLibrary.load(app) }.getOrDefault(emptyList())
            }
            _state.update { old ->
                val albums = MediaLibrary.albums(photos)
                val album = old.album?.takeIf { id -> albums.any { it.id == id } }
                val present = photos.mapTo(HashSet()) { it.uri }
                old.copy(
                    loading = false,
                    access = access,
                    photos = photos,
                    albums = albums,
                    album = album,
                    visible = photos.inAlbum(album),
                    selected = old.selected.filterTo(LinkedHashSet()) { it in present },
                )
            }
        }
    }

    fun selectAlbum(id: String?) = _state.update { it.copy(album = id, visible = it.photos.inAlbum(id)) }

    fun toggle(uri: Uri) = _state.update {
        when {
            uri in it.selected -> it.copy(selected = it.selected - uri)
            it.atLimit -> it
            else -> it.copy(selected = it.selected + uri)
        }
    }

    /** Adds every photo in the current album, newest first, up to the selection cap. */
    fun selectAllVisible() = _state.update {
        val room = GalleryState.MAX_SELECTION - it.selected.size
        val added = it.visible.asSequence().map { p -> p.uri }.filter { uri -> uri !in it.selected }.take(room)
        it.copy(selected = it.selected + added)
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    private fun List<LibraryPhoto>.inAlbum(id: String?) = if (id == null) this else filter { it.albumId == id }
}
