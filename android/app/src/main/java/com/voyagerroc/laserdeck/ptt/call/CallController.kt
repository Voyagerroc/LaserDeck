package com.voyagerroc.laserdeck.ptt.call

import android.content.Context
import android.os.PowerManager
import com.voyagerroc.laserdeck.ptt.audio.CallAudioSession
import com.voyagerroc.laserdeck.ptt.audio.VoiceCodec
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
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Çağrının o anki durumu. Arayüz yalnızca buna bakar. */
sealed class CallState {
    data object Idle : CallState()

    /** Biz arıyoruz. [remoteRinging] karşı taraftan "zilim çalıyor" geldiğinde true. */
    data class Dialing(val peer: String, val remoteRinging: Boolean) : CallState()

    /** Bizi arıyorlar, zil çalıyor. */
    data class Incoming(val peer: String) : CallState()

    /** Konuşma açık. Süre [startedAt] üzerinden hesaplanır. */
    data class Active(
        val peer: String,
        val startedAt: Long,
        val codecId: Int,
        val effects: CallAudioSession.Effects
    ) : CallState()

    /** Kısa süreli kapanış durumu; ardından kendiliğinden [Idle]'a döner. */
    data class Ended(val reason: Reason) : CallState()

    enum class Reason { HUNG_UP, DECLINED, BUSY, NO_ANSWER, MISSED, LINK_LOST, FAILED }
}

/** Sessiz/hoparlör anahtarları — ayrı akış, çünkü çağrı durumunu değiştirmezler. */
data class CallControls(val muted: Boolean = false, val speakerOn: Boolean = false)

/**
 * Telsiz üzerinden çift yönlü çağrı.
 *
 * Bas-konuştan farkı yalnızca sesin iki yönlü olması değil; çağrının bir
 * *oturumu* var: davet gider, karşı taraf çalar, kabul eder ya da reddeder,
 * biri kapatır. Bu yüzden ses yolu ancak kabulden sonra açılır ve codec iki
 * tarafın ortak desteğine göre seçilir (geniş bant varsa o).
 *
 * Çağrı sesi PTT'den AYRI çerçeve tipi kullanır ([LinkProtocol.TYPE_CALL_AUDIO]);
 * böylece iki ses yolu birbirinin çerçevesine karışmaz.
 *
 * ## Aynı anda arama (glare)
 * İki taraf da aynı anda ararsa ikisi de "arıyor" durumunda kalır ve kimse
 * cevap veremezdi. Davetin içinde rastgele bir üstünlük sayısı taşınıyor:
 * sayısı küçük olan taraf kendi aramasından vazgeçip karşınınkini çalar.
 *
 * ## İş parçacığı
 * Kullanıcı eylemleri (ara/yanıtla/kapat) MediaCodec kurma, AudioRecord açma
 * ve iş parçacığı bekleme gibi yüzlerce ms süren işler yapar; hepsi IO'ya
 * atılır, arayüz iş parçacığında hiçbir ağır iş kalmaz.
 */
class CallController(
    private val context: Context,
    private val transport: FrameTransport,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val onStateChanged: (CallState) -> Unit = {}
) {
    companion object {
        /** Cevapsız sayılana kadar geçen süre — telefon şebekesiyle aynı mertebede. */
        private const val RING_TIMEOUT_MS = 45_000L

        /** Kapanış durumunun ekranda kaldığı süre. */
        private const val ENDED_LINGER_MS = 2_500L

        /** Pakette kaç codec çerçevesi taşınır (40 ms) — PTT'de 80 ms, çağrıda
         *  gecikme daha önemli olduğu için yarısı. */
        private const val FRAMES_PER_PACKET = 2
    }

    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state

    private val _controls = MutableStateFlow(CallControls())
    val controls: StateFlow<CallControls> = _controls

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel
    private val _remoteLevel = MutableStateFlow(0f)
    val remoteLevel: StateFlow<Float> = _remoteLevel

    private val seq = AtomicInteger(0)
    private val ringer = Ringer(context)

    private val lock = Any()
    /** Taşıyıcı iş parçacığı sesi buraya kuyruklar; ana iş parçacığı da okur. */
    @Volatile private var session: CallAudioSession? = null
    @Volatile private var myTiebreak = 0
    @Volatile private var remoteMask = VoiceCodec.MASK_AMR_NB
    @Volatile private var timeoutJob: Job? = null
    @Volatile private var lingerJob: Job? = null
    @Volatile private var proximityLock: PowerManager.WakeLock? = null

    /** txBatch hem yazma hem sıfırlama için TEK kilit altında — iki ayrı monitör
     *  kullanılınca yeni çağrının ilk paketinde sayaç ile veri kayabiliyordu. */
    private val txLock = Any()
    private val txBatch = ByteArrayOutputStream()
    private var txBatchCount = 0

    val speakerOn: Boolean get() = _controls.value.speakerOn
    val muted: Boolean get() = _controls.value.muted

    /** Çağrı sürerken bas-konuş kilitlenir: iki ses yolu aynı mikrofonu ister. */
    val isBusy: Boolean
        get() = _state.value !is CallState.Idle && _state.value !is CallState.Ended

    init {
        scope.launch(Dispatchers.IO) {
            transport.incoming.collect { frame ->
                runCatching { handleIncoming(frame) }
                    .onFailure { log("Çağrı çerçevesi atlandı: ${it.message}") }
            }
        }
        scope.launch(Dispatchers.IO) {
            transport.state.collect { s ->
                if (s !is TransportState.Connected && isBusy) {
                    finish(CallState.Reason.LINK_LOST, notifyPeer = false)
                }
            }
        }
    }

    // ---------- kullanıcı eylemleri ----------

    /** Karşı tarafı arar. Hat yoksa ya da meşgulsek sessizce yok sayılır. */
    fun placeCall() {
        val peer = (transport.state.value as? TransportState.Connected)?.peer ?: return
        synchronized(lock) {
            if (isBusy) return
            myTiebreak = Random.nextInt()
        }
        // Önceki çağrının kapanış işi, yeni çağrının zilini susturmasın.
        clearLinger()
        _controls.value = CallControls()
        setState(CallState.Dialing(peer, remoteRinging = false))
        log("Çağrı: $peer aranıyor")

        // localCallMask() ilk çağrıda AMR-WB kodlayıcısını deneyerek ölçer;
        // bu yüzlerce ms sürebilir, arayüz iş parçacığında yapılmaz.
        scope.launch(Dispatchers.IO) {
            sendInvite()
            ringer.startOutgoing()
        }
        armTimeout(CallState.Reason.NO_ANSWER, asCaller = true)
    }

    private fun sendInvite() {
        val t = myTiebreak
        send(
            LinkProtocol.TYPE_CALL_INVITE,
            byteArrayOf(
                (t and 0xFF).toByte(),
                ((t shr 8) and 0xFF).toByte(),
                ((t shr 16) and 0xFF).toByte(),
                ((t shr 24) and 0xFF).toByte(),
                VoiceCodec.localCallMask().toByte()
            )
        )
    }

    /** Gelen çağrıyı kabul eder. */
    fun answer() {
        val incoming = _state.value as? CallState.Incoming ?: return
        ringer.stop()
        scope.launch(Dispatchers.IO) {
            val codecId = VoiceCodec.bestCallCodec(remoteMask)
            send(LinkProtocol.TYPE_CALL_ACCEPT, byteArrayOf(codecId.toByte()))
            startAudio(incoming.peer, codecId)
        }
    }

    /** Gelen çağrıyı reddeder ya da açık çağrıyı kapatır. */
    fun hangUp() {
        if (!isBusy) return
        val reason = when (_state.value) {
            is CallState.Incoming -> CallState.Reason.DECLINED
            else -> CallState.Reason.HUNG_UP
        }
        // stop() içindeki iş parçacığı beklemeleri saniye sürebilir.
        scope.launch(Dispatchers.IO) { finish(reason, notifyPeer = true) }
    }

    fun setMuted(value: Boolean) {
        _controls.value = _controls.value.copy(muted = value)
        session?.muted = value
    }

    fun setSpeaker(value: Boolean) {
        _controls.value = _controls.value.copy(speakerOn = value)
        val s = session
        scope.launch(Dispatchers.IO) {
            s?.setSpeakerphone(value)
            // Hoparlördeyken telefon kulakta değildir; yakınlık kilidi kalkmalı.
            if (value) releaseProximity() else if (s != null) acquireProximity()
        }
    }

    // ---------- gelen çerçeveler ----------

    private fun handleIncoming(frame: LinkFrame) {
        when (frame.type) {
            LinkProtocol.TYPE_CALL_INVITE -> onInvite(frame)

            LinkProtocol.TYPE_CALL_RINGING -> {
                val d = _state.value as? CallState.Dialing ?: return
                setState(d.copy(remoteRinging = true))
            }

            LinkProtocol.TYPE_CALL_ACCEPT -> {
                val d = _state.value as? CallState.Dialing ?: return
                val codecId = frame.payload.firstOrNull()?.toInt()?.and(0xFF)
                    ?: VoiceCodec.ID_AMR_NB
                ringer.stop()
                startAudio(d.peer, codecId)
            }

            LinkProtocol.TYPE_CALL_DECLINE -> {
                if (!isBusy) return
                val reason = when (frame.payload.firstOrNull()?.toInt()?.and(0xFF)) {
                    LinkProtocol.DECLINE_BUSY -> CallState.Reason.BUSY
                    LinkProtocol.DECLINE_TIMEOUT -> CallState.Reason.NO_ANSWER
                    else -> CallState.Reason.DECLINED
                }
                ringer.playBusy()
                finish(reason, notifyPeer = false, keepTone = true)
            }

            LinkProtocol.TYPE_CALL_END -> {
                if (!isBusy) return
                // Zilimiz çalarken kapatıldıysa bu bizim için cevapsız çağrıdır.
                val reason = if (_state.value is CallState.Incoming) {
                    CallState.Reason.MISSED
                } else {
                    CallState.Reason.HUNG_UP
                }
                finish(reason, notifyPeer = false)
            }

            LinkProtocol.TYPE_CALL_AUDIO -> {
                val s = session ?: return
                val p = frame.payload
                if (p.isEmpty()) return
                val count = p[0].toInt() and 0xFF
                var off = 1
                repeat(count) {
                    if (off >= p.size) return
                    val len = p[off].toInt() and 0xFF
                    off += 1
                    if (len == 0 || off + len > p.size) return
                    s.enqueue(p.copyOfRange(off, off + len))
                    off += len
                }
            }
        }
    }

    private fun onInvite(frame: LinkFrame) {
        val peer = (transport.state.value as? TransportState.Connected)?.peer ?: return
        if (frame.payload.size < 5) return
        val theirTiebreak = LinkProtocol.readMsgId(frame.payload) ?: return
        remoteMask = frame.payload[4].toInt() and 0xFF

        when (val current = _state.value) {
            is CallState.Idle, is CallState.Ended -> {
                clearLinger()
                _controls.value = CallControls()
                beginRinging(peer)
                log("Çağrı: $peer arıyor")
            }

            is CallState.Dialing -> when {
                // İkimiz de aynı anda aradık. Sayısı küçük olan geri çekilir.
                theirTiebreak > myTiebreak -> {
                    cancelTimeout()
                    beginRinging(current.peer)
                    log("Çağrı: aynı anda arama — gelen çağrıya geçildi")
                }
                // Eşitlik (4 milyarda bir): kimse çekilmezse ikisi de asılı kalır.
                // Yeni bir sayı üretip daveti tazeliyoruz.
                theirTiebreak == myTiebreak -> {
                    myTiebreak = Random.nextInt()
                    scope.launch(Dispatchers.IO) { sendInvite() }
                }
                // Aksi halde karşı taraf geri çekilecek; kendi aramamızı sürdürürüz.
                else -> Unit
            }

            is CallState.Incoming, is CallState.Active ->
                send(LinkProtocol.TYPE_CALL_DECLINE, byteArrayOf(LinkProtocol.DECLINE_BUSY.toByte()))
        }
    }

    private fun beginRinging(peer: String) {
        ringer.stop()
        setState(CallState.Incoming(peer))
        send(LinkProtocol.TYPE_CALL_RINGING, ByteArray(0))
        ringer.startIncoming()
        armTimeout(CallState.Reason.MISSED, asCaller = false)
    }

    // ---------- ses oturumu ----------

    /** IO iş parçacığından çağrılır: codec ve ses aygıtı kurulumu yüzlerce ms sürer. */
    private fun startAudio(peer: String, codecId: Int) {
        cancelTimeout()
        val codec = runCatching {
            VoiceCodec.forId(codecId).also { c ->
                // Boru hattini sessizlikle isit: MediaCodec'in ilk cikitisi
                // gecikmeli geliyor ve cagrinin ILK KELIMESI kesiliyordu.
                (c as? com.voyagerroc.laserdeck.ptt.audio.AmrCodec)?.prime()
            }
        }.getOrElse {
            log("Çağrı codec'i kurulamadı: ${it.message}")
            finish(CallState.Reason.FAILED, notifyPeer = true)
            return
        }
        val s = runCatching {
            CallAudioSession(
                context = context,
                codec = codec,
                onEncoded = { encoded -> queueEncoded(encoded) },
                onLevels = { mic, remote ->
                    _micLevel.value = mic
                    _remoteLevel.value = remote
                },
                onFatal = { sebep ->
                    log("Cagri sesi durdu: $sebep")
                    finish(CallState.Reason.FAILED, notifyPeer = true)
                }
            ).also { it.start() }
        }.getOrElse {
            runCatching { codec.release() }
            log("Çağrı sesi açılamadı: ${it.message}")
            finish(CallState.Reason.FAILED, notifyPeer = true)
            return
        }

        // Kurulum sürerken karşı taraf kapatmış ya da hat kopmuş olabilir:
        // aksi halde ekranda giden bir kişiyle "görüşme" başlatırdık.
        val stillWanted = synchronized(lock) {
            val ok = _state.value is CallState.Dialing || _state.value is CallState.Incoming
            if (ok) session = s
            ok
        }
        if (!stillWanted) {
            runCatching { s.stop() }
            log("Çağrı kurulurken sonlandı")
            return
        }

        synchronized(txLock) {
            txBatch.reset()
            txBatchCount = 0
        }
        s.muted = muted
        s.setSpeakerphone(speakerOn)
        if (!speakerOn) acquireProximity()

        val bant = if (codecId == VoiceCodec.ID_AMR_WB) "geniş bant 16 kHz" else "dar bant 8 kHz"
        val fx = s.effectsReport
        log("Çağrı açık ($bant, kaynak=${s.sourceName}) — yankı:${fx.echoCancel.tr()} gürültü:${fx.noiseSuppress.tr()} kazanç:${fx.autoGain.tr()}")
        if (!fx.echoCancel) {
            log("UYARI: yankı gidericisi devrede değil, hoparlör uğultu yapabilir")
        }
        setState(CallState.Active(peer, System.currentTimeMillis(), codecId, fx))
    }

    private fun Boolean.tr() = if (this) "var" else "yok"

    private fun queueEncoded(encoded: ByteArray) {
        if (encoded.isEmpty() || encoded.size > 255) return
        val toSend: ByteArray?
        synchronized(txLock) {
            txBatch.write(encoded.size)
            txBatch.write(encoded, 0, encoded.size)
            txBatchCount++
            toSend = if (txBatchCount >= FRAMES_PER_PACKET) buildBatchLocked() else null
        }
        toSend?.let { send(LinkProtocol.TYPE_CALL_AUDIO, it) }
    }

    /** txLock altında çağrılır. */
    private fun buildBatchLocked(): ByteArray {
        val chunks = txBatch.toByteArray()
        val payload = ByteArray(1 + chunks.size)
        payload[0] = txBatchCount.toByte()
        chunks.copyInto(payload, 1)
        txBatch.reset()
        txBatchCount = 0
        return payload
    }

    // ---------- kapanış ----------

    private fun finish(
        reason: CallState.Reason,
        notifyPeer: Boolean,
        keepTone: Boolean = false
    ) {
        val previous = synchronized(lock) {
            val st = _state.value
            if (st is CallState.Idle || st is CallState.Ended) return
            _state.value = CallState.Ended(reason)
            st
        }
        cancelTimeout()
        if (!keepTone) ringer.stop()

        if (notifyPeer) {
            when (previous) {
                is CallState.Incoming ->
                    send(
                        LinkProtocol.TYPE_CALL_DECLINE,
                        byteArrayOf(LinkProtocol.DECLINE_USER.toByte())
                    )
                else -> send(LinkProtocol.TYPE_CALL_END, ByteArray(0))
            }
        }

        val s = synchronized(lock) { session.also { session = null } }
        runCatching { s?.stop() }
        releaseProximity()
        _micLevel.value = 0f
        _remoteLevel.value = 0f
        // Sonraki çağrı sessize alınmış ya da hoparlörde başlamasın.
        _controls.value = CallControls()

        onStateChanged(_state.value)
        clearLinger()
        lingerJob = scope.launch {
            delay(ENDED_LINGER_MS)
            ringer.stop()
            if (_state.value is CallState.Ended) setState(CallState.Idle)
        }
    }

    /** Bekleyen kapanış işini iptal eder — yeni çağrının zilini susturmasın. */
    private fun clearLinger() {
        lingerJob?.cancel()
        lingerJob = null
    }

    /**
     * [asCaller] doğruysa süre dolduğunda karşı tarafın zili hâlâ çalıyordur:
     * ona "meşgul" değil "kapatıldı" bildirilir, yoksa cevapsız çağrıyı
     * reddedilmiş gibi gösterirdi.
     */
    private fun armTimeout(reason: CallState.Reason, asCaller: Boolean) {
        cancelTimeout()
        timeoutJob = scope.launch {
            delay(RING_TIMEOUT_MS)
            if (isBusy && _state.value !is CallState.Active) {
                if (asCaller) {
                    send(LinkProtocol.TYPE_CALL_END, ByteArray(0))
                } else {
                    send(
                        LinkProtocol.TYPE_CALL_DECLINE,
                        byteArrayOf(LinkProtocol.DECLINE_TIMEOUT.toByte())
                    )
                }
                finish(reason, notifyPeer = false)
            }
        }
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    // ---------- yakınlık sensörü ----------

    /** Telefon kulaktayken ekran sönsün — yanağın ekrana basmasını da engeller. */
    private fun acquireProximity() {
        if (proximityLock?.isHeld == true) return
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java) ?: return
            if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
            @Suppress("DEPRECATION")
            proximityLock = pm.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "LaserDeck:call"
            ).also { it.acquire(60 * 60 * 1000L) }
        }
    }

    private fun releaseProximity() {
        runCatching { if (proximityLock?.isHeld == true) proximityLock?.release() }
        proximityLock = null
    }

    // ---------- ortak ----------

    private fun setState(next: CallState) {
        _state.value = next
        onStateChanged(next)
    }

    private fun send(type: Int, payload: ByteArray) {
        transport.send(LinkFrame(type, seq.incrementAndGet() and 0xFFFF, payload))
    }

    fun release() {
        cancelTimeout()
        clearLinger()
        ringer.stop()
        val s = synchronized(lock) { session.also { session = null } }
        runCatching { s?.stop() }
        releaseProximity()
        _state.value = CallState.Idle
    }
}
