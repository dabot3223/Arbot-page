package com.arbot.game.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.arbot.core.api.ApiException
import com.arbot.core.api.PurchaseResult
import com.arbot.game.data.GameRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed interface BillingEvent {
    data class Granted(val productId: String, val result: PurchaseResult) : BillingEvent
    data class Pending(val productId: String) : BillingEvent
    data object Cancelled : BillingEvent
    data class Failed(val message: String) : BillingEvent
}

/**
 * Google Play Billing with server-side verification.
 *
 * The client never grants anything itself. Every purchase token goes to the
 * `purchase.verify` action (signed, encrypted, Play Integrity attested), the
 * server checks it with the Play Developer API, verifies it belongs to this
 * player (obfuscatedAccountId), credits it exactly once and consumes or
 * acknowledges it. Unfinished purchases are retried on every app start.
 */
class BillingManager(
    context: Context,
    private val repository: GameRepository,
    private val scope: CoroutineScope,
) : PurchasesUpdatedListener {

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private val _products = MutableStateFlow<Map<String, ProductDetails>>(emptyMap())
    val products: StateFlow<Map<String, ProductDetails>> = _products.asStateFlow()

    private val _events = MutableSharedFlow<BillingEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<BillingEvent> = _events.asSharedFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private suspend fun ensureConnected(): Boolean {
        if (client.isReady) return true
        return suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    suspend fun refresh(productIds: List<String>) {
        if (productIds.isEmpty() || !ensureConnected()) return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                productIds.map {
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                },
            )
            .build()
        val details: List<ProductDetails> = suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { result, queryResult ->
                val list = if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryResult.productDetailsList
                } else {
                    emptyList()
                }
                if (cont.isActive) cont.resume(list)
            }
        }
        _products.value = details.associateBy { it.productId }
        recoverUnfinished()
    }

    suspend fun launch(activity: Activity, productId: String) {
        val details = _products.value[productId]
        if (details == null) {
            _events.tryEmit(BillingEvent.Failed("This item is not available right now"))
            return
        }
        val accountId = try {
            repository.obfuscatedAccountId()
        } catch (e: Exception) {
            _events.tryEmit(BillingEvent.Failed("Connect to the internet to make purchases"))
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build()),
            )
            .setObfuscatedAccountId(accountId)
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _events.tryEmit(BillingEvent.Failed("Purchase could not be started (${result.responseCode})"))
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach { p -> scope.launch { process(p) } }
            BillingClient.BillingResponseCode.USER_CANCELED -> _events.tryEmit(BillingEvent.Cancelled)
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> scope.launch { recoverUnfinished() }
            else -> _events.tryEmit(BillingEvent.Failed("Purchase failed (${result.responseCode})"))
        }
    }

    /** Re-sends purchases that were paid but never confirmed by the server. */
    suspend fun recoverUnfinished() {
        if (!ensureConnected()) return
        val purchases: List<Purchase> = suspendCancellableCoroutine { cont ->
            client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
            ) { result, list ->
                if (cont.isActive) {
                    cont.resume(if (result.responseCode == BillingClient.BillingResponseCode.OK) list else emptyList())
                }
            }
        }
        // Consumables disappear once the server consumes them; non-consumables
        // stay but are acknowledged.
        purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }
            .forEach { process(it) }
    }

    private suspend fun process(purchase: Purchase) {
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PENDING ->
                purchase.products.forEach { _events.tryEmit(BillingEvent.Pending(it)) }

            Purchase.PurchaseState.PURCHASED -> {
                _busy.value = true
                try {
                    for (productId in purchase.products) {
                        try {
                            val r = repository.verifyPurchase(productId, purchase.purchaseToken)
                            _events.tryEmit(
                                if (r.status == "pending") BillingEvent.Pending(productId)
                                else BillingEvent.Granted(productId, r),
                            )
                        } catch (e: ApiException) {
                            _events.tryEmit(BillingEvent.Failed("Purchase could not be verified (${e.code})"))
                        } catch (e: Exception) {
                            _events.tryEmit(BillingEvent.Failed("Purchase will be delivered when you are back online"))
                        }
                    }
                } finally {
                    _busy.value = false
                }
            }

            else -> Unit
        }
    }

    fun close() = client.endConnection()
}
