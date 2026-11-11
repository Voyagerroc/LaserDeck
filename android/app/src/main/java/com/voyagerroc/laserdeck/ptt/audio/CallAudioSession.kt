package com.voyagerroc.laserdeck.ptt.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Çift yönlü (full-duplex) çağrı ses oturumu — bas-konuştan yapıca farklı.
 *
 * Bas-konuşta tek yön açıktır, bu yüzden hoparlörden çıkan sesin mikrofona
 * kaçması sorun değildi. Çağrıda iki yön aynı anda açık: hoparlör sesi
 * mikrofona girer ve karşı taraf KENDİ SESİNİ duyar; iki taraf da hoparlördeyse
 * uğultuya döner. Bunu çözen şey donanım yankı gidericisidir (AEC) ve o da
 * yalnızca telefonun "konuşma" ses yolunda çalışır:
 *
 *   - kayıt kaynağı  = VOICE_COMMUNICATION
 *   - çalma kullanımı = USAGE_VOICE_COMMUNICATION
 *   - cihaz kipi      = MODE_IN_COMMUNICATION
 *
 * Üçü birden kurulunca aynı zincir gürültü bastırmayı (NS) ve otomatik kazanç
 * ayarını (AGC) da açar — arka plan uğultusu, klima, trafik sesi bu katmanda
 * bastırılır. [effectsReport] hangilerinin gerçekten kurulabildiğini söyler;
 * bunlar donanıma bağlıdır ve her cihazda bulunmaz.
 *
 * Gelen ses doğrudan çalınmaz, önce sıçrama (jitter) kuyruğuna girer: RFCOMM
 * gecikmesi düzensizdir ve çözme işi taşıyıcının okuma iş parçacığında
 * yapılırsa hat tıkanır.
 */
class CallAudioSession(
    context: Context,
    private val codec: VoiceCodec,
    private val onEncoded: (ByteArray) -> Unit,
    private val onLevels: (mic: Float, remote: Float) -> Unit = { _, _ -> },
    /** Ses yolu kurtarilamaz bicimde bozulursa cagri sessizce devam etmesin. */
    private val onFatal: (String) -> Unit = {}
) {
    companion object {
        /** ~600 ms tavan. Dolarsa en eski çerçeve düşer: gecikme birikmesin. */
        private const val JITTER_CAPACITY = 30

        /** Seviye göstergesi 20 ms'de bir değil, ~100 ms'de bir güncellenir. */
        private const val LEVEL_EVERY = 5

        /** Üst üste bu kadar hata gelirse ses yolu gerçekten bozulmuştur. */
        private const val MAX_FAULTS = 25

        /**
         * Hedef kuyruk derinliği (çerçeve). Bunun üzerine çıkan birikim,
         * sessizlik anlarında boşaltılır.
         *
         * Neden gerekli: kuyruk yalnızca 600 ms'de taşıp çerçeve düşürüyordu,
         * yani bir takılmadan sonra oluşan gecikme çağrının SONUNA KADAR
         * kalıyordu. Gecikme hep artıyor, hiç azalmıyordu — kullanıcılar bunu
         * "konuştukça kötüleşiyor" diye tarif eder.
         */
        private const val TARGET_FRAMES = 5

        /** Bu seviyenin altındaki çerçeve "sessiz" sayılır ve atılabilir. */
        private const val QUIET_LEVEL = 0.02f
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val frameBytes = codec.frameSamples * 2

    private val running = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    private val jitter = LinkedBlockingQueue<ByteArray>(JITTER_CAPACITY)

    private var focusRequest: AudioFocusRequest? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false

    @Volatile var muted = false
    @Volatile private var plcActive = false
    @Volatile private var micLevel = 0f
    @Volatile private var remoteLevel = 0f

    /** Hangi ses işleme birimleri gerçekten kurulabildi — arayüzde gösterilir. */
    @Volatile var effectsReport: Effects = Effects()
        private set

    /** Mikrofonun hangi kaynakla açıldığı — saha teşhisi için loglanır. */
    @Volatile var sourceName: String = "?"
        private set

    private var voiceSource = false

    data class Effects(
        val echoCancel: Boolean = false,
        val noiseSuppress: Boolean = false,
        val autoGain: Boolean = false
    )

    /** Yankı gidericisi yoksa hoparlör açmak uğultu riski taşır. */
    val echoCancelActive: Boolean get() = effectsReport.echoCancel

    // ---------- yaşam döngüsü ----------

    @SuppressLint("MissingPermission") // RECORD_AUDIO Activity'de istenir
    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            enterCommunicationMode()
            openRecorder()
            openTrack()
            startThreads()
        } catch (t: Throwable) {
            running.set(false)
            stop()
            throw t
        }
    }

    fun stop() {
        running.set(false)
        captureThread?.let { runCatching { it.join(1000) } }
        captureThread = null
        playbackThread?.let { runCatching { it.join(1000) } }
        playbackThread = null

        listOf(aec, ns, agc).forEach { fx -> runCatching { fx?.release() } }
        aec = null; ns = null; agc = null

        record?.let { r -> runCatching { r.stop() }; runCatching { r.release() } }
        record = null
        track?.let { t -> runCatching { t.stop() }; runCatching { t.release() } }
        track = null

        jitter.clear()
        leaveCommunicationMode()
        runCatching { codec.release() }
    }

    // ---------- ses yolu ----------

    private fun enterCommunicationMode() {
        val am = audioManager ?: return
        previousMode = am.mode
        previousSpeaker = am.isSpeakerphoneOn

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        // Müzik/video çağrı boyunca sussun.
        // Dinleyicisiz odak istegi, odagin kaybedildigini HABER VERMIYORDU.
        // Araya giren bir telefon gorusmesi mikrofonumuzu sessizce sifir
        // dolduruyor: read() hata dondurmuyor, sadece sessizlik geliyor.
        focusRequest = AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                        onFatal("ses odagi kaybedildi (telefon gorusmesi olabilir)")
                }
            }
            .build()
            .also { runCatching { am.requestAudioFocus(it) } }

        // Yankı gidericinin referans aldığı yol budur; AEC bu kip olmadan çalışmaz.
        runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
    }

    private fun leaveCommunicationMode() {
        val am = audioManager ?: return
        focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        focusRequest = null
        // Her adım ayrı: biri atarsa diğerleri yine de geri yüklenmeli. Kip en
        // kritiği — geri alınmazsa süreç boyunca TÜM sesler kulaklıktan çıkar.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
        }
        runCatching {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = previousSpeaker
        }
        runCatching { am.mode = previousMode }
    }

    /** Kulaklık <-> hoparlör. Konuşma kipi korunur ki AEC devrede kalsın. */
    fun setSpeakerphone(on: Boolean) {
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val want = if (on) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                } else {
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
                val device = am.availableCommunicationDevices.firstOrNull { it.type == want }
                if (device != null) am.setCommunicationDevice(device) else am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = on
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder() {
        val minBuf = AudioRecord.getMinBufferSize(
            codec.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        // VOICE_COMMUNICATION şart: AEC/NS/AGC yalnızca bu kaynakta bağlanır.
        // Açılamazsa çağrı yine kurulur ama yankı işlemesi olmaz.
        var rec = tryRecorder(MediaRecorder.AudioSource.VOICE_COMMUNICATION, minBuf)
        voiceSource = rec != null
        if (rec == null) rec = tryRecorder(MediaRecorder.AudioSource.MIC, minBuf)
        if (rec == null) {
            throw IllegalStateException("mikrofon açılamadı (başka uygulama kullanıyor olabilir)")
        }
        sourceName = if (voiceSource) "VOICE_COMMUNICATION" else "MIC"
        record = rec
        attachEffects(rec.audioSessionId)
        rec.startRecording()
    }

    @SuppressLint("MissingPermission")
    private fun tryRecorder(source: Int, minBuf: Int): AudioRecord? = runCatching {
        val r = AudioRecord(
            source,
            codec.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, frameBytes * 8)
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release(); null
        } else r
    }.getOrNull()

    /** Etkiler kayıt oturumuna bağlanır; hepsi donanım desteğine tabidir. */
    private fun attachEffects(sessionId: Int) {
        val echo = if (AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true } }
                .getOrNull().also { aec = it } != null
        } else false

        val noise = if (NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(sessionId)?.also { it.enabled = true } }
                .getOrNull().also { ns = it } != null
        } else false

        val gain = if (AutomaticGainControl.isAvailable()) {
            runCatching { AutomaticGainControl.create(sessionId)?.also { it.enabled = true } }
                .getOrNull().also { agc = it } != null
        } else false

        // AcousticEchoCanceler.create() sade MIC oturumunda da bir nesne döndürür,
        // ama platformun yankı zinciri yalnızca VOICE_COMMUNICATION yolunda
        // devreye girer. Bunu "açık" saymak arayüze yalan söyletirdi.
        effectsReport = Effects(
            echoCancel = echo && voiceSource,
            noiseSuppress = noise,
            autoGain = gain
        )
    }

    private fun openTrack() {
        val minBuf = AudioTrack.getMinBufferSize(
            codec.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Çağrıda ses kulaklıktan çıkmalı ve ses tuşu "görüşme"
                    // seviyesini ayarlamalı — bas-konuşun MEDIA yolu burada yanlış
                    // olurdu, üstelik AEC'yi de devre dışı bırakırdı.
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
            // Sıçrama kuyruğunun ARDINDA duran bu tampon da doğrudan gecikmeye
            // yazılıyor: 200 ms'lik sabit bir tampon, önündeki tüm ayarlamayı
            // anlamsız kılıyordu. Kayıp çerçeve gizleme eklendiği için artık
            // ~60 ms güvenli — boşluk oluşursa sessizlik değil, gizleme çalınır.
            .setBufferSizeInBytes(maxOf(minBuf, frameBytes * 3))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // Once ata, sonra play(): play() atarsa stop() serbest birakabilsin.
        track = t
        t.play()
    }

    // ---------- iş parçacıkları ----------

    private fun startThreads() {
        val rec = record ?: return
        captureThread = Thread({
            val pcm = ShortArray(codec.frameSamples)
            var tick = 0
            var faults = 0
            var micDied = false
            while (running.get()) {
                // try/catch DÖNGÜNÜN İÇİNDE: tek bir geçici codec istisnası
                // çağrının kalanında mikrofonu sessizce öldürüyordu.
                try {
                    var off = 0
                    while (off < pcm.size && running.get()) {
                        val n = rec.read(pcm, off, pcm.size - off)
                        if (n <= 0) break
                        off += n
                    }
                    if (off != pcm.size) continue
                    if (muted) {
                        // Sessize alınca akış kesilmez, susturulur: karşı taraftaki
                        // çalma tamponu boşalıp cızırtı yapmasın.
                        java.util.Arrays.fill(pcm, 0)
                    }
                    micLevel = if (muted) 0f else rms(pcm)
                    codec.encode(pcm)?.let(onEncoded)
                    if (++tick % LEVEL_EVERY == 0) onLevels(micLevel, remoteLevel)
                    faults = 0
                } catch (_: Throwable) {
                    if (++faults > MAX_FAULTS) { micDied = true; break }
                }
            }
            if (micDied && running.get()) onFatal("mikrofon yolu durdu")
        }, "call-capture").also { it.start() }

        val plc = PacketLossConcealer(codec.sampleRate, codec.frameSamples)
        playbackThread = Thread({
            var faults = 0
            var died = false
            while (running.get()) {
                try {
                    val data = jitter.poll(60, TimeUnit.MILLISECONDS)
                    if (data == null) {
                        // Bosluk: sessizlik yerine gizleme cal. Sessizlik hem
                        // tik sesi uretiyor hem de "hat oldu" hissi veriyordu.
                        if (remoteLevel > 0f || plcActive) {
                            plcActive = true
                            val filler = plc.conceal()
                            remoteLevel = rms(filler) * 0.5f
                            track?.write(filler, 0, filler.size)
                        }
                        continue
                    }
                    plcActive = false
                    val pcm = codec.decode(data) ?: continue
                    remoteLevel = rms(pcm)

                    // Gecikme birikmesini sessizlikte bosalt: kuyruk hedefin
                    // uzerindeyse ve bu cerceve zaten sessizse, calmadan at.
                    // 20 ms oda tonunun eksikligi duyulmaz; birikmis gecikme
                    // duyulur.
                    if (jitter.size > TARGET_FRAMES && remoteLevel < QUIET_LEVEL) {
                        plc.onFrame(pcm)
                        continue
                    }

                    plc.onFrame(pcm)
                    track?.write(pcm, 0, pcm.size)
                    faults = 0
                } catch (_: Throwable) {
                    if (++faults > MAX_FAULTS) { died = true; break }
                }
            }
            // Sessizce olmek yerine haber ver: eskiden cagri "acik" gorunup
            // sesi hic gelmiyordu.
            if (died && running.get()) onFatal("ses calma yolu durdu")
        }, "call-playback").also { it.start() }
    }

    /**
     * Taşıyıcının okuma iş parçacığından çağrılır — ASLA bloklamamalı.
     * Kuyruk doluysa en eski çerçeve düşer: gecikme birikeceğine ses delinsin.
     */
    fun enqueue(encoded: ByteArray) {
        if (!running.get()) return
        if (!jitter.offer(encoded)) {
            jitter.poll()
            jitter.offer(encoded)
        }
    }

    private fun rms(pcm: ShortArray): Float {
        if (pcm.isEmpty()) return 0f
        var sum = 0.0
        for (s in pcm) {
            val v = s.toDouble()
            sum += v * v
        }
        val rms = Math.sqrt(sum / pcm.size)
        // 32768 tam ölçek; konuşma tipik olarak çok altında kalır, bu yüzden
        // gösterge için 8000'e göre normalize edilip kırpılıyor.
        return (rms / 8000.0).coerceIn(0.0, 1.0).toFloat()
    }
}
