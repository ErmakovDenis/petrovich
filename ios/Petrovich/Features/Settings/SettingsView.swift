import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var push: PushRegistration
    @Environment(\.petrovichColors) private var c

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                SectionLabel(text: "Оформление")
                SurfaceCard {
                    HStack(spacing: 8) {
                        ForEach(ThemeMode.allCases) { mode in
                            PChip(title: mode.title, selected: settings.themeMode == mode) {
                                settings.themeMode = mode
                            }
                        }
                    }
                }

                SectionLabel(text: "Доклад")
                SurfaceCard {
                    HStack {
                        Text("Время").foregroundStyle(c.ink)
                        Spacer()
                        Text(settings.reportTime)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .background(c.accentSoft)
                            .foregroundStyle(c.accent)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                    }
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            ForEach(["07:00", "08:00", "09:00", "09:30", "10:00"], id: \.self) { t in
                                PChip(title: t, selected: settings.reportTime == t) {
                                    settings.reportTime = t
                                }
                            }
                        }
                    }
                }

                SectionLabel(text: "Уведомления на телефоне")
                SurfaceCard {
                    SettingsSwitchRow(
                        title: "Push с приложения",
                        subtitle: settings.telegramLinked
                            ? "Можно выключить, если события уже идут в Telegram"
                            : "Пока Telegram не подключён, push остаются на телефоне",
                        isOn: Binding(
                            get: { settings.telegramLinked ? settings.appPushEnabled : true },
                            set: { settings.appPushEnabled = $0 }
                        )
                    )
                    Divider()
                    PrimaryButton(title: push.isAuthorized ? "Push разрешены" : "Разрешить уведомления", enabled: !push.isAuthorized) {
                        Task { await push.requestAuthorization() }
                    }
                    if !push.statusText.isEmpty {
                        Text(push.statusText).font(.caption).foregroundStyle(c.muted)
                    }
                }

                SectionLabel(text: "Ещё")
                NavigationLink {
                    TelegramView()
                } label: {
                    settingsLink(title: "Telegram", subtitle: "Подключить бота и выбрать, что присылать")
                }
                NavigationLink {
                    ConnectionView()
                } label: {
                    settingsLink(title: "Подключение и данные", subtitle: "AutoGRAPH, стенд")
                }

                SoftButton(title: "Пройти знакомство заново") {
                    settings.onboarded = false
                }
                SoftButton(title: "Выйти", ink: c.high) {
                    settings.signOut()
                }
            }
            .padding(16)
        }
        .background(c.bg.ignoresSafeArea())
        .navigationTitle("Настройки")
    }

    private func settingsLink(title: String, subtitle: String) -> some View {
        SurfaceCard {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(c.ink)
                    Text(subtitle).font(.caption).foregroundStyle(c.muted)
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(c.muted)
            }
        }
    }
}
