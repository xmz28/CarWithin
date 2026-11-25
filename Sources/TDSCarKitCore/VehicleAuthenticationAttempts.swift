import Foundation

/// One explicit connection intent may try each saved key once. Keep rejection
/// state across fresh BLE handshakes; a manual retry starts a new instance.
public struct VehicleAuthenticationAttempts {
    private var rejected = Set<Data>()
    public init() {}

    public func remaining(from candidates: [Data]) -> [Data] {
        var seen = Set<Data>()
        return candidates.filter { !rejected.contains($0) && seen.insert($0).inserted }
    }

    public mutating func reject(_ key: Data) { rejected.insert(key) }
}
