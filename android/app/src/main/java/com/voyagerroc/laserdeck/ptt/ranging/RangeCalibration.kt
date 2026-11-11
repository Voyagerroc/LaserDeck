package com.voyagerroc.laserdeck.ptt.ranging

import android.content.Context
import kotlin.math.log10

/**
 * Cihaz çifti başına yol kaybı kalibrasyonu.
 *
 * ## Neden gerekli
 * Mesafe kestirimi "1 metrede sinyal kaç dBm?" sorusunun cevabına dayanır.
 * Bu değer telefondan telefona 10–20 dB değişir: gönderim gücü, anten, el
 * tutuşu, hatta kılıf. Genel bir varsayılanla çalışmak, her ölçümü sabit bir
 * çarpanla yanlış yapmak demek.
 *
 * İki telefona özel tek bir ölçümle bu sabit kaybolur ve kalan hata yalnızca
 * ortamın gürültüsü olur.
 *
 * ## Neden 75. yüzdelik
 * Gölgeleme TEK YÖNLÜDÜR: araya giren bir şey sinyali yalnızca ZAYIFLATIR,
 * güçlendirmez. Bu yüzden sabit mesafedeki örneklerin dağılımı sola çarpıktır
 * ve üst çeyrek, engelsiz gerçek değere ortalamadan çok daha yakındır.
 * Ortalama alsaydık her kalibrasyon sistematik olarak "olduğundan uzak"
 * çıkardı.
 */
class RangeCalibration(context: Context) {

    companion object {
        private const val PREFS = "mesafe_kalibrasyon"

        /** Kalibrasyon yokken kullanılan genel varsayılan. */
        const val DEFAULT_REF_DBM = -55.0

        /** Yol kaybı üsteli — iç mekân/karışık ortam. */
        const val DEFAULT_N = 2.5

        /** Kalibrasyonun yapıldığı mesafe. */
        const val CALIBRATION_DISTANCE_M = 1.0

        /** Bu yayılımın üstünde ölçüm güvenilmez sayılır (dB). */
        const val MAX_SPREAD_DB = 8.0
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Calibration(val refDbm: Double, val n: Double, val samples: Int) {
        val calibrated: Boolean get() = samples > 0
    }

    fun load(peerAddress: String?): Calibration {
        if (peerAddress == null) return Calibration(DEFAULT_REF_DBM, DEFAULT_N, 0)
        val ref = prefs.getFloat(key(peerAddress, "ref"), Float.NaN)
        val n = prefs.getFloat(key(peerAddress, "n"), DEFAULT_N.toFloat())
        val count = prefs.getInt(key(peerAddress, "count"), 0)
        return if (ref.isNaN()) {
            Calibration(DEFAULT_REF_DBM, DEFAULT_N, 0)
        } else {
            Calibration(ref.toDouble(), n.toDouble(), count)
        }
    }

    fun save(peerAddress: String, refDbm: Double, samples: Int) {
        prefs.edit()
            .putFloat(key(peerAddress, "ref"), refDbm.toFloat())
            .putFloat(key(peerAddress, "n"), DEFAULT_N.toFloat())
            .putInt(key(peerAddress, "count"), samples)
            .apply()
    }

    fun clear(peerAddress: String) {
        prefs.edit()
            .remove(key(peerAddress, "ref"))
            .remove(key(peerAddress, "n"))
            .remove(key(peerAddress, "count"))
            .apply()
    }

    private fun key(address: String, field: String) =
        "${address.filter { it.isLetterOrDigit() }}_$field"

    /**
     * Toplanan örneklerden 1 m referansını çıkarır.
     *
     * Yayılım çok genişse (biri hareket etti, araya bir şey girdi) null döner —
     * kötü bir kalibrasyon, kalibrasyonsuzluktan daha zararlıdır çünkü
     * sonrasındaki her ölçümü sistematik olarak bozar.
     */
    fun estimateRef(samples: List<Double>): Double? {
        if (samples.size < 20) return null
        val sorted = samples.sorted()
        val q1 = sorted[sorted.size / 4]
        val q3 = sorted[(sorted.size * 3) / 4]
        if (q3 - q1 > MAX_SPREAD_DB) return null
        // Üst YARININ ortalaması (≈ 75. yüzdelik): engellenmemiş duruma en yakın kestirim.
        val upper = sorted.drop(sorted.size / 2)
        return upper.average()
    }

    /** Kalibrasyon 1 metrede yapıldığı için referans doğrudan okunan değerdir. */
    fun distanceFor(cal: Calibration, signalDbm: Double): Double =
        Math.pow(10.0, (cal.refDbm - signalDbm) / (10.0 * cal.n))

    /** Verilen mesafede beklenen sinyal — engel tespiti için. */
    fun expectedDbm(cal: Calibration, meters: Double): Double =
        cal.refDbm - 10.0 * cal.n * log10(meters.coerceAtLeast(0.1))
}
