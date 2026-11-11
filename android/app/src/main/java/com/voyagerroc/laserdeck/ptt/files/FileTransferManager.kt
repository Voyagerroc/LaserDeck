package com.voyagerroc.laserdeck.ptt.files

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.voyagerroc.laserdeck.ptt.protocol.LinkFrame
import com.voyagerroc.laserdeck.ptt.protocol.LinkProtocol
import com.voyagerroc.laserdeck.ptt.transport.FrameTransport
import com.voyagerroc.laserdeck.ptt.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Fotoğraf/dosya aktarımı — mevcut Bluetooth hattı üzerinden.
 *
 * ## "Kalite asla düşmesin"
 * Dosya yeniden sıkıştırılmıyor, ölçeklenmiyor, hiçbir şekilde işlenmiyor:
 * kaynağın BAYTLARI olduğu gibi gidiyor. Karşı tarafta SHA-256 özeti
 * doğrulanıyor — bit birebir aynılık iddia edilecekse ispatlanmalı.
 *
 * ## Hız
 * Bluetooth Classic pratikte ~250 kbps taşıyor. 4 MB'lık bir fotoğraf ~2
 * dakika sürer ve bu radyonun kendi sınırı; yazılımla aşılamaz. Aktarım bu
 * yüzden arka planda ilerliyor ve kesilirse iptal ediliyor.
 *
 * ## Neden [FrameTransport.sendBlocking]
 * Normal [FrameTransport.send] kuyruk doluyken çerçeveyi DÜŞÜRÜR. Seste doğru,
 * dosyada felaket: düşen tek parça bozuk dosya demek. Geri baskılı gönderim
 * doğal olarak hattın hızına yavaşlıyor.
 *
 * ## Konuşma önceliği
 * Ses aktarımı sürerken parça göndermek durur. İkisi aynı hattı paylaşıyor ve
 * toplu veri, sesi kesintiye uğratır.
 */
class FileTransferManager(
    private val context: Context,
    private val transport: FrameTransport,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val onProgress: (Transfer) -> Unit = {},
    private val onReceived: (Transfer) -> Unit = {}
) {
    companion object {
        private const val SEND_TIMEOUT_MS = 8_000L
        private const val AUDIO_POLL_MS = 200L
        /** Karşı taraf bu süre içinde kabul etmezse vazgeç. */
        private const val ACCEPT_TIMEOUT_MS = 15_000L
        private const val DIR_NAME = "LaserDeck"

        const val CANCEL_USER = 0
        const val CANCEL_ERROR = 1
        const val CANCEL_LINK = 2
        const val CANCEL_CORRUPT = 3
    }

    data class Transfer(
        val id: Int,
        val name: String,
        val totalBytes: Long,
        val doneBytes: Long,
        val outgoing: Boolean,
        val state: State,
        /** Tamamlanan gelen dosyanın yolu. */
        val path: String? = null
    ) {
        enum class State { OFFERED, RUNNING, COMPLETE, FAILED, CANCELLED }

        val percent: Int
            get() = if (totalBytes <= 0) 0
            else ((doneBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
    }

    private val lock = Any()
    private var outgoing: OutgoingJob? = null
    private var incoming: IncomingJob? = null

    @Volatile private var audioActive = false

    private class OutgoingJob(
        val id: Int,
        val uri: Uri,
        val name: String,
        val size: Long,
        var sent: Long = 0,
        @Volatile var accepted: Boolean = false,
        @Volatile var cancelled: Boolean = false,
        var job: Job? = null
    )

    private class IncomingJob(
        val id: Int,
        val name: String,
        val size: Long,
        val sha: ByteArray,
        val temp: File,
        val out: FileOutputStream,
        val digest: MessageDigest,
        var received: Long = 0,
        var nextSeq: Long = 0
    )

    init {
        scope.launch(Dispatchers.IO) {
            transport.incoming.collect { frame ->
                runCatching { handleIncoming(frame) }
                    .onFailure { log("Dosya çerçevesi atlandı: ${it.message}") }
            }
        }
        scope.launch(Dispatchers.IO) {
            transport.state.collect { s ->
                if (s !is TransportState.Connected) abortAll(CANCEL_LINK)
            }
        }
    }

    /** Ses akarken toplu veri durur: aynı hat. */
    fun setAudioActive(active: Boolean) {
        audioActive = active
    }

    // ---------- gönderme ----------

    /** Seçilen fotoğrafı gönderir. Zaten bir aktarım varsa false döner. */
    fun sendFile(uri: Uri): Boolean {
        if (transport.state.value !is TransportState.Connected) return false
        synchronized(lock) {
            if (outgoing != null) return false
        }
        val meta = readMeta(uri) ?: return false
        if (meta.second <= 0L) return false

        val job = OutgoingJob(
            id = Random.nextInt(),
            uri = uri,
            name = meta.first,
            size = meta.second
        )
        synchronized(lock) { outgoing = job }
        emit(job, Transfer.State.OFFERED)

        job.job = scope.launch(Dispatchers.IO) { runOutgoing(job) }
        return true
    }

    private fun runOutgoing(job: OutgoingJob) {
        val sha = runCatching { digestOf(job.uri) }.getOrNull()
        if (sha == null) {
            log("Dosya okunamadı: ${job.name}")
            finishOutgoing(job, Transfer.State.FAILED)
            return
        }

        val nameBytes = job.name.toByteArray(Charsets.UTF_8).let {
            if (it.size > 200) it.copyOf(200) else it
        }
        val offer = ByteArray(4 + 4 + 32 + 1 + nameBytes.size)
        writeInt(offer, 0, job.id)
        writeInt(offer, 4, job.size.toInt())
        sha.copyInto(offer, 8)
        offer[40] = nameBytes.size.toByte()
        nameBytes.copyInto(offer, 41)
        transport.send(LinkFrame(LinkProtocol.TYPE_FILE_OFFER, 0, offer))

        // Kabul bekle.
        val deadline = System.currentTimeMillis() + ACCEPT_TIMEOUT_MS
        while (!job.accepted && !job.cancelled && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        if (!job.accepted || job.cancelled) {
            log("Aktarım kabul edilmedi: ${job.name}")
            finishOutgoing(job, Transfer.State.CANCELLED)
            return
        }

        emit(job, Transfer.State.RUNNING)
        val ok = runCatching { streamChunks(job) }.getOrElse {
            log("Aktarım hatası: ${it.message}")
            false
        }

        if (!ok || job.cancelled) {
            sendCancel(job.id, CANCEL_ERROR)
            finishOutgoing(job, if (job.cancelled) Transfer.State.CANCELLED else Transfer.State.FAILED)
            return
        }
        transport.send(LinkFrame(LinkProtocol.TYPE_FILE_DONE, 0, intBytes(job.id)))
        finishOutgoing(job, Transfer.State.COMPLETE)
        log("Fotoğraf gönderildi: ${job.name}")
    }

    private fun streamChunks(job: OutgoingJob): Boolean {
        val input = context.contentResolver.openInputStream(job.uri) ?: return false
        input.use { stream ->
            val buf = ByteArray(LinkProtocol.MAX_CHUNK_BYTES)
            var seq = 0L
            var lastEmit = 0L
            while (!job.cancelled) {
                // Konuşma varsa bekle: toplu veri sesi keser.
                while (audioActive && !job.cancelled) Thread.sleep(AUDIO_POLL_MS)
                if (job.cancelled) return false

                val n = stream.read(buf)
                if (n <= 0) break

                val payload = ByteArray(8 + n)
                writeInt(payload, 0, job.id)
                writeInt(payload, 4, seq.toInt())
                buf.copyInto(payload, 8, 0, n)

                // Geri baskılı: kuyrukta yer açılana kadar bekler. Düşen bir
                // parça bozuk dosya demek olurdu.
                if (!transport.sendBlocking(
                        LinkFrame(LinkProtocol.TYPE_FILE_CHUNK, 0, payload),
                        SEND_TIMEOUT_MS
                    )
                ) {
                    return false
                }
                seq++
                job.sent += n

                val now = System.currentTimeMillis()
                if (now - lastEmit > 300) {
                    lastEmit = now
                    emit(job, Transfer.State.RUNNING)
                }
            }
        }
        return !job.cancelled
    }

    fun cancelOutgoing() {
        val job = synchronized(lock) { outgoing } ?: return
        job.cancelled = true
        sendCancel(job.id, CANCEL_USER)
    }

    private fun finishOutgoing(job: OutgoingJob, state: Transfer.State) {
        synchronized(lock) { if (outgoing === job) outgoing = null }
        emit(job, state)
    }

    // ---------- alma ----------

    private fun handleIncoming(frame: LinkFrame) {
        when (frame.type) {
            LinkProtocol.TYPE_FILE_OFFER -> onOffer(frame.payload)

            LinkProtocol.TYPE_FILE_ACCEPT -> {
                val id = LinkProtocol.readMsgId(frame.payload) ?: return
                synchronized(lock) { outgoing }?.let { if (it.id == id) it.accepted = true }
            }

            LinkProtocol.TYPE_FILE_CHUNK -> onChunk(frame.payload)

            LinkProtocol.TYPE_FILE_DONE -> {
                val id = LinkProtocol.readMsgId(frame.payload) ?: return
                finishIncoming(id)
            }

            LinkProtocol.TYPE_FILE_CANCEL -> {
                val id = LinkProtocol.readMsgId(frame.payload) ?: return
                synchronized(lock) { outgoing }?.let { if (it.id == id) it.cancelled = true }
                abortIncoming(id, Transfer.State.CANCELLED)
            }
        }
    }

    private fun onOffer(payload: ByteArray) {
        if (payload.size < 41) return
        val id = LinkProtocol.readMsgId(payload) ?: return
        val size = readInt(payload, 4).toLong() and 0xFFFFFFFFL
        val sha = payload.copyOfRange(8, 40)
        val nameLen = payload[40].toInt() and 0xFF
        if (payload.size < 41 + nameLen) return
        val name = String(payload, 41, nameLen, Charsets.UTF_8).ifBlank { "foto.jpg" }

        // Aynı anda tek aktarım: ikinci teklif reddedilir.
        synchronized(lock) { if (incoming != null) return sendCancel(id, CANCEL_ERROR) }

        val temp = runCatching {
            File(context.cacheDir, "aktarim").apply { mkdirs() }
                .resolve("$id.part")
        }.getOrNull() ?: return sendCancel(id, CANCEL_ERROR)

        val out = runCatching { FileOutputStream(temp) }.getOrNull()
            ?: return sendCancel(id, CANCEL_ERROR)

        val job = IncomingJob(
            id = id, name = sanitize(name), size = size, sha = sha,
            temp = temp, out = out, digest = MessageDigest.getInstance("SHA-256")
        )
        synchronized(lock) { incoming = job }
        emitIncoming(job, Transfer.State.RUNNING)
        transport.send(LinkFrame(LinkProtocol.TYPE_FILE_ACCEPT, 0, intBytes(id)))
        log("Fotoğraf alınıyor: ${job.name}")
    }

    private fun onChunk(payload: ByteArray) {
        if (payload.size <= 8) return
        val id = LinkProtocol.readMsgId(payload) ?: return
        val job = synchronized(lock) { incoming } ?: return
        if (job.id != id) return
        val seq = readInt(payload, 4).toLong() and 0xFFFFFFFFL
        // RFCOMM sıralı teslim garanti ediyor; sıra atlamışsa bir şey bozulmuş
        // demektir ve dosyayı sessizce yanlış yazmaktansa iptal etmek doğru.
        if (seq != job.nextSeq) {
            log("Aktarım sırası bozuldu (${job.nextSeq} beklenirken $seq)")
            sendCancel(id, CANCEL_CORRUPT)
            abortIncoming(id, Transfer.State.FAILED)
            return
        }
        job.nextSeq++

        val data = payload.copyOfRange(8, payload.size)
        runCatching {
            job.out.write(data)
            job.digest.update(data)
            job.received += data.size
        }.onFailure {
            sendCancel(id, CANCEL_ERROR)
            abortIncoming(id, Transfer.State.FAILED)
            return
        }
        if (job.received % (LinkProtocol.MAX_CHUNK_BYTES * 8) < data.size) {
            emitIncoming(job, Transfer.State.RUNNING)
        }
    }

    private fun finishIncoming(id: Int) {
        val job = synchronized(lock) { incoming } ?: return
        if (job.id != id) return
        runCatching { job.out.flush(); job.out.close() }

        // Bit birebir aynılık ispatlanmalı: özet tutmuyorsa dosya çöp.
        val actual = job.digest.digest()
        if (!actual.contentEquals(job.sha)) {
            log("Aktarım doğrulanamadı — dosya atıldı")
            runCatching { job.temp.delete() }
            synchronized(lock) { incoming = null }
            emitIncoming(job, Transfer.State.FAILED)
            return
        }

        val path = runCatching { store(job) }.getOrNull()
        runCatching { job.temp.delete() }
        synchronized(lock) { incoming = null }

        if (path == null) {
            emitIncoming(job, Transfer.State.FAILED)
            return
        }
        val done = Transfer(
            id = job.id, name = job.name, totalBytes = job.size,
            doneBytes = job.size, outgoing = false,
            state = Transfer.State.COMPLETE, path = path
        )
        onProgress(done)
        onReceived(done)
        log("Fotoğraf alındı: ${job.name}")
    }

    /**
     * Kaydetme yeri. API 29+ galeriye (izin gerekmez); altında uygulamaya özel
     * dış klasöre — orada da izin gerekmez, yalnızca galeride görünmez.
     */
    private fun store(job: IncomingJob): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, job.name)
                put(MediaStore.Images.Media.MIME_TYPE, mimeFor(job.name))
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$DIR_NAME"
                )
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { out ->
                job.temp.inputStream().use { it.copyTo(out) }
            } ?: return null
            return uri.toString()
        }
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), DIR_NAME
        ).apply { mkdirs() }
        val dest = File(dir, job.name)
        job.temp.inputStream().use { input ->
            FileOutputStream(dest).use { input.copyTo(it) }
        }
        return dest.absolutePath
    }

    private fun abortIncoming(id: Int, state: Transfer.State) {
        val job = synchronized(lock) { incoming } ?: return
        if (job.id != id) return
        runCatching { job.out.close() }
        runCatching { job.temp.delete() }
        synchronized(lock) { incoming = null }
        emitIncoming(job, state)
    }

    private fun abortAll(reason: Int) {
        synchronized(lock) { outgoing }?.let { it.cancelled = true }
        synchronized(lock) { incoming }?.let { abortIncoming(it.id, Transfer.State.CANCELLED) }
        if (reason == CANCEL_LINK) log("Hat koptu, aktarım durdu")
    }

    // ---------- yardımcılar ----------

    private fun readMeta(uri: Uri): Pair<String, Long>? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (!c.moveToFirst()) return@use null
            val name = if (nameIdx >= 0) c.getString(nameIdx) else "foto.jpg"
            val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else -1L
            (name ?: "foto.jpg") to size
        }
    }.getOrNull()

    private fun digestOf(uri: Uri): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        } ?: throw IllegalStateException("akış açılamadı")
        return md.digest()
    }

    private fun sendCancel(id: Int, reason: Int) {
        transport.send(
            LinkFrame(
                LinkProtocol.TYPE_FILE_CANCEL, 0,
                intBytes(id) + byteArrayOf(reason.toByte())
            )
        )
    }

    private fun emit(job: OutgoingJob, state: Transfer.State) {
        onProgress(
            Transfer(job.id, job.name, job.size, job.sent, true, state)
        )
    }

    private fun emitIncoming(job: IncomingJob, state: Transfer.State) {
        onProgress(
            Transfer(job.id, job.name, job.size, job.received, false, state)
        )
    }

    /** Karşı taraftan gelen ad dosya sistemine yön veremesin. */
    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "foto.jpg" }

    private fun mimeFor(name: String): String = when {
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".webp", true) -> "image/webp"
        name.endsWith(".heic", true) -> "image/heic"
        else -> "image/jpeg"
    }

    private fun intBytes(v: Int) = ByteArray(4).also { writeInt(it, 0, v) }

    private fun writeInt(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v and 0xFF).toByte()
        dst[off + 1] = ((v shr 8) and 0xFF).toByte()
        dst[off + 2] = ((v shr 16) and 0xFF).toByte()
        dst[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun readInt(src: ByteArray, off: Int): Int =
        (src[off].toInt() and 0xFF) or
                ((src[off + 1].toInt() and 0xFF) shl 8) or
                ((src[off + 2].toInt() and 0xFF) shl 16) or
                ((src[off + 3].toInt() and 0xFF) shl 24)

    fun release() {
        abortAll(CANCEL_USER)
    }
}
