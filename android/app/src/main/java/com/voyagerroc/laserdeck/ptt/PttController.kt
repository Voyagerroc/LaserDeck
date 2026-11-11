package com.voyagerroc.laserdeck.ptt

import com.voyagerroc.laserdeck.ptt.audio.AudioEngine
import com.voyagerroc.laserdeck.ptt.audio.VoiceCodec
import com.voyagerroc.laserdeck.ptt.protocol.LinkFrame
import com.voyagerroc.laserdeck.ptt.protocol.LinkProtocol
import com.voyagerroc.laserdeck.ptt.transport.FrameTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bas-konuş durum makinesi: yarı çift yönlü (half-duplex) telsiz mantığı.
 *
 * TX akışı:  PTT basılı -> AUDIO_START -> AUDIO_FRAME* -> PTT bırakıldı -> AUDIO_END
 * RX akışı:  AUDIO_START gelince codec kur + çalmayı başlat; karşı taraf
 *            konuşurken yerel PTT kilitlenir (canTalk = false).
 *
 * AUDIO_FRAME payload'ı, çerçeve yükünü azaltmak için FRAMES_PER_PACKET adet
 * codec çerçevesini [n][len][chunk]... biçiminde paketler (~80 ms/paket).
 */
class PttController(
    private val transport: FrameTransport,
    scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val onStateChanged: () -> Unit = {}
) {
    companion object {
        const val FRAMES_PER_PACKET = 4
        val TX_CODEC_ID = VoiceCodec.ID_AMR_NB
    }

    private val seq = AtomicInteger(0)
    private val talking = AtomicBoolean(false)
    private val receiving = AtomicBoolean(false)

    /** release() sonrası gelen çerçeveler yeni bir motor kurup ses çalmasın. */
    @Volatile private var released = false

    @Volatile private var txEngine: AudioEngine? = null
    @Volatile private var rxEngine: AudioEngine? = null

    /** RX motoru bir iş parçacığında serbest bırakılırken başkası decode etmesin.
     *  Bu kilit olmadan "Kes" tuşu karşı taraf konuşurken uygulamayı çökertiyordu. */
    private val rxLock = Any()

    private val txBatch = ByteArrayOutputStream()
    private var txBatchCount = 0

    val isTalking: Boolean get() = talking.get()
    val isReceiving: Boolean get() = receiving.get()
    /** Karşı taraf konuşurken PTT kilitli kalır (yarı çift yönlü). */
    val canTalk: Boolean get() = !receiving.get()

    init {
        scope.launch(Dispatchers.IO) {
            // Tek bir bozuk çerçeve ya da yarışan release() çağrısı, yakalanmayan
            // istisnayla süreci öldürmesin: hata = düşen çerçeve.
            transport.incoming.collect { frame ->
                runCatching { handleIncoming(frame) }
                    .onFailure { log("RX çerçeve atlandı: ${it.message}") }
            }
        }
    }

    // ---------- TX ----------

    fun startTalking() {
        if (!canTalk || !talking.compareAndSet(false, true)) return

        // Mikrofon başkasındaysa (ör. ekran kaydedici) ya da codec açılamazsa
        // çökmek yerine kullanıcıya söyle ve durumu geri al.
        val codec = VoiceCodec.forId(TX_CODEC_ID)
        val engine = runCatching { AudioEngine(codec) }.getOrElse {
            runCatching { codec.release() }
            talking.set(false)
            log("TX açılamadı (mikrofon meşgul olabilir): ${it.message}")
            onStateChanged()
            return
        }
        txEngine = engine

        val startPayload = byteArrayOf(
            codec.id.toByte(),
            (codec.sampleRate and 0xFF).toByte(),
            ((codec.sampleRate shr 8) and 0xFF).toByte()
        )
        send(LinkProtocol.TYPE_AUDIO_START, startPayload)
        log("TX: konuşma başladı (codec=${codec.id})")

        synchronized(txBatch) { txBatch.reset(); txBatchCount = 0 }
        runCatching { engine.startCapture { encoded -> queueEncodedFrame(encoded) } }
            .onFailure {
                log("TX: mikrofon açılamadı: ${it.message}")
                stopTalking()
                return
            }
        onStateChanged()
    }

    fun stopTalking() {
        if (!talking.compareAndSet(true, false)) return
        txEngine?.let { engine ->
            engine.stopCapture()
            engine.release()
        }
        txEngine = null
        flushBatch()
        send(LinkProtocol.TYPE_AUDIO_END, ByteArray(0))
        log("TX: konuşma bitti")
        onStateChanged()
    }

    private fun queueEncodedFrame(encoded: ByteArray) {
        if (encoded.size > 255) return // tek bayt uzunluk öneki varsayımı
        val toSend: ByteArray?
        synchronized(txBatch) {
            txBatch.write(encoded.size)
            txBatch.write(encoded, 0, encoded.size)
            txBatchCount++
            toSend = if (txBatchCount >= FRAMES_PER_PACKET) buildBatchLocked() else null
        }
        toSend?.let { send(LinkProtocol.TYPE_AUDIO_FRAME, it) }
    }

    private fun flushBatch() {
        val toSend: ByteArray?
        synchronized(txBatch) {
            toSend = if (txBatchCount > 0) buildBatchLocked() else null
        }
        toSend?.let { send(LinkProtocol.TYPE_AUDIO_FRAME, it) }
    }

    /** txBatch kilidi altında çağrılır. */
    private fun buildBatchLocked(): ByteArray {
        val chunks = txBatch.toByteArray()
        val payload = ByteArray(1 + chunks.size)
        payload[0] = txBatchCount.toByte()
        chunks.copyInto(payload, 1)
        txBatch.reset()
        txBatchCount = 0
        return payload
    }

    // ---------- RX ----------

    private fun handleIncoming(frame: LinkFrame) {
        if (released) return
        when (frame.type) {
            LinkProtocol.TYPE_AUDIO_START -> {
                if (frame.payload.isEmpty()) return
                val codecId = frame.payload[0].toInt() and 0xFF
                synchronized(rxLock) {
                    runCatching {
                        rxEngine?.release()
                        rxEngine = null
                        val engine = AudioEngine(VoiceCodec.forId(codecId))
                        engine.startPlayback()
                        rxEngine = engine
                        receiving.set(true)
                    }.onFailure { log("RX: codec kurulamadı: ${it.message}") }
                }
                if (receiving.get()) {
                    log("RX: karşı taraf konuşuyor (codec=$codecId)")
                    onStateChanged()
                }
            }

            LinkProtocol.TYPE_AUDIO_FRAME -> {
                val p = frame.payload
                if (p.isEmpty()) return
                synchronized(rxLock) {
                    val engine = rxEngine ?: return
                    val count = p[0].toInt() and 0xFF
                    var off = 1
                    repeat(count) {
                        if (off >= p.size) return
                        val len = p[off].toInt() and 0xFF
                        off += 1
                        if (off + len > p.size) return
                        engine.playEncoded(p.copyOfRange(off, off + len))
                        off += len
                    }
                }
            }

            LinkProtocol.TYPE_AUDIO_END -> {
                synchronized(rxLock) {
                    rxEngine?.release()
                    rxEngine = null
                }
                receiving.set(false)
                log("RX: karşı taraf sustu")
                onStateChanged()
            }

            LinkProtocol.TYPE_PING ->
                send(LinkProtocol.TYPE_PONG, frame.payload)

            LinkProtocol.TYPE_PONG ->
                log("PONG alındı (seq=${frame.seq})")

            // TYPE_TEXT / TYPE_TEXT_ACK: Messenger kendi toplayıcısıyla dinler.
        }
    }

    // ---------- ortak ----------

    private fun send(type: Int, payload: ByteArray) {
        transport.send(LinkFrame(type, seq.incrementAndGet() and 0xFFFF, payload))
    }

    fun sendPing() = send(LinkProtocol.TYPE_PING, ByteArray(0))

    /**
     * Taşıyıcı bağlantısı koptuğunda çağrılır (yeniden bağlanma sürerken de):
     * yarım kalan TX/RX oturumlarını sıfırlar ki PTT kilidi askıda kalmasın.
     */
    fun onLinkLost() {
        stopTalking()
        synchronized(rxLock) {
            rxEngine?.release()
            rxEngine = null
        }
        if (receiving.compareAndSet(true, false)) {
            log("RX: bağlantı koptu, alım sıfırlandı")
            onStateChanged()
        }
    }

    fun release() {
        released = true
        stopTalking()
        synchronized(rxLock) {
            rxEngine?.release()
            rxEngine = null
        }
        receiving.set(false)
    }
}
