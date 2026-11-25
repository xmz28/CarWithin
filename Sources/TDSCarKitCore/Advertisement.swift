import Foundation

public enum AdvertisementError: Error, LocalizedError, Equatable {
    case unsupportedManufacturer(UInt16)
    case malformed
    case checksumMismatch

    public var errorDescription: String? {
        switch self {
        case .unsupportedManufacturer: return CWL("不是支持的九号蓝牙广播")
        case .malformed: return CWL("九号蓝牙广播格式不完整")
        case .checksumMismatch: return CWL("九号蓝牙广播校验失败")
        }
    }
}

/// Input is CoreBluetooth's CBAdvertisementDataManufacturerDataKey value, INCLUDING the
/// two manufacturer bytes. IDs here match their raw on-air order, not Bluetooth LE integers.
public struct Advertisement: Equatable, Sendable {
    public struct TLV: Equatable, Sendable {
        public let type: UInt8
        public let value: Data
    }

    public let manufacturerID: UInt16
    public let protocolVersion: UInt8
    public let model: UInt16?
    /// The official parser's `sn` candidate: its LAST type-1/type-2/type-3 value.
    /// This preserves authentication behavior, without asserting every value is a semantic SN.
    public let serialNumber: Data?
    public let rawTLVs: [TLV]
    public let isPowerConnected: Bool
    /// Follows the official parser's candidate length. Missing values use the session default;
    /// the session must still validate PRE_COMM payload bounds before extracting identity bytes.
    public var serialNumberLength: Int? { serialNumber?.count }
    public var supportsV2: Bool { protocolVersion == 2 }
    /// The official legacy path uses BleEncryption2Protocol and Encryption2Processor
    /// for both advertised versions. Eligibility permits a handshake, not an auth bypass.
    public var canAttemptLocalAuthentication: Bool { Self.canAttemptLocalAuthentication(version: protocolVersion) }
    public static func canAttemptLocalAuthentication(version: UInt8) -> Bool { version == 2 || version == 3 }

    public static func parse(manufacturerData: Data) throws -> Advertisement {
        let raw = [UInt8](manufacturerData)
        guard raw.count >= 4 else { throw AdvertisementError.malformed }
        let manufacturer = UInt16(raw[0]) << 8 | UInt16(raw[1])
        guard [0x4e42, 0x4e43, 0x1f0f].contains(manufacturer) else {
            throw AdvertisementError.unsupportedManufacturer(manufacturer)
        }
        let payload = Array(raw[2..<(raw.count - 1)])
        let checksum = ~payload.reduce(UInt8(0)) { $0 &+ $1 }
        guard checksum == raw.last else { throw AdvertisementError.checksumMismatch }
        var version: UInt8
        var model: UInt16?
        var serial: Data?
        var tlvs = [TLV]()
        var power = false
        switch manufacturer {
        case 0x4e42:
            guard payload.count >= 2 else { throw AdvertisementError.malformed }
            version = payload[1]
            let kind = payload[0]
            if (1...15).contains(kind) {
                guard payload.count >= 4 else { throw AdvertisementError.malformed }
                model = UInt16(kind) << 8 | UInt16(payload[3])
            } else {
                model = UInt16(kind)
            }
            if kind == 51, payload.count >= 3 { power = payload[2] & 4 != 0 }
        case 0x4e43:
            guard payload.count >= 3 else { throw AdvertisementError.malformed }
            model = UInt16(payload[0]) << 8 | UInt16(payload[1])
            version = payload[2]
        default:
            guard !payload.isEmpty else { throw AdvertisementError.malformed }
            version = payload[0] & 0x0f
            var position = 1
            while position < payload.count {
                let marker = payload[position]
                position += 1
                let type = marker >> 5
                let length = Int(marker & 31)
                guard position + length <= payload.count else { throw AdvertisementError.malformed }
                let value = Array(payload[position..<(position + length)])
                position += length
                tlvs.append(TLV(type: type, value: Data(value)))
                switch type {
                case 0:
                    guard length == 2 else { throw AdvertisementError.malformed }
                    model = UInt16(value[0]) << 8 | UInt16(value[1])
                case 1, 2, 3:
                    guard length > 0 else { throw AdvertisementError.malformed }
                    // Source constants name these SN/PN/VIN, but the actual parser stores
                    // all three in `sn`; preserve last-value behavior for PRE_COMM length.
                    serial = Data(value)
                default: break
                }
            }
        }
        return Advertisement(manufacturerID: manufacturer, protocolVersion: version,
                             model: model, serialNumber: serial, rawTLVs: tlvs,
                             isPowerConnected: power)
    }
}
