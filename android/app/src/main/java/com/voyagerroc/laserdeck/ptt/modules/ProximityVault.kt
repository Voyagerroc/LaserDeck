package com.voyagerroc.laserdeck.ptt.modules

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.voyagerroc.laserdeck.ptt.protocol.LinkFrame
import com.voyagerroc.laserdeck.ptt.protocol.LinkProtocol
import com.voyagerroc.laserdeck.ptt.transport.FrameTransport
import com.voyagerroc.laserdeck.ptt.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Yakınlık kasası — yalnızca iki telefon bir aradayken açılan dosyalar.
 *
 * ## Fikir
 * Şifreleme anahtarı hiçbir telefonda BÜTÜN olarak durmuyor. Her cihaz
 * rastgele 32 baytlık kendi yarısını saklıyor; asıl anahtar iki yarının
 * özetinden türetiliyor:
 *
 *     anahtar = SHA-256( küçük_yarı ‖ büyük_yarı )
 *
 * Yarılar sıralanarak birleştiriliyor ki iki taraf da aynı anahtarı üretsin.
 * Telefonun tek başına çalınması dosyaları açmaya yetmez — diğer telefon da
 * gerekir, üstelik **menzil içinde** olması gerekir.
 *
 * Nükleer fırlatma anahtarlarındaki "iki kişi kuralı"nın fizikle uygulanmış
 * hali: yazılımla taklit edilemeyen bir koşul, mesafe.
 *
 * ## Gerçek şifreleme
 * AES-256-GCM kullanılıyor; her dosya için rastgele başlangıç vektörü ve
 * kimlik doğrulama etiketi üretiliyor. Kilitliyken dosyalar gizlenmiyor,
 * gerçekten **okunamıyor** — dosya adları bile şifreli gövdenin içinde, o
 * yüzden kilitliyken listelenemiyorlar.
 *
 */
class ProximityVault(
    private val context: Context,
    private val transport: FrameTransport,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    /** Karşı taraf menzil içinde mi — radar bunu besler. */
    private val proximityOk: () -> Boolean = { true }
) {
    companion object {
        private const val PREFS = "kasa"
        private const val KEY_HALF = "yarim"
        private const val DIR = "kasa"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val HALF_BYTES = 32

        /** Kilit bu süre sonunda kendiliğinden kapanır. */
        private const val AUTO_LOCK_MS = 5 * 60 * 1000L

        /** Karşı taraf yanıt vermezse açma isteği düşer. */
        private const val UNLOCK_TIMEOUT_MS = 8_000L
    }

    enum class State { LOCKED, UNLOCKING, UNLOCKED, DENIED, NO_PEER }

    data class Entry(val id: String, val name: String, val bytes: Long)

    private val _state = MutableStateFlow(State.LOCKED)
    val state: StateFlow<State> = _state

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, DIR).apply { mkdirs() }
    private val random = SecureRandom()

    /** Yalnızca bellekte; kilit kapanınca siliniyor. */
    @Volatile private var sessionKey: ByteArray? = null
    private var unlockJob: Job? = null
    private var autoLockJob: Job? = null

    /** Bu cihazın kalıcı anahtar yarısı. */
    private val myHalf: ByteArray by lazy {
        val stored = prefs.getString(KEY_HALF, null)
        if (stored != null) {
            runCatching { Base64.decode(stored, Base64.NO_WRAP) }.getOrNull()
                ?.takeIf { it.size == HALF_BYTES }
                ?: newHalf()
        } else {
            newHalf()
        }
    }

    private fun newHalf(): ByteArray {
        val half = ByteArray(HALF_BYTES).also { random.nextBytes(it) }
        prefs.edit().putString(KEY_HALF, Base64.encodeToString(half, Base64.NO_WRAP)).apply()
        return half
    }

    init {
        scope.launch(Dispatchers.IO) {
            transport.incoming.collect { frame ->
                runCatching { handle(frame) }
                    .onFailure { log("Kasa çerçevesi atlandı: ${it.message}") }
            }
        }
        scope.launch(Dispatchers.IO) {
            transport.state.collect { s ->
                if (s !is TransportState.Connected) {
                    // Hat koptu: kasa derhal kilitlenir. Yakınlık koşulu artık
                    // doğrulanamıyor, açık bırakmak kuralı delerdi.
                    lock()
                    _state.value = State.NO_PEER
                } else if (_state.value == State.NO_PEER) {
                    _state.value = State.LOCKED
                }
            }
        }
    }

    // ---------- kilit ----------

    /** Karşı telefondan anahtar yarısını ister. */
    fun unlock() {
        if (_state.value == State.UNLOCKED) return
        if (transport.state.value !is TransportState.Connected) {
            _state.value = State.NO_PEER
            return
        }
        _state.value = State.UNLOCKING
        transport.send(LinkFrame(LinkProtocol.TYPE_VAULT_REQUEST, 0, ByteArray(0)))

        unlockJob?.cancel()
        unlockJob = scope.launch {
            delay(UNLOCK_TIMEOUT_MS)
            if (_state.value == State.UNLOCKING) {
                _state.value = State.DENIED
                log("Kasa: karşı taraf yanıt vermedi")
            }
        }
    }

    fun lock() {
        unlockJob?.cancel()
        autoLockJob?.cancel()
        // Acilmis dosyalarin COZULMUS kopyalari silinmeli. Kalirsa kasa
        // kilitlendikten sonra bile duz metin diskte duruyor ve modulun tum
        // iddiasi ("tek telefon yetmez") cokuyordu.
        wipeOpened()
        // Anahtarı bellekten sil; kalıntı bırakmamak için üzerine yaz.
        sessionKey?.fill(0)
        sessionKey = null
        _entries.value = emptyList()
        if (_state.value != State.NO_PEER) _state.value = State.LOCKED
    }

    private fun handle(frame: LinkFrame) {
        when (frame.type) {
            LinkProtocol.TYPE_VAULT_REQUEST -> {
                // Yarıyı YALNIZCA yakınlık koşulu sağlanıyorsa ver. Bağlantının
                // varlığı yetmez: menzilin dışındaki bir cihaz da bağlı olabilir.
                if (!proximityOk()) {
                    log("Kasa: yakınlık koşulu sağlanmadı, anahtar verilmedi")
                    return
                }
                transport.send(LinkFrame(LinkProtocol.TYPE_VAULT_HALF, 0, myHalf))
            }

            LinkProtocol.TYPE_VAULT_HALF -> {
                if (frame.payload.size != HALF_BYTES) return
                if (_state.value != State.UNLOCKING) return
                unlockJob?.cancel()
                sessionKey = deriveKey(myHalf, frame.payload)
                _state.value = State.UNLOCKED
                refresh()
                armAutoLock()
                log("Kasa açıldı")
            }
        }
    }

    /**
     * İki yarıyı SIRALAYARAK birleştirir: hangi telefonda çalıştığı fark
     * etmeksizin aynı anahtar çıksın.
     */
    private fun deriveKey(a: ByteArray, b: ByteArray): ByteArray {
        val first: ByteArray
        val second: ByteArray
        if (compare(a, b) <= 0) {
            first = a; second = b
        } else {
            first = b; second = a
        }
        val md = MessageDigest.getInstance("SHA-256")
        md.update(first)
        md.update(second)
        return md.digest()
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return 0
    }

    private fun armAutoLock() {
        autoLockJob?.cancel()
        autoLockJob = scope.launch {
            delay(AUTO_LOCK_MS)
            log("Kasa kendiliğinden kilitlendi")
            lock()
        }
    }

    // ---------- dosyalar ----------

    /**
     * Dosyayı kasaya şifreleyerek ekler. Kasa açık olmalı.
     *
     * ARKA PLANDA calisir: sifreleme dosyanin tamamini bellege aliyor ve ana
     * is parcaciginda buyuk bir dosya secilirse uygulama donuyordu.
     */
    fun addFile(uri: Uri, displayName: String) {
        val key = sessionKey ?: return
        scope.launch(Dispatchers.IO) { addFileBlocking(uri, displayName, key) }
    }

    private fun addFileBlocking(uri: Uri, displayName: String, key: ByteArray): Boolean {
        return runCatching {
            val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return false
            val nameBytes = displayName.toByteArray(Charsets.UTF_8)
            // Dosya adı da şifreli gövdenin içinde: kilitliyken kasada NE
            // olduğu bile görünmesin.
            val plain = ByteArray(2 + nameBytes.size + data.size)
            plain[0] = (nameBytes.size and 0xFF).toByte()
            plain[1] = ((nameBytes.size shr 8) and 0xFF).toByte()
            nameBytes.copyInto(plain, 2)
            data.copyInto(plain, 2 + nameBytes.size)

            val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, iv)
            )
            val sealed = cipher.doFinal(plain)

            val out = File(dir, "${System.currentTimeMillis()}_${random.nextInt(9999)}.kasa")
            out.outputStream().use { s -> s.write(iv); s.write(sealed) }
            refresh()
            true
        }.getOrDefault(false)
    }

    private fun wipeOpened() {
        runCatching { File(context.cacheDir, "kasa-acik").deleteRecursively() }
    }

    /** Şifreyi çözüp geçici bir dosyaya yazar ve yolunu döner. */
    fun openFile(id: String): File? {
        val key = sessionKey ?: return null
        // Disaridan gelen kimlik dosya sistemine yon veremesin.
        if (id.contains('/') || id.contains('\\') || id.contains("..")) return null
        return runCatching {
            val src = File(dir, id)
            if (!src.exists()) return null
            val all = src.readBytes()
            if (all.size <= IV_BYTES) return null
            val iv = all.copyOfRange(0, IV_BYTES)
            val body = all.copyOfRange(IV_BYTES, all.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, iv)
            )
            val plain = cipher.doFinal(body)
            val nameLen = (plain[0].toInt() and 0xFF) or ((plain[1].toInt() and 0xFF) shl 8)
            val name = String(plain, 2, nameLen, Charsets.UTF_8)
            val data = plain.copyOfRange(2 + nameLen, plain.size)

            val temp = File(context.cacheDir, "kasa-acik").apply { mkdirs() }
                .resolve(name.filter { it.isLetterOrDigit() || it == '.' || it == '_' })
            temp.writeBytes(data)
            temp
        }.getOrNull()
    }

    fun deleteFile(id: String) {
        runCatching { File(dir, id).delete() }
        refresh()
    }

    /**
     * Kasa açıkken içeriği listeler; kilitliyse liste boştur.
     *
     * Not: GCM etiketi tum mesaji kapsadigi icin adi okumak dosyanin tamamini
     * cozmeyi gerektiriyor. Bu, cok buyuk dosyalarda pahali; ileride ad ayri
     * bir muhurlu alan olarak saklanmali.
     */
    private fun refresh() {
        val key = sessionKey
        if (key == null) {
            _entries.value = emptyList()
            return
        }
        scope.launch(Dispatchers.IO) {
            val list = runCatching {
                dir.listFiles()?.mapNotNull { f ->
                    val name = peekName(f, key) ?: return@mapNotNull null
                    Entry(f.name, name, f.length())
                }?.sortedBy { it.name } ?: emptyList()
            }.getOrDefault(emptyList())
            _entries.value = list
        }
    }

    /** Adi cozmek icin dosyanin TAMAMI cozulur — GCM etiketi butun mesaji kapsiyor. */
    private fun peekName(f: File, key: ByteArray): String? = runCatching {
        val all = f.readBytes()
        if (all.size <= IV_BYTES) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, all.copyOfRange(0, IV_BYTES))
        )
        val plain = cipher.doFinal(all.copyOfRange(IV_BYTES, all.size))
        val nameLen = (plain[0].toInt() and 0xFF) or ((plain[1].toInt() and 0xFF) shl 8)
        String(plain, 2, nameLen, Charsets.UTF_8)
    }.getOrNull()

    /** Kasada kaç dosya var — kilitliyken de sayılabilir, içerik görünmez. */
    fun fileCount(): Int = runCatching { dir.listFiles()?.size ?: 0 }.getOrDefault(0)

    fun release() {
        lock()
        wipeOpened()
    }
}
