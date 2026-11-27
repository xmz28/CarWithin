import CoreImage
import Darwin
import Foundation
import ImageIO
import VideoToolbox

// The iOS configuration controller's error is only a localized message wrapper.
typealias ConfigurationError = Failure

struct Failure: LocalizedError, CustomStringConvertible {
    var errorDescription: String? { description }
    let description: String
    init(_ description: String) { self.description = description }
}

private func require(_ condition: Bool, _ message: String) throws {
    if !condition { throw Failure(message) }
}

/// The simulated instrument listens on loopback and replies to
/// the actual source port selected by the OS. No instrument network is needed.
private final class InstrumentPort {
    let descriptor: Int32
    let port: UInt16
    private var sender = sockaddr_in()

    init(port: UInt16 = 0) throws {
        let fd = socket(AF_INET, SOCK_DGRAM, 0)
        guard fd >= 0 else { throw Failure("socket: \(errno)") }
        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_port = port.bigEndian
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        var length = socklen_t(address.sin_len)
        let result = withUnsafeMutablePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { pointer in
                guard bind(fd, pointer, length) == 0 else { return Int32(-1) }
                return getsockname(fd, pointer, &length)
            }
        }
        guard result == 0 else {
            let code = errno
            Darwin.close(fd)
            throw Failure("bind or getsockname: \(code)")
        }
        descriptor = fd
        self.port = UInt16(bigEndian: address.sin_port)
    }

    deinit { Darwin.close(descriptor) }

    func hasDatagram(timeoutMilliseconds: Int32) throws -> Bool {
        var event = pollfd(fd: descriptor, events: Int16(POLLIN), revents: 0)
        let result = poll(&event, 1, timeoutMilliseconds)
        try require(result >= 0, "poll failed")
        return result == 1 && event.revents & Int16(POLLIN) != 0
    }

    func receive(timeoutMilliseconds: Int32 = 1_000) throws -> Data {
        let ready = try hasDatagram(timeoutMilliseconds: timeoutMilliseconds)
        try require(ready, "Timed out waiting for a datagram")
        var data = [UInt8](repeating: 0, count: 2_048)
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let count = withUnsafeMutablePointer(to: &sender) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                recvfrom(descriptor, &data, data.count, 0, $0, &length)
            }
        }
        try require(count > 0, "recvfrom: \(errno)")
        return Data(data.prefix(count))
    }

    func reply(_ data: Data) throws {
        let sent = withUnsafePointer(to: &sender) { address in
            address.withMemoryRebound(to: sockaddr.self, capacity: 1) { pointer in
                data.withUnsafeBytes { sendto(descriptor, $0.baseAddress, $0.count, 0, pointer, socklen_t(address.pointee.sin_len)) }
            }
        }
        try require(sent == data.count, "sendto: \(errno)")
    }
}

@main
struct Ninebot43Smoke {
    private static func verifyColorBars(in jpeg: Data) throws {
        guard let source = CGImageSourceCreateWithData(jpeg as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { throw Failure("Color-bar JPEG decode failed") }
        try require(image.width == 480 && image.height == 272, "Incorrect color-bar size")
        let rowBytes = image.width * 4
        let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
        var bytes = [UInt8](repeating: 0, count: rowBytes * image.height)
        try bytes.withUnsafeMutableBytes { pixels in
            guard let context = CGContext(data: pixels.baseAddress, width: image.width, height: image.height,
                  bitsPerComponent: 8, bytesPerRow: rowBytes, space: colorSpace,
                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else {
                throw Failure("Failed to create a bitmap for color verification")
            }
            context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
            let expected = [[255,255,255], [255,255,0], [0,255,255], [0,255,0], [255,0,255], [255,0,0], [0,0,255], [0,0,0]]
            for (bar, rgb) in expected.enumerated() {
                let x = (2 * bar + 1) * image.width / (expected.count * 2)
                for y in [image.height / 4, image.height / 2, 3 * image.height / 4] {
                    let offset = y * rowBytes + x * 4
                    for channel in 0..<3 {
                        try require(abs(Int(pixels[offset + channel]) - rgb[channel]) <= 20,
                                    "Unexpected color in bar \(bar + 1) at y=\(y)")
                    }
                }
            }
        }
    }

    private static func receiveJPEG(from instrument: InstrumentPort) throws -> Data {
        var jpeg = Data()
        while jpeg.count < 1_048_576 {
            let packet = try instrument.receive(timeoutMilliseconds: 5_000)
            try require(packet.count > 20 && packet.count <= 1420, "Unexpected frame packet length")
            try require(packet[0] == 0x90 && (packet[1] & 0x7f) == 0x20, "Unexpected RTP type")
            jpeg.append(packet.dropFirst(20))
            if packet[1] & 0x80 != 0 { return jpeg }
        }
        throw Failure("RTP frame did not end")
    }

    private static func acknowledgement(for heartbeat: Data) -> Data {
        var response = Data([0x85,0xcc,0,4, 0,0,0,1, 0,0,0,0])
        response.append(heartbeat[12..<16])
        response.append(contentsOf: [2,0,0,0])
        return response
    }

    private static func testCarPlayVideo(localAddress: String) throws {
        let width = 960, height = 544
        var pixels: CVPixelBuffer?
        let attributes = [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary
        let result = CVPixelBufferCreate(nil, width, height, kCVPixelFormatType_32BGRA, attributes, &pixels)
        guard result == kCVReturnSuccess, let pixels else {
            throw Failure("Could not create test video input")
        }
        let palette: [CIColor] = [.white, .yellow, .cyan, .green, .magenta, .red, .blue, .black]
        let stripeWidth = width / palette.count
        var image = CIImage.empty()
        for (index, color) in palette.enumerated() {
            let rect = CGRect(x: index * stripeWidth, y: 0, width: stripeWidth, height: height)
            image = CIImage(color: color).cropped(to: rect).composited(over: image)
        }
        CIContext().render(image, to: pixels, bounds: image.extent, colorSpace: CGColorSpaceCreateDeviceRGB())
        let lock = NSLock()
        var encoded: Result<H264EncodedFrame, Error>?
        let encoder = try H264Encoder(width: width, height: height, fps: 10, bitrate: 1_500_000) { frame in
            lock.withLock { encoded = frame }
        }
        defer { encoder.invalidate() }
        try encoder.encode(pixels, presentationTime: .zero, forceKeyframe: true)
        encoder.complete()
        guard let frame = try lock.withLock({ encoded })?.get(), frame.isKeyframe else {
            throw Failure("Missing encoded test keyframe")
        }
        let video = try InstrumentPort(port: NinebotH264Packetizer.videoPort)
        let control = try InstrumentPort(port: NinebotH264Packetizer.controlPort)
        let output = NinebotOutput()
        output.start(localAddress: localAddress, targetAddress: "127.0.0.1", mode: .fourThreeInch,
                     onState: { _ in }, onLog: { _ in }, requestKeyframe: {})
        defer { output.stop() }
        output.append(frame.data, codec: .h264, keyframe: true)
        let heartbeat = try control.receive(timeoutMilliseconds: 5_000)
        try require(heartbeat == NinebotJPEGPacketizer.heartbeat(counter: 0), "Unexpected starting heartbeat")
        let unseen = NinebotJPEGPacketizer.heartbeat(counter: UInt32.max)
        try control.reply(acknowledgement(for: unseen))
        let sentPrematurely = try video.hasDatagram(timeoutMilliseconds: 600)
        try require(!sentPrematurely, "Video sent before a matching heartbeat acknowledgement")

        try control.reply(acknowledgement(for: heartbeat))
        try verifyColorBars(in: receiveJPEG(from: video))
        for _ in 0..<5 {
            let probe = try control.receive(timeoutMilliseconds: 2_000)
            try control.reply(acknowledgement(for: probe))
        }
        while try video.hasDatagram(timeoutMilliseconds: 0) { _ = try video.receive() }
        try verifyColorBars(in: receiveJPEG(from: video))

        let finalProbe = try control.receive(timeoutMilliseconds: 2_000)
        let duplicate = acknowledgement(for: finalProbe)
        try control.reply(duplicate)
        for _ in 0..<3 {
            _ = try control.receive(timeoutMilliseconds: 2_000)
            try control.reply(duplicate)
        }
        // A repeated acknowledgement must not extend the four-second deadline.
        let deadline = ProcessInfo.processInfo.systemUptime + 2
        let lostContact = CWL("仪表已停止回应，4.3 寸投屏已结束。")
        while NinebotOutput.lastProjectionReport?.contains(lostContact) != true,
              ProcessInfo.processInfo.systemUptime < deadline { usleep(10_000) }
        try require(NinebotOutput.lastProjectionReport?.contains(lostContact) == true, "Missing timeout report")
        while try video.hasDatagram(timeoutMilliseconds: 0) { _ = try video.receive() }
        let continuedSending = try video.hasDatagram(timeoutMilliseconds: 750)
        try require(!continuedSending, "Still sending video after the timeout")
        print("PASS: the production 4.3-inch sender waits for a reply, produces 480×272 JPEGs and closes on loss of contact")
    }

    static func main() throws {
        for code in [EAGAIN, EWOULDBLOCK, ENOBUFS, EINTR, ECONNREFUSED, EHOSTUNREACH, EHOSTDOWN, ENETUNREACH, ENETDOWN] {
            try require(NinebotSocketError.operation("UDP", code).isTransient, "Unexpected session-ending errno \(code)")
        }
        for code in [EBADF, EACCES, EINVAL, EADDRINUSE] {
            try require(!NinebotSocketError.operation("UDP", code).isTransient, "Unexpected retry for errno \(code)")
        }

        let control = try InstrumentPort()
        do {
            _ = try NinebotUDPSocket(localIP: "127.0.0.1", localPort: control.port, remoteIP: "127.0.0.1", remotePort: control.port)
            throw Failure("The occupied casting port unexpectedly bound")
        } catch NinebotSocketError.operation(_, let code) {
            try require(code == EADDRINUSE, "Expected the exact bind error for the occupied port, got \(code)")
        }
        let controlSender = try NinebotUDPSocket(localIP: "127.0.0.1", localPort: 0, remoteIP: "127.0.0.1", remotePort: control.port)
        let counter: UInt32 = 0x12345678
        try controlSender.send(NinebotJPEGPacketizer.heartbeat(counter: counter))
        let request = try control.receive()
        try require(request == Data([0x85,0xcc,0,3, 0,0,0,1, 0x6d,0x70,0x76,0, 0x78,0x56,0x34,0x12]), "4.3 heartbeat bytes changed")
        try control.reply(Data([0x85,0xcc,0,4, 0,0,0,1, 0,0,0,0, 0x78,0x56,0x34,0x12, 2,0,0,0]))
        var reply: Data?
        let deadline = ProcessInfo.processInfo.systemUptime + 1
        while reply == nil, ProcessInfo.processInfo.systemUptime < deadline {
            reply = try controlSender.receive()
            if reply == nil { usleep(1_000) }
        }
        try require(reply.flatMap(NinebotH264Packetizer.heartbeatReplyCounter) == counter, "Heartbeat reply failed")

        let video = try InstrumentPort()
        let sender = try NinebotUDPSocket(localIP: "127.0.0.1", localPort: 0, remoteIP: "127.0.0.1", remotePort: video.port)
        let bounds = CGRect(x: 0, y: 0, width: 480, height: 272)
        let board = CIFilter(name: "CICheckerboardGenerator", parameters: [kCIInputWidthKey: 9])!
        let frame = board.outputImage!.cropped(to: bounds)
        let context = CIContext(options: [.useSoftwareRenderer: true])
        guard let jpeg = context.jpegRepresentation(of: frame, colorSpace: CGColorSpaceCreateDeviceRGB()) else {
            throw Failure("JPEG encoding failed")
        }
        let packets = try NinebotJPEGPacketizer(ssrc: 7).packets(for: jpeg, timestamp: 9)
        try require(packets.count > 1, "Expected a fragmented JPEG")
        var receivedJPEG = Data()
        for (index, packet) in packets.enumerated() {
            try sender.send(packet)
            let received = try video.receive()
            try require(received.count > 20 && received.count <= 1420, "Invalid packet size")
            let last = index == packets.count - 1
            let expectedHeader: [UInt8] = [
                0x90, last ? 0xa0 : 0x20, UInt8(index >> 8), UInt8(truncatingIfNeeded: index),
                0,0,0,9, 0,0,0,7, 0,0,0,1, 0,1,0,0
            ]
            try require(received.prefix(20).elementsEqual(expectedHeader), "Unexpected RTP header at fragment \(index)")
            receivedJPEG.append(received.dropFirst(20))
        }
        try require(receivedJPEG == jpeg, "Reassembled JPEG differs from the source")
        guard let source = CGImageSourceCreateWithData(receivedJPEG as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { throw Failure("JPEG decode failed") }
        try require(image.width == 480 && image.height == 272, "Incorrect dashboard resolution")
        if let localAddress = CommandLine.arguments.dropFirst().first, !localAddress.isEmpty {
            try testCarPlayVideo(localAddress: localAddress)
        } else {
            print("SKIP: production fixed-port session requires a second IPv4 address on this Mac")
        }
        print("PASS: Ninebot 4.3-inch heartbeat, temporary route errors, and 480×272 JPEG over \(packets.count) loopback UDP fragments")
    }
}
