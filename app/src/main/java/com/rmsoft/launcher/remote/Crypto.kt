package com.rmsoft.launcher.remote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts sensitive strings (MQTT password, enrollment password, device token) with an
 * AES-256/GCM key held in the hardware-backed **Android Keystore** — the key never leaves secure
 * hardware and can't be extracted even off a rooted device. Replaces the deprecated
 * EncryptedSharedPreferences library (which silently dropped writes on Android 14+).
 *
 * Ciphertext format: Base64( iv[12] || ciphertext+tag ).
 */
object Crypto {
    private const val TAG = "Crypto"
    private const val KEY_ALIAS = "rmsoft_mdm_secret_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return kg.generateKey()
    }

    /** Encrypt; returns Base64 ciphertext, or the plaintext unchanged if encryption is unavailable. */
    fun encrypt(plain: String): String = runCatching {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }.getOrElse {
        Log.e(TAG, "encrypt failed — storing plaintext fallback", it)
        plain
    }

    /**
     * Decrypt Base64 ciphertext. Returns the input unchanged when it isn't our ciphertext (e.g. a
     * legacy plaintext value written before encryption was added), so upgrades don't lose data.
     */
    fun decrypt(data: String): String = runCatching {
        val bytes = Base64.decode(data, Base64.NO_WRAP)
        if (bytes.size <= IV_LEN) return data
        val iv = bytes.copyOfRange(0, IV_LEN)
        val ct = bytes.copyOfRange(IV_LEN, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrDefault(data)
}
