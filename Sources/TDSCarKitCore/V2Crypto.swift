import Foundation
import CommonCrypto

public enum V2CryptoError: Error, LocalizedError, Equatable {
    case invalidAuthParameter
    case authenticationFailed
    case staleSerial
    case serialExhausted
    case cipherFailure(Int32)

    public var errorDescription: String? {
        switch self {
        case .invalidAuthParameter: return CWL("蓝牙认证参数必须为 16 字节")
        case .authenticationFailed: return CWL("蓝牙消息认证失败")
        case .staleSerial: return CWL("蓝牙消息序号过期或重复")
        case .serialExhausted: return CWL("蓝牙消息序号已用尽，请重新连接")
        case .cipherFailure(let code): return CWL("蓝牙加密失败（{1}）", [String(code)])
        }
    }
}

/// The APK's legacy BleEncryption2Protocol primitive (also inherited by its v3 wrapper).
/// This does not implement the separate SDK/account-auth path. No keys leave this instance.
/// Mutable session state: all calls must run on the same serial queue.
public final class V2Crypto {
    private static let constant: [UInt8] = [0x97, 0xcf, 0xb8, 0x02, 0x84, 0x41, 0x43, 0xde,
                                            0x56, 0x00, 0x2b, 0x3b, 0x34, 0x78, 0x0a, 0x5d]
    private var key = V2Crypto.deriveKey(nil, nil)
    private var auth = [UInt8](repeating: 0, count: 16)
    private var txCounter: UInt32 = 0
    private var rxCounter: UInt32 = 0

    public init() {}

    // Internal initial state for independent native-vector and rollover tests.
    init(txCounter: UInt32, rxCounter: UInt32) {
        self.txCounter = txCounter
        self.rxCounter = rxCounter
    }

    public func resetSerial() {
        txCounter = 0
        rxCounter = 0
        auth = [UInt8](repeating: 0, count: 16)
        key = Self.deriveKey(nil, nil)
    }

    public func startSerial() {
        txCounter = 1
        rxCounter = 1
    }

    public func setAuthParam(_ data: Data) throws {
        guard data.count == 16 else { throw V2CryptoError.invalidAuthParameter }
        auth = [UInt8](data)
    }

    /// Pads/truncates each input to its first 16 bytes. Session code must truncate a BLE name
    /// to its LAST 16 UTF-8 bytes before calling this, matching the official name handling.
    public func setKey(_ first: Data?, _ second: Data?) throws {
        key = Self.deriveKey(first, second)
    }

    public func encrypt(_ plain: Data) throws -> Data {
        _ = try VehicleBLEFrame.decode(plain)
        let bytes = [UInt8](plain)
        let header = Array(bytes.prefix(3))
        let body = Array(bytes.dropFirst(3))
        let previous = max(txCounter, rxCounter)
        if previous == 0 {
            let mask = try aes(Self.constant)
            let encrypted = body.enumerated().map { $0.element ^ mask[$0.offset % 16] }
            let checksum = Self.checksum(body)
            return Data(header + encrypted + [0, 0, UInt8(truncatingIfNeeded: checksum),
                                               UInt8(checksum >> 8), 0, 0])
        }
        guard previous < UInt32.max else { throw V2CryptoError.serialExhausted }
        let serial = previous + 1
        let nonce = makeNonce(serial)
        let encrypted = try crypt(body, nonce: nonce)
        let tag = try makeTag(header: header, body: body, nonce: nonce)
        let output = Data(header + encrypted + tag + [UInt8(truncatingIfNeeded: serial >> 8),
                                                       UInt8(truncatingIfNeeded: serial)])
        txCounter = serial
        return output
    }

    public func decrypt(_ wire: Data) throws -> Data {
        let bytes = [UInt8](wire)
        guard bytes.count >= 13, bytes[0] == 0x5a, bytes[1] == 0xa5,
              Int(bytes[2]) <= VehicleBLEFrame.maximumPayloadLength, bytes.count == Int(bytes[2]) + 13 else {
            throw VehicleBLEFrameError.malformed
        }
        let bodyEnd = bytes.count - 6
        let header = Array(bytes.prefix(3))
        let encrypted = Array(bytes[3..<bodyEnd])
        let lowSerial = UInt32(bytes[bytes.count - 2]) << 8 | UInt32(bytes[bytes.count - 1])
        var highSerial = rxCounter & 0xffff0000
        if lowSerial & 0x8000 == 0, rxCounter & 0x8000 != 0 {
            guard highSerial < 0xffff0000 else { throw V2CryptoError.serialExhausted }
            highSerial += 0x10000
        }
        let serial = highSerial | lowSerial
        if serial == 0 {
            // Reject a downgrade/replayed PRE_COMM once authenticated serial mode has begun.
            guard rxCounter == 0, txCounter == 0 else { throw V2CryptoError.staleSerial }
            let mask = try aes(Self.constant)
            let body = encrypted.enumerated().map { $0.element ^ mask[$0.offset % 16] }
            let checksum = UInt16(bytes[bodyEnd + 2]) | UInt16(bytes[bodyEnd + 3]) << 8
            guard Self.checksum(body) == checksum else { throw V2CryptoError.authenticationFailed }
            return Data(header + body)
        }
        guard serial > rxCounter else { throw V2CryptoError.staleSerial }
        let nonce = makeNonce(serial)
        let body = try crypt(encrypted, nonce: nonce)
        let expected = try makeTag(header: header, body: body, nonce: nonce)
        var difference: UInt8 = 0
        for offset in 0..<4 { difference |= expected[offset] ^ bytes[bodyEnd + offset] }
        guard difference == 0 else { throw V2CryptoError.authenticationFailed }
        rxCounter = serial
        return Data(header + body)
    }

    private static func deriveKey(_ first: Data?, _ second: Data?) -> [UInt8] {
        func padded(_ value: Data?) -> [UInt8] {
            guard let value else { return constant }
            return Array(value.prefix(16)) + [UInt8](repeating: 0, count: max(0, 16 - value.count))
        }
        let input = padded(first) + padded(second)
        var digest = [UInt8](repeating: 0, count: Int(CC_SHA1_DIGEST_LENGTH))
        _ = input.withUnsafeBytes { CC_SHA1($0.baseAddress, CC_LONG(input.count), &digest) }
        return Array(digest.prefix(16))
    }

    private static func checksum(_ bytes: [UInt8]) -> UInt16 {
        ~bytes.reduce(UInt16(0)) { $0 &+ UInt16($1) }
    }

    private func makeNonce(_ serial: UInt32) -> [UInt8] {
        [UInt8(serial >> 24), UInt8(truncatingIfNeeded: serial >> 16),
         UInt8(truncatingIfNeeded: serial >> 8), UInt8(truncatingIfNeeded: serial)] + auth.prefix(8)
    }

    private func crypt(_ input: [UInt8], nonce: [UInt8]) throws -> [UInt8] {
        var output = input
        for start in stride(from: 0, to: input.count, by: 16) {
            let block = UInt8(start / 16 + 1)
            let mask = try aes([1] + nonce + [0, 0, block])
            for offset in 0..<min(16, input.count - start) {
                output[start + offset] ^= mask[offset]
            }
        }
        return output
    }

    private func makeTag(header: [UInt8], body: [UInt8], nonce: [UInt8]) throws -> [UInt8] {
        var tag = try aes([0x59] + nonce + [0, 0, UInt8(body.count)])
        let additionalData = header + [UInt8](repeating: 0, count: 13)
        tag = try aes(zip(tag, additionalData).map { $0 ^ $1 })
        for start in stride(from: 0, to: body.count, by: 16) {
            var block = [UInt8](repeating: 0, count: 16)
            for offset in 0..<min(16, body.count - start) { block[offset] = body[start + offset] }
            tag = try aes(zip(tag, block).map { $0 ^ $1 })
        }
        let mask = try aes([1] + nonce + [0, 0, 0])
        return (0..<4).map { tag[$0] ^ mask[$0] }
    }

    private func aes(_ input: [UInt8]) throws -> [UInt8] {
        var output = [UInt8](repeating: 0, count: 16)
        var written = 0
        let status = key.withUnsafeBytes { keyBytes in
            input.withUnsafeBytes { inputBytes in
                output.withUnsafeMutableBytes { outputBytes in
                    CCCrypt(CCOperation(kCCEncrypt), CCAlgorithm(kCCAlgorithmAES),
                            CCOptions(kCCOptionECBMode), keyBytes.baseAddress, 16, nil,
                            inputBytes.baseAddress, input.count, outputBytes.baseAddress,
                            outputBytes.count, &written)
                }
            }
        }
        guard status == kCCSuccess, written == 16 else { throw V2CryptoError.cipherFailure(status) }
        return output
    }
}
