package com.financeapp.presentation

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.financeapp.core.money.Money
import com.financeapp.data.repository.AppSettingsRepository
import com.financeapp.domain.session.SessionManager
import com.financeapp.domain.session.SessionState
import com.financeapp.presentation.navigation.FinanceNavHost
import com.financeapp.presentation.navigation.Prefill
import com.financeapp.presentation.navigation.Routes
import com.financeapp.presentation.screens.lock.LockScreen
import com.financeapp.presentation.theme.FinanceTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Single activity. FragmentActivity (not plain ComponentActivity) because BiometricPrompt
 * needs a FragmentManager — v1.0's LockScreen cast the context to FragmentActivity, the cast
 * failed, and enabling the lock left users stuck on a lock screen that never prompted.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var session: SessionManager
    @Inject lateinit var settings: AppSettingsRepository
    @Inject lateinit var appEvents: AppEvents

    private val prefill = MutableStateFlow<Prefill?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Keep the splash until we know whether to show onboarding, the lock or the app.
        splash.setKeepOnScreenCondition {
            settings.settings.value == null || session.state.value == SessionState.UNKNOWN
        }
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            FinanceTheme {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Accepts only well-formed pre-fill extras; nothing is saved without user confirmation. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != ACTION_PREFILL) return
        val amount = intent.getLongExtra(EXTRA_AMOUNT_MINOR, -1L)
        if (!Money.isValidAmount(amount)) return
        prefill.value = Prefill(
            amountMinor = amount,
            bank = intent.getStringExtra(EXTRA_BANK)?.take(40),
            sourceKey = intent.getStringExtra(EXTRA_SOURCE_KEY)?.takeIf { it.length in 16..128 },
            currency = intent.getStringExtra(EXTRA_CURRENCY)?.take(3)
        )
    }

    @Composable
    private fun AppRoot() {
        val appSettings by settings.settings.collectAsStateWithLifecycle()
        val sessionState by session.state.collectAsStateWithLifecycle()
        val pinSetupRequested by appEvents.pinSetupRequested.collectAsStateWithLifecycle()
        val pendingPrefill by prefill.collectAsStateWithLifecycle()
        val navController = rememberNavController()
        val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

        val loaded = appSettings ?: return
        // Decided once per activity instance; later changes (finishing onboarding) navigate instead.
        val startDestination = remember { if (loaded.onboardingDone) Routes.HOME else Routes.ONBOARDING }

        val locked = sessionState == SessionState.LOCKED
        val sensitive = locked || Routes.isSensitive(currentRoute)
        LaunchedEffect(sensitive, loaded.hideInRecents) { applyWindowPrivacy(sensitive, loaded.hideInRecents) }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (sessionState) {
                SessionState.UNKNOWN -> Unit // splash is still showing
                // The lock REPLACES the navigation host: nothing beneath it can be reached with
                // Back, deep links or accessibility services while locked.
                SessionState.LOCKED -> LockScreen()
                SessionState.UNLOCKED -> FinanceNavHost(
                    navController = navController,
                    startDestination = startDestination,
                    pinSetupRequested = pinSetupRequested,
                    onPinSetupHandled = appEvents::consumePinSetupRequest,
                    prefill = if (loaded.onboardingDone) pendingPrefill else null,
                    onPrefillHandled = { prefill.value = null }
                )
            }
        }
    }

    /**
     * FLAG_SECURE blocks screenshots/recording and blanks the Recents thumbnail. Always on for
     * PIN entry; on Android 13+ "hide in Recents" uses setRecentsScreenshotEnabled instead so
     * normal screenshots keep working, older versions need FLAG_SECURE for that too.
     */
    private fun applyWindowPrivacy(sensitive: Boolean, hideInRecents: Boolean) {
        val legacyHide = hideInRecents && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        if (sensitive || legacyHide) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(!hideInRecents)
        }
    }

    companion object {
        const val ACTION_PREFILL = "com.financeapp.action.PREFILL_EXPENSE"
        private const val EXTRA_AMOUNT_MINOR = "amount_minor"
        private const val EXTRA_BANK = "bank"
        private const val EXTRA_SOURCE_KEY = "source_key"
        private const val EXTRA_CURRENCY = "currency"

        fun prefillIntent(context: Context, amountMinor: Long, bank: String, sourceKey: String?, currency: String?): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_PREFILL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_AMOUNT_MINOR, amountMinor)
                .putExtra(EXTRA_BANK, bank)
                .putExtra(EXTRA_SOURCE_KEY, sourceKey)
                .putExtra(EXTRA_CURRENCY, currency)
    }
}
