import SwiftUI

struct RootView: View {
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.petrovichColors) private var c

    var body: some View {
        Group {
            if settings.onboarded {
                MainTabView()
            } else {
                OnboardingView()
            }
        }
        .background(c.bg.ignoresSafeArea())
    }
}
