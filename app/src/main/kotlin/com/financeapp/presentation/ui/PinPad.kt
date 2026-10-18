package com.financeapp.presentation.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.financeapp.R
import com.financeapp.presentation.theme.Spacing

/** Filled/empty dots for PIN progress; shakes whenever [shakeKey] changes (wrong PIN). */
@Composable
fun PinDots(filled: Int, total: Int, isError: Boolean, shakeKey: Int, modifier: Modifier = Modifier) {
    val offset = remember { Animatable(0f) }
    LaunchedEffect(shakeKey) {
        if (shakeKey > 0) {
            offset.animateTo(0f, keyframes {
                durationMillis = 400
                (-14f) at 50
                14f at 110
                (-10f) at 170
                10f at 230
                (-5f) at 290
                5f at 350
            })
        }
    }
    val description = stringResource(R.string.pin_progress_a11y, filled, total)
    Row(
        modifier = modifier
            .graphicsLayer { translationX = offset.value }
            .semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(Spacing.l)
    ) {
        val active = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        repeat(total) { i ->
            val isFilled = i < filled
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (isFilled) active else MaterialTheme.colorScheme.surface)
                    .border(1.5.dp, if (isFilled) active else MaterialTheme.colorScheme.outline, CircleShape)
            )
        }
    }
}

/**
 * Numeric keypad. [bottomStart] is an optional slot left of "0" (e.g. biometric button).
 * Keys are 72 dp — well above the 48 dp minimum touch target.
 */
@Composable
fun PinPad(
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    bottomStart: (@Composable () -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.m)
    ) {
        listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                row.forEach { d ->
                    DigitKey(d, enabled) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onDigit(d)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(KEY), contentAlignment = Alignment.Center) { bottomStart?.invoke() }
            DigitKey(0, enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onDigit(0)
            }
            Box(Modifier.size(KEY), contentAlignment = Alignment.Center) {
                IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.size(KEY)) {
                    Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = stringResource(R.string.pin_delete))
                }
            }
        }
    }
}

@Composable
private fun DigitKey(digit: Int, enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.pin_digit, digit)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .size(KEY)
            .semantics { contentDescription = label }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                digit.toString(),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.clearAndSetSemantics { }
            )
        }
    }
}

private val KEY = 72.dp

