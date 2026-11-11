package com.voyagerroc.laserdeck.ptt.protocol

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Taşıyıcıdan bağımsız çerçeve katmanı.
 *
 * LaserDeck optik protokolünün (preamble/sync/CRC32) bayt-akışı uyarlaması:
 * aynı çerçeveler RFCOMM soketi üzerinden de, ileride USB seri -> ESP32 ->
 * 940nm optik hat üzerinden de taşınabilir.
 *
 * Tel formatı (little-endian):
 *   0xAA 0x7E | ver(1) | type(1) | seq(2) | len(2) | payload(len) | crc32(4)
 *
 * CRC32, ver..payload aralığı üzerinden hesaplanır (IEEE 802.3, LaserDeck ile aynı).
 */
object LinkProtocol {
    const val SYNC0: Int = 0xAA
    const val SYNC1: Int = 0x7E
    const val VERSION: Int = 1

    const val HEADER_LEN = 6           // ver + type + seq(2) + len(2)
    const val MAX_PAYLOAD = 2048

    // Çerçeve tipleri
    const val TYPE_PING = 0x01
    const val TYPE_PONG = 0x02
    const val TYPE_AUDIO_START = 0x10  // payload: [codecId u8][sampleRate u16 LE]
    const val TYPE_AUDIO_FRAME = 0x11  // payload: [n u8]([len u8][chunk])*n
    const val TYPE_AUDIO_END = 0x12
    const val TYPE_TEXT = 0x20         // payload: [msgId u32 LE][UTF-8 metin]
    const val TYPE_TEXT_ACK = 0x21     // payload: [msgId u32 LE]

    // Çağrı işaretleşmesi. Çağrı sesi, PTT sesinden AYRI bir tip kullanır:
    // aynı tipi paylaşsalardı iki ses yolu (bas-konuş ve çağrı) aynı çerçeveye
    // talip olur, hangisinin işleyeceği duruma bağlı kalırdı.
    const val TYPE_CALL_INVITE = 0x30  // payload: [tiebreak u32 LE][codecMask u8]
    const val TYPE_CALL_RINGING = 0x31 // payload: yok — "karşı taraf çalıyor"
    const val TYPE_CALL_ACCEPT = 0x32  // payload: [codecId u8]
    const val TYPE_CALL_DECLINE = 0x33 // payload: [reason u8]
    const val TYPE_CALL_END = 0x34     // payload: yok
    const val TYPE_CALL_AUDIO = 0x35   // payload: [n u8]([len u8][chunk])*n

    // Mesafe ölçümü. Sinyal gücü hattın kendisinden okunamıyor (Android bağlı
    // bir Bluetooth Classic bağlantısının RSSI'ını hiç vermiyor), o yüzden
    // paralel bir BLE ilanı/taraması kuruluyor ve okumalar bu hat üzerinden
    // takas ediliyor.
    const val TYPE_RANGE_HELLO = 0x40  // payload: [token u32 LE][ilanGucu i8]
    const val TYPE_RANGE_REPORT = 0x42 // payload: [rssi i8][ilanGucu i8]

    // Dosya (fotoğraf) aktarımı. Parçalar SIRALI gider; RFCOMM güvenilir bir
    // akış olduğu için yeniden iletim gerekmiyor, ama bütünlük yine de SHA-256
    // ile doğrulanıyor — "kalite asla düşmesin" ancak bit birebir aynılıkla
    // ispatlanabilir.
    const val TYPE_FILE_OFFER = 0x50   // [id u32][boyut u32][sha256 32B][adUzunluk u8][ad UTF-8]
    const val TYPE_FILE_ACCEPT = 0x51  // [id u32]
    const val TYPE_FILE_CHUNK = 0x52   // [id u32][sıra u32][veri]
    const val TYPE_FILE_DONE = 0x53    // [id u32]
    const val TYPE_FILE_CANCEL = 0x54  // [id u32][sebep u8]

    /** Parça yükü: 4 bayt kimlik + 4 bayt sıra, kalanı veri. */
    const val MAX_CHUNK_BYTES = MAX_PAYLOAD - 8

    // Saat eşitleme — senkron gösteri ve çok hoparlörlü ses bunun üstünde durur.
    // NTP'nin yaptığı işin küçük hali: gidiş-dönüş süresinden saat farkı çıkarılır.
    const val TYPE_CLOCK_PING = 0x60   // [seq u32][t1 u64]
    const val TYPE_CLOCK_PONG = 0x61   // [seq u32][t1 u64][t2 u64]

    // Senkron gösteri: "şu ANDA şunu yap". Zaman, eşitlenmiş ortak saatte.
    const val TYPE_SHOW_CUE = 0x62     // [tür u8][başlangıç u64][süre u32][desen u8]
    const val TYPE_SHOW_STOP = 0x63    // payload yok

    // Taşıyıcı ağ (sneakernet): mesajlar cihazdan cihaza elden ele taşınır.
    const val TYPE_CARRY_INDEX = 0x64  // [n u16]([id u32])*n — bende bunlar var
    const val TYPE_CARRY_WANT = 0x65   // [n u16]([id u32])*n — bunları isterim
    const val TYPE_CARRY_ITEM = 0x66   // [id u32][ttl u8][sıçrama u8][zaman u64][len u16][UTF-8]

    // Yakınlık kasası: anahtarın yarısı karşı telefonda durur.
    const val TYPE_VAULT_REQUEST = 0x67 // payload yok
    const val TYPE_VAULT_HALF = 0x68    // [32 bayt anahtar yarısı]

    // TYPE_CALL_DECLINE gerekçeleri
    const val DECLINE_USER = 0
    const val DECLINE_BUSY = 1
    const val DECLINE_TIMEOUT = 2

    /** Metin gövdesi için kalan yer (4 bayt mesaj kimliği düşülmüş). */
    const val MAX_TEXT_BYTES = MAX_PAYLOAD - 4

    /**
     * Mesaj kimliği neden var: hat kopup geri geldiğinde gönderilmemiş mesajlar
     * yeniden yollanır. Alıcı aynı kimliği ikinci kez görürse yinelemeyi atar
     * ama onayı yine gönderir — kaybolan şey mesaj değil, onay olabilir.
     */
    fun encodeText(msgId: Int, text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_TEXT_BYTES) { "text too long: ${body.size} B" }
        val payload = ByteArray(4 + body.size)
        writeIdLe(payload, msgId)
        body.copyInto(payload, 4)
        return payload
    }

    fun encodeAck(msgId: Int): ByteArray = ByteArray(4).also { writeIdLe(it, msgId) }

    /** Bozuk/kısa yükte null döner; çağıran çerçeveyi sessizce atar. */
    fun readMsgId(payload: ByteArray): Int? {
        if (payload.size < 4) return null
        return (payload[0].toInt() and 0xFF) or
                ((payload[1].toInt() and 0xFF) shl 8) or
                ((payload[2].toInt() and 0xFF) shl 16) or
                ((payload[3].toInt() and 0xFF) shl 24)
    }

    /** Geçersiz UTF-8 baytları U+FFFD'ye düşer; çerçeve atılmaz. */
    fun readText(payload: ByteArray): String =
        if (payload.size <= 4) "" else String(payload, 4, payload.size - 4, Charsets.UTF_8)

    private fun writeIdLe(dst: ByteArray, value: Int) {
        dst[0] = (value and 0xFF).toByte()
        dst[1] = ((value shr 8) and 0xFF).toByte()
        dst[2] = ((value shr 16) and 0xFF).toByte()
        dst[3] = ((value shr 24) and 0xFF).toByte()
    }

    fun encode(frame: LinkFrame): ByteArray {
        require(frame.payload.size <= MAX_PAYLOAD) { "payload too large" }
        val out = ByteArrayOutputStream(frame.payload.size + 12)
        out.write(SYNC0)
        out.write(SYNC1)

        val body = ByteArray(HEADER_LEN + frame.payload.size)
        body[0] = VERSION.toByte()
        body[1] = frame.type.toByte()
        body[2] = (frame.seq and 0xFF).toByte()
        body[3] = ((frame.seq shr 8) and 0xFF).toByte()
        body[4] = (frame.payload.size and 0xFF).toByte()
        body[5] = ((frame.payload.size shr 8) and 0xFF).toByte()
        frame.payload.copyInto(body, HEADER_LEN)

        val crc = CRC32()
        crc.update(body)
        val crcVal = crc.value

        out.write(body)
        out.write((crcVal and 0xFF).toInt())
        out.write(((crcVal shr 8) and 0xFF).toInt())
        out.write(((crcVal shr 16) and 0xFF).toInt())
        out.write(((crcVal shr 24) and 0xFF).toInt())
        return out.toByteArray()
    }
}

class LinkFrame(
    val type: Int,
    val seq: Int,
    val payload: ByteArray
)

/**
 * Bayt akışından çerçeve ayıklayan durum makinesi.
 * Gürültülü/kayıplı taşıyıcılarda (seri optik hat) senkron kaybını tolere eder:
 * CRC tutmayan ya da bozuk başlıklı çerçeveler sessizce atılır, arama
 * bir sonraki SYNC çiftinden devam eder.
 */
class FrameParser(private val onFrame: (LinkFrame) -> Unit) {

    private enum class State { SYNC0, SYNC1, HEADER, PAYLOAD_CRC }

    private var state = State.SYNC0
    private val header = ByteArray(LinkProtocol.HEADER_LEN)
    private var headerPos = 0
    private var body: ByteArray = ByteArray(0)   // payload + crc
    private var bodyPos = 0
    private var payloadLen = 0

    var droppedFrames: Long = 0
        private set

    fun feed(data: ByteArray, count: Int = data.size) {
        for (i in 0 until count) {
            val b = data[i].toInt() and 0xFF
            when (state) {
                State.SYNC0 -> if (b == LinkProtocol.SYNC0) state = State.SYNC1

                State.SYNC1 -> state = when (b) {
                    LinkProtocol.SYNC1 -> {
                        headerPos = 0
                        State.HEADER
                    }
                    LinkProtocol.SYNC0 -> State.SYNC1  // 0xAA 0xAA ... dizisi
                    else -> State.SYNC0
                }

                State.HEADER -> {
                    header[headerPos++] = b.toByte()
                    if (headerPos == LinkProtocol.HEADER_LEN) {
                        payloadLen = (header[4].toInt() and 0xFF) or
                                ((header[5].toInt() and 0xFF) shl 8)
                        val version = header[0].toInt() and 0xFF
                        if (version != LinkProtocol.VERSION || payloadLen > LinkProtocol.MAX_PAYLOAD) {
                            droppedFrames++
                            state = State.SYNC0
                        } else {
                            body = ByteArray(payloadLen + 4)
                            bodyPos = 0
                            state = State.PAYLOAD_CRC
                        }
                    }
                }

                State.PAYLOAD_CRC -> {
                    body[bodyPos++] = b.toByte()
                    if (bodyPos == body.size) {
                        finishFrame()
                        state = State.SYNC0
                    }
                }
            }
        }
    }

    private fun finishFrame() {
        val crc = CRC32()
        crc.update(header)
        crc.update(body, 0, payloadLen)
        val expected = crc.value

        val p = payloadLen
        val received = ((body[p].toLong() and 0xFF)) or
                ((body[p + 1].toLong() and 0xFF) shl 8) or
                ((body[p + 2].toLong() and 0xFF) shl 16) or
                ((body[p + 3].toLong() and 0xFF) shl 24)

        if (expected != received) {
            droppedFrames++
            return
        }

        val type = header[1].toInt() and 0xFF
        val seq = (header[2].toInt() and 0xFF) or ((header[3].toInt() and 0xFF) shl 8)
        onFrame(LinkFrame(type, seq, body.copyOfRange(0, payloadLen)))
    }
}
