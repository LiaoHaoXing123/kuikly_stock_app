@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

// iOS 安全密钥存储：系统 Keychain（kSecClassGenericPassword），无需额外 entitlement。

package com.kuikly.stock.ai.config

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVarOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSData
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

private const val KEYCHAIN_SERVICE = "com.kuikly.stock.apikey"

internal actual object SecureSecretStore {

    actual fun isSecureStorageAvailable(): Boolean = true

    actual fun get(profileId: String): String? = try {
        memScoped {
            val query = buildQuery(profileId) ?: return null
            try {
                CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
                val result = alloc<CPointerVarOf<CPointer<out CPointed>>>()
                val status = SecItemCopyMatching(query, result.ptr)
                if (status != errSecSuccess) return null
                val nsData = CFBridgingRelease(result.value) as? NSData ?: return null
                val bytes = nsData.bytes?.reinterpret<ByteVar>()?.readBytes(nsData.length.toInt())
                bytes?.decodeToString()
            } finally {
                CFRelease(query)
            }
        }
    } catch (e: Throwable) {
        null
    }

    actual fun put(profileId: String, secret: String) {
        memScoped {
            val query = buildQuery(profileId) ?: error("Keychain 查询构造失败")
            try {
                SecItemDelete(query)
                val addQuery = buildQuery(profileId, secret) ?: error("Keychain 查询构造失败")
                try {
                    val status = SecItemAdd(addQuery, null)
                    if (status != errSecSuccess) error("Keychain 保存失败（$status）")
                } finally {
                    CFRelease(addQuery)
                }
            } finally {
                CFRelease(query)
            }
        }
    }

    actual fun remove(profileId: String) {
        try {
            memScoped {
                val query = buildQuery(profileId) ?: return
                try {
                    SecItemDelete(query)
                } finally {
                    CFRelease(query)
                }
            }
        } catch (e: Throwable) {
            // 删除失败可忽略
        }
    }

    private fun buildQuery(profileId: String, secret: String? = null): CFDictionaryRef? {
        val query = CFDictionaryCreateMutable(null, 5, null, null) ?: return null
        // const char * 参数直接传 Kotlin String（自动转 UTF-8 C 字符串）
        val service = CFStringCreateWithCString(null, KEYCHAIN_SERVICE, kCFStringEncodingUTF8)
        val account = CFStringCreateWithCString(null, profileId, kCFStringEncodingUTF8)
        CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(query, kSecAttrService, service)
        CFDictionaryAddValue(query, kSecAttrAccount, account)
        if (secret != null) {
            val cstr = secret.cstr
            // cstr 末尾含 NUL，存入 Keychain 的长度需减 1，否则读出的 Key 会多一个 '\0'
            val data = CFDataCreate(null, cstr, (cstr.size - 1).toLong())
            CFDictionaryAddValue(query, kSecValueData, data)
            if (data != null) CFRelease(data)
        }
        if (service != null) CFRelease(service)
        if (account != null) CFRelease(account)
        return query
    }
}
