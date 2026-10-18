package com.financeapp.presentation.screens.lock

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withResumed
import com.financeapp.R
import com.financeapp.presentation.findFragmentActivity
import com.financeapp.presentation.security.BiometricPrompts
import com.financeapp.presentation.theme.Spacing
import com.financeapp.presentation.ui.PinDots
import com.financeapp.presentation.ui.PinPad

/**
 * Full-screen lock gate. Rendered INSTEAD of the navigation host while the session is locked,
 * so there is nothing underneath to navigate back to.
 */
@Composable
fun LockScreen(vm: LockViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findFragmentActivity()
    val lifecycleOwner = LocalLifecycleOwner.current

    // No BackHandler: Back from the lock screen leaves the app; there is no content beneath it.

    fun launchBiometric() {
        val act = activity ?: return
        val cipher = vm.biometricCipher() ?: return
        BiometricPrompts.authenticateBiometric(act, cipher, vm::onBiometricResult)
    }

    LaunchedEffect(Unit) { vm.onShown() }

    // Offer biometrics automatically once per lock-screen appearance.
    var autoPrompted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.biometricOffered, state.isLockedOut) {
        if (state.biometricOffered && !state.isLockedOut && !autoPrompted) {
            autoPrompted = true
            lifecycleOwner.withResumed { launchBiometric() }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl, vertical = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(Spacing.xl))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(72.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (state.isLockedOut) Icons.Rounded.LockClock else Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Spacer(Modifier.height(Spacing.l))

            when (state.mode) {
                LockMode.LOADING -> CircularProgressIndicator()
                LockMode.PIN -> PinContent(state, vm, onBiometric = ::launchBiometric)
                LockMode.DEVICE_CREDENTIAL -> DeviceCredentialContent(state) {
                    activity?.let { act ->
                        BiometricPrompts.authenticateDeviceCredential(
                            act, act.getString(R.string.device_credential_unlock_title), vm::onDeviceCredentialUnlock
                        )
                    }
                }
                LockMode.NO_SECURE_METHOD -> NoSecureMethodContent(onContinue = vm::disableUnenforceableLock)
            }
        }
    }

    if (state.showForgotDialog) {
        AlertDialog(
            onDismissRequest = { vm.showForgotDialog(false) },
            title = { Text(stringResource(R.string.lock_forgot_title)) },
            text = {
                Text(stringResource(if (state.deviceSecure) R.string.lock_forgot_body else R.string.lock_forgot_no_device_lock))
            },
            confirmButton = {
                if (state.deviceSecure) {
                    TextButton(onClick = {
                        activity?.let { act ->
                            BiometricPrompts.authenticateDeviceCredential(
                                act, act.getString(R.string.device_credential_reset_title), vm::onPinResetVerified
                            )
                        }
                    }) { Text(stringResource(R.string.lock_forgot_confirm)) }
                } else {
                    TextButton(onClick = { vm.showEraseDialog(true) }) {
                        Text(stringResource(R.string.lock_erase_all), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.showForgotDialog(false) }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (state.showEraseDialog) {
        AlertDialog(
            onDismissRequest = { vm.showEraseDialog(false) },
            title = { Text(stringResource(R.string.lock_erase_confirm_title)) },
            text = { Text(stringResource(R.string.lock_erase_confirm_body)) },
            confirmButton = {
                TextButton(onClick = vm::eraseAllData) {
                    Text(stringResource(R.string.lock_erase_all), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.showEraseDialog(false) }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun PinContent(state: LockUiState, vm: LockViewModel, onBiometric: () -> Unit) {
    Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(Spacing.xs))
    Text(
        stringResource(R.string.lock_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(Spacing.xl))
    PinDots(
        filled = state.entered,
        total = state.pinLength,
        isError = state.message is LockMessage.WrongPin,
        shakeKey = state.shakeKey
    )
    Spacer(Modifier.height(Spacing.m))

    val message = when {
        state.isLockedOut -> stringResource(R.string.lock_locked_out) + "\n" +
            stringResource(R.string.lock_retry_in, formatDuration(state.lockoutRemainingMs))
        else -> when (val m = state.message) {
            is LockMessage.WrongPin -> if (m.attemptsLeft <= 1) stringResource(R.string.lock_wrong_pin_last)
            else stringResource(R.string.lock_wrong_pin, m.attemptsLeft)
            LockMessage.BiometricReset -> stringResource(R.string.lock_biometric_reset)
            is LockMessage.System -> m.text
            null -> ""
        }
    }
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
    )
    Spacer(Modifier.height(Spacing.m))

    Box(contentAlignment = Alignment.Center) {
        PinPad(
            onDigit = vm::onDigit,
            onDelete = vm::onDelete,
            enabled = state.inputEnabled,
            bottomStart = if (state.biometricOffered) {
                {
                    FilledTonalIconButton(onClick = onBiometric, enabled = !state.isLockedOut, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.Fingerprint, contentDescription = stringResource(R.string.lock_use_biometric))
                    }
                }
            } else null
        )
        if (state.verifying) CircularProgressIndicator()
    }
    Spacer(Modifier.height(Spacing.l))
    TextButton(onClick = { vm.showForgotDialog(true) }) { Text(stringResource(R.string.lock_forgot)) }
}

@Composable
private fun DeviceCredentialContent(state: LockUiState, onUnlock: () -> Unit) {
    Text(stringResource(R.string.lock_device_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(Spacing.s))
    Text(
        stringResource(R.string.lock_device_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    (state.message as? LockMessage.System)?.let {
        Spacer(Modifier.height(Spacing.s))
        Text(it.text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(Spacing.xl))
    Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(stringResource(R.string.lock_device_unlock))
    }
}

@Composable
private fun NoSecureMethodContent(onContinue: () -> Unit) {
    Text(stringResource(R.string.lock_device_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(Spacing.s))
    Text(
        stringResource(R.string.lock_device_unavailable),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(Spacing.xl))
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(stringResource(R.string.action_continue))
    }
}

/** "0:42" / "14:59". */
internal fun formatDuration(ms: Long): String {
    val totalSeconds = ((ms + 999) / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

