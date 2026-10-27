package com.rozin.exifremover.core

import androidx.exifinterface.media.ExifInterface
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ExifGroup(val title: String) {
    LOCATION("Location"),
    PEOPLE("People & text"),
    DEVICE("Device"),
    DATES("Date & time"),
    SETTINGS("Camera settings"),
    IMAGE("Image"),
}

/**
 * One user-facing row, backed by one or more raw EXIF tags (e.g. "Coordinates" is four GPS tags).
 * [sensitive] fields are pre-selected for removal.
 */
data class ExifField(
    val id: String,
    val group: ExifGroup,
    val label: String,
    val value: String,
    val tags: List<String>,
    val sensitive: Boolean,
)

object ExifCatalog {

    private class Spec(
        val group: ExifGroup,
        val label: String,
        val tags: List<String>,
        val sensitive: Boolean,
        val format: (ExifInterface) -> String? = { exif -> tags.mapNotNull { exif.getAttribute(it) }.joinToString(" ") },
    )

    fun read(exif: ExifInterface): List<ExifField> = specs(exif).mapNotNull { spec ->
        if (spec.tags.none { exif.getAttribute(it) != null }) return@mapNotNull null
        val value = runCatching { spec.format(exif) }.getOrNull()?.trim().orEmpty().ifEmpty { "Present" }
        ExifField(spec.label, spec.group, spec.label, value.take(MAX_VALUE_LENGTH), spec.tags, spec.sensitive)
    }

    private fun specs(exif: ExifInterface): List<Spec> {
        val xmp = exif.getAttribute(ExifInterface.TAG_XMP).orEmpty()
        val xmpHasLocation = "GPSLatitude" in xmp
        return listOf(
            Spec(ExifGroup.LOCATION, "Coordinates", GPS_COORDINATES, sensitive = true) { e ->
                e.latLong?.let { (lat, lng) -> formatLatLng(lat, lng) } ?: "Present (values hidden by Android)"
            },
            Spec(ExifGroup.LOCATION, "Altitude", GPS_ALTITUDE, sensitive = true) { e ->
                "${e.getAltitude(0.0).roundToInt()} m"
            },
            Spec(ExifGroup.LOCATION, "Facing direction", GPS_DIRECTION, sensitive = true) { e ->
                "${e.getAttributeDouble(ExifInterface.TAG_GPS_IMG_DIRECTION, 0.0).roundToInt()}°"
            },
            Spec(ExifGroup.LOCATION, "Speed", GPS_SPEED, sensitive = true),
            Spec(ExifGroup.LOCATION, "GPS time", GPS_TIME, sensitive = true),
            Spec(ExifGroup.LOCATION, "Other GPS data", GPS_OTHER, sensitive = true) { "Present" },
            // XMP is a separate block that phones often fill with a copy of the GPS position.
            Spec(
                if (xmpHasLocation) ExifGroup.LOCATION else ExifGroup.PEOPLE,
                if (xmpHasLocation) "XMP block (contains location)" else "XMP block",
                listOf(ExifInterface.TAG_XMP),
                sensitive = true,
            ) { "${xmp.length} characters of extra metadata" },

            Spec(ExifGroup.PEOPLE, "Artist", listOf(ExifInterface.TAG_ARTIST), sensitive = true),
            Spec(ExifGroup.PEOPLE, "Camera owner", listOf(ExifInterface.TAG_CAMERA_OWNER_NAME), sensitive = true),
            Spec(ExifGroup.PEOPLE, "Copyright", listOf(ExifInterface.TAG_COPYRIGHT), sensitive = true),
            Spec(ExifGroup.PEOPLE, "Description", listOf(ExifInterface.TAG_IMAGE_DESCRIPTION), sensitive = true),
            Spec(ExifGroup.PEOPLE, "User comment", listOf(ExifInterface.TAG_USER_COMMENT), sensitive = true),

            Spec(ExifGroup.DEVICE, "Camera", listOf(ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL), sensitive = false),
            Spec(ExifGroup.DEVICE, "Lens", listOf(ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL), sensitive = false),
            Spec(ExifGroup.DEVICE, "Software", listOf(ExifInterface.TAG_SOFTWARE), sensitive = false),
            Spec(
                ExifGroup.DEVICE, "Serial numbers",
                listOf(ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_LENS_SERIAL_NUMBER), sensitive = true,
            ),
            Spec(ExifGroup.DEVICE, "Unique image ID", listOf(ExifInterface.TAG_IMAGE_UNIQUE_ID), sensitive = true),
            Spec(ExifGroup.DEVICE, "Maker notes", listOf(ExifInterface.TAG_MAKER_NOTE), sensitive = true) {
                "Hidden manufacturer data"
            },

            Spec(ExifGroup.DATES, "Taken", DATE_TAKEN, sensitive = false) { e -> dateWithOffset(e, DATE_TAKEN) },
            Spec(ExifGroup.DATES, "Digitized", DATE_DIGITIZED, sensitive = false) { e -> dateWithOffset(e, DATE_DIGITIZED) },
            Spec(ExifGroup.DATES, "Modified", DATE_MODIFIED, sensitive = false) { e -> dateWithOffset(e, DATE_MODIFIED) },

            Spec(ExifGroup.SETTINGS, "Exposure", listOf(ExifInterface.TAG_EXPOSURE_TIME), sensitive = false) { e ->
                val t = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                if (t in 0.0..0.5 && t > 0) "1/${(1 / t).roundToInt()} s" else "${fmt(t)} s"
            },
            Spec(ExifGroup.SETTINGS, "Aperture", listOf(ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_APERTURE_VALUE), sensitive = false) { e ->
                e.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f/${fmt(e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0))}" } ?: "Present"
            },
            Spec(ExifGroup.SETTINGS, "ISO", listOf(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY), sensitive = false),
            Spec(
                ExifGroup.SETTINGS, "Focal length",
                listOf(ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM), sensitive = false,
            ) { e -> "${fmt(e.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0))} mm" },
            Spec(ExifGroup.SETTINGS, "Flash", listOf(ExifInterface.TAG_FLASH), sensitive = false) { e ->
                if (e.getAttributeInt(ExifInterface.TAG_FLASH, 0) and 1 == 1) "Fired" else "Did not fire"
            },
            Spec(ExifGroup.SETTINGS, "White balance", listOf(ExifInterface.TAG_WHITE_BALANCE), sensitive = false) { e ->
                if (e.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, 0) == 0) "Auto" else "Manual"
            },

            Spec(ExifGroup.IMAGE, "Orientation", listOf(ExifInterface.TAG_ORIENTATION), sensitive = false) { e ->
                "${e.rotationDegrees}°" + if (e.isFlipped) ", mirrored" else ""
            },
        )
    }

    private fun dateWithOffset(exif: ExifInterface, tags: List<String>): String? =
        exif.getAttribute(tags[0])?.let { date ->
            val offset = exif.getAttribute(tags.last())
            date.replaceFirst(':', '-').replaceFirst(':', '-') + (offset?.let { " (UTC$it)" } ?: "")
        }

    private fun formatLatLng(lat: Double, lng: Double): String {
        val ns = if (lat >= 0) "N" else "S"
        val ew = if (lng >= 0) "E" else "W"
        return String.format(Locale.US, "%.5f° %s, %.5f° %s", abs(lat), ns, abs(lng), ew)
    }

    private fun fmt(v: Double) = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v)

    private const val MAX_VALUE_LENGTH = 120

    private val GPS_COORDINATES = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
    )
    private val GPS_ALTITUDE = listOf(ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF)
    private val GPS_DIRECTION = listOf(ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF)
    private val GPS_SPEED = listOf(ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF)
    private val GPS_TIME = listOf(ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_TIMESTAMP)
    private val GPS_OTHER = listOf(
        ExifInterface.TAG_GPS_VERSION_ID, ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_SATELLITES,
        ExifInterface.TAG_GPS_STATUS, ExifInterface.TAG_GPS_MEASURE_MODE, ExifInterface.TAG_GPS_DOP,
        ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_TRACK_REF, ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DEST_BEARING, ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE, ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_AREA_INFORMATION, ExifInterface.TAG_GPS_DIFFERENTIAL,
        ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
    )
    private val DATE_TAKEN = listOf(
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
    )
    private val DATE_DIGITIZED = listOf(
        ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
    )
    private val DATE_MODIFIED = listOf(ExifInterface.TAG_DATETIME, ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_OFFSET_TIME)
}
