import SwiftUI

struct AnomalyDetailView: View {
    @Environment(\.petrovichColors) private var c
    let anomaly: Anomaly

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                SurfaceCard {
                    Text(anomaly.severity == "high" ? "Срочно" : "Внимание")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(anomaly.severity == "high" ? c.high : c.med)
                    Text(anomaly.title)
                        .font(.title3.bold())
                        .foregroundStyle(c.ink)
                    Text(anomaly.vehicleName)
                        .font(.subheadline)
                        .foregroundStyle(c.muted)
                    Text(anomaly.eventTime.formatted(date: .long, time: .shortened))
                        .font(.caption)
                        .foregroundStyle(c.faint)
                }

                SectionLabel(text: "Что случилось")
                SurfaceCard {
                    Text(anomaly.description.isEmpty ? "Описание появится из стенда." : anomaly.description)
                        .foregroundStyle(c.ink)
                }

                SectionLabel(text: "Действия")
                NavigationLink {
                    ChatView(seed: "Разбери событие: \(anomaly.title) на \(anomaly.vehicleName)")
                } label: {
                    Text("Разобраться с Петровичем")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .frame(height: 52)
                        .foregroundStyle(c.onAccent)
                        .background(c.accent)
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
            }
            .padding(16)
        }
        .background(c.bg.ignoresSafeArea())
        .navigationTitle("Событие")
        .navigationBarTitleDisplayMode(.inline)
    }
}
