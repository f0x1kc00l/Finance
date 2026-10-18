package com.financeapp.presentation.screens.pin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financeapp.R
import com.financeapp.core.security.PinPolicy
import com.financeapp.domain.pin.PinFlowMessage
import com.financeapp.domain.pin.PinScreenMode
import com.financeapp.domain.pin.PinSetupStep
import com.financeapp.presentation.screens.lock.formatDuration
import com.financeapp.presentation.theme.Spacing
import com.financeapp.presentation.ui.FinanceTopBar
import com.financeapp.presentation.ui.PinDots
import com.financeapp.presentation.ui.PinPad

@Composable
fun PinFlowScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    vm: PinFlowViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val flow = state.flow

    // FLAG_SECURE for this route is applied centrally by AppRoot (so it can't fight the
    // "hide in Recents" setting by clearing the flag on dispose).

    LaunchedEffect(state.finished) { if (state.finished) onDone() }

    val (title, subtitle) = when (flow.step) {
        PinSetupStep.VERIFY_CURRENT -> stringResource(R.string.pin_current_title) to stringResource(
            if (vm.mode == PinScreenMode.DISABLE) R.string.pin_current_subtitle_disable else R.string.pin_current_subtitle_change
        )
        PinSetupStep.CREATE -> stringResource(R.string.pin_create_title) to stringResource(R.string.pin_create_subtitle)
        PinSetupStep.CONFIRM -> stringResource(R.string.pin_confirm_title) to stringResource(R.string.pin_confirm_subtitle)
    }
    val topTitle = stringResource(
        when (vm.mode) {
            PinScreenMode.SETUP -> R.string.settings_set_pin
            PinScreenMode.CHANGE -> R.string.settings_change_pin
            PinScreenMode.DISABLE -> R.string.settings_lock
        }
    )

    Scaffold(topBar = { FinanceTopBar(title = topTitle, onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl, vertical = Spacing.l),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(Spacing.xl))
            PinDots(filled = flow.entered, total = PinPolicy.LENGTH, isError = flow.message != null, shakeKey = flow.shakeKey)
            Spacer(Modifier.height(Spacing.m))
            val message = when (val m = flow.message) {
                PinFlowMessage.Mismatch -> stringResource(R.string.pin_mismatch)
                PinFlowMessage.TooWeak -> stringResource(R.string.pin_too_weak)
                is PinFlowMessage.WrongPin -> if (m.attemptsLeft <= 1) stringResource(R.string.lock_wrong_pin_last)
                else stringResource(R.string.lock_wrong_pin, m.attemptsLeft)
                is PinFlowMessage.LockedOut -> stringResource(R.string.lock_locked_out) + "\n" +
                    stringResource(R.string.lock_retry_in, formatDuration(m.remainingMs))
                null -> ""
            }
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
            Spacer(Modifier.height(Spacing.m))
            Box(contentAlignment = Alignment.Center) {
                PinPad(onDigit = vm::onDigit, onDelete = vm::onDelete, enabled = !state.busy && !state.finished)
                if (state.verifying) CircularProgressIndicator()
            }
        }
    }

    if (state.showSecurityWarning) {
        AlertDialog(
            onDismissRequest = vm::dismissSecurityWarning,
            title = { Text(stringResource(R.string.security_warning_title)) },
            text = { Text(stringResource(R.string.security_warning_body)) },
            confirmButton = { TextButton(onClick = vm::dismissSecurityWarning) { Text(stringResource(R.string.security_warning_ok)) } }
        )
    }
}
