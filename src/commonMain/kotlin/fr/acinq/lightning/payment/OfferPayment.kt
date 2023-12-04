package fr.acinq.lightning.payment

import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.message.Postman
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.currentTimestampSeconds
import fr.acinq.lightning.wire.OfferTypes
import fr.acinq.lightning.wire.OnionMessagePayloadTlv
import fr.acinq.lightning.wire.TlvStream

/*class OfferPayment(val nodeParams: NodeParams, val postman: Postman, val outgoingPaymentHandler: OutgoingPaymentHandler) {
    suspend fun payOffer(offer: OfferTypes.Offer, amount: MilliSatoshi, quantity: Long) {
        if (!nodeParams.features.bolt12Features().areSupported(offer.features)) {
            return UnsupportedFeatures(offer.features.invoiceFeatures())
          } else if (!offer.chains.contains(nodeParams.chainHash)) {
            return UnsupportedChains(offer.chains)
          } else if (offer.expirySeconds?.let { it < currentTimestampSeconds() } == true) {
            return ExpiredOffer(offer.expiry.get)
          } else if ((offer.quantityMax ?: 1) < quantity) {
            return QuantityTooHigh(offer.quantityMax ?: 1)
          } else if (offer.amount?.let { it * quantity > amount } == true) {
            return AmountInsufficient(offer.amount * quantity)
          } else {
            val payerKey = randomKey()
            val invoiceRequest = OfferTypes.InvoiceRequest(offer, amount, quantity, nodeParams.features.bolt12Features(), payerKey, nodeParams.chainHash)

            val contactInfo = invoiceRequest.offer.contactInfos[attemptNumber % invoiceRequest.offer.contactInfos.size]
            val messageContent = TlvStream<OnionMessagePayloadTlv>(OnionMessagePayloadTlv.InvoiceRequest(invoiceRequest.records))
            when (postman.sendMessageExpectingReply(contactInfo, messageContent, intermediateNodes)) {
                is Either.Left -> TODO()
                is Either.Right -> TODO()
            }

          }
    }
}*/