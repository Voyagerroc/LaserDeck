package com.voyagerroc.laserdeck.ptt.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mikrofon yakalama (TX) ve hoparlör çalma (RX) motoru.
 *
 * TX: AudioRecord -> 20 ms PCM çerçeveleri -> codec.encode -> onEncoded geri çağrısı.
 * RX: playEncoded(codec çerçevesi) -> codec.decode -> AudioTrack (akış modu).
 *
 * Jitter tamponu olarak AudioTrack'in kendi dahili tamponu kullanılır;
 * tampon 8 çerçeve (~160 ms) olarak boyutlandırılır. Yol haritası: uyarlanabilir
 * jitter tamponu + kayıp çerçeve gizleme (PLC).
 */
class AudioEngine(private val codec: VoiceCodec) {

    private val frameBytes = codec.frameSamples * 2
    private val capturing = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null

    // ---------- TX: mikrofon yakalama ----------

    /** RECORD_AUDIO izni verilmiş olmalı (Activity denetler). */
    @SuppressLint("MissingPermission")
    fun startCapture(onEncoded: (ByteArray) -> Unit) {
        if (!capturing.compareAndSet(false, true)) return

        val minBuf = AudioRecord.getMinBufferSize(
            codec.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // VOICE_COMMUNICATION her cihazda 8 kHz'i desteklemeyebilir; olmazsa MIC'e düş.
        val rec = openRecorder(MediaRecorder.AudioSource.VOICE_COMMUNICATION, minBuf)
            ?: openRecorder(MediaRecorder.AudioSource.MIC, minBuf)
            ?: run {
                capturing.set(false)
                throw IllegalStateException("mikrofon açılamadı (başka uygulama kullanıyor olabilir)")
            }
        record = rec
        rec.startRecording()

        captureThread = Thread({
            // Bu thread'de yakalanmayan bir istisna süreci öldürür; sarmala.
            try {
                val pcm = ShortArray(codec.frameSamples)
                while (capturing.get()) {
                    var off = 0
                    while (off < pcm.size && capturing.get()) {
                        val n = rec.read(pcm, off, pcm.size - off)
                        if (n <= 0) break
                        off += n
                    }
                    if (off == pcm.size) {
                        codec.encode(pcm)?.let(onEncoded)
                    }
                }
            } catch (_: Throwable) {
            }
        }, "audio-capture").also { it.start() }
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(source: Int, minBuf: Int): AudioRecord? = runCatching {
        val r = AudioRecord(
            source,
            codec.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, frameBytes * 8)
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            null
        } else r
    }.getOrNull()

    fun stopCapture() {
        capturing.set(false)
        // Zaman aşımlı join, hâlâ koşan thread'i serbest bırakılmış AudioRecord'a
        // sokup çökertiyordu. Döngü bayrağa bakıyor, en fazla bir 20 ms okuma sürer.
        captureThread?.join()
        captureThread = null
        record?.let { r ->
            runCatching { r.stop() }
            r.release()
        }
        record = null
    }

    // ---------- RX: hoparlör çalma ----------

    fun startPlayback() {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            codec.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // USAGE_MEDIA: hoparlörden, medya ses seviyesinde çalar.
                    // VOICE_COMMUNICATION kullanınca cihaz MODE_NORMAL'de kaldığı
                    // için ses KULAKLIKTAN çıkıyor ve ses tuşu onu kontrol etmiyor;
                    // telsizde konuşma hoparlörden duyulmalı. Hat yarı çift yönlü
                    // olduğu için geri besleme riski yok.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(codec.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            // ~500 ms tampon: RFCOMM gecikmesi düzensiz, 160 ms'lik tamponda
            // sürekli underrun oluyor ve ses kesik kesik çıkıyordu.
            .setBufferSizeInBytes(maxOf(minBuf, frameBytes * 25))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    /** Transport'un dinleyici iş parçacığından çağrılır; decode + yaz bloklayabilir. */
    fun playEncoded(data: ByteArray) {
        val t = track ?: return
        val pcm = codec.decode(data) ?: return
        t.write(pcm, 0, pcm.size)
    }

    fun stopPlayback() {
        track?.let { t ->
            runCatching { t.stop() }
            t.release()
        }
        track = null
    }

    fun release() {
        stopCapture()
        stopPlayback()
        codec.release()
    }
}
