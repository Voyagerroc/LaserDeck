package com.voyagerroc.laserdeck.ptt.ranging

/**
 * Mesafe kestirimi — ve ne kadarına güvenilebileceği.
 *
 * Sinyal gücünden mesafe çıkarmanın hatası ÇARPANSAL'dır: ±6 dB'lik bir
 * belirsizlik her mesafede "gerçek değer 0,58 ile 1,74 katı arasında" demektir.
 * Bu yüzden tek bir sayı yerine hep bir aralık taşınıyor ve arayüzde de aralık
 * gösteriliyor.
 *
 * [trend] ise kalibrasyondan BAĞIMSIZ olarak güvenilir: bilinmeyen sabit
 * türevde sadeleşir. Yani "kaç metre" tartışmalıyken "yaklaşıyor" kesindir.
 */
data class RangeEstimate(
    /** Kestirilen mesafe (m). [state] LIVE değilse anlamsızdır. */
    val meters: Float = 0f,
    /** Güven aralığının alt ve üst sınırı (m). */
    val low: Float = 0f,
    val high: Float = 0f,
    /** Yaklaşma hızı (m/s); negatif = yaklaşıyor. */
    val closingSpeed: Float = 0f,
    val trend: Trend = Trend.UNKNOWN,
    val zone: Zone = Zone.UNKNOWN,
    /** Saniyedeki örnek sayısı — radar süpürmesinin hızını bu belirler. */
    val sampleHz: Float = 0f,
    val state: State = State.OFF,
    /** Son örnekten bu yana geçen süre (ms). */
    val ageMs: Long = 0L
) {
    enum class Trend { CLOSING, HOLDING, OPENING, UNKNOWN }

    /**
     * Metre yerine bölge göstermek daha dürüst: sınırlar logaritmik aralıklı,
     * yani bir bölge değişimi gerçekten anlamlı bir değişimdir — oysa metredeki
     * basamaklar çoğu zaman gürültüdür.
     */
    enum class Zone { CONTACT, CLOSE, NEAR, FAR, UNKNOWN }

    enum class State {
        /** Radar kapalı ya da hat yok. */
        OFF,
        /** Bu Android sürümü konum izni olmadan tarama yaptırmıyor. */
        UNSUPPORTED,
        /** İzin verilmedi. */
        NO_PERMISSION,
        /** Ölçüm akıyor. */
        LIVE,
        /** Ses aktarımı sürüyor; tarama askıda (aynı radyo). */
        PAUSED,
        /** Hat var ama sinyal örneği gelmiyor. */
        NO_SIGNAL
    }

    companion object {
        fun zoneFor(meters: Float): Zone = when {
            meters < 1.5f -> Zone.CONTACT
            meters < 4f -> Zone.CLOSE
            meters < 12f -> Zone.NEAR
            else -> Zone.FAR
        }
    }
}
