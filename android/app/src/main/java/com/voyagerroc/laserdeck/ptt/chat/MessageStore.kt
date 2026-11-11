package com.voyagerroc.laserdeck.ptt.chat

import org.json.JSONObject
import java.io.File

/**
 * Cihaz başına sohbet geçmişi — satır başına bir JSON nesnesi.
 *
 * Neden veritabanı değil: uygulamanın tek bağımlılığı coroutines ve APK 1 MB.
 * [MAX_MESSAGES] mesajlık tavanla dosya birkaç on KB'de kalıyor, o yüzden
 * güncellemede dosyayı baştan yazmak yeterince ucuz ve yerinde güncellemeden
 * çok daha az hataya açık.
 *
 * Dosya adı MAC adresinden türetilir: cihazın adı değişse de geçmiş kaybolmaz.
 *
 * Tüm genel yöntemler senkronize: iki yazma çakışırsa ikisi de aynı geçici
 * dosyayı sıfırlayıp yazıyor, sonra ikinci renameTo kaynağı bulamayıp gerçek
 * geçmiş dosyasının ÜZERİNE atomik olmayan biçimde yazıyordu. Çağrılar yine de
 * arka plan iş parçacığından yapılmalıdır (disk işi).
 */
class MessageStore(private val baseDir: File) {

    companion object {
        const val MAX_MESSAGES = 500
        private const val DIR_NAME = "sohbet"
    }

    private fun fileFor(peerAddress: String): File {
        val safe = peerAddress.filter { it.isLetterOrDigit() }.ifEmpty { "bilinmeyen" }
        return File(File(baseDir, DIR_NAME).apply { mkdirs() }, "$safe.jsonl")
    }

    /** Bozuk satırlar atlanır: yarım yazılmış tek bir satır tüm geçmişi silmemeli. */
    @Synchronized
    fun load(peerAddress: String): List<ChatMessage> {
        val f = fileFor(peerAddress)
        if (!f.exists()) return emptyList()
        return runCatching {
            f.readLines().mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                runCatching {
                    val o = JSONObject(line)
                    ChatMessage(
                        id = o.getInt("id"),
                        outgoing = o.getBoolean("out"),
                        text = o.getString("text"),
                        timestamp = o.getLong("ts"),
                        state = ChatMessage.State.parse(o.optString("state")),
                        attachment = o.optString("att").ifBlank { null }
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(peerAddress: String, messages: List<ChatMessage>) {
        val f = fileFor(peerAddress)
        val tail = if (messages.size > MAX_MESSAGES) {
            messages.subList(messages.size - MAX_MESSAGES, messages.size)
        } else {
            messages
        }
        val text = buildString {
            tail.forEach { m ->
                append(
                    JSONObject()
                        .put("id", m.id)
                        .put("out", m.outgoing)
                        .put("text", m.text)
                        .put("ts", m.timestamp)
                        .put("state", m.state.name)
                        .put("att", m.attachment ?: "")
                        .toString()
                )
                append('\n')
            }
        }
        // Önce geçici dosyaya yaz, sonra taşı: yazma yarıda kesilirse
        // (süreç öldü, disk doldu) eski geçmiş olduğu gibi kalır.
        runCatching {
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                f.writeText(text)
                tmp.delete()
            }
        }
    }

    @Synchronized
    fun clear(peerAddress: String) {
        runCatching { fileFor(peerAddress).delete() }
    }
}
