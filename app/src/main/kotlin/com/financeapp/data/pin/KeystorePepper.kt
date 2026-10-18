package com.financeapp.data.pin

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.financeapp.core.security.Pepper
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HMAC-SHA256 with a non-exportable Android Keystore key (StrongBox when available).
 * Exactly one Keystore operation per PIN check.
 *
 * The key needs no user authentication: it protects the hash at rest against offline
 * brute force; the app PIN itself is the authentication factor.
 */
@Singleton
class KeystorePepper @Inject constructor() : Pepper {

    @Synchronized
    override fun apply(data: ByteArray): ByteArray {
        val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
        mac.init(key())
        return mac.doFinal(data)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        // Remove the key used by the unreachable v1.0 PIN code, if present.
        runCatching { if (ks.containsAlias(LEGACY_ALIAS)) ks.deleteEntry(LEGACY_ALIAS) }
        return try {
            generate(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "finance_pin_pepper_v3"
        const val LEGACY_ALIAS = "finance_pin_hmac_key"
    }
}
