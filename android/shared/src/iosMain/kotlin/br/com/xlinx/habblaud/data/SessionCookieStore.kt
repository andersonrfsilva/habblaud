package br.com.xlinx.habblaud.data

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*
internal actual class SessionLock actual constructor() {
    private val delegate = NSRecursiveLock()
    actual fun lock() = delegate.lock()
    actual fun unlock() = delegate.unlock()
}

@OptIn(ExperimentalForeignApi::class)
internal actual class SecureSessionStorage actual constructor() {
    private fun <T> query(block: (CFMutableDictionaryRef) -> T): T = memScoped {
        val dictionary = CFDictionaryCreateMutable(null, 0, null, null) ?: error("Could not create Keychain query")
        val service = CFBridgingRetain("br.com.xlinx.habblaud.remote-session")
        val account = CFBridgingRetain("habblaud_remote")
        try {
            CFDictionarySetValue(dictionary, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(dictionary, kSecAttrService, service)
            CFDictionarySetValue(dictionary, kSecAttrAccount, account)
            CFDictionarySetValue(dictionary, kSecAttrSynchronizable, kCFBooleanFalse)
            block(dictionary)
        } finally {
            CFRelease(dictionary)
            if (service != null) CFRelease(service)
            if (account != null) CFRelease(account)
        }
    }
    actual fun read(): String? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = query {
            CFDictionarySetValue(it, kSecReturnData, kCFBooleanTrue)
            CFDictionarySetValue(it, kSecMatchLimit, kSecMatchLimitOne)
            SecItemCopyMatching(it, result.ptr)
        }
        if (status == errSecItemNotFound) return@memScoped null
        check(status == errSecSuccess) { "Keychain read failed: $status" }
        val data = CFBridgingRelease(result.value) as NSData
        NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString() ?: error("Invalid Keychain session")
    }
    actual fun write(value: String) {
        val bytes = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: error("Invalid session encoding")
        val retained = CFBridgingRetain(bytes) ?: error("Could not bridge session")
        val attributes = CFDictionaryCreateMutable(null, 0, null, null) ?: error("Could not create Keychain attributes")
        try {
            CFDictionarySetValue(attributes, kSecValueData, retained)
            CFDictionarySetValue(attributes, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
            var status = query { SecItemUpdate(it, attributes) }
            if (status == errSecItemNotFound) status = query {
                CFDictionarySetValue(it, kSecValueData, retained)
                CFDictionarySetValue(it, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
                SecItemAdd(it, null)
            }
            check(status == errSecSuccess) { "Keychain save failed: $status" }
        } finally { CFRelease(attributes); CFRelease(retained) }
    }
    actual fun clear() {
        val status = query { SecItemDelete(it) }
        check(status == errSecSuccess || status == errSecItemNotFound) { "Keychain delete failed: $status" }
    }
}
