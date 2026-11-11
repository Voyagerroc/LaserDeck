package com.voyagerroc.laserdeck.ptt.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Çağrı sesleri.
 *
 *  - Gelen çağrı: telefonun KENDİ zil sesi çalar, titreşimle birlikte. Kendi
 *    ses dosyamızı koymuyoruz; kullanıcı zilini zaten seçmiş ve sessiz/titreşim
 *    kipini de biz değil sistem yönetmeli.
 *  - Giden çağrı: kulaklıktan standart "çalıyor" tonu (ToneGenerator). Karşı
 *    taraf zilinin çaldığını duyduğun ton budur.
 */
class Ringer(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    // Alanlar arayuz, IO toplayicisi ve zaman asimi coroutine'inden ellenir.
    @Volatile private var ringtone: Ringtone? = null
    @Volatile private var tone: ToneGenerator? = null
    @Volatile private var vibrator: Vibrator? = null
    @Volatile private var looping = false
    private val handler = Handler(Looper.getMainLooper())

    /** Gelen çağrı: zil + titreşim, telefonun sessiz kipine saygı duyarak. */
    fun startIncoming() {
        stop()
        val mode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            runCatching {
                val uri = RingtoneManager.getActualDefaultRingtoneUri(
                    context, RingtoneManager.TYPE_RINGTONE
                ) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                    play()
                }
                // isLooping API 28'de geldi; minSdk 26. Eski surumlerde zil bir kez
                // calip susuyordu (telefon 40 saniye sessizce titriyordu).
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    looping = true
                    scheduleRingtoneRestart()
                }
            }
        }

        if (mode != AudioManager.RINGER_MODE_SILENT) {
            runCatching {
                vibrator = resolveVibrator()?.also { v ->
                    // bekle-titre-bekle, sürekli tekrar (0. adımdan)
                    val pattern = longArrayOf(0, 700, 900)
                    v.vibrate(VibrationEffect.createWaveform(pattern, 0))
                }
            }
        }
    }

    /**
     * Giden çağrı: karşı taraf çalarken duyulan ton.
     *
     * STREAM_MUSIC kullanılıyor: çağrı henüz kurulmadığı için cihaz hâlâ
     * MODE_NORMAL'de ve birçok telefonda STREAM_VOICE_CALL bu durumda ya
     * kulaklığa gider ya da hiç duyulmaz.
     */
    fun startOutgoing() {
        stop()
        runCatching {
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 60).also {
                it.startTone(ToneGenerator.TONE_SUP_RINGTONE)
            }
        }
    }

    /** Karşı taraf meşgul ya da reddetti. */
    fun playBusy() {
        stop()
        runCatching {
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 60).also {
                it.startTone(ToneGenerator.TONE_SUP_BUSY, 1500)
            }
        }
    }

    fun stop() {
        looping = false
        handler.removeCallbacksAndMessages(null)
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { tone?.stopTone() }
        runCatching { tone?.release() }
        tone = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    /** API 26-27: zil bittiyse yeniden başlat. */
    private fun scheduleRingtoneRestart() {
        handler.postDelayed({
            if (!looping) return@postDelayed
            runCatching { if (ringtone?.isPlaying == false) ringtone?.play() }
            scheduleRingtoneRestart()
        }, 1000)
    }

    private fun resolveVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
}
