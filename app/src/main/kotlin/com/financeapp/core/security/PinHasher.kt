package com.financeapp.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Device-bound secret transformation (a "pepper"). On Android this is an HMAC-SHA256 computed
 * inside the Android Keystore with a non-exportable key, so a stolen hash record cannot be
 * brute-forced off the device. Tests use a software key.
 */
fun interface Pepper {
    fun apply(data: ByteArray): ByteArray
}

/**
 * PIN hashing.
 *
 * record = "v3:<iterations>:<salt b64>:<hash b64>"
 * hash   = Pepper( PBKDF2-HMAC-SHA256(pin, salt, iterations, 256 bit) )
 *
 * Rationale (Android):
 *  - A 4-digit PIN has only 10 000 values, so no KDF work factor can stop an OFFLINE attack
 *    on its own. The protection against offline guessing is the Keystore-bound pepper; the
 *    protection against ONLINE guessing is [PinAttemptPolicy]'s escalating lockout.
 *  - PBKDF2 (software, javax.crypto, available since API 26) still adds a per-guess cost and
 *    a per-install random salt; [DEFAULT_ITERATIONS] keeps verification ≈100–300 ms on
 *    mid-range devices. It always runs off the main thread.
 *  - Exactly ONE Keystore operation per verification. (The previous implementation ran
 *    ~310 000 Keystore HMAC calls per check, which could take minutes.)
 *  - Comparison is constant-time; the caller's CharArray is wiped.
 */
class PinHasher(
    private val pepper: Pepper,
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom()
) {

    fun hash(pin: CharArray): String {
        try {
            require(PinPolicy.isWellFormed(pin)) { "PIN must be ${PinPolicy.LENGTH} digits" }
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val digest = derive(pin, salt, iterations)
            val enc = Base64.getEncoder()
            return "$VERSION:$iterations:${enc.encodeToString(salt)}:${enc.encodeToString(digest)}"
        } finally {
            pin.fill('\u0000')
        }
    }

    /** Returns false for any malformed or unknown-version record (fail closed). */
    fun verify(pin: CharArray, record: String): Boolean {
        try {
            if (!PinPolicy.isWellFormed(pin)) return false
            val parts = record.split(':')
            if (parts.size != 4 || parts[0] != VERSION) return false
            val iter = parts[1].toIntOrNull()?.takeIf { it in MIN_ITERATIONS..MAX_ITERATIONS } ?: return false
            val dec = Base64.getDecoder()
            val salt = runCatching { dec.decode(parts[2]) }.getOrNull() ?: return false
            val expected = runCatching { dec.decode(parts[3]) }.getOrNull() ?: return false
            val actual = derive(pin, salt, iter)
            return MessageDigest.isEqual(expected, actual)
        } finally {
            pin.fill('\u0000')
        }
    }

    /** True when the record uses weaker parameters than the current defaults. */
    fun needsRehash(record: String): Boolean {
        val parts = record.split(':')
        return parts.size != 4 || parts[0] != VERSION || (parts[1].toIntOrNull() ?: 0) < iterations
    }

    private fun derive(pin: CharArray, salt: ByteArray, iter: Int): ByteArray {
        val spec = PBEKeySpec(pin.copyOf(), salt, iter, HASH_BITS)
        try {
            val stretched = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try {
                return pepper.apply(stretched)
            } finally {
                stretched.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val VERSION = "v3"
        const val DEFAULT_ITERATIONS = 120_000
        private const val MIN_ITERATIONS = 10_000
        private const val MAX_ITERATIONS = 5_000_000
        private const val SALT_BYTES = 16
        private const val HASH_BITS = 256
    }
}

object PinPolicy {
    const val LENGTH = 4

    fun isWellFormed(pin: CharArray): Boolean = pin.size == LENGTH && pin.all { it in '0'..'9' }

    /**
     * Rejects the most-guessed PINs: all-same digits (0000) and straight runs (1234, 9876).
     * Applied only when CREATING a PIN, never when verifying.
     */
    fun isTooWeak(pin: CharArray): Boolean {
        if (!isWellFormed(pin)) return true
        val d = pin.map { it - '0' }
        if (d.all { it == d[0] }) return true
        val steps = d.zipWithNext { a, b -> b - a }.toSet()
        return steps == setOf(1) || steps == setOf(-1)
    }
}

/**
 * Online brute-force throttling.
 *
 * The first [FREE_ATTEMPTS] wrong PINs are free. Each further wrong PIN starts a lockout that
 * doubles from [BASE_LOCKOUT_MS] up to [MAX_LOCKOUT_MS] (30 s, 1 min, 2 min … 15 min). The cap
 * keeps an attacker from locking the owner out for days (denial of service) while still making
 * exhaustive guessing of 10 000 PINs take months.
 *
 * Wall-clock is used so the lockout survives reboots. If the clock is moved BACKWARDS the
 * remaining time is clamped to the policy maximum for the current failure count; moving the
 * clock FORWARDS can shorten a lockout (documented limitation — no trusted time source).
 */
object PinAttemptPolicy {
    const val FREE_ATTEMPTS = 5
    const val BASE_LOCKOUT_MS = 30_000L
    const val MAX_LOCKOUT_MS = 15 * 60_000L

    fun lockoutDurationMs(failures: Int): Long {
        if (failures < FREE_ATTEMPTS) return 0L
        val exponent = (failures - FREE_ATTEMPTS).coerceAtMost(20)
        return (BASE_LOCKOUT_MS shl exponent).coerceAtMost(MAX_LOCKOUT_MS)
    }

    fun remainingMs(nowMs: Long, lockedUntilMs: Long, failures: Int): Long {
        if (lockedUntilMs <= 0L) return 0L
        val remaining = lockedUntilMs - nowMs
        if (remaining <= 0L) return 0L
        val ceiling = lockoutDurationMs(failures).coerceAtLeast(BASE_LOCKOUT_MS)
        return remaining.coerceAtMost(ceiling)
    }

    fun attemptsBeforeLockout(failures: Int): Int = (FREE_ATTEMPTS - failures).coerceAtLeast(0)
}
