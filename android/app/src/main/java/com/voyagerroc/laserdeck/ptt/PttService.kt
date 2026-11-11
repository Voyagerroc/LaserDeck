package com.voyagerroc.laserdeck.ptt

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.voyagerroc.laserdeck.ptt.chat.ChatMessage
import com.voyagerroc.laserdeck.ptt.chat.MessageStore
import com.voyagerroc.laserdeck.ptt.call.CallController
import com.voyagerroc.laserdeck.ptt.call.CallState
import com.voyagerroc.laserdeck.ptt.ble.BleRadio
import com.voyagerroc.laserdeck.ptt.chat.Messenger
import com.voyagerroc.laserdeck.ptt.modules.BroadcastBoard
import com.voyagerroc.laserdeck.ptt.modules.CarrierNetwork
import com.voyagerroc.laserdeck.ptt.modules.GpsTape
import com.voyagerroc.laserdeck.ptt.modules.ProximityVault
import com.voyagerroc.laserdeck.ptt.modules.SyncShow
import com.voyagerroc.laserdeck.ptt.sync.ClockSync
import com.voyagerroc.laserdeck.ptt.files.FileTransferManager
import com.voyagerroc.laserdeck.ptt.ranging.RangingCoordinator
import com.voyagerroc.laserdeck.ptt.transport.BluetoothRfcommTransport
import com.voyagerroc.laserdeck.ptt.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Ön plan (foreground) telsiz servisi.
 *
 * Bağlantı, PTT durum makinesi ve loglar burada yaşar; MainActivity yalnızca
 * bağlanıp (bind) kumanda eder. Böylece ekran kapansa ya da uygulama arka
 * plana geçse de hat açık kalır, mikrofon FGS tipi sayesinde konuşma sürer.
 *
 * Yaşam döngüsü:
 *  - Activity her zaman bind olur (BIND_AUTO_CREATE).
 *  - Kullanıcı Bekle/Bağlan deyince Activity startForegroundService + listen()/connectTo().
 *  - Bildirimdeki "Kapat" ya da ekrandaki "Kes" -> shutdown(): bağlantı kapanır,
 *    ön plan bildirimi kalkar; servis bind sürdüğü sürece boşta bekler.
 */
class PttService : Service() {

    companion object {
        private const val CHANNEL_ID = "ptt_link"
        private const val CHANNEL_MSG_ID = "ptt_mesaj"
        private const val CHANNEL_CALL_ID = "ptt_cagri"

        /** Kasanin acilabilmesi icin gereken en fazla mesafe (m). */
        private const val VAULT_RANGE_M = 5f
        private const val NOTIF_ID = 1
        private const val NOTIF_MSG_ID = 2
        const val ACTION_START = "com.voyagerroc.laserdeck.ptt.action.START"
        const val ACTION_STOP = "com.voyagerroc.laserdeck.ptt.action.STOP"
        const val ACTION_ANSWER = "com.voyagerroc.laserdeck.ptt.action.ANSWER"
        const val ACTION_HANGUP = "com.voyagerroc.laserdeck.ptt.action.HANGUP"
        const val EXTRA_OPEN_CHAT = "com.voyagerroc.laserdeck.ptt.extra.OPEN_CHAT"
        const val EXTRA_OPEN_CALL = "com.voyagerroc.laserdeck.ptt.extra.OPEN_CALL"

        fun stateText(s: TransportState): String = when (s) {
            is TransportState.Idle -> "BOŞTA"
            is TransportState.Listening -> "BEKLENİYOR"
            is TransportState.Connecting -> "BAĞLANIYOR · ${s.peer}"
            is TransportState.Connected -> "BAĞLI · ${s.peer}"
            is TransportState.Error -> s.message
        }
    }

    inner class LocalBinder : Binder() {
        val service: PttService get() = this@PttService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var foreground = false

    var transport: BluetoothRfcommTransport? = null
        private set
    var controller: PttController? = null
        private set
    var messenger: Messenger? = null
        private set
    var callController: CallController? = null
        private set
    var ranging: RangingCoordinator? = null
        private set
    var files: FileTransferManager? = null
        private set

    /** BLE radyosunun tek sahibi — radar ve duyuru panosu bunu paylasir. */
    var radio: BleRadio? = null
        private set
    var clock: ClockSync? = null
        private set
    var board: BroadcastBoard? = null
        private set
    var carrier: CarrierNetwork? = null
        private set
    var show: SyncShow? = null
        private set
    var vault: ProximityVault? = null
        private set

    /** GPS serit metre - hatta bagli degil, tek telefonla calisir. */
    var tape: GpsTape? = null
        private set

    /** Gosteri sirasinda ekrani yakip sondurme sinyali. */
    private val _flash = MutableSharedFlow<Boolean>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val flash: SharedFlow<Boolean> = _flash

    /** Suren aktarimin son durumu — arayuz ilerleme seridini bundan cizer. */
    private val _transfers = MutableSharedFlow<FileTransferManager.Transfer>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val transfers: SharedFlow<FileTransferManager.Transfer> = _transfers

    /**
     * Sohbet ekranı gerçekten görünüyor mu.
     *
     * "Uygulama önde mi" yetmiyordu: kullanıcı TELSİZ sekmesinde otururken gelen
     * mesaj için hiç bildirim atılmıyordu ve tek belirti sekmedeki küçük sayıydı.
     */
    @Volatile var chatVisible = false

    /** Bekleyen (henüz okunmamış) mesaj metinleri — tek bildirimde toplanır. */
    private val unreadTexts = mutableListOf<String>()

    /** Activity yeniden bağlandığında geçmişi görebilsin diye replay'li log akışı. */
    private val _logs = MutableSharedFlow<String>(
        replay = 200,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val logs: SharedFlow<String> = _logs

    /** PTT durum değişimleri (konuşma/alım başladı-bitti) — UI yenilemesi için. */
    private val _pttEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val pttEvents: SharedFlow<Unit> = _pttEvents

    /** Gelen mesaj bildirimleri — okunmamış sayacı bunu dinler. */
    private val _incomingMessages = MutableSharedFlow<ChatMessage>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incomingMessages: SharedFlow<ChatMessage> = _incomingMessages

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Bluetooth'tan bagimsiz: adaptor yoksa da serit metre calissin.
        tape = GpsTape(context = this, scope = scope, log = { msg -> log(msg) })

        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            log("HATA: Bluetooth adaptörü bulunamadı")
            return
        }

        val t = BluetoothRfcommTransport(adapter)
        transport = t
        controller = PttController(
            transport = t,
            scope = scope,
            log = { msg -> log(msg) },
            onStateChanged = {
                _pttEvents.tryEmit(Unit)
                syncRadioSharing()
            }
        )
        messenger = Messenger(
            transport = t,
            store = MessageStore(filesDir),
            scope = scope,
            log = { msg -> log(msg) },
            onIncoming = { msg ->
                _incomingMessages.tryEmit(msg)
                if (!chatVisible) notifyMessage(msg)
            }
        )

        val r = BleRadio(adapter) { msg -> log(msg) }
        radio = r
        clock = ClockSync(transport = t, scope = scope, log = { msg -> log(msg) })

        ranging = RangingCoordinator(
            context = this,
            radio = r,
            transport = t,
            scope = scope,
            log = { msg -> log(msg) }
        )
        files = FileTransferManager(
            context = this,
            transport = t,
            scope = scope,
            log = { msg -> log(msg) },
            onProgress = { tr -> _transfers.tryEmit(tr) },
            onReceived = { tr ->
                tr.path?.let { messenger?.addPhoto(tr.name, it, outgoing = false) }
            }
        )
        board = BroadcastBoard(radio = r, scope = scope, log = { msg -> log(msg) })
        carrier = CarrierNetwork(
            transport = t, baseDir = filesDir, scope = scope, log = { msg -> log(msg) }
        )
        show = SyncShow(
            context = this,
            transport = t,
            clock = clock!!,
            scope = scope,
            log = { msg -> log(msg) },
            onFlash = { on -> _flash.tryEmit(on) }
        )
        vault = ProximityVault(
            context = this,
            transport = t,
            scope = scope,
            log = { msg -> log(msg) },
            // Yakinlik kosulu: radar olcumu menzil icinde mi. Baglanti tek
            // basina yetmez - baglі ama uzaktaki bir cihaz da olabilir.
            proximityOk = {
                val e = ranging?.estimate?.value
                e != null && e.state == com.voyagerroc.laserdeck.ptt.ranging
                    .RangeEstimate.State.LIVE && e.meters <= VAULT_RANGE_M
            }
        )
        callController = CallController(
            context = this,
            transport = t,
            scope = scope,
            log = { msg -> log(msg) },
            onStateChanged = { st -> onCallStateChanged(st) }
        )

        scope.launch {
            t.state.collect { s ->
                if (s !is TransportState.Connected) controller?.onLinkLost()
                if (foreground) refreshNotification()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                START_NOT_STICKY
            }
            ACTION_ANSWER -> {
                callController?.answer()
                START_NOT_STICKY
            }
            ACTION_HANGUP -> {
                callController?.hangUp()
                START_NOT_STICKY
            }
            else -> {
                goForeground()
                // START_STICKY olmamalı: sistem servisi null intent ile arka planda
                // yeniden başlatırsa Android 12+ startForeground'u reddedip çökertir,
                // üstelik bağlantısız bir "zombi" bildirim bırakır.
                START_NOT_STICKY
            }
        }
    }

    // ---------- Activity'nin kumanda API'si ----------

    // Soket kapatma ve iş parçacığı join'leri saniyeler sürebiliyor; ana iş parçacığında
    // yapılınca arayüz donuyordu. Hepsi IO'ya alındı.

    fun listen() {
        goForeground()
        log("Dinleniyor... Diğer telefonda 'Bağlan'a bas.")
        scope.launch { transport?.startServer() }
    }

    fun connectTo(device: BluetoothDevice) {
        goForeground()
        // Sohbeti bağlantı kurulmadan önce bağla: kullanıcı hat gelmeden de
        // yazabilsin, mesaj kuyrukta beklesin. (Yalnızca istemci rolünde
        // mümkün — BEKLE rolünde karşı tarafın kim olacağı henüz bilinmiyor.)
        scope.launch { runCatching { messenger?.bindPeer(device.address) } }
        scope.launch { transport?.connect(device) }
    }

    /** Bağlantıyı ve yeniden bağlanma döngüsünü durdurur, ön planı kapatır. */
    fun shutdown() {
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            // micType da sifirlanmali: aksi halde sonraki cagrida
            // "zaten eklenmis" sanilip mikrofon tipi hic eklenmiyor ve
            // ekran kapaninca Android 14+ mikrofonu kesiyor.
            micType = false
        }
        log("Hat kapatıldı.")
        scope.launch {
            controller?.onLinkLost()
            transport?.close()
            stopSelf()
        }
    }

    /**
     * Cagri surerken servisin on plan tipi mikrofonu da kapsamali: Android 14+
     * aksi halde ekran kapandiginda mikrofonu keser (telefonu kulaga goturunce
     * tam da bu olur).
     */
    private fun onCallStateChanged(state: CallState) {
        val busy = state is CallState.Incoming || state is CallState.Dialing ||
                state is CallState.Active
        if (busy) {
            // Kullanici tam o anda PTT'ye basili tutuyor olabilir; dugme devre
            // disi kalinca ACTION_UP hic gelmez ve mikrofon acik kalirdi.
            // Konusmayi burada, dugmeden bagimsiz olarak kapatiyoruz.
            controller?.stopTalking()
            goForeground(withMicrophone = true)
        }
        syncRadioSharing()
        if (foreground) refreshNotification()
    }

    /**
     * BLE taramasi ile ses ayni radyoyu paylasiyor: konusma sirasinda tarama
     * acik kalirsa hat bogulup ses kesiliyor. Ses varken radar duraklatilir,
     * bittikten kisa sure sonra kendiliginden geri doner.
     */
    private fun syncRadioSharing() {
        val sesVar = controller?.isTalking == true ||
                controller?.isReceiving == true ||
                callController?.isBusy == true
        ranging?.setAudioActive(sesVar)
        // Toplu veri de sesi keser: konusma varken parca gonderimi durur.
        files?.setAudioActive(sesVar)
        carrier?.setAudioActive(sesVar)
    }

    // ---------- bildirim ----------

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "PTT bağlantısı",
                NotificationManager.IMPORTANCE_LOW // sessiz, titreşimsiz kalıcı bildirim
            )
        )
        // Mesaj bildirimi ayrı kanalda: kullanıcı kalıcı hat bildirimini
        // susturmadan mesaj sesini açık tutabilsin (ya da tersi).
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MSG_ID,
                "Gelen mesaj",
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        // Cagri kanali sessiz: zili biz caliyoruz (kullanicinin kendi zil sesi,
        // sessiz/titresim kipine saygi duyarak). Kanal da ses calarsa ust uste biner.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALL_ID,
                "Cagri",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    /**
     * Tek bir bildirim kimliği kullanıldığı için arka arkaya gelen mesajlar
     * birbirinin üstüne yazılıyordu: beş mesaj gelince yalnızca beşincisi
     * görünüyordu. Okunmamışlar biriktirilip tek bildirimde listeleniyor.
     */
    @SuppressLint("MissingPermission") // POST_NOTIFICATIONS Activity'de istenir
    private fun notifyMessage(msg: ChatMessage) {
        val pending = synchronized(unreadTexts) {
            unreadTexts += msg.text
            while (unreadTexts.size > 6) unreadTexts.removeAt(0)
            unreadTexts.toList()
        }
        val peer = (transport?.state?.value as? TransportState.Connected)?.peer
            ?: getString(R.string.notif_new_message)
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .putExtra(EXTRA_OPEN_CHAT, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_MSG_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(peer)
            .setContentText(msg.text)
            .setContentIntent(open)
            .setAutoCancel(true)
        if (pending.size > 1) {
            builder.setNumber(pending.size)
            builder.setStyle(
                Notification.InboxStyle().also { style -> pending.forEach { style.addLine(it) } }
            )
        } else {
            builder.setStyle(Notification.BigTextStyle().bigText(msg.text))
        }
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_MSG_ID, builder.build())
        }
    }

    /** Kullanıcı sohbeti açtığında bekleyen mesaj bildirimi anlamsızlaşır. */
    fun clearMessageNotification() {
        synchronized(unreadTexts) { unreadTexts.clear() }
        runCatching {
            getSystemService(NotificationManager::class.java)?.cancel(NOTIF_MSG_ID)
        }
    }

    private var micType = false

    private fun goForeground(withMicrophone: Boolean = false) {
        // Zaten on plandaysak ve mikrofon tipi de eklenmisse yapacak bir sey yok.
        if (foreground && (micType || !withMicrophone)) return
        if (!foreground) micType = false
        val notif = currentNotification()
        // Android 12+ arka planda baslatmayi, 14+ ise FGS tipi on kosullarini
        // reddedebilir; bu hata uygulamayi cokertmemeli.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // connectedDevice: Bluetooth hattini canli tutar. microphone yalnizca
                // cagri sirasinda eklenir - bas-konusta mikrofon uygulama ondeyken
                // kullaniliyor, cagrida ise ekran kapaliyken de gerekiyor.
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                // Mikrofon tipi API 30'da geldi; API 29'a bilinmeyen bir bit
                // gonderirsek startForeground istisna atar.
                if (withMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(NOTIF_ID, notif, types)
                micType = micType || withMicrophone
            } else {
                startForeground(NOTIF_ID, notif)
            }
            foreground = true
        }.onFailure { log("On plan servisi baslatilamadi: ${it.message}") }
    }

    @SuppressLint("MissingPermission") // POST_NOTIFICATIONS Activity'de istenir
    private fun refreshNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIF_ID, currentNotification())
        }
    }

    /** Cagri varsa cagri bildirimi, yoksa hat bildirimi. Tek kimlik kullanilir ki
     *  bildirim golgesinde iki ayri kart durmasin. */
    private fun currentNotification(): Notification =
        buildCallNotification(callController?.state?.value ?: CallState.Idle)
            ?: buildNotification(stateText(transport?.state?.value ?: TransportState.Idle))

    /**
     * Gelen/suren cagri bildirimi. Android 12+ icin CallStyle kullanilir:
     * sistem bunu gercek bir cagri gibi en uste alir ve Yanitla/Reddet
     * dugmelerini kendi cizer.
     */
    private fun buildCallNotification(state: CallState): Notification? {
        val peer = when (state) {
            is CallState.Incoming -> state.peer
            is CallState.Dialing -> state.peer
            is CallState.Active -> state.peer
            else -> return null
        }
        val open = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .putExtra(EXTRA_OPEN_CALL, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val answer = PendingIntent.getService(
            this, 4,
            Intent(this, PttService::class.java).setAction(ACTION_ANSWER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val hangUp = PendingIntent.getService(
            this, 5,
            Intent(this, PttService::class.java).setAction(ACTION_HANGUP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val b = Notification.Builder(this, CHANNEL_CALL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_CALL)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val person = Person.Builder().setName(peer).setImportant(true).build()
            b.setStyle(
                if (state is CallState.Incoming) {
                    Notification.CallStyle.forIncomingCall(person, hangUp, answer)
                } else {
                    Notification.CallStyle.forOngoingCall(person, hangUp)
                }
            )
        } else {
            b.setContentTitle(peer)
            b.setContentText(
                getString(
                    when (state) {
                        is CallState.Incoming -> R.string.call_incoming
                        is CallState.Dialing -> R.string.call_dialing
                        else -> R.string.call_active
                    }
                )
            )
            if (state is CallState.Incoming) {
                b.addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.sym_action_call),
                        getString(R.string.call_answer), answer
                    ).build()
                )
            }
            b.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    getString(R.string.call_end), hangUp
                ).build()
            )
        }
        // Kilit ekranindaysa cagri ekranini dogrudan ac.
        if (state is CallState.Incoming) b.setFullScreenIntent(open, true)
        return b.build()
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PttService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("LaserDeck PTT")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Kapat",
                    stopIntent
                ).build()
            )
            .build()
    }

    // ---------- ortak ----------

    private fun log(msg: String) {
        _logs.tryEmit(msg)
    }

    override fun onDestroy() {
        super.onDestroy()
        controller?.release()
        callController?.release()
        ranging?.release()
        files?.release()
        board?.release()
        show?.release()
        vault?.release()
        tape?.release()
        radio?.stop()
        // scope iptal edilmeden once: sohbet gecmisinin son hali diske yazilmali.
        messenger?.release()
        transport?.close()
        scope.cancel()
    }
}
