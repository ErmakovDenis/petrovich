import Foundation

@MainActor
final class FleetRepository: ObservableObject {
    @Published private(set) var vehicles: [Vehicle] = []
    @Published private(set) var anomalies: [Anomaly] = []
    @Published private(set) var widgetItems: [DailyVehicleStats] = []
    @Published var lastError: String?

    private let settings: AppSettings
    private let autoGraph = AutoGraphClient()
    private var stand: StandClient

    init(settings: AppSettings) {
        self.settings = settings
        let url = URL(string: settings.serverURL) ?? AppConfig.standURL
        self.stand = StandClient(
            baseURL: url,
            sessionToken: settings.sessionToken,
            schemaId: settings.schemaId
        )
    }

    func refreshSessionBindings() {
        let url = URL(string: settings.serverURL) ?? AppConfig.standURL
        Task {
            await stand.update(baseURL: url, sessionToken: settings.sessionToken, schemaId: settings.schemaId)
        }
    }

    func loadVehicles() async {
        lastError = nil
        if settings.demoMode {
            vehicles = Self.demoVehicles
            return
        }
        do {
            if settings.sessionToken.isEmpty {
                try await ensureSession()
            }
            // Сначала стенд; при ошибке — прямой EnumDevices.
            do {
                struct VehiclesResponse: Decodable {
                    var items: [VehicleDTO]?
                    var vehicles: [VehicleDTO]?
                }
                struct VehicleDTO: Decodable {
                    var id: String
                    var name: String
                    var plate: String?
                    var group: String?
                }
                let resp: VehiclesResponse = try await stand.get("v1/vehicles", as: VehiclesResponse.self)
                let rows = resp.items ?? resp.vehicles ?? []
                vehicles = rows.map { Vehicle(id: $0.id, name: $0.name, plate: $0.plate, group: $0.group) }
                if !vehicles.isEmpty { return }
            } catch {
                // fallback AutoGRAPH
            }
            let list = try await autoGraph.devices(sessionToken: settings.sessionToken, schemaId: settings.schemaId)
            vehicles = list
        } catch {
            lastError = error.localizedDescription
            vehicles = []
        }
    }

    func loadAnomalies() async {
        lastError = nil
        if settings.demoMode {
            anomalies = Self.demoAnomalies
            return
        }
        do {
            try await ensureSession()
            struct Item: Decodable {
                var id: String
                var vehicleId: String
                var vehicleName: String?
                var title: String
                var kind: String
                var severity: String
                var status: String
                var description: String?
                var eventTime: String?
            }
            struct Resp: Decodable { var items: [Item]? }
            // Контракт может отличаться — терпим разные ключи через сырой JSON.
            let data = try await stand.getData("v1/anomalies")
            if let decoded = try? JSONDecoder().decode(Resp.self, from: data), let items = decoded.items {
                anomalies = items.map {
                    Anomaly(
                        id: $0.id,
                        vehicleId: $0.vehicleId,
                        vehicleName: $0.vehicleName ?? $0.vehicleId,
                        title: $0.title,
                        kind: $0.kind,
                        severity: $0.severity,
                        status: $0.status,
                        description: $0.description ?? "",
                        eventTime: ISO8601DateFormatter().date(from: $0.eventTime ?? "") ?? Date()
                    )
                }
            } else {
                anomalies = []
            }
        } catch {
            lastError = error.localizedDescription
            anomalies = Self.demoAnomalies.map { a in
                var copy = a
                copy.status = a.status
                return copy
            }
            // На стенде без ручки — показываем демо-ленту с пометкой в lastError.
        }
    }

    func loadWidgetStats(period: WidgetPeriod) async {
        lastError = nil
        if settings.demoMode {
            widgetItems = Self.demoWidgetItems
            return
        }
        do {
            try await ensureSession()
            let days = period.bucketDays * 2
            let to = Calendar.current.startOfDay(for: Date())
            let from = Calendar.current.date(byAdding: .day, value: -(days - 1), to: to) ?? to
            let items = try await loadWidgetStatsChunked(from: from, to: Date())
            widgetItems = items
        } catch {
            lastError = error.localizedDescription
            widgetItems = []
        }
    }

    /// Чанки по 13 суток — как Android WIDGET_STATS_CHUNK_DAYS.
    private func loadWidgetStatsChunked(from: Date, to: Date) async throws -> [DailyVehicleStats] {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "yyyy-MM-dd"
        df.timeZone = .current

        let fullFrom = df.string(from: from)
        let fullTo = df.string(from: to)
        do {
            let resp: WidgetStatsResponse = try await stand.get(
                "v1/widget-stats",
                query: ["from": fullFrom, "to": fullTo],
                as: WidgetStatsResponse.self
            )
            return resp.items
        } catch {
            var merged: [DailyVehicleStats] = []
            var cursor = from
            let cal = Calendar.current
            while cursor <= to {
                let end = min(cal.date(byAdding: .day, value: 12, to: cursor) ?? to, to)
                let resp: WidgetStatsResponse = try await stand.get(
                    "v1/widget-stats",
                    query: ["from": df.string(from: cursor), "to": df.string(from: end)],
                    as: WidgetStatsResponse.self
                )
                merged.append(contentsOf: resp.items)
                guard let next = cal.date(byAdding: .day, value: 1, to: end) else { break }
                cursor = next
            }
            return merged
        }
    }

    func sendChat(message: String, history: [ChatMessage]) async throws -> String {
        if settings.demoMode {
            return "Демо-режим: я ответил бы про парк, но сейчас без стенда. Спросите про простои или расход на реальных данных."
        }
        try await ensureSession()
        struct Req: Encodable {
            var message: String
            var history: [[String: String]]
        }
        struct Out: Decodable { var reply: String?; var text: String?; var message: String? }
        let payload = In(
            message: message,
            history: history.map { ["role": $0.role.rawValue, "content": $0.text] }
        )
        let body = try JSONEncoder().encode(payload)
        let data = try await stand.postRaw("v1/assistant/chat", body: body)
        if let out = try? JSONDecoder().decode(Out.self, from: data) {
            return out.reply ?? out.text ?? out.message ?? String(data: data, encoding: .utf8) ?? ""
        }
        return String(data: data, encoding: .utf8) ?? ""
    }

    private func ensureSession() async throws {
        if !settings.sessionToken.isEmpty { return }
        guard let password = settings.password, !settings.userName.isEmpty else {
            throw AutoGraphError.emptySession
        }
        let token = try await autoGraph.login(userName: settings.userName, password: password)
        settings.sessionToken = token
        refreshSessionBindings()
    }

    // MARK: - Demo

    static let demoVehicles: [Vehicle] = [
        Vehicle(id: "1", name: "Газ Соболь", plate: "Н184ОМ196", group: "Лёгкие"),
        Vehicle(id: "2", name: "LADA Гранта", plate: "С504НР196", group: "Легковые"),
        Vehicle(id: "3", name: "Нива", plate: "Х814МН196", group: "Легковые"),
        Vehicle(id: "4", name: "УТМ-17", plate: "В530ЕР186", group: "Спец"),
    ]

    static let demoAnomalies: [Anomaly] = [
        Anomaly(
            id: "a1", vehicleId: "2", vehicleName: "С504НР196 · LADA Гранта",
            title: "Пропуск данных: 600", kind: "gap", severity: "med", status: "new",
            description: "Нет точек телеметрии дольше обычного.", eventTime: Date().addingTimeInterval(-3600 * 30)
        ),
        Anomaly(
            id: "a2", vehicleId: "3", vehicleName: "Х814МН196 · Нива",
            title: "Перерасход топлива", kind: "fuel", severity: "high", status: "new",
            description: "Расход выше медианы парка на ≥15 %.", eventTime: Date().addingTimeInterval(-3600 * 5)
        ),
    ]

    static var demoWidgetItems: [DailyVehicleStats] {
        let cal = Calendar.current
        let vehicles = demoVehicles
        return (0..<14).flatMap { offset -> [DailyVehicleStats] in
            let day = cal.date(byAdding: .day, value: -offset, to: Date())!
            let df = DateFormatter()
            df.dateFormat = "yyyy-MM-dd"
            let key = df.string(from: day)
            return vehicles.map { v in
                DailyVehicleStats(
                    day: key, vehicle: v,
                    fuel: Double.random(in: 5...40),
                    mileage: Double.random(in: 20...180),
                    idle: Double.random(in: 0.5...8),
                    refuels: Int.random(in: 0...1)
                )
            }
        }
    }
}

enum WidgetStatsMath {
    static func total(items: [DailyVehicleStats], period: WidgetPeriod, metric: (DailyVehicleStats) -> Double) -> (value: Double, points: [WidgetPoint]) {
        let days = Set(items.map(\.day)).sorted()
        let current = Array(days.suffix(period.bucketDays))
        let points: [WidgetPoint] = {
            if period == .quarter || current.count >= 61 {
                // недельные группы
                let chunk = 7
                return stride(from: 0, to: current.count, by: chunk).map { i in
                    let slice = Array(current[i..<min(i + chunk, current.count)])
                    let sum = items.filter { slice.contains($0.day) }.map(metric).reduce(0, +)
                    let a = slice.first ?? ""
                    let b = slice.last ?? a
                    let label = "\(short(a))\n\(short(b))"
                    return WidgetPoint(label: label, value: sum)
                }
            }
            return current.map { day in
                let sum = items.filter { $0.day == day }.map(metric).reduce(0, +)
                return WidgetPoint(label: short(day), value: sum)
            }
        }()
        let value = items.filter { current.contains($0.day) }.map(metric).reduce(0, +)
        return (value, points)
    }

    private static func short(_ day: String) -> String {
        let parts = day.split(separator: "-")
        guard parts.count == 3, let d = Int(parts[2]), let m = Int(parts[1]) else { return day }
        return "\(d).\(String(format: "%02d", m))"
    }
}
