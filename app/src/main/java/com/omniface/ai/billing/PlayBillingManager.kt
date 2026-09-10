package com.omniface.ai.billing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.android.billingclient.api.*
import com.omniface.ai.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.min
import org.json.JSONObject

/**
 * Production-Grade Google Play Billing Manager (Play Billing Library v7.1.1).
 *
 * Implements Google Play's required lifecycle, real-time subscription queries,
 * automatic purchase acknowledgment (mandatory within 3 days to avoid auto-refunds),
 * pending transactions (UPI / net banking), exponential backoff connection retries,
 * and purchase restoration.
 */
object PlayBillingManager : PurchasesUpdatedListener, BillingClientStateListener {

    private const val TAG = "PlayBillingManager"

    // Google Play Console Product & Plan IDs
    const val PRODUCT_ID_PREMIUM_MONTHLY = "omniface_premium_monthly_199"
    const val BASE_PLAN_ID_DEFAULT = "monthly-auto-renewing"

    // Subscription Duration: 30 days
    private val SUBSCRIPTION_DURATION_MS = TimeUnit.DAYS.toMillis(30)

    private var applicationContext: Context? = null
    private var billingClient: BillingClient? = null

    // Coroutine Scope for background billing queries
    private val billingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Observable States
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _premiumProductDetails = MutableStateFlow<ProductDetails?>(null)
    val premiumProductDetails: StateFlow<ProductDetails?> = _premiumProductDetails.asStateFlow()

    private val _formattedPrice = MutableStateFlow("₹199 / month")
    val formattedPrice: StateFlow<String> = _formattedPrice.asStateFlow()

    private val _billingEvents = MutableSharedFlow<BillingEvent>(extraBufferCapacity = 10)
    val billingEvents: SharedFlow<BillingEvent> = _billingEvents.asSharedFlow()

    // Retry backoff
    private var retryCount = 0
    private const val MAX_RETRY_ATTEMPTS = 5
    private const val BASE_RETRY_DELAY_MS = 1500L

    sealed class BillingEvent {
        data class PurchaseSuccess(val purchaseToken: String, val orderId: String?) : BillingEvent()
        data class PurchasePending(val purchaseToken: String) : BillingEvent()
        data class PurchaseFailed(val responseCode: Int, val message: String) : BillingEvent()
        data object UserCanceled : BillingEvent()
        data class RestoreResult(val success: Boolean, val message: String) : BillingEvent()
    }

    /**
     * Initializes the BillingClient on application start.
     */
    fun initialize(context: Context) {
        if (billingClient != null) return
        applicationContext = context.applicationContext

        Log.i(TAG, "Initializing Google Play BillingClient v7.1.1...")

        val pendingPurchasesParams = PendingPurchasesParams.newBuilder()
            .enableOneTimeProducts()
            .enablePrepaidPlans()
            .build()

        billingClient = BillingClient.newBuilder(context.applicationContext)
            .setListener(this)
            .enablePendingPurchases(pendingPurchasesParams)
            .build()

        startConnection()
    }

    /**
     * Connects to Google Play with exponential backoff on failure.
     */
    private fun startConnection() {
        val client = billingClient ?: return
        if (client.isReady) {
            _isConnected.value = true
            return
        }

        try {
            client.startConnection(this)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting BillingClient connection", e)
            scheduleReconnect()
        }
    }

    override fun onBillingSetupFinished(billingResult: BillingResult) {
        val code = billingResult.responseCode
        if (code == BillingClient.BillingResponseCode.OK) {
            Log.i(TAG, "✅ Google Play BillingClient connected successfully.")
            _isConnected.value = true
            retryCount = 0

            // Query product details for ₹199 monthly subscription
            querySubscriptionProductDetails()

            // Query existing purchases to ensure local cache reflects current active subscriptions
            syncPurchasesInternal()

            // Pull any Web Dashboard enterprise/business subscription licenses
            pullSubscriptionFromBackend()
        } else {
            Log.w(TAG, "Billing setup failed with responseCode=$code: ${billingResult.debugMessage}")
            _isConnected.value = false
            scheduleReconnect()
        }
    }

    override fun onBillingServiceDisconnected() {
        Log.w(TAG, "Google Play Billing service disconnected. Scheduling reconnect...")
        _isConnected.value = false
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (retryCount >= MAX_RETRY_ATTEMPTS) {
            Log.w(TAG, "Max BillingClient reconnect attempts reached ($MAX_RETRY_ATTEMPTS). Will retry on user action.")
            return
        }
        val delayMs = min(BASE_RETRY_DELAY_MS * (1 shl retryCount), 30000L)
        retryCount++
        Log.d(TAG, "Scheduling Billing reconnect attempt $retryCount in ${delayMs}ms")
        billingScope.launch {
            delay(delayMs)
            startConnection()
        }
    }

    /**
     * Queries Google Play for the ₹199 Premium subscription product details.
     */
    fun querySubscriptionProductDetails(onComplete: ((ProductDetails?) -> Unit)? = null) {
        val client = billingClient
        if (client == null || !client.isReady) {
            Log.w(TAG, "Cannot query product details: BillingClient is not ready.")
            onComplete?.invoke(null)
            return
        }

        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID_PREMIUM_MONTHLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        client.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && !productDetailsList.isNullOrEmpty()) {
                val details = productDetailsList.firstOrNull {
                    it.productId == PRODUCT_ID_PREMIUM_MONTHLY
                }
                _premiumProductDetails.value = details

                details?.subscriptionOfferDetails?.firstOrNull()?.let { offer ->
                    val phase = offer.pricingPhases.pricingPhaseList.firstOrNull()
                    if (phase != null) {
                        _formattedPrice.value = "${phase.formattedPrice} / month"
                        Log.i(TAG, "Retrieved Play Store subscription price: ${phase.formattedPrice}")
                    }
                }

                onComplete?.invoke(details)
            } else {
                Log.w(TAG, "Failed to query product details or empty: ${billingResult.debugMessage}")
                onComplete?.invoke(null)
            }
        }
    }

    /**
     * Launches the native Google Play Purchase Flow for ₹199/mo Premium.
     */
    fun launchBillingFlow(
        activity: Activity,
        onLaunched: ((Boolean, String) -> Unit)? = null
    ) {
        val client = billingClient

        // Fallback for Debug builds without Google Play Services
        if (client == null || !client.isReady) {
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "Debug build sandbox fallback: activating Premium locally.")
                activateSandboxPremium()
                onLaunched?.invoke(true, "Sandbox Premium Activated (Debug Mode)")
                return
            } else {
                startConnection()
                onLaunched?.invoke(false, "Google Play Store is connecting. Please retry in a moment.")
                return
            }
        }

        val details = _premiumProductDetails.value
        if (details == null) {
            // Attempt to query on-demand
            querySubscriptionProductDetails { loadedDetails ->
                if (loadedDetails != null) {
                    executeBillingFlow(activity, loadedDetails, onLaunched)
                } else {
                    if (BuildConfig.DEBUG) {
                        Log.i(TAG, "Product details not configured on Play Console. Using debug sandbox.")
                        activateSandboxPremium()
                        onLaunched?.invoke(true, "Sandbox Premium Activated (Console Pending)")
                    } else {
                        onLaunched?.invoke(false, "Unable to load subscription details from Google Play.")
                    }
                }
            }
            return
        }

        executeBillingFlow(activity, details, onLaunched)
    }

    private fun executeBillingFlow(
        activity: Activity,
        details: ProductDetails,
        onLaunched: ((Boolean, String) -> Unit)?
    ) {
        val client = billingClient ?: return
        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken

        if (offerToken == null) {
            Log.e(TAG, "No offerToken found for ${details.productId}")
            onLaunched?.invoke(false, "Subscription offer is unavailable.")
            return
        }

        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offerToken)
            .build()

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()

        val response = client.launchBillingFlow(activity, billingFlowParams)
        if (response.responseCode == BillingClient.BillingResponseCode.OK) {
            onLaunched?.invoke(true, "Billing flow launched.")
        } else {
            Log.e(TAG, "launchBillingFlow error code: ${response.responseCode}, ${response.debugMessage}")
            onLaunched?.invoke(false, response.debugMessage.ifBlank { "Error code: ${response.responseCode}" })
        }
    }

    /**
     * Handles purchase updates returned by Google Play.
     */
    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (!purchases.isNullOrEmpty()) {
                    for (purchase in purchases) {
                        handlePurchase(purchase)
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.d(TAG, "User canceled the Google Play subscription purchase.")
                _billingEvents.tryEmit(BillingEvent.UserCanceled)
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Log.i(TAG, "Item already owned. Synchronizing active purchase...")
                syncPurchasesInternal()
            }
            else -> {
                Log.e(TAG, "Purchase failed: [${billingResult.responseCode}] ${billingResult.debugMessage}")
                _billingEvents.tryEmit(
                    BillingEvent.PurchaseFailed(billingResult.responseCode, billingResult.debugMessage)
                )
            }
        }
    }

    /**
     * Processes a single purchase: checks state, acknowledges if necessary, and persists tier.
     */
    private fun handlePurchase(purchase: Purchase) {
        val isTargetProduct = purchase.products.contains(PRODUCT_ID_PREMIUM_MONTHLY)
        if (!isTargetProduct) return

        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                Log.i(TAG, "💰 Valid purchase detected for $PRODUCT_ID_PREMIUM_MONTHLY: token=${purchase.purchaseToken.take(12)}...")

                // Immediate acknowledgment (Mandatory within 3 days or Google auto-refunds)
                if (!purchase.isAcknowledged) {
                    acknowledgePurchaseToken(purchase.purchaseToken) { ackSuccess ->
                        if (ackSuccess) {
                            applyActiveSubscription(purchase)
                        }
                    }
                } else {
                    applyActiveSubscription(purchase)
                }

                _billingEvents.tryEmit(
                    BillingEvent.PurchaseSuccess(purchase.purchaseToken, purchase.orderId)
                )
            }
            Purchase.PurchaseState.PENDING -> {
                Log.w(TAG, "⏳ Purchase state is PENDING (e.g. slow payment / UPI approval)")
                _billingEvents.tryEmit(BillingEvent.PurchasePending(purchase.purchaseToken))
            }
            Purchase.PurchaseState.UNSPECIFIED_STATE -> {
                Log.w(TAG, "Purchase state is UNSPECIFIED")
            }
        }
    }

    /**
     * Acknowledges a purchase with Google Play.
     */
    private fun acknowledgePurchaseToken(purchaseToken: String, onComplete: (Boolean) -> Unit) {
        val client = billingClient ?: return onComplete(false)
        val ackParams = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()

        client.acknowledgePurchase(ackParams) { billingResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.i(TAG, "✅ Purchase acknowledged successfully.")
                onComplete(true)
            } else {
                Log.e(TAG, "❌ Failed to acknowledge purchase: [${billingResult.responseCode}] ${billingResult.debugMessage}")
                onComplete(false)
            }
        }
    }

    /**
     * Applies the validated active subscription to the local SubscriptionTierManager.
     */
    private fun applyActiveSubscription(purchase: Purchase) {
        val expiryTime = purchase.purchaseTime + SUBSCRIPTION_DURATION_MS
        SubscriptionTierManager.setSubscription(
            tier = SubscriptionTier.PREMIUM,
            expiryTimestampMs = expiryTime,
            purchaseToken = purchase.purchaseToken
        )
        syncSubscriptionWithBackend(SubscriptionTier.PREMIUM, purchase.purchaseToken)
    }

    /**
     * Pushes in-app subscription purchases to the Web Dashboard & PostgreSQL database.
     */
    fun syncSubscriptionWithBackend(tier: SubscriptionTier, purchaseToken: String?) {
        billingScope.launch {
            try {
                val prefs = applicationContext?.getSharedPreferences("OMNIFACE_PREFS", Context.MODE_PRIVATE)
                val endpoint = prefs?.getString("SUBSCRIPTION_REST_ENDPOINT", "https://omniface.vercel.app/api/v1/subscriptions")
                    ?: "https://omniface.vercel.app/api/v1/subscriptions"
                val orgId = prefs?.getString("ORG_ID", "00000000-0000-0000-0000-000000000001")
                    ?: "00000000-0000-0000-0000-000000000001"
                val deviceId = prefs?.getString("DEVICE_ID", "OMNIFACE-TERMINAL-01") ?: "OMNIFACE-TERMINAL-01"

                val payload = JSONObject().apply {
                    put("orgId", orgId)
                    put("tier", tier.name)
                    put("provider", "GOOGLE_PLAY")
                    put("purchaseToken", purchaseToken ?: "")
                    put("deviceId", deviceId)
                }.toString()

                val url = URL(endpoint)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("Accept", "application/json")
                }

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload)
                    writer.flush()
                }

                val code = conn.responseCode
                if (code in 200..299) {
                    Log.i(TAG, "✅ In-App Subscription synced to Web Dashboard successfully ($tier).")
                } else {
                    Log.w(TAG, "Backend subscription sync returned code $code")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Unable to sync subscription to web backend: ${e.message}")
            }
        }
    }

    /**
     * Pulls active enterprise/business subscription status from Web Dashboard (Razorpay / Stripe purchases).
     */
    fun pullSubscriptionFromBackend(onResolved: ((SubscriptionTier) -> Unit)? = null) {
        billingScope.launch {
            try {
                val prefs = applicationContext?.getSharedPreferences("OMNIFACE_PREFS", Context.MODE_PRIVATE)
                val endpoint = prefs?.getString("SUBSCRIPTION_REST_ENDPOINT", "https://omniface.vercel.app/api/v1/subscriptions")
                    ?: "https://omniface.vercel.app/api/v1/subscriptions"
                val orgId = prefs?.getString("ORG_ID", "00000000-0000-0000-0000-000000000001")
                    ?: "00000000-0000-0000-0000-000000000001"

                val url = URL("$endpoint?orgId=$orgId")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/json")
                }

                val code = conn.responseCode
                if (code in 200..299) {
                    val response = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val json = JSONObject(response)
                    val subObj = json.optJSONObject("subscription")
                    val remoteTierStr = subObj?.optString("tier", "FREE") ?: "FREE"
                    val remoteTier = try {
                        SubscriptionTier.valueOf(remoteTierStr)
                    } catch (_: Exception) {
                        SubscriptionTier.FREE
                    }

                    val finalTier = if (remoteTier == SubscriptionTier.BUSINESS) {
                        Log.i(TAG, "🏛️ Active Enterprise Business license detected on Web Dashboard! Unlocking kiosk fleet.")
                        val validUntil = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(365)
                        SubscriptionTierManager.setSubscription(
                            tier = SubscriptionTier.BUSINESS,
                            expiryTimestampMs = validUntil,
                            purchaseToken = "web_business_license"
                        )
                        SubscriptionTier.BUSINESS
                    } else if (remoteTier == SubscriptionTier.PREMIUM && SubscriptionTierManager.isFreeTier()) {
                        Log.i(TAG, "👑 Active Premium license detected on Web Dashboard. Elevating kiosk.")
                        val validUntil = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30)
                        SubscriptionTierManager.setSubscription(
                            tier = SubscriptionTier.PREMIUM,
                            expiryTimestampMs = validUntil,
                            purchaseToken = "web_premium_license"
                        )
                        SubscriptionTier.PREMIUM
                    } else {
                        SubscriptionTierManager.currentTier.value
                    }
                    withContext(Dispatchers.Main) {
                        onResolved?.invoke(finalTier)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onResolved?.invoke(SubscriptionTierManager.currentTier.value)
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pull subscription from web backend: ${e.message}")
                withContext(Dispatchers.Main) {
                    onResolved?.invoke(SubscriptionTierManager.currentTier.value)
                }
            }
        }
    }

    /**
     * Synchronizes existing active purchases from Google Play (called on startup & restore).
     */
    private fun syncPurchasesInternal(onComplete: ((Boolean, Int) -> Unit)? = null) {
        val client = billingClient
        if (client == null || !client.isReady) {
            onComplete?.invoke(false, 0)
            return
        }

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        client.queryPurchasesAsync(params) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                var foundActive = false
                for (purchase in purchasesList) {
                    if (purchase.products.contains(PRODUCT_ID_PREMIUM_MONTHLY) &&
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                    ) {
                        handlePurchase(purchase)
                        foundActive = true
                    }
                }

                if (!foundActive && !SubscriptionTierManager.isFreeTier()) {
                    // Check if current subscription in local prefs is a Google Play sub that has expired
                    val activeTier = SubscriptionTierManager.currentTier.value
                    if (activeTier == SubscriptionTier.PREMIUM) {
                        Log.i(TAG, "No active subscription found on Play Store. Evaluating offline grace period.")
                        SubscriptionTierManager.evaluateCurrentTier()
                    }
                }

                onComplete?.invoke(true, purchasesList.size)
            } else {
                Log.w(TAG, "queryPurchasesAsync failed: ${billingResult.debugMessage}")
                onComplete?.invoke(false, 0)
            }
        }
    }

    /**
     * User-facing Restore Purchases method.
     */
    fun restorePurchases(onResult: (Boolean, String) -> Unit) {
        val client = billingClient
        if (client == null || !client.isReady) {
            startConnection()
            onResult(false, "Connecting to Google Play. Please try again.")
            return
        }

        syncPurchasesInternal { success, count ->
            if (success) {
                if (SubscriptionTierManager.currentTier.value == SubscriptionTier.PREMIUM) {
                    onResult(true, "Premium subscription restored successfully!")
                    _billingEvents.tryEmit(BillingEvent.RestoreResult(true, "Premium restored."))
                } else {
                    onResult(false, "No active subscription found for this Google account.")
                    _billingEvents.tryEmit(BillingEvent.RestoreResult(false, "No active subscription found."))
                }
            } else {
                onResult(false, "Failed to query Google Play. Please check your network connection.")
            }
        }
    }

    /**
     * Opens the native Google Play Subscriptions management deep link.
     */
    fun openSubscriptionManagement(context: Context) {
        val uri = Uri.parse(
            "https://play.google.com/store/account/subscriptions?sku=$PRODUCT_ID_PREMIUM_MONTHLY&package=${context.packageName}"
        )
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to open Google Play subscriptions URI directly", e)
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/account/subscriptions")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { context.startActivity(fallback) } catch (_: Exception) {}
        }
    }

    /**
     * Activates 30-day Premium for development & sandbox environments.
     */
    fun activateSandboxPremium() {
        val expiryTime = System.currentTimeMillis() + SUBSCRIPTION_DURATION_MS
        val mockToken = "gp_sub_sandbox_${System.currentTimeMillis()}"
        SubscriptionTierManager.setSubscription(
            tier = SubscriptionTier.PREMIUM,
            expiryTimestampMs = expiryTime,
            purchaseToken = mockToken
        )
        _billingEvents.tryEmit(BillingEvent.PurchaseSuccess(mockToken, "SANDBOX-ORDER-123"))
    }
}
