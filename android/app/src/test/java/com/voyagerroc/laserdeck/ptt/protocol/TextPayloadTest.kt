package com.voyagerroc.laserdeck.ptt.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Metin mesajı yükü: [msgId u32 LE][UTF-8 metin].
 * Kimlik, hat koptuktan sonra yeniden yollanan mesajların karşı tarafta
 * ikinci kez yazılmamasını sağlıyor — bu yüzden bit düzeyinde doğrulanıyor.
 */
class TextPayloadTest {

    @Test
    fun `text roundtrip preserves id and turkish characters`() {
        val text = "Yola çıktım, şafakta oradayım — İĞÜŞÖÇ"
        val payload = LinkProtocol.encodeText(0x1A2B3C4D, text)

        assertEquals(0x1A2B3C4D, LinkProtocol.readMsgId(payload))
        assertEquals(text, LinkProtocol.readText(payload))
    }

    @Test
    fun `negative id survives the wire`() {
        // Kimlikler rastgele Int; en anlamlı biti 1 olanlar da doğru dönmeli.
        val payload = LinkProtocol.encodeText(-2, "x")
        assertEquals(-2, LinkProtocol.readMsgId(payload))
    }

    @Test
    fun `text frame survives full encode and parse`() {
        val payload = LinkProtocol.encodeText(99, "merhaba")
        val bytes = LinkProtocol.encode(LinkFrame(LinkProtocol.TYPE_TEXT, 1, payload))

        val decoded = mutableListOf<LinkFrame>()
        FrameParser { decoded += it }.feed(bytes)

        assertEquals(1, decoded.size)
        assertEquals(LinkProtocol.TYPE_TEXT, decoded[0].type)
        assertEquals(99, LinkProtocol.readMsgId(decoded[0].payload))
        assertEquals("merhaba", LinkProtocol.readText(decoded[0].payload))
    }

    @Test
    fun `ack carries only the id`() {
        val ack = LinkProtocol.encodeAck(7)
        assertEquals(4, ack.size)
        assertEquals(7, LinkProtocol.readMsgId(ack))
    }

    @Test
    fun `short payload yields no id instead of crashing`() {
        assertNull(LinkProtocol.readMsgId(ByteArray(3)))
        assertEquals("", LinkProtocol.readText(ByteArray(4)))
    }

    @Test
    fun `oversized text is rejected before it reaches the wire`() {
        val tooLong = "ç".repeat(LinkProtocol.MAX_TEXT_BYTES) // 2 bayt/karakter
        val result = runCatching { LinkProtocol.encodeText(1, tooLong) }
        assertTrue(result.isFailure)
    }
}
