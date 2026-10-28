package id.controlengineer.modbus

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import id.controlengineer.modbus.protocol.ProEntitlement
import id.controlengineer.modbus.protocol.ProLabelSource
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private var startTabState by mutableIntStateOf(0)
    private var startSubTabState by mutableIntStateOf(0)
    private var startPaywallState by mutableStateOf(false)
    private var intentGeneration by mutableIntStateOf(0)
    private var playRepo: PlayProRepository? = null
    internal var activeRepo by mutableStateOf<ProRepository?>(null)
        private set
    internal lateinit var sessionController: ModbusSessionController
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionController = ModbusSessionController(applicationContext)
        enableEdgeToEdge()
        applyIntent(intent)
        setContent {
            val repo = activeRepo ?: return@setContent
            AppTheme {
                App(
                    startTab = startTabState,
                    startSubTab = startSubTabState,
                    initialShowPaywall = startPaywallState,
                    intentKey = intentGeneration,
                    proRepo = repo,
                    session = sessionController,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        activeRepo?.refresh()
        sessionController.refreshUsbAdapters()
    }

    internal fun applyIntent(intent: Intent?) {
        // Extra "tab" (0..4) dan "subtab" (0..1) dipakai skrip screenshot CI & tes untuk membuka tab tertentu.
        startTabState = (intent?.getIntExtra("tab", 0) ?: 0).coerceIn(0, 4)
        startSubTabState = (intent?.getIntExtra("subtab", 0) ?: 0).coerceIn(0, 1)

        // Extra debug: "demo" (1/true), "pro" ("on"/"off"), "trial" ("fresh"/"expired"/ms), "welcome", "rewarded_ms", "paywall".
        val isDemo = if (BuildConfig.DEBUG) {
            val intDemo = intent?.getIntExtra("demo", 0) ?: 0
            val strDemo = intent?.extras?.get("demo")?.toString()?.trim()?.lowercase()
            intDemo == 1 || strDemo == "1" || strDemo == "true"
        } else {
            false
        }
        sessionController.setDemoMode(enabled = isDemo, seedRead = isDemo)

        val debugPro = if (BuildConfig.DEBUG) intent?.getStringExtra("pro")?.lowercase() else null
        val debugTrial = if (BuildConfig.DEBUG) {
            (intent?.getStringExtra("trial") ?: intent?.extras?.get("trial")?.toString())?.trim()?.lowercase()
        } else {
            null
        }
        val debugWelcome = if (BuildConfig.DEBUG) intent?.getBooleanExtra("welcome", false) == true else false
        val rewardedMs = if (BuildConfig.DEBUG) intent?.getLongExtra("rewarded_ms", -1L) ?: -1L else -1L
        startPaywallState = if (BuildConfig.DEBUG) intent?.getBooleanExtra("paywall", false) == true else false
        intentGeneration++

        val now = System.currentTimeMillis()
        val trialRemainingMs = debugTrial?.toLongOrNull()
        activeRepo = when {
            debugTrial == "fresh" -> FakeProRepository(
                initialLifetime = false,
                initialTrialStartMillis = now,
                initialTrialWelcomeShown = !debugWelcome,
            )
            debugTrial == "expired" -> FakeProRepository(
                initialLifetime = false,
                initialTrialStartMillis = now - ProEntitlement.TRIAL_DURATION_MS - 1_000L,
                initialTrialWelcomeShown = true,
            )
            trialRemainingMs != null && trialRemainingMs > 0L -> FakeProRepository(
                initialLifetime = false,
                initialTrialStartMillis = now - ProEntitlement.TRIAL_DURATION_MS +
                    trialRemainingMs.coerceAtMost(ProEntitlement.TRIAL_DURATION_MS),
                initialTrialWelcomeShown = !debugWelcome,
            )
            rewardedMs > 0L -> FakeProRepository(
                initialLifetime = false,
                initialRewardedUntilMillis = now + rewardedMs,
                initialTrialStartMillis = 0L,
                initialTrialWelcomeShown = true,
            )
            debugPro == "on" -> FakeProRepository(
                initialLifetime = true,
                initialTrialStartMillis = 0L,
                initialTrialWelcomeShown = true,
            )
            debugPro == "off" -> FakeProRepository(
                initialLifetime = false,
                initialTrialStartMillis = 0L,
                initialTrialWelcomeShown = true,
            )
            else -> (playRepo ?: PlayProRepository(applicationContext).also { playRepo = it }).also {
                if (debugWelcome) {
                    it.resetFirstOpenForTest()
                }
                it.initializeConsentAndAds(this)
            }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(
    startTab: Int = 0,
    startSubTab: Int = 0,
    initialShowPaywall: Boolean = false,
    intentKey: Int = 0,
    proRepo: ProRepository,
    session: ModbusSessionController,
) {
    var tab by rememberSaveable(intentKey) { mutableIntStateOf(startTab.coerceIn(0, 4)) }
    var showPaywall by rememberSaveable(intentKey) { mutableStateOf(initialShowPaywall) }
    val lifetime by proRepo.lifetime.collectAsState()
    val rewardedUntilMillis by proRepo.rewardedUntilMillis.collectAsState()
    val trialStartMillis by proRepo.trialStartMillis.collectAsState()
    val trialWelcomeShown by proRepo.trialWelcomeShown.collectAsState()
    val formattedPrice by proRepo.formattedPrice.collectAsState()
    val statusMessage by proRepo.statusMessage.collectAsState()
    val privacyRequired by proRepo.privacyOptionsRequired.collectAsState()
    val savedProfiles by proRepo.savedProfiles.collectAsState()
    val reportMeta by proRepo.reportMeta.collectAsState()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, proRepo) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                now = System.currentTimeMillis()
                proRepo.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(rewardedUntilMillis, trialStartMillis, lifetime, intentKey) {
        while (!lifetime) {
            var current = System.currentTimeMillis()
            now = current
            val nextExpiry = ProEntitlement.nextExpiryMillis(lifetime, rewardedUntilMillis, trialStartMillis, current)
            if (nextExpiry <= current) break
            while (current < nextExpiry) {
                val waitMs = (nextExpiry - current + 15L).coerceAtLeast(15L)
                delay(waitMs)
                current = System.currentTimeMillis()
            }
            now = current
            proRepo.refresh()
        }
    }

    val clampedUntil = ProEntitlement.clampRewardedUntil(rewardedUntilMillis, now)
    val trialRemaining = ProEntitlement.trialRemainingMillis(trialStartMillis, now)
    val isTrialActive = ProEntitlement.isTrialActive(trialStartMillis, now)
    val isTrialExpired = ProEntitlement.isTrialExpired(trialStartMillis, now)
    val isPro = ProEntitlement.isProActive(lifetime, clampedUntil, now, trialStartMillis)
    val labelSource = ProEntitlement.labelSource(lifetime, clampedUntil, trialStartMillis, now)
    val context = LocalContext.current
    val activity = context as? Activity

    LaunchedEffect(isPro, lifetime) {
        if (isPro && showPaywall && !initialShowPaywall) {
            showPaywall = false
        }
    }

    val trialStatusLabel = if (isTrialActive) {
        if (trialRemaining >= ProEntitlement.DAY_MS) {
            stringResource(R.string.pro_trial_days, ProEntitlement.trialDaysLeft(trialRemaining))
        } else {
            stringResource(R.string.pro_trial_hours, ProEntitlement.trialHoursLeft(trialRemaining))
        }
    } else {
        null
    }

    val proChipLabel = when (labelSource) {
        ProLabelSource.LIFETIME -> stringResource(R.string.pro_lifetime)
        ProLabelSource.TRIAL -> trialStatusLabel ?: stringResource(R.string.pro_unlock)
        ProLabelSource.REWARDED -> {
            val timeStr = Instant.ofEpochMilli(clampedUntil)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
                .format(DateTimeFormatter.ofPattern("HH:mm"))
            stringResource(R.string.pro_active_until, timeStr)
        }
        ProLabelSource.NONE -> stringResource(R.string.pro_unlock)
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Text("IO", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                    label = { Text(stringResource(R.string.tab_connection)) },
                    modifier = Modifier.testTag("nav_conn"),
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Text("RW", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                    label = { Text(stringResource(R.string.tab_rw)) },
                    modifier = Modifier.testTag("nav_rw"),
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Text("HEX", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                    label = { Text(stringResource(R.string.tab_monitor)) },
                    modifier = Modifier.testTag("nav_monitor"),
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Text("PRO", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                    label = { Text(stringResource(R.string.tab_pro)) },
                    modifier = Modifier.testTag("nav_pro"),
                )
                NavigationBarItem(
                    selected = tab == 4,
                    onClick = { tab = 4 },
                    icon = { Text("?", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                    label = { Text(stringResource(R.string.tab_about)) },
                    modifier = Modifier.testTag("nav_about"),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (privacyRequired && activity != null) {
                        TextButton(
                            onClick = { proRepo.showPrivacyOptions(activity) },
                            modifier = Modifier.testTag("privacy_options"),
                        ) {
                            Text(stringResource(R.string.privacy_options), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    AssistChip(
                        onClick = {
                            proRepo.clearStatusMessage()
                            showPaywall = true
                        },
                        label = { Text(proChipLabel) },
                        modifier = Modifier.testTag("pro_status_chip"),
                    )
                }
            }

            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> ConnectionScreen(
                        session = session,
                        isPro = isPro,
                        savedProfiles = savedProfiles,
                        onSaveProfiles = { proRepo.saveProfiles(it) },
                        onRequestPro = {
                            proRepo.clearStatusMessage()
                            showPaywall = true
                        },
                    )
                    1 -> ReadWriteScreen(
                        session = session,
                        isPro = isPro,
                        reportMeta = reportMeta,
                        onSaveReportMeta = { proRepo.saveReportMeta(it) },
                        onRequestPro = {
                            proRepo.clearStatusMessage()
                            showPaywall = true
                        },
                    )
                    2 -> MonitorScreen(session = session)
                    3 -> {
                        if (isPro) {
                            ProToolsScreen(
                                initialSubTab = startSubTab,
                                session = session,
                                isPro = isPro,
                                reportMeta = reportMeta,
                                onSaveReportMeta = { proRepo.saveReportMeta(it) },
                                onRequestPro = {
                                    proRepo.clearStatusMessage()
                                    showPaywall = true
                                },
                            )
                        } else {
                            LockedProTabScreen(
                                title = stringResource(R.string.locked_pro_title),
                                subtitle = stringResource(R.string.locked_pro_subtitle),
                                features = listOf(
                                    stringResource(R.string.locked_pro_feat_scanner),
                                    stringResource(R.string.locked_pro_feat_profiles),
                                    stringResource(R.string.locked_pro_feat_csv),
                                    stringResource(R.string.locked_pro_feat_trend),
                                    stringResource(R.string.locked_pro_feat_reports),
                                ),
                                onUnlockPro = {
                                    proRepo.clearStatusMessage()
                                    showPaywall = true
                                },
                            )
                        }
                    }
                    else -> AboutScreen()
                }
            }
        }

        if (!trialWelcomeShown && isTrialActive) {
            TrialWelcomeDialog(onDismiss = { proRepo.dismissTrialWelcome() })
        }

        if (showPaywall) {
            PaywallBottomSheet(
                lifetime = lifetime,
                trialStatusLabel = trialStatusLabel,
                isTrialExpired = isTrialExpired,
                formattedPrice = formattedPrice,
                statusMessage = statusMessage,
                onWatchAd = { if (activity != null) proRepo.showRewarded(activity) },
                onBuyPro = { if (activity != null) proRepo.purchase(activity) },
                onDismiss = {
                    proRepo.clearStatusMessage()
                    showPaywall = false
                },
            )
        }
    }
}

@Composable
fun TrialWelcomeDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("trial_welcome_dialog"),
        title = { Text(stringResource(R.string.trial_welcome_title), fontWeight = FontWeight.Bold) },
        text = {
            Text(
                stringResource(R.string.trial_welcome_message),
                modifier = Modifier.testTag("trial_welcome_message"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("trial_welcome_close"),
            ) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
fun LockedProTabScreen(
    title: String,
    subtitle: String,
    features: List<String>,
    onUnlockPro: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("locked_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("locked_title"),
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (feat in features) {
                        Text("• $feat", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onUnlockPro,
                    modifier = Modifier.fillMaxWidth().testTag("btn_unlock_pro"),
                ) {
                    Text(stringResource(R.string.pro_unlock))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallBottomSheet(
    lifetime: Boolean,
    trialStatusLabel: String? = null,
    isTrialExpired: Boolean = false,
    formattedPrice: String?,
    statusMessage: String?,
    onWatchAd: () -> Unit,
    onBuyPro: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .testTag("paywall_sheet"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.paywall_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (!lifetime && trialStatusLabel != null) {
                Text(
                    trialStatusLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("paywall_trial_status"),
                )
            } else if (!lifetime && isTrialExpired) {
                Text(
                    stringResource(R.string.paywall_trial_ended),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("paywall_trial_status"),
                )
            }
            Text(
                stringResource(R.string.paywall_subtitle),
                style = MaterialTheme.typography.bodyMedium,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("• " + stringResource(R.string.locked_pro_feat_scanner), style = MaterialTheme.typography.bodyMedium)
                Text("• " + stringResource(R.string.locked_pro_feat_profiles), style = MaterialTheme.typography.bodyMedium)
                Text("• " + stringResource(R.string.locked_pro_feat_csv), style = MaterialTheme.typography.bodyMedium)
                Text("• " + stringResource(R.string.locked_pro_feat_trend), style = MaterialTheme.typography.bodyMedium)
                Text("• " + stringResource(R.string.locked_pro_feat_reports), style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.paywall_reward_disclosure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onWatchAd,
                enabled = !lifetime,
                modifier = Modifier.fillMaxWidth().testTag("btn_watch_ad"),
            ) {
                Text(stringResource(R.string.paywall_watch_ad))
            }

            Button(
                onClick = onBuyPro,
                enabled = formattedPrice != null && !lifetime,
                modifier = Modifier.fillMaxWidth().testTag("btn_buy_pro"),
            ) {
                val label = if (formattedPrice != null) {
                    stringResource(R.string.paywall_buy_price, formattedPrice)
                } else {
                    stringResource(R.string.paywall_buy_unavailable)
                }
                Text(label)
            }

            if (formattedPrice == null) {
                Text(
                    stringResource(R.string.billing_unavailable_reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("billing_unavailable_text"),
                )
            }

            if (!statusMessage.isNullOrBlank()) {
                Text(
                    statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("paywall_status_message"),
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("paywall_close"),
                ) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}
