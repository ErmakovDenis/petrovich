import SwiftUI

enum ProblemsTab: String, CaseIterable, Identifiable {
    case new = "Новые"
    case working = "В работе"
    case closed = "Закрыты"
    var id: String { rawValue }
    var status: String {
        switch self {
        case .new: return "new"
        case .working: return "working"
        case .closed: return "closed"
        }
    }
}

struct ProblemsView: View {
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var fleet: FleetRepository
    @Environment(\.petrovichColors) private var c
    @State private var tab: ProblemsTab = .new

    private var items: [Anomaly] {
        fleet.anomalies
            .filter { $0.status == tab.status }
            .filter { !settings.mutedKinds.contains($0.kind) }
    }

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $tab) {
                ForEach(ProblemsTab.allCases) { t in
                    Text(t.rawValue).tag(t)
                }
            }
            .pickerStyle(.segmented)
            .padding()

            List {
                ForEach(items) { a in
                    NavigationLink {
                        AnomalyDetailView(anomaly: a)
                    } label: {
                        ProblemRow(anomaly: a)
                    }
                    .listRowBackground(c.surface)
                }
            }
            .scrollContentBackground(.hidden)
        }
        .background(c.bg.ignoresSafeArea())
        .navigationTitle("Проблемы")
        .refreshable { await fleet.loadAnomalies() }
        .task { await fleet.loadAnomalies() }
        .overlay {
            if items.isEmpty {
                ContentUnavailableView("Пусто", systemImage: "checkmark.circle", description: Text("В этой вкладке нет событий"))
            }
        }
    }
}

struct ProblemRow: View {
    @Environment(\.petrovichColors) private var c
    let anomaly: Anomaly

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(anomaly.title).font(.headline).foregroundStyle(c.ink)
            Text(anomaly.vehicleName).font(.subheadline).foregroundStyle(c.muted)
            HStack {
                Text(anomaly.severity.uppercased())
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(anomaly.severity == "high" ? c.high : c.med)
                Spacer()
                Text(anomaly.eventTime, style: .relative)
                    .font(.caption)
                    .foregroundStyle(c.faint)
            }
        }
        .padding(.vertical, 4)
    }
}
