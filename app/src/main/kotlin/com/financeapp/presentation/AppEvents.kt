package com.financeapp.presentation

import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Cross-screen one-shot requests that outlive a single ViewModel. */
@Singleton
class AppEvents @Inject constructor() {
    private val _pinSetupRequested = MutableStateFlow(false)
    /** Set after a successful "Forgot PIN" reset: the nav host opens PIN creation once unlocked. */
    val pinSetupRequested: StateFlow<Boolean> = _pinSetupRequested.asStateFlow()

    fun requestPinSetup() { _pinSetupRequested.value = true }
    fun consumePinSetupRequest() { _pinSetupRequested.value = false }
}

/** Walks ContextWrappers to the hosting FragmentActivity (needed by BiometricPrompt). */
tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
