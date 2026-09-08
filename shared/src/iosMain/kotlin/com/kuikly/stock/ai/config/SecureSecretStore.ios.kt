// iOS 安全密钥存储：系统 Keychain（kSecClassGenericPassword），无需额外 entitlement。

package com.kuikly.stock.ai.config

import platform.CoreFoundation.CFDataCreate
import kotlinx.cinterop.CFTypeRefVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.__CFData
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
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
                val result = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(query, result.ptr)
                if (status != errSecSuccess) return null
                val dataRef: CFDataRef? = result.value?.reinterpret<__CFData>()
                val bytes = if (dataRef == null) {
                    null
                } else {
                    CFDataGetBytePtr(dataRef)?.readBytes(CFDataGetLength(dataRef).toInt())
                }
                CFRelease(result.value)
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
        val service = CFStringCreateWithCString(null, KEYCHAIN_SERVICE.cstr, kCFStringEncodingUTF8)
        val account = CFStringCreateWithCString(null, profileId.cstr, kCFStringEncodingUTF8)
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
