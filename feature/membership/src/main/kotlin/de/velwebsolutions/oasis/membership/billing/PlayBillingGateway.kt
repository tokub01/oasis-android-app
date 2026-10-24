package de.velwebsolutions.oasis.membership.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * [BillingGateway] with Google Play Billing: one [BillingClient] for the app, connected on first use. Every failure –
 * no Play Store on the device, not signed in, service gone – ends as "not available", never as a crash.
 */
@Singleton
class PlayBillingGateway @Inject constructor(@ApplicationContext context: Context) : BillingGateway, PurchasesUpdatedListener {
    private val client: BillingClient? = try {
        BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().enablePrepaidPlans().build())
            .enableAutoServiceReconnection()
            .build()
    } catch (error: RuntimeException) {
        Log.w(TAG, "Google Play Billing is not available", error)
        null
    }

    private val connection = Mutex()
    private val details = ConcurrentHashMap<String, ProductDetails>()
    private val updates = MutableSharedFlow<PlayPurchase>(extraBufferCapacity = 16)

    /** The purchase flow waiting for Play's answer in [onPurchasesUpdated]. */
    @Volatile
    private var waitingFlow: CompletableDeferred<PurchaseOutcome>? = null

    override val purchaseUpdates: Flow<PlayPurchase> = updates.asSharedFlow()

    override suspend fun products(ids: List<String>): List<PlayProduct>? {
        val client = connected() ?: return null
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                ids.map {
                    QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build()
                }
            )
            .build()
        val result = guarded { client.queryProductDetails(params) } ?: return null
        if (result.billingResult.responseCode != BillingResponseCode.OK) return null
        val list = result.productDetailsList.orEmpty()
        list.forEach { details[it.productId] = it }
        return list.mapNotNull(::product)
    }

    override suspend fun purchase(activity: Activity?, product: PlayProduct, accountId: String?): PurchaseOutcome {
        val client = connected() ?: return PurchaseOutcome.Unavailable
        val productDetails = details[product.id] ?: return PurchaseOutcome.Failed
        val offerToken = product.offerToken ?: return PurchaseOutcome.Failed
        if (activity == null) return PurchaseOutcome.Failed
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(productDetails).setOfferToken(offerToken).build()
                )
            )
            .apply { if (accountId != null && accountId.length <= 64) setObfuscatedAccountId(accountId) }
            .build()
        val waiting = CompletableDeferred<PurchaseOutcome>()
        waitingFlow = waiting
        val launched = try {
            client.launchBillingFlow(activity, params)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not start the purchase", error)
            null
        }
        if (launched?.responseCode != BillingResponseCode.OK) {
            waitingFlow = null
            return outcome(launched, emptyList())
        }
        return waiting.await()
    }

    override suspend fun ownedPurchases(): List<PlayPurchase> {
        val client = connected() ?: return emptyList()
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        val result = guarded { client.queryPurchasesAsync(params) } ?: return emptyList()
        if (result.billingResult.responseCode != BillingResponseCode.OK) return emptyList()
        return result.purchasesList.map(::purchase)
    }

    override suspend fun acknowledge(purchase: PlayPurchase): Boolean {
        val client = connected() ?: return false
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        return guarded { client.acknowledgePurchase(params) }?.responseCode == BillingResponseCode.OK
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        val mapped = purchases.orEmpty().map(::purchase)
        val waiting = waitingFlow
        if (waiting != null) {
            waitingFlow = null
            waiting.complete(outcome(result, mapped))
        } else if (result.responseCode == BillingResponseCode.OK) {
            mapped.forEach { updates.tryEmit(it) }
        }
    }

    private fun outcome(result: BillingResult?, purchases: List<PlayPurchase>): PurchaseOutcome = when (result?.responseCode) {
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

    /** The client once it is connected; null when Play Billing cannot be reached within a few seconds. */
    private suspend fun connected(): BillingClient? {
        val client = client ?: return null
        return connection.withLock {
            if (client.isReady) return@withLock client
            val ready = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    try {
                        client.startConnection(
                            object : BillingClientStateListener {
                                override fun onBillingSetupFinished(result: BillingResult) {
                                    if (continuation.isActive) continuation.resume(result.responseCode == BillingResponseCode.OK)
                                }

                                override fun onBillingServiceDisconnected() {
                                    if (continuation.isActive) continuation.resume(false)
                                }
                            }
                        )
                    } catch (error: RuntimeException) {
                        Log.w(TAG, "Could not connect to Google Play Billing", error)
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            }
            if (ready == true) client else null
        }
    }

    private suspend fun <T> guarded(call: suspend () -> T): T? = try {
        call()
    } catch (error: CancellationException) {
        throw error
    } catch (error: RuntimeException) {
        Log.w(TAG, "Google Play Billing call failed", error)
        null
    }

    private fun product(details: ProductDetails): PlayProduct? {
        val offers = details.subscriptionOfferDetails.orEmpty()
        // The base plan (no offer id) carries the regular price; its last pricing phase is the recurring one.
        val offer = offers.firstOrNull { it.offerId == null } ?: offers.firstOrNull() ?: return null
        val phase = offer.pricingPhases.pricingPhaseList.lastOrNull() ?: return null
        return PlayProduct(
            id = details.productId,
            name = details.name,
            description = details.description,
            price = phase.formattedPrice,
            period = BillingPeriod.parse(phase.billingPeriod),
            offerToken = offer.offerToken
        )
    }

    private fun purchase(purchase: Purchase) = PlayPurchase(
        purchaseToken = purchase.purchaseToken,
        productIds = purchase.products,
        isPurchased = purchase.purchaseState == Purchase.PurchaseState.PURCHASED,
        isAcknowledged = purchase.isAcknowledged,
        accountId = purchase.accountIdentifiers?.obfuscatedAccountId
    )

    private companion object {
        const val TAG = "PlayBilling"
        const val CONNECT_TIMEOUT_MS = 8_000L
    }
}
