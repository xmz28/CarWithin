import Foundation

struct NinebotVehicle: Codable, Equatable, Identifiable {
    let id: UUID
    let name: String
    let protocolVersion: UInt8
    let serialLength: Int?
}

/// Only confirmed vehicles are listed. Pairing a new vehicle leaves the current
/// selection in place until the user picks a different one.
struct NinebotVehicleSelection: Codable, Equatable {
    private(set) var vehicles: [NinebotVehicle] = []
    private(set) var selectedID: UUID?

    var selected: NinebotVehicle? { vehicles.first { $0.id == selectedID } }

    mutating func save(_ vehicle: NinebotVehicle) {
        if let index = vehicles.firstIndex(where: { $0.id == vehicle.id }) {
            vehicles[index] = vehicle
        } else {
            vehicles.append(vehicle)
        }
    }

    mutating func select(_ id: UUID?) {
        guard id == nil || vehicles.contains(where: { $0.id == id }) else { return }
        selectedID = id
    }

    mutating func remove(_ id: UUID) {
        vehicles.removeAll { $0.id == id }
        if selectedID == id { selectedID = nil }
    }
}
