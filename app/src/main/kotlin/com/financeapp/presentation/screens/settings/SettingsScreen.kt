package com.financeapp.presentation.screens.settings

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.financeapp.BuildConfig
import com.financeapp.R
import com.financeapp.core.notification.BankNotificationParser
import com.financeapp.data.repository.AppSettings
import com.financeapp.domain.model.Currency
import com.financeapp.presentation.findFragmentActivity
import com.financeapp.presentation.security.BiometricPrompts
import com.financeapp.presentation.theme.FinanceTheme
import com.financeapp.presentation.theme.Spacing
import com.financeapp.presentation.ui.FinanceTopBar
import com.financeapp.presentation.ui.SectionCard
import com.financeapp.presentation.ui.labelRes
import com.financeapp.service.PaymentNotificationListener
import java.time.LocalDate

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSetupPin: () -> Unit,
    onChangePin: () -> Unit,
    onDisablePin: () -> Unit,
    vm: SettingsViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Permissions and hardware can change while we're in system settings.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.refreshSystemState() }
    }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is SettingsEvent.Message -> snackbar.showSnackbar(
                    if (e.arg != null) context.getString(e.resId, e.arg) else context.getString(e.resId)
                )
                is SettingsEvent.Share -> {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/csv")
                        .putExtra(Intent.EXTRA_STREAM, e.uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    send.clipData = ClipData.newRawUri("", e.uri)
                    context.startActivity(Intent.createChooser(send, context.getString(R.string.settings_export_share_title)))
                }
            }
        }
    }

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportTo(uri)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.refreshSystemState()
    }

    Scaffold(
        topBar = { FinanceTopBar(title = stringResource(R.string.settings_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = Spacing.gutter, end = Spacing.gutter,
                top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + Spacing.xl
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.m)
        ) {
            item(key = "currency") { CurrencySection(state.settings.currency, vm::setCurrency) }
            item(key = "budget") { BudgetSection(state, vm) }
            item(key = "security") {
                SecuritySection(
                    state = state,
                    onLockToggle = { enable ->
                        when {
                            enable -> onSetupPin()
                            state.pinSet -> onDisablePin()
                            else -> vm.disableLegacyLock()
                        }
                    },
                    onSetupPin = onSetupPin,
                    onChangePin = onChangePin,
                    onBiometricToggle = { enable ->
                        if (!enable) {
                            vm.disableBiometric()
                        } else {
                            val activity = context.findFragmentActivity()
                            val cipher = vm.prepareBiometricEnrollment()
                            if (activity != null && cipher != null) {
                                BiometricPrompts.authenticateBiometric(activity, cipher, vm::onBiometricEnrollmentResult)
                            }
                        }
                    },
                    onAutoLock = vm::setAutoLock,
                    onHideInRecents = vm::setHideInRecents
                )
            }
            item(key = "detection") {
                DetectionSection(
                    state = state,
                    onConfigureAccess = { context.startActivity(PaymentNotificationListener.settingsIntent()) },
                    onAllowNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            )
                        }
                    }
                )
            }
            item(key = "export") {
                ExportSection(
                    exporting = state.exporting,
                    onSave = { createDocument.launch("financeapp_${LocalDate.now()}.csv") },
                    onShare = vm::exportForSharing
                )
            }
            item(key = "privacy") { PrivacySection() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrencySection(selected: Currency, onSelect: (Currency) -> Unit) {
    SectionCard(title = stringResource(R.string.settings_currency)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Currency.entries.forEach { c ->
                FilterChip(
                    selected = c == selected,
                    onClick = { onSelect(c) },
                    label = { Text("${c.symbol}  ${stringResource(c.labelRes)}") },
                    modifier = Modifier.semantics { role = Role.RadioButton }
                )
            }
        }
        Spacer(Modifier.height(Spacing.s))
        Text(
            stringResource(R.string.settings_currency_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BudgetSection(state: SettingsUiState, vm: SettingsViewModel) {
    SectionCard(title = stringResource(R.string.settings_budget)) {
        OutlinedTextField(
            value = state.budgetInput,
            onValueChange = vm::onBudgetInput,
            label = { Text(stringResource(R.string.settings_budget_hint, state.settings.currency.symbol)) },
            singleLine = true,
            isError = state.budgetError,
            supportingText = if (state.budgetError) {
                { Text(stringResource(R.string.settings_budget_invalid)) }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Spacing.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Button(onClick = vm::saveBudget) { Text(stringResource(R.string.action_save)) }
            if (state.hasBudget) {
                TextButton(onClick = vm::removeBudget) { Text(stringResource(R.string.settings_budget_remove)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecuritySection(
    state: SettingsUiState,
    onLockToggle: (Boolean) -> Unit,
    onSetupPin: () -> Unit,
    onChangePin: () -> Unit,
    onBiometricToggle: (Boolean) -> Unit,
    onAutoLock: (Long) -> Unit,
    onHideInRecents: (Boolean) -> Unit
) {
    val s = state.settings
    SectionCard(title = stringResource(R.string.settings_security)) {
        SwitchRow(
            title = stringResource(R.string.settings_lock),
            subtitle = stringResource(R.string.settings_lock_desc),
            checked = s.lockEnabled,
            enabled = state.loaded,
            onChange = onLockToggle
        )
        if (state.legacyLock) {
            Text(
                stringResource(R.string.settings_lock_legacy),
                style = MaterialTheme.typography.bodySmall,
                color = FinanceTheme.extended.warning
            )
            TextButton(onClick = onSetupPin) { Text(stringResource(R.string.settings_set_pin)) }
        }
        if (s.lockEnabled && state.pinSet) {
            TextButton(onClick = onChangePin) { Text(stringResource(R.string.settings_change_pin)) }
            HorizontalDivider(Modifier.padding(vertical = Spacing.s))
            SwitchRow(
                title = stringResource(R.string.settings_biometric),
                subtitle = stringResource(
                    if (state.strongBiometricAvailable) R.string.settings_biometric_desc else R.string.settings_biometric_unavailable
                ),
                checked = s.biometricEnabled && state.strongBiometricAvailable,
                enabled = state.strongBiometricAvailable,
                onChange = onBiometricToggle
            )
        }
        if (s.lockEnabled) {
            HorizontalDivider(Modifier.padding(vertical = Spacing.s))
            Text(stringResource(R.string.settings_auto_lock), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(Spacing.s))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                AppSettings.AUTO_LOCK_CHOICES_MS.forEach { ms ->
                    FilterChip(
                        selected = s.autoLockTimeoutMs == ms,
                        onClick = { onAutoLock(ms) },
                        label = { Text(autoLockLabel(ms)) },
                        modifier = Modifier.semantics { role = Role.RadioButton }
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = Spacing.s))
        SwitchRow(
            title = stringResource(R.string.settings_hide_recents),
            subtitle = stringResource(R.string.settings_hide_recents_desc),
            checked = s.hideInRecents,
            enabled = state.loaded,
            onChange = onHideInRecents
        )
    }
}

@Composable
private fun autoLockLabel(ms: Long): String = when {
    ms == 0L -> stringResource(R.string.settings_auto_lock_immediately)
    ms < 60_000L -> stringResource(R.string.settings_auto_lock_seconds, (ms / 1000).toInt())
    else -> stringResource(R.string.settings_auto_lock_minutes, (ms / 60_000).toInt())
}

@Composable
private fun DetectionSection(state: SettingsUiState, onConfigureAccess: () -> Unit, onAllowNotifications: () -> Unit) {
    SectionCard(title = stringResource(R.string.settings_detection)) {
        Text(stringResource(R.string.settings_detection_desc), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(Spacing.s))
        val banks = BankNotificationParser.DEFAULT_BANK_PACKAGES.values.distinct().joinToString(", ")
        Text(
            stringResource(R.string.settings_detection_banks, banks),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.m))
        StatusLine(
            ok = state.notificationAccess,
            text = stringResource(if (state.notificationAccess) R.string.settings_detection_access_on else R.string.settings_detection_access_off)
        )
        OutlinedButton(onClick = onConfigureAccess, modifier = Modifier.padding(top = Spacing.s)) {
            Text(stringResource(R.string.settings_detection_configure))
        }
        if (state.notificationAccess && !state.canPostNotifications) {
            Spacer(Modifier.height(Spacing.s))
            StatusLine(ok = false, text = stringResource(R.string.settings_detection_post_off))
            OutlinedButton(onClick = onAllowNotifications, modifier = Modifier.padding(top = Spacing.s)) {
                Text(stringResource(R.string.settings_detection_allow_post))
            }
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    val color = if (ok) FinanceTheme.extended.positive else MaterialTheme.colorScheme.error
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Icon shape (check vs. warning) + text carry the state, not colour alone.
        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.s))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ExportSection(exporting: Boolean, onSave: () -> Unit, onShare: () -> Unit) {
    SectionCard(title = stringResource(R.string.settings_export)) {
        Text(stringResource(R.string.settings_export_desc), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(Spacing.m))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onSave, enabled = !exporting) { Text(stringResource(R.string.settings_export_save)) }
            OutlinedButton(onClick = onShare, enabled = !exporting) { Text(stringResource(R.string.settings_export_share)) }
            if (exporting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun PrivacySection() {
    SectionCard(title = stringResource(R.string.settings_privacy)) {
        listOf(
            R.string.settings_privacy_local,
            R.string.settings_privacy_backup,
            R.string.settings_privacy_pin,
            R.string.settings_privacy_db
        ).forEach {
            Text("• " + stringResource(it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = Spacing.xxs))
        }
        Spacer(Modifier.height(Spacing.m))
        Text(
            stringResource(R.string.settings_about) + " · " + stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        // The whole row is the toggle so TalkBack announces the title together with the state.
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
    )
}

