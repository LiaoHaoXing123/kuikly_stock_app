package com.kuikly.stock.ai.config

internal actual object SecureSecretStore {
    actual fun get(profileId: String): String? = null

    actual fun put(profileId: String, secret: String) {
        throw IllegalStateException("当前 iOS 构建尚未启用安全密钥存储")
    }

    actual fun remove(profileId: String) = Unit

    actual fun isSecureStorageAvailable(): Boolean = false
}
