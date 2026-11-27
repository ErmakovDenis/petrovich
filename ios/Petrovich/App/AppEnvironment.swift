import SwiftUI
import UIKit

@MainActor
final class AppEnvironment: ObservableObject {
    let settings: AppSettings
    let fleet: FleetRepository
    let push: PushRegistration

    init() {
        let settings = AppSettings()
        self.settings = settings
        self.fleet = FleetRepository(settings: settings)
        self.push = PushRegistration(settings: settings)
    }

    var colors: PetrovichColors {
        switch settings.themeMode {
        case .light: return .light
        case .dark: return .dark
        case .system:
            return UITraitCollection.current.userInterfaceStyle == .dark ? .dark : .light
        }
    }
}
