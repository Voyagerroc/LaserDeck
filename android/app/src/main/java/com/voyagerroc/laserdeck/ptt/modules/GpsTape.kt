package com.voyagerroc.laserdeck.ptt.modules

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.voyagerroc.laserdeck.ptt.geo.Geodesy
import com.voyagerroc.laserdeck.ptt.geo.PositionAverager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * GPS şerit metre — iki noktayı işaretle, aradaki mesafeyi oku.
 *
 * ## Ne yapar
 * A noktasında düğmeye basılır, telefon birkaç saniye hareketsiz tutulur;
 * B noktasında aynısı. Aradaki mesafe WGS84 elipsoidi üzerinde (Vincenty)
 * hesaplanır. Bağlantı gerekmez: bu modül TEK telefonla çalışır.
 *
 * ## Doğruluk — dürüst sınır
 * Tüketici GPS'i tek bir konumu açık gökyüzünde ±3–5 m (1σ) verir. İki noktanın
 * FARKI bundan iyidir: uydu saati, yörünge ve iyonosfer hataları iki ölçümü de
 * aynı yöne iter ve farkta sadeleşir — yeter ki iki nokta aynı uydularla, kısa
 * arayla alınsın. Sadeleşmeyen kısım çoklu yansıma (bina, ağaç) ve alıcı
 * gürültüsüdür. Bu yüzden:
 *   - her noktada [DWELL_MS] boyunca fix toplanır, doğruluk ağırlıklı ortalaması alınır,
 *   - kötü fix'ler ([MAX_FIX_ACCURACY_M] üstü) hiç alınmaz,
 *   - sonuç TEK SAYI değil, ± bandıyla verilir — radarla aynı dürüstlük,
 *   - iki nokta arası [STALE_BASELINE_MS] aşılırsa uyarılır: ortak sapma artık sadeleşmez.
 * Yükseklik hesaba katılmaz: GPS yükseklik hatası (±10 m) 10 m'lik bir baz
 * hattını tamamen gömer. "Hatasız" GPS ölçümü fizikte yoktur; buradaki her
 * sayı yanına belirsizliğini de yazar.
 *
 * ## Neden yalnızca GPS_PROVIDER
 * Ağ/"fused" konumu Wi-Fi ve baz istasyonuna göre sıçrar; 10 m'lik bir
 * ölçümde 30 m'lik tek bir sıçrama her şeyi bozar. Uydu alıcısı yavaş ama
 * tutarlıdır.
 *
 * ## Gizlilik
 * Konum yalnızca bu modül kullanıcı tarafından AÇILDIĞINDA ve ekran öndeyken
 * okunur; hattan gönderilmez, diske yazılmaz, modül kapanınca bellekten
 * silinir. Uygulamanın başka hiçbir yeri konum kullanmaz.
 */
class GpsTape(
    private val context: Context,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {}
) {
    companion object {
        /** Bir noktayı sabitlerken örnek toplama süresi. */
        const val DWELL_MS = 8_000L

        /** Bu doğruluğun (m, %68) üstündeki fix'ler toplamaya alınmaz. */
        const val MAX_FIX_ACCURACY_M = 12f

        /** Bundan eski fix'ler atılır (alıcı bazen eski fix'i yeniden verir). */
        const val MAX_FIX_AGE_MS = 3_000L

        /** Bu kadar süre fix gelmezse "uydu aranıyor". */
        const val FIX_TIMEOUT_MS = 5_000L

        /** İki nokta arası bu süreyi aşarsa ortak sapma sadeleşmez; uyarılır. */
        const val STALE_BASELINE_MS = 10 * 60 * 1000L

        /** Bir noktayı kabul etmek için gereken en az fix sayısı. */
        const val MIN_FIXES = 4

        /**
         * Kısa baz hattı kazancı. İki nokta aynı uydularla kısa arayla alınınca
         * yörünge/saat/iyonosfer hataları farkta sadeleşir; yalnızca çoklu yansıma
         * ve alıcı gürültüsü kalır. Bağımsız hata varsayımının 0,7'si, açık
         * alanla kentsel ortam arasında orta bir değer — iddialı değil.
         */
        const val SHORT_BASELINE_GAIN = 0.7f

        private const val TICK_MS = 250L

        const val FAIL_TOO_FEW = "az"
        const val FAIL_NO_FIX = "fix"
    }

    enum class Phase {
        /** Alıcı kapalı. */
        OFF,
        /** Kesin konum izni yok. */
        NO_PERMISSION,
        /** Konum servisi (GPS sağlayıcısı) sistemde kapalı. */
        GPS_OFF,
        /** Alıcı açık, taze fix yok. */
        SEARCHING,
        /** Taze fix var; işaretlenebilir. */
        READY,
        /** Bir nokta için fix toplanıyor. */
        MARKING
    }

    data class Mark(
        val lat: Double,
        val lon: Double,
        /** Noktanın 1σ yatay belirsizliği (m). */
        val sigmaM: Float,
        val fixes: Int,
        /** SystemClock.elapsedRealtime — iki nokta arası süre için. */
        val atMs: Long
    )

    data class TapeState(
        val phase: Phase = Phase.OFF,
        /** 0..1 toplama ilerlemesi (MARKING). */
        val progress: Float = 0f,
        /** Son taze fix'in doğruluğu (m). */
        val fixAccuracyM: Float? = null,
        val satsUsed: Int = 0,
        val satsTotal: Int = 0,
        val a: Mark? = null,
        val b: Mark? = null,
        /** A işaretlendi, B henüz yok: A'dan anlık uzaklık. */
        val liveMeters: Float? = null,
        val liveSigmaM: Float? = null,
        /** A→B sonucu (b != null iken anlamlı). */
        val meters: Float = 0f,
        val sigmaM: Float = 0f,
        val bearingDeg: Float = 0f,
        val gapMs: Long = 0L,
        val failed: String? = null
    ) {
        val done: Boolean get() = a != null && b != null
    }

    private val _state = MutableStateFlow(TapeState())
    val state: StateFlow<TapeState> = _state

    private val locationManager: LocationManager? =
        runCatching { context.getSystemService(LocationManager::class.java) }.getOrNull()

    private val lock = Any()
    private val averager = PositionAverager()

    @Volatile private var wanted = false
    @Volatile private var visible = true
    @Volatile private var permitted = hasFinePermission()
    @Volatile private var listening = false
    @Volatile private var gnssRegistered = false
    @Volatile private var collecting = false
    @Volatile private var progress = 0f
    @Volatile private var failed: String? = null
    @Volatile private var satsUsed = 0
    @Volatile private var satsTotal = 0

    private var lastFix: Location? = null
    private var lastFixMs = 0L

    @Volatile private var a: Mark? = null
    @Volatile private var b: Mark? = null
    @Volatile private var meters = 0f
    @Volatile private var sigmaM = 0f
    @Volatile private var bearingDeg = 0f
    @Volatile private var gapMs = 0L

    private var collectJob: Job? = null
    private var tickJob: Job? = null

    // ---------- dışarıya açık kumanda ----------

    /** Activity izin sonucunu buraya bildirir. */
    fun setPermitted(fine: Boolean) {
        permitted = fine
        sync()
    }

    /**
     * Ekran önde mi. Alıcı yalnızca ekran öndeyken açık kalır: arka planda
     * konum okunmaz — hem pil hem gizlilik hem Android 14+ kısıtı.
     */
    fun setVisible(value: Boolean) {
        visible = value
        sync()
    }

    /** Alıcıyı aç. İzin yoksa durum NO_PERMISSION olur, arayüz izni ister. */
    fun start() {
        wanted = true
        failed = null
        sync()
    }

    /** Alıcıyı kapat; işaretler silinir. */
    fun stop() {
        wanted = false
        cancelCollect()
        clearMarks()
        failed = null
        sync()
    }

    /**
     * A ya da B noktasını işaretle. Ölçüm bittiyse (A ve B var) yeni ölçüme
     * sıfırlar — düğme etiketi arayüzde buna göre değişir.
     */
    fun mark() {
        if (collecting) return
        if (a != null && b != null) {
            clearMarks()
            failed = null
            publish()
            return
        }
        val fresh = synchronized(lock) {
            lastFix != null && SystemClock.elapsedRealtime() - lastFixMs <= FIX_TIMEOUT_MS
        }
        if (!listening || !fresh) {
            failed = FAIL_NO_FIX
            publish()
            return
        }
        beginCollect()
    }

    fun reset() {
        cancelCollect()
        clearMarks()
        failed = null
        publish()
    }

    fun release() {
        collectJob?.cancel()
        stopListening()
    }

    // ---------- alıcı ----------

    private fun hasFinePermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun gpsEnabled(): Boolean =
        runCatching { locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true }
            .getOrDefault(false)

    private fun sync() {
        val should = wanted && visible && permitted
        if (should && !listening) startListening()
        else if (!should && listening) stopListening()
        publish()
    }

    @SuppressLint("MissingPermission") // izin hemen üstte denetleniyor; Activity de sonucu bildiriyor
    private fun startListening() {
        val lm = locationManager ?: run {
            log("Şerit metre: bu cihazda konum servisi yok")
            return
        }
        if (!hasFinePermission()) {
            permitted = false
            return
        }
        val ok = runCatching {
            // Yalnızca uydu alıcısı; 500 ms istense de çoğu alıcı 1 Hz verir.
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 500L, 0f, listener, Looper.getMainLooper()
            )
        }.onFailure { log("Şerit metre: alıcı açılamadı: ${it.message}") }.isSuccess
        if (!ok) return
        listening = true
        registerGnss(lm)
        synchronized(lock) {
            lastFix = null
            lastFixMs = 0L
        }
        startTick()
        log("Şerit metre: uydu alıcısı açık")
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun registerGnss(lm: LocationManager) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.registerGnssStatusCallback(context.mainExecutor, gnssCallback)
            } else {
                lm.registerGnssStatusCallback(gnssCallback, Handler(Looper.getMainLooper()))
            }
            gnssRegistered = true
        }
    }

    private fun stopListening() {
        val lm = locationManager
        if (listening) {
            listening = false
            runCatching { lm?.removeUpdates(listener) }
            log("Şerit metre: uydu alıcısı kapalı")
        }
        if (gnssRegistered) {
            gnssRegistered = false
            runCatching { lm?.unregisterGnssStatusCallback(gnssCallback) }
        }
        tickJob?.cancel()
        tickJob = null
        satsUsed = 0
        satsTotal = 0
        synchronized(lock) {
            lastFix = null
            lastFixMs = 0L
        }
        if (collecting) {
            // Toplama yarıda kaldı (ekran arkaya geçti): yarım nokta kaydedilmez.
            cancelCollect()
            failed = FAIL_TOO_FEW
        }
    }

    private fun startTick() {
        tickJob?.cancel()
        tickJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(TICK_MS)
                publish()
            }
        }
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)

        override fun onProviderEnabled(provider: String) = publish()

        override fun onProviderDisabled(provider: String) = publish()

        // API 26–28'de arayüzde soyut; gövdesiz bırakılsa eski cihazda
        // AbstractMethodError ile çökerdi.
        @Deprecated("Eski API; yalnızca API 26–28 uyumluluğu için")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            satsUsed = used
            satsTotal = status.satelliteCount
        }
    }

    private fun onFix(location: Location) {
        if (!location.hasAccuracy()) return
        val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        if (ageMs > MAX_FIX_AGE_MS) return
        synchronized(lock) {
            lastFix = location
            lastFixMs = SystemClock.elapsedRealtime()
            if (collecting && location.accuracy <= MAX_FIX_ACCURACY_M) {
                averager.add(location.latitude, location.longitude, location.accuracy)
            }
        }
        publish()
    }

    // ---------- işaretleme ----------

    private fun beginCollect() {
        synchronized(lock) { averager.clear() }
        failed = null
        progress = 0f
        collecting = true
        collectJob?.cancel()
        collectJob = scope.launch(Dispatchers.IO) {
            val start = SystemClock.elapsedRealtime()
            while (isActive && SystemClock.elapsedRealtime() - start < DWELL_MS) {
                progress = (SystemClock.elapsedRealtime() - start).toFloat() / DWELL_MS
                publish()
                delay(120)
            }
            if (isActive) finishCollect()
        }
    }

    private fun cancelCollect() {
        collectJob?.cancel()
        collectJob = null
        collecting = false
        progress = 0f
    }

    private fun finishCollect() {
        val result = synchronized(lock) {
            collecting = false
            averager.result(MIN_FIXES)
        }
        if (result == null) {
            failed = FAIL_TOO_FEW
            publish()
            return
        }
        val mark = Mark(result.lat, result.lon, result.sigmaM, result.count, SystemClock.elapsedRealtime())
        val start = a
        if (start == null) {
            a = mark
            log("Şerit metre A: ±${fmt(mark.sigmaM)} m, ${mark.fixes} fix, saçılım ${fmt(result.scatterM)} m")
        } else {
            val g = Geodesy.inverse(start.lat, start.lon, mark.lat, mark.lon)
            val gap = mark.atMs - start.atMs
            val gain = if (gap <= STALE_BASELINE_MS) SHORT_BASELINE_GAIN else 1f
            meters = g.meters.toFloat()
            bearingDeg = g.bearingDeg.toFloat()
            sigmaM = sqrt(start.sigmaM * start.sigmaM + mark.sigmaM * mark.sigmaM) * gain
            gapMs = gap
            b = mark
            log("Şerit metre A→B: ${fmt(meters)} m ±${fmt(sigmaM)} m, yön ${bearingDeg.toInt()}°")
        }
        publish()
    }

    private fun clearMarks() {
        a = null
        b = null
        meters = 0f
        sigmaM = 0f
        bearingDeg = 0f
        gapMs = 0L
    }

    // ---------- durum ----------

    private fun publish() {
        val now = SystemClock.elapsedRealtime()
        val fix: Location?
        val fresh: Boolean
        synchronized(lock) {
            fix = lastFix
            fresh = fix != null && now - lastFixMs <= FIX_TIMEOUT_MS
        }
        val phase = when {
            !wanted -> Phase.OFF
            !permitted -> Phase.NO_PERMISSION
            !gpsEnabled() -> Phase.GPS_OFF
            collecting -> Phase.MARKING
            !fresh -> Phase.SEARCHING
            else -> Phase.READY
        }

        val start = a
        var live: Float? = null
        var liveSigma: Float? = null
        if (start != null && b == null && fresh && fix != null) {
            live = Geodesy.inverse(start.lat, start.lon, fix.latitude, fix.longitude).meters.toFloat()
            liveSigma = sqrt(start.sigmaM * start.sigmaM + fix.accuracy * fix.accuracy)
        }

        _state.value = TapeState(
            phase = phase,
            progress = if (collecting) progress else 0f,
            fixAccuracyM = if (fresh) fix?.accuracy else null,
            satsUsed = satsUsed,
            satsTotal = satsTotal,
            a = start,
            b = b,
            liveMeters = live,
            liveSigmaM = liveSigma,
            meters = meters,
            sigmaM = sigmaM,
            bearingDeg = bearingDeg,
            gapMs = gapMs,
            failed = failed
        )
    }

    private fun fmt(v: Float): String = "%.1f".format(v)
}
