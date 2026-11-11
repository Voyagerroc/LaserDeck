package com.voyagerroc.laserdeck.ptt.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkFrameTest {

    @Test
    fun `encode then parse roundtrip`() {
        val payload = ByteArray(200) { (it * 7).toByte() }
        val bytes = LinkProtocol.encode(LinkFrame(LinkProtocol.TYPE_AUDIO_FRAME, 42, payload))

        val decoded = mutableListOf<LinkFrame>()
        val parser = FrameParser { decoded += it }
        parser.feed(bytes)

        assertEquals(1, decoded.size)
        assertEquals(LinkProtocol.TYPE_AUDIO_FRAME, decoded[0].type)
        assertEquals(42, decoded[0].seq)
        assertArrayEquals(payload, decoded[0].payload)
        assertEquals(0L, parser.droppedFrames)
    }

    @Test
    fun `parser survives split byte stream`() {
        val bytes = LinkProtocol.encode(LinkFrame(LinkProtocol.TYPE_TEXT, 7, "merhaba".toByteArray()))

        val decoded = mutableListOf<LinkFrame>()
        val parser = FrameParser { decoded += it }
        // Baytları teker teker besle (RFCOMM/seri hatta parçalanmış okumalar)
        for (b in bytes) parser.feed(byteArrayOf(b))

        assertEquals(1, decoded.size)
        assertEquals("merhaba", decoded[0].payload.decodeToString())
    }

    @Test
    fun `corrupted crc is dropped and next frame still parses`() {
        val bad = LinkProtocol.encode(LinkFrame(LinkProtocol.TYPE_TEXT, 1, "bozuk".toByteArray()))
        bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 0xFF).toByte() // CRC'yi boz
        val good = LinkProtocol.encode(LinkFrame(LinkProtocol.TYPE_TEXT, 2, "saglam".toByteArray()))

        val decoded = mutableListOf<LinkFrame>()
        val parser = FrameParser { decoded += it }
        parser.feed(bad + good)

        assertEquals(1, decoded.size)
        assertEquals("saglam", decoded[0].payload.decodeToString())
        assertEquals(1L, parser.droppedFrames)
    }

    @Test
    fun `garbage prefix before frame is skipped`() {
        val garbage = ByteArray(64) { (it * 31 + 5).toByte() }
        val frame = LinkProtocol.encode(LinkFrame(LinkProtocol.TYPE_PING, 3, ByteArray(0)))

        val decoded = mutableListOf<LinkFrame>()
        val parser = FrameParser { decoded += it }
        parser.feed(garbage + frame)

        assertEquals(1, decoded.size)
        assertEquals(LinkProtocol.TYPE_PING, decoded[0].type)
    }
}
