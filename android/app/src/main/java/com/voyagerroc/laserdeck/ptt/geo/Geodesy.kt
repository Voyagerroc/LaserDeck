package com.voyagerroc.laserdeck.ptt.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * WGS84 elipsoidi üzerinde jeodezi — saf Kotlin, Android'siz, JVM'de test edilir.
 *
 * ## Neden Vincenty
 * Küre varsayımı (haversine) elipsoidden %0,3–0,5 sapar: 10 m'de 5 cm, 1 km'de
 * 5 m. GPS gürültüsü bunun kat kat üstünde olsa da hesap katmanı kendi
 * hatasını eklemesin — sapmanın tamamı ölçümden gelsin, formülden değil.
 * Android'in `Location.distanceBetween` çağrısı da aynı algoritmayı kullanır;
 * burada yeniden yazılması birim testlerin cihazsız çalışabilmesi için.
 */
object Geodesy {
    /** Yarı büyük eksen (m). */
    const val A = 6378137.0

    /** Basıklık. */
    const val F = 1.0 / 298.257223563

    /** Yarı küçük eksen (m). */
    val B = A * (1 - F)

    /** Birinci dışmerkezlik karesi. */
    val E2 = F * (2 - F)

    data class Geodesic(
        /** Elipsoid üzerindeki en kısa yol (m). */
        val meters: Double,
        /** Başlangıç noktasındaki ileri yön, kuzeyden saat yönünde 0..360. */
        val bearingDeg: Double
    )

    /**
     * Vincenty ters çözümü. Karşıt (antipodal) noktalarda yakınsamaz; o durumda
     * küresel formüle düşer — bu uygulamada asla olmayacak bir durum ama sonsuz
     * döngü riski hiç bırakılmasın.
     */
    fun inverse(lat1Deg: Double, lon1Deg: Double, lat2Deg: Double, lon2Deg: Double): Geodesic {
        val lat1 = Math.toRadians(lat1Deg)
        val lat2 = Math.toRadians(lat2Deg)
        val l = Math.toRadians(lon2Deg - lon1Deg)
        val u1 = atan((1 - F) * tan(lat1))
        val u2 = atan((1 - F) * tan(lat2))
        val sinU1 = sin(u1)
        val cosU1 = cos(u1)
        val sinU2 = sin(u2)
        val cosU2 = cos(u2)

        var lambda = l
        var sinLambda = 0.0
        var cosLambda = 0.0
        var sinSigma = 0.0
        var cosSigma = 0.0
        var sigma = 0.0
        var cos2Alpha = 0.0
        var cos2SigmaM = 0.0
        var iterations = 0
        var converged = false
        while (iterations < 200) {
            iterations++
            sinLambda = sin(lambda)
            cosLambda = cos(lambda)
            val t1 = cosU2 * sinLambda
            val t2 = cosU1 * sinU2 - sinU1 * cosU2 * cosLambda
            sinSigma = sqrt(t1 * t1 + t2 * t2)
            if (sinSigma == 0.0) return Geodesic(0.0, 0.0) // çakışık noktalar
            cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda
            sigma = atan2(sinSigma, cosSigma)
            val sinAlpha = cosU1 * cosU2 * sinLambda / sinSigma
            cos2Alpha = 1 - sinAlpha * sinAlpha
            cos2SigmaM = if (cos2Alpha == 0.0) 0.0 else cosSigma - 2 * sinU1 * sinU2 / cos2Alpha
            val c = F / 16 * cos2Alpha * (4 + F * (4 - 3 * cos2Alpha))
            val previous = lambda
            lambda = l + (1 - c) * F * sinAlpha *
                    (sigma + c * sinSigma * (cos2SigmaM + c * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)))
            if (abs(lambda - previous) < 1e-12) {
                converged = true
                break
            }
        }
        if (!converged) return haversine(lat1Deg, lon1Deg, lat2Deg, lon2Deg)

        val uSq = cos2Alpha * (A * A - B * B) / (B * B)
        val bigA = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)))
        val bigB = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)))
        val deltaSigma = bigB * sinSigma * (
                cos2SigmaM + bigB / 4 * (
                        cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM) -
                                bigB / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma) *
                                (-3 + 4 * cos2SigmaM * cos2SigmaM)
                        )
                )
        val s = B * bigA * (sigma - deltaSigma)
        val alpha1 = atan2(cosU2 * sinLambda, cosU1 * sinU2 - sinU1 * cosU2 * cosLambda)
        return Geodesic(s, (Math.toDegrees(alpha1) + 360.0) % 360.0)
    }

    /** Küresel yedek — yalnızca Vincenty yakınsamazsa. */
    private fun haversine(lat1Deg: Double, lon1Deg: Double, lat2Deg: Double, lon2Deg: Double): Geodesic {
        val r = 6371008.8
        val lat1 = Math.toRadians(lat1Deg)
        val lat2 = Math.toRadians(lat2Deg)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(lon2Deg - lon1Deg)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        val d = 2 * r * atan2(sqrt(h), sqrt(1 - h))
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return Geodesic(d, (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0)
    }

    /** Verilen enlemde bir derece enlemin uzunluğu (m) — meridyen eğrilik yarıçapından. */
    fun metersPerDegreeLat(latDeg: Double): Double {
        val s = sin(Math.toRadians(latDeg))
        val w = 1 - E2 * s * s
        return PI / 180 * A * (1 - E2) / w.pow(1.5)
    }

    /** Verilen enlemde bir derece boylamın uzunluğu (m) — paralel dairenin yarıçapından. */
    fun metersPerDegreeLon(latDeg: Double): Double {
        val rad = Math.toRadians(latDeg)
        val s = sin(rad)
        return PI / 180 * A * cos(rad) / sqrt(1 - E2 * s * s)
    }
}

/**
 * Bir noktada hareketsiz dururken toplanan GPS fix'lerinin ağırlıklı ortalaması.
 *
 * ## Neden ağırlıklı
 * Her fix kendi doğruluk yarıçapıyla gelir (%68, m). 3 m'lik bir fix ile 12 m'lik
 * bir fix aynı ağırlıkta toplanmaz: ağırlık 1/σ² — en küçük kareler çözümünün
 * verdiği ağırlık.
 *
 * ## Neden √N kadar iyileşme İDDİA EDİLMİYOR
 * Ardışık fix'ler bağımsız değildir: alıcının kendi süzgeci ve aynı uydu
 * geometrisi yüzünden 1 Hz'lik 8 örnek belki 2–3 bağımsız örneğe denktir.
 * Etkin örnek sayısı bu yüzden [N_EFF_MAX] ile sınırlandırılır. Ayrıca
 * fix'lerin ortalama çevresindeki saçılımı σ'yi hiçbir zaman küçültmez, yalnızca
 * büyütür — kullanıcı toplama sırasında kıpırdadıysa bu, bantta görünür.
 *
 * Hesap yerel teğet düzlemde (metre) yapılır: enlem/boylam derecelerinin
 * doğrudan ortalaması 10 m ölçeğinde zararsız olsa da σ'nin metre cinsinden
 * çıkması gerekiyor.
 */
class PositionAverager {

    companion object {
        /** Ardışık fix'lerin ilişkili olması nedeniyle etkin bağımsız örnek tavanı. */
        const val N_EFF_MAX = 3
    }

    private class Fix(val lat: Double, val lon: Double, val accuracyM: Double)

    data class Result(
        val lat: Double,
        val lon: Double,
        /** Ortalamanın 1σ yatay belirsizliği (m). */
        val sigmaM: Float,
        val count: Int,
        /** Fix'lerin ortalama çevresindeki ağırlıklı RMS saçılımı (m). */
        val scatterM: Float,
        val medianAccuracyM: Float
    )

    private val fixes = ArrayList<Fix>()

    val count: Int get() = fixes.size

    fun add(lat: Double, lon: Double, accuracyM: Float) {
        if (!accuracyM.isFinite() || accuracyM <= 0f) return
        if (!lat.isFinite() || !lon.isFinite()) return
        fixes += Fix(lat, lon, accuracyM.toDouble())
    }

    fun clear() = fixes.clear()

    fun result(minFixes: Int): Result? {
        if (fixes.size < minFixes || fixes.isEmpty()) return null
        val lat0 = fixes[0].lat
        val lon0 = fixes[0].lon
        val mLat = Geodesy.metersPerDegreeLat(lat0)
        val mLon = Geodesy.metersPerDegreeLon(lat0)

        var sumW = 0.0
        var sumE = 0.0
        var sumN = 0.0
        for (f in fixes) {
            val w = 1.0 / (f.accuracyM * f.accuracyM)
            sumW += w
            sumE += w * (f.lon - lon0) * mLon
            sumN += w * (f.lat - lat0) * mLat
        }
        val east = sumE / sumW
        val north = sumN / sumW

        var sumSq = 0.0
        for (f in fixes) {
            val w = 1.0 / (f.accuracyM * f.accuracyM)
            val de = (f.lon - lon0) * mLon - east
            val dn = (f.lat - lat0) * mLat - north
            sumSq += w * (de * de + dn * dn)
        }
        val scatter = sqrt(sumSq / sumW)

        val sorted = fixes.map { it.accuracyM }.sorted()
        val median = sorted[sorted.size / 2]
        val nEff = minOf(fixes.size, N_EFF_MAX).toDouble()
        val sigma = maxOf(median / sqrt(nEff), scatter)

        return Result(
            lat = lat0 + north / mLat,
            lon = lon0 + east / mLon,
            sigmaM = sigma.toFloat(),
            count = fixes.size,
            scatterM = scatter.toFloat(),
            medianAccuracyM = median.toFloat()
        )
    }
}
