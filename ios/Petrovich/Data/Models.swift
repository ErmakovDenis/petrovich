import Foundation

struct Schema: Identifiable, Codable, Hashable {
    var id: String
    var name: String
}

struct Vehicle: Identifiable, Codable, Hashable {
    var id: String
    var name: String
    var plate: String?
    var group: String?

    var listName: String {
        if let plate, !plate.isEmpty { return "\(plate) · \(name)" }
        return name
    }
}

struct Anomaly: Identifiable, Codable, Hashable {
    var id: String
    var vehicleId: String
    var vehicleName: String
    var title: String
    var kind: String
    var severity: String // high | med | low
    var status: String // new | working | closed
    var description: String
    var eventTime: Date
}

struct DailyVehicleStats: Identifiable, Codable, Hashable {
    var id: String { "\(vehicle.id)-\(day)" }
    var day: String
    var vehicle: Vehicle
    var fuel: Double
    var mileage: Double
    var idle: Double
    var refuels: Int
}

struct WidgetStatsResponse: Codable {
    var from: String
    var to: String
    var items: [DailyVehicleStats]

    enum CodingKeys: String, CodingKey {
        case from
        case to
        case items
        case fromAlias = "from_"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        from = try c.decodeIfPresent(String.self, forKey: .from)
            ?? c.decodeIfPresent(String.self, forKey: .fromAlias)
            ?? ""
        to = try c.decodeIfPresent(String.self, forKey: .to) ?? ""
        items = try c.decodeIfPresent([DailyVehicleStats].self, forKey: .items) ?? []
    }
}

struct ChatMessage: Identifiable, Hashable {
    var id = UUID()
    var role: Role
    var text: String
    var createdAt = Date()

    enum Role: String { case user, assistant, system }
}

struct TelegramChat: Identifiable, Codable, Hashable {
    var id: Int64 { chatId }
    var chatId: Int64
    var title: String?
    var userName: String
    var schemaName: String
    var linkedAt: Int64
}

enum WidgetPeriod: String, CaseIterable, Identifiable {
    case day, week, month, quarter
    var id: String { rawValue }
    var title: String {
        switch self {
        case .day: return "День"
        case .week: return "Неделя"
        case .month: return "Месяц"
        case .quarter: return "3 месяца"
        }
    }
    var bucketDays: Int {
        switch self {
        case .day: return 1
        case .week: return 7
        case .month: return 30
        case .quarter: return 90
        }
    }
}

struct WidgetPoint: Identifiable, Hashable {
    var id: String { label }
    var label: String
    var value: Double
}
