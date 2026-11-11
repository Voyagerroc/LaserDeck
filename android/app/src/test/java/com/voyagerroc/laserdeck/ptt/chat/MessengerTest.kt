package com.voyagerroc.laserdeck.ptt.chat

import com.voyagerroc.laserdeck.ptt.protocol.LinkFrame
import com.voyagerroc.laserdeck.ptt.protocol.LinkProtocol
import com.voyagerroc.laserdeck.ptt.transport.FrameTransport
import com.voyagerroc.laserdeck.ptt.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Mesajlaşma mantığının davranış testi: kuyruk, teslim onayı, yineleme ayıklama
 * ve kalıcılık gerçekten çalıştırılarak doğrulanır.
 *
 * Taşıyıcı sahte; Bluetooth yok. Zamanlama belirsizliği olmasın diye Messenger'a
 * Unconfined dağıtıcı verilir: başlatılan işler ilk askıya alınmaya kadar
 * çağıran iş parçacığında satır içi koşar.
 */
class MessengerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class FakeTransport : FrameTransport {
        val sent = mutableListOf<LinkFrame>()
        private val _state = MutableStateFlow<TransportState>(TransportState.Idle)
        override val state: StateFlow<TransportState> = _state
        private val _incoming = MutableSharedFlow<LinkFrame>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        override val incoming: SharedFlow<LinkFrame> = _incoming

        override fun startServer() = Unit
        override fun send(frame: LinkFrame) { sent += frame }
        override fun sendBlocking(frame: LinkFrame, timeoutMs: Long): Boolean {
            sent += frame
            return true
        }
        override fun close() { _state.value = TransportState.Idle }

        fun connect(address: String = "AA:BB:CC:DD:EE:FF") {
            _state.value = TransportState.Connected("Test Telefon", address)
        }

        fun drop() { _state.value = TransportState.Idle }

        fun deliver(frame: LinkFrame) {
            check(_incoming.tryEmit(frame)) { "test akışı doldu" }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var transport: FakeTransport
    private lateinit var store: MessageStore
    private lateinit var messenger: Messenger
    private val incomingSeen = mutableListOf<ChatMessage>()

    private fun build(): Messenger {
        transport = FakeTransport()
        store = MessageStore(temp.root)
        return Messenger(
            transport = transport,
            store = store,
            scope = scope,
            onIncoming = { incomingSeen += it },
            io = Dispatchers.Unconfined
        ).also { messenger = it }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun textFrom(peerId: Int, body: String) =
        LinkFrame(LinkProtocol.TYPE_TEXT, 1, LinkProtocol.encodeText(peerId, body))


    /**
     * Diske yazma tek bir arka plan iş parçacığında sıraya giriyor; testin
     * dosyayı okumadan önce sıranın boşalmasını beklemesi gerekiyor.
     */
    private fun awaitSave() {
        assertTrue("diske yazma suresi doldu", messenger.awaitSavesForTest())
    }

    private fun outgoing() = messenger.messages.value.filter { it.outgoing }

    // ---------- kuyruk ----------

    @Test
    fun `message written with no link waits in the queue`() {
        build().bindPeer("AA:BB:CC:DD:EE:FF")

        assertEquals(Messenger.SendResult.OK, messenger.send("hat yokken yazildi"))

        assertEquals(1, outgoing().size)
        assertEquals(ChatMessage.State.QUEUED, outgoing()[0].state)
        assertTrue("hat yokken sokete yazilmamali", transport.sent.isEmpty())
    }

    @Test
    fun `queued message goes out by itself when the link returns`() {
        build().bindPeer("AA:BB:CC:DD:EE:FF")
        messenger.send("once kuyruk")

        transport.connect()

        assertEquals(ChatMessage.State.SENT, outgoing()[0].state)
        assertEquals(1, transport.sent.count { it.type == LinkProtocol.TYPE_TEXT })
    }

    @Test
    fun `no peer means nothing can be sent`() {
        build()
        assertEquals(Messenger.SendResult.NO_PEER, messenger.send("kime gidecek?"))
        assertTrue(messenger.messages.value.isEmpty())
    }

    @Test
    fun `blank and oversized text are refused`() {
        build().bindPeer("AA:BB:CC:DD:EE:FF")
        assertEquals(Messenger.SendResult.EMPTY, messenger.send("   "))
        assertEquals(
            Messenger.SendResult.TOO_LONG,
            messenger.send("a".repeat(Messenger.MAX_CHARS + 1))
        )
        assertTrue(messenger.messages.value.isEmpty())
    }


    @Test
    fun `text within the character limit but over the byte limit is refused`() {
        build().bindPeer("AA:BB:CC:DD:EE:FF")

        // 800 karakter sinirina uyuyor ama 3 baytlik karakterlerle cerceveye
        // sigmiyor; eskiden kabul edilip sonsuza dek KUYRUKTA kaliyordu.
        val cok = "中".repeat(Messenger.MAX_CHARS)
        assertTrue(cok.toByteArray(Charsets.UTF_8).size > LinkProtocol.MAX_TEXT_BYTES)
        assertEquals(Messenger.SendResult.TOO_LONG, messenger.send(cok))
        assertTrue(messenger.messages.value.isEmpty())
    }

    @Test
    fun `in-memory history is capped like the file`() {
        build().bindPeer("AA:BB:CC:DD:EE:FF")
        transport.connect()
        repeat(MessageStore.MAX_MESSAGES + 20) { messenger.send("mesaj $it") }

        assertEquals(MessageStore.MAX_MESSAGES, messenger.messages.value.size)
        assertEquals("mesaj ${MessageStore.MAX_MESSAGES + 19}", messenger.messages.value.last().text)
    }

    // ---------- teslim onayı ----------

    @Test
    fun `ack marks the message delivered`() {
        build().bindPeer("AA:BB:CC:DD:EE:FF")
        transport.connect()
        messenger.send("teslim edilecek")

        val sentFrame = transport.sent.first { it.type == LinkProtocol.TYPE_TEXT }
        val id = LinkProtocol.readMsgId(sentFrame.payload)!!
        assertEquals(ChatMessage.State.SENT, outgoing()[0].state)

        transport.deliver(LinkFrame(LinkProtocol.TYPE_TEXT_ACK, 9, LinkProtocol.encodeAck(id)))

        assertEquals(ChatMessage.State.DELIVERED, outgoing()[0].state)
    }

    @Test
    fun `reconnect does not resend an already delivered message`() {
        build().bindPeer("AA:BB:CC:DD:EE:FF")
        transport.connect()
        messenger.send("bir kez gitsin")
        val id = LinkProtocol.readMsgId(
            transport.sent.first { it.type == LinkProtocol.TYPE_TEXT }.payload
        )!!
        transport.deliver(LinkFrame(LinkProtocol.TYPE_TEXT_ACK, 9, LinkProtocol.encodeAck(id)))

        transport.drop()
        transport.connect()

        assertEquals(1, transport.sent.count { it.type == LinkProtocol.TYPE_TEXT })
    }

    @Test
    fun `undelivered message is resent after the link comes back`() {
        build().bindPeer("AA:BB:CC:DD:EE:FF")
        transport.connect()
        messenger.send("onay gelmedi")   // ACK yok

        transport.drop()
        transport.connect()

        assertEquals(2, transport.sent.count { it.type == LinkProtocol.TYPE_TEXT })
    }

    // ---------- gelen ----------

    @Test
    fun `incoming message is stored and acknowledged`() {
        build().bindPeer("AA:BB:CC:DD:EE:FF")
        transport.connect()

        transport.deliver(textFrom(4242, "merhaba"))

        val incoming = messenger.messages.value.filter { !it.outgoing }
        assertEquals(1, incoming.size)
        assertEquals("merhaba", incoming[0].text)
        assertEquals(ChatMessage.State.INCOMING, incoming[0].state)
        assertEquals(1, transport.sent.count { it.type == LinkProtocol.TYPE_TEXT_ACK })
        assertEquals(1, incomingSeen.size)
    }

    @Test
    fun `duplicate id is not written twice but is acknowledged again`() {
        build().bindPeer("AA:BB:CC:DD:EE:FF")
        transport.connect()

        transport.deliver(textFrom(4242, "tek kez yazilmali"))
        transport.deliver(textFrom(4242, "tek kez yazilmali"))

        assertEquals(1, messenger.messages.value.count { !it.outgoing })
        // Yineleme, karşı tarafın onayı almadığı anlamına gelir: onay tekrar gider.
        assertEquals(2, transport.sent.count { it.type == LinkProtocol.TYPE_TEXT_ACK })
        assertEquals(1, incomingSeen.size)
    }

    @Test
    fun `malformed text frame does not add a message`() {
        build().bindPeer("AA:BB:CC:DD:EE:FF")
        transport.connect()

        transport.deliver(LinkFrame(LinkProtocol.TYPE_TEXT, 1, ByteArray(2)))  // kimlik sığmıyor

        assertTrue(messenger.messages.value.isEmpty())
    }

    // ---------- kalıcılık ----------

    @Test
    fun `history survives a restart and is not re-added by a retransmission`() {
        val peer = "AA:BB:CC:DD:EE:FF"
        build().bindPeer(peer)
        transport.connect()
        messenger.send("giden")
        transport.deliver(textFrom(777, "gelen"))
        messenger.release()
        awaitSave()

        // Uygulama yeniden açıldı.
        build().bindPeer(peer)
        assertEquals(2, messenger.messages.value.size)

        transport.connect()
        transport.deliver(textFrom(777, "gelen"))   // karşı taraf onayı almamıştı
        assertEquals(
            "yeniden gonderilen mesaj ikinci kez yazilmamali",
            2, messenger.messages.value.size
        )
    }

    @Test
    fun `switching peers keeps each conversation separate`() {
        build().bindPeer("AA:AA:AA:AA:AA:AA")
        transport.connect("AA:AA:AA:AA:AA:AA")
        messenger.send("birinci telefona")

        messenger.bindPeer("BB:BB:BB:BB:BB:BB")
        assertTrue("yeni cihazin gecmisi bos olmali", messenger.messages.value.isEmpty())
        awaitSave()

        messenger.bindPeer("AA:AA:AA:AA:AA:AA")
        assertEquals(1, messenger.messages.value.size)
        assertEquals("birinci telefona", messenger.messages.value[0].text)
    }

    @Test
    fun `store keeps only the newest messages`() {
        val many = (1..MessageStore.MAX_MESSAGES + 50).map {
            ChatMessage(it, it % 2 == 0, "mesaj $it", it.toLong(), ChatMessage.State.DELIVERED)
        }
        val store = MessageStore(temp.root)
        store.save("AA:BB", many)

        val loaded = store.load("AA:BB")
        assertEquals(MessageStore.MAX_MESSAGES, loaded.size)
        assertEquals("mesaj ${MessageStore.MAX_MESSAGES + 50}", loaded.last().text)
    }

    @Test
    fun `a corrupt line does not destroy the rest of the history`() {
        val store = MessageStore(temp.root)
        store.save(
            "AA:BB",
            listOf(ChatMessage(1, true, "saglam", 1L, ChatMessage.State.DELIVERED))
        )
        val file = temp.root.resolve("sohbet/AABB.jsonl")
        file.appendText("{bozuk json\n")
        file.appendText(
            """{"id":2,"out":false,"text":"ikinci","ts":2,"state":"INCOMING"}""" + "\n"
        )

        val loaded = store.load("AA:BB")
        assertEquals(2, loaded.size)
        assertEquals("ikinci", loaded[1].text)
    }
}
