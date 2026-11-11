package com.voyagerroc.laserdeck.ptt.sync

import android.os.SystemClock
import com.voyagerroc.laserdeck.ptt.protocol.LinkFrame
import com.voyagerroc.laserdeck.ptt.protocol.LinkProtocol
import com.voyagerroc.laserdeck.ptt.transport.FrameTransport
import com.voyagerroc.laserdeck.ptt.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * İki telefonun saatlerini ortak bir zamana oturtur.
 *
 * NTP'nin yaptığı işin küçük hali. A, kendi saatiyle t1'de bir sorgu yollar;
 * B onu t2'de alır ve t1 ile t2'yi geri gönderir; A yanıtı t4'te alır.
 *
 *     fark  = ((t2 − t1) + (t2 − t4)) / 2
 *     gidiş-dönüş = t4 − t1
 *
 * Ölçüm gürültülüdür çünkü RFCOMM gecikmesi düzensiz. Ama gürültü TEK YÖNLÜDÜR:
 * bir paket beklenenden erken varamaz, yalnızca gecikebilir. Bu yüzden **en
 * kısa gidiş-dönüşe sahip örnek** en doğru olanıdır ve ortalama almak yerine
 * onu seçiyoruz — ortalama, her takılmayı hataya çevirirdi.
 *
 * [SystemClock.elapsedRealtimeNanos] kullanılıyor: monoton, kullanıcı saati
 * değiştirse ya da saat dilimi kaysa da etkilenmez.
 */
class ClockSync(
    private val transport: FrameTransport,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {}
) {
    companion object {
        private const val PING_PERIOD_MS = 1_500L

        /** Bu kadar örnek arasından en iyisi seçilir. */
        private const val WINDOW = 8

        /** Bu süre boyunca yeni ölçüm gelmezse eşitleme bayatlamış sayılır. */
        private const val STALE_MS = 15_000L
    }

    data class Sync(
        /** Karşı saatin bizimkinden farkı (ns). remote = local + offset. */
        val offsetNanos: Long = 0L,
        /** Bu kestirimin dayandığı gidiş-dönüş süresi (ns). */
        val rttNanos: Long = 0L,
        val ready: Boolean = false
    ) {
        /** Kaba doğruluk: gidiş-dönüşün yarısı kadar belirsizlik var. */
        val accuracyMs: Long get() = (rttNanos / 2) / 1_000_000
    }

    private val _sync = MutableStateFlow(Sync())
    val sync: StateFlow<Sync> = _sync

    private val seq = AtomicInteger(0)
    private val lock = Any()
    private val samples = ArrayDeque<Pair<Long, Long>>() // (rtt, offset)
    private val pending = HashMap<Int, Long>()           // seq -> t1
    private var lastUpdate = 0L
    private var job: Job? = null

    init {
        scope.launch(Dispatchers.IO) {
            transport.incoming.collect { frame ->
                runCatching { handle(frame) }
                    .onFailure { log("Saat çerçevesi atlandı: ${it.message}") }
            }
        }
        scope.launch(Dispatchers.IO) {
            transport.state.collect { s ->
                if (s is TransportState.Connected) start() else stop()
            }
        }
    }

    private fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                ping()
                delay(PING_PERIOD_MS)
            }
        }
    }

    private fun stop() {
        job?.cancel()
        job = null
        synchronized(lock) {
            samples.clear()
            pending.clear()
        }
        _sync.value = Sync()
    }

    private fun ping() {
        val id = seq.incrementAndGet() and 0xFFFF
        val t1 = SystemClock.elapsedRealtimeNanos()
        synchronized(lock) {
            pending[id] = t1
            // Cevapsız kalanlar birikmesin.
            if (pending.size > 32) {
                pending.keys.take(pending.size - 32).forEach { pending.remove(it) }
            }
        }
        transport.send(LinkFrame(LinkProtocol.TYPE_CLOCK_PING, id, encode(id, t1)))
    }

    private fun handle(frame: LinkFrame) {
        when (frame.type) {
            LinkProtocol.TYPE_CLOCK_PING -> {
                if (frame.payload.size < 12) return
                val id = readInt(frame.payload, 0)
                val t1 = readLong(frame.payload, 4)
                val t2 = SystemClock.elapsedRealtimeNanos()
                val out = ByteArray(20)
                writeInt(out, 0, id)
                writeLong(out, 4, t1)
                writeLong(out, 12, t2)
                transport.send(LinkFrame(LinkProtocol.TYPE_CLOCK_PONG, frame.seq, out))
            }

            LinkProtocol.TYPE_CLOCK_PONG -> {
                if (frame.payload.size < 20) return
                val id = readInt(frame.payload, 0)
                val t2 = readLong(frame.payload, 12)
                val t4 = SystemClock.elapsedRealtimeNanos()
                val t1 = synchronized(lock) { pending.remove(id) } ?: return

                val rtt = t4 - t1
                if (rtt <= 0) return
                // Tek bir zaman damgası döndüğü için t3 = t2 varsayılıyor;
                // karşı tarafın işleme süresi mikrosaniye mertebesinde.
                val offset = ((t2 - t1) + (t2 - t4)) / 2

                val best = synchronized(lock) {
                    samples.addLast(rtt to offset)
                    while (samples.size > WINDOW) samples.removeFirst()
                    // EN KISA gidiş-dönüş en az bozulmuş ölçümdür.
                    samples.minByOrNull { it.first }
                } ?: return

                lastUpdate = SystemClock.elapsedRealtime()
                _sync.value = Sync(
                    offsetNanos = best.second,
                    rttNanos = best.first,
                    ready = true
                )
            }
        }
    }

    /** Karşı tarafın saatindeki bir anı bizim saatimize çevirir. */
    fun remoteToLocal(remoteNanos: Long): Long = remoteNanos - _sync.value.offsetNanos

    /** Bizim saatimizdeki bir anı karşı tarafın saatine çevirir. */
    fun localToRemote(localNanos: Long): Long = localNanos + _sync.value.offsetNanos

    fun isFresh(): Boolean =
        _sync.value.ready && SystemClock.elapsedRealtime() - lastUpdate < STALE_MS

    // ---------- bayt yardımcıları ----------

    private fun encode(id: Int, t1: Long) = ByteArray(12).also {
        writeInt(it, 0, id)
        writeLong(it, 4, t1)
    }

    private fun writeInt(dst: ByteArray, off: Int, v: Int) {
        for (i in 0 until 4) dst[off + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    private fun readInt(src: ByteArray, off: Int): Int {
        var v = 0
        for (i in 0 until 4) v = v or ((src[off + i].toInt() and 0xFF) shl (8 * i))
        return v
    }

    private fun writeLong(dst: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) dst[off + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    private fun readLong(src: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((src[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }
}
