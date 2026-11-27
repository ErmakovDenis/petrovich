import Foundation
import Combine

@MainActor
final class AppSettings: ObservableObject {
    @Published var onboarded: Bool {
        didSet { defaults.set(onboarded, forKey: Keys.onboarded) }
    }
    @Published var demoMode: Bool {
        didSet { defaults.set(demoMode, forKey: Keys.demoMode) }
    }
    @Published var userName: String {
        didSet {
            defaults.set(userName, forKey: Keys.userName)
            if !userName.isEmpty, let pwd = password, !pwd.isEmpty {
                KeychainStore.setPassword(pwd, account: userName)
            }
        }
    }
    @Published var password: String? {
        didSet {
            guard let password, !userName.isEmpty else { return }
            KeychainStore.setPassword(password, account: userName)
        }
    }
    @Published var schemaId: String {
        didSet { defaults.set(schemaId, forKey: Keys.schemaId) }
    }
    @Published var schemaName: String {
        didSet { defaults.set(schemaName, forKey: Keys.schemaName) }
    }
    @Published var sessionToken: String {
        didSet { defaults.set(sessionToken, forKey: Keys.sessionToken) }
    }
    @Published var serverURL: String {
        didSet { defaults.set(serverURL, forKey: Keys.serverURL) }
    }
    @Published var themeMode: ThemeMode {
        didSet { defaults.set(themeMode.rawValue, forKey: Keys.themeMode) }
    }
    @Published var reportTime: String {
        didSet { defaults.set(reportTime, forKey: Keys.reportTime) }
    }
    @Published var appPushEnabled: Bool {
        didSet { defaults.set(appPushEnabled, forKey: Keys.appPushEnabled) }
    }
    @Published var telegramLinked: Bool {
        didSet { defaults.set(telegramLinked, forKey: Keys.telegramLinked) }
    }
    @Published var mutedKinds: Set<String> {
        didSet { defaults.set(Array(mutedKinds), forKey: Keys.mutedKinds) }
    }
    @Published var apnsDeviceToken: String {
        didSet { defaults.set(apnsDeviceToken, forKey: Keys.apnsDeviceToken) }
    }

    private let defaults = UserDefaults.standard

    private enum Keys {
        static let onboarded = "onboarded"
        static let demoMode = "demoMode"
        static let userName = "userName"
        static let schemaId = "schemaId"
        static let schemaName = "schemaName"
        static let sessionToken = "sessionToken"
        static let serverURL = "serverURL"
        static let themeMode = "themeMode"
        static let reportTime = "reportTime"
        static let appPushEnabled = "appPushEnabled"
        static let telegramLinked = "telegramLinked"
        static let mutedKinds = "mutedKinds"
        static let apnsDeviceToken = "apnsDeviceToken"
    }

    init() {
        onboarded = defaults.bool(forKey: Keys.onboarded)
        demoMode = defaults.bool(forKey: Keys.demoMode)
        userName = defaults.string(forKey: Keys.userName) ?? ""
        password = userName.isEmpty ? nil : KeychainStore.password(account: userName)
        schemaId = defaults.string(forKey: Keys.schemaId) ?? ""
        schemaName = defaults.string(forKey: Keys.schemaName) ?? ""
        sessionToken = defaults.string(forKey: Keys.sessionToken) ?? ""
        serverURL = defaults.string(forKey: Keys.serverURL) ?? AppConfig.standURL.absoluteString
        themeMode = ThemeMode(rawValue: defaults.string(forKey: Keys.themeMode) ?? "") ?? .system
        reportTime = defaults.string(forKey: Keys.reportTime) ?? "09:00"
        appPushEnabled = defaults.object(forKey: Keys.appPushEnabled) as? Bool ?? true
        telegramLinked = defaults.bool(forKey: Keys.telegramLinked)
        mutedKinds = Set(defaults.stringArray(forKey: Keys.mutedKinds) ?? [])
        apnsDeviceToken = defaults.string(forKey: Keys.apnsDeviceToken) ?? ""
    }

    func completeOnboarding(demo: Bool) {
        demoMode = demo
        onboarded = true
    }

    func signOut() {
        if !userName.isEmpty { KeychainStore.deletePassword(account: userName) }
        userName = ""
        password = nil
        schemaId = ""
        schemaName = ""
        sessionToken = ""
        demoMode = false
        onboarded = false
    }
}
