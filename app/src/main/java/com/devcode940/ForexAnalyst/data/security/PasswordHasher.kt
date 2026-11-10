package com.devcode940.ForexAnalyst.data.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordHasher {
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val DEFAULT_ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private val secureRandom = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(16)
        secureRandom.nextBytes(salt)
        val derived = deriveKey(password, salt, DEFAULT_ITERATIONS, KEY_LENGTH_BITS)
        return "$DEFAULT_ITERATIONS:${Base64.getEncoder().encodeToString(salt)}:${Base64.getEncoder().encodeToString(derived)}"
    }

    fun verify(password: String, storedHash: String): Boolean {
        if (password.isBlank() || storedHash.isBlank()) return false

        val parts = storedHash.split(":")
        if (parts.size != 3) return false

        return try {
            val iterations = parts[0].toIntOrNull() ?: return false
            val salt = Base64.getDecoder().decode(parts[1])
            val expected = Base64.getDecoder().decode(parts[2])
            val actual = deriveKey(password, salt, iterations, expected.size * 8)
            constantTimeEquals(actual, expected)
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun deriveKey(
        password: String,
        salt: ByteArray,
        iterations: Int,
        keyLengthBits: Int
    ): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keyLengthBits)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        return factory.generateSecret(spec).encoded
    }

    private fun constantTimeEquals(actual: ByteArray, expected: ByteArray): Boolean {
        if (actual.size != expected.size) return false
        var diff = 0
        for (i in actual.indices) {
            diff = diff or (actual[i].toInt() xor expected[i].toInt())
        }
        return diff == 0
    }
}
