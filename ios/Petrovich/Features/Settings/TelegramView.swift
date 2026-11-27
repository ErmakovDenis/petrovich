import SwiftUI

struct TelegramView: View {
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.petrovichColors) private var c
    @Environment(\.openURL) private var openURL

    @State private var code = ""
    @State private var status: String?
    @State private var chats: [TelegramChat] = []
    @State private var botURL: URL?
    @State private var busy = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                SurfaceCard {
                    Text("Бот «Петровича» присылает события парка в Telegram. Работает по доступу фоновой проверки стенда.")
                        .font(.caption)
                        .foregroundStyle(c.muted)
                }

                SectionLabel(text: chats.isEmpty ? "Подключить бота" : "Подключить ещё один чат")
                SurfaceCard {
                    Text("1. Откройте бота и отправьте /start.\n2. Введите здесь код, который пришлёт бот.")
                        .font(.subheadline)
                    if let botURL {
                        SoftButton(title: "Открыть @\(botURL.lastPathComponent)") {
                            openURL(botURL)
                        }
                    }
                    TextField("XXXX-XXXX", text: $code)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .padding()
                        .background(c.surface2)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                    PrimaryButton(
                        title: "Подключить",
                        enabled: !busy && code.filter(\.isLetter).count + code.filter(\.isNumber).count >= 8
                    ) {
                        Task { await pair() }
                    }
                    SoftButton(title: "Обновить список чатов") {
                        Task { await reload() }
                    }
                    if let status {
                        Text(status).font(.caption).foregroundStyle(c.muted)
                    }
                }

                if !chats.isEmpty {
                    SectionLabel(text: "Что присылать")
                    ForEach(chats) { chat in
                        SurfaceCard {
                            Text(chat.title ?? "чат \(chat.chatId)")
                                .font(.subheadline.weight(.semibold))
                            Text("\(chat.userName) · \(chat.schemaName)")
                                .font(.caption)
                                .foregroundStyle(c.muted)
                            SoftButton(title: "Отключить этот чат", ink: c.high) {
                                Task { await unlink(chat.chatId) }
                            }
                        }
                    }
                }
            }
            .padding(16)
        }
        .background(c.bg.ignoresSafeArea())
        .navigationTitle("Telegram")
        .navigationBarTitleDisplayMode(.inline)
        .task { await reload() }
    }

    private func reload() async {
        // Пока стенд не отдал чаты — UI готов; при ошибке показываем текст.
        busy = true
        defer { busy = false }
        do {
            let url = URL(string: settings.serverURL) ?? AppConfig.standURL
            let client = StandClient(baseURL: url, sessionToken: settings.sessionToken, schemaId: settings.schemaId)
            struct Resp: Decodable {
                var configured: Bool?
                var botUrl: String?
                var chats: [TelegramChat]?
            }
            let resp: Resp = try await client.get("v1/telegram/chats", as: Resp.self)
            chats = resp.chats ?? []
            botURL = resp.botUrl.flatMap(URL.init(string:))
            settings.telegramLinked = !chats.isEmpty
            status = chats.isEmpty ? "Чатов пока нет" : "Чатов: \(chats.count)"
        } catch {
            status = error.localizedDescription
        }
    }

    private func pair() async {
        busy = true
        defer { busy = false }
        do {
            let url = URL(string: settings.serverURL) ?? AppConfig.standURL
            let client = StandClient(baseURL: url, sessionToken: settings.sessionToken, schemaId: settings.schemaId)
            struct Body: Encodable { var code: String; var schemaName: String }
            let data = try JSONEncoder().encode(Body(code: code, schemaName: settings.schemaName))
            _ = try await client.postRaw("v1/telegram/pair", body: data)
            code = ""
            status = "Чат подключён"
            await reload()
        } catch {
            status = error.localizedDescription
        }
    }

    private func unlink(_ chatId: Int64) async {
        do {
            let url = URL(string: settings.serverURL) ?? AppConfig.standURL
            let client = StandClient(baseURL: url, sessionToken: settings.sessionToken, schemaId: settings.schemaId)
            _ = try await client.postRaw("v1/telegram/unlink/\(chatId)", body: Data("{}".utf8))
            await reload()
        } catch {
            status = error.localizedDescription
        }
    }
}
