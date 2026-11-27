import Foundation
import Security

/// Keys never leave the device's Keychain. Pending material is saved before SET_PWD,
/// so a connection loss after the vehicle writes it cannot discard the only copy.
struct NinebotVehicleKeyStore {
    struct Record: Codable {
        var confirmedKey: Data?
        var pendingKey: Data?
        var pendingWriteAcknowledged = false

        var authenticationCandidates: [Data] {
            var result: [Data] = []
            if let pendingKey { result.append(pendingKey) }
            if let confirmedKey, !result.contains(confirmedKey) { result.append(confirmedKey) }
            return result
        }
    }

    enum Failure: LocalizedError {
        case system(OSStatus)
        case invalidRecord
        var errorDescription: String? {
            switch self {
            case .system(let code): return CWL("钥匙串无法访问（{1}）；请先解锁 iPhone。", [String(code)])
            case .invalidRecord: return CWL("保存的蓝牙密钥记录无效；原记录未修改。")
            }
        }
    }

    private let service = "local.tds.carkit.ninebot.ble-v2"

    /// Delete the exact vehicle record, including confirmed and pending keys.
    /// This deliberately does not load/decode it first: corrupt records must be removable.
    func remove(for id: UUID) throws {
        let status = SecItemDelete(baseQuery(id) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw Failure.system(status) }
    }

    func load(for id: UUID) throws -> Record {
        var query = baseQuery(id)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return Record() }
        guard status == errSecSuccess else { throw Failure.system(status) }
        guard let data = result as? Data,
              let record = try? JSONDecoder().decode(Record.self, from: data),
              [record.confirmedKey, record.pendingKey].allSatisfy({ $0 == nil || $0!.count == 32 || $0!.count == 16 })
        else { throw Failure.invalidRecord }
        return record
    }

    /// Reuse a pending key on an explicitly requested retry instead of overwriting it,
    /// unless a fresh re-pair explicitly requests a new candidate.
    func prepareCandidate(for id: UUID, forceNew: Bool = false) throws -> Data {
        var record = try load(for: id)
        if !forceNew, let pending = record.pendingKey { return pending }
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        guard status == errSecSuccess else { throw Failure.system(status) }
        let candidate = Data(bytes)
        record.pendingKey = candidate
        record.pendingWriteAcknowledged = false
        try save(record, for: id)
        return candidate
    }

    func acknowledgeWrite(_ key: Data, for id: UUID) throws {
        var record = try load(for: id)
        guard record.pendingKey == key else { throw Failure.invalidRecord }
        record.pendingWriteAcknowledged = true
        try save(record, for: id)
    }

    /// Only an authenticated 5D success promotes a key. A failed attempt retains
    /// both the original confirmed key and the write-ahead candidate.
    func confirmAuthentication(_ key: Data, for id: UUID) throws {
        var record = try load(for: id)
        guard record.pendingKey == key || record.confirmedKey == key else { throw Failure.invalidRecord }
        record.confirmedKey = key
        if record.pendingKey == key {
            record.pendingKey = nil
            record.pendingWriteAcknowledged = false
        }
        try save(record, for: id)
    }

    private func baseQuery(_ id: UUID) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: id.uuidString]
    }

    private func save(_ record: Record, for id: UUID) throws {
        let data = try JSONEncoder().encode(record)
        let query = baseQuery(id)
        let update: [String: Any] = [kSecValueData as String: data,
                                    kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemUpdate(query as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            let insertion = query.merging(update) { _, new in new }
            let addStatus = SecItemAdd(insertion as CFDictionary, nil)
            guard addStatus == errSecSuccess else { throw Failure.system(addStatus) }
        } else if status != errSecSuccess {
            throw Failure.system(status)
        }
    }
}
