package com.voyagerroc.laserdeck.ptt

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.voyagerroc.laserdeck.ptt.call.CallState
import com.voyagerroc.laserdeck.ptt.chat.Messenger
import com.voyagerroc.laserdeck.ptt.files.FileTransferManager
import com.voyagerroc.laserdeck.ptt.modules.GpsTape
import com.voyagerroc.laserdeck.ptt.modules.ProximityVault
import com.voyagerroc.laserdeck.ptt.modules.SyncShow
import com.voyagerroc.laserdeck.ptt.transport.TransportState
import com.voyagerroc.laserdeck.ptt.audio.AmrCodec
import com.voyagerroc.laserdeck.ptt.ranging.FAIL_NO_SIGNAL
import com.voyagerroc.laserdeck.ptt.ranging.FAIL_TOO_FEW
import com.voyagerroc.laserdeck.ptt.ranging.Measurement
import com.voyagerroc.laserdeck.ptt.ranging.RangeEstimate
import com.voyagerroc.laserdeck.ptt.ui.BootSequence
import com.voyagerroc.laserdeck.ptt.ui.RadarScope
import com.voyagerroc.laserdeck.ptt.ui.LevelMeter
import com.voyagerroc.laserdeck.ptt.ui.MessageAdapter
import com.voyagerroc.laserdeck.ptt.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Tek ekran, iki kanal: TELSİZ (bağlantı + bas-konuş) ve MESAJ (yazışma).
 *
 * Bağlantı, PTT ve mesaj mantığı [PttService] içinde yaşar; bu ekran servise
 * bind olup kumanda eder. Ekran kapansa da hat açık kalır, kopan bağlantı
 * servis tarafında otomatik yeniden kurulur, mesajlar kuyrukta bekler.
 *
 * Ön koşul: iki telefon önce Android Ayarlar > Bluetooth'tan eşleştirilmelidir.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMISSIONS = 1
        private const val REQ_PICK_PHOTO = 2
        private const val REQ_PICK_VAULT = 3
        private const val REQ_PICK_AUDIO = 4
        private const val REQ_LOCATION = 5
        private const val LOG_MAX_CHARS = 8000
    }

    private enum class Tab { RADIO, CHAT, MODULES }

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val serviceJobs = mutableListOf<Job>()

    private lateinit var txtStatus: TextView
    private lateinit var viewLinkRule: View
    private lateinit var tabRadio: LinearLayout
    private lateinit var tabChat: LinearLayout
    private lateinit var txtTabRadio: TextView
    private lateinit var txtTabChat: TextView
    private lateinit var undRadio: View
    private lateinit var undChat: View
    private lateinit var paneRadio: View
    private lateinit var paneChat: View

    private lateinit var txtLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var spinnerDevices: Spinner
    private lateinit var btnListen: Button
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnPtt: Button

    private lateinit var btnCall: Button
    private lateinit var paneCall: View
    private lateinit var txtCallLabel: TextView
    private lateinit var txtCallPeer: TextView
    private lateinit var txtCallTimer: TextView
    private lateinit var txtCallInfo: TextView
    private lateinit var rowMeters: View
    private lateinit var rowToggles: View
    private lateinit var txtMeterPeer: TextView
    private lateinit var meterMic: LevelMeter
    private lateinit var meterRemote: LevelMeter
    private lateinit var btnMute: Button
    private lateinit var btnSpeaker: Button
    private lateinit var btnHangUp: Button
    private lateinit var btnAnswer: Button

    private var callTimerJob: Job? = null

    private lateinit var tabModules: LinearLayout
    private lateinit var txtTabModules: TextView
    private lateinit var undModules: View
    private lateinit var paneModules: View
    private lateinit var flashOverlay: View

    private lateinit var editBoard: EditText
    private lateinit var btnBoardPublish: Button
    private lateinit var txtBoardOut: TextView
    private lateinit var editCarrier: EditText
    private lateinit var btnCarrierPost: Button
    private lateinit var txtCarrierOut: TextView
    private lateinit var btnShowBeacon: Button
    private lateinit var btnShowStrobe: Button
    private lateinit var btnShowPulse: Button
    private lateinit var btnShowAudio: Button
    private lateinit var btnShowStop: Button
    private lateinit var txtShowOut: TextView
    private lateinit var btnVaultUnlock: Button
    private lateinit var btnVaultLock: Button
    private lateinit var btnVaultAdd: Button
    private lateinit var txtVaultOut: TextView
    private lateinit var btnTapePower: Button
    private lateinit var btnTapeMark: Button
    private lateinit var rowTapeResult: View
    private lateinit var txtTapeValue: TextView
    private lateinit var txtTapeBand: TextView
    private lateinit var txtTapeOut: TextView

    private lateinit var paneMeasure: View
    private lateinit var txtMeasureValue: TextView
    private lateinit var txtMeasureBand: TextView
    private lateinit var txtMeasureState: TextView
    private lateinit var txtMeasureHint: TextView
    private lateinit var btnMeasureRun: Button
    private lateinit var btnMeasureCalibrate: Button
    private lateinit var btnMeasureClose: Button

    private lateinit var radar: RadarScope
    private lateinit var txtRadarRange: TextView
    private lateinit var txtRadarTrend: TextView

    private lateinit var bannerUpdate: View
    private lateinit var txtUpdate: TextView
    private lateinit var btnUpdate: Button
    private lateinit var btnUpdateDismiss: TextView

    private lateinit var paneBoot: View
    private lateinit var txtBoot: TextView
    private var boot: BootSequence? = null

    private lateinit var listMessages: ListView
    private lateinit var editMessage: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnPhoto: ImageButton
    private lateinit var rowTransfer: View
    private lateinit var txtTransfer: TextView
    private lateinit var btnTransferCancel: TextView

    /** Gonderilen fotografin kaynagi; aktarim bitince sohbete bunu yaziyoruz. */
    private var pendingPhoto: Pair<String, String>? = null
    private lateinit var messageAdapter: MessageAdapter

    private var service: PttService? = null
    private var bound = false
    private var resumed = false
    private var pairedDevices: List<BluetoothDevice> = emptyList()

    private var tab = Tab.RADIO
    private var unread = 0

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as PttService.LocalBinder).service
            service = svc
            svc.chatVisible = resumed && tab == Tab.CHAT
            if (svc.chatVisible) svc.clearMessageNotification()
            wireService(svc)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            unwireService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        btnListen.setOnClickListener {
            if (!hasRequiredPermissions()) {
                requestPermissions(allRequestedPermissions(), REQ_PERMISSIONS)
                return@setOnClickListener
            }
            startPttService()
            service?.listen()
        }
        btnConnect.setOnClickListener { connectToSelected() }
        btnDisconnect.setOnClickListener { service?.shutdown() }

        tabRadio.setOnClickListener { showTab(Tab.RADIO) }
        tabChat.setOnClickListener { showTab(Tab.CHAT) }
        tabModules.setOnClickListener { showTab(Tab.MODULES) }

        setupPttButton()
        setupComposer()
        setupCallButtons()
        setupMeasure()
        setupModules()

        if (hasRequiredPermissions()) onPermissionsReady()
        else requestPermissions(allRequestedPermissions(), REQ_PERMISSIONS)

        updateControlsEnabled()
        checkForUpdate()
        startBoot(savedInstanceState == null &&
                intent?.getBooleanExtra(PttService.EXTRA_OPEN_CALL, false) != true)
        showTab(if (intent?.getBooleanExtra(PttService.EXTRA_OPEN_CHAT, false) == true) Tab.CHAT else Tab.RADIO)
    }

    private fun bindViews() {
        txtStatus = findViewById(R.id.txtStatus)
        viewLinkRule = findViewById(R.id.viewLinkRule)
        tabRadio = findViewById(R.id.tabRadio)
        tabChat = findViewById(R.id.tabChat)
        txtTabRadio = findViewById(R.id.txtTabRadio)
        txtTabChat = findViewById(R.id.txtTabChat)
        undRadio = findViewById(R.id.undRadio)
        undChat = findViewById(R.id.undChat)
        paneRadio = findViewById(R.id.paneRadio)
        paneChat = findViewById(R.id.paneChat)

        txtLog = findViewById(R.id.txtLog)
        scrollLog = findViewById(R.id.scrollLog)
        spinnerDevices = findViewById(R.id.spinnerDevices)
        btnListen = findViewById(R.id.btnListen)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnPtt = findViewById(R.id.btnPtt)

        // Sürüm manifestten okunur (BuildConfig üretimi kapalı).
        findViewById<TextView>(R.id.txtVersion).text = runCatching {
            "v" + packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("")

        tabModules = findViewById(R.id.tabModules)
        txtTabModules = findViewById(R.id.txtTabModules)
        undModules = findViewById(R.id.undModules)
        paneModules = findViewById(R.id.paneModules)
        flashOverlay = findViewById(R.id.flashOverlay)

        editBoard = findViewById(R.id.editBoard)
        btnBoardPublish = findViewById(R.id.btnBoardPublish)
        txtBoardOut = findViewById(R.id.txtBoardOut)
        editCarrier = findViewById(R.id.editCarrier)
        btnCarrierPost = findViewById(R.id.btnCarrierPost)
        txtCarrierOut = findViewById(R.id.txtCarrierOut)
        btnShowBeacon = findViewById(R.id.btnShowBeacon)
        btnShowStrobe = findViewById(R.id.btnShowStrobe)
        btnShowPulse = findViewById(R.id.btnShowPulse)
        btnShowAudio = findViewById(R.id.btnShowAudio)
        btnShowStop = findViewById(R.id.btnShowStop)
        txtShowOut = findViewById(R.id.txtShowOut)
        btnVaultUnlock = findViewById(R.id.btnVaultUnlock)
        btnVaultLock = findViewById(R.id.btnVaultLock)
        btnVaultAdd = findViewById(R.id.btnVaultAdd)
        txtVaultOut = findViewById(R.id.txtVaultOut)
        btnTapePower = findViewById(R.id.btnTapePower)
        btnTapeMark = findViewById(R.id.btnTapeMark)
        rowTapeResult = findViewById(R.id.rowTapeResult)
        txtTapeValue = findViewById(R.id.txtTapeValue)
        txtTapeBand = findViewById(R.id.txtTapeBand)
        txtTapeOut = findViewById(R.id.txtTapeOut)

        paneMeasure = findViewById(R.id.paneMeasure)
        txtMeasureValue = findViewById(R.id.txtMeasureValue)
        txtMeasureBand = findViewById(R.id.txtMeasureBand)
        txtMeasureState = findViewById(R.id.txtMeasureState)
        txtMeasureHint = findViewById(R.id.txtMeasureHint)
        btnMeasureRun = findViewById(R.id.btnMeasureRun)
        btnMeasureCalibrate = findViewById(R.id.btnMeasureCalibrate)
        btnMeasureClose = findViewById(R.id.btnMeasureClose)

        radar = findViewById(R.id.radar)
        radar.setColors(getColor(R.color.lazer), getColor(R.color.cizgi), getColor(R.color.soluk))
        txtRadarRange = findViewById(R.id.txtRadarRange)
        txtRadarTrend = findViewById(R.id.txtRadarTrend)

        bannerUpdate = findViewById(R.id.bannerUpdate)
        txtUpdate = findViewById(R.id.txtUpdate)
        btnUpdate = findViewById(R.id.btnUpdate)
        btnUpdateDismiss = findViewById(R.id.btnUpdateDismiss)

        paneBoot = findViewById(R.id.paneBoot)
        txtBoot = findViewById(R.id.txtBoot)
        btnCall = findViewById(R.id.btnCall)
        paneCall = findViewById(R.id.paneCall)
        txtCallLabel = findViewById(R.id.txtCallLabel)
        txtCallPeer = findViewById(R.id.txtCallPeer)
        txtCallTimer = findViewById(R.id.txtCallTimer)
        txtCallInfo = findViewById(R.id.txtCallInfo)
        rowMeters = findViewById(R.id.rowMeters)
        rowToggles = findViewById(R.id.rowToggles)
        txtMeterPeer = findViewById(R.id.txtMeterPeer)
        meterMic = findViewById(R.id.meterMic)
        meterRemote = findViewById(R.id.meterRemote)
        btnMute = findViewById(R.id.btnMute)
        btnSpeaker = findViewById(R.id.btnSpeaker)
        btnHangUp = findViewById(R.id.btnHangUp)
        btnAnswer = findViewById(R.id.btnAnswer)

        listMessages = findViewById(R.id.listMessages)
        editMessage = findViewById(R.id.editMessage)
        btnSend = findViewById(R.id.btnSend)
        btnPhoto = findViewById(R.id.btnPhoto)
        rowTransfer = findViewById(R.id.rowTransfer)
        txtTransfer = findViewById(R.id.txtTransfer)
        btnTransferCancel = findViewById(R.id.btnTransferCancel)

        // paneCall kok FrameLayout'un IKINCI cocugu; ilk cocuk fitsSystemWindows
        // ile bosluklari tuketiyor ve kaplamaya hic ulasmiyordu. Bosluklari
        // burada elle uyguluyoruz, sonra normal dagitimi surduruyoruz.
        findViewById<View>(R.id.rootFrame).setOnApplyWindowInsetsListener { v, insets ->
            val yatay = (28 * resources.displayMetrics.density).toInt()
            val dikey = (28 * resources.displayMetrics.density).toInt()
            val ust: Int
            val alt: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                ust = bars.top
                alt = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                ust = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                alt = insets.systemWindowInsetBottom
            }
            paneCall.setPadding(yatay, ust + dikey, yatay, alt + dikey)
            v.onApplyWindowInsets(insets)
        }

        messageAdapter = MessageAdapter(this)
        listMessages.adapter = messageAdapter
        listMessages.emptyView = findViewById(R.id.txtEmpty)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra(PttService.EXTRA_OPEN_CHAT, false) == true) showTab(Tab.CHAT)
    }

    // ---------- kanal seçimi ----------

    private fun showTab(next: Tab) {
        tab = next
        val chat = next == Tab.CHAT
        val mods = next == Tab.MODULES
        paneChat.visibility = if (chat) View.VISIBLE else View.GONE
        paneModules.visibility = if (mods) View.VISIBLE else View.GONE
        paneRadio.visibility = if (!chat && !mods) View.VISIBLE else View.GONE

        fun renk(secili: Boolean) = getColor(if (secili) R.color.lazer else android.R.color.transparent)
        fun yazi(secili: Boolean) = getColor(if (secili) R.color.metin else R.color.soluk)
        undRadio.setBackgroundColor(renk(!chat && !mods))
        undChat.setBackgroundColor(renk(chat))
        undModules.setBackgroundColor(renk(mods))
        txtTabRadio.setTextColor(yazi(!chat && !mods))
        txtTabChat.setTextColor(yazi(chat))
        txtTabModules.setTextColor(yazi(mods))

        service?.chatVisible = resumed && chat
        if (chat) {
            unread = 0
            service?.clearMessageNotification()
        }
        updateTabLabels()
    }

    /** Okunmamış sayısı sekme etiketinde, tek vurgu renginde. */
    private fun updateTabLabels() {
        if (unread <= 0) {
            txtTabChat.text = getString(R.string.tab_chat)
            return
        }
        val label = "${getString(R.string.tab_chat)}  $unread"
        val span = SpannableString(label)
        span.setSpan(
            ForegroundColorSpan(getColor(R.color.lazer)),
            label.length - unread.toString().length,
            label.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        txtTabChat.text = span
    }

    // ---------- mesaj yazma ----------

    private fun setupComposer() {
        btnSend.setOnClickListener { sendMessage() }
        btnPhoto.setOnClickListener { pickPhoto() }
        btnTransferCancel.setOnClickListener { service?.files?.cancelOutgoing() }
        editMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }
    }

    private fun sendMessage() {
        val text = editMessage.text.toString()
        if (text.isBlank()) return
        when (service?.messenger?.send(text) ?: Messenger.SendResult.NO_PEER) {
            Messenger.SendResult.OK -> editMessage.setText("")
            Messenger.SendResult.TOO_LONG ->
                toast(getString(R.string.chat_too_long, Messenger.MAX_CHARS))
            Messenger.SendResult.NO_PEER -> toast(getString(R.string.chat_no_peer))
            Messenger.SendResult.EMPTY -> Unit
        }
    }

    // ---------- moduller ----------

    private fun setupModules() {
        btnBoardPublish.setOnClickListener {
            val board = service?.board ?: return@setOnClickListener
            if (board.publishing.value != null) {
                board.stop()
                editBoard.setText("")
            } else {
                board.publish(editBoard.text.toString())
            }
        }
        btnCarrierPost.setOnClickListener {
            val carrier = service?.carrier ?: return@setOnClickListener
            if (carrier.post(editCarrier.text.toString())) editCarrier.setText("")
        }
        btnShowBeacon.setOnClickListener { runShow(SyncShow.Mode.BEACON) }
        btnShowStrobe.setOnClickListener { runShow(SyncShow.Mode.STROBE) }
        btnShowPulse.setOnClickListener { runShow(SyncShow.Mode.PULSE) }
        btnShowAudio.setOnClickListener { pickAudio() }
        btnShowStop.setOnClickListener { service?.show?.stop() }

        btnVaultUnlock.setOnClickListener { service?.vault?.unlock() }
        btnVaultLock.setOnClickListener { service?.vault?.lock() }
        btnVaultAdd.setOnClickListener { pickVaultFile() }

        btnTapePower.setOnClickListener { toggleTape() }
        btnTapeMark.setOnClickListener { service?.tape?.mark() }
        // Panel dokunusu: izin yoksa izni ister, konum servisi kapaliysa Ayarlar'i acar.
        txtTapeOut.setOnClickListener {
            when (service?.tape?.state?.value?.phase) {
                GpsTape.Phase.NO_PERMISSION -> requestLocationPermission()
                GpsTape.Phase.GPS_OFF -> openLocationSettings()
                else -> Unit
            }
        }
    }

    private fun runShow(mode: SyncShow.Mode) {
        val show = service?.show
        if (show == null || !show.start(mode)) toast(getString(R.string.mod_no_link))
    }

    private fun pickAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/*")
        runCatching { startActivityForResult(intent, REQ_PICK_AUDIO) }
    }

    private fun pickVaultFile() {
        if (service?.vault?.state?.value != ProximityVault.State.UNLOCKED) {
            toast(getString(R.string.mod_no_link))
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        runCatching { startActivityForResult(intent, REQ_PICK_VAULT) }
    }

    /** Duyuru panosu: yayindaki metin + menzilde duyulanlar. */
    private fun renderBoard() {
        val board = service?.board ?: return
        val yayinda = board.publishing.value
        btnBoardPublish.setText(
            if (yayinda != null) R.string.mod_stop_publish else R.string.mod_publish
        )
        val items = board.items.value
        txtBoardOut.text = buildString {
            if (yayinda != null) {
                append("> YAYINDA: ").append(yayinda).append('\n')
                append("// sinir ").append(board.maxBytes).append(" bayt\n\n")
            }
            if (items.isEmpty()) {
                append(getString(R.string.mod_board_empty))
            } else {
                items.forEach { a ->
                    append(if (a.rssi > -60) "▲ " else if (a.rssi > -80) "▪ " else "· ")
                    append(a.text).append('\n')
                }
            }
        }
    }

    /** Tasiyici ag: cantadaki mesajlar ve kac sicrama yapmislar. */
    private fun renderCarrier() {
        val carrier = service?.carrier ?: return
        val items = carrier.items.value
        txtCarrierOut.text = buildString {
            append("// bu oturumda ").append(carrier.forwarded.value)
            append(" mesaj ileri tasindi\n\n")
            if (items.isEmpty()) {
                append(getString(R.string.mod_carrier_empty))
            } else {
                items.forEach { p ->
                    append(if (p.mine) "◆ " else "◇ ")
                    append(p.text)
                    append("  [").append(p.hops).append(" sıçrama · kalan ")
                    append(p.ttl).append("]\n")
                }
            }
        }
    }

    /** Senkron gosteri: esitleme dogrulugu ve gosteri durumu. */
    private fun renderShow() {
        val svc = service ?: return
        val sync = svc.clock?.sync?.value
        val st = svc.show?.state?.value
        txtShowOut.text = when {
            st != null && st.running ->
                getString(R.string.mod_show_running, st.mode.name) +
                        "\n// eşitleme ±" + st.accuracyMs + " ms" +
                        (if (st.master) "\n// bu cihaz başlattı" else "\n// karşı cihaz başlattı")
            sync != null && sync.ready ->
                getString(R.string.mod_show_ready, sync.accuracyMs)
            else -> getString(R.string.mod_show_idle)
        }
    }

    /**
     * Yakinlik kasasi. Kilitliyken dosya SAYISI gorunur ama icerik gorunmez -
     * dosya adlari da sifreli govdenin icinde.
     */
    private fun renderVault() {
        val vault = service?.vault ?: return
        val st = vault.state.value
        val entries = vault.entries.value
        txtVaultOut.text = buildString {
            append(
                when (st) {
                    ProximityVault.State.UNLOCKED -> getString(R.string.mod_vault_unlocked)
                    ProximityVault.State.UNLOCKING -> getString(R.string.mod_vault_unlocking)
                    ProximityVault.State.DENIED -> getString(R.string.mod_vault_denied)
                    ProximityVault.State.NO_PEER -> getString(R.string.mod_vault_nopeer)
                    else -> getString(R.string.mod_vault_locked, vault.fileCount())
                }
            )
            if (st == ProximityVault.State.UNLOCKED) {
                append("\n")
                if (entries.isEmpty()) append("\n// kasa boş")
                else entries.forEach { e ->
                    append("\n▸ ").append(e.name).append("  ").append(e.bytes / 1024).append(" KB")
                }
            }
        }
        btnVaultAdd.isEnabled = st == ProximityVault.State.UNLOCKED
    }

    // ---------- gps serit metre ----------

    /** Ac/kapat. Konum izni ilk kez burada istenir - uygulamanin geri kalani konum kullanmaz. */
    private fun toggleTape() {
        val t = service?.tape ?: return
        if (t.state.value.phase != GpsTape.Phase.OFF) {
            t.stop()
            return
        }
        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }
        t.start()
    }

    private fun requestLocationPermission() {
        // COARSE de istenir: Android 12+ yalnizca FINE isteyen uygulamaya iletisim
        // kutusunu hic gostermeyebiliyor. Kullanici "yaklasik" secerse GPS_PROVIDER
        // calismaz; sonuc isleyicisi bunu soyler.
        requestPermissions(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            REQ_LOCATION
        )
    }

    private fun openLocationSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * GPS serit metre. Sonuc TEK SAYI degil, ± bandiyla gosterilir - radarla ayni
     * durustluk. Fark gurultunun altinda kaldiysa "ayni nokta" denir, uydurma
     * bir ondalik yazilmaz.
     */
    private fun renderTape(s: GpsTape.TapeState) {
        val on = s.phase != GpsTape.Phase.OFF
        btnTapePower.setText(if (on) R.string.mod_tape_off else R.string.mod_tape_on)
        btnTapeMark.setText(
            when {
                s.done -> R.string.mod_tape_again
                s.a != null -> R.string.mod_tape_mark_b
                else -> R.string.mod_tape_mark_a
            }
        )
        btnTapeMark.isEnabled = s.done || s.phase == GpsTape.Phase.READY

        if (s.done) {
            rowTapeResult.visibility = View.VISIBLE
            if (s.meters < s.sigmaM) {
                txtTapeValue.text = "< " + yuvarla(s.sigmaM) + " m"
                txtTapeBand.text = getString(R.string.mod_tape_same)
            } else {
                txtTapeValue.text = yuvarla(s.meters) + " m"
                txtTapeBand.text = getString(
                    R.string.mod_tape_result_band, yuvarla(s.sigmaM), s.bearingDeg.roundToInt()
                )
            }
        } else {
            rowTapeResult.visibility = View.GONE
        }

        val live = s.liveMeters
        txtTapeOut.text = buildString {
            append(
                when (s.phase) {
                    GpsTape.Phase.OFF -> getString(R.string.mod_tape_idle)
                    GpsTape.Phase.NO_PERMISSION -> getString(R.string.mod_tape_no_permission)
                    GpsTape.Phase.GPS_OFF -> getString(R.string.mod_tape_gps_off)
                    GpsTape.Phase.SEARCHING -> getString(R.string.mod_tape_searching, s.satsTotal)
                    GpsTape.Phase.READY -> getString(
                        R.string.mod_tape_fix, yuvarla(s.fixAccuracyM ?: 0f), s.satsUsed, s.satsTotal
                    )
                    GpsTape.Phase.MARKING -> getString(
                        R.string.mod_tape_marking, (s.progress * 100).toInt()
                    )
                }
            )
            s.a?.let {
                append("\n\n").append(getString(R.string.mod_tape_point, "A", yuvarla(it.sigmaM), it.fixes))
            }
            if (s.a != null && s.b == null && live != null) {
                append('\n').append(
                    getString(R.string.mod_tape_live, yuvarla(live), yuvarla(s.liveSigmaM ?: 0f))
                )
            }
            s.b?.let {
                append('\n').append(getString(R.string.mod_tape_point, "B", yuvarla(it.sigmaM), it.fixes))
            }
            if (s.done && s.gapMs > GpsTape.STALE_BASELINE_MS) {
                append('\n').append(getString(R.string.mod_tape_stale, s.gapMs / 60_000))
            }
            s.failed?.let {
                append("\n\n").append(
                    getString(
                        if (it == GpsTape.FAIL_NO_FIX) R.string.mod_tape_fail_fix
                        else R.string.mod_tape_fail_few
                    )
                )
            }
        }
    }

    // ---------- olcum ----------

    private fun setupMeasure() {
        radar.setOnClickListener { openMeasure() }
        btnMeasureRun.setOnClickListener { service?.ranging?.startMeasurement() }
        btnMeasureCalibrate.setOnClickListener { service?.ranging?.startCalibration() }
        btnMeasureClose.setOnClickListener {
            service?.ranging?.cancelMeasurement()
            paneMeasure.visibility = View.GONE
        }
    }

    private fun openMeasure() {
        val r = service?.ranging ?: return
        paneMeasure.visibility = View.VISIBLE
        txtMeasureValue.text = ""
        txtMeasureBand.text = ""
        txtMeasureState.text = getString(
            if (r.calibration.value.calibrated) R.string.measure_calibrated
            else R.string.measure_uncalibrated
        )
        // Donanim mesafe olcumu varsa kullaniciya soyle: o yolda santimetre
        // mertebesi mumkun, sinyal gucuyle degil.
        if (r.hardwareRangingAvailable) {
            txtMeasureHint.text = buildString {
                append(getString(R.string.measure_hw_yes))
                append('\n')
                append(getString(R.string.measure_hint))
            }
        }
    }

    /**
     * Olcum sonucu. Metre yerine ARALIK gosteriliyor ve alan/hacim hic
     * hesaplanmiyor: hata mesafede carpansal oldugu icin alanda kareleniyor,
     * yani anlamli bir m2 rakami uretilemiyor.
     */
    private fun renderMeasure(m: Measurement) {
        val calibrated = service?.ranging?.calibration?.value?.calibrated == true
        when {
            m.running -> {
                val pct = (m.progress * 100).toInt()
                txtMeasureValue.text = "…"
                txtMeasureBand.text = ""
                txtMeasureState.text = getString(
                    if (m.calibrating) R.string.measure_calibrating else R.string.measure_hold,
                    pct
                )
                btnMeasureRun.isEnabled = false
                btnMeasureCalibrate.isEnabled = false
            }
            m.failed != null -> {
                txtMeasureValue.text = ""
                txtMeasureBand.text = ""
                txtMeasureState.text = getString(
                    when (m.failed) {
                        FAIL_NO_SIGNAL -> R.string.measure_fail_signal
                        FAIL_TOO_FEW -> R.string.measure_fail_few
                        else -> R.string.measure_fail_unstable
                    }
                )
                btnMeasureRun.isEnabled = true
                btnMeasureCalibrate.isEnabled = true
            }
            m.done && m.calibrating -> {
                txtMeasureState.text = getString(R.string.measure_cal_done)
                btnMeasureRun.isEnabled = true
                btnMeasureCalibrate.isEnabled = true
            }
            m.done -> {
                txtMeasureValue.text = yuvarla(m.meters) + " m"
                txtMeasureBand.text =
                    getString(R.string.measure_band, yuvarla(m.low), yuvarla(m.high))
                txtMeasureState.text = getString(
                    if (calibrated) R.string.measure_calibrated
                    else R.string.measure_uncalibrated
                )
                btnMeasureRun.isEnabled = true
                btnMeasureCalibrate.isEnabled = true
            }
            else -> {
                btnMeasureRun.isEnabled = true
                btnMeasureCalibrate.isEnabled = true
            }
        }
    }

    // ---------- radar ----------

    /**
     * Radar okumasi. Metre yerine ARALIK gosteriliyor: sinyalden mesafe
     * kestiriminin hatasi carpansaldir, tek bir sayi yazmak yaniltici olurdu.
     * Yon hic gosterilmiyor - Bluetooth ile yon bilgisi fiziksel olarak yok.
     */
    private fun renderRadar(e: RangeEstimate) {
        radar.setEstimate(e)
        when (e.state) {
            RangeEstimate.State.LIVE, RangeEstimate.State.PAUSED -> {
                txtRadarRange.text = getString(
                    R.string.radar_range, yuvarla(e.low), yuvarla(e.high)
                )
                txtRadarTrend.text = getString(
                    if (e.state == RangeEstimate.State.PAUSED) {
                        R.string.radar_paused
                    } else when (e.trend) {
                        RangeEstimate.Trend.CLOSING -> R.string.radar_closing
                        RangeEstimate.Trend.OPENING -> R.string.radar_opening
                        else -> R.string.radar_holding
                    }
                )
                txtRadarTrend.setTextColor(
                    getColor(
                        if (e.trend == RangeEstimate.Trend.CLOSING) R.color.lazer
                        else R.color.soluk
                    )
                )
            }
            else -> {
                txtRadarRange.text = ""
                txtRadarTrend.text = getString(
                    when (e.state) {
                        RangeEstimate.State.UNSUPPORTED -> R.string.radar_unsupported
                        RangeEstimate.State.NO_PERMISSION -> R.string.radar_no_permission
                        RangeEstimate.State.NO_SIGNAL -> R.string.radar_no_signal
                        else -> R.string.radar_off
                    }
                )
                txtRadarTrend.setTextColor(getColor(R.color.soluk))
            }
        }
    }

    /** Ondalik gostermek sahte bir kesinlik verirdi; tek anlamli basamak yeter. */
    private fun yuvarla(m: Float): String =
        if (m < 10f) String.format("%.1f", m).trimEnd('0').trimEnd('.', ',')
        else m.toInt().toString()

    // ---------- guncelleme ----------

    /**
     * Yeni surum var mi diye sorar ve varsa seridi gosterir.
     *
     * Kurulumu uygulama YAPMAZ: APK baglantisi sisteme devredilir, indirmeyi ve
     * kurmayi Android'in kendi yukleyicisi yapar. Kendi APK'sini kuran bir
     * uygulama REQUEST_INSTALL_PACKAGES istemek zorunda kalirdi ve Play bu izni
     * yalnizca magaza uygulamalarina veriyor.
     *
     * Ag yoksa ya da istek dusesse hicbir sey olmaz - sessizce vazgecilir.
     */
    private fun checkForUpdate() {
        uiScope.launch {
            val checker = UpdateChecker(this@MainActivity)
            val update = withContext(Dispatchers.IO) {
                runCatching { checker.check() }.getOrNull()
            } ?: return@launch

            txtUpdate.text = getString(R.string.update_available, update.versionName)
            bannerUpdate.visibility = View.VISIBLE
            btnUpdate.setOnClickListener {
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(update.apkUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure { toast(getString(R.string.update_failed)) }
            }
            btnUpdateDismiss.setOnClickListener {
                checker.dismiss(update.versionCode)
                bannerUpdate.visibility = View.GONE
            }
        }
    }

    // ---------- acilis dizisi ----------

    /**
     * Aygitin kendi kendini sinamasi. Her satir gercek durumu bildirir:
     * Bluetooth acik mi, kac cihaz eslesmis, hangi ses codec'i kurulabiliyor,
     * kac mesaj kayitli. Sahte bir tiyatro degil, acilis raporu.
     *
     * Gelen cagriyla acildiysa hic gosterilmez - cagri ekrani beklemez.
     */
    @SuppressLint("MissingPermission")
    private fun startBoot(show: Boolean) {
        if (!show) {
            paneBoot.visibility = View.GONE
            return
        }
        paneBoot.visibility = View.VISIBLE
        val seq = BootSequence(
            view = txtBoot,
            scope = uiScope,
            dimColor = getColor(R.color.soluk),
            accentColor = getColor(R.color.lazer),
            textColor = getColor(R.color.metin)
        )
        boot = seq
        // Dokununca atlanir.
        paneBoot.setOnClickListener { finishBoot() }

        uiScope.launch {
            // Codec sinamasi gercekten MediaCodec kuruyor: ana is parcaciginda olmaz.
            val codec = withContext(Dispatchers.IO) {
                runCatching {
                    if (AmrCodec.wideBandSupported) "AMR-WB 16kHz" else "AMR-NB 8kHz"
                }.getOrDefault("AMR-NB 8kHz")
            }
            val kayit = withContext(Dispatchers.IO) {
                runCatching {
                    File(filesDir, "sohbet").listFiles()
                        ?.sumOf { f -> f.readLines().count { it.isNotBlank() } } ?: 0
                }.getOrDefault(0)
            }
            val adapter = runCatching {
                getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
            }.getOrNull()
            val radyoAcik = adapter?.isEnabled == true
            val eslesmis = runCatching { adapter?.bondedDevices?.size ?: 0 }.getOrDefault(0)
            val surum = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrDefault("")

            seq.run(
                header = listOf(
                    "LASERDECK OS $surum",
                    "VOYAGERROC · RF/OPTİK HAT YIĞINI",
                    "────────────────────────────────"
                ),
                lines = listOf(
                    BootSequence.Line(" OK ", getString(R.string.boot_kernel), getString(R.string.boot_ready)),
                    BootSequence.Line(" OK ", getString(R.string.boot_frame), "CRC32 · 2048B"),
                    BootSequence.Line(
                        if (radyoAcik) " OK " else "FAIL",
                        getString(R.string.boot_radio),
                        getString(if (radyoAcik) R.string.boot_on else R.string.boot_off)
                    ),
                    BootSequence.Line(" OK ", getString(R.string.boot_paired), getString(R.string.boot_devices, eslesmis)),
                    BootSequence.Line(" OK ", getString(R.string.boot_codec), codec),
                    BootSequence.Line(" OK ", getString(R.string.boot_store), getString(R.string.boot_records, kayit)),
                    BootSequence.Line(" .. ", getString(R.string.boot_carrier), getString(R.string.boot_waiting))
                ),
                onDone = { finishBoot() }
            )
        }
    }

    private fun finishBoot() {
        boot?.skip()
        boot = null
        paneBoot.visibility = View.GONE
    }

    // ---------- cagri ----------

    private fun setupCallButtons() {
        btnCall.setOnClickListener {
            if (!hasRequiredPermissions()) {
                requestPermissions(allRequestedPermissions(), REQ_PERMISSIONS)
                return@setOnClickListener
            }
            service?.callController?.placeCall()
        }
        btnAnswer.setOnClickListener { service?.callController?.answer() }
        btnHangUp.setOnClickListener { service?.callController?.hangUp() }
        btnMute.setOnClickListener {
            val c = service?.callController ?: return@setOnClickListener
            c.setMuted(!c.muted)
        }
        btnSpeaker.setOnClickListener {
            val c = service?.callController ?: return@setOnClickListener
            c.setSpeaker(!c.speakerOn)
        }
    }

    /**
     * Cagri ekrani her seyin ustunu kaplar. Gelen cagrida ekran kilitliyse bile
     * gorunmeli - gercek bir telefon cagrisi gibi.
     */
    private fun renderCall(state: CallState) {
        val visible = state !is CallState.Idle
        paneCall.visibility = if (visible) View.VISIBLE else View.GONE
        showOverLockScreen(state is CallState.Incoming)

        val c = service?.callController
        btnCall.isEnabled =
            service?.transport?.state?.value is TransportState.Connected && c?.isBusy != true

        when (state) {
            is CallState.Idle -> stopCallTimer()

            is CallState.Incoming -> {
                stopCallTimer()
                txtCallLabel.setText(R.string.call_incoming)
                txtCallPeer.text = state.peer
                txtCallTimer.text = ""
                txtCallInfo.text = ""
                rowMeters.visibility = View.GONE
                rowToggles.visibility = View.GONE
                btnAnswer.visibility = View.VISIBLE
                btnHangUp.setText(R.string.call_decline)
            }

            is CallState.Dialing -> {
                stopCallTimer()
                txtCallLabel.setText(
                    if (state.remoteRinging) R.string.call_ringing else R.string.call_dialing
                )
                txtCallPeer.text = state.peer
                txtCallTimer.text = ""
                txtCallInfo.text = ""
                rowMeters.visibility = View.GONE
                rowToggles.visibility = View.GONE
                btnAnswer.visibility = View.GONE
                btnHangUp.setText(R.string.call_end)
            }

            is CallState.Active -> {
                txtCallLabel.setText(R.string.call_active)
                txtCallPeer.text = state.peer
                txtCallInfo.text = callInfoText(state)
                txtMeterPeer.text = state.peer.uppercase()
                rowMeters.visibility = View.VISIBLE
                rowToggles.visibility = View.VISIBLE
                btnAnswer.visibility = View.GONE
                btnHangUp.setText(R.string.call_end)
                startCallTimer(state.startedAt)
            }

            is CallState.Ended -> {
                stopCallTimer()
                txtCallLabel.text = getString(endedLabel(state.reason))
                txtCallTimer.text = ""
                txtCallInfo.text = ""
                rowMeters.visibility = View.GONE
                rowToggles.visibility = View.GONE
                btnAnswer.visibility = View.GONE
                btnHangUp.setText(R.string.call_end)
            }
        }
    }

    /** Ses yolu hakkinda dogruyu soyler: yankı gidericisi donanima bagli. */
    private fun callInfoText(state: CallState.Active): String {
        val band = getString(
            if (state.codecId == com.voyagerroc.laserdeck.ptt.audio.VoiceCodec.ID_AMR_WB) {
                R.string.call_band_wide
            } else {
                R.string.call_band_narrow
            }
        )
        val dsp = getString(
            when {
                state.effects.echoCancel -> R.string.call_dsp_on
                state.effects.noiseSuppress -> R.string.call_dsp_noise_only
                else -> R.string.call_dsp_off
            }
        )
        return "$band\n$dsp"
    }

    private fun endedLabel(reason: CallState.Reason): Int = when (reason) {
        CallState.Reason.DECLINED -> R.string.call_ended_declined
        CallState.Reason.BUSY -> R.string.call_ended_busy
        CallState.Reason.NO_ANSWER -> R.string.call_ended_no_answer
        CallState.Reason.MISSED -> R.string.call_ended_missed
        CallState.Reason.LINK_LOST -> R.string.call_ended_link
        CallState.Reason.FAILED -> R.string.call_ended_failed
        else -> R.string.call_ended_hung_up
    }

    /** Acikken dolu kirmizi, kapaliyken anahat - digerleriyle ayni dil. */
    private fun paintToggle(button: Button, on: Boolean) {
        button.setBackgroundResource(if (on) R.drawable.btn_dolu else R.drawable.btn_anahat)
        button.setTextColor(getColor(if (on) android.R.color.white else R.color.metin))
    }

    private fun startCallTimer(startedAt: Long) {
        if (callTimerJob?.isActive == true) return
        callTimerJob = uiScope.launch {
            while (isActive) {
                val secs = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
                txtCallTimer.text = String.format("%02d:%02d", secs / 60, secs % 60)
                delay(500)
            }
        }
    }

    private fun stopCallTimer() {
        callTimerJob?.cancel()
        callTimerJob = null
    }

    /** Gelen cagri kilit ekraninin ustunde acilsin ve ekrani uyandirsin. */
    private fun showOverLockScreen(enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(enable)
            setTurnScreenOn(enable)
        }
    }

    // ---------- fotograf ----------

    /**
     * Sistem belge seciciyi acar. Depolama izni GEREKMEZ: kullanici dosyayi
     * kendi sectigi icin erisim tek seferlik olarak bize veriliyor.
     */
    private fun pickPhoto() {
        if (service?.transport?.state?.value !is TransportState.Connected) {
            toast(getString(R.string.photo_no_link))
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
        runCatching { startActivityForResult(intent, REQ_PICK_PHOTO) }
            .onFailure { toast(getString(R.string.photo_pick)) }
    }

    @Deprecated("Activity tabanli ekran; ActivityResult API'si AndroidX ister")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            REQ_PICK_AUDIO -> {
                service?.show?.setAudio(uri)
                if (service?.show?.start(SyncShow.Mode.AUDIO, 5 * 60 * 1000L) != true) {
                    toast(getString(R.string.mod_no_link))
                }
                return
            }
            REQ_PICK_VAULT -> {
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "dosya"
                // Sifreleme arka planda: buyuk bir dosya secilirse ana is
                // parcaciginda yapmak uygulamayi donduruyordu.
                service?.vault?.addFile(uri, name)
                return
            }
            REQ_PICK_PHOTO -> Unit
            else -> return
        }
        val files = service?.files ?: return
        if (!files.sendFile(uri)) {
            toast(getString(R.string.photo_busy))
            return
        }
        // Kalici okuma izni al: aktarim bitince sohbette onizleme gosterecegiz.
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        pendingPhoto = (uri.lastPathSegment?.substringAfterLast('/') ?: "foto") to uri.toString()
        showTab(Tab.CHAT)
    }

    /** Aktarim seridi. Bitince fotograf sohbete kaydediliyor. */
    private fun renderTransfer(t: FileTransferManager.Transfer) {
        when (t.state) {
            FileTransferManager.Transfer.State.OFFERED,
            FileTransferManager.Transfer.State.RUNNING -> {
                rowTransfer.visibility = View.VISIBLE
                btnTransferCancel.visibility = if (t.outgoing) View.VISIBLE else View.GONE
                txtTransfer.text = getString(
                    if (t.outgoing) R.string.photo_sending else R.string.photo_receiving,
                    t.name, t.percent
                )
            }
            FileTransferManager.Transfer.State.COMPLETE -> {
                rowTransfer.visibility = View.GONE
                if (t.outgoing) {
                    pendingPhoto?.let { (name, source) ->
                        service?.messenger?.addPhoto(name, source, outgoing = true)
                    }
                    pendingPhoto = null
                }
            }
            else -> {
                rowTransfer.visibility = View.GONE
                pendingPhoto = null
                if (t.state == FileTransferManager.Transfer.State.FAILED) {
                    toast(getString(R.string.photo_failed, t.name))
                }
            }
        }
    }

    // ---------- izinler ----------

    /** Olmazsa olmaz izinler. Bildirim izni buraya DAHİL DEĞİL: reddedilse de
     *  ön plan servisi çalışır, sadece bildirim görünmez — uygulamayı kilitlemeye
     *  değmez. */
    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        return perms.toTypedArray()
    }

    /** İstenen ama zorunlu olmayanlar dahil tüm izinler. */
    private fun allRequestedPermissions(): Array<String> {
        val perms = requiredPermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        // Radar izinleri: reddedilirse yalnizca radar kapali kalir, telsiz
        // calismaya devam eder. Konum izni DEGIL.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        return perms.toTypedArray()
    }

    private fun hasRadarPermissions(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            service?.ranging?.setPermitted(hasRadarPermissions())
            if (hasRequiredPermissions()) {
                onPermissionsReady()
            } else {
                toast(getString(R.string.toast_need_permissions))
            }
            updateControlsEnabled()
        }
        if (requestCode == REQ_LOCATION) {
            val fine = hasLocationPermission()
            service?.tape?.setPermitted(fine)
            if (fine) service?.tape?.start() else toast(getString(R.string.mod_tape_need_fine))
        }
    }

    /** İzin yoksa bağlantı düğmeleri servisi başlatıp SecurityException'a
     *  yol açmasın; onun yerine izni tekrar iste. */
    private fun updateControlsEnabled() {
        val ok = hasRequiredPermissions()
        btnListen.isEnabled = ok
        btnConnect.isEnabled = ok
        if (!ok) btnPtt.isEnabled = false
    }

    // ---------- kurulum ----------

    /**
     * onResume'dan da çağrılır: kullanıcı Ayarlar'a gidip Bluetooth'u açtıktan ya da
     * iki telefonu eşleştirdikten sonra geri döndüğünde liste ve bağlantı tazelenmeli.
     */
    @SuppressLint("MissingPermission")
    private fun onPermissionsReady() {
        // Servise bağlanmak Bluetooth durumundan bağımsızdır — önce onu yap.
        if (!bound) {
            bound = true
            bindService(Intent(this, PttService::class.java), connection, Context.BIND_AUTO_CREATE)
        }

        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            txtStatus.text = getString(R.string.status_no_bluetooth)
            return
        }
        if (!adapter.isEnabled) {
            txtStatus.text = getString(R.string.status_bt_off)
            pairedDevices = emptyList()
            spinnerDevices.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.spinner_bt_off))
            )
            return
        }

        pairedDevices = adapter.bondedDevices?.toList().orEmpty()
        val labels = pairedDevices.map { "${it.name ?: "?"} (${it.address})" }
        spinnerDevices.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            if (labels.isEmpty()) listOf(getString(R.string.spinner_no_paired)) else labels
        )
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        service?.chatVisible = tab == Tab.CHAT
        if (tab == Tab.CHAT) {
            unread = 0
            updateTabLabels()
            service?.clearMessageNotification()
        }
        if (hasRequiredPermissions()) onPermissionsReady()
        refreshPtt()
        service?.tape?.setVisible(true)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        service?.chatVisible = false
        // Konum alicisi yalnizca ekran ondeyken acik: arka planda konum okunmaz.
        service?.tape?.setVisible(false)
    }

    private fun startPttService() {
        startForegroundService(
            Intent(this, PttService::class.java).setAction(PttService.ACTION_START)
        )
    }

    private fun connectToSelected() {
        if (!hasRequiredPermissions()) {
            requestPermissions(allRequestedPermissions(), REQ_PERMISSIONS)
            return
        }
        val device = pairedDevices.getOrNull(spinnerDevices.selectedItemPosition)
        if (device == null) {
            toast(getString(R.string.toast_pair_first))
            return
        }
        startPttService()
        service?.connectTo(device)
    }

    // ---------- servis bağlama ----------

    private fun wireService(svc: PttService) {
        unwireService()

        // GPS serit metre hatta bagli degil: Bluetooth adaptoru olmasa da calisir,
        // o yuzden tasiyici denetiminden ONCE baglanir.
        svc.tape?.let { t ->
            t.setPermitted(hasLocationPermission())
            t.setVisible(resumed)
            serviceJobs += uiScope.launch { t.state.collect { renderTape(it) } }
        }

        val transport = svc.transport
        if (transport == null) {
            txtStatus.text = getString(R.string.status_no_adapter)
            return
        }

        serviceJobs += uiScope.launch {
            transport.state.collect { s ->
                txtStatus.text = PttService.stateText(s)
                viewLinkRule.setBackgroundColor(
                    getColor(if (s is TransportState.Connected) R.color.lazer else R.color.cizgi)
                )
                btnCall.isEnabled = s is TransportState.Connected &&
                        service?.callController?.isBusy != true
                refreshPtt()
            }
        }
        serviceJobs += uiScope.launch {
            svc.logs.collect { msg -> appendLog(msg) }
        }
        serviceJobs += uiScope.launch {
            svc.pttEvents.collect { refreshPtt() }
        }
        svc.messenger?.let { m ->
            serviceJobs += uiScope.launch {
                m.messages.collect { list -> messageAdapter.submit(list) }
            }
        }
        // Okunmamis sayisi listenin BOYUNDAN degil, gercekten gelen mesajlardan
        // sayilir: gecmis yuklenince (baglanti aninda) eski mesajlarin tamami
        // yeni sanilip sekmede sahte bir rozet cikiyordu.
        serviceJobs += uiScope.launch {
            svc.transfers.collect { renderTransfer(it) }
        }
        // Senkron gosteri: ekranin tamami yanip soner.
        serviceJobs += uiScope.launch {
            svc.flash.collect { on ->
                flashOverlay.visibility = if (on) View.VISIBLE else View.GONE
            }
        }
        svc.board?.let { b ->
            serviceJobs += uiScope.launch { b.items.collect { renderBoard() } }
            serviceJobs += uiScope.launch { b.publishing.collect { renderBoard() } }
        }
        svc.carrier?.let { c ->
            serviceJobs += uiScope.launch { c.items.collect { renderCarrier() } }
            serviceJobs += uiScope.launch { c.forwarded.collect { renderCarrier() } }
        }
        svc.show?.let { sh ->
            serviceJobs += uiScope.launch { sh.state.collect { renderShow() } }
        }
        svc.clock?.let { ck ->
            serviceJobs += uiScope.launch { ck.sync.collect { renderShow() } }
        }
        svc.vault?.let { v ->
            serviceJobs += uiScope.launch { v.state.collect { renderVault() } }
            serviceJobs += uiScope.launch { v.entries.collect { renderVault() } }
        }
        svc.ranging?.let { r ->
            r.setPermitted(hasRadarPermissions())
            serviceJobs += uiScope.launch {
                r.estimate.collect { renderRadar(it) }
            }
            serviceJobs += uiScope.launch {
                r.measure.collect { renderMeasure(it) }
            }
        }
        svc.callController?.let { call ->
            serviceJobs += uiScope.launch {
                call.state.collect { st ->
                    renderCall(st)
                    refreshPtt()
                }
            }
            serviceJobs += uiScope.launch {
                // Sessiz/hoparlor cagri durumunu degistirmez; ayri akis olmasa
                // dugmeler basildiginda hic boyanmiyordu.
                call.controls.collect { ctl ->
                    paintToggle(btnMute, ctl.muted)
                    paintToggle(btnSpeaker, ctl.speakerOn)
                }
            }
            serviceJobs += uiScope.launch {
                call.micLevel.collect { meterMic.setLevel(it) }
            }
            serviceJobs += uiScope.launch {
                call.remoteLevel.collect { meterRemote.setLevel(it) }
            }
        }
        serviceJobs += uiScope.launch {
            svc.incomingMessages.collect {
                if (tab != Tab.CHAT) {
                    unread++
                    updateTabLabels()
                }
            }
        }
    }

    private fun unwireService() {
        serviceJobs.forEach { it.cancel() }
        serviceJobs.clear()
    }

    // ---------- bas-konuş ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPttButton() {
        btnPtt.setOnTouchListener { v, event ->
            val c = service?.controller ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (c.canTalk) {
                        c.startTalking()
                        btnPtt.text = getString(R.string.ptt_live)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    c.stopTalking()
                    v.performClick()
                    refreshPtt()
                    true
                }
                else -> false
            }
        }
    }

    private fun refreshPtt() {
        val connected = service?.transport?.state?.value is TransportState.Connected
        val c = service?.controller

        // Konuşurken düğme ASLA devre dışı bırakılmamalı: devre dışı bir View
        // ACTION_UP almaz, o yüzden stopTalking() hiç çağrılmaz ve mikrofon
        // sonsuza kadar açık kalırdı. Bu koruma çağrı kilidinden ÖNCE gelmeli:
        // aksi halde tam basılıyken gelen bir çağrı aynı tuzağı kuruyordu.
        if (c?.isTalking == true) {
            btnPtt.isEnabled = true
            return
        }

        // Cagri suruyorsa bas-konus kilitli: iki ses yolu ayni mikrofonu ister.
        if (service?.callController?.isBusy == true) {
            btnPtt.isEnabled = false
            btnPtt.text = getString(R.string.ptt_hint)
            return
        }

        btnPtt.isEnabled = connected && (c?.canTalk != false)
        btnPtt.text = getString(
            if (c?.isReceiving == true) R.string.ptt_receiving else R.string.ptt_hint
        )
    }

    // ---------- yardımcılar ----------

    /** Günlük sınırsız büyürse TextView yavaşlar; baştan satır bütünlüğü
     *  korunarak kırpılır. */
    private fun appendLog(msg: String) {
        txtLog.append("[${logClock.format(Date())}] $msg\n")
        if (txtLog.length() > LOG_MAX_CHARS) {
            val text = txtLog.text.toString()
            val cut = text.indexOf('\n', text.length - LOG_MAX_CHARS / 2)
            if (cut > 0) txtLog.text = text.substring(cut + 1)
        }
        scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private val logClock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        unwireService()
        boot?.skip()
        stopCallTimer()
        service?.chatVisible = false
        if (bound) {
            unbindService(connection)
            bound = false
        }
        // Servisi bilerek durdurmuyoruz: hat arka planda açık kalır,
        // kapatmak için ekrandaki "KES" ya da bildirimdeki "Kapat" kullanılır.
        uiScope.cancel()
    }
}
