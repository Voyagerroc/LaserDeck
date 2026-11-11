package com.voyagerroc.laserdeck.ptt.chat

/**
 * Bir telsiz mesajı.
 *
 * [state] bilerek gizlenmiyor: telsizde mesajın karşıya ulaşıp ulaşmadığı
 * belirsizdir, bu belirsizlik arayüzde de görünür (bkz. TransmissionRule).
 */
data class ChatMessage(
    /** Gönderenin ürettiği 32-bit kimlik; yineleme ayıklama ve onay eşleştirme. */
    val id: Int,
    val outgoing: Boolean,
    val text: String,
    val timestamp: Long,
    val state: State,
    /**
     * Fotograf mesajlarinda dosyanin yolu ya da MediaStore adresi.
     * Metin mesajlarinda null.
     */
    val attachment: String? = null
) {
    enum class State {
        /** Hat yok — sırada bekliyor, bağlantı gelince kendiliğinden gidecek. */
        QUEUED,

        /** Sokete yazıldı, karşıdan onay gelmedi. */
        SENT,

        /** Karşı taraf onayladı. */
        DELIVERED,

        /** Gelen mesaj (durum bilgisi taşımaz). */
        INCOMING;

        companion object {
            fun parse(name: String?): State =
                values().firstOrNull { it.name == name } ?: QUEUED
        }
    }
}
