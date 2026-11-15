package com.spwtb.taskbarlyrics.lyrics

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class CoverArtWavTest {
    private val png = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()
    private fun le(n: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
    private fun be(n: Int) = ByteBuffer.allocate(4).putInt(n).array()
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun id3(): ByteArray {
        val apic = byteArrayOf(0) + ascii("image/png") + byteArrayOf(0, 3, 0) + png
        val frames = ascii("APIC") + be(apic.size) + byteArrayOf(0, 0) + apic
        val n = frames.size
        val length = byteArrayOf((n ushr 21).toByte(), ((n ushr 14) and 127).toByte(),
            ((n ushr 7) and 127).toByte(), (n and 127).toByte())
        return ascii("ID3") + byteArrayOf(3, 0, 0) + length + frames
    }
    private fun chunk(id: String, bytes: ByteArray) =
        ascii(id) + le(bytes.size) + bytes + if (bytes.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
    private fun wave(vararg chunks: ByteArray): ByteArray {
        val body = chunks.fold(ascii("WAVE")) { a, b -> a + b }
        return ascii("RIFF") + le(body.size) + body
    }
    private fun read(bytes: ByteArray, suffix: String = ".wav"): ByteArray? {
        val path = Files.createTempFile("cover-art-test-", suffix)
        try {
            Files.write(path, bytes)
            return CoverArt.readEmbeddedPicture(path.toFile())
        } finally { Files.deleteIfExists(path) }
    }

    @Test
    fun `reads ID3 cover after audio and odd padded RIFF chunk`() {
        val wav = wave(chunk("JUNK", byteArrayOf(1, 2, 3)), chunk("data", ByteArray(16)), chunk("id3 ", id3()))
        assertContentEquals(png, read(wav))
    }

    @Test
    fun `accepts uppercase ID3 chunk and WAV extension`() {
        assertContentEquals(png, read(wave(chunk("ID3 ", id3())), ".WAV"))
    }

    @Test
    fun `preserves MP3 APIC parsing`() {
        assertContentEquals(png, read(id3(), ".mp3"))
    }

    @Test
    fun `does not scan fake ID3 within audio data`() {
        assertNull(read(wave(chunk("data", id3()))))
    }

    @Test
    fun `rejects truncated RIFF chunk without reading beyond its boundary`() {
        assertNull(read(wave(ascii("id3 ") + le(Int.MAX_VALUE) + id3())))
    }

    @Test
    fun `ID3 declared size cannot consume following RIFF chunks`() {
        val tag = id3()
        assertNull(read(wave(chunk("id3 ", tag.copyOf(10)), chunk("data", tag.copyOfRange(10, tag.size)))))
    }
    @Test
    fun `WAV cover completes the background decode and scaling pipeline`() {
        val path = Files.createTempFile("cover-pipeline-", ".wav")
        val ready = java.util.concurrent.CountDownLatch(1)
        try {
            Files.write(path, wave(chunk("data", ByteArray(16)), chunk("id3 ", id3())))
            CoverArt.clear()
            CoverArt.start { ready.countDown() }
            assertNull(CoverArt.forSong("wave", path.toString(), 2, 0))
            assertTrue(ready.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val decoded = assertNotNull(CoverArt.forSong("wave", path.toString(), 2, 0))
            assertEquals(2, decoded.width)
            assertEquals(2, decoded.height)
        } finally {
            CoverArt.clear()
            Files.deleteIfExists(path)
        }
    }
}