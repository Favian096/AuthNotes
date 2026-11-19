package com.authnotes.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MasterKeyVault(private val preferences: android.content.SharedPreferences) {
    private val alias = "authnotes.master.wrap.v1"

    fun save(password: String, slot: String = "current") {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try { cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }
        catch (_: Exception) {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val plaintext = password.toByteArray(StandardCharsets.UTF_8)
        val encrypted = try { cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
        check(preferences.edit()
            .putString("$slot.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("$slot.ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()) { "Could not save the local unlock credential" }
    }

    fun load(slot: String = "current"): String? {
        val ivText = preferences.getString("$slot.iv", null) ?: return null
        val encryptedText = preferences.getString("$slot.ciphertext", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP))
            )
            val plaintext = cipher.doFinal(Base64.decode(encryptedText, Base64.NO_WRAP))
            try { String(plaintext, StandardCharsets.UTF_8) } finally { plaintext.fill(0) }
        } catch (_: Exception) {
            null
        }
    }

    fun clearPending() {
        check(preferences.edit().remove("pending.iv").remove("pending.ciphertext").commit())
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        runCatching { keyStore.getKey(alias, null) as? SecretKey }.getOrNull()?.let { return it }
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
