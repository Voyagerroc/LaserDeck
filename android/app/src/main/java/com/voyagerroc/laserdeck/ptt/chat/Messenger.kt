package com.voyagerroc.laserdeck.ptt.chat

import com.voyagerroc.laserdeck.ptt.protocol.LinkFrame
import com.voyagerroc.laserdeck.ptt.protocol.LinkProtocol
import com.voyagerroc.laserdeck.ptt.transport.FrameTransport
import com.voyagerroc.laserdeck.ptt.transport.TransportState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Telsiz üzerinden metin mesajlaşma.
 *
 * Taşıyıcı (RFCOMM) kendi içinde güvenilirdir, ama *hat* değildir: karşı telefon
 * cebe girince bağlantı kopar, sonra kendiliğinden geri gelir. Bu yüzden mesaj
 * gönderimi "yaz ve unut" değil, küçük bir kuyruk olarak modellendi:
 *
 *   yazıldı -> (hat varsa) sokete yazıldı -> karşıdan onay geldi
 *   QUEUED  ->  SENT                      ->  DELIVERED
 *
 * Hat kopukken yazılan mesaj QUEUED'da bekler; bağlantı dönünce sıradaki her şey
 * en eskiden başlayarak yeniden yollanır. Onaysız kalan mesajlar da belirli
 * aralıkla tazelenir — kaybolan onay yüzünden mesaj sonsuza dek "gitmedi"
 * görünmesin diye.
 *
 * Yineleme ayıklama alıcı tarafta mesaj kimliğiyle yapılır: aynı kimliği ikinci
 * kez gören taraf mesajı yazmaz ama onayı yine gönderir.
 *
 * ## İş parçacığı düzeni
 *
 * Sınıf üç ayrı coroutine'den (gelen çerçeve toplayıcısı, taşıyıcı durum
 * toplayıcısı, yeniden gönderim döngüsü) ve arayüz iş parçacığından sürülüyor.
 * İki kilit var, sırası önemli:
 *
 *  - [lock]: bellekteki koleksiyonlar ve [peerAddress]. Yalnızca hızlı işler;
 *    bu kilit ARAYÜZ İŞ PARÇACIĞINCA da alınıyor, altında ASLA disk işi yapılmaz.
 *  - [bindLock]: cihaz değiştirme. Disk okuması bunun altında yapılır; arayüz
 *    bu kilidi hiç almaz, o yüzden ekranı bloklamaz. Sırası: bindLock -> lock.
 *
 * Diske yazma tek bir iş parçacığına (saveExecutor) veriliyor: IO havuzuna
 * doğrudan atılan iki yazma sırasız koşabiliyor ve ESKİ anlık görüntü yeninin
 * üzerine yazıp mesaj kaybettirebiliyordu.
 */
class Messenger(
    private val transport: FrameTransport,
    private val store: MessageStore,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val onIncoming: (ChatMessage) -> Unit = {},
    /** Testte deterministik çalışabilmek için dışarıdan verilebilir. */
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        /** Kullanıcı arayüzünde de aynı sınır uygulanır. */
        const val MAX_CHARS = 800

        private const val RETRY_PERIOD_MS = 3_000L
        private const val RETRY_AFTER_MS = 4_000L

        /** Yineleme ayıklama için akılda tutulan gelen mesaj kimliği sayısı. */
        private const val SEEN_LIMIT = 400
    }

    /** Gönderim reddedildiyse sebebi: arayüz doğru açıklamayı gösterebilsin. */
    enum class SendResult { OK, EMPTY, TOO_LONG, NO_PEER }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val seq = AtomicInteger(Random.nextInt())

    private val lock = Any()
    private val items = ArrayList<ChatMessage>()
    private val seen = LinkedHashSet<Int>()
    private val lastAttempt = HashMap<Int, Long>()
    /** [lock] altında okunur/yazılır — adres ve liste birlikte tutarlı alınmalı. */
    private var peerAddress: String? = null

    private val bindLock = Any()

    /**
     * Bekleyen diske yazmalar, cihaz adresi başına birleştirilir. Adres başına
     * ayrı tutmak şart: cihaz değiştirilirken hem eski hem yeni cihazın kaydı
     * kuyruğa giriyor, tek gözlü bir tampon bunlardan birini düşürürdü.
     */
    private val pendingSaves = ConcurrentHashMap<String, List<ChatMessage>>()
    private val saveExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "chat-save").apply { isDaemon = true }
    }

    @Volatile private var released = false

    init {
        scope.launch(io) {
            transport.incoming.collect { frame ->
                runCatching { handleIncoming(frame) }
                    .onFailure { log("Mesaj çerçevesi atlandı: ${it.message}") }
            }
        }
        scope.launch(io) {
            transport.state.collect { s ->
                if (s is TransportState.Connected) {
                    bindPeer(s.address)
                    flushPending(force = true)
                }
            }
        }
        scope.launch(io) {
            while (isActive && !released) {
                delay(RETRY_PERIOD_MS)
                runCatching { flushPending(force = false) }
            }
        }
    }

    // ---------- eş seçimi ----------

    /**
     * Sohbeti bir cihaza bağlar ve geçmişini yükler. Aynı adres için tekrar
     * çağrılması zararsızdır — yeniden bağlanma döngüsünde sık sık olur.
     *
     * Tümü [bindLock] altında: iki çağrı (biri PttService.connectTo'dan, biri
     * bağlantı durumu toplayıcısından) aynı anda girerse eski cihazın geçmişi
     * yeni cihazın mesajlarıyla üzerine yazılıyordu.
     */
    fun bindPeer(address: String) {
        if (released) return
        synchronized(bindLock) {
            if (released) return
            val previous = synchronized(lock) {
                if (peerAddress == address) return
                peerAddress
            }
            // Eski cihazın son hali, listeyi boşaltmadan önce alınır.
            val carry = if (previous != null) synchronized(lock) { ArrayList(items) } else null
            val loaded = store.load(address)   // disk: lock ALTINDA DEĞİL

            synchronized(lock) {
                peerAddress = address
                items.clear()
                items.addAll(loaded)
                seen.clear()
                // Uygulama yeniden açıldıysa yineleme ayıklama geçmişten kurulur,
                // yoksa karşı taraf onaysız mesajını yollayınca ikinci kez yazılırdı.
                loaded.forEach { if (!it.outgoing) seen.add(it.id) }
                lastAttempt.clear()
            }
            if (previous != null && carry != null) queueSave(previous, carry)
        }
        publish()
    }

    // ---------- gönderme ----------

    /**
     * Aktarimi tamamlanan bir fotografi sohbete yazar.
     *
     * Metin mesajlarindan farkli olarak burada onay/yeniden gonderim yok:
     * dosya aktariminin kendi dogrulamasi (SHA-256) zaten islemi bitirmis
     * durumda, bu yalnizca sohbet kaydi.
     */
    fun addPhoto(name: String, path: String, outgoing: Boolean) {
        if (released) return
        val msg = ChatMessage(
            id = seq.incrementAndGet(),
            outgoing = outgoing,
            text = name,
            timestamp = System.currentTimeMillis(),
            state = if (outgoing) ChatMessage.State.DELIVERED else ChatMessage.State.INCOMING,
            attachment = path
        )
        val ok = synchronized(lock) {
            if (peerAddress == null) return
            items.add(msg)
            trimLocked()
            true
        }
        if (!ok) return
        publish()
        persist()
    }

    /** Dönüş, mesajın kabul edildiğini söyler — *ulaştığını* değil. */
    fun send(text: String): SendResult {
        if (released) return SendResult.NO_PEER
        val clean = text.trim()
        if (clean.isEmpty()) return SendResult.EMPTY
        if (clean.length > MAX_CHARS) return SendResult.TOO_LONG
        // Sınır bayt cinsinden de denetlenmeli: 800 karakterlik Çince/Japonca ya
        // da tipografik tire içeren metin karakter sınırına uyup çerçeveye
        // sığmıyor, sonsuza dek "KUYRUKTA" kalıyordu.
        if (clean.toByteArray(Charsets.UTF_8).size > LinkProtocol.MAX_TEXT_BYTES) {
            return SendResult.TOO_LONG
        }

        val msg = ChatMessage(
            id = seq.incrementAndGet(),
            outgoing = true,
            text = clean,
            timestamp = System.currentTimeMillis(),
            state = ChatMessage.State.QUEUED
        )
        val accepted = synchronized(lock) {
            if (peerAddress == null) {
                false
            } else {
                items.add(msg)
                trimLocked()
                true
            }
        }
        if (!accepted) return SendResult.NO_PEER

        publish()
        scope.launch(io) {
            transmit(msg)
            persist()
        }
        return SendResult.OK
    }

    /** Sokete yazmayı dener; hat yoksa mesaj kuyrukta kalır. */
    private fun transmit(msg: ChatMessage) {
        if (transport.state.value !is TransportState.Connected) return
        val payload = runCatching { LinkProtocol.encodeText(msg.id, msg.text) }.getOrElse {
            // Deneme zamanını yine de işaretle: aksi halde yeniden gönderim
            // döngüsü bu mesajı her 3 saniyede bir sonsuza dek deniyordu.
            synchronized(lock) { lastAttempt[msg.id] = System.currentTimeMillis() }
            log("Mesaj kodlanamadı: ${it.message}")
            return
        }
        transport.send(LinkFrame(LinkProtocol.TYPE_TEXT, msg.id and 0xFFFF, payload))
        synchronized(lock) { lastAttempt[msg.id] = System.currentTimeMillis() }
        updateState(msg.id) { if (it == ChatMessage.State.QUEUED) ChatMessage.State.SENT else it }
    }

    /**
     * Onaylanmamış giden mesajları yeniden yollar.
     * [force] yeniden bağlanma anında kullanılır: bekleme süresi aranmaz.
     */
    private fun flushPending(force: Boolean) {
        if (released || transport.state.value !is TransportState.Connected) return
        val now = System.currentTimeMillis()
        val due = synchronized(lock) {
            items.filter { m ->
                m.outgoing && m.state != ChatMessage.State.DELIVERED &&
                        (force || now - (lastAttempt[m.id] ?: 0L) > RETRY_AFTER_MS)
            }
        }
        if (due.isEmpty()) return
        due.forEach { transmit(it) }   // en eskiden yeniye: sıra korunur
    }

    // ---------- alma ----------

    private fun handleIncoming(frame: LinkFrame) {
        if (released) return
        when (frame.type) {
            LinkProtocol.TYPE_TEXT -> {
                val id = LinkProtocol.readMsgId(frame.payload) ?: return
                val body = LinkProtocol.readText(frame.payload)

                // Onay her durumda gider: yineleme demek, önceki onayın
                // kaybolduğu anlamına gelir.
                transport.send(
                    LinkFrame(LinkProtocol.TYPE_TEXT_ACK, frame.seq, LinkProtocol.encodeAck(id))
                )
                if (body.isEmpty()) return

                val added: ChatMessage? = synchronized(lock) {
                    if (peerAddress == null) return@synchronized null
                    if (!seen.add(id)) return@synchronized null
                    while (seen.size > SEEN_LIMIT) {
                        val oldest = seen.iterator()
                        oldest.next()
                        oldest.remove()
                    }
                    ChatMessage(
                        id = id,
                        outgoing = false,
                        text = body,
                        timestamp = System.currentTimeMillis(),
                        state = ChatMessage.State.INCOMING
                    ).also {
                        items.add(it)
                        trimLocked()
                    }
                }
                if (added == null) return   // yineleme: onay gitti, kayıt yok
                publish()
                persist()
                onIncoming(added)
            }

            LinkProtocol.TYPE_TEXT_ACK -> {
                val id = LinkProtocol.readMsgId(frame.payload) ?: return
                val changed = updateState(id) { ChatMessage.State.DELIVERED }
                if (changed) {
                    synchronized(lock) { lastAttempt.remove(id) }
                    persist()
                }
            }
        }
    }

    // ---------- ortak ----------

    /** Verilen kimlikli giden mesajın durumunu günceller; değişiklik olduysa true. */
    private fun updateState(id: Int, transform: (ChatMessage.State) -> ChatMessage.State): Boolean {
        val changed = synchronized(lock) {
            val i = items.indexOfFirst { it.id == id && it.outgoing }
            if (i < 0) return@synchronized false
            val old = items[i]
            val next = transform(old.state)
            if (next == old.state) return@synchronized false
            items[i] = old.copy(state = next)
            true
        }
        if (changed) publish()
        return changed
    }

    /**
     * Anlık görüntüyü *kilit altında* yayınlar.
     *
     * Kopyayı kilit altında alıp atamayı dışarıda yapmak yetmiyordu: iki
     * yayıncı araya girince ESKİ liste sonra atanıp yeni gelen mesajı ekrandan
     * düşürüyordu — ve kendiliğinden yeniden yayınlayan bir şey olmadığı için
     * mesaj uygulama yeniden açılana dek görünmez kalıyordu.
     */
    private fun publish() {
        synchronized(lock) { _messages.value = ArrayList(items) }
    }

    /** [lock] altında çağrılır. Bellekteki liste de diskteki tavana uyar. */
    private fun trimLocked() {
        while (items.size > MessageStore.MAX_MESSAGES) items.removeAt(0)
    }

    private fun persist() {
        val snapshot = synchronized(lock) {
            val address = peerAddress ?: return
            address to ArrayList(items)
        }
        queueSave(snapshot.first, snapshot.second)
    }

    /** Adres başına birleştirir; tek iş parçacığı sırayı garanti eder. */
    private fun queueSave(address: String, snapshot: List<ChatMessage>) {
        pendingSaves[address] = snapshot
        runCatching {
            saveExecutor.execute {
                val snap = pendingSaves.remove(address) ?: return@execute
                runCatching { store.save(address, snap) }
                    .onFailure { log("Sohbet kaydedilemedi: ${it.message}") }
            }
        }
    }

    /**
     * Servis kapanırken çağrılır. Diske yazma ana iş parçacığında YAPILMAZ:
     * son anlık görüntü kuyruğa verilir, executor kapatılır ve kuyruktakiler
     * kendi iş parçacığında tamamlanır.
     */
    fun release() {
        released = true
        val snapshot = synchronized(lock) { peerAddress?.let { it to ArrayList(items) } }
        snapshot?.let { queueSave(it.first, it.second) }
        runCatching { saveExecutor.shutdown() }
    }

    /**
     * Yalnızca test için: bekleyen diske yazmalar bitene kadar bekler.
     * Tek iş parçacığı FIFO çalıştığından, sıraya eklenen boş bir görevin
     * çalışması kendinden öncekilerin bittiği anlamına gelir.
     */
    internal fun awaitSavesForTest(timeoutMs: Long = 2_000): Boolean = runCatching {
        if (saveExecutor.isShutdown) {
            saveExecutor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
        } else {
            val done = CountDownLatch(1)
            saveExecutor.execute { done.countDown() }
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }.getOrDefault(false)
}
