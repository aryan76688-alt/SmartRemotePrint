package com.smartremote.print.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecurityVault(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "smart_remote_secure_prefs"
        private const val KEY_ALIAS = "SmartRemotePrintKey"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_MODE = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128

        private const val PREF_HOST = "pref_server_host"
        private const val PREF_PORT = "pref_server_port"
        private const val PREF_ENCRYPTED_TOKEN = "pref_enc_token"
        private const val PREF_TOKEN_IV = "pref_token_iv"
        private const val PREF_TARGET_PRINTER = "pref_target_printer"
        private const val PREF_ESC_MAPPING = "pref_esc_mapping"
        private const val PREF_PAIRING_PIN = "pref_pairing_pin"
        const val DEFAULT_PIN = "260890"

        private const val PREF_ACC1_USER = "pref_acc1_user"
        private const val PREF_ACC1_PASS = "pref_acc1_pass"
        private const val PREF_ACC2_USER = "pref_acc2_user"
        private const val PREF_ACC2_PASS = "pref_acc2_pass"

        const val DEFAULT_ACC1_USER = "shreejiseven@gmail.com"
        const val DEFAULT_ACC1_PASS = "Tushar@260890"
        const val DEFAULT_ACC2_USER = "tushar9263@gmail.com"
        const val DEFAULT_ACC2_PASS = "Shreeji@9263"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        ensureKeyStoreKey()
    }

    private fun ensureKeyStoreKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as SecretKey
    }

    fun saveAuthToken(token: String) {
        try {
            val cipher = Cipher.getInstance(AES_MODE)
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
            val iv = cipher.iv
            val cipherText = cipher.doFinal(token.toByteArray(Charsets.UTF_8))

            prefs.edit()
                .putString(PREF_ENCRYPTED_TOKEN, Base64.encodeToString(cipherText, Base64.NO_WRAP))
                .putString(PREF_TOKEN_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getAuthToken(): String? {
        val encBase64 = prefs.getString(PREF_ENCRYPTED_TOKEN, null) ?: return null
        val ivBase64 = prefs.getString(PREF_TOKEN_IV, null) ?: return null

        return try {
            val encBytes = Base64.decode(encBase64, Base64.NO_WRAP)
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)

            val cipher = Cipher.getInstance(AES_MODE)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)

            val plainBytes = cipher.doFinal(encBytes)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun clearAuthToken() {
        prefs.edit().remove(PREF_ENCRYPTED_TOKEN).remove(PREF_TOKEN_IV).apply()
    }

    var serverHost: String
        get() = prefs.getString(PREF_HOST, "100.111.100.122") ?: "100.111.100.122"
        set(value) = prefs.edit().putString(PREF_HOST, value).apply()

    var serverPort: Int
        get() = prefs.getInt(PREF_PORT, 8766)
        set(value) = prefs.edit().putInt(PREF_PORT, value).apply()

    var pairingPin: String
        get() = prefs.getString(PREF_PAIRING_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
        set(value) = prefs.edit().putString(PREF_PAIRING_PIN, value).apply()

    var targetPrinter: String
        get() = prefs.getString(PREF_TARGET_PRINTER, "Brother DCP-T530DW Printer") ?: "Brother DCP-T530DW Printer"
        set(value) = prefs.edit().putString(PREF_TARGET_PRINTER, value).apply()

    var isEscMappingEnabled: Boolean
        get() = prefs.getBoolean(PREF_ESC_MAPPING, true)
        set(value) = prefs.edit().putBoolean(PREF_ESC_MAPPING, value).apply()

    var acc1User: String
        get() = prefs.getString(PREF_ACC1_USER, DEFAULT_ACC1_USER) ?: DEFAULT_ACC1_USER
        set(value) = prefs.edit().putString(PREF_ACC1_USER, value).apply()

    var acc1Pass: String
        get() = prefs.getString(PREF_ACC1_PASS, DEFAULT_ACC1_PASS) ?: DEFAULT_ACC1_PASS
        set(value) = prefs.edit().putString(PREF_ACC1_PASS, value).apply()

    var acc2User: String
        get() = prefs.getString(PREF_ACC2_USER, DEFAULT_ACC2_USER) ?: DEFAULT_ACC2_USER
        set(value) = prefs.edit().putString(PREF_ACC2_USER, value).apply()

    var acc2Pass: String
        get() = prefs.getString(PREF_ACC2_PASS, DEFAULT_ACC2_PASS) ?: DEFAULT_ACC2_PASS
        set(value) = prefs.edit().putString(PREF_ACC2_PASS, value).apply()
}
