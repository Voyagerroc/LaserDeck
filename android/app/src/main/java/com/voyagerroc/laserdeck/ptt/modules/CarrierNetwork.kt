package com.voyagerroc.laserdeck.ptt.modules

import com.voyagerroc.laserdeck.ptt.protocol.LinkFrame
import com.voyagerroc.laserdeck.ptt.protocol.LinkProtocol
import com.voyagerroc.laserdeck.ptt.transport.FrameTransport
import com.voyagerroc.laserdeck.ptt.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Taşıyıcı ağ — mesajın insanlarla birlikte yürüdüğü ağ.
 *
 * Normal mesajlaşma iki telefonun aynı anda bağlı olmasını ister. Burada öyle
 * değil: mesajı bir cihaza bırakırsın, o cihaz onu **taşır**, karşılaştığı her
 * yeni cihaza verir, o da taşımaya devam eder. Kimse aynı anda bağlı olmak
 * zorunda değil; bilgi altyapı yerine insanların hareketiyle yayılır.
 *
 * Akademide "gecikmeye toleranslı ağ" denen şey. Afet senaryosunda en anlamlı
 * hali: şebeke yok, kimse kimseye ulaşamıyor, ama insanlar hareket ediyor.
 *
 * ## Yayılma kuralları
 * - Her mesajın bir **sıçrama bütçesi** (TTL) var; her aktarımda bir azalır,
 *   sıfırlanınca daha ileri taşınmaz. Yoksa mesajlar sonsuza dek dolaşırdı.
 * - Her mesajın rastgele bir kimliği var; aynı kimliği ikinci kez alan cihaz
 *   yok sayar. Bu olmadan iki cihaz aynı mesajı sonsuz döngüde takas ederdi.
 * - Bağlantı kurulunca önce **kimlik listesi** takas edilir, sonra yalnızca
 *   eksik olanlar istenir. Her karşılaşmada tüm çantayı yollamak hattı boğardı.
 */
class CarrierNetwork(
    private val transport: FrameTransport,
    baseDir: File,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {}
) {
    companion object {
        /** Varsayılan sıçrama bütçesi. */
        const val DEFAULT_TTL = 6

        /** Çantada tutulan en fazla mesaj. */
        private const val MAX_ITEMS = 200

        /** Bu yaştan sonra mesaj çantadan düşer. */
        private const val EXPIRY_MS = 24 * 60 * 60 * 1000L

        /** Bağlantıdan sonra takasa başlamadan önceki bekleme. */
        private const val SYNC_DELAY_MS = 1_200L
    }

    data class Parcel(
        val id: Int,
        val text: String,
        val createdAt: Long,
        val ttl: Int,
        val hops: Int,
        /** Bu cihazda mı yazıldı, yoksa taşınıyor mu. */
        val mine: Boolean
    )

    private val file = File(baseDir, "tasiyici.jsonl")

    private val _items = MutableStateFlow<List<Parcel>>(emptyList())
    val items: StateFlow<List<Parcel>> = _items

    /** Bu oturumda kaç mesaj ileri taşındı — modülün işe yaradığının ölçüsü. */
    private val _forwarded = MutableStateFlow(0)
    val forwarded: StateFlow<Int> = _forwarded

    private val lock = Any()
    private val bag = LinkedHashMap<Int, Parcel>()

    /**
     * Diske yazma TEK is parcaciginda sirayla yapilir. IO havuzuna dogrudan
     * atilan yazmalar sirasiz kosuyor ve ESKI anlik goruntu yeninin uzerine
     * yaziyordu; ustelik hepsi ayni gecici dosya adini kullandigi icin ic ice
     * girmis, yarim satirli bir dosya birakabiliyordu.
     */
    private val saveExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "carrier-save").apply { isDaemon = true }
    }

    /** Ses akarken toplu takas durur: ayni hat sesi tasiyor. */
    @Volatile private var audioActive = false

    init {
        load()
        scope.launch(Dispatchers.IO) {
            transport.incoming.collect { frame ->
                runCatching { handle(frame) }
                    .onFailure { log("Taşıyıcı çerçevesi atlandı: ${it.message}") }
            }
        }
        scope.launch(Dispatchers.IO) {
            transport.state.collect { s ->
                if (s is TransportState.Connected) {
                    // Karşılaşma anı: biraz bekle, sonra çantaları karşılaştır.
                    delay(SYNC_DELAY_MS)
                    sendIndex()
                }
            }
        }
    }

    // ---------- kullanıcı ----------

    /** Ağa yeni bir mesaj bırakır. */
    fun post(text: String, ttl: Int = DEFAULT_TTL): Boolean {
        val clean = text.trim()
        if (clean.isEmpty() || clean.length > 500) return false
        val parcel = Parcel(
            id = Random.nextInt(),
            text = clean,
            createdAt = System.currentTimeMillis(),
            ttl = ttl.coerceIn(1, 20),
            hops = 0,
            mine = true
        )
        synchronized(lock) { bag[parcel.id] = parcel; trim() }
        publish()
        persist()
        // Karşı taraf zaten bağlıysa hemen yola çıksın.
        if (transport.state.value is TransportState.Connected) sendIndex()
        return true
    }

    fun drop(id: Int) {
        synchronized(lock) { bag.remove(id) }
        publish()
        persist()
    }

    fun setAudioActive(active: Boolean) {
        audioActive = active
    }

    fun clear() {
        synchronized(lock) { bag.clear() }
        publish()
        persist()
    }

    // ---------- takas ----------

    private fun sendIndex() {
        if (audioActive) return
        val ids = synchronized(lock) { bag.keys.toList() }
        val payload = ByteArray(2 + ids.size * 4)
        payload[0] = (ids.size and 0xFF).toByte()
        payload[1] = ((ids.size shr 8) and 0xFF).toByte()
        ids.forEachIndexed { i, id -> writeInt(payload, 2 + i * 4, id) }
        transport.send(LinkFrame(LinkProtocol.TYPE_CARRY_INDEX, 0, payload))
    }

    private fun handle(frame: LinkFrame) {
        when (frame.type) {
            LinkProtocol.TYPE_CARRY_INDEX -> {
                val theirs = readIds(frame.payload) ?: return
                // Onda olup bende olmayanları iste.
                val want = synchronized(lock) { theirs.filter { !bag.containsKey(it) } }
                if (want.isEmpty()) return
                val payload = ByteArray(2 + want.size * 4)
                payload[0] = (want.size and 0xFF).toByte()
                payload[1] = ((want.size shr 8) and 0xFF).toByte()
                want.forEachIndexed { i, id -> writeInt(payload, 2 + i * 4, id) }
                transport.send(LinkFrame(LinkProtocol.TYPE_CARRY_WANT, 0, payload))
            }

            LinkProtocol.TYPE_CARRY_WANT -> {
                val wanted = readIds(frame.payload) ?: return
                val toSend = synchronized(lock) {
                    wanted.mapNotNull { bag[it] }.filter { it.ttl > 0 }
                }
                scope.launch(Dispatchers.IO) {
                    toSend.forEach { parcel ->
                        // Konusma basladiysa bekle: sendBlocking kuyrugu dolu
                        // tutuyor ve ses cerceveleri kuyruk tasmasinda
                        // sessizce dusuruluyor.
                        while (audioActive) delay(200)
                        // Geri baskılı gönderim: toplu takas hattı boğmasın.
                        val ok = transport.sendBlocking(
                            LinkFrame(LinkProtocol.TYPE_CARRY_ITEM, 0, encode(parcel)),
                            4_000L
                        )
                        if (ok) _forwarded.value = _forwarded.value + 1
                        delay(40)
                    }
                    if (toSend.isNotEmpty()) log("Taşıyıcı: ${toSend.size} mesaj aktarıldı")
                }
            }

            LinkProtocol.TYPE_CARRY_ITEM -> {
                val parcel = decode(frame.payload) ?: return
                val fresh = synchronized(lock) {
                    if (bag.containsKey(parcel.id)) return@synchronized false
                    // Sıçrama bütçesi bir azalır: mesaj sonsuza dek dolaşmasın.
                    bag[parcel.id] = parcel.copy(
                        ttl = parcel.ttl - 1,
                        hops = parcel.hops + 1,
                        mine = false
                    )
                    trim()
                    true
                }
                if (!fresh) return
                publish()
                persist()
                log("Taşıyıcı: yeni mesaj alındı (${parcel.hops + 1}. sıçrama)")
            }
        }
    }

    // ---------- kalıcılık ----------

    private fun load() {
        val loaded = runCatching {
            if (!file.exists()) return@runCatching emptyList<Parcel>()
            file.readLines().mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                runCatching {
                    val o = JSONObject(line)
                    Parcel(
                        id = o.getInt("id"),
                        text = o.getString("text"),
                        createdAt = o.getLong("ts"),
                        ttl = o.getInt("ttl"),
                        hops = o.getInt("hops"),
                        mine = o.getBoolean("mine")
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())

        val now = System.currentTimeMillis()
        synchronized(lock) {
            loaded.filter { now - it.createdAt < EXPIRY_MS }.forEach { bag[it.id] = it }
        }
        publish()
    }

    private fun persist() {
        val snapshot = synchronized(lock) { bag.values.toList() }
        runCatching {
            saveExecutor.execute {
                runCatching {
                    file.parentFile?.mkdirs()
                    val text = buildString {
                        snapshot.forEach { p ->
                            append(
                                JSONObject()
                                    .put("id", p.id).put("text", p.text)
                                    .put("ts", p.createdAt).put("ttl", p.ttl)
                                    .put("hops", p.hops).put("mine", p.mine)
                                    .toString()
                            )
                            append('\n')
                        }
                    }
                    val tmp = File(file.parentFile, "${file.name}.${Thread.currentThread().id}.tmp")
                    tmp.writeText(text)
                    if (!tmp.renameTo(file)) {
                        file.writeText(text); tmp.delete()
                    }
                }
            }
        }
    }

    /** lock altında çağrılır. */
    private fun trim() {
        val now = System.currentTimeMillis()
        bag.entries.removeAll { now - it.value.createdAt > EXPIRY_MS }
        while (bag.size > MAX_ITEMS) {
            val oldest = bag.keys.firstOrNull() ?: break
            bag.remove(oldest)
        }
    }

    private fun publish() {
        _items.value = synchronized(lock) { bag.values.sortedByDescending { it.createdAt } }
    }

    // ---------- bayt ----------

    private fun encode(p: Parcel): ByteArray {
        val text = p.text.toByteArray(Charsets.UTF_8)
        val out = ByteArray(4 + 1 + 1 + 8 + 2 + text.size)
        writeInt(out, 0, p.id)
        out[4] = p.ttl.toByte()
        out[5] = p.hops.toByte()
        writeLong(out, 6, p.createdAt)
        out[14] = (text.size and 0xFF).toByte()
        out[15] = ((text.size shr 8) and 0xFF).toByte()
        text.copyInto(out, 16)
        return out
    }

    private fun decode(src: ByteArray): Parcel? {
        if (src.size < 16) return null
        val id = readInt(src, 0)
        val ttl = src[4].toInt() and 0xFF
        val hops = src[5].toInt() and 0xFF
        val ts = readLong(src, 6)
        val len = (src[14].toInt() and 0xFF) or ((src[15].toInt() and 0xFF) shl 8)
        if (src.size < 16 + len || len == 0) return null
        val text = String(src, 16, len, Charsets.UTF_8)
        // Hattan gelen TTL sinirlanmali: kotu niyetli bir es 255 sicramalik
        // bir mesaj enjekte edebilirdi.
        return Parcel(id, text, ts, ttl.coerceIn(0, DEFAULT_TTL), hops, mine = false)
    }

    private fun readIds(src: ByteArray): List<Int>? {
        if (src.size < 2) return null
        val n = (src[0].toInt() and 0xFF) or ((src[1].toInt() and 0xFF) shl 8)
        if (src.size < 2 + n * 4) return null
        return (0 until n).map { readInt(src, 2 + it * 4) }
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
