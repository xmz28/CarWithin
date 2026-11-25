import Foundation

public enum VehicleBLEFrameError: Error, LocalizedError, Equatable {
    case malformed
    case payloadTooLarge

    public var errorDescription: String? {
        switch self {
        case .malformed: return CWL("蓝牙帧格式或长度不正确")
        case .payloadTooLarge: return CWL("蓝牙帧数据超过 242 字节")
        }
    }
}

public struct VehicleBLEFrame: Equatable, Sendable {
    public static let maximumPayloadLength = 242
    public let src: UInt8
    public let dst: UInt8
    public let command: UInt8
    public let index: UInt8
    public let payload: Data

    public init(src: UInt8, dst: UInt8, command: UInt8, index: UInt8, payload: Data = Data()) {
        self.src = src
        self.dst = dst
        self.command = command
        self.index = index
        self.payload = payload
    }

    public func encode() throws -> Data {
        guard payload.count <= Self.maximumPayloadLength else { throw VehicleBLEFrameError.payloadTooLarge }
        return Data([0x5a, 0xa5, UInt8(payload.count), src, dst, command, index]) + payload
    }

    public static func decode(_ data: Data) throws -> VehicleBLEFrame {
        let bytes = [UInt8](data)
        guard bytes.count >= 7, bytes[0] == 0x5a, bytes[1] == 0xa5,
              bytes[2] <= maximumPayloadLength, bytes.count == Int(bytes[2]) + 7 else {
            throw VehicleBLEFrameError.malformed
        }
        return VehicleBLEFrame(src: bytes[3], dst: bytes[4], command: bytes[5], index: bytes[6],
                        payload: Data(bytes.dropFirst(7)))
    }
}

/// Reassembles arbitrary GATT notification fragments. Call from the session's serial queue.
/// A partial frame never holds more than 255 bytes, even if a notification contains garbage.
public final class VehicleBLEFrameAssembler {
    private var buffer = [UInt8]()
    private let trailerLength: Int
    private let maximumBufferedBytes: Int
    public var bufferedByteCount: Int { buffer.count }

    public init(encrypted: Bool = true, maxBufferedBytes: Int = 1024) {
        trailerLength = encrypted ? 13 : 7
        maximumBufferedBytes = max(255, maxBufferedBytes)
    }

    public func reset() { buffer.removeAll(keepingCapacity: true) }

    public func append(_ data: Data) -> [Data] {
        var frames = [Data]()
        for byte in data {
            if buffer.count >= maximumBufferedBytes { buffer.removeFirst() }
            buffer.append(byte)
            while !buffer.isEmpty {
                guard buffer[0] == 0x5a else { buffer.removeFirst(); continue }
                guard buffer.count >= 2 else { break }
                guard buffer[1] == 0xa5 else { buffer.removeFirst(); continue }
                guard buffer.count >= 3 else { break }
                guard Int(buffer[2]) <= VehicleBLEFrame.maximumPayloadLength else {
                    buffer.removeFirst(); continue
                }
                let length = Int(buffer[2]) + trailerLength
                guard buffer.count >= length else { break }
                frames.append(Data(buffer.prefix(length)))
                buffer.removeFirst(length)
            }
        }
        return frames
    }
}
