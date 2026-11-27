import Foundation

enum StandError: LocalizedError {
    case http(Int, String)
    case notConfigured

    var errorDescription: String? {
        switch self {
        case .http(_, let detail): return detail
        case .notConfigured: return "Стенд не настроен"
        }
    }
}

/// HTTP к fleet_service: заголовки сессии AutoGRAPH + schema.
actor StandClient {
    private var baseURL: URL
    private var sessionToken: String
    private var schemaId: String
    private let session: URLSession
    private let decoder: JSONDecoder

    init(
        baseURL: URL,
        sessionToken: String,
        schemaId: String,
        session: URLSession = .shared
    ) {
        self.baseURL = baseURL
        self.sessionToken = sessionToken
        self.schemaId = schemaId
        self.session = session
        let dec = JSONDecoder()
        dec.keyDecodingStrategy = .convertFromSnakeCase
        dec.dateDecodingStrategy = .iso8601
        self.decoder = dec
    }

    func update(baseURL: URL, sessionToken: String, schemaId: String) {
        self.baseURL = baseURL
        self.sessionToken = sessionToken
        self.schemaId = schemaId
    }

    func get<T: Decodable>(_ path: String, query: [String: String] = [:], as type: T.Type) async throws -> T {
        let data = try await raw(path: path, method: "GET", query: query, body: nil)
        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            // fallback без convertFromSnakeCase для ответов с camelCase alias
            let plain = JSONDecoder()
            plain.dateDecodingStrategy = .iso8601
            return try plain.decode(T.self, from: data)
        }
    }

    func getData(_ path: String, query: [String: String] = [:]) async throws -> Data {
        try await raw(path: path, method: "GET", query: query, body: nil)
    }

    func postJSON<T: Decodable>(_ path: String, body: Data, as type: T.Type) async throws -> T {
        let data = try await raw(path: path, method: "POST", query: [:], body: body)
        return try decoder.decode(T.self, from: data)
    }

    func postRaw(_ path: String, body: Data) async throws -> Data {
        try await raw(path: path, method: "POST", query: [:], body: body)
    }

    private func raw(path: String, method: String, query: [String: String], body: Data?) async throws -> Data {
        guard !sessionToken.isEmpty || path.hasPrefix("health") else {
            // demo / до логина — пусть вызывающий обработает
        }
        var comps = URLComponents(url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty {
            comps.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        var req = URLRequest(url: comps.url!)
        req.httpMethod = method
        req.setValue(sessionToken, forHTTPHeaderField: "X-Session")
        req.setValue(schemaId, forHTTPHeaderField: "X-Schema-Id")
        req.setValue("Petrovich-iOS", forHTTPHeaderField: "User-Agent")
        if let body {
            req.httpBody = body
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await session.data(for: req)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            let detail = Self.detail(from: data) ?? String(data: data, encoding: .utf8) ?? "ошибка \(code)"
            throw StandError.http(code, detail)
        }
        return data
    }

    private static func detail(from data: Data) -> String? {
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        if let s = obj["detail"] as? String { return s }
        return nil
    }
}
