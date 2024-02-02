package fr.acinq.lightning.io

import fr.acinq.bitcoin.*
import fr.acinq.lightning.*
import fr.acinq.lightning.blockchain.WatchEvent
import fr.acinq.lightning.blockchain.electrum.*
import fr.acinq.lightning.blockchain.fee.FeeratePerByte
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.channel.states.*
import fr.acinq.lightning.crypto.noise.*
import fr.acinq.lightning.db.*
import fr.acinq.lightning.payment.*
import fr.acinq.lightning.message.Postman
import fr.acinq.lightning.serialization.Encryption.from
import fr.acinq.lightning.serialization.Serialization.DeserializationResult
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.*
import fr.acinq.lightning.wire.Ping
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.*
import org.kodein.log.newLogger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed class PeerCommand

/**
 * Try to open a channel, consuming all the spendable utxos in the wallet state provided.
 */
data class RequestChannelOpen(val requestId: ByteVector32, val walletInputs: List<WalletState.Utxo>) : PeerCommand()

/** Open a channel, consuming all the spendable utxos in the wallet state provided. */
data class OpenChannel(
    val fundingAmount: Satoshi,
    val pushAmount: MilliSatoshi,
    val walletInputs: List<WalletState.Utxo>,
    val commitTxFeerate: FeeratePerKw,
    val fundingTxFeerate: FeeratePerKw,
    val channelFlags: Byte,
    val channelType: ChannelType.SupportedChannelType
) : PeerCommand()

data class PeerConnection(val id: Long, val output: Channel<LightningMessage>, val logger: MDCLogger) {
    fun send(msg: LightningMessage) {
        // We can safely use trySend because we use unlimited channel buffers.
        // If the connection was closed, the message will automatically be dropped.
        val result = output.trySend(msg)
        result.onFailure { failure -> logger.warning(failure) { "cannot send $msg" } }
    }
}

data class Connected(val peerConnection: PeerConnection) : PeerCommand()
data class MessageReceived(val connectionId: Long, val msg: LightningMessage) : PeerCommand()
data class WatchReceived(val watch: WatchEvent) : PeerCommand()
data class WrappedChannelCommand(val channelId: ByteVector32, val channelCommand: ChannelCommand) : PeerCommand()
object Disconnected : PeerCommand()

sealed class PaymentCommand : PeerCommand()
private object CheckPaymentsTimeout : PaymentCommand()
data class PayToOpenResponseCommand(val payToOpenResponse: PayToOpenResponse) : PeerCommand()
data class SendPayment(val paymentId: UUID, val amount: MilliSatoshi, val recipient: PublicKey, val paymentRequest: PaymentRequest, val trampolineFeesOverride: List<TrampolineFees>? = null) : PaymentCommand() {
    val paymentHash: ByteVector32 = paymentRequest.paymentHash
}

data class PurgeExpiredPayments(val fromCreatedAt: Long, val toCreatedAt: Long) : PaymentCommand()

sealed class PeerEvent
data class PaymentReceived(val incomingPayment: IncomingPayment, val received: IncomingPayment.Received) : PeerEvent()
data class PaymentProgress(val request: SendPayment, val fees: MilliSatoshi) : PeerEvent()
data class PaymentNotSent(val request: SendPayment, val reason: OutgoingPaymentFailure) : PeerEvent()
data class PaymentSent(val request: SendPayment, val payment: LightningOutgoingPayment) : PeerEvent()
data class ChannelClosing(val channelId: ByteVector32) : PeerEvent()

/**
 * Useful to handle transparent migration on Phoenix Android between eclair-core and lightning-kmp.
 */
data class PhoenixAndroidLegacyInfoEvent(val info: PhoenixAndroidLegacyInfo) : PeerEvent()

/**
 * The peer we establish a connection to. This object contains the TCP socket, a flow of the channels with that peer, and watches
 * the events on those channels and processes the relevant actions. The dialogue with the peer is done in coroutines.
 *
 * @param nodeParams Low level, Lightning related parameters that our node will use in relation to this Peer.
 * @param walletParams High level parameters for our node. It especially contains the Peer's [NodeUri].
 * @param watcher Watches events from the Electrum client and publishes transactions and events.
 * @param db Wraps the various databases persisting the channels and payments data related to the Peer.
 * @param socketBuilder Builds the TCP socket used to connect to the Peer.
 * @param trustedSwapInTxs a set of txids that can be used for swap-in even if they are zeroconf (useful when migrating from the legacy phoenix android app).
 * @param initTlvStream Optional stream of TLV for the [Init] message we send to this Peer after connection. Empty by default.
 */
@OptIn(ExperimentalStdlibApi::class)
class Peer(
    val nodeParams: NodeParams,
    val walletParams: WalletParams,
    val watcher: ElectrumWatcher,
    val db: Databases,
    socketBuilder: TcpSocket.Builder?,
    scope: CoroutineScope,
    private val trustedSwapInTxs: Set<TxId> = emptySet(),
    private val initTlvStream: TlvStream<InitTlv> = TlvStream.empty()
) : CoroutineScope by scope {
    companion object {
        private const val prefix: Byte = 0x00
        private val prologue = "lightning".encodeToByteArray()
    }

    var socketBuilder: TcpSocket.Builder? = socketBuilder
        set(value) {
            logger.debug { "swap socket builder=$value" }
            field = value
        }

    val remoteNodeId: PublicKey = walletParams.trampolineNode.id

    // We use unlimited buffers, otherwise we may end up in a deadlock since we're both
    // receiving *and* sending to those channels in the same coroutine.
    private val input = Channel<PeerCommand>(UNLIMITED)

    private val swapInCommands = Channel<SwapInCommand>(UNLIMITED)

    private val logger = MDCLogger(nodeParams.loggerFactory.newLogger(this::class), staticMdc = mapOf("remoteNodeId" to remoteNodeId))

    // The channels map, as initially loaded from the database at "boot" (on Peer.init).
    // As the channelsFlow is unavailable until the electrum connection is up-and-running,
    // this may provide useful information for the UI.
    private val _bootChannelsFlow = MutableStateFlow<Map<ByteVector32, ChannelState>?>(null)
    val bootChannelsFlow: StateFlow<Map<ByteVector32, ChannelState>?> get() = _bootChannelsFlow

    // channels map, indexed by channel id
    // note that a channel starts with a temporary id then switches to its final id once accepted
    private val _channelsFlow = MutableStateFlow<Map<ByteVector32, ChannelState>>(HashMap())
    val channelsFlow: StateFlow<Map<ByteVector32, ChannelState>> get() = _channelsFlow

    private var _channels by _channelsFlow
    val channels: Map<ByteVector32, ChannelState> get() = _channelsFlow.value

    // pending requests asking our peer to open a channel to us
    private var channelRequests: Map<ByteVector32, RequestChannelOpen> = HashMap()

    private val _connectionState = MutableStateFlow<Connection>(Connection.CLOSED(null))
    val connectionState: StateFlow<Connection> get() = _connectionState

    private val _eventsFlow = MutableSharedFlow<PeerEvent>(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
    val eventsFlow: SharedFlow<PeerEvent> get() = _eventsFlow.asSharedFlow()

    // encapsulates logic for validating incoming payments
    private val incomingPaymentHandler = IncomingPaymentHandler(nodeParams, db.payments)

    // encapsulates logic for sending payments
    private val outgoingPaymentHandler = OutgoingPaymentHandler(nodeParams, walletParams, db.payments)

    private val features = nodeParams.features

    private val ourInit = Init(features.initFeatures(), initTlvStream)
    private var theirInit: Init? = null

    val currentTipFlow = MutableStateFlow<Pair<Int, BlockHeader>?>(null)
    val onChainFeeratesFlow = MutableStateFlow<OnChainFeerates?>(null)
    val swapInFeeratesFlow = MutableStateFlow<FeeratePerKw?>(null)

    private val _channelLogger = nodeParams.loggerFactory.newLogger(ChannelState::class)
    private suspend fun ChannelState.process(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        val state = this
        val ctx = ChannelContext(
            StaticParams(nodeParams, remoteNodeId),
            currentTipFlow.filterNotNull().first().first,
            onChainFeeratesFlow.filterNotNull().first(),
            logger = MDCLogger(
                logger = _channelLogger,
                staticMdc = mapOf("remoteNodeId" to remoteNodeId) + state.mdc()
            )
        )
        return state.run { ctx.process(cmd) }
            .also { (state1, _) ->
                if (state1::class != state::class) {
                    ctx.logger.info { "${state.stateName} -> ${state1.stateName}" }
                }
            }
    }

    val finalWallet = ElectrumMiniWallet(nodeParams.chainHash, watcher.client, scope, nodeParams.loggerFactory, name = "final")
    val finalAddress: String = nodeParams.keyManager.finalOnChainWallet.address(addressIndex = 0L).also { finalWallet.addAddress(it) }

    val swapInWallet = ElectrumMiniWallet(nodeParams.chainHash, watcher.client, scope, nodeParams.loggerFactory, name = "swap-in")
    val swapInAddress: String = nodeParams.keyManager.swapInOnChainWallet.address.also { swapInWallet.addAddress(it) }

    private var swapInJob: Job? = null

    private val postman = Postman(nodeParams.nodePrivateKey, ::sendOnionMessage)

    init {
        logger.info { "initializing peer" }
        launch {
            watcher.client.notifications.filterIsInstance<HeaderSubscriptionResponse>()
                .collect { msg ->
                    currentTipFlow.value = msg.blockHeight to msg.header
                }
        }
        launch {
            watcher.client.connectionStatus.filter { it is ElectrumConnectionStatus.Connected }.collect {
                // onchain fees are retrieved punctually, when electrum status moves to Connection.ESTABLISHED
                // since the application is not running most of the time, and when it is, it will be only for a few minutes, this is good enough.
                // (for a node that is online most of the time things would be different and we would need to re-evaluate onchain fee estimates on a regular basis)
                updateEstimateFees()
            }
        }
        launch {
            watcher.openWatchNotificationsFlow().collect {
                logger.debug { "notification: $it" }
                input.send(WrappedChannelCommand(it.channelId, ChannelCommand.WatchReceived(it)))
            }
        }
        launch {
            finalWallet.walletStateFlow
                .distinctUntilChangedBy { it.totalBalance }
                .collect { wallet ->
                    logger.info { "${wallet.totalBalance} available on final wallet with ${wallet.utxos.size} utxos" }
                }
        }
        launch {
            // we don't restore closed channels
            val bootChannels = db.channels.listLocalChannels().filterNot { it is Closed || it is LegacyWaitForFundingConfirmed }
            _bootChannelsFlow.value = bootChannels.associateBy { it.channelId }
            val channelIds = bootChannels.map {
                logger.info { "restoring channel ${it.channelId} from local storage" }
                val state = WaitForInit
                val (state1, actions) = state.process(ChannelCommand.Init.Restore(it))
                processActions(it.channelId, peerConnection, actions)
                _channels = _channels + (it.channelId to state1)
                it.channelId
            }
            logger.info { "restored ${channelIds.size} channels" }
            launch {
                // the swap-in manager executes commands, but will not do anything until startWatchSwapInWallet() is called
                val swapInManager = SwapInManager(bootChannels, logger)
                processSwapInCommands(swapInManager)
            }
            launch {
                // If we have some htlcs that have timed out, we may need to close channels to ensure we don't lose funds.
                // But maybe we were offline for too long and it is why our peer couldn't settle these htlcs in time.
                // We give them a bit of time after we reconnect to send us their latest htlc updates.
                delay(timeMillis = nodeParams.checkHtlcTimeoutAfterStartupDelaySeconds.toLong() * 1000)
                logger.info { "checking for timed out htlcs for channels: ${channelIds.joinToString(", ")}" }
                channelIds.forEach { input.send(WrappedChannelCommand(it, ChannelCommand.Commitment.CheckHtlcTimeout)) }
            }
            run()
        }
        launch {
            var previousState = connectionState.value
            connectionState.filter { it != previousState }.collect {
                logger.info { "connection state changed: ${it::class.simpleName}" }
                previousState = it
            }
        }
    }

    private suspend fun updateEstimateFees() {
        watcher.client.connectionStatus.filter { it is ElectrumConnectionStatus.Connected }.first()
        val sortedFees = listOf(
            watcher.client.estimateFees(2),
            watcher.client.estimateFees(6),
            watcher.client.estimateFees(18),
            watcher.client.estimateFees(144),
        )
        logger.info { "on-chain fees: $sortedFees" }
        // TODO: If some feerates are null, we may implement a retry
        onChainFeeratesFlow.value = OnChainFeerates(
            fundingFeerate = sortedFees[3] ?: FeeratePerKw(FeeratePerByte(2.sat)),
            mutualCloseFeerate = sortedFees[2] ?: FeeratePerKw(FeeratePerByte(10.sat)),
            claimMainFeerate = sortedFees[1] ?: FeeratePerKw(FeeratePerByte(20.sat)),
            fastFeerate = sortedFees[0] ?: FeeratePerKw(FeeratePerByte(50.sat))
        )
    }

    data class ConnectionJob(val job: Job, val socket: TcpSocket) {
        fun cancel() {
            job.cancel()
            socket.close()
        }
    }

    private var connectionJob: ConnectionJob? = null

    suspend fun connect(connectTimeout: Duration, handshakeTimeout: Duration): Boolean {
        return if (connectionState.value is Connection.CLOSED) {
            // Clean up previous connection state: we do this here to ensure that it is handled before the Connected event for the new connection.
            // That means we're not sending this event if we don't reconnect. It's ok, since that has the same effect as not detecting a disconnection and closing the app.
            input.send(Disconnected)
            _connectionState.value = Connection.ESTABLISHING

            val connectionId = currentTimestampMillis()
            val logger = MDCLogger(nodeParams.loggerFactory.newLogger(this::class), staticMdc = mapOf("remoteNodeId" to remoteNodeId, "connectionId" to connectionId))
            logger.info { "connecting to ${walletParams.trampolineNode.host}" }
            val socket = openSocket(connectTimeout) ?: return false

            val priv = nodeParams.nodePrivateKey
            val pub = priv.publicKey()
            val keyPair = Pair(pub.value.toByteArray(), priv.value.toByteArray())
            val (enc, dec, ck) = try {
                withTimeout(handshakeTimeout) {
                    handshake(
                        keyPair,
                        remoteNodeId.value.toByteArray(),
                        { s -> socket.receiveFully(s) },
                        { b -> socket.send(b) }
                    )
                }
            } catch (ex: TcpSocket.IOException) {
                logger.warning(ex) { "Noise handshake: ${ex.message}: " }
                socket.close()
                _connectionState.value = Connection.CLOSED(ex)
                return false
            }

            val session = LightningSession(enc, dec, ck)
            // TODO use atomic counter instead
            val peerConnection = PeerConnection(connectionId, Channel(UNLIMITED), logger)
            // Inform the peer about the new connection.
            input.send(Connected(peerConnection))
            connectionJob = connectionLoop(socket, session, peerConnection, logger)
            true
        } else {
            logger.warning { "Peer is already connecting / connected" }
            false
        }
    }

    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        _connectionState.value = Connection.CLOSED(null)
    }

    private suspend fun openSocket(timeout: Duration): TcpSocket? {
        var socket: TcpSocket? = null
        return try {
            withTimeout(timeout) {
                socket = socketBuilder?.connect(
                    host = walletParams.trampolineNode.host,
                    port = walletParams.trampolineNode.port,
                    tls = TcpSocket.TLS.DISABLED,
                    loggerFactory = nodeParams.loggerFactory
                ) ?: error("socket builder is null.")
                socket
            }
        } catch (ex: Throwable) {
            logger.warning(ex) { "TCP connect: ${ex.message}: " }
            val ioException = when (ex) {
                is TcpSocket.IOException -> ex
                else -> TcpSocket.IOException.ConnectionRefused(ex)
            }
            socket?.close()
            _connectionState.value = Connection.CLOSED(ioException)
            null
        }
    }

    private fun connectionLoop(socket: TcpSocket, session: LightningSession, peerConnection: PeerConnection, logger: MDCLogger): ConnectionJob {
        val job = launch {
            fun closeSocket(ex: TcpSocket.IOException?) {
                if (_connectionState.value is Connection.CLOSED) return
                logger.warning(ex) { "closing TCP socket: " }
                socket.close()
                _connectionState.value = Connection.CLOSED(ex)
                cancel()
            }

            suspend fun doPing() {
                val ping = Ping(10, ByteVector("deadbeef"))
                while (isActive) {
                    delay(30.seconds)
                    peerConnection.send(ping)
                }
            }

            suspend fun checkPaymentsTimeout() {
                while (isActive) {
                    delay(10.seconds) // we schedule a check every 10 seconds
                    input.send(CheckPaymentsTimeout)
                }
            }

            suspend fun receiveLoop() {
                try {
                    while (isActive) {
                        val received = session.receive { size -> socket.receiveFully(size) }
                        try {
                            val msg = LightningMessage.decode(received)
                            input.send(MessageReceived(peerConnection.id, msg))
                        } catch (e: Throwable) {
                            logger.warning { "cannot deserialize message: ${received.byteVector().toHex()}" }
                        }
                    }
                    closeSocket(null)
                } catch (ex: TcpSocket.IOException) {
                    logger.warning { "TCP receive: ${ex.message}" }
                    closeSocket(ex)
                } finally {
                    peerConnection.output.close()
                }
            }

            suspend fun sendLoop() {
                try {
                    for (msg in peerConnection.output) {
                        // Avoids polluting the logs with pings/pongs
                        if (msg !is Ping && msg !is Pong) logger.info { "sending $msg" }
                        val encoded = LightningMessage.encode(msg)
                        session.send(encoded) { data, flush -> socket.send(data, flush) }
                    }
                } catch (ex: TcpSocket.IOException) {
                    logger.warning { "TCP send: ${ex.message}" }
                    closeSocket(ex)
                } finally {
                    peerConnection.output.close()
                }
            }

            launch(CoroutineName("keep-alive")) { doPing() }
            launch(CoroutineName("check-payments-timeout")) { checkPaymentsTimeout() }
            launch(CoroutineName("send-loop")) { sendLoop() }
            val receiveJob = launch(CoroutineName("receive-loop")) { receiveLoop() }
            // Suspend until the coroutine is cancelled or the socket is closed.
            receiveJob.join()
        }

        return ConnectionJob(job, socket)
    }

    /** We try swapping funds in whenever one of those fields is updated. */
    data class TrySwapInFlow(val currentBlockHeight: Int, val walletState: WalletState, val feerate: FeeratePerKw, val liquidityPolicy: LiquidityPolicy)

    /**
     * This function needs to be called after [Peer] is initialized, to start watching the swap-in wallet
     * and trigger swap-ins.
     * Warning: not thread-safe!
     */
    suspend fun startWatchSwapInWallet() {
        logger.info { "starting swap-in watch job" }
        if (swapInJob != null) {
            logger.info { "swap-in watch job already started" }
            return
        }
        logger.info { "waiting for peer to be ready" }
        waitForPeerReady()
        swapInJob = launch {
            swapInWallet.walletStateFlow
                .filter { it.consistent }
                .combine(currentTipFlow.filterNotNull()) { walletState, currentTip -> Pair(walletState, currentTip.first) }
                .combine(swapInFeeratesFlow.filterNotNull()) { (walletState, currentTip), feerate -> Triple(walletState, currentTip, feerate) }
                .combine(nodeParams.liquidityPolicy) { (walletState, currentTip, feerate), policy -> TrySwapInFlow(currentTip, walletState, feerate, policy) }
                .collect { w ->
                    // Local mutual close txs from pre-splice channels can be used as zero-conf inputs for swap-in to facilitate migration
                    val mutualCloseTxs = channels.values
                        .filterIsInstance<Closing>()
                        .filterNot { it.commitments.params.channelFeatures.hasFeature(Feature.DualFunding) }
                        .flatMap { state -> state.mutualClosePublished.map { closingTx -> closingTx.tx.txid } }
                    val trustedTxs = trustedSwapInTxs + mutualCloseTxs
                    swapInCommands.send(SwapInCommand.TrySwapIn(w.currentBlockHeight, w.walletState, walletParams.swapInParams, trustedTxs))
                }
        }
    }

    suspend fun stopWatchSwapInWallet() {
        logger.info { "stopping swap-in watch job" }
        swapInJob?.cancelAndJoin()
        swapInJob = null
    }

    private suspend fun processSwapInCommands(swapInManager: SwapInManager) {
        for (command in swapInCommands) {
            swapInManager.process(command)?.let { requestChannelOpen -> input.send(requestChannelOpen) }
        }
    }

    suspend fun send(cmd: PeerCommand) {
        input.send(cmd)
    }

    /**
     * This function blocks until the peer is connected and existing channels have been fully reestablished.
     */
    private suspend fun waitForPeerReady() {
        // In theory we would only need to verify that no channel is in state Offline/Syncing, but there is a corner
        // case where a channel permanently stays in Syncing, because it is only present locally, and the peer will
        // never send a channel_reestablish (this happens e.g. due to an error at funding). That is why we consider
        // the peer ready if "all channels are synced" OR "peer has been connected for 10s".
        connectionState.first { it is Connection.ESTABLISHED }
        val result = withTimeoutOrNull(10.seconds) {
            channelsFlow.first { it.values.all { channel -> channel !is Offline && channel !is Syncing } }
        }
        if (result == null) {
            logger.info { "peer ready timeout elapsed, not all channels are synced but proceeding anyway" }
        }
    }

    /**
     * Estimate the actual feerate to use (and corresponding fee to pay) in order to reach the target feerate
     * for a splice out, taking into account potential unconfirmed parent splices.
     */
    suspend fun estimateFeeForSpliceOut(amount: Satoshi, scriptPubKey: ByteVector, targetFeerate: FeeratePerKw): Pair<FeeratePerKw, Satoshi>? {
        return channels.values
            .filterIsInstance<Normal>()
            .firstOrNull { it.commitments.availableBalanceForSend() > amount }
            ?.let { channel ->
                val weight = FundingContributions.computeWeightPaid(isInitiator = true, commitment = channel.commitments.active.first(), walletInputs = emptyList(), localOutputs = listOf(TxOut(amount, scriptPubKey)))
                watcher.client.computeSpliceCpfpFeerate(channel.commitments, targetFeerate, spliceWeight = weight, logger)
            }
    }

    /**
     * Estimate the actual feerate to use (and corresponding fee to pay) in order to reach the target feerate
     * for a cpfp splice.
     * @return The adjusted feerate to use in [spliceCpfp], such that the whole transaction chain has a feerate equivalent
     *         to [targetFeerate].
     *         NB: if the output feerate is equal to the input feerate then the cpfp is useless and
     *         should not be attempted.
     */
    suspend fun estimateFeeForSpliceCpfp(channelId: ByteVector32, targetFeerate: FeeratePerKw): Pair<FeeratePerKw, Satoshi>? {
        return channels.values
            .filterIsInstance<Normal>()
            .find { it.channelId == channelId }
            ?.let { channel ->
                val weight = FundingContributions.computeWeightPaid(isInitiator = true, commitment = channel.commitments.active.first(), walletInputs = emptyList(), localOutputs = emptyList())
                watcher.client.computeSpliceCpfpFeerate(channel.commitments, targetFeerate, spliceWeight = weight, logger)
            }
    }

    /**
     * Do a splice out using any suitable channel
     * @return  [ChannelCommand.Commitment.Splice.Response] if a splice was attempted, or {null} if no suitable
     *          channel was found
     */
    suspend fun spliceOut(amount: Satoshi, scriptPubKey: ByteVector, feerate: FeeratePerKw): ChannelCommand.Commitment.Splice.Response? {
        return channels.values
            .filterIsInstance<Normal>()
            .firstOrNull { it.commitments.availableBalanceForSend() > amount }
            ?.let { channel ->
                val spliceCommand = ChannelCommand.Commitment.Splice.Request(
                    replyTo = CompletableDeferred(),
                    spliceIn = null,
                    spliceOut = ChannelCommand.Commitment.Splice.Request.SpliceOut(amount, scriptPubKey),
                    feerate = feerate
                )
                send(WrappedChannelCommand(channel.channelId, spliceCommand))
                spliceCommand.replyTo.await()
            }
    }

    suspend fun spliceCpfp(channelId: ByteVector32, feerate: FeeratePerKw): ChannelCommand.Commitment.Splice.Response? {
        return channels.values
            .filterIsInstance<Normal>()
            .find { it.channelId == channelId }
            ?.let { channel ->
                val spliceCommand = ChannelCommand.Commitment.Splice.Request(
                    replyTo = CompletableDeferred(),
                    // no additional inputs or outputs, the splice is only meant to bump fees
                    spliceIn = null,
                    spliceOut = null,
                    feerate = feerate
                )
                send(WrappedChannelCommand(channel.channelId, spliceCommand))
                spliceCommand.replyTo.await()
            }
    }

    suspend fun createInvoice(paymentPreimage: ByteVector32, amount: MilliSatoshi?, description: Either<String, ByteVector32>, expirySeconds: Long? = null): Bolt11Invoice {
        // we add one extra hop which uses a virtual channel with a "peer id", using the highest remote fees and expiry across all
        // channels to maximize the likelihood of success on the first payment attempt
        val remoteChannelUpdates = _channels.values.mapNotNull { channelState ->
            when (channelState) {
                is Normal -> channelState.remoteChannelUpdate
                is Offline -> (channelState.state as? Normal)?.remoteChannelUpdate
                is Syncing -> (channelState.state as? Normal)?.remoteChannelUpdate
                else -> null
            }
        }
        val extraHops = listOf(
            listOf(
                Bolt11Invoice.TaggedField.ExtraHop(
                    nodeId = walletParams.trampolineNode.id,
                    shortChannelId = ShortChannelId.peerId(nodeParams.nodeId),
                    feeBase = remoteChannelUpdates.maxOfOrNull { it.feeBaseMsat } ?: walletParams.invoiceDefaultRoutingFees.feeBase,
                    feeProportionalMillionths = remoteChannelUpdates.maxOfOrNull { it.feeProportionalMillionths } ?: walletParams.invoiceDefaultRoutingFees.feeProportional,
                    cltvExpiryDelta = remoteChannelUpdates.maxOfOrNull { it.cltvExpiryDelta } ?: walletParams.invoiceDefaultRoutingFees.cltvExpiryDelta
                )
            )
        )
        return incomingPaymentHandler.createInvoice(paymentPreimage, amount, description, extraHops, expirySeconds)
    }

    // The (node_id, fcm_token) tuple only needs to be registered once.
    // And after that, only if the tuple changes (e.g. different fcm_token).
    fun registerFcmToken(token: String?) {
        val message = if (token == null) UnsetFCMToken else FCMToken(token)
        peerConnection?.send(message)
    }

    private suspend fun processActions(channelId: ByteVector32, peerConnection: PeerConnection?, actions: List<ChannelAction>) {
        // we peek into the actions to see if the id of the channel is going to change, but we're not processing it yet
        val actualChannelId = actions.filterIsInstance<ChannelAction.ChannelId.IdAssigned>().firstOrNull()?.channelId ?: channelId
        logger.withMDC(mapOf("channelId" to actualChannelId)) { logger ->
            actions.forEach { action ->
                when (action) {
                    is ChannelAction.Message.Send -> peerConnection?.send(action.message) // ignore if disconnected
                    // sometimes channel actions include "self" command (such as ChannelCommand.Commitment.Sign)
                    is ChannelAction.Message.SendToSelf -> input.send(WrappedChannelCommand(actualChannelId, action.command))
                    is ChannelAction.Blockchain.SendWatch -> watcher.watch(action.watch)
                    is ChannelAction.Blockchain.PublishTx -> watcher.publish(action.tx)
                    is ChannelAction.ProcessIncomingHtlc -> processIncomingPayment(Either.Right(action.add))
                    is ChannelAction.ProcessCmdRes.NotExecuted -> logger.warning(action.t) { "command not executed" }
                    is ChannelAction.ProcessCmdRes.AddFailed -> {
                        when (val result = outgoingPaymentHandler.processAddFailed(actualChannelId, action, _channels)) {
                            is OutgoingPaymentHandler.Progress -> {
                                _eventsFlow.emit(PaymentProgress(result.request, result.fees))
                                result.actions.forEach { input.send(it) }
                            }

                            is OutgoingPaymentHandler.Failure -> _eventsFlow.emit(PaymentNotSent(result.request, result.failure))
                            null -> logger.debug { "non-final error, more partial payments are still pending: ${action.error.message}" }
                        }
                    }

                    is ChannelAction.ProcessCmdRes.AddSettledFail -> {
                        val currentTip = currentTipFlow.filterNotNull().first()
                        when (val result = outgoingPaymentHandler.processAddSettled(actualChannelId, action, _channels, currentTip.first)) {
                            is OutgoingPaymentHandler.Progress -> {
                                _eventsFlow.emit(PaymentProgress(result.request, result.fees))
                                result.actions.forEach { input.send(it) }
                            }

                            is OutgoingPaymentHandler.Success -> _eventsFlow.emit(PaymentSent(result.request, result.payment))
                            is OutgoingPaymentHandler.Failure -> _eventsFlow.emit(PaymentNotSent(result.request, result.failure))
                            null -> logger.debug { "non-final error, more partial payments are still pending: ${action.result}" }
                        }
                    }

                    is ChannelAction.ProcessCmdRes.AddSettledFulfill -> {
                        when (val result = outgoingPaymentHandler.processAddSettled(action)) {
                            is OutgoingPaymentHandler.Success -> _eventsFlow.emit(PaymentSent(result.request, result.payment))
                            is OutgoingPaymentHandler.PreimageReceived -> logger.debug(mapOf("paymentId" to result.request.paymentId)) { "payment preimage received: ${result.preimage}" }
                            null -> logger.debug { "unknown payment" }
                        }
                    }

                    is ChannelAction.Storage.StoreState -> {
                        logger.info { "storing state=${action.data::class.simpleName}" }
                        db.channels.addOrUpdateChannel(action.data)
                    }

                    is ChannelAction.Storage.RemoveChannel -> {
                        logger.info { "removing channelId=${action.data.channelId} state=${action.data::class.simpleName}" }
                        db.channels.removeChannel(action.data.channelId)
                    }

                    is ChannelAction.Storage.StoreHtlcInfos -> {
                        action.htlcs.forEach { db.channels.addHtlcInfo(actualChannelId, it.commitmentNumber, it.paymentHash, it.cltvExpiry) }
                    }

                    is ChannelAction.Storage.StoreIncomingPayment -> {
                        logger.info { "storing incoming payment $action" }
                        incomingPaymentHandler.process(actualChannelId, action)
                    }

                    is ChannelAction.Storage.StoreOutgoingPayment -> {
                        logger.info { "storing $action" }
                        db.payments.addOutgoingPayment(
                            when (action) {
                                is ChannelAction.Storage.StoreOutgoingPayment.ViaSpliceOut ->
                                    SpliceOutgoingPayment(
                                        id = UUID.randomUUID(),
                                        recipientAmount = action.amount,
                                        address = action.address,
                                        miningFees = action.miningFees,
                                        channelId = channelId,
                                        txId = action.txId,
                                        createdAt = currentTimestampMillis(),
                                        confirmedAt = null,
                                        lockedAt = null
                                    )
                                is ChannelAction.Storage.StoreOutgoingPayment.ViaSpliceCpfp ->
                                    SpliceCpfpOutgoingPayment(
                                        id = UUID.randomUUID(),
                                        miningFees = action.miningFees,
                                        channelId = channelId,
                                        txId = action.txId,
                                        createdAt = currentTimestampMillis(),
                                        confirmedAt = null,
                                        lockedAt = null
                                    )
                                is ChannelAction.Storage.StoreOutgoingPayment.ViaClose ->
                                    ChannelCloseOutgoingPayment(
                                        id = UUID.randomUUID(),
                                        recipientAmount = action.amount,
                                        address = action.address,
                                        isSentToDefaultAddress = action.isSentToDefaultAddress,
                                        miningFees = action.miningFees,
                                        channelId = channelId,
                                        txId = action.txId,
                                        createdAt = currentTimestampMillis(),
                                        confirmedAt = null,
                                        lockedAt = currentTimestampMillis(), // channel close are not splices, they are final
                                        closingType = action.closingType
                                    )
                            }
                        )
                        _eventsFlow.emit(ChannelClosing(channelId))
                    }

                    is ChannelAction.Storage.SetLocked -> {
                        logger.info { "setting status locked for txid=${action.txId}" }
                        db.payments.setLocked(action.txId)
                    }

                    is ChannelAction.Storage.GetHtlcInfos -> {
                        val htlcInfos = db.channels.listHtlcInfos(actualChannelId, action.commitmentNumber).map { ChannelAction.Storage.HtlcInfo(actualChannelId, action.commitmentNumber, it.first, it.second) }
                        input.send(WrappedChannelCommand(actualChannelId, ChannelCommand.Closing.GetHtlcInfosResponse(action.revokedCommitTxId, htlcInfos)))
                    }

                    is ChannelAction.ChannelId.IdAssigned -> {
                        logger.info { "switching channel id from ${action.temporaryChannelId} to ${action.channelId}" }
                        _channels[action.temporaryChannelId]?.let { _channels = _channels + (action.channelId to it) - action.temporaryChannelId }
                    }

                    is ChannelAction.EmitEvent -> nodeParams._nodeEvents.emit(action.event)
                }
            }
        }
    }

    private suspend fun processIncomingPayment(item: Either<PayToOpenRequest, UpdateAddHtlc>) {
        val currentBlockHeight = currentTipFlow.filterNotNull().first().first
        val result = when (item) {
            is Either.Right -> incomingPaymentHandler.process(item.value, currentBlockHeight)
            is Either.Left -> incomingPaymentHandler.process(item.value, currentBlockHeight)
        }
        when (result) {
            is IncomingPaymentHandler.ProcessAddResult.Accepted -> _eventsFlow.emit(PaymentReceived(result.incomingPayment, result.received))
            else -> Unit
        }
        result.actions.forEach { input.send(it) }
    }

    private suspend fun handshake(
        ourKeys: Pair<ByteArray, ByteArray>,
        theirPubkey: ByteArray,
        r: suspend (Int) -> ByteArray,
        w: suspend (ByteArray) -> Unit
    ): Triple<CipherState, CipherState, ByteArray> {

        /**
         * See BOLT #8: during the handshake phase we are expecting 3 messages of 50, 50 and 66 bytes (including the prefix)
         *
         * @param reader handshake state reader
         * @return the size of the message the reader is expecting
         */
        fun expectedLength(reader: HandshakeStateReader): Int = when (reader.messages.size) {
            3, 2 -> 50
            1 -> 66
            else -> throw RuntimeException("invalid state")
        }

        val writer = HandshakeState.initializeWriter(
            handshakePatternXK, prologue,
            ourKeys, Pair(ByteArray(0), ByteArray(0)), theirPubkey, ByteArray(0),
            Secp256k1DHFunctions, Chacha20Poly1305CipherFunctions, SHA256HashFunctions
        )
        val (state1, message, _) = writer.write(ByteArray(0))
        w(byteArrayOf(prefix) + message)

        val payload = r(expectedLength(state1))
        require(payload[0] == prefix)

        val (writer1, _, _) = state1.read(payload.drop(1).toByteArray())
        val (_, message1, foo) = writer1.write(ByteArray(0))
        val (enc, dec, ck) = foo!!
        w(byteArrayOf(prefix) + message1)
        return Triple(enc, dec, ck)
    }

    private suspend fun run() {
        logger.info { "peer is active" }
        for (event in input) {
            logger.withMDC(logger.staticMdc + (peerConnection?.logger?.staticMdc ?: emptyMap()) + ((event as? MessageReceived)?.msg?.mdc() ?: emptyMap())) { logger ->
                processEvent(event, logger)
            }
        }
    }

    // MUST ONLY BE SET BY processEvent()
    private var peerConnection: PeerConnection? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun processEvent(cmd: PeerCommand, logger: MDCLogger) {
        when (cmd) {
            is Connected -> {
                logger.info { "new connection with id=${cmd.peerConnection.id}, sending init $ourInit" }
                peerConnection = cmd.peerConnection
                peerConnection?.send(ourInit)
            }
            is MessageReceived -> {
                if (cmd.connectionId != peerConnection?.id) {
                    logger.warning { "ignoring ${cmd.msg} for connectionId=${cmd.connectionId}" }
                    return
                }
                val msg = cmd.msg
                msg.let { if (it !is Ping && it !is Pong) logger.info { "received $it" } }
                when (msg) {
                    is UnknownMessage -> {
                        logger.warning { "unhandled code=${msg.type}" }
                    }
                    is Init -> {
                        logger.info { "peer is using features ${msg.features}" }
                        when (val error = Features.validateFeatureGraph(msg.features)) {
                            is Features.Companion.FeatureException -> {
                                logger.error(error) { "feature validation error" }
                                // TODO: disconnect peer
                            }

                            else -> {
                                theirInit = msg
                                _connectionState.value = Connection.ESTABLISHED
                                _channels = _channels.mapValues { entry ->
                                    val (state1, actions) = entry.value.process(ChannelCommand.Connected(ourInit, theirInit!!))
                                    processActions(entry.key, peerConnection, actions)
                                    state1
                                }
                            }
                        }
                    }
                    is Ping -> {
                        val pong = Pong(ByteVector(ByteArray(msg.pongLength)))
                        peerConnection?.send(pong)
                    }
                    is Pong -> {
                        logger.debug { "received pong" }
                    }
                    is Warning -> {
                        // NB: we don't forward warnings to the channel because it shouldn't take any automatic action,
                        // these warnings are meant for humans.
                        logger.warning { "peer sent warning: ${msg.toAscii()}" }
                    }
                    is OpenDualFundedChannel -> {
                        if (theirInit == null) {
                            logger.error { "they sent open_channel before init" }
                        } else if (_channels.containsKey(msg.temporaryChannelId)) {
                            logger.warning { "ignoring open_channel with duplicate temporaryChannelId=${msg.temporaryChannelId}" }
                        } else {
                            val (walletInputs, fundingAmount, pushAmount) = when (val origin = msg.origin) {
                                is Origin.PleaseOpenChannelOrigin -> when (val request = channelRequests[origin.requestId]) {
                                    is RequestChannelOpen -> {
                                        val totalFee = origin.serviceFee + origin.miningFee.toMilliSatoshi() - msg.pushAmount
                                        nodeParams.liquidityPolicy.value.maybeReject(request.walletInputs.balance.toMilliSatoshi(), totalFee, LiquidityEvents.Source.OnChainWallet, logger)?.let { rejected ->
                                            logger.info { "rejecting open_channel2: reason=${rejected.reason}" }
                                            nodeParams._nodeEvents.emit(rejected)
                                            swapInCommands.send(SwapInCommand.UnlockWalletInputs(request.walletInputs.map { it.outPoint }.toSet()))
                                            peerConnection?.send(Error(msg.temporaryChannelId, "cancelling open due to local liquidity policy"))
                                            return
                                        }
                                        val fundingFee = Transactions.weight2fee(msg.fundingFeerate, request.walletInputs.size * Transactions.swapInputWeight)
                                        // We have to pay the fees for our inputs, so we deduce them from our funding amount.
                                        val fundingAmount = request.walletInputs.balance - fundingFee
                                        // We pay the other fees by pushing the corresponding amount
                                        val pushAmount = origin.serviceFee + origin.miningFee.toMilliSatoshi() - fundingFee.toMilliSatoshi()
                                        nodeParams._nodeEvents.emit(SwapInEvents.Accepted(request.requestId, serviceFee = origin.serviceFee, miningFee = origin.miningFee))
                                        Triple(request.walletInputs, fundingAmount, pushAmount)
                                    }

                                    else -> {
                                        logger.warning { "n:$remoteNodeId c:${msg.temporaryChannelId} rejecting open_channel2: cannot find channel request with requestId=${origin.requestId}" }
                                        peerConnection?.send(Error(msg.temporaryChannelId, "no corresponding channel request"))
                                        return
                                    }
                                }
                                else -> Triple(listOf(), 0.sat, 0.msat)
                            }
                            if (fundingAmount.toMilliSatoshi() < pushAmount) {
                                logger.warning { "rejecting open_channel2 with invalid funding and push amounts ($fundingAmount < $pushAmount)" }
                                peerConnection?.send(Error(msg.temporaryChannelId, InvalidPushAmount(msg.temporaryChannelId, pushAmount, fundingAmount.toMilliSatoshi()).message))
                            } else {
                                val localParams = LocalParams(nodeParams, isInitiator = false)
                                val state = WaitForInit
                                val channelConfig = ChannelConfig.standard
                                val (state1, actions1) = state.process(ChannelCommand.Init.NonInitiator(msg.temporaryChannelId, fundingAmount, pushAmount, walletInputs, localParams, channelConfig, theirInit!!))
                                val (state2, actions2) = state1.process(ChannelCommand.MessageReceived(msg))
                                _channels = _channels + (msg.temporaryChannelId to state2)
                                when (val origin = msg.origin) {
                                    is Origin.PleaseOpenChannelOrigin -> channelRequests = channelRequests - origin.requestId
                                    else -> Unit
                                }
                                processActions(msg.temporaryChannelId, peerConnection, actions1 + actions2)
                            }
                        }
                    }
                    is ChannelReestablish -> {
                        val local: ChannelState? = _channels[msg.channelId]
                        val backup: DeserializationResult? = msg.channelData.takeIf { !it.isEmpty() }?.let { channelData ->
                            PersistedChannelState
                                .from(nodeParams.nodePrivateKey, channelData)
                                .onFailure { logger.warning(it) { "unreadable backup" } }
                                .getOrNull()
                        }

                        suspend fun recoverChannel(recovered: PersistedChannelState) {
                            db.channels.addOrUpdateChannel(recovered)

                            val state = WaitForInit
                            val event1 = ChannelCommand.Init.Restore(recovered)
                            val (state1, actions1) = state.process(event1)
                            processActions(msg.channelId, peerConnection, actions1)

                            val event2 = ChannelCommand.Connected(ourInit, theirInit!!)
                            val (state2, actions2) = state1.process(event2)
                            processActions(msg.channelId, peerConnection, actions2)

                            val event3 = ChannelCommand.MessageReceived(msg)
                            val (state3, actions3) = state2.process(event3)
                            processActions(msg.channelId, peerConnection, actions3)
                            _channels = _channels + (msg.channelId to state3)
                        }

                        when {
                            backup is DeserializationResult.UnknownVersion -> {
                                logger.warning { "peer sent a reestablish with a backup generated by a more recent of phoenix: version=${backup.version}." }
                                // In this corner case, we do not want to return an error to the peer, because they will force-close and we will be unable to
                                // do anything as we can't read the data. Best thing is to not answer, and tell the user to upgrade the app.
                                logger.error { "need to upgrade your app!" }
                                nodeParams._nodeEvents.emit(UpgradeRequired)
                            }
                            local == null && backup == null -> {
                                logger.warning { "peer sent a reestablish for a unknown channel with no or undecipherable backup" }
                                peerConnection?.send(Error(msg.channelId, "unknown channel"))
                            }
                            local == null && backup is DeserializationResult.Success -> {
                                logger.warning { "recovering channel from peer backup" }
                                recoverChannel(backup.state)
                            }
                            local is Syncing && local.state is ChannelStateWithCommitments && backup is DeserializationResult.Success && backup.state is ChannelStateWithCommitments && backup.state.commitments.isMoreRecent(local.state.commitments) -> {
                                logger.warning { "recovering channel from peer backup (it is more recent)" }
                                recoverChannel(backup.state)
                            }
                            local is ChannelState -> {
                                val (state1, actions1) = local.process(ChannelCommand.MessageReceived(msg))
                                processActions(msg.channelId, peerConnection, actions1)
                                _channels = _channels + (msg.channelId to state1)
                            }
                        }
                    }
                    is HasTemporaryChannelId -> {
                        _channels[msg.temporaryChannelId]?.let { state ->
                            logger.info { "received ${msg::class.simpleName} for temporary channel ${msg.temporaryChannelId}" }
                            val event1 = ChannelCommand.MessageReceived(msg)
                            val (state1, actions) = state.process(event1)
                            _channels = _channels + (msg.temporaryChannelId to state1)
                            processActions(msg.temporaryChannelId, peerConnection, actions)
                        } ?: run {
                            logger.error { "received ${msg::class.simpleName} for unknown temporary channel ${msg.temporaryChannelId}" }
                            peerConnection?.send(Error(msg.temporaryChannelId, "unknown channel"))
                        }
                    }
                    is HasChannelId -> {
                        if (msg is Error && msg.channelId == ByteVector32.Zeroes) {
                            logger.error { "connection error: ${msg.toAscii()}" }
                        } else {
                            _channels[msg.channelId]?.let { state ->
                                val event1 = ChannelCommand.MessageReceived(msg)
                                val (state1, actions) = state.process(event1)
                                processActions(msg.channelId, peerConnection, actions)
                                _channels = _channels + (msg.channelId to state1)
                            } ?: run {
                                logger.error { "received ${msg::class.simpleName} for unknown channel ${msg.channelId}" }
                                peerConnection?.send(Error(msg.channelId, "unknown channel"))
                            }
                        }
                    }
                    is ChannelUpdate -> {
                        _channels.values.filterIsInstance<Normal>().find { it.shortChannelId == msg.shortChannelId }?.let { state ->
                            val event1 = ChannelCommand.MessageReceived(msg)
                            val (state1, actions) = state.process(event1)
                            processActions(state.channelId, peerConnection, actions)
                            _channels = _channels + (state.channelId to state1)
                        }
                    }
                    is PayToOpenRequest -> {
                        logger.info { "received ${msg::class.simpleName}" }
                        // If a channel is currently being created, it can't process splices yet. We could accept this payment, but
                        // it wouldn't be reflected in the user balance until the channel is ready, because we only insert
                        // the payment in db when we will process the corresponding splice and see the pay-to-open origin. This
                        // can take a long time depending on the confirmation speed. It is better and simpler to reject the incoming
                        // payment rather that having the user wonder where their money went.
                        val channelInitializing = _channels.isNotEmpty()
                                && !_channels.values.any { it is Normal } // we don't have a channel that can be spliced
                                && _channels.values.any { it is WaitForFundingSigned || it is WaitForFundingConfirmed || it is WaitForChannelReady } // but we will have one soon
                        if (channelInitializing) {
                            val rejected = LiquidityEvents.Rejected(msg.amountMsat, msg.payToOpenFeeSatoshis.toMilliSatoshi(), LiquidityEvents.Source.OffChainPayment, LiquidityEvents.Rejected.Reason.ChannelInitializing)
                            logger.info { "rejecting pay-to-open: reason=${rejected.reason}" }
                            nodeParams._nodeEvents.emit(rejected)
                            val action = IncomingPaymentHandler.actionForPayToOpenFailure(nodeParams.nodePrivateKey, TemporaryNodeFailure, msg)
                            input.send(action)
                        } else {
                            processIncomingPayment(Either.Left(msg))
                        }
                    }
                    is PhoenixAndroidLegacyInfo -> {
                        logger.info { "received ${msg::class.simpleName} hasChannels=${msg.hasChannels}" }
                        _eventsFlow.emit(PhoenixAndroidLegacyInfoEvent(msg))
                    }
                    is OnionMessage -> {
                        logger.info { "received ${msg::class.simpleName}" }
                        postman.processOnionMessage(msg)
                    }
                }
            }
            is WatchReceived -> {
                if (!_channels.containsKey(cmd.watch.channelId)) {
                    logger.error { "received watch event ${cmd.watch} for unknown channel ${cmd.watch.channelId}}" }
                } else {
                    val state = _channels[cmd.watch.channelId] ?: error("channel ${cmd.watch.channelId} not found")
                    val event1 = ChannelCommand.WatchReceived(cmd.watch)
                    val (state1, actions) = state.process(event1)
                    processActions(cmd.watch.channelId, peerConnection, actions)
                    _channels = _channels + (cmd.watch.channelId to state1)
                }
            }
            is RequestChannelOpen -> {
                when (val channel = channels.values.firstOrNull { it is Normal }) {
                    is Normal -> {
                        // we have a channel and we are connected (otherwise state would be Offline/Syncing)
                        val targetFeerate = swapInFeeratesFlow.filterNotNull().first()
                        val weight = FundingContributions.computeWeightPaid(isInitiator = true, commitment = channel.commitments.active.first(), walletInputs = cmd.walletInputs, localOutputs = emptyList())
                        val (feerate, fee) = watcher.client.computeSpliceCpfpFeerate(channel.commitments, targetFeerate, spliceWeight = weight, logger)

                        logger.info { "requesting splice-in using balance=${cmd.walletInputs.balance} feerate=$feerate fee=$fee" }
                        nodeParams.liquidityPolicy.value.maybeReject(cmd.walletInputs.balance.toMilliSatoshi(), fee.toMilliSatoshi(), LiquidityEvents.Source.OnChainWallet, logger)?.let { rejected ->
                            logger.info { "rejecting splice: reason=${rejected.reason}" }
                            nodeParams._nodeEvents.emit(rejected)
                            swapInCommands.send(SwapInCommand.UnlockWalletInputs(cmd.walletInputs.map { it.outPoint }.toSet()))
                            return
                        }

                        val spliceCommand = ChannelCommand.Commitment.Splice.Request(
                            replyTo = CompletableDeferred(),
                            spliceIn = ChannelCommand.Commitment.Splice.Request.SpliceIn(cmd.walletInputs),
                            spliceOut = null,
                            feerate = feerate
                        )
                        // If the splice fails, we immediately unlock the utxos to reuse them in the next attempt.
                        spliceCommand.replyTo.invokeOnCompletion { ex ->
                            if (ex == null && spliceCommand.replyTo.getCompleted() is ChannelCommand.Commitment.Splice.Response.Failure) {
                                swapInCommands.trySend(SwapInCommand.UnlockWalletInputs(cmd.walletInputs.map { it.outPoint }.toSet()))
                            }
                        }
                        input.send(WrappedChannelCommand(channel.channelId, spliceCommand))
                    }
                    else -> {
                        if (channels.values.all { it is ShuttingDown || it is Negotiating || it is Closing || it is Closed || it is Aborted }) {
                            // Either there are no channels, or they will never be suitable for a splice-in: we request a new channel.
                            // Grandparents are supplied as a proof of migration
                            val grandParents = cmd.walletInputs.map { utxo -> utxo.previousTx.txIn.map { txIn -> txIn.outPoint } }.flatten()
                            val pleaseOpenChannel = PleaseOpenChannel(
                                nodeParams.chainHash,
                                cmd.requestId,
                                cmd.walletInputs.balance,
                                cmd.walletInputs.size,
                                cmd.walletInputs.size * Transactions.swapInputWeight,
                                TlvStream(PleaseOpenChannelTlv.GrandParents(grandParents))
                            )
                            logger.info { "sending please_open_channel with ${cmd.walletInputs.size} utxos (amount = ${cmd.walletInputs.balance})" }
                            peerConnection?.send(pleaseOpenChannel)
                            nodeParams._nodeEvents.emit(SwapInEvents.Requested(pleaseOpenChannel))
                            channelRequests = channelRequests + (pleaseOpenChannel.requestId to cmd)
                        } else {
                            // There are existing channels but not immediately usable (e.g. creating, disconnected), we don't do anything yet
                            logger.info { "ignoring channel request, existing channels are not ready for splice-in: ${channels.values.map { it::class.simpleName }}" }
                        }
                    }
                }
            }
            is OpenChannel -> {
                val localParams = LocalParams(nodeParams, isInitiator = true)
                val state = WaitForInit
                val (state1, actions1) = state.process(
                    ChannelCommand.Init.Initiator(
                        cmd.fundingAmount,
                        cmd.pushAmount,
                        cmd.walletInputs,
                        cmd.commitTxFeerate,
                        cmd.fundingTxFeerate,
                        localParams,
                        theirInit!!,
                        cmd.channelFlags,
                        ChannelConfig.standard,
                        cmd.channelType
                    )
                )
                val msg = actions1.filterIsInstance<ChannelAction.Message.Send>().map { it.message }.filterIsInstance<OpenDualFundedChannel>().first()
                _channels = _channels + (msg.temporaryChannelId to state1)
                processActions(msg.temporaryChannelId, peerConnection, actions1)
            }
            is PayToOpenResponseCommand -> {
                logger.info { "sending ${cmd.payToOpenResponse::class.simpleName}" }
                peerConnection?.send(cmd.payToOpenResponse)
            }
            is SendPayment -> {
                val currentTip = currentTipFlow.filterNotNull().first()
                when (val result = outgoingPaymentHandler.sendPayment(cmd, _channels, currentTip.first)) {
                    is OutgoingPaymentHandler.Progress -> {
                        _eventsFlow.emit(PaymentProgress(result.request, result.fees))
                        result.actions.forEach { input.send(it) }
                    }
                    is OutgoingPaymentHandler.Failure -> _eventsFlow.emit(PaymentNotSent(result.request, result.failure))
                }
            }
            is PurgeExpiredPayments -> {
                incomingPaymentHandler.purgeExpiredPayments(cmd.fromCreatedAt, cmd.toCreatedAt)
            }
            is CheckPaymentsTimeout -> {
                val actions = incomingPaymentHandler.checkPaymentsTimeout(currentTimestampSeconds())
                actions.forEach { input.send(it) }
            }
            is WrappedChannelCommand -> {
                if (cmd.channelId == ByteVector32.Zeroes) {
                    // this is for all channels
                    _channels.forEach { (key, value) ->
                        val (state1, actions) = value.process(cmd.channelCommand)
                        processActions(key, peerConnection, actions)
                        _channels = _channels + (key to state1)
                    }
                } else {
                    _channels[cmd.channelId]?.let { state ->
                        val (state1, actions) = state.process(cmd.channelCommand)
                        processActions(cmd.channelId, peerConnection, actions)
                        _channels = _channels + (cmd.channelId to state1)
                    } ?: logger.error { "received ${cmd.channelCommand::class.simpleName} for an unknown channel ${cmd.channelId}" }
                }
            }
            is Disconnected -> {
                when (peerConnection) {
                    null -> logger.info { "ignoring disconnected event, we're already disconnected" }
                    else -> {
                        logger.warning { "disconnecting channels from connectionId=${peerConnection?.id}" }
                        peerConnection = null
                        _channels.forEach { (key, value) ->
                            val (state1, actions) = value.process(ChannelCommand.Disconnected)
                            _channels = _channels + (key to state1)
                            processActions(key, peerConnection, actions)
                        }
                        incomingPaymentHandler.purgePayToOpenRequests()
                    }
                }
            }
        }
    }

    fun sendOnionMessage(nextNodeId: PublicKey, onionMessage: OnionMessage): Postman.SendMessageError? {
        return if (nextNodeId == remoteNodeId) {
            if(peerConnection?.send(onionMessage) == null){
                Postman.SendMessageError("Not connected to peer")
            } else {
                null
            }
        } else {
            Postman.SendMessageError("Invalid next node id")
        }
    }
}
