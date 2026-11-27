import SwiftUI

struct ConnectionView: View {
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var fleet: FleetRepository
    @Environment(\.petrovichColors) private var c

    @State private var user = ""
    @State private var password = ""
    @State private var serverURL = ""
    @State private var busy = false
    @State private var status: String?
    @State private var schemas: [Schema] = []

    private let autoGraph = AutoGraphClient()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                SectionLabel(text: "AutoGRAPH")
                SurfaceCard {
                    Text(AppConfig.autoGraphBaseURL.absoluteString)
                        .font(.caption)
                        .foregroundStyle(c.muted)
                    field("Логин", text: $user)
                    SecureField("Пароль", text: $password)
                        .padding()
                        .background(c.surface2)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                    PrimaryButton(title: busy ? "Входим…" : "Войти и загрузить схемы", enabled: !busy && !user.isEmpty) {
                        Task { await signIn() }
                    }
                    if let status {
                        Text(status).font(.caption).foregroundStyle(c.muted)
                    }
                    if !settings.schemaName.isEmpty {
                        Text("Текущая: \(settings.schemaName)")
                            .font(.subheadline)
                    }
                    ForEach(schemas) { schema in
                        Button {
                            settings.schemaId = schema.id
                            settings.schemaName = schema.name
                            fleet.refreshSessionBindings()
                        } label: {
                            HStack {
                                Image(systemName: settings.schemaId == schema.id ? "largecircle.fill.circle" : "circle")
                                    .foregroundStyle(c.accent)
                                Text(schema.name).foregroundStyle(c.ink)
                                Spacer()
                            }
                        }
                    }
                }

                SectionLabel(text: "Стенд")
                SurfaceCard {
                    Text("Данные, проверка машин, чат и письма идут через стенд.")
                        .font(.caption)
                        .foregroundStyle(c.muted)
                    field("Адрес стенда", text: $serverURL)
                    SoftButton(title: "Сохранить адрес", enabled: serverURL != settings.serverURL) {
                        settings.serverURL = serverURL.trimmingCharacters(in: .whitespacesAndNewlines)
                        fleet.refreshSessionBindings()
                        status = "Адрес стенда сохранён"
                    }
                }

                if settings.demoMode {
                    SurfaceCard {
                        Text("Сейчас включён демо-режим.")
                            .foregroundStyle(c.med)
                    }
                }
            }
            .padding(16)
        }
        .background(c.bg.ignoresSafeArea())
        .navigationTitle("Подключение и данные")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            user = settings.userName
            password = settings.password ?? ""
            serverURL = settings.serverURL
        }
    }

    private func field(_ title: String, text: Binding<String>) -> some View {
        TextField(title, text: text)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .padding()
            .background(c.surface2)
            .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private func signIn() async {
        busy = true
        defer { busy = false }
        do {
            let token = try await autoGraph.login(userName: user, password: password)
            let list = try await autoGraph.schemas(sessionToken: token)
            settings.userName = user
            settings.password = password
            settings.sessionToken = token
            settings.demoMode = false
            schemas = list
            if list.count == 1 {
                settings.schemaId = list[0].id
                settings.schemaName = list[0].name
            }
            fleet.refreshSessionBindings()
            status = "Вход выполнен. Схем: \(list.count)"
            await fleet.loadVehicles()
        } catch {
            status = error.localizedDescription
        }
    }
}
