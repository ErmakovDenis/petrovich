import SwiftUI

enum AppTab: Hashable {
    case home, park, chat, problems, settings
}

struct MainTabView: View {
    @Environment(\.petrovichColors) private var c
    @State private var tab: AppTab = .home

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack { HomeView() }
                .tabItem { Label("Главная", systemImage: "house") }
                .tag(AppTab.home)

            NavigationStack { ParkView() }
                .tabItem { Label("Парк", systemImage: "truck.box") }
                .tag(AppTab.park)

            NavigationStack { ChatView() }
                .tabItem { Label("Петрович", systemImage: "bubble.left.and.bubble.right") }
                .tag(AppTab.chat)

            NavigationStack { ProblemsView() }
                .tabItem { Label("Проблемы", systemImage: "exclamationmark.triangle") }
                .tag(AppTab.problems)

            NavigationStack { SettingsView() }
                .tabItem { Label("Настройки", systemImage: "gearshape") }
                .tag(AppTab.settings)
        }
        .tint(c.accent)
    }
}
