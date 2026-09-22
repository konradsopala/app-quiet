package com.booking.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-HMAC-SHA256 hashing for operator PINs.
 *
 * PINs are short and low-entropy, so the iteration count is the only thing
 * standing between a leaked snapshot and every operator's PIN. Keep
 * [iterations] high and bump it when hardware gets faster; existing hashes
 * carry their own iteration count so old records keep verifying.
 *
 * Stored form: `pbkdf2-sha256$<iterations>$<hex key>`; the salt is stored
 * beside it on the [com.booking.model.Operator].
 */
class PinHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom()
) {
    companion object {
        const val DEFAULT_ITERATIONS = 120_000
        const val SALT_BYTES = 16
        const val KEY_BITS = 256
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 8
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val PREFIX = "pbkdf2-sha256"
    }

    class WeakPinException(message: String) : IllegalArgumentException(message)

    /** Random salt as lower-case hex. One per operator, regenerated on every PIN change. */
    fun newSalt(): String {
        val bytes = ByteArray(SALT_BYTES)
        random.nextBytes(bytes)
        return bytes.toHex()
    }

    /**
     * Reject PINs an attacker would try first. Digits only, 4–8 long, not
     * a single repeated digit, not a straight run up or down.
     */
    fun validatePin(pin: String) {
        if (pin.length !in MIN_PIN_LENGTH..MAX_PIN_LENGTH) {
            throw WeakPinException("PIN must be $MIN_PIN_LENGTH-$MAX_PIN_LENGTH digits.")
        }
        if (!pin.all { it.isDigit() }) throw WeakPinException("PIN must contain digits only.")
        if (pin.toSet().size == 1) throw WeakPinException("PIN cannot be a single repeated digit.")
        val ascending = pin.zipWithNext().all { (a, b) -> b - a == 1 }
        val descending = pin.zipWithNext().all { (a, b) -> a - b == 1 }
        if (ascending || descending) throw WeakPinException("PIN cannot be a straight sequence like 1234.")
    }

    fun hash(pin: String, saltHex: String): String {
        val key = derive(pin, saltHex.hexToBytes(), iterations)
        return "$PREFIX\$$iterations\$${key.toHex()}"
    }

    /**
     * Constant-time comparison against a stored hash. Malformed stored
     * values verify as false rather than throwing, so a corrupted snapshot
     * locks the account out instead of crashing sign-in.
     */
    fun verify(pin: String, saltHex: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 3 || parts[0] != PREFIX) return false
        val storedIterations = parts[1].toIntOrNull() ?: return false
        val expected = try { parts[2].hexToBytes() } catch (e: IllegalArgumentException) { return false }
        val actual = derive(pin, saltHex.hexToBytes(), storedIterations)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derive(pin: String, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, rounds, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
