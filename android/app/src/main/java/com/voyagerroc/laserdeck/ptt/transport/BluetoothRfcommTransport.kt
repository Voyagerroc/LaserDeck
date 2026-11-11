package com.voyagerroc.laserdeck.ptt.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.voyagerroc.laserdeck.ptt.protocol.FrameParser
import com.voyagerroc.laserdeck.ptt.protocol.LinkFrame
import com.voyagerroc.laserdeck.ptt.protocol.LinkProtocol
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android <-> Android Bluetooth Classic (RFCOMM/SPP) taşıyıcısı — otomatik
 * yeniden bağlanmalı sürüm.
 *
 * Tek bir "supervisor" iş parçacığı bağlantı yaşam döngüsünü yönetir:
 *  - SERVER rolü: accept -> bağlantı -> koptu -> hemen tekrar accept.
 *  - CLIENT rolü: connect -> bağlantı -> koptu -> üstel geri çekilme
 *    (2s, 4s, 8s, 15s tavan) ile aynı cihaza yeniden bağlanma.
 * close() kullanıcı niyetidir: döngüyü tamamen durdurur.
 *
 * Keşif (discovery) kullanılmaz: iki telefon önce Ayarlar'dan eşleştirilir.
 * Böylece konum/tarama izinleri gerekmez; API 31+ için tek çalışma zamanı
 * izni BLUETOOTH_CONNECT'tir. İzin denetimleri Activity katmanında yapılır.
 */
@SuppressLint("MissingPermission")
class BluetoothRfcommTransport(
    private val adapter: BluetoothAdapter
) : FrameTransport {

    companion object {
        /** Uygulamaya özel servis UUID'i (SPP jenerik UUID'i değil). */
        val APP_UUID: UUID = UUID.fromString("8b6c5e5a-3f2b-4c96-9c8e-5d2f1a6b7c01")
        const val SERVICE_NAME = "LaserDeckPTT"
        private const val MAX_BACKOFF_MS = 15_000L
    }

    private enum class Role { SERVER, CLIENT }

    private val _state = MutableStateFlow<TransportState>(TransportState.Idle)
    override val state: StateFlow<TransportState> = _state

    private val _incoming = MutableSharedFlow<LinkFrame>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incoming: SharedFlow<LinkFrame> = _incoming

    private val shouldRun = AtomicBoolean(false)
    @Volatile private var role: Role? = null
    @Volatile private var targetDevice: BluetoothDevice? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var socket: BluetoothSocket? = null
    private var supervisor: Thread? = null
    private val txQueue = LinkedBlockingQueue<ByteArray>(512)

    override fun startServer() = startLoop(Role.SERVER, null)

    fun connect(device: BluetoothDevice) = startLoop(Role.CLIENT, device)

    private fun startLoop(newRole: Role, device: BluetoothDevice?) {
        close()
        role = newRole
        targetDevice = device
        shouldRun.set(true)
        supervisor = Thread(::supervise, "bt-supervisor").also { it.start() }
    }

    private fun supervise() {
        var attempt = 0
        while (shouldRun.get()) {
            try {
                val s: BluetoothSocket = if (role == Role.SERVER) {
                    _state.value = TransportState.Listening
                    val server = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
                    serverSocket = server
                    try {
                        server.accept() // bloklar; close() serverSocket'i kapatınca fırlatır
                    } finally {
                        runCatching { server.close() }
                        serverSocket = null
                    }
                } else {
                    val dev = targetDevice ?: break
                    _state.value = TransportState.Connecting(dev.name ?: dev.address)
                    val cs = dev.createRfcommSocketToServiceRecord(APP_UUID)
                    socket = cs // close() bekleyen connect'i de iptal edebilsin
                    cs.connect() // bloklar
                    cs
                }
                attempt = 0
                runConnection(s) // dönerse bağlantı kopmuş demektir
            } catch (_: Exception) {
                if (!shouldRun.get()) break
            }

            if (!shouldRun.get()) break

            // CLIENT üstel geri çekilme; SERVER sabit kısa bekleme.
            // Sunucu tarafında bekleme YOKTU: listen() hemen hata verirse (Bluetooth
            // kapandı, izin yok) döngü sıfır gecikmeyle dönüp bir çekirdeği kilitliyordu.
            val delayMs = if (role == Role.CLIENT) {
                attempt++
                minOf(MAX_BACKOFF_MS, 1000L shl minOf(attempt, 4))
            } else {
                1000L
            }
            if (role == Role.CLIENT) {
                _state.value =
                    TransportState.Error("Bağlantı yok — ${delayMs / 1000}s sonra yeniden denenecek")
            }
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                break
            }
        }
        _state.value = TransportState.Idle
    }

    /** Okuyucu döngüsü supervisor iş parçacığında koşar; dönüş = kopuş. */
    private fun runConnection(s: BluetoothSocket) {
        socket = s
        txQueue.clear()
        val address = s.remoteDevice?.address ?: "00:00:00:00:00:00"
        val peer = s.remoteDevice?.name ?: address
        _state.value = TransportState.Connected(peer, address)

        val writer = Thread({
            try {
                val out = s.outputStream
                while (true) {
                    val bytes = txQueue.take()
                    if (bytes.isEmpty()) break // kapatma sinyali
                    out.write(bytes)
                }
            } catch (_: Exception) {
            }
        }, "bt-writer").also { it.start() }

        val parser = FrameParser { frame -> _incoming.tryEmit(frame) }
        val buf = ByteArray(4096)
        try {
            val input = s.inputStream
            while (shouldRun.get()) {
                val n = input.read(buf)
                if (n < 0) break
                parser.feed(buf, n)
            }
        } catch (_: Exception) {
        } finally {
            runCatching { s.close() }
            socket = null
            txQueue.clear()
            txQueue.offer(ByteArray(0)) // yazıcıyı uyandır
            runCatching { writer.join(500) }
        }
    }

    override fun send(frame: LinkFrame) {
        if (_state.value is TransportState.Connected) {
            txQueue.offer(LinkProtocol.encode(frame))
        }
    }

    /**
     * Toplu veri için geri baskılı gönderim: kuyrukta yer açılana kadar bekler.
     * Dosya aktarımında düşen tek bir parça bozuk dosya demek olduğu için
     * [send]'in "doluysa at" davranışı kullanılamaz.
     */
    override fun sendBlocking(frame: LinkFrame, timeoutMs: Long): Boolean {
        if (_state.value !is TransportState.Connected) return false
        val bytes = LinkProtocol.encode(frame)
        return runCatching {
            txQueue.offer(bytes, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
    }

    override fun close() {
        shouldRun.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { socket?.close() }
        socket = null
        txQueue.clear()
        txQueue.offer(ByteArray(0))
        supervisor?.let { t ->
            t.interrupt() // geri çekilme uykusunu böl
            runCatching { t.join(1000) }
        }
        supervisor = null
        _state.value = TransportState.Idle
    }
}
