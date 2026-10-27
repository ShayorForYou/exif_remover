package com.rozin.exifremover.core

import java.io.OutputStream

enum class ImageFormat(val mimeType: String, val extension: String) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
    HEIC("image/heic", "heic"),
    HEIF("image/heif", "heif"),
    AVIF("image/avif", "avif"),
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
        else -> heifBrands(b).let { brands ->
            when {
                brands.any { it in AVIF_BRANDS } -> ImageFormat.AVIF
                brands.any { it in HEIC_BRANDS } -> ImageFormat.HEIC
                brands.any { it in HEIF_BRANDS } -> ImageFormat.HEIF
                else -> null
            }
        }
    }

    /**
     * Writes a metadata-free copy of [b] to [out].
     * With [keepOrientation], a 36-byte EXIF block holding only the rotation tag is kept for JPEGs,
     * so portrait photos don't come out sideways. HEIF/AVIF keep rotation in their own box regardless.
     */
    fun strip(b: ByteArray, format: ImageFormat, out: OutputStream, keepOrientation: Boolean): StripReport =
        when (format) {
            ImageFormat.JPEG -> stripJpeg(b, out, keepOrientation)
            ImageFormat.PNG -> stripPng(b, out)
            ImageFormat.WEBP -> stripWebp(b, out)
            ImageFormat.HEIC, ImageFormat.HEIF, ImageFormat.AVIF -> stripHeif(b, out)
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

    // HEIF / AVIF (ISO-BMFF): EXIF and XMP are "items" whose bytes the 'iloc' box points at. Removing
    // them would mean rewriting every offset in the file, so instead their bytes are overwritten in place
    // with an empty block. Layout, size and image data stay identical; rotation lives in 'irot', untouched.

    private class Box(val type: String, val body: Int, val end: Int)

    private enum class HeifMeta { EXIF, XMP }

    private fun stripHeif(b: ByteArray, out: OutputStream): StripReport {
        val meta = findBox(b, 0, b.size, "meta") ?: throw MalformedImageException("HEIF without a meta box")
        val children = meta.body + 4 // FullBox version + flags
        val kinds = heifMetadataItems(b, children, meta.end)
        val extents = heifItemExtents(b, children, meta.end, kinds.keys)

        val clean = b.copyOf()
        var removed = 0
        var hadLocation = false
        for ((id, kind) in kinds) {
            val ranges = extents[id] ?: continue
            val payload = ranges.fold(ByteArray(0)) { acc, r -> acc + b.copyOfRange(r.first, r.last + 1) }
            hadLocation = hadLocation || when (kind) {
                // Exif item: 4-byte offset to the TIFF header, then (usually "Exif\0\0" and) the TIFF data.
                HeifMeta.EXIF -> payload.size >= 4 && payload.u32be(0) < payload.size &&
                    readExif(payload, 4 + payload.u32be(0).toInt(), payload.size).hasLocation
                HeifMeta.XMP -> payload.containsAscii(GPS_XMP_KEY, 0, payload.size)
            }
            val blank = if (kind == HeifMeta.EXIF) emptyHeifExif(payload.size) else emptyXmp(payload.size)
            var k = 0
            for (r in ranges) for (at in r) clean[at] = blank[k++]
            removed += payload.size
        }
        out.write(clean)
        return StripReport(removedBytes = removed, hadLocation = hadLocation)
    }

    /** Item IDs of the EXIF and XMP items, from 'iinf'. */
    private fun heifMetadataItems(b: ByteArray, from: Int, to: Int): Map<Int, HeifMeta> {
        val iinf = findBox(b, from, to, "iinf") ?: return emptyMap()
        val first = iinf.body + 4 + if (b.u8(iinf.body) == 0) 2 else 4 // FullBox header + entry_count
        val items = HashMap<Int, HeifMeta>()
        for (infe in boxes(b, first, iinf.end)) {
            if (infe.type != "infe") continue
            val version = b.u8(infe.body)
            if (version < 2) continue // v0/v1 entries can't describe Exif or mime items
            var p = infe.body + 4
            val id = if (version == 2) b.u16be(p).also { p += 2 } else b.u32be(p).toInt().also { p += 4 }
            p += 2 // item_protection_index
            if (p + 4 > infe.end) continue
            when (String(b, p, 4, Charsets.ISO_8859_1)) {
                "Exif" -> items[id] = HeifMeta.EXIF
                "mime" -> {
                    val nameEnd = b.indexOfZero(p + 4, infe.end)
                    val typeEnd = b.indexOfZero(nameEnd + 1, infe.end)
                    val contentType = if (typeEnd > nameEnd) String(b, nameEnd + 1, typeEnd - nameEnd - 1, Charsets.ISO_8859_1) else ""
                    if ("rdf+xml" in contentType) items[id] = HeifMeta.XMP
                }
            }
        }
        return items
    }

    /** Absolute byte ranges of each wanted item, from 'iloc'. */
    private fun heifItemExtents(b: ByteArray, from: Int, to: Int, wanted: Set<Int>): Map<Int, List<IntRange>> {
        if (wanted.isEmpty()) return emptyMap()
        val iloc = findBox(b, from, to, "iloc") ?: throw MalformedImageException("HEIF without iloc")
        val idat = findBox(b, from, to, "idat")
        val version = b.u8(iloc.body)
        var p = iloc.body + 4
        fun read(size: Int): Long {
            if (p + size > iloc.end) throw MalformedImageException("Truncated iloc")
            val v = when (size) {
                0 -> 0L
                2 -> b.u16be(p).toLong()
                4 -> b.u32be(p)
                8 -> if (b.u32be(p) == 0L) b.u32be(p + 4) else throw MalformedImageException("HEIF offset over 4 GB")
                else -> throw MalformedImageException("Bad iloc field size $size")
            }
            p += size
            return v
        }
        val sizes = read(2).toInt()
        val offsetSize = sizes shr 12
        val lengthSize = (sizes shr 8) and 0x0F
        val baseSize = (sizes shr 4) and 0x0F
        val indexSize = if (version == 1 || version == 2) sizes and 0x0F else 0
        val count = read(if (version < 2) 2 else 4).toInt()

        val result = HashMap<Int, List<IntRange>>()
        repeat(count) {
            val id = read(if (version < 2) 2 else 4).toInt()
            val method = if (version == 1 || version == 2) read(2).toInt() and 0x0F else 0
            read(2) // data_reference_index
            val base = read(baseSize)
            val ranges = ArrayList<IntRange>()
            repeat(read(2).toInt()) {
                if (indexSize > 0) read(indexSize)
                val offset = read(offsetSize)
                val length = read(lengthSize)
                val origin = when (method) {
                    0 -> 0L
                    1 -> idat?.body?.toLong() ?: throw MalformedImageException("iloc refers to a missing idat")
                    else -> -1L // built from other items: no bytes of its own to blank
                }
                if (origin >= 0 && length > 0) {
                    val start = origin + base + offset
                    if (start + length > b.size) throw MalformedImageException("HEIF item outside the file")
                    ranges += start.toInt() until (start + length).toInt()
                }
            }
            if (id in wanted && ranges.isNotEmpty()) result[id] = ranges
        }
        return result
    }

    private fun boxes(b: ByteArray, from: Int, to: Int): Sequence<Box> = sequence {
        var i = from
        while (i + 8 <= to) {
            var size = b.u32be(i)
            var header = 8
            when (size) {
                0L -> size = (to - i).toLong() // extends to the end of the parent
                1L -> {
                    if (i + 16 > to || b.u32be(i + 8) != 0L) throw MalformedImageException("Box too large at $i")
                    size = b.u32be(i + 12)
                    header = 16
                }
            }
            if (size < header || size > to - i) throw MalformedImageException("Bad box size at $i")
            yield(Box(String(b, i + 4, 4, Charsets.ISO_8859_1), i + header, i + size.toInt()))
            i += size.toInt()
        }
    }

    private fun findBox(b: ByteArray, from: Int, to: Int, type: String) = boxes(b, from, to).firstOrNull { it.type == type }

    private fun heifBrands(b: ByteArray): Set<String> {
        if (b.size < 16 || !b.startsWith(FTYP, 4)) return emptySet()
        val end = minOf(b.u32be(0), b.size.toLong()).toInt()
        return (sequenceOf(8) + generateSequence(16) { it + 4 }.takeWhile { it + 4 <= end })
            .map { String(b, it, 4, Charsets.ISO_8859_1) }
            .toSet()
    }

    /** A valid Exif item holding an empty TIFF (no tags), zero-padded to [size]. */
    private fun emptyHeifExif(size: Int): ByteArray {
        val minimal = byteArrayOf(0, 0, 0, 0, 'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0)
        return if (size >= minimal.size) minimal.copyOf(size) else ByteArray(size)
    }

    /** An empty XMP packet, space-padded to [size] (XMP explicitly allows whitespace padding). */
    private fun emptyXmp(size: Int): ByteArray {
        val head = ascii("<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>")
        val tail = ascii("<?xpacket end=\"w\"?>")
        val out = ByteArray(size) { ' '.code.toByte() }
        if (size >= head.size + tail.size) {
            head.copyInto(out)
            tail.copyInto(out, size - tail.size)
        }
        return out
    }

    private fun ByteArray.indexOfZero(from: Int, to: Int): Int {
        for (i in from until to) if (this[i].toInt() == 0) return i
        return to
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
    private val FTYP = ascii("ftyp")
    private val HEIC_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs")
    private val HEIF_BRANDS = setOf("mif1", "msf1", "mif2")
    private val AVIF_BRANDS = setOf("avif", "avis")
}
