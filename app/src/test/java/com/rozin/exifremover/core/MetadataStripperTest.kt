package com.rozin.exifremover.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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

    // Helpers

    private fun strip(input: ByteArray, keepOrientation: Boolean): Pair<ByteArray, StripReport> {
        val format = requireNotNull(MetadataStripper.detect(input)) { "undetected format" }
        val out = ByteArrayOutputStream()
        val report = MetadataStripper.strip(input, format, out, keepOrientation)
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
    }
}
