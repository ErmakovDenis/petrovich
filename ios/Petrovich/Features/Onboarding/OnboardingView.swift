import SwiftUI

struct OnboardingView: View {
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.petrovichColors) private var c

    @State private var step = 0
    @State private var user = ""
    @State private var password = ""
    @State private var busy = false
    @State private var status: String?
    @State private var schemas: [Schema] = []
    @State private var selectedSchema: Schema?

    private let autoGraph = AutoGraphClient()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Шаг \(step + 1) из 3")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(c.muted)

                ProgressView(value: Double(step + 1), total: 3)
                    .tint(c.accent)

                switch step {
                case 0: welcome
                case 1: login
                default: finish
                }
            }
            .padding(20)
        }
        .background(c.bg.ignoresSafeArea())
    }

    private var welcome: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Петрович.")
                .font(.largeTitle.bold())
                .foregroundStyle(c.accent)
            HStack {
                Spacer()
                ZStack {
                    Circle().fill(c.accent).frame(width: 72, height: 72)
                    Text("П").font(.title.bold()).foregroundStyle(c.onAccent)
                }
                Spacer()
            }
            Text("Здравствуйте!\nЯ Петрович")
                .font(.title2.bold())
                .foregroundStyle(c.ink)
            Text("Слежу за вашим автопарком и коротко расскажу, что важно.")
                .foregroundStyle(c.muted)

            featureRow("bell.badge", "Сразу сообщу, если с машиной что-то не так")
            featureRow("play.circle", "Каждое утро — короткий доклад")
            featureRow("phone", "Позвоню и напишу сотрудникам по вашей просьбе")

            PrimaryButton(title: "Войти через АвтоГРАФ") { step = 1 }
            SoftButton(title: "Демо-режим без логина") {
                settings.completeOnboarding(demo: true)
            }
            Text("Данные берём только из вашей системы мониторинга АвтоГРАФ")
                .font(.caption)
                .foregroundStyle(c.muted)
                .frame(maxWidth: .infinity)
        }
    }

    private var login: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Вход в АвтоГРАФ")
                .font(.title2.bold())
                .foregroundStyle(c.ink)
            Text(AppConfig.autoGraphBaseURL.absoluteString)
                .font(.caption)
                .foregroundStyle(c.muted)

            TextField("Логин", text: $user)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding()
                .background(c.surface)
                .clipShape(RoundedRectangle(cornerRadius: 12))

            SecureField("Пароль", text: $password)
                .padding()
                .background(c.surface)
                .clipShape(RoundedRectangle(cornerRadius: 12))

            if let status {
                Text(status).font(.footnote).foregroundStyle(c.high)
            }

            PrimaryButton(title: busy ? "Входим…" : "Войти и загрузить схемы", enabled: !busy && !user.isEmpty) {
                Task { await signIn() }
            }

            if !schemas.isEmpty {
                SectionLabel(text: "Схема")
                ForEach(schemas) { schema in
                    Button {
                        selectedSchema = schema
                    } label: {
                        HStack {
                            Image(systemName: selectedSchema?.id == schema.id ? "largecircle.fill.circle" : "circle")
                                .foregroundStyle(c.accent)
                            Text(schema.name).foregroundStyle(c.ink)
                            Spacer()
                        }
                        .padding(.vertical, 6)
                    }
                }
                PrimaryButton(title: "Продолжить", enabled: selectedSchema != nil) {
                    guard let selectedSchema else { return }
                    settings.schemaId = selectedSchema.id
                    settings.schemaName = selectedSchema.name
                    step = 2
                }
            }

            SoftButton(title: "Назад") { step = 0 }
        }
    }

    private var finish: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Оформление")
                .font(.title2.bold())
            HStack(spacing: 8) {
                ForEach(ThemeMode.allCases) { mode in
                    PChip(title: mode.title, selected: settings.themeMode == mode) {
                        settings.themeMode = mode
                    }
                }
            }
            Text("Время доклада")
                .font(.headline)
            HStack {
                ForEach(["07:00", "08:00", "09:00", "10:00"], id: \.self) { t in
                    PChip(title: t, selected: settings.reportTime == t) {
                        settings.reportTime = t
                    }
                }
            }
            PrimaryButton(title: "Начать работу") {
                settings.demoMode = false
                settings.onboarded = true
            }
        }
    }

    private func featureRow(_ icon: String, _ text: String) -> some View {
        HStack(spacing: 12) {
            ZStack {
                Circle().fill(c.accentSoft).frame(width: 40, height: 40)
                Image(systemName: icon).foregroundStyle(c.accent)
            }
            Text(text).foregroundStyle(c.ink)
        }
    }

    private func signIn() async {
        busy = true
        status = nil
        defer { busy = false }
        do {
            let token = try await autoGraph.login(userName: user.trimmingCharacters(in: .whitespaces), password: password)
            let list = try await autoGraph.schemas(sessionToken: token)
            settings.userName = user.trimmingCharacters(in: .whitespaces)
            settings.password = password
            settings.sessionToken = token
            settings.demoMode = false
            schemas = list
            if list.count == 1 {
                selectedSchema = list[0]
                settings.schemaId = list[0].id
                settings.schemaName = list[0].name
            }
            status = "Вход выполнен. Схем: \(list.count)"
        } catch {
            status = error.localizedDescription
        }
    }
}
