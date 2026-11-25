import Foundation
import XCTest
@testable import TDSCarKitCore

final class NinebotVehicleTests: XCTestCase {
    private func vehicle(_ name: String) -> NinebotVehicle {
        NinebotVehicle(id: UUID(), name: name, protocolVersion: 2, serialLength: 14)
    }

    func testOnlyAnExplicitlySelectedPairedVehicleIsRestored() throws {
        let first = vehicle("First"), second = vehicle("Second")
        var selection = NinebotVehicleSelection()
        selection.save(first)
        XCTAssertNil(selection.selected)
        selection.select(first.id)
        selection.save(second)
        selection.select(UUID())
        XCTAssertEqual(selection.selected, first)

        let stored = try JSONDecoder().decode(NinebotVehicleSelection.self, from: JSONEncoder().encode(selection))
        XCTAssertEqual(stored.vehicles, [first, second])
        XCTAssertEqual(stored.selected, first)
    }

    func testForgettingSelectedVehicleDoesNotSelectAnotherAutomatically() {
        let first = vehicle("First"), second = vehicle("Second")
        var selection = NinebotVehicleSelection()
        selection.save(first)
        selection.save(second)
        selection.select(second.id)
        selection.remove(second.id)
        XCTAssertNil(selection.selected)
        XCTAssertEqual(selection.vehicles, [first])
    }

    func testRePairingUpdatesTheExistingVehicle() {
        let original = vehicle("Original")
        let updated = NinebotVehicle(id: original.id, name: "Updated", protocolVersion: 3, serialLength: 16)
        var selection = NinebotVehicleSelection()
        selection.save(original)
        selection.select(original.id)
        selection.save(updated)
        XCTAssertEqual(selection.vehicles, [updated])
        XCTAssertEqual(selection.selected, updated)
    }

    func testPairingWriteRequiresExplicitPairingPhase() {
        let pair = VehicleBLEFrame(src: 0x3e, dst: 0x04, command: 0x5c, index: 0,
                                   payload: Data(repeating: 1, count: 32))
        XCTAssertTrue(VehicleNavigationPolicy.permits(pair, pairing: true, authenticated: false, wantsNavigation: false))
        XCTAssertFalse(VehicleNavigationPolicy.permits(pair, pairing: false, authenticated: false, wantsNavigation: true))
        XCTAssertFalse(VehicleNavigationPolicy.permits(pair, pairing: true, authenticated: true, wantsNavigation: false))
    }

    func testNavigationCommandsRequireAuthenticationAndMatchingIntent() {
        let start = VehicleBLEFrame(src: 0x3e, dst: 0x23, command: 0x71, index: 0, payload: Data([1]))
        let stop = VehicleBLEFrame(src: 0x3e, dst: 0x23, command: 0x71, index: 0, payload: Data([0]))
        let inquiryReply = VehicleBLEFrame(src: 0x3e, dst: 0x23, command: 0x71, index: 0x30, payload: Data([1]))
        let exitReply = VehicleBLEFrame(src: 0x3e, dst: 0x23, command: 0x71, index: 0xff, payload: Data())
        for frame in [start, inquiryReply] {
            XCTAssertTrue(VehicleNavigationPolicy.permits(frame, pairing: false, authenticated: true, wantsNavigation: true))
            XCTAssertFalse(VehicleNavigationPolicy.permits(frame, pairing: false, authenticated: false, wantsNavigation: true))
            XCTAssertFalse(VehicleNavigationPolicy.permits(frame, pairing: false, authenticated: true, wantsNavigation: false))
        }
        for frame in [stop, exitReply] {
            XCTAssertTrue(VehicleNavigationPolicy.permits(frame, pairing: false, authenticated: true, wantsNavigation: false))
            XCTAssertFalse(VehicleNavigationPolicy.permits(frame, pairing: false, authenticated: false, wantsNavigation: false))
            XCTAssertFalse(VehicleNavigationPolicy.permits(frame, pairing: false, authenticated: true, wantsNavigation: true))
        }
        let theme = VehicleBLEFrame(src: 0x3e, dst: 0x23, command: 0x06, index: 0xf7, payload: Data([1, 0, 1, 0]))
        XCTAssertFalse(VehicleNavigationPolicy.permits(theme, pairing: false, authenticated: true, wantsNavigation: true))
    }

    func testOnlyTheEmptyDashboardStartReplyCountsAsAcknowledgement() {
        let ack = VehicleBLEFrame(src: 0x23, dst: 0x3e, command: 0x71, index: 0, payload: Data())
        let inquiry = VehicleBLEFrame(src: 0x23, dst: 0x3e, command: 0x71, index: 0x30, payload: Data())
        let exit = VehicleBLEFrame(src: 0x23, dst: 0x3e, command: 0x71, index: 0xff, payload: Data([0]))
        XCTAssertTrue(VehicleNavigationPolicy.isNavigationStartAcknowledgement(ack))
        XCTAssertFalse(VehicleNavigationPolicy.isNavigationStartAcknowledgement(inquiry))
        XCTAssertFalse(VehicleNavigationPolicy.isNavigationStartAcknowledgement(exit))
    }

    func testNavigationStartMatchesNineCastWireVector() throws {
        let start = VehicleBLEFrame(src: 0x3e, dst: 0x3a, command: 0x71, index: 0, payload: Data([1]))
        let crypto = V2Crypto(txCounter: 5, rxCounter: 5)
        let parameter = Data(0..<16)
        try crypto.setAuthParam(parameter)
        try crypto.setKey(Data(16..<32), parameter)
        let wire = try crypto.encrypt(start.encode())
        XCTAssertEqual(wire, Data([0x5a, 0xa5, 0x01, 0x71, 0xb2, 0xff, 0x80,
                                  0x60, 0xe9, 0x73, 0x12, 0x54, 0x00, 0x06]))
        let assembler = VehicleBLEFrameAssembler(encrypted: true, maxBufferedBytes: 1024)
        XCTAssertTrue(assembler.append(Data(wire.prefix(5))).isEmpty)
        XCTAssertEqual(assembler.append(Data(wire.dropFirst(5))), [wire])
    }
}
