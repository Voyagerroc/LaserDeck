package com.voyagerroc.laserdeck.ptt.ranging

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.voyagerroc.laserdeck.ptt.ble.BleRadio
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
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

/** Ölçüm kipinin durumu. */
data class Measurement(
    val running: Boolean = false,
    /** 0..1 toplama ilerlemesi. */
    val progress: Float = 0f,
    val calibrating: Boolean = false,
    val done: Boolean = false,
    val meters: Float = 0f,
    val low: Float = 0f,
    val high: Float = 0f,
    val calibrated: Boolean = false,
    val failed: String? = null
)

const val FAIL_NO_SIGNAL = "sinyal"
const val FAIL_TOO_FEW = "az"
const val FAIL_UNSTABLE = "kararsiz"

/**
 * Radar beyni: BLE ölçümlerini toplar, iki yönlü birleştirir, süzer ve mesafeye
 * çevirir.
 *
 * ## Neden iki yönlü
 * Her telefonun gönderim gücü ve anteni farklı. Yalnızca tek yön ölçülürse bu
 * farklar doğrudan mesafe hatasına dönüşür. İki taraf da birbirini ölçüp
 * okumasını hat üzerinden gönderince ortalama, cihaz farklarının çoğunu
 * sadeleştirir ve gürültüyü de ~%30 azaltır.
 *
 * ## Neden dB alanında süzülüyor
 * Gürültü desibel alanında toplanabilir; metreye çevirme ise üsteldir.
 * Metrelerin ortalamasını almak hem yanlı sonuç verir hem de uzak örnekleri
 * gereğinden fazla ağırlıklandırır.
 *
 * ## Neden aralık gösteriliyor
 * Hata çarpansaldır: ±5 dB, her mesafede "gerçek değer 0,63 ile 1,58 katı
 * arasında" demektir. Tek bir sayı yazmak yalan olurdu.
 */
class RangingCoordinator(
    private val context: Context,
    /** Radyo PAYLAŞILIR: duyuru panosu da aynı ilanı ve taramayı kullanıyor. */
    private val radio: BleRadio,
    private val transport: FrameTransport,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {}
) {
    companion object {
        /** Ölçüm/kalibrasyon toplama süresi. */
        private const val MEASURE_MS = 10_000L
        private const val CALIBRATE_MS = 8_000L

        /** Kalan belirsizlik; aralığın genişliğini bu belirler. */
        private const val SIGMA_DB = 5.0

        /** Süzgeç zaman sabiti (s). */
        private const val TAU_S = 1.2
        private const val TREND_TAU_S = 3.0

        /** Bu eşiğin altındaki hız "sabit" sayılır. */
        private const val TREND_DEADBAND = 0.25f

        /** Okumamızı karşı tarafa bildirme aralığı. */
        private const val REPORT_PERIOD_MS = 500L

        /** Bu kadar süre örnek gelmezse sinyal kayıp sayılır. */
        private const val SIGNAL_TIMEOUT_MS = 5_000L

        /** Ses bittikten sonra taramaya dönmeden önceki bekleme. */
        private const val AUDIO_COOLDOWN_MS = 3_000L

        /** El sıkışma tekrar aralıkları: önce sık, oturunca seyrek. */
        private const val HELLO_RETRY_FAST_MS = 2_000L
        private const val HELLO_RETRY_SLOW_MS = 10_000L
        private const val HELLO_FAST_ATTEMPTS = 5

        /** "Sinyal yok" sürerken teşhis satırının günlük aralığı. */
        private const val DIAG_PERIOD_MS = 5_000L

        /** Ölçüm/kalibrasyonda karşı tarafın okumasını hesaba katmak için en az rapor. */
        private const val MIN_REMOTE_SAMPLES = 8
    }

    private val _estimate = MutableStateFlow(RangeEstimate())
    val estimate: StateFlow<RangeEstimate> = _estimate

    /** Ölçüm kipinin durumu — arayüz ölçüm panelini bundan çizer. */
    private val _measure = MutableStateFlow(Measurement())
    val measure: StateFlow<Measurement> = _measure

    private val calibrationStore = RangeCalibration(context)
    private val _calibration = MutableStateFlow(calibrationStore.load(null))
    val calibration: StateFlow<RangeCalibration.Calibration> = _calibration

    /**
     * Donanım tabanlı mesafe ölçümü (Bluetooth 6.0 Kanal Sondalama) bu cihazda
     * var mı. Varsa santimetre mertebesinde ölçüm mümkün; sinyal gücüyle
     * yapılan kestirim ancak kaba bir tahmindir.
     */
    val hardwareRangingAvailable: Boolean by lazy {
        Build.VERSION.SDK_INT >= 36 &&
                runCatching { context.getSystemService("ranging") != null }.getOrDefault(false)
    }


    private val lock = Any()
    private val recent = ArrayDeque<Int>()          // ham RSSI, medyan için
    private var localSmoothed: Double? = null       // bizim ölçtüğümüz (dB)
    private var remoteSmoothed: Double? = null      // karşının ölçtüğü (dB)
    private var localTs = 0L
    private var remoteTs = 0L
    private var lastSampleMs = 0L
    private var lastLocalUpdateMs = 0L
    private var remoteTxPower = -7
    /** İlan gücü hat üzerinden (HELLO/REPORT) geldi mi — geldiyse tarama değeri ezmez. */
    @Volatile private var remoteTxFromLink = false
    private val sampleTimes = ArrayDeque<Long>()

    /** Ölçüm sırasında ham örnekler biriktirilir. */
    private val measureSamples = ArrayList<Double>()
    /** Ölçüm sırasında karşı tarafın raporladığı okumalar — iki yönlü ölçüm için. */
    private val measureRemoteSamples = ArrayList<Double>()
    @Volatile private var measuring = false
    @Volatile private var measuringForCalibration = false
    private var measureJob: Job? = null

    private var trendMeters: Double? = null
    private var trendTs = 0L
    private var speed = 0f
    private var lastDiagMs = 0L

    @Volatile private var peerAddress: String? = null
    @Volatile private var myToken = 0
    @Volatile private var sharedToken: Int? = null
    /** Karşıdan en son görülen belirteç — aynı HELLO'ya bir kez yankı için. */
    @Volatile private var lastPeerToken: Int? = null
    private var helloJob: Job? = null
    @Volatile private var permitted = false
    @Volatile private var audioActive = false
    @Volatile private var enabled = false

    private var loopJob: Job? = null
    private var cooldownJob: Job? = null

    init {
        radio.onRadarSample = { token, rssi, tx ->
            // Belirteci DOGRULA. Etraftaki baska bir LaserDeck cifti ya da
            // kotu niyetli bir verici, dogrulanmadigi surece bizim radarimiza
            // sinyal besleyebiliyordu - ve yakinlik kasasinin mesafe kilidi
            // yalnizca bu olcume dayandigi icin anahtar sahte bir yakinlikla
            // alinabiliyordu.
            if (token == sharedToken) onLocalSample(rssi, tx)
        }
        scope.launch(Dispatchers.IO) {
            transport.incoming.collect { frame ->
                runCatching { handleIncoming(frame) }
                    .onFailure { log("Radar çerçevesi atlandı: ${it.message}") }
            }
        }
        scope.launch(Dispatchers.IO) {
            transport.state.collect { s ->
                if (s is TransportState.Connected) onLinkUp() else onLinkDown()
            }
        }
    }

    /** Activity izinleri aldıktan sonra çağırır. */
    fun setPermitted(value: Boolean) {
        permitted = value
        if (value && enabled) startRadio()
        publish()
    }

    /** Ses (bas-konuş ya da çağrı) sürerken tarama askıya alınır: aynı radyo. */
    fun setAudioActive(active: Boolean) {
        if (audioActive == active) return
        audioActive = active
        cooldownJob?.cancel()
        if (active) {
            radio.setScanning(BleRadio.Owner.RADAR, false)
            publish()
        } else {
            // Hemen geri dönmüyoruz: art arda basılan PTT'ler startScan
            // sınırına takılır (30 saniyede ~5 çağrı).
            cooldownJob = scope.launch {
                delay(AUDIO_COOLDOWN_MS)
                if (!audioActive) startRadio()
            }
        }
    }

    // ---------- ölçüm kipi ----------

    /**
     * Uzun süreli, hareketsiz ölçüm.
     *
     * Radardaki canlı okuma hızlı tepki vermek için kısa zaman sabitiyle
     * süzülüyor; ölçüm kipi ise 10 saniye boyunca örnek toplayıp ortancasını
     * alıyor. Hareketsiz durulduğunda gürültünün büyük kısmı böyle siliniyor.
     */
    fun startMeasurement() = beginCollect(forCalibration = false)

    /**
     * 1 metrede kalibrasyon. İki telefon tam 1 metre arayken, aralarında hiçbir
     * şey yokken çalıştırılmalı — bu tek ölçüm, iki cihaz arasındaki güç ve
     * anten farkını kalıcı olarak siliyor.
     */
    fun startCalibration() = beginCollect(forCalibration = true)

    fun cancelMeasurement() {
        measureJob?.cancel()
        measuring = false
        _measure.value = Measurement()
    }

    private fun beginCollect(forCalibration: Boolean) {
        if (measuring) return
        if (_estimate.value.state != RangeEstimate.State.LIVE) {
            _measure.value = Measurement(failed = FAIL_NO_SIGNAL)
            return
        }
        synchronized(lock) {
            measureSamples.clear()
            measureRemoteSamples.clear()
        }
        measuring = true
        measuringForCalibration = forCalibration
        val total = if (forCalibration) CALIBRATE_MS else MEASURE_MS

        measureJob?.cancel()
        measureJob = scope.launch(Dispatchers.IO) {
            val start = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - start < total) {
                val elapsed = (SystemClock.elapsedRealtime() - start).toFloat() / total
                _measure.value = Measurement(
                    running = true, progress = elapsed, calibrating = forCalibration
                )
                delay(120)
            }
            measuring = false
            finishCollect(forCalibration)
        }
    }

    private fun finishCollect(forCalibration: Boolean) {
        val (samples, remote) = synchronized(lock) {
            ArrayList(measureSamples) to ArrayList(measureRemoteSamples)
        }
        if (samples.size < 20) {
            _measure.value = Measurement(failed = FAIL_TOO_FEW)
            return
        }

        if (forCalibration) {
            val address = peerAddress
            val refLocal = calibrationStore.estimateRef(samples)
            if (address == null || refLocal == null) {
                // Kötü kalibrasyon, kalibrasyonsuzluktan daha zararlı: sonraki
                // her ölçümü sistematik olarak bozar. Kaydetmiyoruz.
                _measure.value = Measurement(failed = FAIL_UNSTABLE)
                return
            }
            // İKİ YÖNLÜ: canlı okuma iki tarafın ortalamasını kullanıyor, o hâlde
            // 1 m referansı da iki tarafın ortalaması olmalı. Yalnızca yerel
            // referansla kalibre edip iki yönlü okumaya uygulamak, antenleri
            // farklı telefonlarda sabit bir kayma bırakıyordu.
            val refRemote = upperHalfMean(remote)
            val ref = combineTwoWay(refLocal, refRemote)
            calibrationStore.save(address, ref, samples.size)
            _calibration.value = calibrationStore.load(address)
            _measure.value = Measurement(done = true, calibrating = true)
            log(
                "Radar kalibre edildi: 1 m = ${"%.1f".format(ref)} dBm" +
                        if (refRemote != null) " (iki yönlü)" else " (tek yönlü)"
            )
            return
        }

        val cal = _calibration.value
        val local = samples.sorted()[samples.size / 2]
        val remoteMedian =
            if (remote.size >= MIN_REMOTE_SAMPLES) remote.sorted()[remote.size / 2] else null
        val signal = combineTwoWay(local, remoteMedian)
        // Canlı okumayla AYNI referans: kalibrasyon varsa o, yoksa iki tarafın
        // bildirdiği ilan gücüne göre kaydırılmış varsayılan. Eski sürüm burada
        // ham varsayılanı kullanıyor, ölçüm kipi ile radar farklı sayı veriyordu.
        val meters = distanceFor(referenceDbm(cal), signal, cal.n)
        // Kalibre edilmişse kalan belirsizlik daha dar.
        val sigma = if (cal.calibrated) 3.5 else 5.0
        val factor = 10.0.pow(sigma / (10.0 * cal.n))
        _measure.value = Measurement(
            done = true,
            meters = meters.toFloat(),
            low = (meters / factor).toFloat(),
            high = (meters * factor).toFloat(),
            calibrated = cal.calibrated
        )
    }

    /**
     * Karşı tarafın raporları zaten süzülmüş (kendi üstel süzgecinden geçmiş)
     * geldiği için az sayıda örnek yeter; üst yarının ortalaması, yerel
     * kalibrasyonla aynı kestirimci.
     */
    private fun upperHalfMean(values: List<Double>): Double? {
        if (values.size < MIN_REMOTE_SAMPLES) return null
        val sorted = values.sorted()
        return sorted.drop(sorted.size / 2).average()
    }

    private fun combineTwoWay(local: Double, remote: Double?): Double =
        if (remote == null) local else (local + remote) / 2.0

    /** Mesafe modelinin 1 m referansı — canlı okuma ve ölçüm kipi aynı değeri kullanır. */
    private fun referenceDbm(cal: RangeCalibration.Calibration): Double =
        if (cal.calibrated) {
            cal.refDbm
        } else {
            RangeCalibration.DEFAULT_REF_DBM +
                    (((radio.localTxPower + remoteTxPower) / 2.0) - 1.0)
        }

    // ---------- hat ----------

    private fun onLinkUp() {
        enabled = true
        peerAddress = (transport.state.value as? TransportState.Connected)?.address
        _calibration.value = calibrationStore.load(peerAddress)
        myToken = Random.nextInt()
        lastPeerToken = null
        sharedToken = null
        startHelloLoop()
        startLoop()
    }

    private fun sendHello() {
        send(
            LinkProtocol.TYPE_RANGE_HELLO,
            byteArrayOf(
                (myToken and 0xFF).toByte(),
                ((myToken shr 8) and 0xFF).toByte(),
                ((myToken shr 16) and 0xFF).toByte(),
                ((myToken shr 24) and 0xFF).toByte(),
                radio.localTxPower.toByte()
            )
        )
    }

    /**
     * El sıkışma TEKRARLI. Eski sürüm HELLO'yu bağlantıda bir kez atıyordu:
     * paket kaçarsa ya da karşı taraf uygulamayı yeniden başlatırsa (belirteci
     * değişir) iki taraf farklı belirteç bekliyor ve radar KALICI olarak
     * "sinyal yok"ta kalıyordu. Şimdi: örnek akana kadar önce sık sonra seyrek
     * aralıkla HELLO yinelenir; oturduktan sonra da düşük tempoda sürer ki
     * karşı taraf sessizce sıfırlanırsa kendiliğinden toparlansın.
     */
    private fun startHelloLoop() {
        helloJob?.cancel()
        helloJob = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive && enabled) {
                sendHello()
                attempt++
                val settled = sharedToken != null &&
                        SystemClock.elapsedRealtime() - lastSampleMs < SIGNAL_TIMEOUT_MS
                if (attempt == HELLO_FAST_ATTEMPTS && sharedToken == null) {
                    log("Radar: el sıkışma yanıtı yok — karşı sürüm eski olabilir")
                }
                delay(
                    if (settled || attempt >= HELLO_FAST_ATTEMPTS) HELLO_RETRY_SLOW_MS
                    else HELLO_RETRY_FAST_MS
                )
            }
        }
    }

    private fun onLinkDown() {
        enabled = false
        sharedToken = null
        lastPeerToken = null
        remoteTxFromLink = false
        helloJob?.cancel(); helloJob = null
        radio.setRadarToken(null)
        radio.setScanning(BleRadio.Owner.RADAR, false)
        loopJob?.cancel(); loopJob = null
        cooldownJob?.cancel(); cooldownJob = null
        synchronized(lock) {
            recent.clear(); sampleTimes.clear()
            localSmoothed = null; remoteSmoothed = null
            trendMeters = null; speed = 0f
        }
        _estimate.value = RangeEstimate(state = RangeEstimate.State.OFF)
    }

    private fun handleIncoming(frame: LinkFrame) {
        when (frame.type) {
            LinkProtocol.TYPE_RANGE_HELLO -> {
                if (frame.payload.size < 5) return
                val theirToken = LinkProtocol.readMsgId(frame.payload) ?: return
                remoteTxPower = frame.payload[4].toInt()
                remoteTxFromLink = true
                // Belirteci XOR'layarak iki taraf da AYNI değeri elde eder;
                // pazarlığa, kim-önce-gönderdi sırasına gerek kalmaz.
                sharedToken = myToken xor theirToken
                // YANKI: karşının HELLO'su bize ulaştıysa bizimkinin ona
                // ulaştığı kesin değil. Yeni bir belirteç gördüğümüzde kendi
                // HELLO'muzu bir kez daha yollarız; belirteç aynıysa susarız,
                // yoksa iki taraf sonsuz ping-pong'a girerdi.
                if (theirToken != lastPeerToken) {
                    lastPeerToken = theirToken
                    sendHello()
                    log("Radar: el sıkışma tamam")
                }
                startRadio()
            }

            LinkProtocol.TYPE_RANGE_REPORT -> {
                if (frame.payload.size < 2) return
                val rssi = frame.payload[0].toInt()
                remoteTxPower = frame.payload[1].toInt()
                remoteTxFromLink = true
                synchronized(lock) {
                    remoteSmoothed = rssi.toDouble()
                    remoteTs = SystemClock.elapsedRealtime()
                    // Ölçüm kipi iki yönlü: karşının okuması da toplanır.
                    if (measuring) measureRemoteSamples.add(rssi.toDouble())
                }
            }
        }
    }

    private fun startRadio() {
        val token = sharedToken ?: return
        if (!permitted || !enabled) return
        radio.setRadarToken(token)
        if (!audioActive) radio.setScanning(BleRadio.Owner.RADAR, true)
    }

    // ---------- ölçüm ----------

    private fun onLocalSample(rssi: Int, tx: Int) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            // İlan gücü için hat üzerinden gelen değer yetkili: karşı tarafın
            // denetleyicisi bildiriyor. Tarama sonucundaki alan yalnızca hat
            // raporu henüz gelmemişse ve gerçekten okunabildiyse kullanılır.
            if (!remoteTxFromLink && tx != BleRadio.TX_UNKNOWN) remoteTxPower = tx
            // Medyan-5: tek tük çarpışma/parazit sıçramalarını at.
            recent.addLast(rssi)
            while (recent.size > 5) recent.removeFirst()
            val median = recent.sorted()[recent.size / 2].toDouble()

            // Zamana duyarlı üstel süzgeç: BLE örnekleri düzensiz aralıklarla
            // geliyor, sabit alfa yanlış ağırlık verirdi.
            val prev = localSmoothed
            localSmoothed = if (prev == null) {
                median
            } else {
                val dt = ((now - lastLocalUpdateMs).coerceAtLeast(1)) / 1000.0
                val alpha = 1.0 - exp(-dt / TAU_S)
                prev + alpha * (median - prev)
            }
            lastLocalUpdateMs = now
            localTs = now
            lastSampleMs = now

            if (measuring) measureSamples.add(median)

            sampleTimes.addLast(now)
            while (sampleTimes.isNotEmpty() && now - sampleTimes.first() > 2000) {
                sampleTimes.removeFirst()
            }
        }
    }

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(REPORT_PERIOD_MS)
                // Kendini onarma: tarama başlatma çağrısı 30 saniyede ~5 kez
                // sınırlı, o yüzden ilk deneme sessizce reddedilmiş olabilir.
                // İlan ve tarama zaten açıksa bu çağrılar hiçbir şey yapmaz.
                if (!audioActive) startRadio()
                // Sessizce reddedilmis ilani kurtar: gecici bir hata yuzunden
                // ilanin kalici olarak olmesini engeller.
                radio.heartbeat()
                reportToPeer()
                publish()
            }
        }
    }

    /** Kendi okumamızı karşı tarafa yolla ki o da iki yönlü ortalama alabilsin. */
    private fun reportToPeer() {
        val value = synchronized(lock) {
            val v = localSmoothed ?: return
            if (SystemClock.elapsedRealtime() - localTs > SIGNAL_TIMEOUT_MS) return
            v
        }
        send(
            LinkProtocol.TYPE_RANGE_REPORT,
            byteArrayOf(
                value.toInt().coerceIn(-127, 0).toByte(),
                radio.localTxPower.toByte()
            )
        )
    }

    private fun publish() {
        val now = SystemClock.elapsedRealtime()

        if (!BleRadio.supported()) {
            _estimate.value = RangeEstimate(state = RangeEstimate.State.UNSUPPORTED)
            return
        }
        if (!enabled) {
            _estimate.value = RangeEstimate(state = RangeEstimate.State.OFF)
            return
        }
        if (!permitted) {
            _estimate.value = RangeEstimate(state = RangeEstimate.State.NO_PERMISSION)
            return
        }

        val snapshot = synchronized(lock) {
            Triple(localSmoothed, remoteSmoothed, sampleTimes.size)
        }
        val (local, remote, count) = snapshot
        val age = now - lastSampleMs

        if (local == null || age > SIGNAL_TIMEOUT_MS) {
            _estimate.value = RangeEstimate(
                state = if (audioActive) RangeEstimate.State.PAUSED
                else RangeEstimate.State.NO_SIGNAL,
                ageMs = if (lastSampleMs == 0L) 0L else age
            )
            // Teşhis: "sinyal yok" tek başına hiçbir şey söylemiyor. Hangi
            // aşamanın öldüğünü aralıklı olarak günlüğe yaz ki sahada
            // ekrandaki kayıttan görülebilsin.
            if (!audioActive && now - lastDiagMs > DIAG_PERIOD_MS) {
                lastDiagMs = now
                log(
                    "Radar teşhis: el-sıkışma=" +
                            (if (sharedToken != null) "tamam" else "BEKLİYOR") +
                            " ilan=" + (if (radio.isAdvertising) "açık" else "KAPALI") +
                            " tarama=" + (if (radio.isScanning) "açık" else "KAPALI") +
                            " karşı-rapor=" +
                            (if (remote != null && now - remoteTs < SIGNAL_TIMEOUT_MS) "geliyor" else "yok")
                )
            }
            return
        }

        // İki yön varsa ortalama al: cihazlar arası güç farkı sadeleşir.
        val fresh = remote != null && now - remoteTs < SIGNAL_TIMEOUT_MS
        val s = if (fresh) (local + remote!!) / 2.0 else local

        // Kalibrasyon varsa onu kullan; yoksa iki tarafın bildirdiği ilan
        // gücüne göre kaydırılmış genel varsayılan — ölçüm kipiyle AYNI yol.
        val cal = _calibration.value
        val meters = distanceFor(referenceDbm(cal), s, cal.n)
        // Kalibre edilmemiş ölçümde belirsizlik daha geniş - ve bu ekranda
        // halkanın kalınlığı olarak görünüyor.
        val sigma = if (cal.calibrated) SIGMA_DB else SIGMA_DB + 2.0
        val factor = 10.0.pow(sigma / (10.0 * cal.n))

        updateTrend(meters, now)

        _estimate.value = RangeEstimate(
            meters = meters.toFloat(),
            low = (meters / factor).toFloat(),
            high = (meters * factor).toFloat(),
            closingSpeed = speed,
            trend = when {
                speed < -TREND_DEADBAND -> RangeEstimate.Trend.CLOSING
                speed > TREND_DEADBAND -> RangeEstimate.Trend.OPENING
                else -> RangeEstimate.Trend.HOLDING
            },
            zone = RangeEstimate.zoneFor(meters.toFloat()),
            sampleHz = count / 2f,
            state = if (audioActive) RangeEstimate.State.PAUSED else RangeEstimate.State.LIVE,
            ageMs = age
        )
    }

    private fun distanceFor(refDbm: Double, signalDbm: Double, n: Double): Double {
        val d = 10.0.pow((refDbm - signalDbm) / (10.0 * n))
        // Model iki uçta da kırılıyor: 0,5 m altında yakın alan etkisi ve alıcı
        // doyması, 60 m üstünde zaten sinyal kalmıyor.
        return d.coerceIn(0.5, 60.0)
    }

    /**
     * Yaklaşma hızı. Bu, kalibrasyondan BAĞIMSIZ olarak güvenilir: bilinmeyen
     * referans sabiti türev alınırken sadeleşir. "Kaç metre" tartışmalıyken
     * "yaklaşıyor" kesindir.
     */
    private fun updateTrend(meters: Double, now: Long) {
        val prev = trendMeters
        if (prev == null || trendTs == 0L) {
            trendMeters = meters
            trendTs = now
            speed = 0f
            return
        }
        val dt = ((now - trendTs).coerceAtLeast(1)) / 1000.0
        val alpha = 1.0 - exp(-dt / TREND_TAU_S)
        val smoothed = prev + alpha * (meters - prev)
        val raw = ((smoothed - prev) / dt).toFloat()
        // Hızın kendisi de süzülür, yoksa gösterge sürekli zıplar.
        speed = speed * 0.7f + raw * 0.3f
        if (abs(speed) < 0.02f) speed = 0f
        trendMeters = smoothed
        trendTs = now
    }

    private fun send(type: Int, payload: ByteArray) {
        transport.send(LinkFrame(type, 0, payload))
    }

    fun release() {
        loopJob?.cancel()
        cooldownJob?.cancel()
        helloJob?.cancel()
        // Radyoyu KAPATMIYORUZ: duyuru panosu da onu kullanıyor olabilir.
        // Yalnızca kendi yükümüzü ve tarama isteğimizi çekiyoruz.
        radio.setRadarToken(null)
        radio.setScanning(BleRadio.Owner.RADAR, false)
    }
}
