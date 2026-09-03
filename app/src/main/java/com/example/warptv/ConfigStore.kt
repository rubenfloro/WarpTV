package com.example.warptv

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small encrypted store for the WireGuard config. */
class ConfigStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("warp_secure", Context.MODE_PRIVATE)
    private val alias = "warp_tv_config_key"

    fun save(configText: String) {
        val key = getOrCreateKey()
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(configText.toByteArray(StandardCharsets.UTF_8))
        prefs.edit()
            .putString("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    fun load(): String? {
        val iv = prefs.getString("iv", null) ?: return null
        val data = prefs.getString("data", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), StandardCharsets.UTF_8)
        } catch (_: Exception) { null }
    }

    fun clear() { prefs.edit().clear().apply() }

    private fun getOrCreateKey(): SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(android.security.keystore.KeyGenParameterSpec.Builder(
            alias,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
}
