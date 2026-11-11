package com.voyagerroc.laserdeck.ptt.modules

import android.os.SystemClock
import com.voyagerroc.laserdeck.ptt.ble.BleRadio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Duyuru panosu — eşleşmeden, bağlanmadan, etraftaki herkese.
 *
 * Telsizin geri kalanı iki eşleşmiş telefon arasında çalışır. Duyuru panosu
 * bunun tersi: BLE ilanıyla yayın yapar, menzildeki HERKES görür, kimse
 * kimseyle eşleşmez, bağlantı kurulmaz. Bir kişi yayınlar, on kişi duyar.
 *
 * ## Neden mesaj kısa
 * Bir BLE ilanı 31 bayttır. İmza, bayraklar ve radar belirteci düşüldükten
 * sonra metne kalan ~17 bayt. Cihaz genişletilmiş ilanı destekliyorsa
 * (Bluetooth 5) bu ~170 bayta çıkar. Sınır [BleRadio.maxAnnouncementBytes]
 * üzerinden okunur ve arayüzde gösterilir.
 *
 * ## Neden sıra numarası var
 * İlan saniyede on kez tekrarlanır. Sıra numarası olmasaydı aynı duyuru
 * listeye onlarca kez düşerdi.
 */
class BroadcastBoard(
    private val radio: BleRadio,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {}
) {
    companion object {
        /** Duyurular bu süre sonunda listeden düşer. */
        private const val EXPIRY_MS = 10 * 60 * 1000L
        private const val MAX_ITEMS = 40
        private const val SWEEP_MS = 15_000L
    }

    data class Announcement(
        /** Gonderen cihazin yayin kimligi. */
        val deviceId: Int,
        val seq: Int,
        val text: String,
        val rssi: Int,
        val firstSeen: Long,
        val lastSeen: Long,
        val mine: Boolean = false
    ) {
        /** Kaba yakınlık: sinyal gücünden. Kesin mesafe değil, sıralama için. */
        val nearness: Int get() = rssi
    }

    private val _items = MutableStateFlow<List<Announcement>>(emptyList())
    val items: StateFlow<List<Announcement>> = _items

    private val _publishing = MutableStateFlow<String?>(null)
    val publishing: StateFlow<String?> = _publishing

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening

    private val lock = Any()
    private val store = LinkedHashMap<Long, Announcement>()

    /** Metnin kaç bayta sığdığı — arayüz sayaç gösterir. */
    val maxBytes: Int get() = radio.maxAnnouncementBytes

    init {
        radio.onAnnouncement = { id, seq, text, rssi -> onReceived(id, seq, text, rssi) }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(SWEEP_MS)
                sweep()
            }
        }
    }

    /** Yayına başla. Boş metin yayını durdurur. */
    fun publish(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            stop()
            return
        }
        radio.setAnnouncement(clean)
        radio.setScanning(BleRadio.Owner.BOARD, true)
        _publishing.value = clean
        log("Duyuru yayında: $clean")
    }

    fun stop() {
        radio.setAnnouncement(null)
        // Tarama istegini de birak: birakilmayinca en yuksek guc tuketen BLE
        // kipi servisin omru boyunca acik kaliyordu.
        radio.setScanning(BleRadio.Owner.BOARD, false)
        _publishing.value = null
        log("Duyuru yayını durdu")
    }

    /** Yalnızca dinle — yayın yapmadan etrafı duy. */
    fun listen(enabled: Boolean) {
        radio.setScanning(BleRadio.Owner.BOARD, enabled)
        _listening.value = enabled
    }

    fun clear() {
        synchronized(lock) { store.clear() }
        publishList()
    }

    private fun onReceived(deviceId: Int, seq: Int, text: String, rssi: Int) {
        // Kendi yayınımızı duyamayız (cihaz kendi ilanını almaz), yine de
        // aynı metni ayırt etmek için işaretliyoruz.
        val mine = _publishing.value == text
        // Anahtar CIHAZ KIMLIGINI icermeli: eslesmemis yayincilarin hepsinde
        // belirtec yok ve anahtar yalnizca 8 bitlik siraya dusuyordu - iki
        // farkli kisinin ilk duyurusu ayni goze dusup biri gizleniyordu.
        val key = (deviceId.toLong() shl 8) or seq.toLong()
        val now = SystemClock.elapsedRealtime()

        synchronized(lock) {
            val existing = store[key]
            store[key] = if (existing == null) {
                Announcement(deviceId, seq, text, rssi, now, now, mine)
            } else {
                // Tekrarlanan ilan: yalnızca son görülme ve sinyal tazelenir.
                existing.copy(rssi = rssi, lastSeen = now)
            }
            while (store.size > MAX_ITEMS) {
                val oldest = store.keys.firstOrNull() ?: break
                store.remove(oldest)
            }
        }
        publishList()
    }

    private fun sweep() {
        val now = SystemClock.elapsedRealtime()
        val changed = synchronized(lock) {
            val stale = store.filterValues { now - it.lastSeen > EXPIRY_MS }.keys
            stale.forEach { store.remove(it) }
            stale.isNotEmpty()
        }
        if (changed) publishList()
    }

    private fun publishList() {
        _items.value = synchronized(lock) {
            store.values.sortedByDescending { it.lastSeen }
        }
    }

    fun release() {
        radio.onAnnouncement = null
        stop()
    }
}
