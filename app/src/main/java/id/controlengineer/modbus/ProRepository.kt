package id.controlengineer.modbus

import android.app.Activity
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.android.billingclient.api.AcknowledgePurchaseParams
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
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import id.controlengineer.modbus.protocol.ConnectionProfile
import id.controlengineer.modbus.protocol.ProEntitlement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal val Context.proDataStore: DataStore<Preferences> by preferencesDataStore(name = "modbus_prefs")

/** Kontrak status & aksi Pro untuk produksi (Play Billing + AdMob + DataStore) maupun tes (Fake). */
interface ProRepository {
    val lifetime: StateFlow<Boolean>
    val rewardedUntilMillis: StateFlow<Long>
    val trialStartMillis: StateFlow<Long>
    val trialWelcomeShown: StateFlow<Boolean>
    val formattedPrice: StateFlow<String?>
    val statusMessage: StateFlow<String?>
    val privacyOptionsRequired: StateFlow<Boolean>
    val savedProfiles: StateFlow<List<ConnectionProfile>>

    fun purchase(activity: Activity)
    fun showRewarded(activity: Activity)
    fun refresh()

    fun dismissTrialWelcome() {}
    fun initializeConsentAndAds(activity: Activity) {}
    fun showPrivacyOptions(activity: Activity) {}
    fun clearStatusMessage() {}
    fun saveProfiles(profiles: List<ConnectionProfile>)
}

/** Implementasi palsu untuk androidTest dan mode debug extra "pro" / "trial". */
class FakeProRepository(
    initialLifetime: Boolean = false,
    initialRewardedUntilMillis: Long = 0L,
    initialTrialStartMillis: Long = 0L,
    initialTrialWelcomeShown: Boolean = true,
    initialFormattedPrice: String? = null,
    private val simulateAdUnavailableMsg: String = "Rewarded ad unavailable in test mode.",
) : ProRepository {
    private val _lifetime = MutableStateFlow(initialLifetime)
    override val lifetime: StateFlow<Boolean> = _lifetime.asStateFlow()

    private val _rewardedUntilMillis = MutableStateFlow(initialRewardedUntilMillis)
    override val rewardedUntilMillis: StateFlow<Long> = _rewardedUntilMillis.asStateFlow()

    private val _trialStartMillis = MutableStateFlow(initialTrialStartMillis)
    override val trialStartMillis: StateFlow<Long> = _trialStartMillis.asStateFlow()

    private val _trialWelcomeShown = MutableStateFlow(initialTrialWelcomeShown)
    override val trialWelcomeShown: StateFlow<Boolean> = _trialWelcomeShown.asStateFlow()

    private val _formattedPrice = MutableStateFlow(initialFormattedPrice)
    override val formattedPrice: StateFlow<String?> = _formattedPrice.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    override val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _privacyOptionsRequired = MutableStateFlow(false)
    override val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired.asStateFlow()

    private val _savedProfiles = MutableStateFlow<List<ConnectionProfile>>(emptyList())
    override val savedProfiles: StateFlow<List<ConnectionProfile>> = _savedProfiles.asStateFlow()

    override fun purchase(activity: Activity) {
        if (_formattedPrice.value != null) {
            _lifetime.value = true
        } else {
            _statusMessage.value = activity.getString(R.string.billing_unavailable_reason)
        }
    }

    override fun showRewarded(activity: Activity) {
        _statusMessage.value = activity.getString(R.string.ad_unavailable).ifBlank { simulateAdUnavailableMsg }
    }

    override fun refresh() {
        val now = System.currentTimeMillis()
        _rewardedUntilMillis.value = ProEntitlement.clampRewardedUntil(_rewardedUntilMillis.value, now)
    }

    override fun dismissTrialWelcome() {
        _trialWelcomeShown.value = true
    }

    override fun clearStatusMessage() {
        _statusMessage.value = null
    }

    override fun saveProfiles(profiles: List<ConnectionProfile>) {
        _savedProfiles.value = profiles
    }
}

/** Implementasi nyata: Google Play Billing (`pro_lifetime`), UMP + AdMob Rewarded, dan DataStore Preferences. */
class PlayProRepository(context: Context) : ProRepository {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _lifetime = MutableStateFlow(false)
    override val lifetime: StateFlow<Boolean> = _lifetime.asStateFlow()

    private val _rewardedUntilMillis = MutableStateFlow(0L)
    override val rewardedUntilMillis: StateFlow<Long> = _rewardedUntilMillis.asStateFlow()

    private val _trialStartMillis = MutableStateFlow(0L)
    override val trialStartMillis: StateFlow<Long> = _trialStartMillis.asStateFlow()

    private val _trialWelcomeShown = MutableStateFlow(true)
    override val trialWelcomeShown: StateFlow<Boolean> = _trialWelcomeShown.asStateFlow()

    private val _formattedPrice = MutableStateFlow<String?>(null)
    override val formattedPrice: StateFlow<String?> = _formattedPrice.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    override val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _privacyOptionsRequired = MutableStateFlow(false)
    override val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired.asStateFlow()

    private val _savedProfiles = MutableStateFlow<List<ConnectionProfile>>(emptyList())
    override val savedProfiles: StateFlow<List<ConnectionProfile>> = _savedProfiles.asStateFlow()

    private var productDetails: ProductDetails? = null
    private var rewardedAd: RewardedAd? = null
    private var isAdLoading = false
    private val adsInitialized = AtomicBoolean(false)

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases != null) {
                    processPurchases(purchases, fromFullQuery = false)
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> {
                _statusMessage.value = appContext.getString(R.string.billing_unavailable_reason)
            }
        }
    }

    private val billingClient: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .build()

    init {
        scope.launch {
            appContext.proDataStore.data.collect { prefs ->
                _lifetime.value = prefs[KEY_LIFETIME] ?: false
                val now = System.currentTimeMillis()
                val storedTrialStart = prefs[KEY_TRIAL_START]
                if (storedTrialStart == null) {
                    _trialStartMillis.value = now
                    _trialWelcomeShown.value = prefs[KEY_WELCOME_SHOWN] ?: false
                    appContext.proDataStore.edit { editPrefs ->
                        if (editPrefs[KEY_TRIAL_START] == null) {
                            editPrefs[KEY_TRIAL_START] = now
                        }
                    }
                } else {
                    _trialStartMillis.value = storedTrialStart
                    _trialWelcomeShown.value = prefs[KEY_WELCOME_SHOWN] ?: false
                }
                val rawUntil = prefs[KEY_REWARDED_UNTIL] ?: 0L
                val clamped = ProEntitlement.clampRewardedUntil(rawUntil, now)
                _rewardedUntilMillis.value = clamped
                if (rawUntil != clamped && rawUntil > 0L) {
                    appContext.proDataStore.edit { it[KEY_REWARDED_UNTIL] = clamped }
                }
                _savedProfiles.value = ConnectionProfile.decodeList(prefs[KEY_PROFILES] ?: "")
            }
        }
        refresh()
    }

    override fun refresh() {
        val now = System.currentTimeMillis()
        val prev = _rewardedUntilMillis.value
        val clamped = ProEntitlement.clampRewardedUntil(prev, now)
        _rewardedUntilMillis.value = clamped
        if (clamped != prev && prev > 0L) {
            scope.launch {
                appContext.proDataStore.edit { it[KEY_REWARDED_UNTIL] = clamped }
            }
        }
        if (billingClient.isReady) {
            queryProductAndPurchases()
        } else {
            try {
                billingClient.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                            queryProductAndPurchases()
                        }
                    }

                    override fun onBillingServiceDisconnected() = Unit
                })
            } catch (_: Throwable) {
            }
        }
    }

    private fun queryProductAndPurchases() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build(),
        )
        val detailsParams = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()
        billingClient.queryProductDetailsAsync(detailsParams) { result, detailsResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val found = detailsResult.productDetailsList.firstOrNull { it.productId == PRODUCT_ID }
                productDetails = found
                _formattedPrice.value = found?.oneTimePurchaseOfferDetails?.formattedPrice
            }
        }

        val purchasesParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(purchasesParams) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                processPurchases(purchases, fromFullQuery = true)
            }
        }
    }

    private fun processPurchases(purchases: List<Purchase>, fromFullQuery: Boolean) {
        var hasPurchased = false
        var hasPending = false
        for (purchase in purchases) {
            if (!purchase.products.contains(PRODUCT_ID)) continue
            when (purchase.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> {
                    hasPurchased = true
                    if (!purchase.isAcknowledged) {
                        val ackParams = AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchase.purchaseToken)
                            .build()
                        billingClient.acknowledgePurchase(ackParams) { _ -> }
                    }
                }
                Purchase.PurchaseState.PENDING -> {
                    hasPending = true
                }
                else -> Unit
            }
        }
        if (hasPending && !hasPurchased) {
            _statusMessage.value = appContext.getString(R.string.purchase_pending)
        }
        if (fromFullQuery || hasPurchased) {
            _lifetime.value = hasPurchased
            scope.launch {
                appContext.proDataStore.edit { it[KEY_LIFETIME] = hasPurchased }
            }
        }
    }

    override fun purchase(activity: Activity) {
        val details = productDetails
        if (details == null || !billingClient.isReady) {
            _statusMessage.value = appContext.getString(R.string.billing_unavailable_reason)
            refresh()
            return
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        billingClient.launchBillingFlow(activity, flowParams)
    }

    override fun initializeConsentAndAds(activity: Activity) {
        val params = ConsentRequestParameters.Builder().build()
        val consentInfo = UserMessagingPlatform.getConsentInformation(activity)
        consentInfo.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ ->
                    updatePrivacyRequirement(consentInfo)
                    if (consentInfo.canRequestAds()) {
                        ensureAdsInitializedAndPreload()
                    }
                }
            },
            { _ ->
                updatePrivacyRequirement(consentInfo)
                if (consentInfo.canRequestAds()) {
                    ensureAdsInitializedAndPreload()
                }
            },
        )
    }

    private fun updatePrivacyRequirement(consentInfo: ConsentInformation) {
        _privacyOptionsRequired.value =
            consentInfo.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }

    override fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { _ ->
            val consentInfo = UserMessagingPlatform.getConsentInformation(activity)
            updatePrivacyRequirement(consentInfo)
            if (consentInfo.canRequestAds()) {
                ensureAdsInitializedAndPreload()
            }
        }
    }

    private fun ensureAdsInitializedAndPreload() {
        if (adsInitialized.compareAndSet(false, true)) {
            MobileAds.initialize(appContext) {
                scope.launch { loadRewardedAd() }
            }
        } else {
            loadRewardedAd()
        }
    }

    private fun loadRewardedAd() {
        if (isAdLoading || rewardedAd != null) return
        val consentInfo = UserMessagingPlatform.getConsentInformation(appContext)
        if (!consentInfo.canRequestAds()) return
        isAdLoading = true
        // TODO_ADMOB: BuildConfig.ADMOB_REWARDED_ID memakai ID tes Google; ganti sebelum rilis produksi.
        RewardedAd.load(
            appContext,
            BuildConfig.ADMOB_REWARDED_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    isAdLoading = false
                    rewardedAd = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isAdLoading = false
                    rewardedAd = null
                }
            },
        )
    }

    override fun showRewarded(activity: Activity) {
        clearStatusMessage()
        val ad = rewardedAd
        if (ad == null) {
            _statusMessage.value = appContext.getString(R.string.ad_unavailable)
            val consentInfo = UserMessagingPlatform.getConsentInformation(activity)
            if (consentInfo.canRequestAds()) {
                ensureAdsInitializedAndPreload()
            } else {
                initializeConsentAndAds(activity)
            }
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                loadRewardedAd()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                rewardedAd = null
                _statusMessage.value = appContext.getString(R.string.ad_unavailable)
                loadRewardedAd()
            }
        }
        ad.show(activity) { _ ->
            val until = System.currentTimeMillis() + ProEntitlement.REWARD_DURATION_MS
            _rewardedUntilMillis.value = until
            scope.launch {
                appContext.proDataStore.edit { it[KEY_REWARDED_UNTIL] = until }
            }
        }
    }

    override fun dismissTrialWelcome() {
        _trialWelcomeShown.value = true
        scope.launch {
            appContext.proDataStore.edit { it[KEY_WELCOME_SHOWN] = true }
        }
    }

    internal fun resetFirstOpenForTest() {
        val now = System.currentTimeMillis()
        _trialStartMillis.value = now
        _trialWelcomeShown.value = false
        scope.launch {
            appContext.proDataStore.edit {
                it[KEY_TRIAL_START] = now
                it[KEY_WELCOME_SHOWN] = false
            }
        }
    }

    override fun clearStatusMessage() {
        _statusMessage.value = null
    }

    override fun saveProfiles(profiles: List<ConnectionProfile>) {
        _savedProfiles.value = profiles
        scope.launch {
            appContext.proDataStore.edit { it[KEY_PROFILES] = ConnectionProfile.encodeList(profiles) }
        }
    }

    companion object {
        const val PRODUCT_ID = "pro_lifetime"
        private val KEY_LIFETIME = booleanPreferencesKey("pro_lifetime")
        private val KEY_REWARDED_UNTIL = longPreferencesKey("rewarded_until_millis")
        private val KEY_TRIAL_START = longPreferencesKey("trial_start_millis")
        private val KEY_WELCOME_SHOWN = booleanPreferencesKey("trial_welcome_shown")
        private val KEY_PROFILES = stringPreferencesKey("saved_profiles")
    }
}
