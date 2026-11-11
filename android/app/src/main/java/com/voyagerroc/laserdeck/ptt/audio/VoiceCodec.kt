package com.voyagerroc.laserdeck.ptt.audio

/**
 * Ses codec soyutlaması. 20 ms'lik PCM çerçeveleriyle çalışır.
 *
 * Kayıtlı kimlikler (AUDIO_START payload'ında taşınır):
 *   0 = PCM passthrough (128 kbps, yalnızca hata ayıklama)
 *   1 = AMR-NB 12.2 kbps (MediaCodec, tüm Android cihazlarda yerleşik)
 *   2 = Codec2 1.3 kbps  (TODO: JNI — optik hat için hedef codec)
 *   3 = Opus 6-24 kbps   (TODO: libopus JNI — kalite hedefi)
 *   4 = AMR-WB 15.85 kbps (16 kHz "HD ses"; kodlayıcısı her cihazda yok)
 */
interface VoiceCodec {
    val id: Int
    val sampleRate: Int
    /** Bir codec çerçevesindeki PCM örnek sayısı (20 ms). */
    val frameSamples: Int

    /** Tek 20 ms PCM çerçevesini sıkıştırır; codec henüz ısınıyorsa null dönebilir. */
    fun encode(pcm: ShortArray): ByteArray?

    /** Tek sıkıştırılmış çerçeveyi PCM'e açar; bozuk veride null dönebilir. */
    fun decode(data: ByteArray): ShortArray?

    fun release()

    companion object {
        const val ID_PCM = 0
        const val ID_AMR_NB = 1
        const val ID_CODEC2 = 2
        const val ID_OPUS = 3
        const val ID_AMR_WB = 4

        /** Çağrı kurulurken karşı tarafa bildirilen destek maskesi. */
        const val MASK_AMR_NB = 1 shl 0
        const val MASK_AMR_WB = 1 shl 1

        /** Bu cihazın gerçekten kurabildiği çağrı codec'leri. */
        fun localCallMask(): Int =
            MASK_AMR_NB or (if (AmrCodec.wideBandSupported) MASK_AMR_WB else 0)

        /**
         * İki tarafın ortak desteklediği en iyi codec. Geniş bant varsa o
         * seçilir; yoksa her cihazda bulunan dar banda düşülür.
         */
        fun bestCallCodec(remoteMask: Int): Int {
            val common = localCallMask() and remoteMask
            return if (common and MASK_AMR_WB != 0) ID_AMR_WB else ID_AMR_NB
        }

        /** Alıcı tarafta AUDIO_START'taki kimliğe göre codec kurar. */
        fun forId(id: Int): VoiceCodec = when (id) {
            ID_PCM -> PcmCodec()
            ID_AMR_NB -> AmrCodec.narrowBand()
            ID_AMR_WB -> AmrCodec.wideBand()
            else -> throw IllegalArgumentException("Desteklenmeyen codec id: $id")
        }
    }
}

/** Sıkıştırmasız 8 kHz PCM — bant genişliği testi ve hata ayıklama için. */
class PcmCodec : VoiceCodec {
    override val id = VoiceCodec.ID_PCM
    override val sampleRate = 8000
    override val frameSamples = 160

    override fun encode(pcm: ShortArray): ByteArray {
        val out = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            out[i * 2] = (pcm[i].toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((pcm[i].toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    override fun decode(data: ByteArray): ShortArray {
        val out = ShortArray(data.size / 2)
        for (i in out.indices) {
            out[i] = ((data[i * 2].toInt() and 0xFF) or
                    (data[i * 2 + 1].toInt() shl 8)).toShort()
        }
        return out
    }

    override fun release() = Unit
}
