package app.gridlink.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "GridlinkTips"

/**
 * The tip products, as created in Play Console (Eight Point Labs account). Consumable one-time
 * products, so the same reader can tip again. 🔴 Prices are set in Play Console and shown as Play
 * formats them; nothing in the app ever names an amount.
 */
internal val TIP_PRODUCT_IDS = listOf("tip_small", "tip_medium", "tip_large")

/** One tip the card can offer: Play's product id and its locally formatted price, e.g. "$1.99". */
internal data class TipOption(val productId: String, val price: String, val priceMicros: Long)

/** Whether Play can sell a tip on this device right now. */
internal enum class TipAvailability {
    /** Still connecting to Play, or still asking it for the products. */
    Loading,

    /** Connected, and at least one tip product came back with a price. */
    Ready,

    /** No Play Store, billing refused, or no tip products exist yet (no listing, not activated). */
    Unavailable,
}

/** The one line the card shows under the pills after something happened. */
internal enum class TipNotice { None, Thanks, Pending, Failed }

internal data class TipJarState(
    val availability: TipAvailability = TipAvailability.Loading,
    val options: List<TipOption> = emptyList(),
    /** Play's purchase sheet is open, or a finished tip is being consumed. */
    val busy: Boolean = false,
    val notice: TipNotice = TipNotice.None,
    /** This install has completed at least one tip, ever. Changes the card's body to a thank-you. */
    val tippedBefore: Boolean = false,
)

/**
 * The offered tips in the order the card draws them: cheapest first, whatever the ids say, so a
 * price changed in Play Console can never leave the pills out of order. Products Play returned
 * without a one-time price are dropped rather than shown with a blank pill.
 */
internal fun orderTips(options: List<TipOption>): List<TipOption> =
    options.filter { it.price.isNotBlank() }.sortedWith(compareBy({ it.priceMicros }, { it.productId }))

/**
 * Google Play Billing tip jar for the Play build. Tips are voluntary and unlock nothing.
 *
 * Lifecycle: the Settings card calls [connect] when it appears and [close] when it leaves. A
 * [BillingClient] cannot be reused after `endConnection`, so every [connect] builds a fresh one.
 *
 * Tips are CONSUMABLE: a completed purchase is consumed, which is also its acknowledgement (Play
 * refunds a purchase left unacknowledged for three days). 🔴 A tip whose consume never finished
 * (app killed, network dropped, a pending payment that cleared while the app was closed) is still
 * owned and would block buying that tier again, so every connect asks Play for owned purchases and
 * consumes any tip it finds. That is also how a pending tip turns into a thank-you later.
 *
 * Nothing here talks to a server of ours and nothing is unlocked; the only thing kept is a count
 * of completed tips on this install, so the card can say thank you.
 */
internal class TipJar(context: Context) : PurchasesUpdatedListener {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(TipJarState(tippedBefore = prefs.getInt(KEY_TIPS, 0) > 0))
    val state: StateFlow<TipJarState> = _state.asStateFlow()

    // Billing callbacks may arrive off the main thread, so both are read and written as volatiles.
    @Volatile private var client: BillingClient? = null

    @Volatile private var details: Map<String, ProductDetails> = emptyMap()

    /** Connects to Play, loads the tip prices and finishes any tip left unconsumed. */
    fun connect() {
        if (client?.isReady == true) return
        val fresh = BillingClient.newBuilder(appContext)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        client = fresh
        _state.update { it.copy(availability = TipAvailability.Loading) }
        @Suppress("TooGenericExceptionCaught") // A store-less device must cost a quiet card, never a crash.
        try {
            fresh.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        queryProducts(fresh)
                        queryOwned(fresh)
                    } else {
                        Log.w(TAG, "billing_setup_failed code=${result.responseCode}")
                        _state.update { it.copy(availability = TipAvailability.Unavailable, busy = false) }
                    }
                }

                // A dropped service mid-session: the next connect (next visit to Settings) rebuilds.
                override fun onBillingServiceDisconnected() = Unit
            })
        } catch (e: RuntimeException) {
            Log.w(TAG, "billing_start_failed ${e.javaClass.simpleName}")
            _state.update { it.copy(availability = TipAvailability.Unavailable, busy = false) }
        }
    }

    /** Ends the connection. A purchase still in flight finishes in Play and is consumed next connect. */
    fun close() {
        client?.endConnection()
        client = null
    }

    /** Opens Play's purchase sheet for [productId]. The outcome arrives in [onPurchasesUpdated]. */
    fun launch(activity: Activity, productId: String) {
        val live = client
        val product = details[productId]
        if (live == null || !live.isReady || product == null) {
            _state.update { it.copy(notice = TipNotice.Failed) }
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build()),
            )
            .build()
        _state.update { it.copy(busy = true, notice = TipNotice.None) }
        val result = live.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "launch_failed code=${result.responseCode}")
            _state.update { it.copy(busy = false, notice = TipNotice.Failed) }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val tips = purchases.orEmpty().filter(::isTip)
                if (tips.isEmpty()) _state.update { it.copy(busy = false) }
                tips.forEach(::handle)
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> _state.update { it.copy(busy = false) }
            // An earlier tip of this tier was never consumed. Finish it, which frees the tier.
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> client?.let(::queryOwned)
            else -> {
                Log.w(TAG, "purchase_failed code=${result.responseCode}")
                _state.update { it.copy(busy = false, notice = TipNotice.Failed) }
            }
        }
    }

    private fun isTip(purchase: Purchase) = purchase.products.any { it in TIP_PRODUCT_IDS }

    private fun handle(purchase: Purchase) {
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> consume(purchase)
            Purchase.PurchaseState.PENDING -> _state.update { it.copy(busy = false, notice = TipNotice.Pending) }
            else -> _state.update { it.copy(busy = false) }
        }
    }

    private fun consume(purchase: Purchase) {
        val live = client ?: return
        _state.update { it.copy(busy = true) }
        val params = ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        live.consumeAsync(params) { result, _ ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                prefs.edit().putInt(KEY_TIPS, prefs.getInt(KEY_TIPS, 0) + 1).apply()
                _state.update { it.copy(busy = false, notice = TipNotice.Thanks, tippedBefore = true) }
            } else {
                // Still owned, so the next connect's owned-purchases query consumes it again.
                Log.w(TAG, "consume_failed code=${result.responseCode}")
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun queryOwned(live: BillingClient) {
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        live.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "query_owned_failed code=${result.responseCode}")
                _state.update { it.copy(busy = false) }
                return@queryPurchasesAsync
            }
            purchases.filter(::isTip).forEach(::handle)
        }
    }

    private fun queryProducts(live: BillingClient) {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                TIP_PRODUCT_IDS.map { id ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(id)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                },
            )
            .build()
        live.queryProductDetailsAsync(params) { result, response ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "query_products_failed code=${result.responseCode}")
                _state.update { it.copy(availability = TipAvailability.Unavailable) }
                return@queryProductDetailsAsync
            }
            val found = response.productDetailsList.filter { it.productId in TIP_PRODUCT_IDS }
            details = found.associateBy { it.productId }
            val options = orderTips(
                found.mapNotNull { product ->
                    product.oneTimePurchaseOfferDetails?.let { offer ->
                        TipOption(product.productId, offer.formattedPrice, offer.priceAmountMicros)
                    }
                },
            )
            _state.update {
                it.copy(
                    options = options,
                    availability = if (options.isEmpty()) TipAvailability.Unavailable else TipAvailability.Ready,
                )
            }
        }
    }

    private companion object {
        const val PREFS_NAME = "gridlink_tip_jar"
        const val KEY_TIPS = "completed_tips"
    }
}
