package com.kuikly.stock.ai.config

internal expect object SecureSecretStore {
    fun get(profileId: String): String?
    fun put(profileId: String, secret: String)
    fun remove(profileId: String)
    fun isSecureStorageAvailable(): Boolean
}
