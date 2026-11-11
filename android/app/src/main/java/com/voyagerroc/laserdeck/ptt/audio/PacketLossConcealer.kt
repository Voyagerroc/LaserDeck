package com.voyagerroc.laserdeck.ptt.audio

import java.util.Random
import kotlin.math.abs

/**
 * Kayıp çerçeve gizleme.
 *
 * Hat takıldığında ses akışında boşluk oluşuyor. Şu ana kadar bu boşluk
 * DİJİTAL SESSİZLİKLE dolduruluyordu: iki ucunda da süreksizlik olan, tık
 * sesi çıkaran ve kullanıcıya "hat öldü" hissi veren bir boşluk. AMR'nin kendi
 * gizleme yeteneği kullanılamıyor — Android'in kod çözücüsü kayıp çerçeve
 * bildirildiğinde tamponu sıfırlamakla yetiniyor.
 *
 * Uygulanan yöntem:
 *  - Kısa boşluk (≤100 ms): son sesin PERDE PERİYODU tekrarlanır ve kademeli
 *    olarak söndürülür. Tüm çerçeveyi döngüye almak 50 Hz'lik bir vızıltı
 *    üretirdi; tek perde periyodu doğal duyuluyor.
 *  - Uzun boşluk: karşı tarafın ölçülen zemin gürültüsü seviyesinde konfor
 *    gürültüsü. Sessizlik yerine oda tonu duymak "hat hâlâ açık" demek —
 *    kullanıcıların "alo? alo?" deyip kapatmasını bu engelliyor.
 */
class PacketLossConcealer(private val sampleRate: Int, private val frameSamples: Int) {

    companion object {
        /** Ardışık boşluk çerçeveleri için sönme çarpanları (~20 ms adım). */
        private val FADE = floatArrayOf(1.0f, 0.7f, 0.45f, 0.25f, 0.1f)
    }

    private val minLag = sampleRate / 400   // 400 Hz
    private val maxLag = sampleRate / 60    // 60 Hz

    private var lastFrame: ShortArray? = null
    private var pitchLag = 0
    private var phase = 0
    private var gapCount = 0
    private val noise = Random(1)
    private var noiseState = 0f

    /** Karşı tarafın sessizlik anlarında ölçülen zemin seviyesi. */
    private var floorLevel = 0f

    /** Gerçek bir çerçeve çalındığında çağrılır. */
    fun onFrame(pcm: ShortArray) {
        if (pcm.isEmpty()) return
        lastFrame = pcm.copyOf()
        gapCount = 0
        phase = 0
        pitchLag = 0

        // Zemin seviyesi yalnızca sessiz anlarda güncellenir, yoksa konuşma
        // gürültü tahminini yukarı çeker.
        val level = meanAbs(pcm)
        floorLevel = if (level < floorLevel || floorLevel == 0f) {
            floorLevel * 0.9f + level * 0.1f
        } else {
            floorLevel * 0.999f
        }
    }

    /** Boşluk için bir çerçeve üretir. Null dönmez. */
    fun conceal(): ShortArray {
        val out = ShortArray(frameSamples)
        val last = lastFrame
        gapCount++

        if (last == null || gapCount > FADE.size) {
            fillComfortNoise(out)
            return out
        }

        if (pitchLag == 0) pitchLag = estimatePitch(last)
        val gain = FADE[gapCount - 1]
        val src = last
        for (i in out.indices) {
            val idx = src.size - pitchLag + (phase % pitchLag)
            phase++
            val v = if (idx in src.indices) src[idx] else 0
            out[i] = (v * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** Kayıp uzadıysa oda tonu: alçak geçiren süzülmüş beyaz gürültü. */
    private fun fillComfortNoise(out: ShortArray) {
        val amp = floorLevel.coerceIn(0f, 900f)
        if (amp < 1f) return // gerçekten sessizse sessiz bırak
        for (i in out.indices) {
            val w = (noise.nextFloat() * 2f - 1f)
            noiseState = 0.85f * noiseState + 0.53f * w
            out[i] = (noiseState * amp * 1.5f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /**
     * En güçlü tekrar eden periyodu bul. Tam bir perde çözümleyicisi değil;
     * gizleme için "hangi uzunluğu döngüye alırsam doğal duyulur" sorusuna
     * yeterli bir cevap veriyor.
     */
    private fun estimatePitch(pcm: ShortArray): Int {
        val n = pcm.size
        if (n <= maxLag + 4) return (n / 2).coerceAtLeast(1)
        var bestLag = minLag
        var best = Long.MIN_VALUE
        var lag = minLag
        while (lag <= maxLag) {
            var sum = 0L
            var i = n - lag
            while (i < n) {
                sum += pcm[i].toLong() * pcm[i - lag].toLong()
                i++
            }
            if (sum > best) {
                best = sum
                bestLag = lag
            }
            lag++
        }
        return bestLag.coerceIn(1, n)
    }

    private fun meanAbs(pcm: ShortArray): Float {
        var sum = 0L
        for (s in pcm) sum += abs(s.toInt()).toLong()
        return sum.toFloat() / pcm.size
    }

    fun reset() {
        lastFrame = null
        gapCount = 0
        phase = 0
        pitchLag = 0
    }
}
