package com.financeapp.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.financeapp.FinanceApp
import com.financeapp.R
import com.financeapp.core.money.MoneyFormatter
import com.financeapp.core.notification.BankNotification
import com.financeapp.core.notification.BankNotificationParser
import com.financeapp.core.notification.ParseResult
import com.financeapp.data.repository.AppSettingsRepository
import com.financeapp.domain.model.Currency
import com.financeapp.presentation.QuickAddActivity
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

/**
 * Turns outgoing-payment notifications from supported bank apps into a "Record expense?"
 * notification. Nothing is saved until the user picks a category.
 *
 * Privacy: notifications from any other package are dropped before their content is read;
 * for bank notifications only the parsed amount, currency, bank name and a one-way hash leave
 * this method. No notification text is stored or logged.
 *
 * Reliability: Android 10+ forbids starting activities from the background, so v1.0's
 * direct startActivity(QuickAddActivity) was silently blocked; the prompt notification (now
 * with a tap action — v1.0's had none) is the supported path.
 */
@AndroidEntryPoint
class PaymentNotificationListener : NotificationListenerService() {

    @Inject lateinit var settings: AppSettingsRepository

    private val parser = BankNotificationParser()

    /** Keys already prompted in this process — bank apps often re-post/update the same alert. */
    private val prompted = object : LinkedHashMap<String, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 64
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg !in BankNotificationParser.DEFAULT_BANK_PACKAGES) return
        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras ?: return
        // getCharSequence: banks often post styled (Spannable) text, which getString() drops.
        val input = BankNotification(
            packageName = pkg,
            notificationKey = sbn.key ?: "",
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        )
        val appCurrency = settings.snapshot().currency
        val result = parser.parse(input, preferredCurrency = appCurrency.code) as? ParseResult.Debit ?: return

        synchronized(prompted) {
            if (prompted.containsKey(result.dedupKey)) return
            prompted[result.dedupKey] = Unit
        }
        showPrompt(result)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // Ask the system to bind us again (e.g. after the process was killed under memory pressure).
        requestRebind(ComponentName(this, PaymentNotificationListener::class.java))
    }

    private fun showPrompt(debit: ParseResult.Debit) {
        val nm = NotificationManagerCompat.from(this)
        // POST_NOTIFICATIONS denied (API 33+) or notifications blocked: Settings explains this.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (!nm.areNotificationsEnabled()) return

        val currency = Currency.fromIsoCode(debit.currencyCode)
        val amountText = currency?.let { MoneyFormatter(Locale.getDefault()).format(debit.amountMinor, it) }
            ?: "${MoneyFormatter(Locale.getDefault()).formatNumber(debit.amountMinor)} ${debit.currencyCode}"

        val notificationId = debit.dedupKey.hashCode()
        val tap = PendingIntent.getActivity(
            this,
            notificationId,
            QuickAddActivity.intent(this, debit, notificationId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val publicVersion = NotificationCompat.Builder(this, FinanceApp.CHANNEL_PAYMENT)
            .setSmallIcon(R.drawable.ic_stat_payment)
            .setContentTitle(getString(R.string.detected_public_title))
            .build()
        val prompt = NotificationCompat.Builder(this, FinanceApp.CHANNEL_PAYMENT)
            .setSmallIcon(R.drawable.ic_stat_payment)
            .setContentTitle(getString(R.string.detected_title, debit.bank, amountText))
            .setContentText(getString(R.string.detected_text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        try {
            nm.notify(notificationId, prompt)
        } catch (_: SecurityException) {
            // Permission revoked between the check and notify(); nothing else to do.
        }
    }

    companion object {
        /** True when the user granted notification access to this listener. */
        fun isAccessGranted(context: android.content.Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun settingsIntent(): Intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
}
