package com.voyagerroc.laserdeck.ptt.audio

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import java.nio.ByteOrder

/**
 * Android'in yerleşik AMR kodlayıcıları üzerinden ses codec'i — harici/native
 * bağımlılık yok.
 *
 *  - AMR-NB: 8 kHz, 12.2 kbps. Telefon şebekesi kalitesi. Her cihazda var.
 *  - AMR-WB: 16 kHz, 15.85 kbps. "HD ses" bandı; sesin tizleri korunduğu için
 *    konuşma belirgin biçimde daha anlaşılır. Android uyum tanımı el
 *    cihazlarında kodlayıcıyı zorunlu tutuyor, yine de çalışma zamanında
 *    sınanıyor ve çağrı kurulurken iki taraf ortak en iyi codec'te anlaşıyor.
 *
 * ## Kilitleme
 * Kodlayıcı ve kod çözücü AYRI kilitler kullanır. Tek bir sınıf kilidi
 * paylaşıldığında mikrofon iş parçacığı ile çalma iş parçacığı birbirini
 * bekliyordu; her biri kilidi 20 ms'ye kadar tutabildiği için bu doğrudan
 * gecikmeye yazılıyordu — üstelik ikisi zaten ayrı MediaCodec nesneleri,
 * paylaşılan bir duruma dokunmuyorlar.
 */
class AmrCodec private constructor(
    override val id: Int,
    override val sampleRate: Int,
    private val mime: String,
    bitRate: Int
) : VoiceCodec {

    override val frameSamples = sampleRate / 50 // 20 ms

    /** Girişte beklemek yok: tampon hazır değilse çerçeve ATLANMAZ, sonra denenir. */
    private val outTimeoutUs = 4_000L

    private var encPtsUs = 0L
    private var decPtsUs = 0L

    private val encLock = Any()
    private val decLock = Any()

    private val encoder: MediaCodec =
        MediaCodec.createEncoderByType(mime).apply {
            val fmt = MediaFormat.createAudioFormat(mime, sampleRate, 1)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            // 0 = gerçek zamanlı. Belgeler bu değeri tam da konuşma iletimi için
            // tarif ediyor; ayarlanmazsa kaynak sıkıştığında ilk gözden
            // çıkarılanlardan oluyoruz.
            fmt.setInteger(MediaFormat.KEY_PRIORITY, 0)
            configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

    private val decoder: MediaCodec =
        MediaCodec.createDecoderByType(mime).apply {
            val fmt = MediaFormat.createAudioFormat(mime, sampleRate, 1)
            fmt.setInteger(MediaFormat.KEY_PRIORITY, 0)
            configure(fmt, null, null, 0)
            start()
        }

    /**
     * MediaCodec bir boru hattıdır: girişe verilen çerçevenin çıktısı hemen
     * gelmez. Çağrı başında ilk birkaç çerçeve boşa gidiyor ve kullanıcı
     * "ilk kelimem kesildi" diyordu. Sessizlikle önceden doldurup çıktıyı
     * atarak boru hattı ısıtılıyor.
     */
    fun prime() {
        val silence = ShortArray(frameSamples)
        repeat(5) { runCatching { encode(silence) } }
    }

    override fun encode(pcm: ShortArray): ByteArray? = synchronized(encLock) {
        // Giriş tamponu yoksa BEKLEME: bu çerçeveyi düşürmek yerine hemen
        // çıktıyı boşaltmayı dene. Eskiden 10 ms bekleyip sonra sessizce
        // çerçeveyi atıyordu.
        val inIdx = encoder.dequeueInputBuffer(0)
        if (inIdx >= 0) {
            val inBuf = encoder.getInputBuffer(inIdx)
            if (inBuf != null) {
                inBuf.clear()
                inBuf.order(ByteOrder.LITTLE_ENDIAN)
                for (s in pcm) inBuf.putShort(s)
                encoder.queueInputBuffer(inIdx, 0, pcm.size * 2, encPtsUs, 0)
                encPtsUs += 20_000
            }
        }
        drainEncoder()
    }

    /**
     * Çıktı DÖNGÜYLE boşaltılır. Tek çıktı almak, boru hattında kalıcı bir
     * kayma bırakıyor ve fazlası hiç alınmıyordu.
     */
    private fun drainEncoder(): ByteArray? {
        var result: ByteArray? = null
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = encoder.dequeueOutputBuffer(info, if (result == null) outTimeoutUs else 0)
            if (outIdx < 0) break
            val outBuf = encoder.getOutputBuffer(outIdx)
            if (outBuf != null && info.size > 0) {
                val data = ByteArray(info.size)
                outBuf.position(info.offset)
                outBuf.get(data)
                // Birden fazla çıktı biriktiyse en tazesi tutulur: eski ses
                // çerçevesini göndermenin anlamı yok.
                result = data
            }
            encoder.releaseOutputBuffer(outIdx, false)
        }
        return result
    }

    override fun decode(data: ByteArray): ShortArray? = synchronized(decLock) {
        val inIdx = decoder.dequeueInputBuffer(outTimeoutUs)
        if (inIdx >= 0) {
            val inBuf = decoder.getInputBuffer(inIdx)
            if (inBuf != null) {
                inBuf.clear()
                inBuf.put(data)
                decoder.queueInputBuffer(inIdx, 0, data.size, decPtsUs, 0)
                decPtsUs += 20_000
            }
        }

        var result: ShortArray? = null
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = decoder.dequeueOutputBuffer(info, if (result == null) outTimeoutUs else 0)
            if (outIdx < 0) break
            val outBuf = decoder.getOutputBuffer(outIdx)
            if (outBuf != null && info.size > 0) {
                outBuf.position(info.offset)
                outBuf.order(ByteOrder.LITTLE_ENDIAN)
                val samples = ShortArray(info.size / 2)
                outBuf.asShortBuffer().get(samples)
                result = samples
            }
            decoder.releaseOutputBuffer(outIdx, false)
        }
        result
    }

    override fun release() {
        synchronized(encLock) { runCatching { encoder.stop(); encoder.release() } }
        synchronized(decLock) { runCatching { decoder.stop(); decoder.release() } }
    }

    companion object {
        fun narrowBand(): AmrCodec = AmrCodec(
            id = VoiceCodec.ID_AMR_NB,
            sampleRate = 8000,
            mime = MediaFormat.MIMETYPE_AUDIO_AMR_NB,
            bitRate = 12200
        )

        fun wideBand(): AmrCodec = AmrCodec(
            id = VoiceCodec.ID_AMR_WB,
            sampleRate = 16000,
            mime = MediaFormat.MIMETYPE_AUDIO_AMR_WB,
            bitRate = 15850
        )

        /**
         * Geniş bant kodlayıcı var mı — codec kurmadan sorulur.
         *
         * Eskiden bu sınama tam bir kodlayıcı+çözücü çifti kurup yok ediyordu;
         * ilk çağrıda toplam DÖRT MediaCodec kurulumu demekti. MediaCodecList
         * aynı cevabı hiçbir şey kurmadan veriyor.
         *
         * Bit hızını biçime koymak şart: API 24'ten itibaren doğrulanıyor ve
         * aralık dışı bir değer, çalışan bir kodlayıcıya "yok" dedirtiyor.
         */
        val wideBandSupported: Boolean by lazy {
            runCatching {
                val fmt = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AMR_WB, 16000, 1
                )
                fmt.setInteger(MediaFormat.KEY_BIT_RATE, 15850)
                MediaCodecList(MediaCodecList.REGULAR_CODECS)
                    .findEncoderForFormat(fmt) != null
            }.getOrDefault(false)
        }
    }
}
