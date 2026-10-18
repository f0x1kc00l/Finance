package com.financeapp.presentation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.app.NotificationManagerCompat
import com.financeapp.core.notification.ParseResult
import com.financeapp.presentation.screens.quickadd.QuickAddSheet
import com.financeapp.presentation.screens.quickadd.QuickAddViewModel
import com.financeapp.presentation.theme.FinanceTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Translucent "Record expense?" sheet opened from the payment notification.
 * Not exported; only this app's own PendingIntent can start it. It can only ADD an expense
 * (shown amount comes from the bank notification the user already saw), never read data,
 * so it does not require the app lock to be passed.
 */
@AndroidEntryPoint
class QuickAddActivity : ComponentActivity() {

    private val vm: QuickAddViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (vm.notificationId != 0) NotificationManagerCompat.from(this).cancel(vm.notificationId)

        setContent {
            FinanceTheme {
                QuickAddSheet(
                    vm = vm,
                    onDismiss = ::finish,
                    onMoreDetails = { amountMinor, bank ->
                        startActivity(
                            MainActivity.prefillIntent(this, amountMinor, bank, vm.sourceKeyForDetails, vm.state.value.notificationCurrency)
                        )
                        finish()
                    }
                )
            }
        }
    }

    companion object {
        fun intent(context: Context, debit: ParseResult.Debit, notificationId: Int): Intent =
            Intent(context, QuickAddActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(QuickAddViewModel.EXTRA_AMOUNT_MINOR, debit.amountMinor)
                .putExtra(QuickAddViewModel.EXTRA_CURRENCY, debit.currencyCode)
                .putExtra(QuickAddViewModel.EXTRA_BANK, debit.bank)
                .putExtra(QuickAddViewModel.EXTRA_SOURCE_KEY, debit.dedupKey)
                .putExtra(QuickAddViewModel.EXTRA_NOTIFICATION_ID, notificationId)
    }
}
