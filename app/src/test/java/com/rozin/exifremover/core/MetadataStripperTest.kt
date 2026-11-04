package com.rozin.exifremover.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

class MetadataStripperTest {

    // JPEG

    @Test
    fun jpegStripRestoresTheOriginalBytesExactly() {
        val original = encode("jpg")
        val dirty = withJpegMetadata(original)

        val (out, report) = strip(dirty, keepOrientation = false)

        assertArrayEquals(original, out)
        assertTrue(report.hadLocation)
        assertEquals(dirty.size - original.size, report.removedBytes)
    }

    @Test
    fun jpegKeepOrientationLeavesOnlyTheRotationTag() {
        val original = encode("jpg")

        val (out, _) = strip(withJpegMetadata(original), keepOrientation = true)

        val minimal = bytes(
            0xFF, 0xE1, 0, 34, 'E', 'x', 'i', 'f', 0, 0,
            'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1,
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0,
            0, 0, 0, 0,
        )
        assertArrayEquals(original.insert(APP0_END, minimal), out)
        assertNotNull(ImageIO.read(ByteArrayInputStream(out)))
    }

    @Test
    fun jpegWithoutMetadataIsUnchanged() {
        val original = encode("jpg")
        val (out, report) = strip(original, keepOrientation = true)
        assertArrayEquals(original, out)
        assertEquals(0, report.removedBytes)
    }

    @Test
    fun jpegKeepDateRebuildsExifWithOnlyTheCaptureTime() {
        val dirty = encode("jpg").insert(APP0_END, jpegSegment(0xE1, "Exif\u0000\u0000".toByteArray() + richTiff()))

        val (out, report) = strip(dirty, StripOptions(keepOrientation = true, keepDate = true))

        val text = String(out, Charsets.ISO_8859_1)
        assertTrue(report.hadLocation)
        assertTrue(DATE in text && ZONE in text)
        assertFalse(MAKE in text)
        assertFalse(out.containsRun(LATITUDE_BYTE, 24))
        assertNotNull(ImageIO.read(ByteArrayInputStream(out)))
        // Cleaning a cleaned file changes nothing.
        assertArrayEquals(out, strip(out, StripOptions(keepDate = true)).first)
    }

    @Test
    fun jpegLocationOnlyKeepsEverythingButGps() {
        val dirty = encode("jpg").insert(APP0_END, jpegSegment(0xE1, "Exif\u0000\u0000".toByteArray() + richTiff()))

        val (out, report) = strip(dirty, StripOptions(level = StripLevel.LOCATION))

        val text = String(out, Charsets.ISO_8859_1)
        assertTrue(report.hadLocation)
        assertTrue(report.removedBytes > 0)
        assertEquals(dirty.size, out.size) // zeroed in place, nothing shifted
        assertTrue(MAKE in text && DATE in text && ZONE in text)
        assertFalse(out.containsRun(LATITUDE_BYTE, 24))
        assertFalse(strip(out, StripOptions(level = StripLevel.LOCATION)).second.hadLocation)
        assertNotNull(ImageIO.read(ByteArrayInputStream(out)))
    }

    @Test
    fun webpKeepDateKeepsTheExifFlag() {
        val vp8x = webpChunk("VP8X", bytes(0x0C, 0, 0, 0, 9, 0, 0, 9, 0, 0))
        val image = webpChunk("VP8L", bytes(0x2F, 1, 2, 3, 4))
        val dirty = riff(vp8x + image + webpChunk("EXIF", richTiff()) + webpChunk("XMP ", "<x/>".toByteArray()))

        val (out, _) = strip(dirty, StripOptions(keepDate = true))

        assertEquals(0x08, out[20].toInt()) // VP8X flags: EXIF kept, XMP cleared
        assertTrue(DATE in String(out, Charsets.ISO_8859_1))
        assertFalse(MAKE in String(out, Charsets.ISO_8859_1))
    }

    @Test(expected = MalformedImageException::class)
    fun truncatedJpegIsRejected() {
        strip(bytes(0xFF, 0xD8, 0xFF, 0xE1, 0x10, 0x00, 1, 2, 3), keepOrientation = false)
    }

    // PNG

    @Test
    fun pngStripRemovesTextAndExifChunks() {
        val original = encode("png")
        val ihdrEnd = 8 + 25
        val dirty = original.insert(
            ihdrEnd,
            pngChunk("tEXt", "Author\u0000Someone".toByteArray()) +
                pngChunk("eXIf", tiffWithGps()) +
                pngChunk("caBX", "c2pa manifest".toByteArray()),
        )

        val (out, report) = strip(dirty, keepOrientation = true)

        assertArrayEquals(original, out)
        assertTrue(report.hadLocation)
    }

    // WebP

    @Test
    fun webpStripDropsMetadataChunksAndClearsFlags() {
        val vp8x = webpChunk("VP8X", bytes(0x0C, 0, 0, 0, 9, 0, 0, 9, 0, 0))
        val image = webpChunk("VP8L", bytes(0x2F, 1, 2, 3, 4)) // odd size → padded
        val dirty = riff(vp8x + image + webpChunk("EXIF", tiffWithGps()) + webpChunk("XMP ", "<x/>".toByteArray()))

        val (out, report) = strip(dirty, keepOrientation = true)

        val cleanVp8x = webpChunk("VP8X", bytes(0x00, 0, 0, 0, 9, 0, 0, 9, 0, 0))
        assertArrayEquals(riff(cleanVp8x + image), out)
        assertTrue(report.hadLocation)
    }

    // HEIF

    /** A real file from macOS `sips`, with EXIF (GPS, model, artist) and an XMP item added by exiftool. */
    @Test
    fun realHeicIsBlankedInPlaceAndKeepsItsSize() {
        val original = requireNotNull(javaClass.getResourceAsStream("/gps_exif_xmp.heic")).use { it.readBytes() }
        assertEquals(ImageFormat.HEIC, MetadataStripper.detect(original))

        val (out, report) = strip(original, keepOrientation = true)

        assertEquals(original.size, out.size)
        assertTrue(report.hadLocation)
        assertTrue(report.removedBytes > 0)
        // Only metadata bytes may change; the image data and every offset stay put.
        assertTrue(original.indices.count { original[it] != out[it] } <= report.removedBytes)
        assertTrue("GPSLatitude" !in String(out, Charsets.ISO_8859_1))

        // A second pass finds nothing left to report.
        val (again, secondReport) = strip(out, keepOrientation = true)
        assertArrayEquals(out, again)
        assertFalse(secondReport.hadLocation)

        // Kept for checking by hand with exiftool / an image viewer.
        File("build/test-output").apply { mkdirs() }.resolve("gps_exif_xmp.stripped.heic").writeBytes(out)
    }

    /** Synthetic file exercising iloc v1 and EXIF stored inside the meta box's 'idat'. */
    @Test
    fun heifExifInIdatIsBlanked() {
        val exif = be32(6) + "Exif\u0000\u0000".toByteArray() + tiffWithGps()
        val dirty = heif(exif)

        val (out, report) = strip(dirty, keepOrientation = false)

        val blank = bytes(0, 0, 0, 0, 'M', 'M', 0, 42, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0).copyOf(exif.size)
        assertArrayEquals(heif(blank), out)
        assertTrue(report.hadLocation)
        assertEquals(exif.size, report.removedBytes)
    }

    // Helpers

    private fun box(type: String, body: ByteArray) = be32(8 + body.size) + type.toByteArray() + body

    private fun fullBox(type: String, version: Int, body: ByteArray) = box(type, bytes(version, 0, 0, 0) + body)

    /** ftyp + meta(iinf, iloc v1, idat[exif]) + mdat[image]; item 1 is the image, item 2 the EXIF in idat. */
    private fun heif(exif: ByteArray): ByteArray {
        val image = bytes(0x11, 0x22, 0x33, 0x44, 0x55)
        val ftyp = box("ftyp", "heic".toByteArray() + be32(0) + "mif1heic".toByteArray())
        val iinf = fullBox(
            "iinf", 0,
            bytes(0, 2) +
                fullBox("infe", 2, bytes(0, 1, 0, 0) + "hvc1".toByteArray() + bytes(0)) +
                fullBox("infe", 2, bytes(0, 2, 0, 0) + "Exif".toByteArray() + bytes(0)),
        )
        fun iloc(imageOffset: Int) = fullBox(
            "iloc", 1,
            bytes(0x44, 0x00, 0, 2) + // offset/length size 4, base/index size 0; 2 items
                bytes(0, 1, 0, 0, 0, 0, 0, 1) + be32(imageOffset) + be32(image.size) + // method 0 (file)
                bytes(0, 2, 0, 1, 0, 0, 0, 1) + be32(0) + be32(exif.size), // method 1 (idat)
        )
        val idat = box("idat", exif)
        val metaSize = fullBox("meta", 0, iinf + iloc(0) + idat).size
        val imageOffset = ftyp.size + metaSize + 8
        return ftyp + fullBox("meta", 0, iinf + iloc(imageOffset) + idat) + box("mdat", image)
    }

    private fun strip(input: ByteArray, keepOrientation: Boolean): Pair<ByteArray, StripReport> =
        strip(input, StripOptions(keepOrientation = keepOrientation, keepDate = false))

    private fun strip(input: ByteArray, options: StripOptions): Pair<ByteArray, StripReport> {
        val format = requireNotNull(MetadataStripper.detect(input)) { "undetected format" }
        val out = ByteArrayOutputStream()
        val report = MetadataStripper.strip(input, format, out, options)
        return out.toByteArray() to report
    }

    private fun encode(format: String): ByteArray {
        val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 64) for (y in 0 until 48) image.setRGB(x, y, (x * 4 shl 16) or (y * 5 shl 8) or 0x80)
        return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
    }

    /** Inserts EXIF (orientation 6 + GPS), XMP, a comment, and trailing junk after the JFIF APP0. */
    private fun withJpegMetadata(jpeg: ByteArray): ByteArray {
        val exif = jpegSegment(0xE1, "Exif\u0000\u0000".toByteArray() + tiffWithGps(orientation = 6))
        val xmp = jpegSegment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<exif:GPSLatitude>1</exif:GPSLatitude>".toByteArray())
        val comment = jpegSegment(0xFE, "shot at home".toByteArray())
        return jpeg.insert(APP0_END, exif + xmp + comment) + bytes(0xFF, 0xD8, 1, 2, 3)
    }

    /** Little-endian TIFF, IFD0 = [Orientation, GPSInfo pointer]. */
    private fun tiffWithGps(orientation: Int = 1) = bytes(
        'I', 'I', 42, 0, 8, 0, 0, 0,
        2, 0,
        0x12, 0x01, 3, 0, 1, 0, 0, 0, orientation, 0, 0, 0,
        0x25, 0x88, 4, 0, 1, 0, 0, 0, 38, 0, 0, 0,
        0, 0, 0, 0,
    )

    /**
     * Little-endian TIFF: IFD0 = [Make → "Pixel", Exif IFD, GPS IFD]; Exif IFD = [DateTimeOriginal, OffsetTimeOriginal];
     * GPS IFD = [GPSLatitude, 3 rationals filled with [LATITUDE_BYTE]].
     */
    private fun richTiff(): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(156).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun entry(tag: Int, type: Int, count: Int, value: Int) {
            buf.putShort(tag.toShort()).putShort(type.toShort()).putInt(count).putInt(value)
        }
        buf.put("II".toByteArray()).putShort(42).putInt(8)
        buf.putShort(3) // IFD0 @ 8
        entry(0x010F, 2, 6, 98)
        entry(0x8769, 4, 1, 50)
        entry(0x8825, 4, 1, 80)
        buf.putInt(0)
        buf.putShort(2) // Exif IFD @ 50
        entry(0x9003, 2, 20, 104)
        entry(0x9011, 2, 7, 124)
        buf.putInt(0)
        buf.putShort(1) // GPS IFD @ 80
        entry(0x0002, 5, 3, 132)
        buf.putInt(0)
        buf.put("$MAKE\u0000".toByteArray()) // @ 98
        buf.put("$DATE\u0000".toByteArray()) // @ 104
        buf.put("$ZONE\u0000".toByteArray()).put(0) // @ 124, padded
        repeat(24) { buf.put(LATITUDE_BYTE.toByte()) } // @ 132
        return buf.array()
    }

    private fun ByteArray.containsRun(value: Int, length: Int): Boolean {
        var run = 0
        for (byte in this) {
            run = if (byte == value.toByte()) run + 1 else 0
            if (run >= length) return true
        }
        return false
    }

    private fun jpegSegment(marker: Int, payload: ByteArray) =
        bytes(0xFF, marker, (payload.size + 2) shr 8, (payload.size + 2) and 0xFF) + payload

    private fun pngChunk(type: String, data: ByteArray) = be32(data.size) + type.toByteArray() + data + bytes(0, 0, 0, 0)

    private fun webpChunk(fourcc: String, data: ByteArray) =
        fourcc.toByteArray() + le32(data.size) + data + (if (data.size % 2 == 1) bytes(0) else ByteArray(0))

    private fun riff(chunks: ByteArray) = "RIFF".toByteArray() + le32(4 + chunks.size) + "WEBP".toByteArray() + chunks

    private fun ByteArray.insert(at: Int, extra: ByteArray) = copyOfRange(0, at) + extra + copyOfRange(at, size)

    private fun be32(v: Int) = bytes(v ushr 24, v ushr 16, v ushr 8, v)
    private fun le32(v: Int) = bytes(v, v ushr 8, v ushr 16, v ushr 24)

    private fun bytes(vararg values: Any) = ByteArray(values.size) {
        when (val v = values[it]) {
            is Char -> v.code.toByte()
            is Int -> v.toByte()
            else -> error("bad byte $v")
        }
    }

    private companion object {
        /** ImageIO writes SOI followed by an 18-byte JFIF APP0 segment. */
        const val APP0_END = 2 + 18
        const val MAKE = "Pixel"
        const val DATE = "2024:05:01 10:20:30"
        const val ZONE = "+02:00"
        const val LATITUDE_BYTE = 0x7B
    }
}
