package app.matterpair.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores secrets (HA tokens) encrypted with an AES-256-GCM key that lives in the Android
 * Keystore and never leaves it. The ciphertext is kept in private SharedPreferences, which are
 * excluded from backups (see `data_extraction_rules.xml`).
 *
 * We deliberately avoid `androidx.security:security-crypto` (deprecated) and keep this small and
 * auditable.
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure", Context.MODE_PRIVATE)

    private val key: SecretKey by lazy {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: run {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            gen.generateKey()
        }
    }

    fun put(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString(name, blob).apply()
    }

    fun get(name: String): String? {
        val blob = prefs.getString(name, null) ?: return null
        return runCatching {
            val (ivB64, ctB64) = blob.split(':', limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "matterpair_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
