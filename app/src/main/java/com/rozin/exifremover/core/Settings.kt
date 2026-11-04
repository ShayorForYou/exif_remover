package com.rozin.exifremover.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

enum class SaveMode { COPY, REPLACE }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The cleaning choices made on the home screen. One value, so they travel together from the UI to the cleaner. */
data class Settings(
    val saveMode: SaveMode = SaveMode.COPY,
    val strip: StripOptions = StripOptions(),
    /** Replace mode: move originals to the system Trash (restorable for 30 days) rather than overwriting them. */
    val trashOriginals: Boolean = true,
)

object Prefs {
    private const val KEY_KEEP_ORIENTATION = "keep_orientation"
    private const val KEY_KEEP_DATE = "keep_date"
    private const val KEY_STRIP_LEVEL = "strip_level"
    private const val KEY_SAVE_MODE = "save_mode"
    private const val KEY_TRASH_ORIGINALS = "trash_originals"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_GALLERY_ALBUM = "gallery_album"

    private fun Context.prefs() = getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun settings(context: Context): Settings {
        val prefs = context.prefs()
        val defaults = Settings()
        return Settings(
            saveMode = prefs.enum(KEY_SAVE_MODE, defaults.saveMode),
            strip = StripOptions(
                level = prefs.enum(KEY_STRIP_LEVEL, defaults.strip.level),
                keepOrientation = prefs.getBoolean(KEY_KEEP_ORIENTATION, defaults.strip.keepOrientation),
                keepDate = prefs.getBoolean(KEY_KEEP_DATE, defaults.strip.keepDate),
            ),
            trashOriginals = prefs.getBoolean(KEY_TRASH_ORIGINALS, defaults.trashOriginals),
        )
    }

    fun setSettings(context: Context, settings: Settings) = context.prefs().edit {
        putString(KEY_SAVE_MODE, settings.saveMode.name)
        putString(KEY_STRIP_LEVEL, settings.strip.level.name)
        putBoolean(KEY_KEEP_ORIENTATION, settings.strip.keepOrientation)
        putBoolean(KEY_KEEP_DATE, settings.strip.keepDate)
        putBoolean(KEY_TRASH_ORIGINALS, settings.trashOriginals)
    }

    fun themeMode(context: Context): ThemeMode = context.prefs().enum(KEY_THEME_MODE, ThemeMode.SYSTEM)

    fun setThemeMode(context: Context, mode: ThemeMode) = context.prefs().edit { putString(KEY_THEME_MODE, mode.name) }

    /** The album last open in the in-app gallery; null for all photos. */
    fun galleryAlbum(context: Context): String? = context.prefs().getString(KEY_GALLERY_ALBUM, null)

    fun setGalleryAlbum(context: Context, id: String?) = context.prefs().edit { putString(KEY_GALLERY_ALBUM, id) }

    private inline fun <reified T : Enum<T>> SharedPreferences.enum(key: String, default: T): T =
        getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default
}
