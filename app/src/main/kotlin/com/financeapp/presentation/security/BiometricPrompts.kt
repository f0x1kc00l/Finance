package com.financeapp.presentation.security

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.financeapp.R
import javax.crypto.Cipher

/** Outcome of a system authentication prompt. */
sealed interface AuthOutcome {
    /** [cipher] is the CryptoObject cipher, authorised by the biometric (null for device credential). */
    data class Success(val cipher: Cipher?) : AuthOutcome
    /** User dismissed or chose the negative button — not an error. */
    data object Cancelled : AuthOutcome
    data class Error(val message: String) : AuthOutcome
}

object BiometricPrompts {

    /**
     * Device credential (screen-lock PIN/pattern/password) is combined with biometrics because
     * DEVICE_CREDENTIAL alone and STRONG|DEVICE_CREDENTIAL are unsupported on API 29.
     */
    private val deviceCredentialAuthenticators: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun canUseStrongBiometric(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun isDeviceSecure(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(deviceCredentialAuthenticators) == BiometricManager.BIOMETRIC_SUCCESS

    /** Strong biometric only, bound to [cipher]; the negative button falls back to the app PIN. */
    fun authenticateBiometric(activity: FragmentActivity, cipher: Cipher, onResult: (AuthOutcome) -> Unit) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.biometric_prompt_title))
            .setSubtitle(activity.getString(R.string.biometric_prompt_subtitle))
            .setNegativeButtonText(activity.getString(R.string.biometric_prompt_use_pin))
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
        prompt(activity, onResult).authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    /** Confirms the device owner via the system screen lock (used for PIN recovery). */
    fun authenticateDeviceCredential(activity: FragmentActivity, title: String, onResult: (AuthOutcome) -> Unit) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(deviceCredentialAuthenticators)
            .build()
        prompt(activity, onResult).authenticate(info)
    }

    private fun prompt(activity: FragmentActivity, onResult: (AuthOutcome) -> Unit) =
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onResult(AuthOutcome.Success(result.cryptoObject?.cipher))
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                onResult(if (cancelled) AuthOutcome.Cancelled else AuthOutcome.Error(errString.toString()))
            }
            // onAuthenticationFailed (one unrecognised finger) is handled by the system prompt
            // itself and deliberately does NOT count toward the app-PIN lockout.
        })
}
