package com.kuikly.stock.ai.config

internal actual object SecureSecretStore {
    actual fun get(profileId: String): String? = null

    actual fun put(profileId: String, secret: String) {
        throw IllegalStateException("Web 版本不保存 API Key，请使用 Android 版本")
    }

    actual fun remove(profileId: String) = Unit

    actual fun isSecureStorageAvailable(): Boolean = false
}
