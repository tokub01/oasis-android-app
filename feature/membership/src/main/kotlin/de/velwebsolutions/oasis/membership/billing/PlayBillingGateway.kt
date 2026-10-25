package de.velwebsolutions.oasis.membership.billing

import android.app.Activity
import android.util.Log
import com.android.billingclient.api.BillingClient.BillingResponseCode
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [BillingGateway] with Google Play Billing: one [PlayBillingConnection] for the app, connected on first use. Every
 * failure – no Play Store on the device, not signed in, service gone – ends as "not available", never as a crash.
 */
@Singleton
class PlayBillingGateway @Inject constructor(private val connection: PlayBillingConnection) : BillingGateway {
    private val lock = Mutex()
    private val known = ConcurrentHashMap.newKeySet<String>()
    private val updates = MutableSharedFlow<PlayPurchase>(extraBufferCapacity = 16)

    /** The purchase flow waiting for Play's answer in the listener. */
    @Volatile
    private var waitingFlow: CompletableDeferred<PurchaseOutcome>? = null

    init {
        connection.listener = PlayBillingConnection.Listener(::onPurchasesUpdated)
    }

    override val purchaseUpdates: Flow<PlayPurchase> = updates.asSharedFlow()

    override suspend fun products(ids: List<String>): List<PlayProduct>? {
        if (!connected()) return null
        val answer = guarded { connection.products(ids) } ?: return null
        if (answer.responseCode != BillingResponseCode.OK) return null
        answer.value.forEach { known += it.id }
        return answer.value
    }

    override suspend fun purchase(activity: Activity?, product: PlayProduct, accountId: String?): PurchaseOutcome {
        if (!connected()) return PurchaseOutcome.Unavailable
        val offerToken = product.offerToken
        if (activity == null || offerToken == null || product.id !in known) return PurchaseOutcome.Failed
        val waiting = CompletableDeferred<PurchaseOutcome>()
        waitingFlow = waiting
        val launched = guarded { connection.launch(activity, product.id, offerToken, accountId?.takeIf { it.length <= MAX_ACCOUNT_ID }) }
        if (launched != BillingResponseCode.OK) {
            waitingFlow = null
            return outcome(launched, emptyList())
        }
        return waiting.await()
    }

    override suspend fun ownedPurchases(): List<PlayPurchase> {
        if (!connected()) return emptyList()
        val answer = guarded { connection.purchases() } ?: return emptyList()
        return if (answer.responseCode == BillingResponseCode.OK) answer.value else emptyList()
    }

    override suspend fun acknowledge(purchase: PlayPurchase): Boolean =
        connected() && guarded { connection.acknowledge(purchase.purchaseToken) } == BillingResponseCode.OK

    private fun onPurchasesUpdated(responseCode: Int, purchases: List<PlayPurchase>) {
        val waiting = waitingFlow
        if (waiting != null) {
            waitingFlow = null
            waiting.complete(outcome(responseCode, purchases))
        } else if (responseCode == BillingResponseCode.OK) {
            purchases.forEach { updates.tryEmit(it) }
        }
    }

    /** Whether Play Billing is connected, connecting first when needed (at most [CONNECT_TIMEOUT_MS]). */
    private suspend fun connected(): Boolean = lock.withLock {
        guarded { connection.isReady } == true || withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val started = guarded { connection.connect { ready -> if (continuation.isActive) continuation.resume(ready) } }
                if (started == null && continuation.isActive) continuation.resume(false)
            }
        } == true
    }

    private inline fun <T> guarded(call: () -> T): T? = try {
        call()
    } catch (error: CancellationException) {
        throw error
    } catch (error: RuntimeException) {
        Log.w(TAG, "Google Play Billing call failed", error)
        null
    }

    /** Play's limits and the mapping of a purchase flow's answer. */
    companion object {
        private const val TAG = "PlayBilling"

        /** How long the first call waits for Play Billing to connect. */
        const val CONNECT_TIMEOUT_MS = 8_000L

        /** Play's limit for the obfuscated account id; longer ids are not sent. */
        const val MAX_ACCOUNT_ID = 64

        /** How a purchase flow ended, from Play's response code and the purchases it reported (null: not launched). */
        fun outcome(responseCode: Int?, purchases: List<PlayPurchase>): PurchaseOutcome = when (responseCode) {
            BillingResponseCode.OK -> {
                val paid = purchases.filter(PlayPurchase::isPurchased)
                when {
                    paid.isNotEmpty() -> PurchaseOutcome.Purchased(paid)
                    purchases.isNotEmpty() -> PurchaseOutcome.Pending
                    else -> PurchaseOutcome.Failed
                }
            }

            BillingResponseCode.USER_CANCELED -> PurchaseOutcome.Cancelled

            BillingResponseCode.BILLING_UNAVAILABLE, BillingResponseCode.SERVICE_UNAVAILABLE, BillingResponseCode.FEATURE_NOT_SUPPORTED ->
                PurchaseOutcome.Unavailable

            else -> PurchaseOutcome.Failed
        }
    }
}
