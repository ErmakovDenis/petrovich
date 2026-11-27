import Foundation

enum Formatters {
    static let dayMonth: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ru_RU")
        f.dateFormat = "d.MM"
        return f
    }()

    static let weekDayDate: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ru_RU")
        f.dateFormat = "EEE\nd.MM"
        return f
    }()

    static let isoLocal: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        f.timeZone = .current
        return f
    }()

    static func hours(_ value: Double) -> String {
        let n = Int(value.rounded())
        return "\(n) ч"
    }

    static func liters(_ value: Double) -> String {
        let n = Int(value.rounded())
        return "\(n) л"
    }

    static func km(_ value: Double) -> String {
        let n = Int(value.rounded())
        return "\(n) км"
    }
}
