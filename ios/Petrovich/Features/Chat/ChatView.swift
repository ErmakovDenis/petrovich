import SwiftUI

struct ChatView: View {
    @EnvironmentObject private var fleet: FleetRepository
    @Environment(\.petrovichColors) private var c

    var seed: String? = nil
    @State private var messages: [ChatMessage] = [
        ChatMessage(role: .assistant, text: "Я Петрович. Спросите про простои, расход или конкретную машину.")
    ]
    @State private var draft = ""
    @State private var busy = false

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        ForEach(messages) { msg in
                            bubble(msg).id(msg.id)
                        }
                    }
                    .padding(16)
                }
                .onChange(of: messages.count) { _, _ in
                    if let last = messages.last {
                        withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                    }
                }
            }

            HStack(spacing: 8) {
                TextField("Спросить Петровича", text: $draft, axis: .vertical)
                    .lineLimit(1...4)
                    .padding(12)
                    .background(c.surface)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                Button {
                    Task { await send() }
                } label: {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 32))
                        .foregroundStyle(busy || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? c.faint : c.accent)
                }
                .disabled(busy || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding(12)
            .background(c.bg)
        }
        .background(c.bg.ignoresSafeArea())
        .navigationTitle("Петрович")
        .task {
            if let seed, messages.count == 1 {
                draft = seed
                await send()
            }
        }
    }

    private func bubble(_ msg: ChatMessage) -> some View {
        HStack {
            if msg.role == .user { Spacer(minLength: 40) }
            Text(msg.text)
                .padding(12)
                .foregroundStyle(msg.role == .user ? c.onAccent : c.ink)
                .background(msg.role == .user ? c.accent : c.surface)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            if msg.role != .user { Spacer(minLength: 40) }
        }
    }

    private func send() async {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        draft = ""
        messages.append(ChatMessage(role: .user, text: text))
        busy = true
        defer { busy = false }
        do {
            let reply = try await fleet.sendChat(message: text, history: messages)
            messages.append(ChatMessage(role: .assistant, text: reply.isEmpty ? "Пустой ответ стенда." : reply))
        } catch {
            messages.append(ChatMessage(role: .assistant, text: "Не получилось: \(error.localizedDescription)"))
        }
    }
}
