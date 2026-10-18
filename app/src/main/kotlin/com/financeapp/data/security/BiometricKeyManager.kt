package com.financeapp.data.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Binds biometric unlock to a Keystore key so it can't be satisfied by a UI callback alone.
 *
 * The AES key requires a STRONG biometric for every use and is invalidated by Android as soon
 * as a new fingerprint/face is enrolled. Consequence: someone who knows the device PIN and
 * enrolls their own finger cannot use it to open this app — biometric unlock is switched off
 * and the app PIN is required (the user can re-enable biometrics after unlocking).
 */
@Singleton
class BiometricKeyManager @Inject constructor() {

    sealed interface CipherResult {
        data class Ready(val cipher: Cipher) : CipherResult
        /** Enrollment changed (or key wiped): biometric unlock must be re-enabled with the PIN. */
        data object Invalidated : CipherResult
        data object Unavailable : CipherResult
    }

    fun createKey() {
        deleteKey()
        val builder = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1) // auth required for every use
        }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(builder.build()) }
            .generateKey()
    }

    fun deleteKey() {
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    fun hasKey(): Boolean = runCatching { keyStore().containsAlias(ALIAS) }.getOrDefault(false)

    /** A cipher to hand to BiometricPrompt.CryptoObject; init fails if enrollment changed. */
    fun cipherForUnlock(): CipherResult {
        val key = runCatching { keyStore().getKey(ALIAS, null) as? SecretKey }.getOrNull()
            ?: return CipherResult.Invalidated
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            CipherResult.Ready(cipher)
        } catch (_: KeyPermanentlyInvalidatedException) {
            deleteKey()
            CipherResult.Invalidated
        } catch (_: Exception) {
            CipherResult.Unavailable
        }
    }

    /** Proves the prompt actually authorised the key (fails if the callback was spoofed). */
    fun confirm(cipher: Cipher?): Boolean =
        cipher != null && runCatching { cipher.doFinal(PROBE) }.isSuccess

    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "finance_biometric_unlock_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val PROBE = byteArrayOf(0x46, 0x41)
    }
}
