package de.velwebsolutions.oasis.membership.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PlayBillingConnection] over Play's [BillingClient]: builds the client, translates our requests into Play's
 * parameters and Play's answers into [PlayProduct] and [PlayPurchase]. It decides nothing; [PlayBillingGateway] does.
 */
@Singleton
class BillingClientConnection @Inject constructor(@ApplicationContext context: Context) : PlayBillingConnection {
    override var listener: PlayBillingConnection.Listener? = null

    /** Built on first use, inside the gateway's guard: a device without Play may refuse already here. */
    private val client: BillingClient by lazy {
        BillingClient.newBuilder(context)
            .setListener { result, purchases -> listener?.onPurchasesUpdated(result.responseCode, purchases.orEmpty().map(::playPurchase)) }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().enablePrepaidPlans().build())
            .enableAutoServiceReconnection()
            .build()
    }

    /** Play's details of the products [products] returned, needed to start their purchase. */
    private val details = ConcurrentHashMap<String, ProductDetails>()

    override val isReady: Boolean get() = client.isReady

    override fun connect(onReady: (Boolean) -> Unit) = client.startConnection(
        object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) =
                onReady(result.responseCode == BillingClient.BillingResponseCode.OK)

            override fun onBillingServiceDisconnected() = onReady(false)
        }
    )

    override suspend fun products(ids: List<String>): PlayBillingConnection.Answer<List<PlayProduct>> {
        val products = ids.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build()
        }
        val result = client.queryProductDetails(QueryProductDetailsParams.newBuilder().setProductList(products).build())
        val list = result.productDetailsList.orEmpty()
        list.forEach { details[it.productId] = it }
        return PlayBillingConnection.Answer(result.billingResult.responseCode, list.mapNotNull(::playProduct))
    }

    override fun launch(activity: Activity, product: PlayProduct, accountId: String?): Int {
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details.getValue(product.id))
            .setOfferToken(requireNotNull(product.offerToken))
            .build()
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(productParams))
        accountId?.let(params::setObfuscatedAccountId)
        return client.launchBillingFlow(activity, params.build()).responseCode
    }

    override suspend fun purchases(): PlayBillingConnection.Answer<List<PlayPurchase>> {
        val result = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build())
        return PlayBillingConnection.Answer(result.billingResult.responseCode, result.purchasesList.map(::playPurchase))
    }

    override suspend fun acknowledge(purchaseToken: String): Int =
        client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build()).responseCode
}

/**
 * A subscription as the checkout shows it: the base plan (no offer id) carries the regular price, its last pricing
 * phase is the recurring one; null for a product without a usable offer.
 */
internal fun playProduct(details: ProductDetails): PlayProduct? {
    val offers = details.subscriptionOfferDetails.orEmpty()
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

/** A purchase as the membership handles it. */
internal fun playPurchase(purchase: Purchase) = PlayPurchase(
    purchaseToken = purchase.purchaseToken,
    productIds = purchase.products,
    isPurchased = purchase.purchaseState == Purchase.PurchaseState.PURCHASED,
    isAcknowledged = purchase.isAcknowledged
)
