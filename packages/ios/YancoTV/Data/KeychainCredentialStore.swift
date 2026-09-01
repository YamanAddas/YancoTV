import CryptoKit
import Foundation
import Security
import Shared

/// iOS implementation of the shared `CredentialStore` interface.
///
/// Provider secrets (Xtream passwords, Stalker MAC addresses) are encrypted
/// before they reach the `sources` BLOB columns — plaintext never touches
/// the database, matching the Android Keystore implementation and hard rule
/// 3 in AGENTS.md.
///
/// ### Shape
///
/// A single 256-bit master key lives in the Keychain; the secrets themselves
/// are sealed with AES-GCM under that key and the sealed box is what gets
/// stored. This mirrors Android, where the Keystore holds a key that never
/// leaves the TEE and the ciphertext lives in SQLite.
///
/// `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` is the deliberate
/// accessibility class: *AfterFirstUnlock* so a background refresh can still
/// decrypt on a locked device, and *ThisDeviceOnly* so the key is excluded
/// from iCloud Keychain and encrypted backups. A key that rode along in a
/// backup would defeat the point of not storing the passwords in the clear.
///
/// ### Why Swift and not `iosMain`
///
/// The interface's own contract says the ciphertext format is opaque to the
/// caller — unlike `BackupCipher`, this never has to agree byte-for-byte
/// with another platform, because the key is device-bound and the data never
/// travels. That removes the only reason to hand-roll it in Kotlin, where
/// the Keychain means CFDictionary construction with manual CFRelease.
/// CryptoKit is also reachable here and is not reachable from Kotlin/Native
/// at all (it is Swift-only — the same constraint that shaped
/// `AesGcm.ios.kt`).
final class KeychainCredentialStore: NSObject, CredentialStore {

    private let service: String
    private let account: String
    /// Serialises key material access. `SecItem*` is thread-safe, but the
    /// read-then-create-on-miss below is not atomic on its own.
    private let lock = NSLock()
    private var cachedKey: SymmetricKey?

    init(service: String = "com.yancotv.ios.credentials", account: String = "master-key") {
        self.service = service
        self.account = account
        super.init()
    }

    // MARK: - CredentialStore

    func encrypt(plaintext: String) -> KotlinByteArray {
        do {
            let sealed = try AES.GCM.seal(Data(plaintext.utf8), using: masterKey())
            // `combined` is nonce || ciphertext || tag — one blob, which is
            // exactly what the BLOB column wants.
            guard let combined = sealed.combined else {
                throw CredentialStoreError.sealFailed
            }
            return combined.kotlinByteArray
        } catch {
            // Failing closed would mean silently storing nothing while the
            // UI reports success, so this is loud in debug and stores an
            // empty blob in release — which surfaces later as an auth
            // failure the user can act on, rather than as a corrupt secret.
            assertionFailure("Credential encryption failed: \(error)")
            return Data().kotlinByteArray
        }
    }

    func decrypt(ciphertext: KotlinByteArray) -> String {
        do {
            let box = try AES.GCM.SealedBox(combined: ciphertext.data)
            return String(decoding: try AES.GCM.open(box, using: masterKey()), as: UTF8.self)
        } catch {
            // Reachable in normal use: reinstalling the app discards the
            // Keychain item on some restore paths, leaving blobs that can no
            // longer be opened. The interface cannot throw (it is an
            // Obj-C-exported Kotlin method), so this returns empty and lets
            // the provider reject the request — which lands the user on
            // "re-enter your credentials", the correct remedy.
            #if DEBUG
            print("[Keychain] credential decrypt failed: \(error)")
            #endif
            return ""
        }
    }

    // MARK: - Key management

    private func masterKey() throws -> SymmetricKey {
        lock.lock()
        defer { lock.unlock() }

        if let cachedKey { return cachedKey }
        let key = try loadKey() ?? createKey()
        cachedKey = key
        return key
    }

    private func loadKey() throws -> SymmetricKey? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            guard let data = item as? Data else { throw CredentialStoreError.keychain(status) }
            return SymmetricKey(data: data)
        case errSecItemNotFound:
            return nil
        default:
            throw CredentialStoreError.keychain(status)
        }
    }

    private func createKey() throws -> SymmetricKey {
        let key = SymmetricKey(size: .bits256)
        let data = key.withUnsafeBytes { Data($0) }

        var query = baseQuery()
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else {
            // A duplicate means another thread or a previous launch won the
            // race; read theirs rather than overwriting, or every existing
            // stored credential becomes undecryptable.
            if status == errSecDuplicateItem, let existing = try loadKey() { return existing }
            throw CredentialStoreError.keychain(status)
        }
        return key
    }

    private func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}

enum CredentialStoreError: Error {
    case keychain(OSStatus)
    case sealFailed
}

// MARK: - Bridge helpers

extension Data {
    /// Kotlin `ByteArray` has no zero-copy Swift representation, so this
    /// copies element by element. Fine here — credentials are tens of bytes,
    /// not payloads.
    var kotlinByteArray: KotlinByteArray {
        let array = KotlinByteArray(size: Int32(count))
        for (index, byte) in enumerated() {
            array.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return array
    }
}

extension KotlinByteArray {
    var data: Data {
        var bytes = Data()
        bytes.reserveCapacity(Int(size))
        for index in 0..<size {
            bytes.append(UInt8(bitPattern: get(index: index)))
        }
        return bytes
    }
}
