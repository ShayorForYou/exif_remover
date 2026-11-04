package com.rozin.exifremover.core

import java.io.OutputStream
import java.util.zip.CRC32

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

enum class StripLevel {
    /** Everything goes, except what the keep flags in [StripOptions] ask for. */
    ALL,

    /** Only GPS (and blocks that can carry it, like XMP) goes; camera details and dates stay. */
    LOCATION,
}

/** What survives cleaning. The keep flags only matter for [StripLevel.ALL]; [StripLevel.LOCATION] keeps both anyway. */
data class StripOptions(
    val level: StripLevel = StripLevel.ALL,
    /** JPEG/PNG/WebP: keep the rotation tag so portrait photos don't come out sideways. HEIF keeps 'irot' regardless. */
    val keepOrientation: Boolean = true,
    /** Keep when the photo was taken, so galleries still sort it where it belongs instead of under "today". */
    val keepDate: Boolean = true,
)

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
     * Writes a cleaned copy of [b] to [out].
     * With [StripLevel.ALL], what [options] keeps (rotation, date taken) is rewritten as a tiny fresh EXIF block, so
     * nothing else from the original EXIF can tag along. With [StripLevel.LOCATION], the EXIF block is kept as is
     * minus its GPS directory, which is zeroed out in place.
     */
    fun strip(b: ByteArray, format: ImageFormat, out: OutputStream, options: StripOptions): StripReport =
        when (format) {
            ImageFormat.JPEG -> stripJpeg(b, out, options)
            ImageFormat.PNG -> stripPng(b, out, options)
            ImageFormat.WEBP -> stripWebp(b, out, options)
            ImageFormat.HEIC, ImageFormat.HEIF, ImageFormat.AVIF -> stripHeif(b, out, options)
        }

    /**
     * The EXIF to write in place of the original TIFF at [tiff]: the original minus GPS, a minimal rebuild, or null
     * for none. [blanked] receives how many bytes of the original were zeroed, which count as removed.
     */
    private fun cleanTiff(
        b: ByteArray, tiff: Int, end: Int, info: ExifInfo, options: StripOptions, orientation: Boolean, blanked: (Int) -> Unit,
    ): ByteArray? = when (options.level) {
        StripLevel.LOCATION -> b.copyOfRange(tiff, end).also { blanked(removeGps(it, 0, it.size)) }
        StripLevel.ALL -> minimalTiff(
            orientation = if (orientation && options.keepOrientation) info.orientation else 0,
            date = if (options.keepDate) info.date else null,
            offset = if (options.keepDate) info.offset else null,
        )
    }

    // JPEG: a sequence of FFxx marker segments; entropy-coded data follows each SOS.

    private fun stripJpeg(b: ByteArray, out: OutputStream, options: StripOptions): StripReport {
        var written = 0
        var blanked = 0
        fun emit(from: Int, len: Int) {
            out.write(b, from, len)
            written += len
        }

        var hadLocation = false
        var exifWritten = false
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
                        val tiff = payload + EXIF_HEADER.size
                        val exif = readExif(b, tiff, end)
                        hadLocation = hadLocation || exif.hasLocation
                        if (!exifWritten) {
                            cleanTiff(b, tiff, end, exif, options, orientation = true) { blanked += it }?.let {
                                val segment = jpegExifSegment(it)
                                out.write(segment)
                                written += segment.size
                                exifWritten = true
                            }
                        }
                    } else {
                        hadLocation = hadLocation || b.containsAscii(GPS_XMP_KEY, payload, end)
                    }
                    i = end
                }
            }
        }
        return StripReport(removedBytes = b.size - written + blanked, hadLocation = hadLocation)
    }

    private fun keepJpegSegment(b: ByteArray, marker: Int, payload: Int, end: Int): Boolean = when (marker) {
        APP0 -> !b.startsWith(JFXX, payload, end) // JFXX only carries thumbnails
        APP2 -> b.startsWith(ICC_PROFILE, payload, end) // colour profile: affects how pixels look
        APP14 -> b.startsWith(ADOBE, payload, end) // colour transform: needed to decode correctly
        in APP1..APP15, COM -> false
        else -> true
    }

    private fun jpegExifSegment(tiff: ByteArray): ByteArray {
        val length = 2 + EXIF_HEADER.size + tiff.size
        if (length > 0xFFFF) throw MalformedImageException("EXIF block too large")
        return byteArrayOf(0xFF.toByte(), APP1.toByte(), (length shr 8).toByte(), length.toByte()) + EXIF_HEADER + tiff
    }

    /**
     * Big-endian TIFF built from scratch, holding at most Orientation (IFD0) and DateTimeOriginal + OffsetTimeOriginal
     * (Exif IFD). Null when there's nothing to keep. Orientation alone is 26 bytes; with the date, about 90.
     */
    private fun minimalTiff(orientation: Int, date: String?, offset: String?): ByteArray? {
        val keepOrientation = orientation in 2..8
        val time = offset.takeIf { date != null }
        if (!keepOrientation && date == null) return null

        val ifd0Entries = (if (keepOrientation) 1 else 0) + (if (date != null) 1 else 0)
        val exifIfd = 8 + ifdSize(ifd0Entries)
        val dataStart = exifIfd + ifdSize(if (time != null) 2 else 1)
        val size = if (date == null) exifIfd else dataStart + DATE_LENGTH + (if (time != null) OFFSET_LENGTH else 0)

        val t = ByteArray(size)
        var p = 0
        fun put16(v: Int) { t[p++] = (v shr 8).toByte(); t[p++] = v.toByte() }
        fun put32(v: Int) { put16(v ushr 16); put16(v and 0xFFFF) }
        fun entry(tag: Int, type: Int, count: Int, value: Int) { put16(tag); put16(type); put32(count); put32(value) }

        put16(0x4D4D); put16(42); put32(8) // "MM", magic, IFD0 at 8
        put16(ifd0Entries)
        if (keepOrientation) entry(TAG_ORIENTATION, TYPE_SHORT, 1, orientation shl 16) // SHORT sits in the high half
        if (date != null) entry(TAG_EXIF_IFD, TYPE_LONG, 1, exifIfd)
        put32(0) // no IFD1
        if (date != null) {
            put16(if (time != null) 2 else 1)
            entry(TAG_DATETIME_ORIGINAL, TYPE_ASCII, DATE_LENGTH, dataStart)
            if (time != null) entry(TAG_OFFSET_TIME_ORIGINAL, TYPE_ASCII, OFFSET_LENGTH, dataStart + DATE_LENGTH)
            put32(0)
            ascii(date).copyInto(t, p) // NUL terminators are already there
            if (time != null) ascii(time).copyInto(t, p + DATE_LENGTH)
        }
        return t
    }

    private fun ifdSize(entries: Int) = 2 + 12 * entries + 4

    // PNG: 8-byte signature, then length/type/data/crc chunks.

    private fun stripPng(b: ByteArray, out: OutputStream, options: StripOptions): StripReport {
        var written = 8
        var blanked = 0
        var hadLocation = false
        var exifWritten = false
        out.write(b, 0, 8)
        var i = 8
        while (i + 12 <= b.size) {
            val len = b.u32be(i)
            if (len > b.size - i - 12) throw MalformedImageException("Bad PNG chunk length at $i")
            val end = i + 12 + len.toInt()
            val type = String(b, i + 4, 4, Charsets.ISO_8859_1)
            if (type == "eXIf") {
                val exif = readExif(b, i + 8, end - 4)
                hadLocation = hadLocation || exif.hasLocation
                if (!exifWritten) {
                    // Rewritten at the same spot, so it stays ahead of IDAT as the spec requires.
                    cleanTiff(b, i + 8, end - 4, exif, options, orientation = true) { blanked += it }?.let {
                        val chunk = pngChunk(PNG_EXIF, it)
                        out.write(chunk)
                        written += chunk.size
                        exifWritten = true
                    }
                }
            } else if (!keepPngChunk(type)) {
                hadLocation = hadLocation || b.containsAscii(GPS_XMP_KEY, i + 8, end - 4)
            } else {
                out.write(b, i, end - i)
                written += end - i
            }
            i = end
            if (type == "IEND") break
        }
        return StripReport(removedBytes = b.size - written + blanked, hadLocation = hadLocation)
    }

    private fun pngChunk(type: ByteArray, data: ByteArray): ByteArray {
        val crc = CRC32().apply { update(type); update(data) }.value.toInt()
        return be32(data.size) + type + data + be32(crc)
    }

    /** Critical chunks (uppercase first letter) are required; of the ancillary ones only those that affect rendering stay. */
    private fun keepPngChunk(type: String) = type[0].isUpperCase() || type in PNG_RENDERING_CHUNKS

    // WebP: RIFF container of fourcc/size/data chunks, padded to even length.

    /** A slice of the input, or bytes made up for the output; lets WebP image data be written without copying it. */
    private sealed interface Piece {
        val size: Int

        class Slice(val from: Int, override val size: Int) : Piece
        class Bytes(val bytes: ByteArray) : Piece {
            override val size get() = bytes.size
        }
    }

    private fun stripWebp(b: ByteArray, out: OutputStream, options: StripOptions): StripReport {
        val riffEnd = minOf(8L + b.u32le(4), b.size.toLong()).toInt()
        val kept = ArrayList<Piece>()
        var blanked = 0
        var hadLocation = false
        var hasExif = false
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
                    val exif = readExif(b, tiff, dataEnd)
                    hadLocation = hadLocation || exif.hasLocation
                    if (!hasExif) {
                        cleanTiff(b, tiff, dataEnd, exif, options, orientation = true) { blanked += it }?.let {
                            kept += Piece.Bytes(webpChunk(WEBP_EXIF, it))
                            hasExif = true
                        }
                    }
                }
                String(b, i, 4, Charsets.ISO_8859_1) in WEBP_IMAGE_CHUNKS -> {
                    if (b.startsWith(WEBP_VP8X, i)) vp8x = i
                    kept += Piece.Slice(i, end - i)
                }
                // XMP and any vendor chunk.
                else -> hadLocation = hadLocation || b.containsAscii(GPS_XMP_KEY, i + 8, dataEnd)
            }
            i = end
        }

        // XMP is always gone; the EXIF flag stays only if an EXIF chunk was written back.
        val dropFlags = if (hasExif) VP8X_XMP_FLAG else VP8X_XMP_FLAG or VP8X_EXIF_FLAG
        val body = kept.sumOf { it.size }
        out.write(RIFF)
        out.write(le32(4 + body))
        out.write(WEBP)
        for (piece in kept) when (piece) {
            is Piece.Bytes -> out.write(piece.bytes)
            is Piece.Slice -> if (piece.from == vp8x && piece.size > 8) {
                out.write(b, vp8x, 8)
                out.write(b.u8(vp8x + 8) and dropFlags.inv())
                out.write(b, vp8x + 9, piece.size - 9)
            } else {
                out.write(b, piece.from, piece.size)
            }
        }
        return StripReport(removedBytes = b.size - (12 + body) + blanked, hadLocation = hadLocation)
    }

    private fun webpChunk(fourcc: ByteArray, data: ByteArray): ByteArray =
        fourcc + le32(data.size) + data + if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0)

    // HEIF / AVIF (ISO-BMFF): EXIF and XMP are "items" whose bytes the 'iloc' box points at. Removing
    // them would mean rewriting every offset in the file, so instead their bytes are overwritten in place
    // with an empty block. Layout, size and image data stay identical; rotation lives in 'irot', untouched.
    // Vendors also append their own top-level boxes (Samsung's 'sefd' holds capture time and country), so
    // every top-level box that isn't image structure is turned into an all-zero 'free' box of the same size.

    private class Box(val type: String, val start: Int, val body: Int, val end: Int)

    private enum class HeifMeta { EXIF, XMP, OTHER }

    private fun stripHeif(b: ByteArray, out: OutputStream, options: StripOptions): StripReport {
        val meta = findBox(b, 0, b.size, "meta") ?: throw MalformedImageException("HEIF without a meta box")
        val children = meta.body + 4 // FullBox version + flags
        val kinds = heifMetadataItems(b, children, meta.end)
        val extents = heifItemExtents(b, children, meta.end)

        val clean = b.copyOf()
        var removed = 0
        var hadLocation = false
        for ((id, kind) in kinds) {
            val ranges = extents[id] ?: continue
            val payload = ranges.fold(ByteArray(0)) { acc, r -> acc + b.copyOfRange(r.first, r.last + 1) }
            // Exif item: 4-byte offset to the TIFF header, then (usually "Exif\0\0" and) the TIFF data.
            val tiff = if (kind == HeifMeta.EXIF && payload.size >= 4 && payload.u32be(0) < payload.size - 4) {
                4 + payload.u32be(0).toInt()
            } else {
                -1
            }
            val exif = if (tiff >= 0) readExif(payload, tiff, payload.size) else null
            hadLocation = hadLocation || when (kind) {
                HeifMeta.EXIF -> exif?.hasLocation == true
                HeifMeta.XMP, HeifMeta.OTHER -> payload.containsAscii(GPS_XMP_KEY, 0, payload.size)
            }
            val blank = when (kind) {
                HeifMeta.EXIF -> if (options.level == StripLevel.LOCATION && exif != null) {
                    // Same size, same offsets: only the GPS directory's bytes turn to zero.
                    payload.copyOf().also { removed += removeGps(it, tiff, it.size) }
                } else {
                    // Rotation lives in 'irot', so only the date can be worth keeping here.
                    val kept = exif?.let { cleanTiff(payload, tiff, payload.size, it, options, orientation = false) {} }
                    removed += payload.size
                    heifExif(kept, payload.size)
                }
                HeifMeta.XMP -> emptyXmp(payload.size).also { removed += payload.size }
                HeifMeta.OTHER -> ByteArray(payload.size).also { removed += payload.size }
            }
            var k = 0
            for (r in ranges) for (at in r) clean[at] = blank[k++]
        }

        // Image items may in principle live anywhere, so never blank a box that an image item still points into.
        val imageRanges = extents.filterKeys { it !in kinds }.values.flatten()
        for (box in boxes(b, 0, b.size)) {
            if (box.type in HEIF_STRUCTURE_BOXES) continue
            if (imageRanges.any { it.first < box.end && it.last >= box.start }) continue
            val alreadyBlank = box.type == "free" && (box.body until box.end).all { b[it].toInt() == 0 }
            if (!alreadyBlank) {
                hadLocation = hadLocation || b.containsAscii(GPS_XMP_KEY, box.body, box.end)
                removed += box.end - box.body
            }
            FREE.copyInto(clean, box.start + 4) // size field (incl. 64-bit form) is untouched
            clean.fill(0, box.body, box.end)
        }
        out.write(clean)
        return StripReport(removedBytes = removed, hadLocation = hadLocation)
    }

    /** Item IDs of the EXIF, XMP and other non-image (C2PA, URI) items, from 'iinf'. */
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
                    when {
                        "rdf+xml" in contentType -> items[id] = HeifMeta.XMP
                        !contentType.startsWith("image/") -> items[id] = HeifMeta.OTHER // e.g. application/c2pa
                    }
                }
                "uri " -> items[id] = HeifMeta.OTHER
            }
        }
        return items
    }

    /** Absolute byte ranges of every item, from 'iloc'. */
    private fun heifItemExtents(b: ByteArray, from: Int, to: Int): Map<Int, List<IntRange>> {
        val iloc = findBox(b, from, to, "iloc") ?: return emptyMap()
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
            if (ranges.isNotEmpty()) result[id] = ranges
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
            yield(Box(String(b, i + 4, 4, Charsets.ISO_8859_1), i, i + header, i + size.toInt()))
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

    /** A valid Exif item holding [tiff] (or an empty TIFF with no tags), zero-padded to [size]. */
    private fun heifExif(tiff: ByteArray?, size: Int): ByteArray {
        val empty = byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0)
        val item = byteArrayOf(0, 0, 0, 0) + (tiff?.takeIf { it.size + 4 <= size } ?: empty)
        return if (size >= item.size) item.copyOf(size) else ByteArray(size)
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

    // EXIF (TIFF) parsing — IFD0 for orientation and the GPS pointer, the Exif IFD for the capture date.

    private class ExifInfo(val orientation: Int, val hasLocation: Boolean, val date: String?, val offset: String?)

    /** A TIFF structure inside [b], from [start] (its byte-order mark) to [end]. Offsets in it are relative to [start]. */
    private class Tiff private constructor(val b: ByteArray, val start: Int, val end: Int, private val littleEndian: Boolean) {
        fun u16(at: Int) = if (littleEndian) b.u8(at) or (b.u8(at + 1) shl 8) else b.u16be(at)
        fun u32(at: Int) = if (littleEndian) b.u32le(at) else b.u32be(at)

        fun put16(at: Int, v: Int) {
            b[at + if (littleEndian) 0 else 1] = v.toByte()
            b[at + if (littleEndian) 1 else 0] = (v shr 8).toByte()
        }

        /** Absolute position of the IFD that the 4-byte offset at [at] points to, or -1 if it's out of bounds. */
        fun ifdAt(at: Int): Int {
            val offset = u32(at)
            return if (offset in 8L..(end - start - 2).toLong()) start + offset.toInt() else -1
        }

        /** Absolute positions of the in-bounds entries of the IFD at [ifd]. */
        fun entries(ifd: Int): IntProgression {
            val count = u16(ifd)
            val last = minOf(ifd + 2 + 12 * count, end) - 12
            return (ifd + 2)..last step 12
        }

        /** An ASCII value, without its NUL terminator; null if it doesn't fit the file. */
        fun ascii(entry: Int): String? {
            val count = u32(entry + 4)
            if (u16(entry + 2) != TYPE_ASCII || count !in 1L..MAX_ASCII) return null
            val at = if (count <= 4) entry + 8 else start + u32(entry + 8).toInt()
            if (at < start || at + count > end) return null
            return String(b, at, count.toInt(), Charsets.ISO_8859_1).trimEnd('\u0000', ' ')
        }

        /** Bytes taken by the value of the entry at [entry], or 0 if the type is unknown. */
        fun valueSize(entry: Int): Long = TYPE_SIZES.getOrElse(u16(entry + 2)) { 0 } * u32(entry + 4)

        companion object {
            fun at(b: ByteArray, start: Int, end: Int): Tiff? {
                if (start < 0 || start + 8 > end) return null
                val littleEndian = when {
                    b.u8(start) == 'I'.code && b.u8(start + 1) == 'I'.code -> true
                    b.u8(start) == 'M'.code && b.u8(start + 1) == 'M'.code -> false
                    else -> return null
                }
                return Tiff(b, start, end, littleEndian)
            }

            const val MAX_ASCII = 64L
            // Indexed by TIFF type: BYTE, ASCII, SHORT, LONG, RATIONAL, SBYTE, UNDEFINED, SSHORT, SLONG, SRATIONAL, FLOAT, DOUBLE.
            private val TYPE_SIZES = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8)
        }
    }

    private fun readExif(b: ByteArray, start: Int, end: Int): ExifInfo {
        val none = ExifInfo(0, false, null, null)
        val t = Tiff.at(b, start, end) ?: return none
        val ifd0 = t.ifdAt(start + 4).takeIf { it >= 0 } ?: return none
        var orientation = 0
        var hasLocation = false
        var modified: String? = null
        var exifIfd = -1
        for (entry in t.entries(ifd0)) {
            when (t.u16(entry)) {
                TAG_ORIENTATION -> orientation = t.u16(entry + 8)
                // A pointer to an empty GPS directory (what location-only cleaning leaves at worst) isn't a location.
                TAG_GPS_IFD -> hasLocation = t.ifdAt(entry + 8).let { it < 0 || t.u16(it) > 0 }
                TAG_DATETIME -> modified = t.ascii(entry)
                TAG_EXIF_IFD -> exifIfd = t.ifdAt(entry + 8)
            }
        }
        var original: String? = null
        var digitized: String? = null
        var offset: String? = null
        var offsetAny: String? = null
        if (exifIfd >= 0) {
            for (entry in t.entries(exifIfd)) {
                when (t.u16(entry)) {
                    TAG_DATETIME_ORIGINAL -> original = t.ascii(entry)
                    TAG_DATETIME_DIGITIZED -> digitized = t.ascii(entry)
                    TAG_OFFSET_TIME_ORIGINAL -> offset = t.ascii(entry)
                    TAG_OFFSET_TIME, TAG_OFFSET_TIME_DIGITIZED -> offsetAny = offsetAny ?: t.ascii(entry)
                }
            }
        }
        // The best guess at "taken" when a camera only wrote some of them; malformed values aren't carried over.
        val date = sequenceOf(original, digitized, modified).firstOrNull { it != null && DATE_FORMAT.matches(it) }
        val zone = (offset ?: offsetAny)?.takeIf { OFFSET_FORMAT.matches(it) }
        return ExifInfo(orientation, hasLocation, date, zone)
    }

    /**
     * Zeroes the GPS directory of the TIFF at [start] (its entries and every value they point to), then drops the
     * IFD0 pointer to it, shifting the entries after it up. Nothing else moves, so every other offset stays valid.
     * Returns how many bytes were zeroed.
     */
    private fun removeGps(b: ByteArray, start: Int, end: Int): Int {
        val t = Tiff.at(b, start, end) ?: return 0
        val ifd0 = t.ifdAt(start + 4).takeIf { it >= 0 } ?: return 0
        val pointer = t.entries(ifd0).firstOrNull { t.u16(it) == TAG_GPS_IFD } ?: return 0
        var zeroed = 0
        fun zero(from: Int, to: Int) {
            val a = from.coerceIn(start, end)
            val z = to.coerceIn(a, end)
            b.fill(0, a, z)
            zeroed += z - a
        }

        val gps = t.ifdAt(pointer + 8)
        if (gps >= 0) {
            val entries = t.entries(gps)
            for (entry in entries) {
                val size = t.valueSize(entry)
                if (size > 4) {
                    val at = start + t.u32(entry + 8)
                    if (at < end) zero(at.toInt(), minOf(at + size, end.toLong()).toInt())
                }
            }
            zero(gps, gps + 2 + 12 * t.u16(gps) + 4)
        }

        val count = t.u16(ifd0)
        val tail = ifd0 + 2 + 12 * count + 4 // just past IFD0's next-IFD offset
        if (tail <= end) {
            b.copyInto(b, pointer, pointer + 12, tail)
            zero(tail - 12, tail)
            t.put16(ifd0, count - 1)
        }
        return zeroed
    }

    // Byte helpers

    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
    private fun ByteArray.u16be(i: Int) = (u8(i) shl 8) or u8(i + 1)
    private fun ByteArray.u32be(i: Int): Long =
        (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()
    private fun ByteArray.u32le(i: Int): Long =
        (u8(i + 3).toLong() shl 24) or (u8(i + 2).toLong() shl 16) or (u8(i + 1).toLong() shl 8) or u8(i).toLong()

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

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
    private const val TAG_DATETIME = 0x0132
    private const val TAG_EXIF_IFD = 0x8769
    private const val TAG_GPS_IFD = 0x8825
    private const val TAG_DATETIME_ORIGINAL = 0x9003
    private const val TAG_DATETIME_DIGITIZED = 0x9004
    private const val TAG_OFFSET_TIME = 0x9010
    private const val TAG_OFFSET_TIME_ORIGINAL = 0x9011
    private const val TAG_OFFSET_TIME_DIGITIZED = 0x9012
    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    /** "YYYY:MM:DD HH:MM:SS" and "+HH:MM", each with its NUL. */
    private const val DATE_LENGTH = 20
    private const val OFFSET_LENGTH = 7
    private val DATE_FORMAT = Regex("""\d{4}:\d{2}:\d{2} \d{2}:\d{2}:\d{2}""")
    private val OFFSET_FORMAT = Regex("""[+-]\d{2}:\d{2}""")
    private const val VP8X_EXIF_FLAG = 0x08
    private const val VP8X_XMP_FLAG = 0x04

    private val EXIF_HEADER = ascii("Exif\u0000\u0000")
    private val JFXX = ascii("JFXX\u0000")
    private val ICC_PROFILE = ascii("ICC_PROFILE\u0000")
    private val ADOBE = ascii("Adobe")
    private val GPS_XMP_KEY = ascii("GPSLatitude")
    private val PNG_EXIF = ascii("eXIf")
    private val PNG_SIGNATURE =byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    // Everything else ancillary goes: eXIf, tEXt/zTXt/iTXt, tIME, caBX (C2PA), pHYs, and private vendor chunks.
    private val PNG_RENDERING_CHUNKS = setOf(
        "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "cICP", "mDCV", "mDCv", "cLLI", "cLLi", "sBIT", "bKGD",
        "acTL", "fcTL", "fdAT", // APNG
    )
    private val RIFF = ascii("RIFF")
    private val WEBP = ascii("WEBP")
    private val WEBP_VP8X = ascii("VP8X")
    private val WEBP_EXIF = ascii("EXIF")
    private val WEBP_IMAGE_CHUNKS = setOf("VP8 ", "VP8L", "VP8X", "ALPH", "ANIM", "ANMF", "ICCP")
    private val FTYP = ascii("ftyp")
    private val FREE = ascii("free")
    // Top-level boxes that make up the image (still or sequence); anything else is vendor or metadata payload.
    private val HEIF_STRUCTURE_BOXES = setOf("ftyp", "meta", "mdat", "moov", "moof", "mfra", "styp", "sidx", "ssix")
    private val HEIC_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs")
    private val HEIF_BRANDS = setOf("mif1", "msf1", "mif2")
    private val AVIF_BRANDS = setOf("avif", "avis")
}
