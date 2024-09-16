package fr.acinq.lightning.db

import fr.acinq.bitcoin.*
import fr.acinq.lightning.*
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.channel.TooManyAcceptedHtlcs
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.payment.FinalFailure
import fr.acinq.lightning.payment.PaymentRequest
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.TemporaryNodeFailure
import kotlin.test.*

class PaymentsDbTestsCommon : LightningTestSuite() {

    @Test
    fun `receive incoming payment with 1 htlc`() = runSuspendTest {
        val (db, preimage, pr) = createFixture()
        assertNull(db.getIncomingPayment(pr.paymentHash))

        val channelId = randomBytes32()
        val incoming = IncomingPayment(preimage, IncomingPayment.Origin.Invoice(pr), null, 100)
        db.addIncomingPayment(preimage, IncomingPayment.Origin.Invoice(pr), 100)
        val pending = db.getIncomingPayment(pr.paymentHash)
        assertNotNull(pending)
        assertEquals(incoming, pending)

        db.receivePayment(
            pr.paymentHash,
            listOf(
                IncomingPayment.ReceivedWith.LightningPayment(
                    amount = 200_000.msat,
                    channelId = channelId,
                    htlcId = 1L
                )
            ), 110
        )
        val received = db.getIncomingPayment(pr.paymentHash)
        assertNotNull(received)
        assertEquals(
            pending.copy(
                received = IncomingPayment.Received(
                    listOf(
                        IncomingPayment.ReceivedWith.LightningPayment(
                            amount = 200_000.msat,
                            channelId = channelId,
                            htlcId = 1L
                        )
                    ), 110
                )
            ), received
        )
    }

    @Test
    fun `receive incoming payment with several parts`() = runSuspendTest {
        val (db, preimage, pr) = createFixture()
        assertNull(db.getIncomingPayment(pr.paymentHash))

        val (channelId1, channelId2, channelId3) = listOf(randomBytes32(), randomBytes32(), randomBytes32())
        val incoming = IncomingPayment(preimage, IncomingPayment.Origin.Invoice(pr), null, 200)
        db.addIncomingPayment(preimage, IncomingPayment.Origin.Invoice(pr), 200)
        val pending = db.getIncomingPayment(pr.paymentHash)
        assertNotNull(pending)
        assertEquals(incoming, pending)

        db.receivePayment(
            pr.paymentHash, listOf(
                IncomingPayment.ReceivedWith.LightningPayment(amount = 57_000.msat, channelId = channelId1, htlcId = 1L),
                IncomingPayment.ReceivedWith.LightningPayment(amount = 43_000.msat, channelId = channelId2, htlcId = 54L),
                IncomingPayment.ReceivedWith.NewChannel(amount = 99_000.msat, channelId = channelId3, serviceFee = 1_000.msat, miningFee = 0.sat, txId = TxId(randomBytes32()), confirmedAt = null, lockedAt = null)
            ), 110
        )
        val received = db.getIncomingPayment(pr.paymentHash)
        assertNotNull(received)
        assertEquals(199_000.msat, received.amount)
        assertEquals(1_000.msat, received.fees)
        assertEquals(3, received.received!!.receivedWith.size)
        assertEquals(57_000.msat, received.received!!.receivedWith.elementAt(0).amount)
        assertEquals(0.msat, received.received!!.receivedWith.elementAt(0).fees)
        assertEquals(channelId1, (received.received!!.receivedWith.elementAt(0) as IncomingPayment.ReceivedWith.LightningPayment).channelId)
        assertEquals(54L, (received.received!!.receivedWith.elementAt(1) as IncomingPayment.ReceivedWith.LightningPayment).htlcId)
        assertEquals(channelId3, (received.received!!.receivedWith.elementAt(2) as IncomingPayment.ReceivedWith.NewChannel).channelId)
    }

    @Test
    fun `receiving several payments on the same payment hash is additive`() = runSuspendTest {
        val (db, preimage, pr) = createFixture()
        val channelId = randomBytes32()

        db.addIncomingPayment(preimage, IncomingPayment.Origin.Invoice(pr), 200)
        db.receivePayment(
            pr.paymentHash, listOf(
                IncomingPayment.ReceivedWith.LightningPayment(
                    amount = 200_000.msat,
                    channelId = channelId,
                    htlcId = 1L
                )
            ), 110
        )
        val received1 = db.getIncomingPayment(pr.paymentHash)
        assertNotNull(received1)
        assertNotNull(received1.received)
        assertEquals(200_000.msat, received1.amount)

        db.receivePayment(
            pr.paymentHash, listOf(
                IncomingPayment.ReceivedWith.LightningPayment(
                    amount = 100_000.msat,
                    channelId = channelId,
                    htlcId = 2L
                )
            ), 150
        )
        val received2 = db.getIncomingPayment(pr.paymentHash)
        assertNotNull(received2)
        assertNotNull(received2.received)
        assertEquals(300_000.msat, received2.amount)
        assertEquals(150, received2.received!!.receivedAt)
        assertEquals(
            listOf(
                IncomingPayment.ReceivedWith.LightningPayment(
                    amount = 200_000.msat,
                    channelId = channelId,
                    htlcId = 1L
                ),
                IncomingPayment.ReceivedWith.LightningPayment(
                    amount = 100_000.msat,
                    channelId = channelId,
                    htlcId = 2L
                )
            ), received2.received!!.receivedWith
        )
    }

    @Test
    fun `received total amount accounts for the fee`() = runSuspendTest {
        val (db, preimage, pr) = createFixture()
        db.addIncomingPayment(preimage, IncomingPayment.Origin.Invoice(pr), 200)
        db.receivePayment(
            pr.paymentHash, listOf(
                IncomingPayment.ReceivedWith.NewChannel(
                    amount = 500_000.msat,
                    serviceFee = 15_000.msat,
                    miningFee = 0.sat,
                    channelId = randomBytes32(),
                    txId = TxId(randomBytes32()),
                    confirmedAt = null,
                    lockedAt = null
                )
            ), 110
        )
        val received1 = db.getIncomingPayment(pr.paymentHash)
        assertNotNull(received1?.received)
        assertEquals(500_000.msat, received1!!.amount)
        assertEquals(15_000.msat, received1.fees)
    }

    @Test
    fun `reject duplicate payment hash`() = runSuspendTest {
        val (db, preimage, pr) = createFixture()
        db.addIncomingPayment(preimage, IncomingPayment.Origin.Invoice(pr))
        assertFails { db.addIncomingPayment(preimage, IncomingPayment.Origin.Invoice(pr)) }
    }

    @Test
    fun `set expired invoices`() = runSuspendTest {
        val (db, preimage, _) = createFixture()
        val pr = createExpiredInvoice(preimage)
        db.addIncomingPayment(preimage, IncomingPayment.Origin.Invoice(pr))

        val expired = db.getIncomingPayment(pr.paymentHash)
        assertNotNull(expired)
        assertTrue(expired.isExpired())
        assertEquals(IncomingPayment.Origin.Invoice(pr), expired.origin)
        assertEquals(preimage, expired.preimage)
    }

    @Test
    fun `send outgoing payment`() = runSuspendTest {
        val (db, _, pr) = createFixture()
        val (a, b, c) = listOf(randomKey().publicKey(), randomKey().publicKey(), randomKey().publicKey())
        val initialParts = listOf(
            LightningOutgoingPayment.Part(UUID.randomUUID(), 20_000.msat, listOf(HopDesc(a, c, ShortChannelId(42))), LightningOutgoingPayment.Part.Status.Pending, 100),
            LightningOutgoingPayment.Part(UUID.randomUUID(), 30_000.msat, listOf(HopDesc(a, b), HopDesc(b, c)), LightningOutgoingPayment.Part.Status.Pending, 105)
        )
        val initialPayment = LightningOutgoingPayment(
            id = UUID.randomUUID(),
            recipientAmount = 50_000.msat,
            recipient = pr.nodeId,
            details = LightningOutgoingPayment.Details.Normal(pr),
            parts = initialParts,
            status = LightningOutgoingPayment.Status.Pending
        )
        db.addOutgoingPayment(initialPayment)
        // We should never re-use ids.
        assertFails { db.addOutgoingPayment(initialPayment.copy(recipientAmount = 60_000.msat)) }
        assertFails { db.addOutgoingPayment(initialPayment.copy(id = UUID.randomUUID(), parts = initialParts.map { it.copy(id = initialPayment.parts[0].id) })) }

        assertEquals(initialPayment, db.getLightningOutgoingPayment(initialPayment.id))
        assertNull(db.getLightningOutgoingPayment(UUID.randomUUID()))
        initialPayment.parts.forEach { assertEquals(initialPayment, db.getLightningOutgoingPaymentFromPartId(it.id)) }
        assertNull(db.getLightningOutgoingPaymentFromPartId(UUID.randomUUID()))

        // One of the parts fails.
        val onePartFailed = initialPayment.copy(
            parts = listOf(
                initialParts[0].copy(status = LightningOutgoingPayment.Part.Status.Failed(TemporaryNodeFailure.code, TemporaryNodeFailure.message, 110)),
                initialParts[1]
            )
        )
        db.completeOutgoingLightningPart(initialPayment.parts[0].id, Either.Right(TemporaryNodeFailure), 110)
        assertEquals(onePartFailed, db.getLightningOutgoingPayment(initialPayment.id))
        initialPayment.parts.forEach { assertEquals(onePartFailed, db.getLightningOutgoingPaymentFromPartId(it.id)) }

        // We should never update non-existing parts.
        assertFails { db.completeOutgoingLightningPart(UUID.randomUUID(), Either.Right(TemporaryNodeFailure)) }
        assertFails { db.completeOutgoingLightningPart(UUID.randomUUID(), randomBytes32()) }

        // Other payment parts are added.
        val newParts = listOf(
            LightningOutgoingPayment.Part(UUID.randomUUID(), 5_000.msat, listOf(HopDesc(a, c)), LightningOutgoingPayment.Part.Status.Pending, 115),
            LightningOutgoingPayment.Part(UUID.randomUUID(), 10_000.msat, listOf(HopDesc(a, b)), LightningOutgoingPayment.Part.Status.Pending, 120),
        )
        assertFails { db.addOutgoingLightningParts(UUID.randomUUID(), newParts) }
        assertFails { db.addOutgoingLightningParts(onePartFailed.id, newParts.map { it.copy(id = initialPayment.parts[0].id) }) }
        val withMoreParts = onePartFailed.copy(parts = onePartFailed.parts + newParts)
        db.addOutgoingLightningParts(onePartFailed.id, newParts)
        assertEquals(withMoreParts, db.getLightningOutgoingPayment(initialPayment.id))
        withMoreParts.parts.forEach { assertEquals(withMoreParts, db.getLightningOutgoingPaymentFromPartId(it.id)) }

        // Payment parts succeed.
        val preimage = randomBytes32()
        val partsSettled = withMoreParts.copy(
            parts = listOf(
                withMoreParts.parts[0], // this one was failed
                withMoreParts.parts[1].copy(status = LightningOutgoingPayment.Part.Status.Succeeded(preimage, 125)),
                withMoreParts.parts[2].copy(status = LightningOutgoingPayment.Part.Status.Succeeded(preimage, 126)),
                withMoreParts.parts[3].copy(status = LightningOutgoingPayment.Part.Status.Succeeded(preimage, 127)),
            )
        )
        assertEquals(LightningOutgoingPayment.Status.Pending, partsSettled.status)
        db.completeOutgoingLightningPart(withMoreParts.parts[1].id, preimage, 125)
        db.completeOutgoingLightningPart(withMoreParts.parts[2].id, preimage, 126)
        db.completeOutgoingLightningPart(withMoreParts.parts[3].id, preimage, 127)
        assertEquals(partsSettled, db.getLightningOutgoingPayment(initialPayment.id))
        partsSettled.parts.forEach { assertEquals(partsSettled, db.getLightningOutgoingPaymentFromPartId(it.id)) }

        // Payment succeeds: failed parts will be ignored.
        val paymentSucceeded = partsSettled.copy(
            status = LightningOutgoingPayment.Status.Completed.Succeeded.OffChain(preimage, 130),
            parts = partsSettled.parts.drop(1)
        )
        db.completeOutgoingPaymentOffchain(initialPayment.id, preimage, 130)
        assertFails { db.completeOutgoingPaymentOffchain(UUID.randomUUID(), preimage, 130) }
        assertEquals(paymentSucceeded, db.getLightningOutgoingPayment(initialPayment.id))
        partsSettled.parts.forEach { assertEquals(paymentSucceeded, db.getLightningOutgoingPaymentFromPartId(it.id)) }
    }

    @Test
    fun `outgoing normal payment fee and amount computation`() = runSuspendTest {
        val (db, preimage, pr) = createFixture()
        val (a, b, c) = listOf(randomKey().publicKey(), randomKey().publicKey(), randomKey().publicKey())

        // test normal payment fee computation
        val hops1 = listOf(HopDesc(a, c, ShortChannelId(42)), HopDesc(b, c))
        val hops2 = listOf(HopDesc(a, b))
        val normalPayment = LightningOutgoingPayment(
            id = UUID.randomUUID(),
            recipientAmount = 180_000.msat,
            recipient = pr.nodeId,
            details = LightningOutgoingPayment.Details.Normal(pr),
            parts = listOf(),
            status = LightningOutgoingPayment.Status.Pending
        )
        db.addOutgoingPayment(normalPayment)
        val normalParts = listOf(
            LightningOutgoingPayment.Part(UUID.randomUUID(), amount = 115_000.msat, route = hops1, status = LightningOutgoingPayment.Part.Status.Pending, createdAt = 100),
            LightningOutgoingPayment.Part(UUID.randomUUID(), amount = 75_000.msat, route = hops2, status = LightningOutgoingPayment.Part.Status.Pending, createdAt = 105)
        )
        db.addOutgoingLightningParts(parentId = normalPayment.id, parts = normalParts)
        db.completeOutgoingLightningPart(normalParts[0].id, preimage, 110)
        db.completeOutgoingLightningPart(normalParts[1].id, preimage, 115)
        db.completeOutgoingPaymentOffchain(normalPayment.id, preimage, 120)
        val normalPaymentInDb = db.getLightningOutgoingPayment(normalPayment.id)

        assertNotNull(normalPaymentInDb)
        assertEquals(10_000.msat, normalPaymentInDb.fees)
        assertEquals(190_000.msat, normalPaymentInDb.amount)
    }

    @Test
    fun `outgoing swap-out payment fee and amount computation`() = runSuspendTest {
        val (db, preimage, pr) = createFixture()
        val (a, b, c) = listOf(randomKey().publicKey(), randomKey().publicKey(), randomKey().publicKey())
        val hops = listOf(HopDesc(a, c, ShortChannelId(42)), HopDesc(b, c))
        // test swap-out payment fee computation
        val swapOutPayment = LightningOutgoingPayment(
            id = UUID.randomUUID(),
            recipientAmount = 150_000.msat,
            recipient = pr.nodeId,
            details = LightningOutgoingPayment.Details.SwapOut("2NCnVWgTuCptq1y7Cie4KwSBADcBqXKTNCR", pr, 15.sat),
            parts = listOf(),
            status = LightningOutgoingPayment.Status.Pending
        )
        db.addOutgoingPayment(swapOutPayment)
        val swapOutParts = listOf(
            LightningOutgoingPayment.Part(UUID.randomUUID(), amount = 157_000.msat, route = hops, status = LightningOutgoingPayment.Part.Status.Pending, createdAt = 100),
        )
        db.addOutgoingLightningParts(parentId = swapOutPayment.id, parts = swapOutParts)
        db.completeOutgoingLightningPart(swapOutParts[0].id, preimage, 110)
        db.completeOutgoingPaymentOffchain(swapOutPayment.id, preimage, 120)
        val swapOutPaymentInDb = db.getLightningOutgoingPayment(swapOutPayment.id)

        assertNotNull(swapOutPaymentInDb)
        // the service receives 150 000 msat
        // the swap amount received by the on-chain address is 135 000 msat
        // the mining fee are contained in the swap-out service fee
        // total fees is 7 000 msat for routing with trampoline + 15 000 msat for the swap-out service
        assertEquals(22_000.msat, swapOutPaymentInDb.fees)
        assertEquals(157_000.msat, swapOutPaymentInDb.amount)
    }

    @Test
    fun `fail outgoing payment`() = runSuspendTest {
        val (db, _, pr) = createFixture()
        val initialParts = listOf(
            LightningOutgoingPayment.Part(UUID.randomUUID(), 20_000.msat, listOf(HopDesc(randomKey().publicKey(), randomKey().publicKey())), LightningOutgoingPayment.Part.Status.Pending, 100),
            LightningOutgoingPayment.Part(UUID.randomUUID(), 30_000.msat, listOf(HopDesc(randomKey().publicKey(), randomKey().publicKey())), LightningOutgoingPayment.Part.Status.Pending, 105)
        )
        val initialPayment = LightningOutgoingPayment(
            id = UUID.randomUUID(),
            recipientAmount = 50_000.msat,
            recipient = pr.nodeId,
            details = LightningOutgoingPayment.Details.Normal(pr),
            parts = initialParts,
            status = LightningOutgoingPayment.Status.Pending
        )
        db.addOutgoingPayment(initialPayment)
        assertEquals(initialPayment, db.getLightningOutgoingPayment(initialPayment.id))

        val channelId = randomBytes32()
        val partsFailed = initialPayment.copy(
            parts = listOf(
                initialParts[0].copy(status = LightningOutgoingPayment.Part.Status.Failed(TemporaryNodeFailure.code, TemporaryNodeFailure.message, 110)),
                initialParts[1].copy(status = LightningOutgoingPayment.Part.Status.Failed(null, TooManyAcceptedHtlcs(channelId, 10).details(), 111)),
            )
        )
        db.completeOutgoingLightningPart(initialPayment.parts[0].id, Either.Right(TemporaryNodeFailure), 110)
        db.completeOutgoingLightningPart(initialPayment.parts[1].id, Either.Left(TooManyAcceptedHtlcs(channelId, 10)), 111)
        assertEquals(partsFailed, db.getLightningOutgoingPayment(initialPayment.id))
        initialPayment.parts.forEach { assertEquals(partsFailed, db.getLightningOutgoingPaymentFromPartId(it.id)) }

        val paymentFailed = partsFailed.copy(status = LightningOutgoingPayment.Status.Completed.Failed(FinalFailure.NoRouteToRecipient, 120))
        db.completeOutgoingPaymentOffchain(initialPayment.id, FinalFailure.NoRouteToRecipient, 120)
        assertFails { db.completeOutgoingPaymentOffchain(UUID.randomUUID(), FinalFailure.NoRouteToRecipient, 120) }
        assertEquals(paymentFailed, db.getLightningOutgoingPayment(initialPayment.id))
        initialPayment.parts.forEach { assertEquals(paymentFailed, db.getLightningOutgoingPaymentFromPartId(it.id)) }
    }

    @Test
    fun `list outgoing payments by payment hash`() = runSuspendTest {
        val (db, _, pr) = createFixture()
        assertTrue(db.listLightningOutgoingPayments(pr.paymentHash).isEmpty())

        val payment1 = LightningOutgoingPayment(UUID.randomUUID(), 50_000.msat, pr.nodeId, pr)
        db.addOutgoingPayment(payment1)
        assertEquals(listOf(payment1), db.listLightningOutgoingPayments(pr.paymentHash))

        val payment2 = payment1.copy(
            id = UUID.randomUUID(),
            parts = listOf(LightningOutgoingPayment.Part(UUID.randomUUID(), 50_000.msat, listOf(), LightningOutgoingPayment.Part.Status.Pending, 100))
        )
        db.addOutgoingPayment(payment2)
        assertEquals(setOf(payment1, payment2), db.listLightningOutgoingPayments(pr.paymentHash).toSet())
    }

    companion object {
        private val defaultFeatures = Features(
            Feature.VariableLengthOnion to FeatureSupport.Optional,
            Feature.PaymentSecret to FeatureSupport.Optional,
            Feature.BasicMultiPartPayment to FeatureSupport.Optional
        )

        private fun createFixture(): Triple<PaymentsDb, ByteVector32, Bolt11Invoice> {
            val db = InMemoryPaymentsDb()
            val preimage = randomBytes32()
            val pr = createInvoice(preimage)
            return Triple(db, preimage, pr)
        }

        private fun createInvoice(preimage: ByteVector32): Bolt11Invoice {
            return Bolt11Invoice.create(Block.LivenetGenesisBlock.hash, 150_000.msat, Crypto.sha256(preimage).toByteVector32(), randomKey(), Either.Left("invoice"), CltvExpiryDelta(16), defaultFeatures)
        }

        private fun createExpiredInvoice(preimage: ByteVector32 = randomBytes32()): Bolt11Invoice {
            val now = currentTimestampSeconds()
            return Bolt11Invoice.create(
                Block.LivenetGenesisBlock.hash,
                150_000.msat,
                Crypto.sha256(preimage).toByteVector32(),
                randomKey(),
                Either.Left("invoice"),
                CltvExpiryDelta(16),
                defaultFeatures,
                expirySeconds = 60,
                timestampSeconds = now - 120
            )
        }
    }

}