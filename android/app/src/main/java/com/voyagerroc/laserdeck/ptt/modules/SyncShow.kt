package com.voyagerroc.laserdeck.ptt.modules

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.SystemClock
import com.voyagerroc.laserdeck.ptt.protocol.LinkFrame
import com.voyagerroc.laserdeck.ptt.protocol.LinkProtocol
import com.voyagerroc.laserdeck.ptt.sync.ClockSync
import com.voyagerroc.laserdeck.ptt.transport.FrameTransport
import com.voyagerroc.laserdeck.ptt.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Senkron gösteri: iki telefon aynı anda aynı şeyi yapar.
 *
 * Işık şovu, ton şovu ve çok hoparlörlü ses — üçü de aynı temele oturuyor:
 * [ClockSync] ile eşitlenmiş ortak bir saat, ve "şu ANDA başla" komutu.
 * Gönderilen şey içerik değil, **zaman**.
 *
 * ## Neden hazırlık payı var
 * Komut hatta ilerlerken zaman geçiyor. Başlangıç anı bu yüzden "şimdi" değil,
 * "şimdiden [LEAD_MS] sonra" olarak veriliyor; iki taraf da o ana kadar
 * hazırlanıp aynı anda tetikliyor. Pay olmadan komutu alan taraf hep geç
 * kalırdı.
 *
 * ## Neden vuruş numarası hesaplanıyor
 * Döngü "bekle, yak, bekle, söndür" biçiminde ilerleseydi küçük gecikmeler
 * birikip iki telefon zamanla ayrışırdı. Onun yerine her adımda geçen süreden
 * kaçıncı vuruşta olunması GEREKTİĞİ hesaplanıyor — bir adım kaçsa bile bir
 * sonrakinde tekrar hizaya giriyor.
 *
 * ## Dürüst sınır
 * Ulaşılabilir eşitleme doğruluğu gidiş-dönüş süresinin yarısı kadar, tipik
 * olarak ±20–50 ms. Işık ve ritim için bu gözle ayırt edilemez. Çok hoparlörlü
 * seste ise bu fark hafif bir yankı/genişleme olarak duyulur — iki hoparlörün
 * faz uyumu bu hatla mümkün değil, ses "büyür" ama tek kaynak gibi olmaz.
 */
class SyncShow(
    private val context: Context,
    private val transport: FrameTransport,
    private val clock: ClockSync,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    /** Ekranı yakıp söndürmek için arayüze verilen sinyal. */
    private val onFlash: (Boolean) -> Unit = {}
) {
    companion object {
        /** Komutun karşı tarafa ulaşması ve hazırlanması için pay. */
        private const val LEAD_MS = 1_500L

        private const val DEFAULT_DURATION_MS = 30_000L
    }

    enum class Mode(val id: Int) {
        /** Fener + ekran, yarım saniyede bir. */
        BEACON(0),

        /** Hızlı çakma. */
        STROBE(1),

        /** Ton + ekran darbesi — ritim. */
        PULSE(2),

        /** Seçilen ses dosyası, iki telefonda birlikte. */
        AUDIO(3);

        companion object {
            fun of(id: Int) = entries.firstOrNull { it.id == id } ?: BEACON
        }
    }

    data class ShowState(
        val running: Boolean = false,
        val mode: Mode = Mode.BEACON,
        val master: Boolean = false,
        val startsInMs: Long = 0L,
        val accuracyMs: Long = 0L
    )

    private val _state = MutableStateFlow(ShowState())
    val state: StateFlow<ShowState> = _state

    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private var torchId: String? = null

    private var showJob: Job? = null
    // Bu iki alan gosteri is parcaciginda yazilip cagiran is parcaciginda
    // okunuyor; @Volatile olmadan durdurma anindaki yazma gorunmeyebilir.
    @Volatile private var tone: ToneGenerator? = null
    @Volatile private var player: MediaPlayer? = null

    @Volatile private var audioUri: Uri? = null

    init {
        torchId = runCatching {
            cameraManager?.cameraIdList?.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()

        scope.launch(Dispatchers.IO) {
            transport.incoming.collect { frame ->
                runCatching { handle(frame) }
                    .onFailure { log("Gösteri çerçevesi atlandı: ${it.message}") }
            }
        }
        scope.launch(Dispatchers.IO) {
            transport.state.collect { s -> if (s !is TransportState.Connected) stopLocal() }
        }
    }

    /** Çok hoparlörlü ses için bu cihazın çalacağı dosya. */
    fun setAudio(uri: Uri?) {
        audioUri = uri
    }

    val hasTorch: Boolean get() = torchId != null

    // ---------- başlat ----------

    /**
     * Gösteriyi başlatır ve karşı tarafı da aynı ana çağırır.
     * Saat eşitlemesi hazır değilse reddeder — senkronsuz bir "senkron gösteri"
     * yanıltıcı olurdu.
     */
    fun start(mode: Mode, durationMs: Long = DEFAULT_DURATION_MS): Boolean {
        if (transport.state.value !is TransportState.Connected) return false
        if (!clock.isFresh()) {
            log("Gösteri: saat eşitlemesi henüz hazır değil")
            return false
        }
        val startLocal = SystemClock.elapsedRealtimeNanos() + LEAD_MS * 1_000_000

        val payload = ByteArray(13)
        payload[0] = mode.id.toByte()
        // Kendi saatimizi HAM gonderiyoruz. Cevrimi yalnizca ALICI yapar
        // (remoteToLocal). Iki taraf da cevirirse fark iki kez uygulanir ve
        // gosteri, iki telefonun acilistan beri gecen sureleri farki kadar
        // yanlis ana denk gelir - yani saatler, hatta gunler sonrasina.
        writeLong(payload, 1, startLocal)
        writeInt(payload, 9, durationMs.toInt())
        transport.send(LinkFrame(LinkProtocol.TYPE_SHOW_CUE, 0, payload))

        runShow(mode, startLocal, durationMs, master = true)
        return true
    }

    fun stop() {
        transport.send(LinkFrame(LinkProtocol.TYPE_SHOW_STOP, 0, ByteArray(0)))
        stopLocal()
    }

    private fun handle(frame: LinkFrame) {
        when (frame.type) {
            LinkProtocol.TYPE_SHOW_CUE -> {
                if (frame.payload.size < 13) return
                val mode = Mode.of(frame.payload[0].toInt() and 0xFF)
                val startRemote = readLong(frame.payload, 1)
                val duration = readInt(frame.payload, 9).toLong()
                // Karşı tarafın saatindeki an, bizim saatimize çevrilir.
                val startLocal = clock.remoteToLocal(startRemote)
                runShow(mode, startLocal, duration, master = false)
            }

            LinkProtocol.TYPE_SHOW_STOP -> stopLocal()
        }
    }

    // ---------- yürütme ----------

    private fun runShow(mode: Mode, startLocalNanos: Long, durationMs: Long, master: Boolean) {
        stopLocal()
        _state.value = ShowState(
            running = true,
            mode = mode,
            master = master,
            startsInMs = ((startLocalNanos - SystemClock.elapsedRealtimeNanos()) / 1_000_000)
                .coerceAtLeast(0),
            accuracyMs = clock.sync.value.accuracyMs
        )

        showJob = scope.launch(Dispatchers.Default) {
            // Başlangıç anına kadar bekle. Son 20 ms'de meşgul bekleme yapılır:
            // delay() çözünürlüğü senkron için yeterince keskin değil.
            while (isActive) {
                val remaining = startLocalNanos - SystemClock.elapsedRealtimeNanos()
                if (remaining <= 0) break
                if (remaining > 20_000_000) delay((remaining / 2_000_000).coerceAtLeast(1))
                else Thread.yield()
            }
            if (!isActive) return@launch

            when (mode) {
                Mode.AUDIO -> runAudio(startLocalNanos, durationMs)
                else -> runRhythm(mode, startLocalNanos, durationMs)
            }
            stopLocal()
        }
    }

    /** Işık ve ton desenleri. */
    private suspend fun runRhythm(mode: Mode, startNanos: Long, durationMs: Long) = try {
        rhythmLoop(mode, startNanos, durationMs)
    } finally {
        // Iptal edilirsek de isik MUTLAKA sonsun: aksi halde fener acik kaliyor
        // ve tam ekran parlama katmani ekrani beyaz bir ortuyle kapatiyordu.
        setLight(false)
    }

    private suspend fun rhythmLoop(mode: Mode, startNanos: Long, durationMs: Long) {
        val (onMs, periodMs) = when (mode) {
            Mode.STROBE -> 60L to 140L
            Mode.PULSE -> 120L to 600L
            else -> 140L to 700L
        }
        if (mode == Mode.PULSE) {
            tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()
        }

        var lastBeat = -1L
        var lastLit: Boolean? = null
        // scope DEGIL: scope servisin omru boyunca canli, dolayisiyla
        // showJob.cancel() donguyu hic durdurmuyordu.
        while (currentCoroutineContext().isActive) {
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000
            if (elapsedMs > durationMs) break

            // Vuruş numarası GEÇEN SÜREDEN hesaplanıyor; böylece bir adım
            // kaçsa bile bir sonraki vuruşta tekrar hizaya giriyoruz.
            val beat = elapsedMs / periodMs
            val phase = elapsedMs % periodMs
            val lit = phase < onMs

            if (beat != lastBeat && lit) {
                lastBeat = beat
                if (mode == Mode.PULSE) {
                    runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, onMs.toInt()) }
                }
            }
            // Her turda degil, yalnizca DEGISINCE: setTorchMode saniyede
            // 125 kez cagrilinca kamera servisini gereksiz yere zorluyordu.
            if (lit != lastLit) {
                lastLit = lit
                setLight(lit)
            }
            delay(8)
        }
    }

    /** Çok hoparlörlü ses: iki telefon aynı konumdan birlikte çalar. */
    private suspend fun runAudio(startNanos: Long, durationMs: Long) {
        val uri = audioUri
        if (uri == null) {
            log("Gösteri: bu cihazda ses dosyası seçilmedi")
            return
        }
        val mp = runCatching {
            MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                prepare()
            }
        }.getOrNull()
        if (mp == null) {
            log("Gösteri: ses dosyası açılamadı")
            return
        }
        player = mp

        // Başlangıç anı geçtiyse o kadarını atla — geç katılan cihaz da
        // doğru yerden devam etsin.
        val lateMs = ((SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000)
            .coerceAtLeast(0)
        runCatching {
            if (lateMs > 0) mp.seekTo(lateMs.toInt())
            mp.start()
        }

        while (currentCoroutineContext().isActive && mp.isPlaying) {
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000
            if (elapsedMs > durationMs) break
            // Kayma düzeltmesi: beklenen konumdan 120 ms'den fazla saparsak
            // yerimizi düzelt. Daha küçük sapmalarda düzeltmek duyulur bir
            // sıçrama yaratır, hatanın kendisinden kötü olur.
            val expected = elapsedMs.toInt()
            val actual = runCatching { mp.currentPosition }.getOrDefault(expected)
            if (kotlin.math.abs(actual - expected) > 120) {
                runCatching { mp.seekTo(expected) }
            }
            delay(500)
        }
    }

    private fun setLight(on: Boolean) {
        onFlash(on)
        val id = torchId ?: return
        runCatching { cameraManager?.setTorchMode(id, on) }
    }

    private fun stopLocal() {
        showJob?.cancel()
        showJob = null
        setLight(false)
        // Ayri ayri: stop() istisna atarsa release() hic calismiyor ve
        // codec ile ses kanali sizarak kaliyordu.
        runCatching { tone?.stopTone() }
        runCatching { tone?.release() }
        tone = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        _state.value = ShowState()
    }

    fun release() = stopLocal()

    // ---------- bayt ----------

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
