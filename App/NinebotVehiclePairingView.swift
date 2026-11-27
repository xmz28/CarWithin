import SwiftUI
import UIKit

struct NinebotVehiclePairingView: View {
    @ObservedObject var connection: NinebotVehicleConnection
    let locked: Bool
    @State private var vehicleToForget: NinebotVehicle?
    @State private var confirmsRemoval = false

    private var isNormalScanActive: Bool {
        connection.isScanning && connection.canScan
    }

    private var showsCancelInStatusCard: Bool {
        !locked && connection.isPairing && !isNormalScanActive
    }

    private var canEditVehicles: Bool {
        !locked && connection.canChooseVehicle
    }

    private var isWorkingPhase: Bool {
        switch connection.phase {
        case .scanning, .connecting, .discovering, .preparing,
             .authenticating, .starting, .reconnecting, .stopping:
            return true
        case .pairing:
            return !connection.readyForButtonPress
        case .idle, .needsPairing, .active, .unavailable, .failed:
            return false
        }
    }

    private var statusAccent: UIColor {
        if connection.readyForButtonPress {
            return .systemGreen
        }
        switch connection.phase {
        case .active:
            return .systemGreen
        case .needsPairing, .reconnecting, .unavailable:
            return .systemOrange
        case .failed:
            return .systemRed
        case .scanning, .connecting, .discovering, .preparing,
             .pairing, .authenticating, .starting, .stopping:
            return .systemBlue
        case .idle:
            return connection.garage.selected == nil ? .systemGray : .systemBlue
        }
    }

    private var headerTitle: String {
        if let active = connection.activeVehicleName {
            return active
        }
        return connection.garage.selected?.name ?? CWL("未选车辆")
    }

    private var headerSubtitle: String {
        if locked {
            return CWL("投屏运行中 · 停止后可修改")
        }
        if connection.readyForButtonPress {
            return CWL("请短按一下车上的 P 键确认")
        }
        if connection.canBeginPairing {
            return CWL("蓝牙已连接 · 等待建立配对")
        }
        switch connection.phase {
        case .scanning:
            return CWL("正在搜索附近的九号车辆")
        case .connecting, .discovering, .preparing:
            return CWL("正在与车辆建立蓝牙握手")
        case .needsPairing:
            return CWL("蓝牙已连接 · 等待建立配对")
        case .pairing:
            return CWL("等待车机就绪（约 5–8 秒）· 先别按 P 键")
        case .authenticating:
            return CWL("正在验证本机配对密钥")
        case .reconnecting:
            return CWL("正在自动重连车辆")
        case .starting:
            return CWL("正在开启仪表导航")
        case .active:
            return CWL("仪表导航已开启")
        case .stopping:
            return CWL("正在停止仪表导航")
        case .unavailable:
            return CWL("蓝牙暂不可用")
        case .failed:
            return CWL("连接或认证未完成")
        case .idle:
            if let selected = connection.garage.selected {
                return CWL("自动连接 · 蓝牙广播 v{1}", [String(selected.protocolVersion)])
            }
            return CWL("暂未选择自动连接车辆")
        }
    }

    var body: some View {
        Form {
            statusOverviewSection
            pairedVehiclesSection
            if !locked {
                nearbyVehiclesSection
                pairingGuideSection
            }
        }
        .navigationTitle(CWL("车辆配对"))
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { if !locked { connection.cancelPairing() } }
        .confirmationDialog(CWL("删除本机配对记录？"), isPresented: $confirmsRemoval, titleVisibility: .visible,
                            presenting: vehicleToForget) { vehicle in
            Button(CWL("删除 {1}", [vehicle.name]), role: .destructive) { connection.forgetVehicle(vehicle.id) }
        } message: { _ in
            Text(CWL("删除后需要重新扫描并按 P 键配对。"))
        }
    }

    // MARK: - Status Overview

    private var statusOverviewSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 12) {
                    SettingsIconBadge(symbol: "scooter", color: statusAccent, size: 40, cornerRadius: 10, symbolSize: 19)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(headerTitle)
                            .font(.headline)
                            .foregroundStyle(.primary)
                        Text(headerSubtitle)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 4)
                    if locked {
                        Image(systemName: "lock.fill")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .accessibilityLabel(CWL("已锁定"))
                    }
                }

                statusBanner

                if !locked, connection.canBeginPairing || connection.canClearStaleKey || showsCancelInStatusCard {
                    VStack(spacing: 10) {
                        if connection.canBeginPairing {
                            Button {
                                connection.beginPairing()
                            } label: {
                                Label(CWL("建立本机配对"), systemImage: "key.fill")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(PairingActionButtonStyle(kind: .primary))
                        }

                        if connection.canClearStaleKey {
                            Button {
                                connection.clearStaleKey()
                            } label: {
                                Label(CWL("清除残留配对密钥并重配"), systemImage: "trash")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(PairingActionButtonStyle(kind: .tinted(.systemRed)))
                        }

                        if showsCancelInStatusCard {
                            Button {
                                connection.cancelPairing()
                            } label: {
                                Label(CWL("取消配对"), systemImage: "xmark.circle")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(PairingActionButtonStyle(kind: .secondary))
                        }
                    }
                }
            }
            .padding(.vertical, 6)
            .animation(.easeInOut(duration: 0.2), value: connection.phase)
            .animation(.easeInOut(duration: 0.2), value: connection.readyForButtonPress)
        }
    }

    private var statusBanner: some View {
        let tint = Color(uiColor: statusAccent)
        let isReady = connection.readyForButtonPress
        let isAlert = connection.phase == .failed || connection.phase == .unavailable
        return HStack(alignment: .center, spacing: 10) {
            statusIndicator(tint: tint)
            Text(connection.status)
                .font(isReady ? .subheadline.weight(.semibold) : .footnote)
                .foregroundStyle(isReady ? tint : isAlert ? tint : Color.primary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(.vertical, 10)
        .padding(.horizontal, 12)
        .background(tint.opacity(isReady ? 0.16 : 0.10),
                    in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay {
            if isReady {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(tint.opacity(0.35), lineWidth: 1)
            }
        }
    }

    @ViewBuilder
    private func statusIndicator(tint: Color) -> some View {
        if isWorkingPhase {
            ProgressView()
                .controlSize(.small)
                .tint(tint)
        } else if connection.readyForButtonPress || connection.phase == .active {
            Image(systemName: "checkmark.circle.fill")
                .font(.body)
                .foregroundStyle(tint)
                .accessibilityHidden(true)
        } else if connection.canBeginPairing {
            Image(systemName: "hand.tap.fill")
                .font(.body)
                .foregroundStyle(tint)
                .accessibilityHidden(true)
        } else if connection.phase == .failed || connection.phase == .unavailable {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.body)
                .foregroundStyle(tint)
                .accessibilityHidden(true)
        } else {
            Circle()
                .fill(tint)
                .frame(width: 9, height: 9)
                .accessibilityHidden(true)
        }
    }

    // MARK: - Paired Vehicles

    private var pairedVehiclesSection: some View {
        Section {
            if connection.garage.vehicles.isEmpty {
                HStack(spacing: 14) {
                    SettingsIconBadge(symbol: "scooter", color: .systemGray)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(CWL("暂无已配对车辆"))
                            .font(.body)
                            .foregroundStyle(.secondary)
                        Text(CWL("在下方扫描并选择车辆完成首次配对"))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 2)
            } else {
                ForEach(connection.garage.vehicles) { vehicle in
                    let isSelected = vehicle.id == connection.garage.selected?.id
                    let subtitle = isSelected
                        ? CWL("已启用自动连接 · 蓝牙广播 v{1}", [String(vehicle.protocolVersion)])
                        : CWL("蓝牙广播 v{1}", [String(vehicle.protocolVersion)])
                    vehicleRow(
                        vehicle.name,
                        subtitle: subtitle,
                        icon: ("scooter", isSelected ? .systemBlue : .systemGray),
                        selected: isSelected
                    ) {
                        connection.selectVehicle(vehicle.id)
                    }
                    .swipeActions(allowsFullSwipe: false) {
                        if canEditVehicles {
                            Button(CWL("删除"), role: .destructive) {
                                vehicleToForget = vehicle
                                confirmsRemoval = true
                            }
                        }
                    }
                }

                vehicleRow(
                    CWL("不自动连接"),
                    subtitle: connection.garage.selected == nil ? CWL("启动投屏时不自动连接车辆蓝牙") : nil,
                    icon: ("slash.circle", .systemGray),
                    selected: connection.garage.selected == nil
                ) {
                    connection.selectVehicle(nil)
                }

                if !locked, connection.canQuickRePair {
                    Button {
                        connection.rePairSelectedVehicle()
                    } label: {
                        HStack(spacing: 14) {
                            SettingsIconBadge(symbol: "arrow.triangle.2.circlepath", color: .systemOrange)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(CWL("一键重新配对（按 P 键）"))
                                    .font(.body)
                                    .foregroundStyle(.primary)
                                if let selected = connection.garage.selected {
                                    Text(CWL("重新配对 {1}，无需删除重扫", [selected.name]))
                                        .font(.footnote)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            Spacer(minLength: 4)
                        }
                        .padding(.vertical, 2)
                        .contentShape(Rectangle())
                    }
                }
            }
        } header: {
            Text(CWL("已配对车辆"))
        } footer: {
            if locked {
                Text(CWL("停止投屏后可添加或更换车辆。"))
            } else if connection.garage.selected != nil {
                Text(CWL("轻点切换自动连接车辆，左滑可删除记录。切回九号出行连过蓝牙导致认证失败时，可直接点「一键重新配对（按 P 键）」。"))
            } else if !connection.garage.vehicles.isEmpty {
                Text(CWL("轻点选择启动投屏时自动连接的车辆，左滑可删除记录。"))
            }
        }
        .disabled(!canEditVehicles)
    }

    // MARK: - Add / Nearby Vehicles

    private var nearbyVehiclesSection: some View {
        Section {
            if isNormalScanActive {
                Button {
                    connection.cancelPairing()
                } label: {
                    HStack(spacing: 14) {
                        SettingsIconBadge(symbol: "stop.fill", color: .systemOrange)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(CWL("停止扫描"))
                                .font(.body)
                                .foregroundStyle(.primary)
                            Text(CWL("正在搜索附近九号车辆…"))
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 4)
                        ProgressView()
                    }
                    .padding(.vertical, 2)
                    .contentShape(Rectangle())
                }
            } else {
                Button {
                    connection.startScan()
                } label: {
                    HStack(spacing: 14) {
                        SettingsIconBadge(symbol: "antenna.radiowaves.left.and.right",
                                          color: connection.canScan ? .systemBlue : .systemGray)
                        Text(CWL("扫描附近车辆"))
                            .font(.body)
                            .foregroundStyle(connection.canScan ? Color.primary : Color.secondary)
                        Spacer(minLength: 4)
                    }
                    .padding(.vertical, 2)
                    .contentShape(Rectangle())
                }
                .disabled(!connection.canScan)
            }

            if connection.canScan {
                if connection.isScanning && connection.devices.isEmpty {
                    Text(CWL("暂未发现附近车辆，请确保车辆已通电并靠近车头。"))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(connection.devices) { device in
                        Button {
                            connection.connectForPairing(device)
                        } label: {
                            HStack(spacing: 14) {
                                SettingsIconBadge(symbol: "scooter",
                                                  color: device.isConnectable ? .systemTeal : .systemGray)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(device.name)
                                        .font(.body)
                                        .foregroundStyle(device.isConnectable ? Color.primary : Color.secondary)
                                    Text(device.detail)
                                        .font(.footnote)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer(minLength: 4)
                                if device.isConnectable {
                                    Text(CWL("连接"))
                                        .font(.subheadline.weight(.medium))
                                        .foregroundStyle(Color.accentColor)
                                }
                            }
                            .padding(.vertical, 2)
                            .contentShape(Rectangle())
                        }
                        .disabled(!device.isConnectable)
                    }
                }
            }
        } header: {
            Text(CWL("添加车辆"))
        } footer: {
            Text(CWL("配对前请确保车辆已通电，且未在系统蓝牙或「九号出行」中保持连接。"))
        }
    }

    // MARK: - Pairing Guide

    private var pairingGuideSection: some View {
        Section {
            guideRow(
                CWL("1. 扫描并连接车辆"),
                description: CWL("给车辆通电并靠近车头，点击「扫描附近车辆」后点选自己的车辆。"),
                icon: ("antenna.radiowaves.left.and.right", .systemBlue)
            )
            guideRow(
                CWL("2. 等待就绪再按 P 键"),
                description: CWL("发起配对后请等待约 5–8 秒，待提示「车机已就绪」并震动后再短按车上的 P 键。"),
                icon: ("hand.tap.fill", .systemGreen)
            )
            guideRow(
                CWL("3. 选中自动连接"),
                description: CWL("配对完成后在「已配对车辆」勾选车辆，启动投屏时会自动开启仪表导航。"),
                icon: ("checkmark.circle.fill", .systemTeal)
            )
        } header: {
            Text(CWL("配对步骤"))
        }
    }

    // MARK: - Row Builders

    private func vehicleRow(_ title: String, subtitle: String? = nil, icon: (String, UIColor),
                            selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                SettingsIconBadge(symbol: icon.0, color: icon.1)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(.primary)
                    if let subtitle {
                        Text(subtitle)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 4)
                if selected {
                    Image(systemName: "checkmark")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Color.accentColor)
                        .accessibilityHidden(true)
                }
            }
            .padding(.vertical, 2)
            .contentShape(Rectangle())
        }
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func guideRow(_ title: String, description: String, icon: (String, UIColor)) -> some View {
        HStack(alignment: .top, spacing: 14) {
            SettingsIconBadge(symbol: icon.0, color: icon.1)
                .padding(.top, 1)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(.primary)
                Text(description)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.vertical, 2)
    }
}

struct NinebotVehicleDiagnosticsLogView: View {
    @ObservedObject var connection: NinebotVehicleConnection
    @State private var copiedLogs = false

    var body: some View {
        Form {
            Section {
                if connection.debugLogs.isEmpty {
                    Text(CWL("暂无九号蓝牙日志。"))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    Text(connection.debugLogs.joined(separator: "\n"))
                        .font(.system(.caption2, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                }
            } header: {
                Text(CWL("九号蓝牙诊断日志"))
            } footer: {
                Text(CWL("最近 80 条，不含蓝牙配对密钥或敏感载荷。同时也会写入「诊断与实验 › 日志」。"))
            }

            if !connection.debugLogs.isEmpty {
                Section {
                    Button {
                        UIPasteboard.general.string = connection.debugLogs.joined(separator: "\n")
                        copiedLogs = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { copiedLogs = false }
                    } label: {
                        HStack(spacing: 14) {
                            SettingsIconBadge(symbol: copiedLogs ? "checkmark" : "doc.on.doc.fill",
                                              color: copiedLogs ? .systemGreen : .systemBlue)
                            Text(copiedLogs ? CWL("已复制蓝牙日志") : CWL("复制蓝牙日志"))
                                .font(.body)
                                .foregroundStyle(.primary)
                            Spacer(minLength: 4)
                        }
                        .padding(.vertical, 2)
                        .contentShape(Rectangle())
                    }

                    Button(role: .destructive) {
                        connection.clearDebugLogs()
                    } label: {
                        HStack(spacing: 14) {
                            SettingsIconBadge(symbol: "trash.fill", color: .systemRed)
                            Text(CWL("清空蓝牙日志"))
                                .font(.body)
                                .foregroundStyle(.red)
                            Spacer(minLength: 4)
                        }
                        .padding(.vertical, 2)
                        .contentShape(Rectangle())
                    }
                }
            }
        }
        .navigationTitle(CWL("九号蓝牙日志"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Rounded, colored icon tile matching `SettingsIcon` in `SettingsUI.swift`.
private struct SettingsIconBadge: View {
    let symbol: String
    let color: UIColor
    var size: CGFloat = 29
    var cornerRadius: CGFloat = 7
    var symbolSize: CGFloat = 14

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .fill(Color(uiColor: color))
            Image(systemName: symbol)
                .font(.system(size: symbolSize, weight: .semibold))
                .foregroundStyle(.white)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// Consistent full-width action buttons matching `ReceiverViewController` and `AppReleaseViews`.
private struct PairingActionButtonStyle: ButtonStyle {
    enum Kind {
        case primary
        case tinted(UIColor)
        case secondary
    }

    let kind: Kind

    private var foregroundColor: Color {
        switch kind {
        case .primary: return .white
        case .tinted(let color): return Color(uiColor: color)
        case .secondary: return .primary
        }
    }

    private var backgroundColor: Color {
        switch kind {
        case .primary: return Color(uiColor: .systemBlue)
        case .tinted(let color): return Color(uiColor: color).opacity(0.12)
        case .secondary: return Color(uiColor: .tertiarySystemFill)
        }
    }

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .multilineTextAlignment(.center)
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .frame(maxWidth: .infinity, minHeight: 44)
            .foregroundStyle(foregroundColor)
            .background(backgroundColor, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .opacity(configuration.isPressed ? 0.65 : 1)
    }
}
