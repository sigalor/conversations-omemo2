package eu.siacs.conversations

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmKeyManager
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.Arrays

private val Context.securePasswordDataStore by preferencesDataStore(name = "secure_db_password")

/**
 * Stores the database password encrypted with Tink AES-256-GCM, keyed by the Android Keystore.
 * The Tink keyset lives in a regular SharedPreferences file (encrypted by the Keystore master
 * key). The actual ciphertext is stored in DataStore.
 *
 * Both read and write are synchronous (runBlocking) — callers must not be on the main thread
 * for reads (the DB-init background thread satisfies this); writes from the settings UI are
 * fast one-shot operations and acceptable.
 */
class SecurePasswordStorage(context: Context) {

    private val appContext: Context = context.applicationContext
    private val dataStore = appContext.securePasswordDataStore

    companion object {
        private const val KEYSET_NAME = "db_password_keyset"
        private const val KEYSET_PREF_FILE = "db_password_keyset_prefs"
        private const val MASTER_KEY_URI = "android-keystore://db_password_master_key"
        private val PASSWORD_KEY = stringPreferencesKey("encrypted_db_password")
    }

    private val aead: Aead by lazy {
        AeadConfig.register()
        val handle = AndroidKeysetManager.Builder()
            .withSharedPref(appContext, KEYSET_NAME, KEYSET_PREF_FILE)
            .withKeyTemplate(AesGcmKeyManager.aes256GcmTemplate())
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        handle.getPrimitive(Aead::class.java)
    }

    // Binds ciphertext to this app: decryption fails if the blob is moved to a different package.
    private val aad: ByteArray by lazy {
        appContext.packageName.toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Read the stored password as a char[]. Returns null if nothing is stored.
     * Zeroes all intermediate byte arrays.
     */
    fun readPassword(): CharArray? {
        val encoded: String = runBlocking {
            dataStore.data.map { it[PASSWORD_KEY] }.first()
        } ?: return null
        val ciphertext = Base64.decode(encoded, Base64.NO_WRAP)
        val plainBytes = aead.decrypt(ciphertext, aad)
        return try {
            bytesToChars(plainBytes)
        } finally {
            Arrays.fill(plainBytes, 0.toByte())
        }
    }

    /**
     * Encrypt and persist the password. Pass null to delete the stored value.
     * Zeroes all intermediate byte arrays.
     */
    fun writePassword(password: CharArray?) {
        if (password == null) {
            runBlocking { dataStore.edit { it.remove(PASSWORD_KEY) } }
            return
        }
        val plainBytes = charsToBytes(password)
        try {
            val ciphertext = aead.encrypt(plainBytes, aad)
            val encoded = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            runBlocking { dataStore.edit { it[PASSWORD_KEY] = encoded } }
        } finally {
            Arrays.fill(plainBytes, 0.toByte())
        }
    }

    // char[] → UTF-8 bytes, caller must zero result after use
    private fun charsToBytes(chars: CharArray): ByteArray {
        val bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(bb.remaining())
        bb.get(bytes)
        if (bb.hasArray()) Arrays.fill(bb.array(), 0.toByte())
        return bytes
    }

    // UTF-8 bytes → char[], caller must zero result after use
    private fun bytesToChars(bytes: ByteArray): CharArray {
        val cb = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes))
        val chars = CharArray(cb.remaining())
        cb.get(chars)
        if (cb.hasArray()) Arrays.fill(cb.array(), '\u0000')
        return chars
    }
}
