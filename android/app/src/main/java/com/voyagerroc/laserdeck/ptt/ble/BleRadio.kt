package com.voyagerroc.laserdeck.ptt.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.SystemClock
import kotlin.random.Random

/**
 * BLE radyosunun TEK sahibi.
 *
 * ## Neden tek sahip
 * Radar mesafe ölçmek için ilan yayınlıyor ve tarıyor; duyuru panosu da mesaj
 * yayınlamak için aynı şeyi istiyor. İki ayrı sınıf aynı radyoya ayrı ayrı
 * komut verseydi biri diğerinin ilanını durdurur, taramalar başlatma sınırına
 * takılır ve ikisi de güvenilmez çalışırdı.
 *
 * Bu sınıf ikisini de tek bir ilanda birleştiriyor ve tek bir tarama
 * çalıştırıyor; gelen paket içeriğine göre ilgili modüle dağıtılıyor.
 *
 * ## Yük biçimi (üretici verisi 0xFFFF içinde)
 * ```
 * [0] 'L'  imza
 * [1] bayraklar: bit0 = radar belirteci, bit1 = duyuru
 * [2..5] radar belirteci (bayrak varsa)
 * [..]  duyuru: [cihaz kimliği u16][sıra u8][UTF-8 metin]
 * ```
 * Cihaz kimliği şart: duyuru panosunun asıl kullanımı EŞLEŞMEMİŞ cihazlar
 * arasında ve orada radar belirteci yok. Kimlik olmasaydı iki farklı yayıncının
 * mesajları aynı sıra numarasına düşer, biri diğerini gizlerdi.
 *
 * ## Tarama sahipliği sayılıyor
 * Radar ve duyuru panosu taramayı ayrı ayrı istiyor. Tek bir aç/kapa bayrağı
 * olsaydı biri kapattığında diğeri de sağır kalırdı — ve pratikte kalıyordu.
 * Artık her modül kendi adına istiyor; tarama, isteyen kimse kalmayınca duruyor.
 *
 * ## Neden 0xFFFF
 * `neverForLocation` bayrağı kullanıldığında sistem, bilinen konum-işareti
 * biçimlerine uyan ilanları tarama sonuçlarından AYIKLIYOR. Apple'ın 0x004C'si
 * ve Eddystone'un 0xFEAA'sı bu yüzden kullanılamaz. 0xFFFF, Bluetooth SIG'in
 * şirket içi kullanıma ayırdığı kimlik.
 */
@SuppressLint("MissingPermission") // izinler Activity'de istenir
class BleRadio(
    private val adapter: BluetoothAdapter,
    private val log: (String) -> Unit = {}
) {
    companion object {
        private const val MANUFACTURER_ID = 0xFFFF
        private const val MAGIC = 'L'.code.toByte()

        private const val FLAG_RADAR = 0x01
        private const val FLAG_ANNOUNCE = 0x02

        /**
         * Eski ilanda üretici verisine sığan bayt sayısı.
         * 31 toplam − 2 (AD başlığı) − 2 (şirket kimliği) − 3 (gönderim gücü
         * alanı) = 24.
         */
        private const val LEGACY_BUDGET = 24

        /** Genişletilmiş ilanda tuttuğumuz üst sınır; fazlası menzili düşürür. */
        private const val EXTENDED_BUDGET = 180

        /** Başlık: imza + bayraklar + belirteç + kimlik + sıra. */
        private const val HEADER_MAX = 2 + 4 + 2 + 1

        /**
         * startScan çağrısı 30 saniyede ~5 kez sınırlı. 7 sn tam sınırda kalıyor
         * ve yeniden deneme payı bırakmıyordu.
         */
        private const val MIN_SCAN_DWELL_MS = 9_000L

        /** İlan sessizce reddedilmişse bu aralıkla yeniden denenir. */
        private const val ADVERTISE_RETRY_MS = 5_000L

        /** Tarama sonucundan gönderim gücü okunamadı. */
        const val TX_UNKNOWN = ScanResult.TX_POWER_NOT_PRESENT

        fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    /** Taramayı kim istiyor. */
    enum class Owner { RADAR, BOARD }

    private val advertiser = runCatching { adapter.bluetoothLeAdvertiser }.getOrNull()
    private val scanner = runCatching { adapter.bluetoothLeScanner }.getOrNull()

    /**
     * Genişletilmiş ilan yalnızca [startAdvertisingSet] ile mümkün. Eski
     * `startAdvertising` çağrısı BT5 destekli cihazda bile 31 baytta kalıyor —
     * bu ayrım gözden kaçınca yük sınırı 173 sanılıp her duyuru reddediliyordu.
     */
    val extendedSupported: Boolean by lazy {
        runCatching {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    adapter.isLeExtendedAdvertisingSupported &&
                    advertiser != null
        }.getOrDefault(false)
    }

    /** Reddedilen bir ilandan sonra daraltılan geçici sınır. */
    @Volatile private var budgetOverride = 0

    private val fullBudget: Int
        get() = when {
            // Radar sürerken ilan MUTLAKA eski (legacy) biçimde kalmalı ki
            // karşı telefon genişletilmiş tarama yapamıyorsa bile duysun.
            // Uzun duyuru bütçesi yalnızca radar oturumu yokken kullanılır.
            radarToken != null -> LEGACY_BUDGET
            extendedSupported -> EXTENDED_BUDGET
            else -> LEGACY_BUDGET
        }

    /** Duyuru metnine kalan bayt. */
    val maxAnnouncementBytes: Int
        get() = ((if (budgetOverride > 0) budgetOverride else fullBudget) - HEADER_MAX)
            .coerceAtLeast(4)

    @Volatile var localTxPower: Int = -7
        private set

    /** Radar örneği: gönderenin belirteci, sinyal gücü, bildirilen güç. */
    var onRadarSample: ((token: Int, rssi: Int, remoteTxPower: Int) -> Unit)? = null

    /** Duyuru: gönderen kimliği, sıra, metin, sinyal gücü. */
    var onAnnouncement: ((deviceId: Int, seq: Int, text: String, rssi: Int) -> Unit)? = null

    /** Bu cihazın kalıcı yayın kimliği — duyuruları birbirinden ayırmak için. */
    private val deviceId = Random.nextInt(1, 0xFFFF)

    @Volatile private var radarToken: Int? = null
    @Volatile private var announcement: String? = null
    @Volatile private var announceSeq = 0

    /**
     * Geri çağrının SİSTEME KAYITLI olup olmadığı — "başlatma başarılı oldu mu"
     * değil. Framework, geri çağrıyı `startAdvertising` çağrısında hemen
     * kaydediyor ve hata durumunda SİLMİYOR. Bayrağı başarıya bağlamak,
     * başarısız bir denemeden sonra durdurmayı atlatıyor ve sonraki her ilan
     * "zaten başlatılmış" hatasıyla kalıcı olarak ölüyordu.
     */
    @Volatile private var callbackRegistered = false
    @Volatile private var setRegistered = false

    @Volatile private var scanning = false
    private var lastScanStart = 0L
    private var lastAdvertiseAttempt = 0L

    private val lock = Any()
    private val scanOwners = HashSet<Owner>()

    // ---------- dışarıya açık kumanda ----------

    fun setRadarToken(token: Int?) {
        if (radarToken == token) return
        radarToken = token
        budgetOverride = 0 // bütçe radar durumuna bağlı; eski daraltma kalmasın
        refreshAdvertising()
    }

    fun setAnnouncement(text: String?) {
        synchronized(lock) {
            if (announcement == text) return
            announcement = text
            budgetOverride = 0 // yeni metin: sınırı sıfırla
            if (text != null) announceSeq = (announceSeq + 1) and 0xFF
        }
        refreshAdvertising()
    }

    fun currentAnnouncement(): String? = announcement

    /** Tarama isteği sahibi başına sayılır; isteyen kalmayınca durur. */
    fun setScanning(owner: Owner, enabled: Boolean) {
        val shouldScan = synchronized(lock) {
            if (enabled) scanOwners.add(owner) else scanOwners.remove(owner)
            scanOwners.isNotEmpty()
        }
        if (shouldScan) startScan() else stopScan()
    }

    val isScanning: Boolean get() = scanning
    val isAdvertising: Boolean get() = callbackRegistered || setRegistered

    /**
     * Sessizce reddedilmiş ilanı yeniden dener. Servisin düzenli döngüsünden
     * çağrılır: geçici bir hata (Bluetooth kapanıp açıldı, denetleyici meşgul)
     * yüzünden ilanın kalıcı olarak ölmesini engeller.
     */
    fun heartbeat() {
        if (!supported()) return
        val wantsAdvertising = radarToken != null || announcement != null
        if (!wantsAdvertising || isAdvertising) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAdvertiseAttempt < ADVERTISE_RETRY_MS) return
        refreshAdvertising()
    }

    fun stop() {
        synchronized(lock) { scanOwners.clear() }
        stopScan()
        radarToken = null
        announcement = null
        stopAdvertising()
    }

    // ---------- ilan ----------

    private fun refreshAdvertising() {
        if (!supported()) return
        // SIRA ÖNEMLİ: yeni ilanı başlatmadan önce eskisini MUTLAKA durdur.
        // Aksi halde ikinci çağrı "zaten başlatılmış" hatası alıyor ve yük
        // eski değerinde donuyordu.
        stopAdvertising()
        val payload = buildPayload() ?: return
        lastAdvertiseAttempt = SystemClock.elapsedRealtime()

        val adv = advertiser ?: run {
            log("Radyo: bu cihaz BLE ilanı yapamıyor")
            return
        }
        // Karışık cihaz tuzağı: genişletilmiş ilanı, genişletilmiş tarama
        // yapamayan (eski) telefon HİÇ duymuyor. Radar belirteci 6 bayta
        // sığdığı için, yük eski ilan sınırına sığdığı sürece HER ZAMAN eski
        // (legacy) ilan kullanılır — böylece her telefon duyar. Genişletilmiş
        // ilana yalnızca uzun duyuru metni sığmadığında çıkılır.
        if (payload.size <= LEGACY_BUDGET || !extendedSupported) {
            startLegacy(adv, payload)
        } else {
            startExtended(adv, payload)
        }
    }

    private fun startLegacy(adv: android.bluetooth.le.BluetoothLeAdvertiser, payload: ByteArray) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY) // ~100 ms
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(true)
            .setIncludeDeviceName(false)
            .addManufacturerData(MANUFACTURER_ID, payload)
            .build()
        // Kayıt, çağrının kendisiyle olur; başarıyı beklemek yanlıştı.
        callbackRegistered = true
        runCatching { adv.startAdvertising(settings, data, advertiseCallback) }
            .onFailure {
                callbackRegistered = false
                log("Radyo: ilan hatası: ${it.message}")
            }
    }

    /** Genişletilmiş ilan: duyuru metni 31 bayt sınırına sıkışmasın. */
    private fun startExtended(adv: android.bluetooth.le.BluetoothLeAdvertiser, payload: ByteArray) {
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setConnectable(false)
            .setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .setIncludeTxPower(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(MANUFACTURER_ID, payload)
            .build()
        setRegistered = true
        runCatching { adv.startAdvertisingSet(params, data, null, null, null, setCallback) }
            .onFailure {
                setRegistered = false
                log("Radyo: genişletilmiş ilan hatası: ${it.message}")
            }
    }

    private fun buildPayload(): ByteArray? = synchronized(lock) {
        val token = radarToken
        val text = announcement
        if (token == null && text == null) return null

        var flags = 0
        val body = ArrayList<Byte>(48)
        body.add(MAGIC)
        body.add(0)

        if (token != null) {
            flags = flags or FLAG_RADAR
            body.add((token and 0xFF).toByte())
            body.add(((token shr 8) and 0xFF).toByte())
            body.add(((token shr 16) and 0xFF).toByte())
            body.add(((token shr 24) and 0xFF).toByte())
        }
        if (text != null) {
            flags = flags or FLAG_ANNOUNCE
            body.add((deviceId and 0xFF).toByte())
            body.add(((deviceId shr 8) and 0xFF).toByte())
            body.add(announceSeq.toByte())
            var bytes = text.toByteArray(Charsets.UTF_8)
            val budget = maxAnnouncementBytes
            if (bytes.size > budget) {
                // UTF-8 karakterinin ortasından kesme: karakter sınırına in.
                var cut = budget
                while (cut > 0 && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
                bytes = bytes.copyOf(cut)
            }
            bytes.forEach { body.add(it) }
        }
        body[1] = flags.toByte()
        return body.toByteArray()
    }

    private fun stopAdvertising() {
        // Bayrak "kayıtlı mı" anlamına geldiği için başarısız denemeden sonra
        // da temizlik yapılır.
        if (callbackRegistered) {
            callbackRegistered = false
            runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        }
        if (setRegistered) {
            setRegistered = false
            runCatching { advertiser?.stopAdvertisingSet(setCallback) }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            settingsInEffect?.let { localTxPower = powerDbm(it.txPowerLevel) }
        }

        override fun onStartFailure(errorCode: Int) {
            callbackRegistered = false
            onAdvertiseFailed(errorCode)
        }
    }

    private val setCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            if (status == ADVERTISE_SUCCESS) {
                // Denetleyicinin BİLDİRDİĞİ güç, tablo değerinden daha güvenilir.
                localTxPower = txPower
            } else {
                setRegistered = false
                onAdvertiseFailed(status)
            }
        }

        override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
            setRegistered = false
        }
    }

    /**
     * Yük çok büyükse sınırı GERÇEKTEN daralt ve bir kez yeniden dene.
     *
     * Önceki sürüm sabit bir sınırın yarısını alıyordu — sınır değişmediği için
     * metin hiç kısalmıyor, ilan tekrar reddediliyor ve geri çağrı ana
     * döngüde sonsuza kadar kendini yeniden tetikliyordu: uygulama donuyordu.
     */
    private fun onAdvertiseFailed(errorCode: Int) {
        if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE) {
            val current = if (budgetOverride > 0) budgetOverride else fullBudget
            val next = current / 2
            if (next <= HEADER_MAX + 4) {
                log("Radyo: duyuru bu cihazda sığmıyor")
                synchronized(lock) { announcement = null }
                return
            }
            budgetOverride = next
            log("Radyo: duyuru sığmadı, sınır $next bayta indirildi")
            refreshAdvertising()
        } else {
            log("Radyo: ilan başlatılamadı ($errorCode)")
        }
    }

    // ---------- tarama ----------

    private fun startScan() {
        if (!supported() || scanning) return
        val sc = scanner ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastScanStart < MIN_SCAN_DWELL_MS) return
        lastScanStart = now

        val filter = ScanFilter.Builder()
            .setManufacturerData(
                MANUFACTURER_ID,
                byteArrayOf(MAGIC),
                byteArrayOf(0xFF.toByte())
            )
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .setLegacy(!extendedSupported)
            .build()

        runCatching {
            sc.startScan(listOf(filter), settings, scanCallback)
            scanning = true
        }.onFailure { log("Radyo: tarama hatası: ${it.message}") }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { scanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val r = result ?: return
            val data = r.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID) ?: return
            dispatch(data, r)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            log("Radyo: tarama başlatılamadı ($errorCode)")
        }
    }

    private fun dispatch(data: ByteArray, result: ScanResult) {
        if (data.size < 2 || data[0] != MAGIC) return
        val flags = data[1].toInt() and 0xFF
        var off = 2

        var token: Int? = null
        if (flags and FLAG_RADAR != 0) {
            if (data.size < off + 4) return
            token = (data[off].toInt() and 0xFF) or
                    ((data[off + 1].toInt() and 0xFF) shl 8) or
                    ((data[off + 2].toInt() and 0xFF) shl 16) or
                    ((data[off + 3].toInt() and 0xFF) shl 24)
            off += 4
        }

        // Gönderim gücü iki ayrı yerde yaşar: genişletilmiş ilanda PDU alanında
        // (ScanResult.txPower), ESKİ ilanda ise AD yapısında (scanRecord). Radar
        // sürerken ilan her zaman eski biçimde, yani ilk alan hep "yok" dönüyor
        // ve -7 varsayılıyordu; karşı taraf gerçekte +1 dBm ile yayın yapıyorken
        // referans 4 dB kayıyor, mesafe ~1,45 kat KISA çıkıyordu.
        val tx = when {
            result.txPower != ScanResult.TX_POWER_NOT_PRESENT -> result.txPower
            else -> result.scanRecord?.txPowerLevel?.takeIf { it != Int.MIN_VALUE } ?: TX_UNKNOWN
        }

        // Belirteç DOĞRULANMAK ÜZERE yukarı veriliyor. Burada kabul etmek,
        // odadaki başka bir çiftin sinyalini bizim radarımıza karıştırırdı —
        // ve yakınlık kasasının mesafe kilidini sahte bir vericiyle
        // açılabilir hale getirirdi.
        if (token != null) onRadarSample?.invoke(token, result.rssi, tx)

        if (flags and FLAG_ANNOUNCE != 0 && data.size > off + 2) {
            val id = (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
            val seq = data[off + 2].toInt() and 0xFF
            off += 3
            if (data.size <= off) return
            val text = runCatching {
                String(data, off, data.size - off, Charsets.UTF_8)
            }.getOrNull() ?: return
            if (text.isNotBlank()) onAnnouncement?.invoke(id, seq, text, result.rssi)
        }
    }

    private fun powerDbm(level: Int): Int = when (level) {
        AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW -> -21
        AdvertiseSettings.ADVERTISE_TX_POWER_LOW -> -15
        AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM -> -7
        else -> 1
    }
}
