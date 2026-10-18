package com.financeapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.financeapp.data.repository.AppSettingsRepository
import com.financeapp.di.ApplicationScope
import com.financeapp.domain.session.LockConfig
import com.financeapp.domain.session.SessionManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class FinanceApp : Application() {

    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var settings: AppSettingsRepository
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        bindSessionToSettings()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = sessionManager.onBackgrounded()
            override fun onStart(owner: LifecycleOwner) = sessionManager.onForegrounded()
        })
    }

    private fun bindSessionToSettings() {
        appScope.launch {
            settings.settings.filterNotNull()
                .map { LockConfig(it.lockEnabled, it.autoLockTimeoutMs) }
                .distinctUntilChanged()
                .collect { sessionManager.onConfigChanged(it) }
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PAYMENT,
                getString(R.string.channel_payment_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_payment_description)
                // Amounts are hidden on the lock screen; see PaymentNotificationListener.
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
        )
        // v1.0 created a "budget" channel that never posted anything.
        nm.deleteNotificationChannel(LEGACY_CHANNEL_BUDGET)
    }

    companion object {
        const val CHANNEL_PAYMENT = "payment_channel"
        private const val LEGACY_CHANNEL_BUDGET = "budget_channel"
    }
}
