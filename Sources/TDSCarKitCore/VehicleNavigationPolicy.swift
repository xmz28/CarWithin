import Foundation

enum VehicleNavigationPolicy {
    static func permits(_ frame: VehicleBLEFrame, pairing: Bool, authenticated: Bool, wantsNavigation: Bool) -> Bool {
        guard frame.src == 0x3e else { return false }
        switch (frame.dst, frame.command, frame.index) {
        case (0x04, 0x5b, 0):
            return !authenticated && frame.payload.isEmpty
        case (0x04, 0x5c, 0):
            return pairing && !authenticated && frame.payload.count == 32
        case (0x04, 0x5d, 0):
            return !authenticated && (1...64).contains(frame.payload.count)
        case (0x23, 0x71, 0):
            return authenticated && frame.payload == Data([wantsNavigation ? 1 : 0])
        case (0x23, 0x71, 0x30):
            return authenticated && wantsNavigation && frame.payload == Data([1])
        case (0x23, 0x71, 0xff):
            return authenticated && !wantsNavigation && frame.payload.isEmpty
        default:
            return false
        }
    }

    static func isNavigationStartAcknowledgement(_ frame: VehicleBLEFrame) -> Bool {
        frame.src == 0x23 && frame.dst == 0x3e && frame.command == 0x71
            && frame.index == 0 && frame.payload.isEmpty
    }
}
