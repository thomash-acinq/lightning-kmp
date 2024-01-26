package fr.acinq.lightning.wire

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.crypto.RouteBlinding
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.OfferTypes.ContactInfo.*
import fr.acinq.lightning.wire.OfferTypes.InvoiceRequest
import fr.acinq.lightning.wire.OfferTypes.InvoiceRequestAmount
import fr.acinq.lightning.wire.OfferTypes.InvoiceRequestChain
import fr.acinq.lightning.wire.OfferTypes.InvoiceRequestMetadata
import fr.acinq.lightning.wire.OfferTypes.InvoiceRequestPayerId
import fr.acinq.lightning.wire.OfferTypes.InvoiceRequestQuantity
import fr.acinq.lightning.wire.OfferTypes.InvoiceRequestTlv
import fr.acinq.lightning.wire.OfferTypes.Offer
import fr.acinq.lightning.wire.OfferTypes.OfferAmount
import fr.acinq.lightning.wire.OfferTypes.OfferChains
import fr.acinq.lightning.wire.OfferTypes.OfferDescription
import fr.acinq.lightning.wire.OfferTypes.OfferIssuer
import fr.acinq.lightning.wire.OfferTypes.OfferNodeId
import fr.acinq.lightning.wire.OfferTypes.OfferQuantityMax
import fr.acinq.lightning.wire.OfferTypes.ShortChannelIdDir
import fr.acinq.lightning.wire.OfferTypes.Signature
import fr.acinq.lightning.wire.OfferTypes.readPath
import fr.acinq.lightning.wire.OfferTypes.removeSignature
import fr.acinq.lightning.wire.OfferTypes.rootHash
import fr.acinq.lightning.wire.OfferTypes.signSchnorr
import fr.acinq.lightning.wire.OfferTypes.writePath
import kotlin.test.*

class OfferTypesTestsCommon : LightningTestSuite() {
    val nodeKey = PrivateKey.fromHex("85d08273493e489b9330c85a3e54123874c8cd67c1bf531f4b926c9c555f8e1d")
    val nodeId = nodeKey.publicKey()

    @Test
    fun `invoice request is signed`() {
        val sellerKey = randomKey()
        val offer = Offer(100_000.msat, "test offer", sellerKey.publicKey(), Features.empty, Block.LivenetGenesisBlock.hash)
        val payerKey = randomKey()
        val request = InvoiceRequest(offer, 100_000.msat, 1, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
        assertTrue(request.checkSignature())
    }

    @Test
    fun `minimal offer`() {
        val tlvs = setOf(
            OfferDescription("basic offer"),
            OfferNodeId(nodeId))
        val offer = Offer(TlvStream(tlvs))
        val encoded = "lno1pg9kyctnd93jqmmxvejhy93pqvxl9c6mjgkeaxa6a0vtxqteql688v0ywa8qqwx4j05cyskn8ncrj"
        assertEquals(offer, Offer.decode(encoded))
        assertNull(offer.amount)
        assertEquals("basic offer", offer.description)
        assertEquals(nodeId, offer.nodeId)
        // Removing any TLV from the minimal offer makes it invalid.
        for (tlv in tlvs) {
            val incomplete = TlvStream(tlvs.filterNot{it == tlv}.toSet())
            assertTrue(Offer.validate(incomplete).isLeft)
            val incompleteEncoded = Bech32.encodeBytes(Offer.hrp, Offer.tlvSerializer.write(incomplete), Bech32.Encoding.Beck32WithoutChecksum)
            assertFails{Offer.decode(incompleteEncoded)}
        }
    }

    @Test
    fun `offer with amount and quantity`() {
        val offer = Offer(TlvStream(
            OfferChains(listOf(Block.TestnetGenesisBlock.hash)),
            OfferAmount(50.msat),
            OfferDescription("offer with quantity"),
            OfferIssuer("alice@bigshop.com"),
            OfferQuantityMax(0),
            OfferNodeId(nodeId)))
        val encoded = "lno1qgsyxjtl6luzd9t3pr62xr7eemp6awnejusgf6gw45q75vcfqqqqqqqgqyeq5ym0venx2u3qwa5hg6pqw96kzmn5d968jys3v9kxjcm9gp3xjemndphhqtnrdak3gqqkyypsmuhrtwfzm85mht4a3vcp0yrlgua3u3m5uqpc6kf7nqjz6v70qwg"
        assertEquals(offer, Offer.decode(encoded))
        assertEquals(50.msat, offer.amount)
        assertEquals("offer with quantity", offer.description)
        assertEquals( nodeId, offer.nodeId)
        assertEquals("alice@bigshop.com", offer.issuer)
        assertEquals(Long.MAX_VALUE, offer.quantityMax)
    }

    fun signInvoiceRequest(request: InvoiceRequest, key: PrivateKey): InvoiceRequest {
        val tlvs = removeSignature(request.records)
        val signature = signSchnorr(InvoiceRequest.signatureTag, rootHash(tlvs, InvoiceRequest.tlvSerializer), key)
        val signedRequest = InvoiceRequest(tlvs.copy(records = tlvs.records + Signature(signature)))
        assertTrue(signedRequest.checkSignature())
        return signedRequest
    }

    @Test
    fun `check that invoice request matches offer`() {
        val offer = Offer(2500.msat, "basic offer", randomKey().publicKey(), Features.empty, Block.LivenetGenesisBlock.hash)
        val payerKey = randomKey()
        val request = InvoiceRequest(offer, 2500.msat, 1, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
        assertTrue(request.isValid())
        assertEquals(offer, request.offer)
        val biggerAmount = signInvoiceRequest(request.copy(records = TlvStream(request.records.records.map { when(it) { is InvoiceRequestAmount -> InvoiceRequestAmount(3000.msat) else -> it }}.toSet())), payerKey)
        assertTrue(biggerAmount.isValid())
        assertEquals(offer, biggerAmount.offer)
        val lowerAmount = signInvoiceRequest(request.copy(records = TlvStream(request.records.records.map { when(it) { is InvoiceRequestAmount -> InvoiceRequestAmount(2000.msat) else -> it }}.toSet())), payerKey)
        assertFalse(lowerAmount.isValid())
        val withQuantity = signInvoiceRequest(request.copy(records = TlvStream(request.records.records + InvoiceRequestQuantity(1))), payerKey)
        assertFalse(withQuantity.isValid())
    }

    @Test
    fun `check that invoice request matches offer - with features`() {
        val offer = Offer(2500.msat, "offer with features", randomKey().publicKey(), Features.empty, Block.LivenetGenesisBlock.hash)
        val payerKey = randomKey()
        val request = InvoiceRequest(offer, 2500.msat, 1, Features(Feature.BasicMultiPartPayment to FeatureSupport.Optional), payerKey, Block.LivenetGenesisBlock.hash)
        assertTrue(request.isValid())
        assertEquals(offer, request.offer)
        val withoutFeatures = InvoiceRequest(offer, 2500.msat, 1, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
        assertTrue(withoutFeatures.isValid())
        assertEquals(offer, withoutFeatures.offer)
        val otherFeatures = InvoiceRequest(offer, 2500.msat, 1, Features(Feature.BasicMultiPartPayment to FeatureSupport.Mandatory), payerKey, Block.LivenetGenesisBlock.hash)
        assertFalse(otherFeatures.isValid())
        assertEquals(offer, otherFeatures.offer)
    }

    @Test
    fun `check that invoice request matches offer - without amount`() {
        val offer = Offer(null, "offer without amount", randomKey().publicKey(), Features.empty, Block.LivenetGenesisBlock.hash)
        val payerKey = randomKey()
        val request = InvoiceRequest(offer, 500.msat, 1, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
        assertTrue(request.isValid())
        assertEquals(offer, request.offer)
        val withoutAmount = signInvoiceRequest(request.copy(records = TlvStream(request.records.records.filterNot { it is InvoiceRequestAmount }.toSet())), payerKey)
        assertFalse(withoutAmount.isValid())
    }

    @Test
    fun `check that invoice request matches offer - without chain`() {
        val offer = Offer(TlvStream(OfferAmount(100.msat), OfferDescription("offer without chains"), OfferNodeId(randomKey().publicKey())))
        val payerKey = randomKey()
        val tlvs: Set<InvoiceRequestTlv> = offer.records.records + setOf(
            InvoiceRequestMetadata(ByteVector.fromHex("012345")),
            InvoiceRequestAmount(100.msat),
            InvoiceRequestPayerId(payerKey.publicKey()),
        )
        val signature = signSchnorr(InvoiceRequest.signatureTag, rootHash(TlvStream(tlvs), InvoiceRequest.tlvSerializer), payerKey)
        val request = InvoiceRequest(TlvStream(tlvs + Signature(signature)))
        assertTrue(request.isValid())
        assertEquals(offer, request.offer)
        val withDefaultChain = signInvoiceRequest(request.copy(records = TlvStream(request.records.records + InvoiceRequestChain(Block.LivenetGenesisBlock.hash))), payerKey)
        assertTrue(withDefaultChain.isValid())
        assertEquals(offer, withDefaultChain.offer)
        val otherChain = signInvoiceRequest(request.copy(records = TlvStream(request.records.records + InvoiceRequestChain(Block.TestnetGenesisBlock.hash))), payerKey)
        assertFalse(otherChain.isValid())
    }

    @Test
    fun `check that invoice request matches offer - with chains`() {
        val chain1 = BlockHash(randomBytes32())
        val chain2 = BlockHash(randomBytes32())
        val offer = Offer(TlvStream(OfferChains(listOf(chain1, chain2)), OfferAmount(100.msat), OfferDescription("offer with chains"), OfferNodeId(randomKey().publicKey())))
        val payerKey = randomKey()
        val request1 = InvoiceRequest(offer, 100.msat, 1, Features.empty, payerKey, chain1)
        assertTrue(request1.isValid())
        assertEquals(offer, request1.offer)
        val request2 = InvoiceRequest(offer, 100.msat, 1, Features.empty, payerKey, chain2)
        assertTrue(request2.isValid())
        assertEquals(offer, request2.offer)
        val noChain = signInvoiceRequest(request1.copy(records = TlvStream(request1.records.records.filterNot { it is InvoiceRequestChain }.toSet())), payerKey)
        assertFalse(noChain.isValid())
        val otherChain = signInvoiceRequest(request1.copy(records = TlvStream(request1.records.records.map { when(it){ is InvoiceRequestChain -> InvoiceRequestChain(Block.LivenetGenesisBlock.hash) else -> it }}.toSet())), payerKey)
        assertFalse(otherChain.isValid())
    }

    @Test
    fun `check that invoice request matches offer - multiple items`() {
        val offer = Offer(TlvStream(
            OfferAmount(500.msat),
            OfferDescription("offer for multiple items"),
            OfferNodeId(randomKey().publicKey()),
            OfferQuantityMax(10),
        ))
        val payerKey = randomKey()
        val request = InvoiceRequest(offer, 1600.msat, 3, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
        assertNotNull(request.records.get<InvoiceRequestQuantity>())
        assertTrue(request.isValid())
        assertEquals(offer, request.offer)
        val invalidAmount = InvoiceRequest(offer, 2400.msat, 5, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
        assertFalse(invalidAmount.isValid())
        val tooManyItems = InvoiceRequest(offer, 5500.msat, 11, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
        assertFalse(tooManyItems.isValid())
    }

    @Test
    fun `minimal invoice request`() {
        val payerKey = PrivateKey.fromHex("527d410ec920b626ece685e8af9abc976a48dbf2fe698c1b35d90a1c5fa2fbca")
        val tlvsWithoutSignature = setOf<InvoiceRequestTlv>(
            InvoiceRequestMetadata(ByteVector.fromHex("abcdef")),
            OfferDescription("basic offer"),
            OfferNodeId(nodeId),
            InvoiceRequestPayerId(payerKey.publicKey()),
        )
        val signature = signSchnorr(InvoiceRequest.signatureTag, rootHash(TlvStream<InvoiceRequestTlv>(tlvsWithoutSignature), InvoiceRequest.tlvSerializer), payerKey)
        val tlvs = tlvsWithoutSignature + Signature(signature)
        val invoiceRequest = InvoiceRequest(TlvStream(tlvs))
        val encoded = "lnr1qqp6hn00pg9kyctnd93jqmmxvejhy93pqvxl9c6mjgkeaxa6a0vtxqteql688v0ywa8qqwx4j05cyskn8ncrjkppqfxajawru7sa7rt300hfzs2lyk2jrxduxrkx9lmzy6lxcvfhk0j7ruzqc4mtjj5fwukrqp7faqrxn664nmwykad76pu997terewcklsx47apag59wf8exly4tky7y63prr7450n28stqssmzuf48w7e6rjad2eq"
        assertEquals(invoiceRequest, InvoiceRequest.decode(encoded))
        assertNull(invoiceRequest.offer.amount)
        assertEquals("basic offer", invoiceRequest.offer.description)
        assertEquals(nodeId, invoiceRequest.offer.nodeId)
        assertEquals(ByteVector.fromHex("abcdef"), invoiceRequest.metadata)
        assertEquals(payerKey.publicKey(), invoiceRequest.payerId)
        // Removing any TLV from the minimal invoice request makes it invalid.
        for (tlv in tlvs) {
            val incomplete = TlvStream(tlvs.filterNot{it == tlv}.toSet())
            assertTrue(InvoiceRequest.validate(incomplete).isLeft)
            val incompleteEncoded = Bech32.encodeBytes(InvoiceRequest.hrp, InvoiceRequest.tlvSerializer.write(incomplete), Bech32.Encoding.Beck32WithoutChecksum)
            assertFails{InvoiceRequest.decode(incompleteEncoded)}
        }
    }

    @Test
    fun `compute merkle tree root`() {
        data class TestCase(val tlvs: String, val count: Int, val expected: ByteVector32)

        data class GenericTlv(val data: ByteVector, override val tag: Long) : Tlv {
            override fun write(out: Output) {
                    LightningCodecs.writeBytes(data, out)
            }
        }

        data class GenericTlvReader(val tag: Long) : TlvValueReader<GenericTlv> {
            override fun read(input: Input): GenericTlv {
                return GenericTlv(LightningCodecs.bytes(input, input.availableBytes).toByteVector(), tag)
            }
        }

        val genericTlvSerializer = TlvStreamSerializer(
            false, (0..1000).map { i -> i.toLong() to GenericTlvReader(i.toLong()) }.toMap()
        )

        val testCases = listOf(
            // Official test vectors.
            TestCase("010203e8", 1, ByteVector32.fromValidHex("b013756c8fee86503a0b4abdab4cddeb1af5d344ca6fc2fa8b6c08938caa6f93")),
            TestCase("010203e8 02080000010000020003", 2, ByteVector32.fromValidHex("c3774abbf4815aa54ccaa026bff6581f01f3be5fe814c620a252534f434bc0d1")),
            TestCase("010203e8 02080000010000020003 03310266e4598d1d3c415f572a8488830b60f7e744ed9235eb0b1ba93283b315c0351800000000000000010000000000000002", 3, ByteVector32.fromValidHex("ab2e79b1283b0b31e0b035258de23782df6b89a38cfa7237bde69aed1a658c5d")),
            TestCase("0008000000000000000006035553440801640a1741204d617468656d61746963616c205472656174697365162102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661958210324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c", 6, ByteVector32.fromValidHex("608407c18ad9a94d9ea2bcdbe170b6c20c462a7833a197621c916f78cf18e624")),
            // Additional test vectors.
            TestCase("010100", 1, ByteVector32.fromValidHex("14ffa5e1e5d861059abff167dad6e632c45483006f7d4dc4355586062a3da30d")),
            TestCase("010100 020100", 2, ByteVector32.fromValidHex("ec0584e764b71cb49ebe60ce7edbab8387e42da20b6077031bd27ff345b38ff8")),
            TestCase("010100 020100 030100", 3, ByteVector32.fromValidHex("cc68aea3dc863832ef6828b3da8689cce3478c934cc50a68522477506a35feb2")),
            TestCase("010100 020100 030100 040100", 4, ByteVector32.fromValidHex("b531eaa1ca71956148a6756cf8f46bdf231879e6c392019877f23e56acb7b956")),
            TestCase("010100 020100 030100 040100 050100", 5, ByteVector32.fromValidHex("104e383bfdcb620cd8cefa95245332e8bd32ffd8d974fffdafe1488b1f4a1fbd")),
            TestCase("010100 020100 030100 040100 050100 060100", 6, ByteVector32.fromValidHex("d96f0769702cb3440abbe683d7211fd20bd152699352f09f45d2695a89d18cdc")),
            TestCase("010100 020100 030100 040100 050100 060100 070100", 7, ByteVector32.fromValidHex("30b8886e306c97dbc7b730a2e99138c1ea4fdf5c2f71e2a31e434f63f5eed228")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100", 8, ByteVector32.fromValidHex("783262efe5eeef4ec96bcee8d7cf5149ea44e0c28a78f4b1cb73d6cec9a0b378")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100 090100", 9, ByteVector32.fromValidHex("6fd20b65a0097aff2bcc70753612a296edc27933ea335bac5df2e4c724cdb43c")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100", 10, ByteVector32.fromValidHex("9a3cf7785e9c84e03d6bc7fc04226a1cb19f158a69f16684663aa710bd90a14b")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100", 11, ByteVector32.fromValidHex("ace50a04d9dc82ce123c6ac6c2449fa607054560a9a7b8229cd2d47c01b94953")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100", 12, ByteVector32.fromValidHex("1a8e85042447a10ec312b35db34d0c8722caba4aaf6a170c4506d1fdb520aa66")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100", 13, ByteVector32.fromValidHex("8c3b8d9ba90eb9a4a34c890a7a24ba6ddc873529c5fd7c95f33a5b9ba589f54b")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100", 14, ByteVector32.fromValidHex("ed9e3694bbad2fca636576cc69af4c63ad64023bfeb788fe0f40b3533b248a6a")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100", 15, ByteVector32.fromValidHex("bab201e05786ae1eae4d685b4f815134158720ba297ea0f46a9420ffe5e94b16")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100", 16, ByteVector32.fromValidHex("44438261bb64672f374d8782e92dc9616e900378ce4bd64442753722bc2a1acb")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100", 17, ByteVector32.fromValidHex("bb6fbcd5cf426ec0b7e49d9f9ccc6c15319e01f007cce8f16fa802016718b9f7")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100", 18, ByteVector32.fromValidHex("64d8639e76af096223cad2c448d68fabf751d1c6a939bc86e1015b19188202dc")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100", 19, ByteVector32.fromValidHex("bcb88f8e06886a6d422d14bc2ed4e7fc06c0ad2adeedf630a73972c5b15538ca")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100", 20, ByteVector32.fromValidHex("9deddd5f0ab909e6a161fd4b9d44ed7384ee0a7fe8d3fbb637872767eab82f1e")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100", 21, ByteVector32.fromValidHex("4a32a2325bbd1c2b5b4915c6bec6b3e3d734d956e0c123f1fa6d70f7a8609dcd")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100", 22, ByteVector32.fromValidHex("a3ec28f0f9cb64db8d96dd7b9039fbf2240438401ea992df802d7bb70b3d02af")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100", 23, ByteVector32.fromValidHex("d025f268ec4f09baf51c4b94287e76707d9353e8cab31dc586ae47742ba0b266")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100", 24, ByteVector32.fromValidHex("cd5a2086a3919d67d0617da1e6e293f115bed8d8306498ed814c6c109ad370a4")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100 190100", 25, ByteVector32.fromValidHex("f64113810b52f4d6a55380a3d84e59e34d26c145448121c2113a023cb63de71b")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100 190100 1a0100", 26, ByteVector32.fromValidHex("b99d7332ea2db048093a7bc0aaa85f82ccfa9da2b734fc0a14b79c5dac5a3a1c")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100 190100 1a0100 1b0100", 27, ByteVector32.fromValidHex("fab01a3ce6e878942dc5c9c862cb18e88202d50e6026d2266748f7eda5f9db7f")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100 190100 1a0100 1b0100 1c0100", 28, ByteVector32.fromValidHex("2dc8b24a0e142d1ed36a144ed35ef0d4b7d0d1b51e198b2282248e45ebaf0417")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100 190100 1a0100 1b0100 1c0100 1d0100", 29, ByteVector32.fromValidHex("3693a858cc97762d69d05b2191d3e5254c29ddb5abac5b9fe52b227fa216aa4c")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100 190100 1a0100 1b0100 1c0100 1d0100 1e0100", 30, ByteVector32.fromValidHex("db8787d4509265e764e60b7a81cf38efb9d3a7910d67c4ae68a1232436e1cd3b")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100 190100 1a0100 1b0100 1c0100 1d0100 1e0100 1f0100", 31, ByteVector32.fromValidHex("af49f35e5b2565cb229f342405783d330c56031f005a4a6ca01f87e5637d4614")),
            TestCase("010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100 190100 1a0100 1b0100 1c0100 1d0100 1e0100 1f0100 200100", 32, ByteVector32.fromValidHex("2e9f8a8542576197650f61c882625f0f6838f962f9fa24ce809b687784a8a7de")),
        )
        testCases.forEach {
            (tlvStream, tlvCount, expectedRoot) ->
            val tlvs = genericTlvSerializer.read(ByteVector.fromHex(tlvStream).toByteArray())
            assertEquals(tlvCount, tlvs.records.size)
            val root = rootHash(tlvs, genericTlvSerializer)
            assertEquals(expectedRoot, root)
        }
    }

    @Test
    fun `compact blinded route`() {
        data class TestCase(val encoded: ByteVector, val decoded: BlindedContactInfo)

        val testCases = listOf(
            TestCase(ByteVector.fromHex("00 00000000000004d2 0379b470d00b78ded936f8972a0f3ecda2bb6e6df40dcd581dbaeb3742b30008ff 01 02fba71b72623187dd24670110eec870e28b848f255ba2edc0486d3a8e89ec44b7 0002 1dea"),
                CompactBlindedPath(ShortChannelIdDir(isNode1 = true, ShortChannelId(1234)), PublicKey.fromHex("0379b470d00b78ded936f8972a0f3ecda2bb6e6df40dcd581dbaeb3742b30008ff"), listOf(RouteBlinding.BlindedNode(PublicKey.fromHex("02fba71b72623187dd24670110eec870e28b848f255ba2edc0486d3a8e89ec44b7"), ByteVector.fromHex("1dea"))))),
            TestCase(ByteVector.fromHex("01 000000000000ddd5 0353a081bb02d6e361be3df3e92b41b788ca65667f6ea0c01e2bfa03664460ef86 01 03bce3f0cdb4172caac82ec8a9251eb35df1201bdcb977c5a03f3624ec4156a65f 0003 c0ffee"),
                CompactBlindedPath(ShortChannelIdDir(isNode1 = false, ShortChannelId(56789)), PublicKey.fromHex("0353a081bb02d6e361be3df3e92b41b788ca65667f6ea0c01e2bfa03664460ef86"), listOf(RouteBlinding.BlindedNode(PublicKey.fromHex("03bce3f0cdb4172caac82ec8a9251eb35df1201bdcb977c5a03f3624ec4156a65f"), ByteVector.fromHex("c0ffee"))))),
            TestCase(ByteVector.fromHex("022d3b15cea00ee4a8e710b082bef18f0f3409cc4e7aff41c26eb0a4d3ab20dd73 0379a3b6e4bceb7519d09db776994b1f82cf6a9fa4d3ec2e52314c5938f2f9f966 01 02b446aaa523df82a992ab468e5298eabb6168e2c466455c210d8c97dbb8981328 0002 cafe"),
                BlindedPath(RouteBlinding.BlindedRoute(PublicKey.fromHex("022d3b15cea00ee4a8e710b082bef18f0f3409cc4e7aff41c26eb0a4d3ab20dd73"), PublicKey.fromHex("0379a3b6e4bceb7519d09db776994b1f82cf6a9fa4d3ec2e52314c5938f2f9f966"), listOf(RouteBlinding.BlindedNode(PublicKey.fromHex("02b446aaa523df82a992ab468e5298eabb6168e2c466455c210d8c97dbb8981328"), ByteVector.fromHex("cafe")))))),
            TestCase(ByteVector.fromHex("03ba3c458e3299eb19d2e07ae86453f4290bcdf8689707f0862f35194397c45922 028aa5d1a10463d598a0a0ab7296af21619049f94fe03ef664a87561009e58c3dd 01 02988d7381d0434cfebbe521031505fb9987ae6cefd0bab0e5927852eb96bb6cc2 0003 ec1a13"),
                BlindedPath(RouteBlinding.BlindedRoute(PublicKey.fromHex("03ba3c458e3299eb19d2e07ae86453f4290bcdf8689707f0862f35194397c45922"), PublicKey.fromHex("028aa5d1a10463d598a0a0ab7296af21619049f94fe03ef664a87561009e58c3dd"), listOf(RouteBlinding.BlindedNode(PublicKey.fromHex("02988d7381d0434cfebbe521031505fb9987ae6cefd0bab0e5927852eb96bb6cc2"), ByteVector.fromHex("ec1a13")))))),
        )

        testCases.forEach {
            (encoded, decoded) ->
            val out = ByteArrayOutput()
            writePath(decoded, out)
            assertEquals(encoded, out.toByteArray().toByteVector())
            assertEquals(decoded, readPath(ByteArrayInput(encoded.toByteArray())))
        }
    }
}