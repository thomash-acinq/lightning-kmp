package fr.acinq.lightning.message

import fr.acinq.bitcoin.ByteVector
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.wire.GenericTlv
import fr.acinq.lightning.wire.OnionMessagePayloadTlv
import fr.acinq.lightning.wire.TlvStream
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class PostmanTestsCommon : LightningTestSuite() {
    @Test
    fun `send message and reply`() = runSuspendTest {
        val aliceKey = randomKey()
        val bobKey = randomKey()
        var alicePostman: Postman? = null
        val bobPostman = Postman(bobKey) { nextNodeId, onionMessage ->
            assertEquals(aliceKey.publicKey(), nextNodeId)
            launch { alicePostman!!.processOnionMessage(onionMessage) }
            null }
        alicePostman = Postman(aliceKey) { nextNodeId, onionMessage ->
            assertEquals(bobKey.publicKey(), nextNodeId)
            launch { bobPostman.processOnionMessage(onionMessage) }
            null
        }

        val pathId = randomBytes32()
        val blindedPath = OnionMessages.buildRoute(
            randomKey(),
            listOf(OnionMessages.IntermediateNode(bobKey.publicKey())),
            OnionMessages.Destination.Recipient(bobKey.publicKey(), pathId)
        )
        val tlvs1 = TlvStream<OnionMessagePayloadTlv>(setOf(), setOf(GenericTlv(55, ByteVector("c0de"))))
        val tlvs2 = TlvStream<OnionMessagePayloadTlv>(setOf(), setOf(GenericTlv(77, ByteVector("1dea12"))))
        launch {
            val messageToBob = bobPostman.receiveMessage(pathId)!!.messageOnion
            assertEquals(tlvs1.unknown, messageToBob.records.unknown)
            bobPostman.sendMessage(OnionMessages.Destination.BlindedPath(messageToBob.replyPath!!), tlvs2, listOf())
        }
        val messageToAlice = alicePostman.sendMessageExpectingReply(OnionMessages.Destination.BlindedPath(blindedPath), tlvs1, listOf(), 3, 10.seconds).right!!.messageOnion
        assertEquals(tlvs2.unknown, messageToAlice.records.unknown)
    }
}