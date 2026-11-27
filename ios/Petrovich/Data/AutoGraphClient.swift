import Foundation

enum AutoGraphError: LocalizedError {
    case http(Int, String)
    case decode
    case emptySession

    var errorDescription: String? {
        switch self {
        case .http(let code, let body): return "AutoGRAPH \(code): \(body)"
        case .decode: return "Неожиданный ответ AutoGRAPH"
        case .emptySession: return "Нет сессии AutoGRAPH"
        }
    }
}

/// Минимальный клиент Login / EnumSchemas / EnumDevices.
actor AutoGraphClient {
    private let baseURL: URL
    private let session: URLSession

    init(baseURL: URL = AppConfig.autoGraphBaseURL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    func login(userName: String, password: String) async throws -> String {
        var comps = URLComponents(url: baseURL.appendingPathComponent("Login"), resolvingAgainstBaseURL: false)!
        comps.queryItems = [
            URLQueryItem(name: "UserName", value: userName),
            URLQueryItem(name: "Password", value: password),
            URLQueryItem(name: "UTCOffset", value: "\(AppConfig.utcOffsetMinutes)"),
        ]
        let (data, response) = try await session.data(from: comps.url!)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        let text = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard (200..<300).contains(code), !text.isEmpty else {
            throw AutoGraphError.http(code, text.isEmpty ? "ошибка входа" : text)
        }
        return text.trimmingCharacters(in: CharacterSet(charactersIn: "\""))
    }

    func schemas(sessionToken: String) async throws -> [Schema] {
        var comps = URLComponents(url: baseURL.appendingPathComponent("EnumSchemas"), resolvingAgainstBaseURL: false)!
        comps.queryItems = [URLQueryItem(name: "session", value: sessionToken)]
        let (data, response) = try await session.data(from: comps.url!)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            throw AutoGraphError.http(code, String(data: data, encoding: .utf8) ?? "")
        }
        // AutoGRAPH отдаёт массив объектов с полями ID/Name (и вариациями регистра).
        guard let raw = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            throw AutoGraphError.decode
        }
        return raw.compactMap { row in
            let id = (row["ID"] as? String) ?? (row["Id"] as? String) ?? (row["id"] as? String)
            let name = (row["Name"] as? String) ?? (row["name"] as? String) ?? id
            guard let id, let name else { return nil }
            return Schema(id: id, name: name)
        }
    }

    func devices(sessionToken: String, schemaId: String) async throws -> [Vehicle] {
        var comps = URLComponents(url: baseURL.appendingPathComponent("EnumDevices"), resolvingAgainstBaseURL: false)!
        comps.queryItems = [
            URLQueryItem(name: "session", value: sessionToken),
            URLQueryItem(name: "schemaID", value: schemaId),
        ]
        let (data, response) = try await session.data(from: comps.url!)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            throw AutoGraphError.http(code, String(data: data, encoding: .utf8) ?? "")
        }
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw AutoGraphError.decode
        }
        let items = (root["Items"] as? [[String: Any]])
            ?? (root["items"] as? [[String: Any]])
            ?? (root["Devices"] as? [[String: Any]])
            ?? []
        return items.compactMap { row in
            let id = stringValue(row["ID"] ?? row["Id"] ?? row["id"])
            let name = stringValue(row["Name"] ?? row["name"]) ?? id
            guard let id, let name else { return nil }
            let plate = stringValue(row["RegNumber"] ?? row["regNumber"] ?? row["Plate"] ?? row["plate"])
            let group = stringValue(row["Group"] ?? row["group"] ?? row["GroupName"])
            return Vehicle(id: id, name: name, plate: plate, group: group)
        }
    }

    private func stringValue(_ any: Any?) -> String? {
        if let s = any as? String { return s }
        if let n = any as? NSNumber { return n.stringValue }
        return nil
    }
}
