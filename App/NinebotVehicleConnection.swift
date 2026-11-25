import Foundation
import Combine
import CoreBluetooth

/// Manages owner-confirmed Ninebot pairing and the projection navigation session.
@MainActor
final class NinebotVehicleConnection: NSObject, ObservableObject {
    enum Phase: String {
        case idle, scanning, connecting, discovering, preparing, needsPairing
        case pairing, authenticating, starting, active, reconnecting, stopping
        case unavailable, failed
    }

    struct Device: Identifiable, Equatable {
        let id: UUID
        var name: String
        var isConnectable: Bool
        var detail: String
    }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var status = CWL("请扫描并选择车辆。")
    @Published private(set) var devices: [Device] = []
    @Published private(set) var isScanning = false
    @Published private(set) var garage: NinebotVehicleSelection

    var canBeginPairing: Bool {
        purpose == .pairing && phase == .needsPairing && peripheral?.state == .connected
    }
    var canScan: Bool { purpose == nil && pendingProjection == nil && peripheral == nil }
    var canChooseVehicle: Bool { canScan && !scanRequested }
    var isPairing: Bool { purpose == .pairing || scanRequested }

    private enum Purpose { case pairing, projection }

    private struct Outgoing {
        let data: Data
        var offset: Int = 0
    }

    private static let service = CBUUID(string: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private static let writeID = CBUUID(string: "6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private static let notifyID = CBUUID(string: "6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    private static let garageKey = "ninebot.vehicle.selection"

    private var central: CBCentralManager!
    private let defaults: UserDefaults
    private let keyStore = NinebotVehicleKeyStore()
    private var purpose: Purpose?
    private var attemptVehicle: NinebotVehicle?
    private var pendingProjection: NinebotVehicle?
    private var wantsSession = false
    private var scanRequested = false
    private var peripherals: [UUID: CBPeripheral] = [:]
    private var scanDetails: [UUID: DiscoveryEvidence] = [:]
    @Published private var peripheral: CBPeripheral?
    private var writeCharacteristic: CBCharacteristic?
    private var notifyCharacteristic: CBCharacteristic?
    private var crypto = V2Crypto()
    private var assembler = VehicleBLEFrameAssembler(encrypted: true, maxBufferedBytes: 1024)
    private var authParameter: Data?
    private var serialNumber: Data?
    private var nameBytes = Data()
    private var candidateKeys: [Data] = []
    // Retained across transport attempts so an uncommitted pending key cannot
    // starve the original confirmed key when a wrong key yields only bad MACs.
    private var authenticationAttempts = VehicleAuthenticationAttempts()
    private var currentKey: Data?
    private var authenticated = false
    private var outgoing: [Outgoing] = []
    private var queuedBytes = 0
    private var lastWriteProgress = DispatchTime.now().uptimeNanoseconds
    private var epoch: UInt64 = 0
    private var deadlineTask: DispatchWorkItem?
    private var retryTask: DispatchWorkItem?
    private var reconnectTask: DispatchWorkItem?
    private var writeWatchdog: DispatchWorkItem?
    private var retries = 0
    private var reconnectCount = 0
    private var pairingDeadline: UInt64 = 0
    private var disconnecting = false
    private var reconnectAfterDisconnect = false
    private var invalidPacketCount = 0
    private var dashboardExitHandled = false
    private var endingAfterExitReply = false
    private var endingAfterStopWrite = false
    private var stopWriteSubmitted = false

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        garage = defaults.data(forKey: Self.garageKey)
            .flatMap { try? JSONDecoder().decode(NinebotVehicleSelection.self, from: $0) }
            ?? NinebotVehicleSelection()
        super.init()
        if garage.selected != nil { status = CWL("启动投屏时将自动连接车辆。") }
    }

    private func prepareCentral() {
        guard central == nil else { return }
        central = CBCentralManager(delegate: self, queue: .main,
                                   options: [CBCentralManagerOptionShowPowerAlertKey: true])
    }

    func startScan() {
        guard canScan else { return }
        scanRequested = true
        prepareCentral()
        guard central.state == .poweredOn else {
            reportBluetoothState()
            return
        }
        central.stopScan()
        scanDetails.removeAll()
        devices.removeAll()
        peripherals.removeAll()
        // Some supported vehicles omit the UART service from their advertisement.
        central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        isScanning = true
        transition(.scanning, CWL("正在扫描九号车辆…"))
    }

    func stopScan() {
        scanRequested = false
        if central?.isScanning == true { central.stopScan() }
        isScanning = false
        if phase == .scanning { transition(.idle, CWL("扫描已停止。")) }
    }

    func connectForPairing(_ device: Device) {
        guard canScan else { return }
        guard central?.state == .poweredOn, let evidence = scanDetails[device.id], evidence.isEligible,
              let advertisement = evidence.advertisement, let found = peripherals[device.id] else {
            transition(.failed, CWL("尚未收到可连接的车辆广播，请重新扫描。"))
            return
        }
        let vehicle = NinebotVehicle(id: device.id, name: evidence.localName,
                                     protocolVersion: advertisement.protocolVersion,
                                     serialLength: advertisement.serialNumberLength)
        stopScan()
        begin(.pairing, vehicle: vehicle)
        connectPeripheral(found)
    }

    private func begin(_ purpose: Purpose, vehicle: NinebotVehicle) {
        self.purpose = purpose
        attemptVehicle = vehicle
        wantsSession = true
        reconnectCount = 0
        authenticationAttempts = VehicleAuthenticationAttempts()
    }

    /// Explicit UI action only. Neither state restoration nor authentication failure
    /// is permitted to call SET_PWD or generate a replacement key automatically.
    func beginPairing() {
        guard canBeginPairing, let selection = attemptVehicle, let authParameter else { return }
        do {
            let key = try keyStore.prepareCandidate(for: selection.id)
            authenticationAttempts = VehicleAuthenticationAttempts()
            candidateKeys.removeAll()
            currentKey = key
            wantsSession = true
            try crypto.setKey(nameBytes, authParameter)
            pairingDeadline = DispatchTime.now().uptimeNanoseconds + 60_000_000_000
            transition(.pairing, CWL("配对请求已发出，请在 60 秒内按 P 键确认。收到确认后会自动继续认证。"))
            try send(command: 0x5c, destination: 0x04, payload: key)
            schedulePairingRetry()
        } catch {
            fail(CWL("无法开始配对：{1}", [error.localizedDescription]), retryable: false)
        }
    }

    func selectVehicle(_ id: UUID?) {
        guard canChooseVehicle else { return }
        updateGarage { $0.select(id) }
        transition(.idle, garage.selected == nil ? CWL("已关闭自动连接。") : CWL("启动投屏时将自动连接车辆。"))
    }

    func forgetVehicle(_ id: UUID) {
        guard canChooseVehicle, garage.vehicles.contains(where: { $0.id == id }) else { return }
        do {
            try keyStore.remove(for: id)
            updateGarage { $0.remove(id) }
            transition(.idle, CWL("已删除本机配对记录。"))
        } catch {
            transition(.failed, CWL("删除配对记录失败（{1}）。", [error.localizedDescription]))
        }
    }

    private func updateGarage(_ update: (inout NinebotVehicleSelection) -> Void) {
        var changed = garage
        update(&changed)
        garage = changed
        defaults.set(try? JSONEncoder().encode(changed), forKey: Self.garageKey)
    }

    func cancelPairing() {
        stopScan()
        guard purpose == .pairing else { return }
        reconnectTask?.cancel()
        reconnectTask = nil
        if disconnecting { finishAttempt() }
        else { finishDisconnect(message: CWL("已结束车辆配对连接。")) }
    }

    func startProjection() {
        guard let vehicle = garage.selected, pendingProjection == nil else { return }
        if purpose == .projection && wantsSession { return }
        pendingProjection = vehicle
        cancelPairing()
        startPendingProjection()
    }

    private func startPendingProjection() {
        guard peripheral == nil, let vehicle = pendingProjection else { return }
        pendingProjection = nil
        begin(.projection, vehicle: vehicle)
        prepareCentral()
        if central.state == .poweredOn { reconnectVehicle() }
        else { reportBluetoothState() }
    }

    func stopProjection() {
        pendingProjection = nil
        guard purpose == .projection, wantsSession else { return }
        wantsSession = false
        reconnectTask?.cancel()
        reconnectTask = nil
        reconnectAfterDisconnect = false
        retryTask?.cancel()
        deadlineTask?.cancel()
        if disconnecting {
            finishAttempt()
            return
        }
        guard let peripheral, peripheral.state == .connected, authenticated,
              phase == .starting || phase == .active else {
            finishDisconnect(message: CWL("车辆导航连接已停止。"))
            return
        }
        // Complete a partially submitted frame before sending STOP on the byte stream.
        retainOnlyPartialWrite()
        transition(.stopping, CWL("正在通知仪表停止导航…"))
        endingAfterStopWrite = true
        stopWriteSubmitted = false
        armDeadline(seconds: 2) { connection in
            connection.finishDisconnect(message: connection.stopWriteSubmitted
                ? CWL("停止指令已发送，会话已断开。") : CWL("会话已断开；停止指令未完整发送。"))
        }
        do {
            try send(command: 0x71, destination: 0x23, payload: Data([0]))
        } catch { finishDisconnect(message: CWL("已断开；停止指令未能发送。")) }
    }

    private func transition(_ next: Phase, _ message: String) {
        phase = next
        status = message
    }

    private func upsert(_ device: Device) {
        if let index = devices.firstIndex(where: { $0.id == device.id }) {
            if devices[index] != device { devices[index] = device }
        }
        else if devices.count < 100 { devices.append(device) }
    }

    private func connectPeripheral(_ item: CBPeripheral) {
        guard central.state == .poweredOn, wantsSession, let selection = attemptVehicle,
              item.identifier == selection.id, Advertisement.canAttemptLocalAuthentication(version: selection.protocolVersion) else { return }
        resetAttempt()
        peripheral = item
        peripherals[item.identifier] = item
        item.delegate = self
        nameBytes = Data(selection.name.utf8.suffix(16))
        transition(.connecting, CWL("正在连接 {1}…", [selection.name]))
        if item.state == .disconnecting {
            disconnecting = true
            reconnectAfterDisconnect = true
            transition(.reconnecting, CWL("等待上一条蓝牙连接完全断开后再恢复会话。"))
        } else {
            // A system-connected peripheral may belong only to another app.
            // Claim it through this central before using its GATT services.
            central.connect(item)
            armDeadline(seconds: 15) { $0.fail(CWL("蓝牙连接超时。"), retryable: true) }
        }
    }

    private func discover(_ item: CBPeripheral) {
        guard isCurrent(item), !disconnecting, wantsSession else { return }
        deadlineTask?.cancel()
        transition(.discovering, CWL("正在查找指令服务和通知通道…"))
        item.discoverServices([Self.service])
        armDeadline(seconds: 12) { $0.fail(CWL("蓝牙服务发现或通知启用超时。"), retryable: true) }
    }

    private func beginPrecommunication() {
        guard wantsSession, !disconnecting, let selection = attemptVehicle else { return }
        do {
            deadlineTask?.cancel()
            assembler.reset()
            crypto = V2Crypto()
            crypto.resetSerial()
            try crypto.setKey(nameBytes, nil)
            // Read before proceeding: a locked/unavailable Keychain must not look
            // like an unpaired vehicle and trigger accidental key replacement.
            candidateKeys = authenticationAttempts.remaining(from: try keyStore.load(for: selection.id).authenticationCandidates)
            retries = 0
            transition(.preparing, CWL("正在读取车辆认证参数…"))
            try send(command: 0x5b, destination: 0x04)
            scheduleHandshakeRetry(for: .preparing)
        } catch { fail(CWL("读取认证参数失败：{1}", [error.localizedDescription]), retryable: false) }
    }

    private func beginAuthentication(with key: Data) {
        guard let authParameter, let serialNumber else { return }
        do {
            retryTask?.cancel()
            retries = 0
            currentKey = key
            try crypto.setKey(key, authParameter)
            transition(.authenticating, CWL("正在用本机保存的密钥认证…"))
            try send(command: 0x5d, destination: 0x04, payload: serialNumber)
            scheduleHandshakeRetry(for: .authenticating)
        } catch { fail(CWL("认证指令发送失败：{1}", [error.localizedDescription]), retryable: false) }
    }

    private func requestNavigation() {
        do {
            retries = 0
            transition(.starting, CWL("认证成功，正在等待仪表确认开启导航…"))
            try send(command: 0x71, destination: 0x23, payload: Data([1]))
            scheduleHandshakeRetry(for: .starting)
        } catch { fail(CWL("开启导航失败：{1}", [error.localizedDescription]), retryable: true) }
    }

    private func handle(_ frame: VehicleBLEFrame) {
        guard frame.dst == 0x3e, !disconnecting else { return }
        if frame.src == 0x23, frame.command == 0x71 {
            if frame.index == 0xff, frame.payload == Data([0]), authenticated, purpose == .projection {
                handleDashboardExit()
            } else if frame.index == 0x30, authenticated, wantsSession, purpose == .projection {
                // An asynchronous inquiry, never a start ACK and never a timer.
                do {
                    try send(command: 0x71, destination: 0x23, index: 0x30, payload: Data([1]))
                } catch { fail(CWL("回复仪表导航状态失败。"), retryable: true) }
            } else if VehicleNavigationPolicy.isNavigationStartAcknowledgement(frame) {
                if phase == .starting, authenticated, wantsSession {
                    retryTask?.cancel()
                    reconnectCount = 0
                    transition(.active, CWL("仪表已确认导航开启。"))
                }
                // STOP has the same empty ACK as START. An older START ACK can
                // arrive here, so it must never clear a still-queued STOP or be
                // described as a confirmed stop. STOP uses a bounded flush below.
            }
            return
        }
        guard frame.src == 0x04 else { return }
        do {
            switch (phase, frame.command) {
            case (.preparing, 0x5b):
                let serialLength = attemptVehicle?.serialLength ?? 14
                guard serialLength > 0, serialLength <= 64,
                      frame.payload.count >= 16 + serialLength else {
                    fail(CWL("车辆返回的认证信息长度无效。"), retryable: false)
                    return
                }
                retryTask?.cancel()
                authParameter = Data(frame.payload.prefix(16))
                serialNumber = Data(frame.payload.dropFirst(16).prefix(serialLength))
                try crypto.setAuthParam(authParameter!)
                crypto.startSerial()
                if frame.index != 0, !candidateKeys.isEmpty {
                    beginAuthentication(with: candidateKeys.removeFirst())
                } else {
                    guard purpose == .pairing else {
                        fail(CWL("此车需要重新配对。请停止投屏，再打开「车辆配对」。"), retryable: false)
                        return
                    }
                    wantsSession = false
                    transition(.needsPairing, CWL("点击「建立本机配对」，然后按一下车上的 P 键确认。"))
                }
            case (.pairing, 0x5c):
                if frame.index == 1, let key = currentKey, let selection = attemptVehicle {
                    retryTask?.cancel()
                    try keyStore.acknowledgeWrite(key, for: selection.id)
                    candidateKeys.removeAll()
                    beginAuthentication(with: key)
                }
                // Other indices mean continue waiting for physical confirmation.
            case (.authenticating, 0x5d):
                retryTask?.cancel()
                if frame.index == 1, let key = currentKey, let selection = attemptVehicle {
                    try keyStore.confirmAuthentication(key, for: selection.id)
                    authenticated = true
                    authenticationAttempts = VehicleAuthenticationAttempts()
                    candidateKeys.removeAll()
                    if purpose == .pairing {
                        updateGarage { $0.save(selection) }
                        finishDisconnect(message: CWL("配对完成。请在「已配对车辆」中选择这台车。"))
                    } else if purpose == .projection && wantsSession {
                        requestNavigation()
                    }
                } else {
                    authenticationFailed(CWL("车辆未接受保存的密钥。"))
                }
            default: break
            }
        } catch { fail(CWL("认证处理失败：{1}", [error.localizedDescription]), retryable: false) }
    }

    private func send(command: UInt8, destination: UInt8, index: UInt8 = 0, payload: Data = Data()) throws {
        guard let peripheral, peripheral.state == .connected, writeCharacteristic != nil else {
            throw SessionError.message(CWL("蓝牙发送通道尚未就绪。"))
        }
        let frame = VehicleBLEFrame(src: 0x3e, dst: destination, command: command, index: index, payload: payload)
        guard VehicleNavigationPolicy.permits(frame, pairing: purpose == .pairing && phase == .pairing,
                                             authenticated: authenticated,
                                             wantsNavigation: purpose == .projection && wantsSession) else {
            throw SessionError.message(CWL("当前车辆会话不允许发送该命令。"))
        }
        let plain = try frame.encode()
        let wire = try crypto.encrypt(plain)
        guard outgoing.count < 16, queuedBytes + wire.count <= 4096 else {
            throw SessionError.message(CWL("蓝牙发送队列拥塞。"))
        }
        let wasEmpty = outgoing.isEmpty
        outgoing.append(Outgoing(data: wire))
        queuedBytes += wire.count
        if wasEmpty { lastWriteProgress = DispatchTime.now().uptimeNanoseconds }
        pumpWrites()
    }

    private func pumpWrites() {
        guard let peripheral, peripheral.state == .connected, let characteristic = writeCharacteristic,
              !disconnecting else { return }
        let maximum = peripheral.maximumWriteValueLength(for: .withoutResponse)
        guard maximum > 0 else { fail(CWL("蓝牙协商的写入长度无效。"), retryable: false); return }
        while peripheral.canSendWriteWithoutResponse && !outgoing.isEmpty {
            var first = outgoing[0]
            let end = min(first.data.count, first.offset + maximum)
            let chunk = first.data.subdata(in: first.offset..<end)
            peripheral.writeValue(chunk, for: characteristic, type: .withoutResponse)
            first.offset = end
            queuedBytes -= chunk.count
            lastWriteProgress = DispatchTime.now().uptimeNanoseconds
            if first.offset == first.data.count { outgoing.removeFirst() }
            else { outgoing[0] = first }
        }
        writeWatchdog?.cancel()
        writeWatchdog = nil
        if !outgoing.isEmpty { scheduleWriteWatchdog() }
        else if endingAfterExitReply {
            // writeNR has no protocol ACK. Give the controller a bounded flush
            // window, then release BLE without ever setting navigation to 1 again.
            endingAfterExitReply = false
            armDeadline(seconds: 0.5) { $0.finishDisconnect(message: CWL("仪表已主动结束导航，会话已停止。")) }
        } else if endingAfterStopWrite {
            endingAfterStopWrite = false
            stopWriteSubmitted = true
            armDeadline(seconds: 0.5) { $0.finishDisconnect(message: CWL("停止指令已发送，会话已断开。")) }
        }
    }

    private func retainOnlyPartialWrite() {
        if let first = outgoing.first, first.offset > 0 {
            outgoing = [first]
            queuedBytes = first.data.count - first.offset
        } else {
            outgoing.removeAll()
            queuedBytes = 0
        }
    }

    private func handleDashboardExit() {
        // Firmware sends this report three times. Clearing intent immediately and
        // handling it once also prevents late start ACKs or reconnects reviving it.
        guard !dashboardExitHandled else { return }
        dashboardExitHandled = true
        wantsSession = false
        reconnectAfterDisconnect = false
        reconnectTask?.cancel()
        retryTask?.cancel()
        retainOnlyPartialWrite()
        transition(.stopping, CWL("仪表已主动结束导航，正在确认退出；不会自动重新开启。"))
        endingAfterExitReply = true
        armDeadline(seconds: 2) { $0.finishDisconnect(message: CWL("仪表已主动结束导航，会话已停止。")) }
        do {
            // M5 setNaviExit is writeNR, command 71/index FF with absent data.
            try send(command: 0x71, destination: 0x23, index: 0xff)
        } catch { finishDisconnect(message: CWL("仪表已主动结束导航，会话已停止。")) }
    }

    private func scheduleWriteWatchdog() {
        let captured = epoch
        let task = DispatchWorkItem { [weak self] in
            guard let self, self.epoch == captured, !self.outgoing.isEmpty else { return }
            if DispatchTime.now().uptimeNanoseconds - self.lastWriteProgress >= 8_000_000_000 {
                self.fail(CWL("蓝牙写入长时间受阻，将重新建立会话。"), retryable: true)
            } else { self.pumpWrites() }
        }
        writeWatchdog = task
        DispatchQueue.main.asyncAfter(deadline: .now() + 4, execute: task)
    }

    private func scheduleHandshakeRetry(for expected: Phase) {
        retryTask?.cancel()
        let captured = epoch
        let task = DispatchWorkItem { [weak self] in
            guard let self, self.epoch == captured, self.phase == expected,
                  self.wantsSession, !self.disconnecting else { return }
            guard self.outgoing.isEmpty else { self.scheduleHandshakeRetry(for: expected); return }
            guard self.retries < (expected == .starting ? 4 : 2) else {
                if expected == .authenticating { self.authenticationFailed(CWL("车辆密钥认证超时。")) }
                else {
                    let timeout = expected == .starting
                        ? CWL("仪表开启导航确认超时。")
                        : CWL("读取车辆认证参数超时。")
                    self.fail(timeout, retryable: true)
                }
                return
            }
            self.retries += 1
            do {
                switch expected {
                case .preparing: try self.send(command: 0x5b, destination: 0x04)
                case .authenticating:
                    guard let serial = self.serialNumber else { return }
                    try self.send(command: 0x5d, destination: 0x04, payload: serial)
                case .starting: try self.send(command: 0x71, destination: 0x23, payload: Data([1]))
                default: return
                }
                self.scheduleHandshakeRetry(for: expected)
            } catch { self.fail(CWL("蓝牙重试发送失败。"), retryable: true) }
        }
        retryTask = task
        DispatchQueue.main.asyncAfter(deadline: .now() + (expected == .starting ? 1 : 3), execute: task)
    }

    private func schedulePairingRetry() {
        retryTask?.cancel()
        let captured = epoch
        let task = DispatchWorkItem { [weak self] in
            guard let self, self.epoch == captured, self.phase == .pairing, self.wantsSession else { return }
            if DispatchTime.now().uptimeNanoseconds >= self.pairingDeadline {
                self.currentKey = nil
                self.wantsSession = false
                self.transition(.needsPairing, CWL("等待 P 键确认超时。请重新点击「建立本机配对」，并在 60 秒内按一下车上的 P 键。配对记录已保留。"))
                return
            }
            do {
                if self.outgoing.isEmpty, let key = self.currentKey {
                    try self.send(command: 0x5c, destination: 0x04, payload: key)
                }
                self.schedulePairingRetry()
            } catch { self.fail(CWL("配对发送失败，待确认密钥仍已保存。"), retryable: false) }
        }
        retryTask = task
        DispatchQueue.main.asyncAfter(deadline: .now() + 2, execute: task)
    }

    private func armDeadline(seconds: Double, action: @escaping (NinebotVehicleConnection) -> Void) {
        deadlineTask?.cancel()
        let captured = epoch
        let task = DispatchWorkItem { [weak self] in
            guard let self, self.epoch == captured else { return }
            action(self)
        }
        deadlineTask = task
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: task)
    }

    private func resetAttempt() {
        epoch &+= 1
        deadlineTask?.cancel()
        retryTask?.cancel()
        writeWatchdog?.cancel()
        deadlineTask = nil
        retryTask = nil
        writeWatchdog = nil
        outgoing.removeAll()
        queuedBytes = 0
        assembler.reset()
        crypto = V2Crypto()
        authenticated = false
        authParameter = nil
        serialNumber = nil
        currentKey = nil
        candidateKeys.removeAll()
        writeCharacteristic = nil
        notifyCharacteristic = nil
        invalidPacketCount = 0
        dashboardExitHandled = false
        endingAfterExitReply = false
        endingAfterStopWrite = false
        stopWriteSubmitted = false
        disconnecting = false
    }

    private func authenticationFailed(_ reason: String) {
        if let currentKey { authenticationAttempts.reject(currentKey) }
        if !authenticationAttempts.remaining(from: candidateKeys).isEmpty {
            fail(CWL("{1} 将重新握手尝试另一份已保存密钥。", [reason]), retryable: true)
        } else {
            fail(CWL("{1} 已停止自动重试。可在「车辆配对」重新扫描，或删除旧记录后重新配对。", [reason]), retryable: false)
        }
    }

    private func fail(_ message: String, retryable: Bool) {
        reconnectTask?.cancel()
        reconnectTask = nil
        let shouldReconnect = retryable && wantsSession && reconnectCount < 5
        transition(shouldReconnect ? .reconnecting : .failed, message)
        if !shouldReconnect { finishAttempt() }
        resetAttempt()
        reconnectAfterDisconnect = shouldReconnect
        if let peripheral, peripheral.state != .disconnected {
            disconnecting = true
            central.cancelPeripheralConnection(peripheral)
            // Never start another attempt on the same CBPeripheral until its old
            // connection has actually ended: delegates do not carry our epoch.
        } else {
            peripheral = nil
            if shouldReconnect { scheduleReconnect() }
            else { startPendingProjection() }
        }
    }

    private func finishDisconnect(message: String) {
        finishAttempt()
        resetAttempt()
        reconnectAfterDisconnect = false
        transition(.idle, message)
        if let peripheral, peripheral.state != .disconnected {
            disconnecting = true
            central.cancelPeripheralConnection(peripheral)
        } else {
            peripheral = nil
            startPendingProjection()
        }
    }

    private func finishAttempt() {
        wantsSession = false
        purpose = nil
        attemptVehicle = nil
    }

    private func scheduleReconnect() {
        guard wantsSession, central.state == .poweredOn, !disconnecting else { return }
        guard reconnectCount < 5 else {
            finishAttempt()
            transition(.failed, CWL("连续重连失败。请重新发起连接。"))
            return
        }
        reconnectTask?.cancel()
        reconnectCount += 1
        let delay = min(20.0, pow(2.0, Double(reconnectCount)))
        transition(.reconnecting, CWL("连接中断，{1} 秒后重连（{2}/5）；不会自动重新配对。", [String(Int(delay)), String(reconnectCount)]))
        let captured = epoch
        let task = DispatchWorkItem { [weak self] in
            guard let self, self.epoch == captured, self.wantsSession else { return }
            self.reconnectVehicle()
        }
        reconnectTask = task
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: task)
    }

    private func reconnectVehicle() {
        guard central.state == .poweredOn, let selection = attemptVehicle,
              Advertisement.canAttemptLocalAuthentication(version: selection.protocolVersion), wantsSession, peripheral == nil else { return }
        if let item = peripherals[selection.id] ?? central.retrievePeripherals(withIdentifiers: [selection.id]).first {
            connectPeripheral(item)
        } else {
            finishAttempt()
            transition(.failed, CWL("系统找不到已保存的车辆，请重新扫描。"))
        }
    }

    private func isCurrent(_ item: CBPeripheral) -> Bool { peripheral === item }

    private func reportBluetoothState() {
        switch central.state {
        case .poweredOff: transition(.unavailable, CWL("蓝牙已关闭，请在系统设置开启。"))
        case .unauthorized: transition(.unavailable, CWL("未获蓝牙权限，请在系统设置允许此 App 使用蓝牙。"))
        case .unsupported: transition(.unavailable, CWL("此设备不支持低功耗蓝牙。"))
        case .resetting: transition(.unavailable, CWL("系统蓝牙正在重置，等待恢复。"))
        case .unknown: transition(.unavailable, CWL("正在等待系统蓝牙就绪。"))
        case .poweredOn: break
        @unknown default: transition(.unavailable, CWL("系统蓝牙暂不可用。"))
        }
    }

    private enum SessionError: LocalizedError {
        case message(String)
        var errorDescription: String? { if case .message(let value) = self { return value }; return nil }
    }
}

extension NinebotVehicleConnection: @preconcurrency CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard central.state == .poweredOn else {
            isScanning = false
            reconnectTask?.cancel()
            resetAttempt()
            peripheral = nil
            if (purpose == .pairing && !wantsSession) || phase == .stopping { finishAttempt() }
            reportBluetoothState()
            return
        }
        if scanRequested { startScan() }
        else if wantsSession, peripheral == nil { reconnectVehicle() }
        else if pendingProjection != nil { startPendingProjection() }
        else if phase == .unavailable { transition(.idle, CWL("蓝牙已就绪。")) }
    }

    func centralManager(_ central: CBCentralManager, didDiscover item: CBPeripheral,
                        advertisementData: [String: Any], rssi _: NSNumber) {
        guard isScanning else { return }
        var evidence = scanDetails[item.identifier] ?? DiscoveryEvidence()
        let identified = evidence.merge(
            manufacturerData: advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data,
            localName: advertisementData[CBAdvertisementDataLocalNameKey] as? String,
            isConnectable: (advertisementData[CBAdvertisementDataIsConnectable] as? NSNumber)?.boolValue
        )
        guard identified, let advertisement = evidence.advertisement else {
            // Retain bounded name-first/connectability-first packets, without
            // displaying an unverified peripheral as supported or selectable.
            guard !evidence.localName.isEmpty || evidence.hasConnectableAdvertisement,
                  scanDetails[item.identifier] != nil || scanDetails.count < 100 else { return }
            scanDetails[item.identifier] = evidence
            return
        }
        if scanDetails[item.identifier] == nil && scanDetails.count >= 100 {
            guard let unverified = scanDetails.first(where: { $0.value.advertisement == nil })?.key else { return }
            scanDetails.removeValue(forKey: unverified)
        }
        if peripherals[item.identifier] == nil && peripherals.count >= 100 {
            guard let unverified = peripherals.keys.first(where: {
                $0 != peripheral?.identifier && scanDetails[$0]?.advertisement == nil
            }) else { return }
            peripherals.removeValue(forKey: unverified)
            devices.removeAll { $0.id == unverified }
        }
        peripherals[item.identifier] = item
        scanDetails[item.identifier] = evidence
        let detail: String
        if !advertisement.canAttemptLocalAuthentication { detail = CWL("尚未实现广播版本 v{1} 的握手。", [String(advertisement.protocolVersion)]) }
        else if evidence.localName.isEmpty { detail = CWL("已识别，等待设备广播名称。") }
        else if !evidence.hasConnectableAdvertisement { detail = CWL("已识别，等待可连接的广播。") }
        else { detail = CWL("已识别，可连接（广播 v{1}）。", [String(advertisement.protocolVersion)]) }
        let displayName = evidence.localName.isEmpty ? (item.name ?? CWL("未命名设备")) : evidence.localName
        upsert(Device(id: item.identifier, name: displayName,
                      isConnectable: evidence.isEligible, detail: detail))
    }

    func centralManager(_ central: CBCentralManager, didConnect item: CBPeripheral) {
        guard isCurrent(item), !disconnecting, wantsSession else {
            central.cancelPeripheralConnection(item)
            return
        }
        discover(item)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect item: CBPeripheral, error: Error?) {
        guard isCurrent(item) else { return }
        resetAttempt()
        peripheral = nil
        if wantsSession { scheduleReconnect() }
        else if purpose != nil { finishDisconnect(message: CWL("车辆蓝牙连接已断开。")) }
        else { startPendingProjection() }
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral item: CBPeripheral, error: Error?) {
        guard isCurrent(item) else { return }
        if !disconnecting && phase == .authenticating {
            authenticationFailed(CWL("认证期间连接中断。"))
            return
        }
        if !disconnecting && phase == .pairing {
            fail(CWL("配对连接已中断，已停止自动重试；待确认密钥已保留。"), retryable: false)
            return
        }
        let retry = wantsSession && (reconnectAfterDisconnect || phase != .stopping)
        resetAttempt()
        peripheral = nil
        reconnectAfterDisconnect = false
        if retry { scheduleReconnect() }
        else if purpose != nil { finishDisconnect(message: CWL("车辆蓝牙连接已断开。")) }
        else { startPendingProjection() }
    }
}

extension NinebotVehicleConnection: @preconcurrency CBPeripheralDelegate {
    func peripheral(_ item: CBPeripheral, didDiscoverServices error: Error?) {
        guard isCurrent(item), !disconnecting, phase == .discovering else { return }
        guard error == nil, let service = item.services?.first(where: { $0.uuid == Self.service }) else {
            fail(CWL("未发现九号指令服务。"), retryable: false)
            return
        }
        item.discoverCharacteristics([Self.writeID, Self.notifyID], for: service)
    }

    func peripheral(_ item: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard isCurrent(item), !disconnecting, phase == .discovering, service.uuid == Self.service else { return }
        guard error == nil,
              let write = service.characteristics?.first(where: { $0.uuid == Self.writeID }),
              write.properties.contains(.writeWithoutResponse),
              let notify = service.characteristics?.first(where: { $0.uuid == Self.notifyID }),
              notify.properties.contains(.notify) || notify.properties.contains(.indicate) else {
            fail(CWL("指令服务缺少写入或通知能力。"), retryable: false)
            return
        }
        writeCharacteristic = write
        notifyCharacteristic = notify
        if notify.isNotifying { beginPrecommunication() }
        else { item.setNotifyValue(true, for: notify) }
    }

    func peripheral(_ item: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard isCurrent(item), !disconnecting, characteristic === notifyCharacteristic else { return }
        guard error == nil, characteristic.isNotifying else {
            fail(CWL("蓝牙通知已关闭或启用失败。"), retryable: true)
            return
        }
        if phase == .discovering { beginPrecommunication() }
    }

    func peripheral(_ item: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard isCurrent(item), !disconnecting, characteristic === notifyCharacteristic else { return }
        guard error == nil, let data = characteristic.value else {
            fail(CWL("读取蓝牙通知失败。"), retryable: true)
            return
        }
        let captured = epoch
        for wire in assembler.append(data) {
            guard epoch == captured, !disconnecting else { return }
            do {
                let plain = try crypto.decrypt(wire)
                let frame = try VehicleBLEFrame.decode(plain)
                invalidPacketCount = 0
                handle(frame)
            } catch {
                invalidPacketCount += 1
                if invalidPacketCount >= 8 {
                    if phase == .authenticating { authenticationFailed(CWL("保存的密钥无法验证车辆回复。")) }
                    else { fail(CWL("连续收到无法验证的蓝牙数据，将重新认证。"), retryable: wantsSession) }
                    return
                }
            }
        }
    }

    func peripheralIsReady(toSendWriteWithoutResponse item: CBPeripheral) {
        guard isCurrent(item), !disconnecting else { return }
        pumpWrites()
    }

    func peripheral(_ item: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        guard isCurrent(item), !disconnecting, invalidatedServices.contains(where: { $0.uuid == Self.service }) else { return }
        fail(CWL("车辆指令服务已变化，将重新连接。"), retryable: true)
    }
}
