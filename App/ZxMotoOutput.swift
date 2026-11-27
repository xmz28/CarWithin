import CoreImage
import Foundation
import VideoToolbox

struct ZxMotoProfile: Equatable {
    let width: Int
    let height: Int
    let fps: Int
    let bitrate: Int

    static let fiveInch = ZxMotoProfile(width: 800, height: 400, fps: 30, bitrate: 4_000_000)
}

final class ZxMotoOutput {
    var onLog: ((String) -> Void)?
    var onState: ((String) -> Void)?
    private let queue = DispatchQueue(label: "CarWithin.zxmoto", qos: .userInitiated)
    private var session: Session?
    private var configuration: Configuration?
    private var retryWork: DispatchWorkItem?
    private var stableWork: DispatchWorkItem?
    private var retryAttempt = 0
    private var currentToken: UUID?
    private static let maxRetries = 5

    private struct Configuration {
        let localAddress: String
        let targetAddress: String
        let port: UInt16
        let sourceWidth: Int
        let sourceHeight: Int
        let profile: ZxMotoProfile
        let passthrough: Bool
        let highBitrate: Bool
        let longKeyframe: Bool
        let performanceLog: Bool
        let onState: (String) -> Void
        let onLog: (String) -> Void
        let requestKeyframe: () -> Void
    }

    deinit { stop() }

    func start(localAddress: String, targetAddress: String, port: UInt16,
               sourceWidth: Int, sourceHeight: Int,
               profile: ZxMotoProfile = .fiveInch,
               passthrough: Bool = false,
               highBitrate: Bool = false,
               longKeyframe: Bool = false,
               performanceLog: Bool = true,
               onState: @escaping (String) -> Void,
               onLog: @escaping (String) -> Void,
               requestKeyframe: @escaping () -> Void) {
        let stateHandler: (String) -> Void = { message in
            if Thread.isMainThread { onState(message) }
            else { DispatchQueue.main.async { onState(message) } }
        }
        let configuration = Configuration(localAddress: localAddress, targetAddress: targetAddress,
                                          port: port, sourceWidth: sourceWidth, sourceHeight: sourceHeight,
                                          profile: profile, passthrough: passthrough, highBitrate: highBitrate,
                                          longKeyframe: longKeyframe, performanceLog: performanceLog,
                                          onState: stateHandler, onLog: onLog,
                                          requestKeyframe: requestKeyframe)
        queue.async { [weak self] in self?.begin(configuration) }
    }

    func append(_ data: Data, codec: VideoCodec, keyframe: Bool) {
        guard codec == .h264 else { return }
        queue.async { [weak self] in self?.session?.append(data, keyframe: keyframe) }
    }

    func stop() {
        queue.async { [weak self] in self?.stopOnQueue() }
    }

    func retryNow() {
        queue.async { [weak self] in self?.retryNowOnQueue() }
    }

    private func begin(_ configuration: Configuration) {
        stopOnQueue()
        self.configuration = configuration
        retryAttempt = 0
        launchOnQueue()
    }

    private func stopOnQueue() {
        configuration = nil
        retryWork?.cancel()
        retryWork = nil
        stableWork?.cancel()
        stableWork = nil
        retryAttempt = 0
        currentToken = nil
        guard let previous = session else { return }
        session = nil
        previous.cancel()
        queue.async { previous.close() }
    }

    private func launchOnQueue() {
        guard let configuration, session == nil else { return }
        let token = UUID()
        currentToken = token
        let next = Session(queue: queue,
                           localAddress: configuration.localAddress,
                           targetAddress: configuration.targetAddress,
                           port: configuration.port,
                           sourceWidth: configuration.sourceWidth,
                           sourceHeight: configuration.sourceHeight,
                           profile: configuration.profile,
                           passthrough: configuration.passthrough,
                           highBitrate: configuration.highBitrate,
                           longKeyframe: configuration.longKeyframe,
                           performanceLog: configuration.performanceLog,
                           onState: configuration.onState,
                           onLog: configuration.onLog,
                           requestKeyframe: configuration.requestKeyframe,
                           onConnected: { [weak self] in self?.queue.async { self?.connectedOnQueue(token) } },
                           onFinished: { [weak self] error in self?.queue.async { self?.finishedOnQueue(token, error: error) } })
        session = next
        next.start()
    }

    private func connectedOnQueue(_ token: UUID) {
        guard currentToken == token else { return }
        stableWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.currentToken == token, self.session != nil else { return }
            self.retryAttempt = 0
        }
        stableWork = work
        queue.asyncAfter(deadline: .now() + .seconds(10), execute: work)
    }

    private func finishedOnQueue(_ token: UUID, error: Error?) {
        guard currentToken == token, session != nil else { return }
        session = nil
        currentToken = nil
        stableWork?.cancel()
        stableWork = nil
        guard let configuration, error != nil else { return }
        guard retryAttempt < Self.maxRetries else {
            configuration.onState("ZxMoto 重连失败 5 次，请检查仪表 Wi-Fi")
            return
        }
        retryAttempt += 1
        let delay = min(10, 1 << (retryAttempt - 1))
        configuration.onState("ZxMoto 连接中断，\(delay) 秒后重连（第 \(retryAttempt)/5 次）")
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.retryWork = nil
            self.launchOnQueue()
        }
        retryWork = work
        queue.asyncAfter(deadline: .now() + .seconds(delay), execute: work)
    }

    private func retryNowOnQueue() {
        guard configuration != nil else { return }
        retryWork?.cancel()
        retryWork = nil
        stableWork?.cancel()
        stableWork = nil
        retryAttempt = 0
        if let previous = session {
            session = nil
            currentToken = nil
            previous.cancel()
            queue.async { previous.close() }
        }
        launchOnQueue()
    }

    private final class Session: @unchecked Sendable {
        let queue: DispatchQueue
        let onState: (String) -> Void
        let onLog: (String) -> Void
        let requestKeyframe: () -> Void
        let onConnected: () -> Void
        let onFinished: (Error?) -> Void
        private let lock = NSLock()
        private var cancelled = false
        private var acceptingFrames = true
        private var queued = 0
        private var skipping = false
        private var decoded: CVPixelBuffer?
        private var decodeError: Error?
        private var encoded: Result<H264EncodedFrame, Error>?

        private var encodeWidth: Int
        private var encodeHeight: Int
        private var encodeFPS: Int
        private var encodeBitrate: Int
        private let connection: ZxMotoConnection
        private let targetAddress: String
        private let port: UInt16
        private let passthrough: Bool
        private let highBitrate: Bool
        private let longKeyframe: Bool
        private let performanceLog: Bool
        private let metrics = ZxMotoMetrics()
        private var readyForVideo = false
        private var sentFirstFrame = false
        private var lastMetricsReport = ProcessInfo.processInfo.systemUptime
        private var decoder: ZxMotoH264Decoder?
        private var encoder: H264Encoder?
        private var parameters = VideoParameterSets(codec: .h264)
        private var waitingForIDR = true
        private var latest: CVPixelBuffer?
        private lazy var compositor = CIContext(options: [.useSoftwareRenderer: true, .cacheIntermediates: false])
        private var lastKeyframe = -Double.infinity
        private var lastCodecFailure = -Double.infinity
        private var status = ""

        init(queue: DispatchQueue, localAddress: String, targetAddress: String, port: UInt16,
             sourceWidth: Int, sourceHeight: Int,
             profile: ZxMotoProfile, passthrough: Bool, highBitrate: Bool,
             longKeyframe: Bool, performanceLog: Bool,
             onState: @escaping (String) -> Void,
             onLog: @escaping (String) -> Void, requestKeyframe: @escaping () -> Void,
             onConnected: @escaping () -> Void, onFinished: @escaping (Error?) -> Void) {
            self.queue = queue
            self.encodeWidth = profile.width
            self.encodeHeight = profile.height
            self.encodeFPS = profile.fps
            self.encodeBitrate = profile.bitrate
            self.targetAddress = targetAddress
            self.port = port
            self.passthrough = passthrough
            self.highBitrate = highBitrate
            self.longKeyframe = longKeyframe
            self.performanceLog = performanceLog
            self.connection = ZxMotoConnection(localAddress: localAddress, targetAddress: targetAddress,
                                               port: port, sourceWidth: sourceWidth, sourceHeight: sourceHeight,
                                               profile: profile, log: onLog)
            self.onState = onState
            self.onLog = onLog
            self.requestKeyframe = requestKeyframe
            self.onConnected = onConnected
            self.onFinished = onFinished
        }

        func start() {
            report("正在连接 ZxMoto 仪表 \(targetAddress):\(port)…")
            DispatchQueue.global(qos: .userInitiated).async { [self] in
                var failure: Error?
                do {
                    let size = try connection.start()
                    guard active else {
                        connection.close()
                        onFinished(nil)
                        return
                    }
                    encodeWidth = size.width
                    encodeHeight = size.height
                    encodeFPS = size.fps
                    encodeBitrate = highBitrate ? 4_000_000 : size.bitrate
                    report("仪表已连接 · \(size.width)×\(size.height)@\(size.fps)，等待 CarPlay 画面…")
                    onConnected()
                    lock.withLock { readyForVideo = true }
                    lastMetricsReport = ProcessInfo.processInfo.systemUptime
                    if performanceLog {
                        LocalDiagnosticLog.append("ZxMoto 连接 \(targetAddress):\(port) 目标 \(size.width)×\(size.height)@\(size.fps) \(encodeBitrate / 1_000) kbps 直通=\(passthrough ? 1 : 0) 高码率=\(highBitrate ? 1 : 0) 长关键帧=\(longKeyframe ? 1 : 0)")
                    }
                    if passthrough {
                        requestKeyframe()
                        var nextSize = ProcessInfo.processInfo.systemUptime
                        while active {
                            try connection.checkHealth()
                            let now = ProcessInfo.processInfo.systemUptime
                            if now >= nextSize {
                                try connection.sendWidthHeight(width: encodeWidth, height: encodeHeight)
                                nextSize = now + 2
                            }
                            reportMetricsIfNeeded(now)
                            Thread.sleep(forTimeInterval: 0.05)
                        }
                    } else {
                        var first = true
                        var nextFrame = ProcessInfo.processInfo.systemUptime
                        var nextSize = ProcessInfo.processInfo.systemUptime
                        while active {
                            try connection.checkHealth()
                            let now = ProcessInfo.processInfo.systemUptime
                            reportMetricsIfNeeded(now)
                            if now >= nextSize {
                                try connection.sendWidthHeight(width: encodeWidth, height: encodeHeight)
                                nextSize = now + 2
                            }
                            let ready = queue.sync { latest != nil }
                            if !ready {
                                Thread.sleep(forTimeInterval: 0.03)
                                continue
                            }
                            let remaining = nextFrame - now
                            if remaining > 0 {
                                Thread.sleep(forTimeInterval: min(remaining, 0.03))
                                continue
                            }
                            nextFrame = now + 1.0 / Double(max(1, encodeFPS))
                            let result = queue.sync { () -> H264EncodedFrame? in
                                guard active, let image = latest else { return nil }
                                return encode(image, at: now, forceKeyframe: first)
                            }
                            guard let result else {
                                var recovered: H264EncodedFrame?
                                for _ in 0..<20 where active && recovered == nil {
                                    Thread.sleep(forTimeInterval: 0.05)
                                    recovered = queue.sync {
                                        guard let image = latest else { return nil }
                                        return encode(image, at: ProcessInfo.processInfo.systemUptime, forceKeyframe: true)
                                    }
                                }
                                guard let recovered else { throw ZxMotoError.timeout("等待编码画面") }
                                try sendEncodedFrame(recovered)
                                first = false
                                report("正在投屏 · \(encodeWidth)×\(encodeHeight) · H.264")
                                continue
                            }
                            try sendEncodedFrame(result)
                            first = false
                            report("正在投屏 · \(encodeWidth)×\(encodeHeight) · H.264")
                        }
                    }
                } catch {
                    failure = error
                    if active {
                        let message = Self.describe(error)
                        onLog("ZxMoto 输出失败：\(message)")
                        report("ZxMoto 连接失败：\(message)")
                    }
                }
                lock.withLock { readyForVideo = false }
                connection.close()
                queue.async { [self] in close() }
                onFinished(failure)
            }
        }

        private func sendEncodedFrame(_ frame: H264EncodedFrame) throws {
            let units = try AnnexB.units(in: frame.data)
            let started = ProcessInfo.processInfo.systemUptime
            var picture: [Data] = []
            var bytes = 0
            for unit in units {
                if VideoCodec.h264.parameterSet(unit) != nil {
                    var parameterSet = Data([0, 0, 0, 1])
                    parameterSet.append(unit)
                    bytes += parameterSet.count
                    try connection.sendVideo(parameterSet)
                } else {
                    picture.append(unit)
                }
            }
            guard !picture.isEmpty else { return }
            var accessUnit = Data()
            for unit in picture {
                accessUnit.append(contentsOf: [0, 0, 0, 1])
                accessUnit.append(unit)
            }
            bytes += accessUnit.count
            try connection.sendVideo(accessUnit)
            metrics.addSend(seconds: ProcessInfo.processInfo.systemUptime - started, bytes: bytes)
        }

        private var active: Bool { lock.withLock { !cancelled } }

        private func reportMetricsIfNeeded(_ now: Double) {
            let elapsed = now - lastMetricsReport
            guard elapsed >= 5 else { return }
            lastMetricsReport = now
            let snapshot = metrics.takeSnapshot()
            guard performanceLog else { return }
            let mode = passthrough ? "直通" : "转码"
            let decode = Self.average(snapshot.decodeSeconds, snapshot.decodeFrames)
            let scale = Self.average(snapshot.scaleSeconds, snapshot.decodeFrames)
            let encode = Self.average(snapshot.encodeSeconds, snapshot.encodeFrames)
            let send = Self.average(snapshot.sendSeconds, snapshot.sentFrames)
            let fps = Double(snapshot.sentFrames) / elapsed
            let mbps = Double(snapshot.bytes) * 8 / elapsed / 1_000_000
            let line = "\(encodeWidth)×\(encodeHeight)@\(encodeFPS) \(mode) "
                + "解码 \(Self.one(decode))ms 缩放 \(Self.one(scale))ms 编码 \(Self.one(encode))ms "
                + "发送 \(Self.one(send))ms 丢帧 \(snapshot.droppedFrames) "
                + "实际 \(Self.one(fps)) FPS \(Self.two(mbps)) Mbps"
            LocalDiagnosticLog.append("ZxMoto " + line)
        }

        private static func average(_ seconds: Double, _ frames: Int) -> Double {
            frames > 0 ? seconds * 1_000 / Double(frames) : 0
        }

        private static func one(_ value: Double) -> String { String(format: "%.1f", value) }
        private static func two(_ value: Double) -> String { String(format: "%.2f", value) }

        func cancel() {
            lock.withLock { cancelled = true }
            connection.close()
        }

        func append(_ data: Data, keyframe: Bool) {
            if passthrough {
                guard lock.withLock({ !cancelled && readyForVideo }) else { return }
                queue.async { [self] in
                    guard active, lock.withLock({ readyForVideo }) else { return }
                    let started = ProcessInfo.processInfo.systemUptime
                    do {
                        try connection.sendVideo(data)
                        metrics.addSend(seconds: ProcessInfo.processInfo.systemUptime - started, bytes: data.count)
                        if !sentFirstFrame {
                            sentFirstFrame = true
                            report("正在投屏 · \(encodeWidth)×\(encodeHeight) · H.264 直通")
                        }
                    } catch {
                        onLog("ZxMoto 直通发送失败：\(error.localizedDescription)")
                    }
                }
                return
            }
            let (accepted, needsKeyframe) = lock.withLock {
                guard !cancelled, acceptingFrames else { return (false, false) }
                if queued >= 6 {
                    let request = !skipping
                    skipping = true
                    return (false, request)
                }
                guard !skipping || keyframe else { return (false, false) }
                skipping = false
                queued += 1
                return (true, false)
            }
            if needsKeyframe { requestKeyframe() }
            guard accepted else { metrics.addDrop(); return }
            queue.async { [self] in
                defer { lock.withLock { queued -= 1 } }
                guard lock.withLock({ !cancelled && acceptingFrames }) else { return }
                decode(data, keyframe: keyframe)
            }
        }

        private func decode(_ data: Data, keyframe: Bool) {
            let started = ProcessInfo.processInfo.systemUptime
            defer { metrics.addDecode(seconds: ProcessInfo.processInfo.systemUptime - started) }
            do {
                var picture: [Data] = []
                for unit in try AnnexB.units(in: data) {
                    if VideoCodec.h264.parameterSet(unit) != nil {
                        if parameters.absorb(unit) { waitingForIDR = true }
                    } else {
                        picture.append(unit)
                    }
                }
                if waitingForIDR {
                    guard parameters.isComplete, keyframe, !picture.isEmpty else { return }
                    guard ProcessInfo.processInfo.systemUptime - lastCodecFailure >= 0.5 else {
                        requestKeyframe()
                        return
                    }
                    decoder?.invalidate()
                    decoder = try ZxMotoH264Decoder(configuration: parameters.decoderConfigurationRecord(),
                        output: { [weak self] image in
                            guard let self else { return }
                            self.lock.withLock { self.decoded = image }
                        }, failure: { [weak self] error in
                            guard let self else { return }
                            self.lock.withLock { self.decodeError = error }
                        })
                    waitingForIDR = false
                }
                guard let decoder, !picture.isEmpty else { return }
                try decoder.decode(AnnexB.lengthPrefixed(picture),
                    presentationTimeMicroseconds: Int64(ProcessInfo.processInfo.systemUptime * 1_000_000),
                    isKeyframe: keyframe)
                decoder.waitForFrames()
                let (image, error) = lock.withLock {
                    defer { decoded = nil; decodeError = nil }
                    return (decoded, decodeError)
                }
                if let error { throw error }
                if let image { latest = image }
            } catch {
                decoder?.invalidate()
                decoder = nil
                lock.withLock { decoded = nil; decodeError = nil }
                waitingForIDR = true
                logCodecFailure("解码", error)
                requestKeyframe()
            }
        }

        private func encode(_ image: CVPixelBuffer, at now: Double, forceKeyframe: Bool) -> H264EncodedFrame? {
            do {
                if encoder == nil {
                    guard now - lastCodecFailure >= 0.5 else { return nil }
                    encoder = try H264Encoder(width: encodeWidth, height: encodeHeight,
                                              fps: encodeFPS, bitrate: encodeBitrate) { [weak self] result in
                        guard let self else { return }
                        self.lock.withLock { self.encoded = result }
                    }
                    lastKeyframe = -.infinity
                }
                guard let encoder else { return nil }
                let stamp = CMTime(seconds: now, preferredTimescale: 1_000_000)
                let scaleStarted = ProcessInfo.processInfo.systemUptime
                let fitted = try fittedImage(image)
                metrics.addScale(seconds: ProcessInfo.processInfo.systemUptime - scaleStarted)
                let encodeStarted = ProcessInfo.processInfo.systemUptime
                try encoder.encode(fitted, presentationTime: stamp,
                                   forceKeyframe: forceKeyframe || lastKeyframe == -.infinity)
                encoder.complete()
                let result = lock.withLock {
                    defer { encoded = nil }
                    return encoded
                }
                let frame = try result?.get()
                metrics.addEncode(seconds: ProcessInfo.processInfo.systemUptime - encodeStarted)
                if frame?.isKeyframe == true { lastKeyframe = now }
                return frame
            } catch H264EncoderError.frameDropped {
                metrics.addDrop()
                return nil
            } catch {
                encoder?.invalidate()
                encoder = nil
                lastKeyframe = -.infinity
                lock.withLock { encoded = nil }
                logCodecFailure("编码", error)
                return nil
            }
        }

        private func fittedImage(_ image: CVPixelBuffer) throws -> CVPixelBuffer {
            let width = encodeWidth, height = encodeHeight
            if CVPixelBufferGetWidth(image) == width, CVPixelBufferGetHeight(image) == height { return image }
            var buffer: CVPixelBuffer?
            let status = CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_32BGRA,
                                             [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &buffer)
            guard status == kCVReturnSuccess, let buffer else {
                throw ConfigurationError("无法分配 ZxMoto 缩放画面（\(status)）")
            }
            let source = CIImage(cvPixelBuffer: image)
            let scale = min(CGFloat(width) / source.extent.width, CGFloat(height) / source.extent.height)
            let scaled = source.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
            let positioned = scaled.transformed(by: CGAffineTransform(
                translationX: (CGFloat(width) - scaled.extent.width) / 2,
                y: (CGFloat(height) - scaled.extent.height) / 2))
            let bounds = CGRect(x: 0, y: 0, width: width, height: height)
            compositor.render(positioned.composited(over: CIImage(color: .black)).cropped(to: bounds),
                              to: buffer, bounds: bounds, colorSpace: CGColorSpaceCreateDeviceRGB())
            return buffer
        }

        private func logCodecFailure(_ stage: String, _ error: Error) {
            let now = ProcessInfo.processInfo.systemUptime
            if now - lastCodecFailure > 4 {
                onLog("ZxMoto \(stage)恢复：\(error.localizedDescription)")
            }
            lastCodecFailure = now
        }

        private static func describe(_ error: Error) -> String {
            if let zx = error as? ZxMotoError { return zx.localizedDescription }
            if let socketError = error as? ZxMotoSocketError {
                switch socketError {
                case .timeout(let text): return "等待仪表响应超时（\(text)）"
                case .closed: return "连接已关闭"
                case .system(let text, let code): return "\(text)失败（\(code)）"
                case .protocolFailure(let text): return "协议错误：\(text)"
                case .channel(let name, let error): return "\(name)：\(error.localizedDescription)"
                }
            }
            return error.localizedDescription
        }

        private func report(_ message: String) {
            guard active, message != status else { return }
            status = message
            DispatchQueue.main.async { [weak self] in
                guard let self, self.active else { return }
                self.onState(message)
            }
        }

        func close() {
            lock.withLock { acceptingFrames = false }
            connection.close()
            decoder?.invalidate()
            decoder = nil
            encoder?.invalidate()
            encoder = nil
            latest = nil
        }
    }
}

final class ZxMotoConnection: @unchecked Sendable {
    struct MediaSize: Equatable {
        let width: Int
        let height: Int
        let fps: Int
        let bitrate: Int
    }

    private static let messagePort: UInt16 = 11113
    private let localAddress: String
    private let targetAddress: String
    private let port: UInt16
    private let sourceWidth: Int
    private let sourceHeight: Int
    private let profile: ZxMotoProfile
    private let log: (String) -> Void
    private let condition = NSCondition()
    private var socket: ZxMotoSocket?
    private var messageSocket: ZxMotoSocket?
    private var closed = false
    private var failure: Error?
    private var mediaSize: MediaSize
    private var startTime: TimeInterval = 0
    private var lastHeartbeat: TimeInterval = 0
    private var lastHeartbeatLog: TimeInterval = 0
    private var heartbeatSeen = false

    init(localAddress: String, targetAddress: String, port: UInt16,
         sourceWidth: Int, sourceHeight: Int,
         profile: ZxMotoProfile, log: @escaping (String) -> Void) {
        self.localAddress = localAddress
        self.targetAddress = targetAddress
        self.port = port
        self.sourceWidth = sourceWidth
        self.sourceHeight = sourceHeight
        self.profile = profile
        self.mediaSize = MediaSize(width: profile.width, height: profile.height, fps: profile.fps,
                                   bitrate: profile.bitrate)
        self.log = log
    }

    var size: MediaSize {
        condition.lock(); defer { condition.unlock() }
        return mediaSize
    }

    func start() throws -> MediaSize {
        startMessageChannel()
        let next = try ZxMotoSocket.create()
        condition.lock()
        socket = next
        condition.unlock()
        do {
            try next.connect(localIP: localAddress, remoteIP: targetAddress, port: port, timeout: 3)
        } catch {
            next.close()
            throw error
        }
        startTime = ProcessInfo.processInfo.systemUptime
        lastHeartbeat = startTime
        let deadline = Date().addingTimeInterval(10)
        var negotiated: MediaSize?
        while !isClosed && Date() < deadline {
            try sendMirrorStart()
            do {
                let head = try next.readExactly(2, timeout: 0.5)
                if head == Data("OK".utf8) {
                    let rest = try next.readExactly(11, timeout: 2)
                    negotiated = Self.parseOK(rest, fallback: profile)
                    break
                }
                if head == Data("TP".utf8) {
                    _ = try next.readExactly(13, timeout: 2)
                    continue
                }
                if head == Data([0xAA, 0xBB]) {
                    try readPacket(socket: next)
                    continue
                }
                log("ZxMoto 握手忽略数据：\(head.map { String(format: "%02X", $0) }.joined())")
            } catch ZxMotoSocketError.timeout {
                continue
            }
        }
        guard let size = negotiated else {
            throw ZxMotoError.timeout("仪表未返回画面参数")
        }
        let now = ProcessInfo.processInfo.systemUptime
        condition.lock()
        mediaSize = size
        startTime = now
        lastHeartbeat = now
        condition.unlock()
        sendActivate()
        startReceiveLoop()
        return size
    }

    private func startMessageChannel() {
        do {
            let socket = try ZxMotoSocket.create()
            try socket.connect(localIP: localAddress, remoteIP: targetAddress,
                               port: Self.messagePort, timeout: 2)
            condition.lock()
            messageSocket = socket
            condition.unlock()
            let zone = String(TimeZone.current.secondsFromGMT() / 3600)
            let stamp = String(Int(Date().timeIntervalSince1970))
            try sendMessage("{\"fun\":\"time\",\"data\":{\"timeZone\":\"\(zone)\",\"timestamp\":\"\(stamp)\"}}",
                            over: socket)
            startMessageReceiveLoop(socket: socket)
            log("ZxMoto 消息通道已连接 \(targetAddress):\(Self.messagePort)")
        } catch {
            condition.lock()
            let socket = messageSocket
            messageSocket = nil
            condition.unlock()
            socket?.close()
            log("ZxMoto 消息通道不可用：\(error.localizedDescription)")
        }
    }

    private func sendActivate() {
        guard let socket = currentMessageSocket else { return }
        do {
            try sendMessage("{\"type\":\"1\",\"fun\":\"activate\"}", over: socket)
        } catch {
            log("ZxMoto 激活消息发送失败：\(error.localizedDescription)")
        }
    }

    private var currentMessageSocket: ZxMotoSocket? {
        condition.lock(); defer { condition.unlock() }
        return messageSocket
    }

    private func sendMessage(_ json: String, over socket: ZxMotoSocket) throws {
        let payload = Data(json.utf8)
        var frame = Data([0x45, 0x59])
        frame.append(Self.be32(UInt32(payload.count)))
        frame.append(Self.be32(0))
        frame.append(payload)
        try socket.write(frame, timeout: 3)
    }

    private func startMessageReceiveLoop(socket: ZxMotoSocket) {
        let thread = Thread { [weak self] in
            while true {
                do {
                    let head = try socket.readExactly(2, timeout: 30)
                    guard head == Data([0x45, 0x59]) else {
                        self?.log("ZxMoto 消息通道忽略 \(head.map { String(format: "%02X", $0) }.joined())")
                        continue
                    }
                    let lengths = try socket.readExactly(8, timeout: 2)
                    let bytes = [UInt8](lengths)
                    let jsonLength = Int(Self.u32be(bytes, 0))
                    let extraLength = Int(Self.u32be(bytes, 4))
                    guard jsonLength >= 0, jsonLength <= 64 * 1_024,
                          extraLength >= 0, extraLength <= 64 * 1_024 else {
                        throw ZxMotoError.protocolFailure("消息长度异常")
                    }
                    let body = try socket.readExactly(jsonLength + extraLength, timeout: 3)
                    if let text = String(data: body.prefix(jsonLength), encoding: .utf8) {
                        self?.log("ZxMoto 消息 \(text)")
                    }
                } catch ZxMotoSocketError.timeout {
                    continue
                } catch {
                    return
                }
            }
        }
        thread.name = "CarWithin.zxmoto.message"
        thread.start()
    }

    func checkHealth() throws {
        condition.lock(); defer { condition.unlock() }
        if let failure { throw failure }
        if closed { throw ZxMotoError.closed }
        if heartbeatSeen, ProcessInfo.processInfo.systemUptime - lastHeartbeat > 12 {
            throw ZxMotoError.timeout("心跳超时")
        }
    }

    func sendWidthHeight(width: Int, height: Int) throws {
        var payload = Data()
        payload.append(Self.le16(width))
        payload.append(Self.le16(height))
        try write(Self.packet(funcCode: 6, payload: payload))
    }

    func sendVideo(_ data: Data) throws {
        let elapsed = UInt32(truncatingIfNeeded: Int64((ProcessInfo.processInfo.systemUptime - startTime) * 1_000))
        var payload = Data()
        payload.append(Self.le32(elapsed))
        payload.append(data)
        try write(Self.packet(funcCode: 0, payload: payload))
    }

    func close() {
        condition.lock()
        let wasClosed = closed
        let active = socket
        let message = messageSocket
        socket = nil
        messageSocket = nil
        closed = true
        condition.broadcast()
        condition.unlock()
        guard !wasClosed else { return }
        do {
            try active?.write(Self.packet(funcCode: 5, payload: Data()), timeout: 1)
        } catch {}
        active?.close()
        message?.close()
    }

    private var isClosed: Bool {
        condition.lock(); defer { condition.unlock() }
        return closed
    }

    private func write(_ data: Data) throws {
        condition.lock()
        let current = socket
        let stopped = closed
        condition.unlock()
        guard !stopped, let current else { throw ZxMotoError.closed }
        do {
            try current.write(data, timeout: 3)
        } catch {
            fail(error)
            throw error
        }
    }

    private func sendMirrorStart() throws {
        let width = sourceWidth > 0 ? sourceWidth : profile.width
        let height = sourceHeight > 0 ? sourceHeight : profile.height
        var payload = Data()
        payload.append(Self.le16(min(width, height)))
        payload.append(Self.le16(max(width, height)))
        try write(Self.packet(funcCode: 4, payload: payload))
    }

    private func startReceiveLoop() {
        let thread = Thread { [weak self] in
            guard let self else { return }
            while true {
                do {
                    guard let current = self.currentSocket else { return }
                    let head = try current.readExactly(2, timeout: 12)
                    if head == Data("OK".utf8) {
                        _ = try current.readExactly(11, timeout: 2)
                    } else if head == Data("TP".utf8) {
                        let payload = try current.readExactly(13, timeout: 2)
                        self.log("ZxMoto 触摸 \(payload.map { String(format: "%02X", $0) }.joined())")
                    } else if head == Data([0xAA, 0xBB]) {
                        try self.readPacket(socket: current)
                    } else {
                        self.log("ZxMoto 忽略数据：\(head.map { String(format: "%02X", $0) }.joined())")
                    }
                } catch ZxMotoSocketError.timeout {
                    continue
                } catch {
                    self.fail(error)
                    return
                }
            }
        }
        thread.name = "CarWithin.zxmoto.receive"
        thread.start()
    }

    private var currentSocket: ZxMotoSocket? {
        condition.lock(); defer { condition.unlock() }
        return socket
    }

    private func readPacket(socket current: ZxMotoSocket) throws {
        let tail = try current.readExactly(2, timeout: 2)
        guard tail.count == 2 else { throw ZxMotoError.protocolFailure("包头不完整") }
        let funcCode = tail[1]
        let header = try current.readExactly(8, timeout: 2)
        let length = Self.u32le(header, 4)
        guard length <= 64 * 1_024 else { throw ZxMotoError.protocolFailure("包过大") }
        let payload = try current.readExactly(Int(length), timeout: 2)
        if funcCode == 15 {
            let now = ProcessInfo.processInfo.systemUptime
            condition.lock()
            lastHeartbeat = now
            heartbeatSeen = true
            let shouldLog = now - lastHeartbeatLog > 30
            if shouldLog { lastHeartbeatLog = now }
            condition.unlock()
            if shouldLog, let text = String(data: payload, encoding: .utf8), text.contains("ver") {
                log("ZxMoto 心跳正常")
            }
        } else {
            log("ZxMoto 忽略功能包 \(funcCode)")
        }
    }

    private func fail(_ error: Error) {
        condition.lock()
        if failure == nil { failure = error }
        let active = socket
        let message = messageSocket
        socket = nil
        messageSocket = nil
        closed = true
        condition.broadcast()
        condition.unlock()
        active?.close()
        message?.close()
    }

    private static func parseOK(_ data: Data, fallback: ZxMotoProfile) -> MediaSize {
        guard data.count >= 11 else {
            return MediaSize(width: fallback.width, height: fallback.height, fps: fallback.fps,
                             bitrate: fallback.bitrate)
        }
        let bytes = [UInt8](data)
        let landscapeWidth = Int(bytes[4]) | (Int(bytes[5]) << 8)
        let landscapeHeight = Int(bytes[6]) | (Int(bytes[7]) << 8)
        let fps = Int(bytes[8])
        let bitrateKbps = Int(bytes[9]) | (Int(bytes[10]) << 8)
        let width = even(landscapeWidth)
        let height = even(landscapeHeight)
        guard (240...1920).contains(width), (240...1080).contains(height) else {
            return MediaSize(width: fallback.width, height: fallback.height, fps: fallback.fps,
                             bitrate: fallback.bitrate)
        }
        let safeFPS = (10...60).contains(fps) ? fps : fallback.fps
        let safeBitrate = (200...20_000).contains(bitrateKbps) ? bitrateKbps * 1_000 : fallback.bitrate
        return MediaSize(width: width, height: height, fps: safeFPS, bitrate: safeBitrate)
    }

    private static func be32(_ value: UInt32) -> Data {
        Data([
            UInt8(truncatingIfNeeded: value >> 24),
            UInt8(truncatingIfNeeded: value >> 16),
            UInt8(truncatingIfNeeded: value >> 8),
            UInt8(truncatingIfNeeded: value)
        ])
    }

    private static func even(_ value: Int) -> Int {
        value % 2 == 0 ? value : value + 1
    }

    private static func packet(funcCode: UInt8, payload: Data) -> Data {
        var data = Data([0xAA, 0xBB, 0xCC, funcCode, 0, 0, 0, 0])
        data.append(le32(UInt32(payload.count)))
        data.append(payload)
        return data
    }

    private static func le16(_ value: Int) -> Data {
        Data([UInt8(truncatingIfNeeded: value), UInt8(truncatingIfNeeded: value >> 8)])
    }

    private static func le32(_ value: UInt32) -> Data {
        Data([
            UInt8(truncatingIfNeeded: value),
            UInt8(truncatingIfNeeded: value >> 8),
            UInt8(truncatingIfNeeded: value >> 16),
            UInt8(truncatingIfNeeded: value >> 24)
        ])
    }

    private static func u32be(_ bytes: [UInt8], _ offset: Int) -> UInt32 {
        guard bytes.count >= offset + 4 else { return 0 }
        return (UInt32(bytes[offset]) << 24) |
            (UInt32(bytes[offset + 1]) << 16) |
            (UInt32(bytes[offset + 2]) << 8) |
            UInt32(bytes[offset + 3])
    }

    private static func u32le(_ data: Data, _ offset: Int) -> UInt32 {
        let bytes = [UInt8](data)
        guard bytes.count >= offset + 4 else { return 0 }
        return UInt32(bytes[offset]) |
            (UInt32(bytes[offset + 1]) << 8) |
            (UInt32(bytes[offset + 2]) << 16) |
            (UInt32(bytes[offset + 3]) << 24)
    }
}

private final class ZxMotoMetrics {
    struct Snapshot {
        let decodeFrames: Int
        let decodeSeconds: Double
        let scaleSeconds: Double
        let encodeFrames: Int
        let encodeSeconds: Double
        let sentFrames: Int
        let sendSeconds: Double
        let droppedFrames: Int
        let bytes: Int
    }

    private let lock = NSLock()
    private var decodeFrames = 0
    private var decodeSeconds = 0.0
    private var scaleSeconds = 0.0
    private var encodeFrames = 0
    private var encodeSeconds = 0.0
    private var sentFrames = 0
    private var sendSeconds = 0.0
    private var droppedFrames = 0
    private var bytes = 0

    func addDecode(seconds: Double) {
        lock.lock(); decodeFrames += 1; decodeSeconds += seconds; lock.unlock()
    }

    func addScale(seconds: Double) {
        lock.lock(); scaleSeconds += seconds; lock.unlock()
    }

    func addEncode(seconds: Double) {
        lock.lock(); encodeFrames += 1; encodeSeconds += seconds; lock.unlock()
    }

    func addSend(seconds: Double, bytes count: Int) {
        lock.lock(); sentFrames += 1; sendSeconds += seconds; bytes += count; lock.unlock()
    }

    func addDrop() {
        lock.lock(); droppedFrames += 1; lock.unlock()
    }

    func takeSnapshot() -> Snapshot {
        lock.lock()
        defer { lock.unlock() }
        let snapshot = Snapshot(decodeFrames: decodeFrames, decodeSeconds: decodeSeconds,
                                scaleSeconds: scaleSeconds, encodeFrames: encodeFrames,
                                encodeSeconds: encodeSeconds, sentFrames: sentFrames,
                                sendSeconds: sendSeconds, droppedFrames: droppedFrames,
                                bytes: bytes)
        decodeFrames = 0; decodeSeconds = 0; scaleSeconds = 0
        encodeFrames = 0; encodeSeconds = 0
        sentFrames = 0; sendSeconds = 0; droppedFrames = 0; bytes = 0
        return snapshot
    }
}

enum ZxMotoError: LocalizedError {
    case protocolFailure(String)
    case timeout(String)
    case closed

    var errorDescription: String? {
        switch self {
        case .protocolFailure(let text): return "ZxMoto 协议：\(text)"
        case .timeout(let text): return "ZxMoto 等待超时：\(text)"
        case .closed: return "ZxMoto 会话已关闭"
        }
    }
}
