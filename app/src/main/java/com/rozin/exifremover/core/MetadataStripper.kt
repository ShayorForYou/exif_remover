package com.rozin.exifremover.core

import java.io.OutputStream

enum class ImageFormat(val mimeType: String, val extension: String) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
}

class MalformedImageException(message: String) : Exception(message)

data class StripReport(val removedBytes: Int, val hadLocation: Boolean)

/**
 * Removes metadata by copying the container's structure and skipping metadata blocks.
 * Pixel data is never decoded or re-encoded, so this is lossless and runs at memory-copy speed.
 */
object MetadataStripper {

    fun detect(b: ByteArray): ImageFormat? = when {
        b.size >= 3 && b.u8(0) == 0xFF && b.u8(1) == SOI && b.u8(2) == 0xFF -> ImageFormat.JPEG
        b.startsWith(PNG_SIGNATURE, 0) -> ImageFormat.PNG
        b.startsWith(RIFF, 0) && b.startsWith(WEBP, 8) -> ImageFormat.WEBP
        else -> null
    }

    /**
     * Writes a metadata-free copy of [b] to [out].
     * With [keepOrientation], a 36-byte EXIF block holding only the rotation tag is kept for JPEGs,
     * so portrait photos don't come out sideways.
     */
    fun strip(b: ByteArray, format: ImageFormat, out: OutputStream, keepOrientation: Boolean): StripReport =
        when (format) {
            ImageFormat.JPEG -> stripJpeg(b, out, keepOrientation)
            ImageFormat.PNG -> stripPng(b, out)
            ImageFormat.WEBP -> stripWebp(b, out)
        }

    // JPEG: a sequence of FFxx marker segments; entropy-coded data follows each SOS.

    private fun stripJpeg(b: ByteArray, out: OutputStream, keepOrientation: Boolean): StripReport {
        var written = 0
        fun emit(from: Int, len: Int) {
            out.write(b, from, len)
            written += len
        }

        var hadLocation = false
        var orientationWritten = false
        emit(0, 2)
        var i = 2
        while (i < b.size) {
            if (b.u8(i) != 0xFF) throw MalformedImageException("Expected JPEG marker at $i")
            while (i + 1 < b.size && b.u8(i + 1) == 0xFF) i++ // fill bytes
            if (i + 1 >= b.size) break
            val marker = b.u8(i + 1)
            if (marker == EOI) {
                // Anything after EOI (MPF secondary images, motion-photo video, gain maps) is dropped:
                // those embedded images carry their own metadata.
                emit(i, 2)
                break
            }
            if (marker in RST0..RST7 || marker == TEM) {
                emit(i, 2)
                i += 2
                continue
            }
            if (i + 4 > b.size) throw MalformedImageException("Truncated segment at $i")
            val end = i + 2 + b.u16be(i + 2)
            if (end < i + 4 || end > b.size) throw MalformedImageException("Bad segment length at $i")
            val payload = i + 4

            when {
                marker == SOS -> {
                    // Copy the scan header plus entropy data up to the next real marker.
                    // Inside entropy data, FF is followed only by 00 (stuffing) or a restart marker.
                    var j = end
                    while (j + 1 < b.size) {
                        if (b.u8(j) == 0xFF) {
                            val next = b.u8(j + 1)
                            if (next != 0 && next !in RST0..RST7) break
                            j += 2
                        } else {
                            j++
                        }
                    }
                    if (j + 1 >= b.size) j = b.size
                    emit(i, j - i)
                    i = j
                }
                keepJpegSegment(b, marker, payload, end) -> {
                    emit(i, end - i)
                    i = end
                }
                else -> {
                    if (marker == APP1 && b.startsWith(EXIF_HEADER, payload)) {
                        val exif = readExif(b, payload + EXIF_HEADER.size, end)
                        hadLocation = hadLocation || exif.hasLocation
                        if (keepOrientation && !orientationWritten && exif.orientation in 2..8) {
                            val minimal = orientationOnlyExif(exif.orientation)
                            out.write(minimal)
                            written += minimal.size
                            orientationWritten = true
                        }
                    } else {
                        hadLocation = hadLocation || b.containsAscii(GPS_XMP_KEY, payload, end)
                    }
                    i = end
                }
            }
        }
        return StripReport(removedBytes = b.size - written, hadLocation = hadLocation)
    }

    private fun keepJpegSegment(b: ByteArray, marker: Int, payload: Int, end: Int): Boolean = when (marker) {
        APP0 -> !b.startsWith(JFXX, payload, end) // JFXX only carries thumbnails
        APP2 -> b.startsWith(ICC_PROFILE, payload, end) // colour profile: affects how pixels look
        APP14 -> b.startsWith(ADOBE, payload, end) // colour transform: needed to decode correctly
        in APP1..APP15, COM -> false
        else -> true
    }

    /** Big-endian TIFF with a single IFD entry: Orientation (0x0112, SHORT). */
    private fun orientationOnlyExif(orientation: Int): ByteArray = byteArrayOf(
        0xFF.toByte(), APP1.toByte(), 0, 34,
        'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
        'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8,
        0, 1,
        0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0,
        0, 0, 0, 0,
    )

    // PNG: 8-byte signature, then length/type/data/crc chunks.

    private fun stripPng(b: ByteArray, out: OutputStream): StripReport {
        var written = 8
        var hadLocation = false
        out.write(b, 0, 8)
        var i = 8
        while (i + 12 <= b.size) {
            val len = b.u32be(i)
            if (len > b.size - i - 12) throw MalformedImageException("Bad PNG chunk length at $i")
            val end = i + 12 + len.toInt()
            val type = String(b, i + 4, 4, Charsets.ISO_8859_1)
            if (type in PNG_METADATA_CHUNKS) {
                hadLocation = hadLocation ||
                    (type == "eXIf" && readExif(b, i + 8, end - 4).hasLocation) ||
                    b.containsAscii(GPS_XMP_KEY, i + 8, end - 4)
            } else {
                out.write(b, i, end - i)
                written += end - i
            }
            i = end
            if (type == "IEND") break
        }
        return StripReport(removedBytes = b.size - written, hadLocation = hadLocation)
    }

    // WebP: RIFF container of fourcc/size/data chunks, padded to even length.

    private fun stripWebp(b: ByteArray, out: OutputStream): StripReport {
        val riffEnd = minOf(8L + b.u32le(4), b.size.toLong()).toInt()
        val kept = ArrayList<IntRange>()
        var hadLocation = false
        var vp8x = -1
        var i = 12
        while (i + 8 <= riffEnd) {
            val size = b.u32le(i + 4)
            if (size > riffEnd - i - 8) throw MalformedImageException("Bad WebP chunk size at $i")
            val dataEnd = i + 8 + size.toInt()
            val end = minOf(dataEnd + (size.toInt() and 1), riffEnd)
            when {
                b.startsWith(WEBP_EXIF, i) -> {
                    val tiff = if (b.startsWith(EXIF_HEADER, i + 8)) i + 8 + EXIF_HEADER.size else i + 8
                    hadLocation = hadLocation || readExif(b, tiff, dataEnd).hasLocation
                }
                b.startsWith(WEBP_XMP, i) ->
                    hadLocation = hadLocation || b.containsAscii(GPS_XMP_KEY, i + 8, dataEnd)
                else -> {
                    if (b.startsWith(WEBP_VP8X, i)) vp8x = i
                    kept += i until end
                }
            }
            i = end
        }

        val body = kept.sumOf { it.last - it.first + 1 }
        out.write(RIFF)
        out.write(le32(4 + body))
        out.write(WEBP)
        for (range in kept) {
            if (range.first == vp8x && vp8x + 8 < b.size) {
                out.write(b, vp8x, 8)
                out.write(b.u8(vp8x + 8) and VP8X_METADATA_FLAGS.inv())
                out.write(b, vp8x + 9, range.last - vp8x - 8)
            } else {
                out.write(b, range.first, range.last - range.first + 1)
            }
        }
        return StripReport(removedBytes = b.size - (12 + body), hadLocation = hadLocation)
    }

    // EXIF (TIFF) parsing — only IFD0 is needed for orientation and the GPS pointer.

    private class ExifInfo(val orientation: Int, val hasLocation: Boolean)

    private fun readExif(b: ByteArray, tiff: Int, end: Int): ExifInfo {
        val none = ExifInfo(0, false)
        if (tiff < 0 || tiff + 8 > end) return none
        val littleEndian = when {
            b.u8(tiff) == 'I'.code && b.u8(tiff + 1) == 'I'.code -> true
            b.u8(tiff) == 'M'.code && b.u8(tiff + 1) == 'M'.code -> false
            else -> return none
        }
        fun u16(at: Int) = if (littleEndian) b.u8(at) or (b.u8(at + 1) shl 8) else b.u16be(at)
        val ifdOffset = if (littleEndian) b.u32le(tiff + 4) else b.u32be(tiff + 4)
        if (ifdOffset > end - tiff - 2) return none
        val ifd = tiff + ifdOffset.toInt()
        var orientation = 0
        var hasLocation = false
        for (k in 0 until u16(ifd)) {
            val entry = ifd + 2 + 12 * k
            if (entry + 12 > end) break
            when (u16(entry)) {
                TAG_ORIENTATION -> orientation = u16(entry + 8)
                TAG_GPS_IFD -> hasLocation = true
            }
        }
        return ExifInfo(orientation, hasLocation)
    }

    // Byte helpers

    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
    private fun ByteArray.u16be(i: Int) = (u8(i) shl 8) or u8(i + 1)
    private fun ByteArray.u32be(i: Int): Long =
        (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()
    private fun ByteArray.u32le(i: Int): Long =
        (u8(i + 3).toLong() shl 24) or (u8(i + 2).toLong() shl 16) or (u8(i + 1).toLong() shl 8) or u8(i).toLong()

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun ByteArray.startsWith(prefix: ByteArray, at: Int, limit: Int = size): Boolean {
        if (at < 0 || at + prefix.size > limit) return false
        for (k in prefix.indices) if (this[at + k] != prefix[k]) return false
        return true
    }

    private fun ByteArray.containsAscii(needle: ByteArray, from: Int, to: Int): Boolean {
        for (at in from..to - needle.size) if (startsWith(needle, at, to)) return true
        return false
    }

    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val SOS = 0xDA
    private const val TEM = 0x01
    private const val RST0 = 0xD0
    private const val RST7 = 0xD7
    private const val APP0 = 0xE0
    private const val APP1 = 0xE1
    private const val APP2 = 0xE2
    private const val APP14 = 0xEE
    private const val APP15 = 0xEF
    private const val COM = 0xFE
    private const val TAG_ORIENTATION = 0x0112
    private const val TAG_GPS_IFD = 0x8825
    private const val VP8X_METADATA_FLAGS = 0x0C // EXIF (0x08) | XMP (0x04)

    private val EXIF_HEADER = ascii("Exif\u0000\u0000")
    private val JFXX = ascii("JFXX\u0000")
    private val ICC_PROFILE = ascii("ICC_PROFILE\u0000")
    private val ADOBE = ascii("Adobe")
    private val GPS_XMP_KEY = ascii("GPSLatitude")
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    // caBX: C2PA content credentials (creator, tool and edit history).
    private val PNG_METADATA_CHUNKS = setOf("eXIf", "tEXt", "zTXt", "iTXt", "tIME", "caBX")
    private val RIFF = ascii("RIFF")
    private val WEBP = ascii("WEBP")
    private val WEBP_VP8X = ascii("VP8X")
    private val WEBP_EXIF = ascii("EXIF")
    private val WEBP_XMP = ascii("XMP ")
}
