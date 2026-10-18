package com.financeapp.payment

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wallet.IsReadyToPayRequest
import com.google.android.gms.wallet.PaymentData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface GooglePayState {
    data object NotConfigured : GooglePayState
    data object Checking : GooglePayState
    data object Available : GooglePayState
    data object NotAvailable : GooglePayState
    /** Sheet shown or token being verified; further attempts are ignored. */
    data object Processing : GooglePayState
    data class Confirmed(val transactionId: String) : GooglePayState
    /** Token obtained, but nothing could confirm a charge — must not be shown as success. */
    data object Unverified : GooglePayState
    data class Failed(val reason: String) : GooglePayState
}

/**
 * Not wired to any screen (see GooglePayHelper KDoc). State transitions are guarded so a
 * payment can only be reported as Confirmed by a [PaymentVerifier] and double taps can't
 * start two payments.
 */
@HiltViewModel
class GooglePayViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val config = GooglePayConfig.fromBuildConfig()
    private val verifier: PaymentVerifier = UnconfiguredPaymentVerifier
    private val paymentsClient = if (config.isConfigured) GooglePayRequests.createPaymentsClient(application, config) else null

    private val _state = MutableStateFlow<GooglePayState>(GooglePayState.NotConfigured)
    val state: StateFlow<GooglePayState> = _state.asStateFlow()

    private var pending: Pair<Long, String>? = null

    init {
        val client = paymentsClient
        if (client != null) {
            _state.value = GooglePayState.Checking
            client.isReadyToPay(IsReadyToPayRequest.fromJson(GooglePayRequests.isReadyToPayRequest().toString()))
                .addOnCompleteListener { task ->
                    _state.value = try {
                        if (task.getResult(ApiException::class.java) == true) GooglePayState.Available else GooglePayState.NotAvailable
                    } catch (_: ApiException) {
                        GooglePayState.NotAvailable
                    }
                }
        }
    }

    /** Returns the request JSON to launch, or null if a payment can't start now. */
    fun startPayment(amountMinor: Long, currencyCode: String): String? {
        if (_state.value != GooglePayState.Available) return null
        val request = runCatching { GooglePayRequests.paymentDataRequest(config, amountMinor, currencyCode) }.getOrNull()
            ?: return null
        pending = amountMinor to currencyCode
        _state.value = GooglePayState.Processing
        return request.toString()
    }

    fun onPaymentData(paymentData: PaymentData) {
        val (amount, currency) = pending ?: return
        pending = null
        val token = GooglePayRequests.extractToken(paymentData.toJson())
        if (token == null) {
            _state.value = GooglePayState.Failed("missing token")
            return
        }
        viewModelScope.launch {
            _state.value = when (val v = verifier.verify(token, amount, currency)) {
                is PaymentVerification.Confirmed -> GooglePayState.Confirmed(v.transactionId)
                is PaymentVerification.Declined -> GooglePayState.Failed(v.reason)
                PaymentVerification.NotConfigured -> GooglePayState.Unverified
            }
        }
    }

    fun onCancelled() {
        pending = null
        if (_state.value == GooglePayState.Processing) _state.value = GooglePayState.Available
    }

    fun onError(statusCode: Int) {
        pending = null
        _state.value = GooglePayState.Failed("Google Pay error $statusCode")
    }
}
