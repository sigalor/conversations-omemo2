package eu.siacs.conversations

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import org.signal.argon2.Argon2
import org.signal.argon2.MemoryCost
import org.signal.argon2.Type
import org.signal.argon2.Version
import java.nio.CharBuffer
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Derives the SQLCipher raw database key using the Molly approach:
 *
 *   Password + Salt → Argon2id (native C via im.molly:argon2) → 32 bytes
 *       → HMAC-SHA256 with hardware-backed Android KeyStore key → 32 bytes
 *       → formatted as x'<64 hex chars>'
 *
 * The x'...' prefix tells SQLCipher to treat the value as a pre-derived raw key,
 * completely bypassing its internal PBKDF2. This gives full control over the KDF.
 *
 * KeyStore entanglement binds the derived key to this specific device: an attacker
 * who extracts the DB file and learns the user's password still cannot decrypt
 * without access to the hardware-protected HMAC key.
 *
 * Argon2id parameters (64 MB / 3 iterations / 4 parallelism) meet and exceed the
 * OWASP minimum recommendations for interactive authentication contexts.
 */
object Argon2KeyDerivation {

    private const val TAG = "Argon2KeyDerivation"

    // HMAC key alias in the Android KeyStore — versioned so future rotations are possible.
    private const val HMAC_KEY_ALIAS = "db_argon2id_hmac_v1"

    // Output size for both Argon2id and HMAC-SHA256 — 32 bytes = 256-bit AES key.
    const val KEY_BYTES = 32

    // Salt length: 32 random bytes give 256 bits of salt entropy.
    const val SALT_LENGTH = 32

    // Lookup table for hex encoding — avoids String.format() per byte in formatAsRawSqlCipherKey.
    private val HEX_CHARS = ByteArray(16) { i -> "0123456789abcdef"[i].code.toByte() }

    // Argon2id tuning. At 64 MB memory the function is highly resistant to GPU/ASIC attacks.
    // 3 iterations and 4 parallelism are OWASP-recommended for interactive use.
    private const val MEMORY_MIB = 64
    private const val ITERATIONS = 3
    private const val PARALLELISM = 4

    /**
     * Derives a 32-byte SQLCipher raw key and returns it encoded as UTF-8 bytes of
     * x'<64 hex chars>'. Caller must zero the returned array after use.
     */
    fun deriveRawKeyBytes(password: CharArray, salt: ByteArray): ByteArray {
        val passwordBytes = charsToUtf8(password)
        try {
            val argon2Output = runArgon2id(passwordBytes, salt)
            try {
                val finalKey = hmacWithKeyStoreKey(argon2Output)
                try {
                    return formatAsRawSqlCipherKey(finalKey)
                } finally {
                    Arrays.fill(finalKey, 0)
                }
            } finally {
                Arrays.fill(argon2Output, 0)
            }
        } finally {
            Arrays.fill(passwordBytes, 0)
        }
    }

    /** Generates a cryptographically random 32-byte Argon2id salt. */
    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        return salt
    }

    /**
     * Generates a cryptographically random 32-byte raw key for auto-encryption mode.
     * This value is stored Tink-encrypted in DataStore and later passed to
     * [deriveAutoRawKeyBytes] to produce the final SQLCipher key.
     */
    fun generateRandomKey(): ByteArray {
        val key = ByteArray(KEY_BYTES)
        SecureRandom().nextBytes(key)
        return key
    }

    /**
     * Derives a SQLCipher raw key from a stored auto-key (random 32 bytes).
     * Applies HMAC-SHA256 with the hardware-backed KeyStore key to bind the result to this
     * device, then formats as x'<64 hex chars>'. Caller must zero the returned array.
     */
    fun deriveAutoRawKeyBytes(rawKey: ByteArray): ByteArray {
        require(rawKey.size == KEY_BYTES) { "Auto key must be $KEY_BYTES bytes, got ${rawKey.size}" }
        val hmacOutput = hmacWithKeyStoreKey(rawKey)
        try {
            return formatAsRawSqlCipherKey(hmacOutput)
        } finally {
            Arrays.fill(hmacOutput, 0)
        }
    }

    /**
     * Encodes 32 raw key bytes as x'<64 lowercase hex chars>' and returns the result as
     * UTF-8 bytes. SQLCipher recognises the x'...' prefix and uses the bytes as the
     * AES-256 cipher key directly, bypassing all KDF processing.
     */
    fun formatAsRawSqlCipherKey(keyBytes: ByteArray): ByteArray {
        require(keyBytes.size == KEY_BYTES) { "Raw key must be $KEY_BYTES bytes, got ${keyBytes.size}" }
        // Write directly into a byte array — avoids any String/StringBuilder on the heap
        // that would contain the live SQLCipher key and could not be zeroed.
        val out = ByteArray(2 + KEY_BYTES * 2 + 1)
        out[0] = 'x'.code.toByte()
        out[1] = '\''.code.toByte()
        for (i in keyBytes.indices) {
            val b = keyBytes[i].toInt() and 0xFF
            out[2 + i * 2]     = HEX_CHARS[b ushr 4]
            out[2 + i * 2 + 1] = HEX_CHARS[b and 0x0F]
        }
        out[out.size - 1] = '\''.code.toByte()
        return out
    }

    // ── Private ──────────────────────────────────────────────────────────────────────

    private fun runArgon2id(password: ByteArray, salt: ByteArray): ByteArray {
        val argon2 = Argon2.Builder(Version.V13)
            .type(Type.Argon2id)
            .memoryCost(MemoryCost.MiB(MEMORY_MIB))
            .parallelism(PARALLELISM)
            .iterations(ITERATIONS)
            .hashLength(KEY_BYTES)
            .build()
        return argon2.hash(password, salt).hash
    }

    /**
     * Computes HMAC-SHA256 of [data] using a hardware-backed (StrongBox or TEE) key from
     * the Android KeyStore. This binds the final DB key to the device hardware.
     */
    private fun hmacWithKeyStoreKey(data: ByteArray): ByteArray {
        val key = getOrCreateHmacKey()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal(data)
    }

    private fun getOrCreateHmacKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        (keyStore.getKey(HMAC_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return createHmacKey()
    }

    private fun createHmacKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore"
        )
        // Prefer StrongBox (dedicated Secure Element) for maximum hardware protection;
        // fall back gracefully to the regular hardware-backed TEE on older devices.
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                keyGenerator.init(
                    KeyGenParameterSpec.Builder(HMAC_KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setIsStrongBoxBacked(true)
                        .build()
                )
                return keyGenerator.generateKey().also {
                    Log.i(TAG, "HMAC key created in StrongBox")
                }
            } catch (_: android.security.keystore.StrongBoxUnavailableException) {
                Log.i(TAG, "StrongBox unavailable, using TEE")
            }
        }
        keyGenerator.init(
            KeyGenParameterSpec.Builder(HMAC_KEY_ALIAS, KeyProperties.PURPOSE_SIGN).build()
        )
        return keyGenerator.generateKey().also {
            Log.i(TAG, "HMAC key created in TEE-backed KeyStore")
        }
    }

    private fun charsToUtf8(chars: CharArray): ByteArray {
        val bb = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(bb.remaining())
        bb.get(bytes)
        if (bb.hasArray()) Arrays.fill(bb.array(), 0)
        return bytes
    }
}
