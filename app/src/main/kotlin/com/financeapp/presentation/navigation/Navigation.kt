package com.financeapp.presentation.navigation

import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.financeapp.domain.pin.PinScreenMode
import com.financeapp.presentation.screens.add.AddExpenseScreen
import com.financeapp.presentation.screens.add.AddExpenseViewModel
import com.financeapp.presentation.screens.add.AddOutcome
import com.financeapp.presentation.screens.analytics.AnalyticsScreen
import com.financeapp.presentation.screens.home.HomeResult
import com.financeapp.presentation.screens.home.HomeScreen
import com.financeapp.presentation.screens.onboarding.OnboardingScreen
import com.financeapp.presentation.screens.pin.PinFlowScreen
import com.financeapp.presentation.screens.pin.PinFlowViewModel
import com.financeapp.presentation.screens.settings.SettingsScreen

/** Pre-filled values for a new expense coming from a payment notification. */
data class Prefill(val amountMinor: Long, val bank: String?, val sourceKey: String?, val currency: String?)

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val ANALYTICS = "analytics"
    const val SETTINGS = "settings"
    const val ADD = "add?${AddExpenseViewModel.ARG_AMOUNT}={${AddExpenseViewModel.ARG_AMOUNT}}" +
        "&${AddExpenseViewModel.ARG_BANK}={${AddExpenseViewModel.ARG_BANK}}" +
        "&${AddExpenseViewModel.ARG_KEY}={${AddExpenseViewModel.ARG_KEY}}" +
        "&${AddExpenseViewModel.ARG_CURRENCY}={${AddExpenseViewModel.ARG_CURRENCY}}"
    const val EDIT = "edit/{${AddExpenseViewModel.ARG_ID}}"
    const val PIN = "pin/{${PinFlowViewModel.ARG_MODE}}"

    fun add(prefill: Prefill? = null): String {
        if (prefill == null) return "add"
        val parts = buildList {
            add("${AddExpenseViewModel.ARG_AMOUNT}=${prefill.amountMinor}")
            prefill.bank?.let { add("${AddExpenseViewModel.ARG_BANK}=${Uri.encode(it)}") }
            prefill.sourceKey?.let { add("${AddExpenseViewModel.ARG_KEY}=${Uri.encode(it)}") }
            prefill.currency?.let { add("${AddExpenseViewModel.ARG_CURRENCY}=${Uri.encode(it)}") }
        }
        return "add?" + parts.joinToString("&")
    }

    fun edit(id: Long) = "edit/$id"
    fun pin(mode: PinScreenMode) = "pin/${mode.name}"

    /** Routes that show PIN entry and therefore get FLAG_SECURE. */
    fun isSensitive(route: String?) = route == PIN

    const val RESULT_KEY = "home_result"
}

@Composable
fun FinanceNavHost(
    navController: NavHostController,
    startDestination: String,
    pinSetupRequested: Boolean,
    onPinSetupHandled: () -> Unit,
    prefill: Prefill?,
    onPrefillHandled: () -> Unit,
    modifier: Modifier = Modifier
) {
    // After "Forgot PIN": go straight to creating a new one.
    LaunchedEffect(pinSetupRequested) {
        if (pinSetupRequested) {
            onPinSetupHandled()
            navController.navigate(Routes.pin(PinScreenMode.SETUP)) { launchSingleTop = true }
        }
    }
    // "More details" from the payment sheet: open a pre-filled add screen once unlocked.
    LaunchedEffect(prefill) {
        if (prefill != null) {
            onPrefillHandled()
            navController.navigate(Routes.add(prefill)) { launchSingleTop = true }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() }
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onFinish = {
                navController.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            })
        }

        composable(Routes.HOME) { entry ->
            val resultName by entry.savedStateHandle.getStateFlow<String?>(Routes.RESULT_KEY, null).collectAsStateWithLifecycle()
            HomeScreen(
                pendingResult = resultName?.let { n -> HomeResult.entries.firstOrNull { it.name == n } },
                onResultConsumed = { entry.savedStateHandle[Routes.RESULT_KEY] = null },
                onAddExpense = { navController.navigate(Routes.add()) },
                onEditExpense = { id -> navController.navigate(Routes.edit(id)) },
                onOpenAnalytics = { navController.navigate(Routes.ANALYTICS) { launchSingleTop = true } },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } }
            )
        }

        val finishAdd: (AddOutcome) -> Unit = { outcome ->
            val result = when (outcome) {
                AddOutcome.SAVED -> HomeResult.SAVED
                AddOutcome.UPDATED -> HomeResult.UPDATED
                AddOutcome.DELETED -> HomeResult.DELETED
            }
            val home = runCatching { navController.getBackStackEntry(Routes.HOME) }.getOrNull()
            if (home != null) {
                home.savedStateHandle[Routes.RESULT_KEY] = result.name
                navController.popBackStack(Routes.HOME, inclusive = false)
            } else {
                navController.popBackStack()
            }
        }

        composable(
            route = Routes.ADD,
            arguments = listOf(
                navArgument(AddExpenseViewModel.ARG_AMOUNT) { type = NavType.LongType; defaultValue = -1L },
                navArgument(AddExpenseViewModel.ARG_BANK) { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument(AddExpenseViewModel.ARG_KEY) { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument(AddExpenseViewModel.ARG_CURRENCY) { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) {
            AddExpenseScreen(onBack = { navController.popBackStack() }, onFinished = finishAdd)
        }

        composable(
            route = Routes.EDIT,
            arguments = listOf(navArgument(AddExpenseViewModel.ARG_ID) { type = NavType.LongType })
        ) {
            AddExpenseScreen(onBack = { navController.popBackStack() }, onFinished = finishAdd)
        }

        composable(Routes.ANALYTICS) {
            AnalyticsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onSetupPin = { navController.navigate(Routes.pin(PinScreenMode.SETUP)) },
                onChangePin = { navController.navigate(Routes.pin(PinScreenMode.CHANGE)) },
                onDisablePin = { navController.navigate(Routes.pin(PinScreenMode.DISABLE)) }
            )
        }

        composable(
            route = Routes.PIN,
            arguments = listOf(navArgument(PinFlowViewModel.ARG_MODE) { type = NavType.StringType })
        ) {
            PinFlowScreen(
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
