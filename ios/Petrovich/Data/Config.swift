import Foundation

enum AppConfig {
    static var standURL: URL {
        if let raw = Bundle.main.object(forInfoDictionaryKey: "StandURL") as? String,
           let url = URL(string: raw), !raw.isEmpty {
            return url
        }
        return URL(string: "https://petrovichai.ru")!
    }

    static var autoGraphBaseURL: URL {
        if let raw = Bundle.main.object(forInfoDictionaryKey: "AutoGraphBaseURL") as? String,
           let url = URL(string: raw), !raw.isEmpty {
            return url
        }
        return URL(string: "https://web.tk-ekat.ru/ServiceJSON/")!
    }

    static var utcOffsetMinutes: Int {
        TimeZone.current.secondsFromGMT() / 60
    }
}
