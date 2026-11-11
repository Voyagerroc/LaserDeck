package com.voyagerroc.laserdeck.ptt.transport

import com.voyagerroc.laserdeck.ptt.protocol.LinkFrame
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Taşıyıcı soyutlaması. Ses motoru ve PTT mantığı yalnızca bu arayüzü görür;
 * altta RFCOMM soketi mi, BLE L2CAP mi, yoksa USB seri üzerinden LaserDeck
 * optik dongle'ı mı olduğunu bilmez.
 */
interface FrameTransport {

    val state: StateFlow<TransportState>

    /** Alınan, CRC'si doğrulanmış çerçeveler. */
    val incoming: SharedFlow<LinkFrame>

    /** Gelen bağlantıyı bekle (sunucu rolü). */
    fun startServer()

    /** Kuyruğa ekle; bağlı değilse sessizce düşer. Bloklamaz. */
    fun send(frame: LinkFrame)

    /**
     * Yer açılana kadar bekleyerek gönderir; gönderilemezse false döner.
     *
     * [send] kuyruk doluyken çerçeveyi DÜŞÜRÜR. Seste bu doğru davranış —
     * gecikmiş ses zaten değersiz. Dosya aktarımında ise düşen tek bir parça
     * bozuk dosya demek. Bu yüzden toplu veri bu yolu kullanır ve doğal olarak
     * hattın hızına yavaşlar.
     */
    fun sendBlocking(frame: LinkFrame, timeoutMs: Long): Boolean

    fun close()
}

sealed class TransportState {
    data object Idle : TransportState()
    data object Listening : TransportState()
    data class Connecting(val peer: String) : TransportState()

    /**
     * [peer] kullanıcıya gösterilen ad, [address] ise sohbet geçmişinin
     * saklandığı kalıcı anahtar: cihaz adı değişebilir, MAC adresi değişmez.
     */
    data class Connected(val peer: String, val address: String) : TransportState()
    data class Error(val message: String) : TransportState()
}
