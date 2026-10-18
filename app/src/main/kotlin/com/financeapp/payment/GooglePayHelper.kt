package com.financeapp.payment

import android.content.Context
import com.financeapp.BuildConfig
import com.financeapp.core.money.Money
import com.google.android.gms.wallet.PaymentsClient
import com.google.android.gms.wallet.Wallet
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google Pay request building — NOT a payment processor.
 *
 * Status (see README "Google Pay"): this module is not reachable from any screen. It is kept
 * as a correctly-configured starting point, and it FAILS CLOSED:
 *  - The environment is TEST unless the build is given `googlePay.production=true` AND a
 *    real gateway + gateway merchant id + Google merchant id (gradle properties, never
 *    committed). Release builds no longer silently switch to PRODUCTION with the
 *    placeholder "example" gateway.
 *  - A Google Pay token is only an encrypted card credential. It is NOT proof that money moved.
 *    Only a server that charges the token through the gateway can confirm a payment, and this
 *    app has no such server, so [PaymentVerifier] always reports NotConfigured.
 */
data class GooglePayConfig(
    val production: Boolean,
    val merchantId: String,
    val merchantName: String,
    val gateway: String,
    val gatewayMerchantId: String
) {
    /** TEST environment needs a gateway name; PRODUCTION additionally needs the Google merchant id. */
    val isConfigured: Boolean
        get() = gateway.isNotBlank() && gatewayMerchantId.isNotBlank() && (!production || merchantId.isNotBlank())

    /** Raw WalletConstants values: ENVIRONMENT_PRODUCTION = 1, ENVIRONMENT_TEST = 3. */
    val walletEnvironment: Int get() = if (production && isConfigured) 1 else 3

    companion object {
        fun fromBuildConfig() = GooglePayConfig(
            production = BuildConfig.GOOGLE_PAY_PRODUCTION,
            merchantId = BuildConfig.GOOGLE_PAY_MERCHANT_ID,
            merchantName = BuildConfig.GOOGLE_PAY_MERCHANT_NAME,
            gateway = BuildConfig.GOOGLE_PAY_GATEWAY,
            gatewayMerchantId = BuildConfig.GOOGLE_PAY_GATEWAY_MERCHANT_ID
        )
    }
}

object GooglePayRequests {

    private fun apiVersion() = JSONObject().put("apiVersion", 2).put("apiVersionMinor", 0)

    private fun cardParameters() = JSONObject()
        .put("allowedAuthMethods", JSONArray(listOf("PAN_ONLY", "CRYPTOGRAM_3DS")))
        .put("allowedCardNetworks", JSONArray(listOf("MASTERCARD", "VISA")))

    private fun baseCardMethod() = JSONObject().put("type", "CARD").put("parameters", cardParameters())

    fun isReadyToPayRequest(): JSONObject =
        apiVersion().put("allowedPaymentMethods", JSONArray().put(baseCardMethod()))

    /**
     * @param amountMinor exact amount in minor units; serialised with BigDecimal (no Double).
     */
    fun paymentDataRequest(config: GooglePayConfig, amountMinor: Long, currencyCode: String, countryCode: String = "AM"): JSONObject {
        require(config.isConfigured) { "Google Pay is not configured" }
        require(Money.isValidAmount(amountMinor)) { "invalid amount" }
        val tokenization = JSONObject()
            .put("type", "PAYMENT_GATEWAY")
            .put("parameters", JSONObject()
                .put("gateway", config.gateway)
                .put("gatewayMerchantId", config.gatewayMerchantId))
        val card = baseCardMethod().put("tokenizationSpecification", tokenization)
        val merchant = JSONObject().put("merchantName", config.merchantName)
        if (config.merchantId.isNotBlank()) merchant.put("merchantId", config.merchantId)
        return apiVersion()
            .put("allowedPaymentMethods", JSONArray().put(card))
            .put("transactionInfo", JSONObject()
                .put("totalPrice", Money.toPlainString(amountMinor))
                .put("totalPriceStatus", "FINAL")
                .put("countryCode", countryCode)
                .put("currencyCode", currencyCode))
            .put("merchantInfo", merchant)
    }

    fun extractToken(paymentDataJson: String): String? = runCatching {
        JSONObject(paymentDataJson).getJSONObject("paymentMethodData")
            .getJSONObject("tokenizationData").getString("token")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun createPaymentsClient(context: Context, config: GooglePayConfig): PaymentsClient =
        Wallet.getPaymentsClient(context, Wallet.WalletOptions.Builder().setEnvironment(config.walletEnvironment).build())
}

sealed interface PaymentVerification {
    /** Server confirmed the charge with the gateway. */
    data class Confirmed(val transactionId: String) : PaymentVerification
    data class Declined(val reason: String) : PaymentVerification
    /** There is no backend to charge the token — the only honest answer in this app. */
    data object NotConfigured : PaymentVerification
}

/** Charges a token server-side. Implement against your backend; never "confirm" on device. */
fun interface PaymentVerifier {
    suspend fun verify(token: String, amountMinor: Long, currencyCode: String): PaymentVerification
}

object UnconfiguredPaymentVerifier : PaymentVerifier {
    override suspend fun verify(token: String, amountMinor: Long, currencyCode: String) = PaymentVerification.NotConfigured
}
