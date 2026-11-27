import Foundation
import UserNotifications
import UIKit
import Combine

@MainActor
final class PushRegistration: ObservableObject {
    @Published var isAuthorized = false
    @Published var statusText = ""

    private let settings: AppSettings
    private var tokenObserver: NSObjectProtocol?
    private var failObserver: NSObjectProtocol?

    init(settings: AppSettings) {
        self.settings = settings
        tokenObserver = NotificationCenter.default.addObserver(forName: .apnsTokenReceived, object: nil, queue: .main) { [weak self] note in
            guard let hex = note.object as? String else { return }
            Task { @MainActor in
                self?.settings.apnsDeviceToken = hex
                self?.statusText = "APNs токен получен (\(hex.prefix(12))…)"
                await self?.registerOnStand(token: hex)
            }
        }
        failObserver = NotificationCenter.default.addObserver(forName: .apnsRegistrationFailed, object: nil, queue: .main) { [weak self] note in
            Task { @MainActor in
                self?.statusText = "APNs: \(note.object as? String ?? "ошибка")"
            }
        }
        Task { await refreshAuthorization() }
    }

    deinit {
        if let tokenObserver { NotificationCenter.default.removeObserver(tokenObserver) }
        if let failObserver { NotificationCenter.default.removeObserver(failObserver) }
    }

    func requestAuthorization() async {
        do {
            let ok = try await UNUserNotificationCenter.current()
                .requestAuthorization(options: [.alert, .badge, .sound])
            isAuthorized = ok
            statusText = ok ? "Разрешение получено — регистрируем устройство" : "Уведомления запрещены"
            if ok {
                UIApplication.shared.registerForRemoteNotifications()
            }
        } catch {
            statusText = error.localizedDescription
        }
    }

    func refreshAuthorization() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        isAuthorized = settings.authorizationStatus == .authorized
    }

    /// Регистрация токена на стенде (волна 5). Если ручки ещё нет — ошибка остаётся в statusText.
    private func registerOnStand(token: String) async {
        guard !settings.sessionToken.isEmpty else {
            statusText = "Токен сохранён локально; на стенд отправим после входа"
            return
        }
        do {
            let url = URL(string: settings.serverURL) ?? AppConfig.standURL
            let client = StandClient(baseURL: url, sessionToken: settings.sessionToken, schemaId: settings.schemaId)
            struct Body: Encodable {
                var platform: String
                var deviceToken: String
            }
            let data = try JSONEncoder().encode(Body(platform: "ios", deviceToken: token))
            _ = try await client.postRaw("v1/devices/push-token", body: data)
            statusText = "Токен зарегистрирован на стенде"
        } catch {
            statusText = "Локально ок; стенд: \(error.localizedDescription)"
        }
    }
}
