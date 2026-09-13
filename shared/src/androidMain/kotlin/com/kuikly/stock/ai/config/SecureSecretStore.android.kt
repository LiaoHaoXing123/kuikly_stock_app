package com.kuikly.stock.ai.config

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.kuikly.stock.data.stockDbContext
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal actual object SecureSecretStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "kuikly_stock_ai_profile_key_v1"
    private const val PREFS = "secure_ai_profiles_v1"

    actual fun get(profileId: String): String? {
        if (!isSecureStorageAvailable()) return null
        val prefs = context().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storageKey = storageKey(profileId)
        val envelope = prefs.getString(storageKey, null) ?: return null
        return try {
            decrypt(envelope)
        } catch (_: Throwable) {
            // 密钥失效时移除不可解密记录，等待重新配置。
            prefs.edit().remove(storageKey).apply()
            null
        }
    }

    actual fun put(profileId: String, secret: String) {
        require(profileId.isNotBlank()) { "API 档案 ID 不能为空" }
        if (secret.isEmpty()) {
            remove(profileId)
            return
        }
        check(isSecureStorageAvailable()) { "当前 Android 版本不支持安全密钥存储" }
        context().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(storageKey(profileId), encrypt(secret))
            .apply()
    }

    actual fun remove(profileId: String) {
        if (profileId.isBlank()) return
        context().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(storageKey(profileId))
            .apply()
    }

    actual fun isSecureStorageAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    private fun context(): Context = checkNotNull(stockDbContext()) {
        "应用尚未完成安全存储初始化"
    }

    private fun storageKey(profileId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(profileId.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(envelope: String): String {
        val parts = envelope.split(':', limit = 2)
        require(parts.size == 2) { "加密密钥格式无效" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
