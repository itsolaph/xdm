package com.olaph.xdm.vault

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** What is stored for a PIN: never the PIN itself. */
data class PinHash(val salt: ByteArray, val hash: ByteArray, val iterations: Int) {
    fun encode(): String = listOf(iterations.toString(), b64(salt), b64(hash)).joinToString(":")

    override fun equals(other: Any?): Boolean =
        other is PinHash && iterations == other.iterations && salt.contentEquals(other.salt) && hash.contentEquals(other.hash)

    override fun hashCode(): Int = 31 * (31 * salt.contentHashCode() + hash.contentHashCode()) + iterations

    companion object {
        private fun b64(b: ByteArray) = Base64.getEncoder().withoutPadding().encodeToString(b)

        fun decode(s: String?): PinHash? = runCatching {
            val (it, salt, hash) = s!!.split(':')
            PinHash(Base64.getDecoder().decode(salt), Base64.getDecoder().decode(hash), it.toInt())
        }.getOrNull()?.takeIf { it.salt.isNotEmpty() && it.hash.isNotEmpty() && it.iterations > 0 }
    }
}

/** PBKDF2-HMAC-SHA256 over a random salt; verification compares in constant time. */
object PinHasher {
    const val ITERATIONS = 150_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private val PIN = Regex("^[0-9]{4,8}$")

    fun isValidPin(pin: String): Boolean = PIN.matches(pin)

    fun hash(pin: String, salt: ByteArray = newSalt(), iterations: Int = ITERATIONS): PinHash {
        require(isValidPin(pin)) { "PIN must be 4 to 8 digits" }
        return PinHash(salt, derive(pin, salt, iterations), iterations)
    }

    fun verify(pin: String, stored: PinHash): Boolean {
        if (!isValidPin(pin)) return false
        return MessageDigest.isEqual(derive(pin, stored.salt, stored.iterations), stored.hash)
    }

    private fun newSalt() = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
