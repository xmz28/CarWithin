import Foundation

/// Evidence for one peripheral during one scan round. CoreBluetooth can split a
/// name, manufacturer data, and connectability across multiple callbacks. Packet
/// absence or corruption cannot revoke earlier verified protocol evidence.
public struct DiscoveryEvidence {
    public private(set) var advertisement: Advertisement?
    public private(set) var localName = ""
    public private(set) var hasConnectableAdvertisement = false

    public var isEligible: Bool {
        advertisement?.canAttemptLocalAuthentication == true && !localName.isEmpty && hasConnectableAdvertisement
    }

    public init() {}

    /// Returns whether this round has verified manufacturer evidence. A verified
    /// unsupported protocol remains blocked for the rest of this round. Both v2
    /// and v3 may attempt the local handshake; successful auth remains mandatory.
    @discardableResult
    public mutating func merge(manufacturerData: Data?, localName: String?, isConnectable: Bool?) -> Bool {
        // A scan response can precede its manufacturer packet. These fields alone
        // are never eligibility, but must remain available for later verification.
        if let localName, !localName.isEmpty { self.localName = localName }
        if isConnectable == true { hasConnectableAdvertisement = true }
        if let manufacturerData, let verified = try? Advertisement.parse(manufacturerData: manufacturerData) {
            // Preserve the original app's behavior for a verified manufacturer
            // packet without this optional CoreBluetooth field. Missing/invalid
            // manufacturer packets cannot establish this fallback evidence.
            if isConnectable == nil { hasConnectableAdvertisement = true }
            if advertisement == nil || advertisement?.canAttemptLocalAuthentication == true || !verified.canAttemptLocalAuthentication {
                advertisement = verified
            }
        }
        return advertisement != nil
    }
}
